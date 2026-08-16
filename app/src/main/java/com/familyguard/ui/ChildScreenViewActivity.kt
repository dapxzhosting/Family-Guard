package com.familyguard.ui

import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.familyguard.databinding.ActivityChildScreenViewBinding
import com.familyguard.sync.FamilyLink
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/**
 * Layar orang tua untuk MEMANTAU layar HP anak secara real-time lewat WebRTC
 * (video langsung, 30fps+), dengan toggle "Mode Kontrol": kalau OFF, ini murni
 * tampilan (view-only). Kalau ON, setiap tap/geser di renderer diteruskan
 * sebagai sentuhan SUNGGUHAN di HP anak (lewat AccessibilityService.dispatchGesture
 * di sisi anak).
 *
 * Sebelumnya activity ini decode Base64 JPEG dari Firebase RTDB tiap frame
 * (~4fps, delay terasa). Sekarang cuma jadi WebRTC "answerer": terima SDP
 * offer dari HP anak lewat FamilyLink (signaling via RTDB), balas answer,
 * tukar ICE candidate, lalu video mengalir langsung P2P ke SurfaceViewRenderer.
 */
class ChildScreenViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChildScreenViewBinding
    private var controlModeOn = false

    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

    // Resolusi ASLI layar HP anak -- dipakai untuk menormalisasi koordinat tap.
    // Diisi manual dari RemoteControlState kalau tersedia, kalau tidak fallback
    // ke rasio video yang diterima.
    private var remoteWidth = 1080
    private var remoteHeight = 2400

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChildScreenViewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRenderer()
        setupWebRtc()

        // Minta HP anak mulai membagikan layarnya begitu activity ini dibuka.
        FamilyLink.sendRequestScreenShare(this)
        binding.tvStatus.text = "Meminta izin ke HP anak…"

        binding.switchControlMode.setOnCheckedChangeListener { _, isChecked ->
            controlModeOn = isChecked
            binding.controlBar.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
            Toast.makeText(
                this,
                if (isChecked) "Mode Kontrol AKTIF — sentuhan akan diteruskan ke HP anak"
                else "Mode Kontrol nonaktif — hanya melihat",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnRemoteBack.setOnClickListener {
            sendControlCommand(org.json.JSONObject().apply { put("type", "remote_back") })
        }

        setupRemoteTouchHandling()
    }

    private fun setupRenderer() {
        eglBase = EglBase.create()
        binding.rendererScreen.init(eglBase!!.eglBaseContext, null)
        binding.rendererScreen.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        binding.rendererScreen.setMirror(false)
    }

    private var controlChannel: org.webrtc.DataChannel? = null

    private fun setupWebRtc() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .createInitializationOptions()
        )
        val factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
            .createPeerConnectionFactory()
        peerConnectionFactory = factory

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                FamilyLink.sendIceCandidate(
                    this@ChildScreenViewActivity, fromChild = false,
                    sdpMid = candidate.sdpMid, sdpMLineIndex = candidate.sdpMLineIndex,
                    candidate = candidate.sdp
                )
            }
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) {
                    runOnUiThread {
                        track.addSink(binding.rendererScreen)
                        binding.progressLoading.visibility = android.view.View.GONE
                        binding.tvStatus.text = "Terhubung • video real-time"
                    }
                }
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: org.webrtc.DataChannel?) {
                // HP anak yang membuat data channel "control" -- kita tinggal
                // dengarkan supaya tahu kapan siap dipakai untuk kirim command.
                if (channel?.label() == "control") {
                    controlChannel = channel
                    channel.registerObserver(object : org.webrtc.DataChannel.Observer {
                        override fun onBufferedAmountChange(amount: Long) {}
                        override fun onStateChange() {
                            runOnUiThread {
                                if (channel.state() == org.webrtc.DataChannel.State.OPEN) {
                                    binding.tvStatus.text = "Terhubung • video real-time • kontrol cepat aktif"
                                }
                            }
                        }
                        override fun onMessage(buffer: org.webrtc.DataChannel.Buffer) {}
                    })
                }
            }
            override fun onRenegotiationNeeded() {}
        })

        // Tunggu SDP offer dari HP anak (dikirim otomatis begitu ScreenCaptureService
        // di sisi anak mulai jalan), lalu balas dengan answer.
        FamilyLink.observeWebRtcOffer(this) { sdp ->
            val offerDesc = SessionDescription(SessionDescription.Type.OFFER, sdp)
            peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    peerConnection?.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(desc: SessionDescription) {
                            peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                            FamilyLink.sendWebRtcAnswer(this@ChildScreenViewActivity, desc.description)
                        }
                        override fun onSetSuccess() {}
                        override fun onCreateFailure(error: String?) {}
                        override fun onSetFailure(error: String?) {}
                    }, MediaConstraints())
                }
            }, offerDesc)
        }

        FamilyLink.observeIceCandidates(this, fromChild = true) { mid, idx, cand ->
            peerConnection?.addIceCandidate(IceCandidate(mid, idx, cand))
        }
    }

    /**
     * Tangkap sentuhan di renderer (video layar HP anak), konversi ke koordinat
     * ternormalisasi (0.0-1.0) relatif terhadap RESOLUSI ASLI HP anak, lalu kirim
     * sebagai tap/swipe jarak jauh. Hanya aktif kalau Mode Kontrol ON.
     */
    /** Kirim command lewat DataChannel (cepat, P2P) kalau sudah OPEN, kalau belum
     * fallback ke RTDB (lebih lambat tapi tetap jalan) supaya kontrol tidak macet
     * total selama negosiasi WebRTC belum kelar. */
    private fun sendControlCommand(json: org.json.JSONObject) {
        val ch = controlChannel
        if (ch != null && ch.state() == org.webrtc.DataChannel.State.OPEN) {
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            ch.send(org.webrtc.DataChannel.Buffer(java.nio.ByteBuffer.wrap(bytes), false))
        } else {
            when (json.optString("type")) {
                "remote_tap" -> FamilyLink.sendRemoteTap(this, json.getDouble("x").toFloat(), json.getDouble("y").toFloat())
                "remote_swipe" -> FamilyLink.sendRemoteSwipe(
                    this, json.getDouble("x1").toFloat(), json.getDouble("y1").toFloat(),
                    json.getDouble("x2").toFloat(), json.getDouble("y2").toFloat(), json.optLong("duration", 150L)
                )
                "remote_back" -> FamilyLink.sendRemoteBack(this)
            }
        }
    }

    private fun setupRemoteTouchHandling() {
        binding.rendererScreen.setOnTouchListener { view, event ->
            if (!controlModeOn) return@setOnTouchListener false

            val viewW = view.width.toFloat()
            val viewH = view.height.toFloat()
            if (viewW <= 0 || viewH <= 0) return@setOnTouchListener false

            // Video di-render dengan SCALE_ASPECT_FIT (letterbox), hitung area
            // gambar sebenarnya di dalam view berdasarkan rasio resolusi asli HP anak.
            val videoAspect = remoteWidth.toFloat() / remoteHeight.toFloat()
            val viewAspect = viewW / viewH
            val displayedW: Float
            val displayedH: Float
            if (viewAspect > videoAspect) {
                displayedH = viewH
                displayedW = viewH * videoAspect
            } else {
                displayedW = viewW
                displayedH = viewW / videoAspect
            }
            val offsetX = (viewW - displayedW) / 2f
            val offsetY = (viewH - displayedH) / 2f

            val relX = ((event.x - offsetX) / displayedW).coerceIn(0f, 1f)
            val relY = ((event.y - offsetY) / displayedH).coerceIn(0f, 1f)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = relX
                    touchDownY = relY
                    touchDownTime = System.currentTimeMillis()
                }
                MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - touchDownTime
                    val movedFar = kotlin.math.abs(relX - touchDownX) > 0.03f || kotlin.math.abs(relY - touchDownY) > 0.03f
                    if (movedFar) {
                        sendControlCommand(org.json.JSONObject().apply {
                            put("type", "remote_swipe")
                            put("x1", touchDownX); put("y1", touchDownY)
                            put("x2", relX); put("y2", relY)
                            put("duration", duration.coerceIn(50L, 1000L))
                        })
                    } else {
                        sendControlCommand(org.json.JSONObject().apply {
                            put("type", "remote_tap")
                            put("x", relX); put("y", relY)
                        })
                    }
                }
            }
            true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        peerConnectionFactory?.dispose()
        binding.rendererScreen.release()
        eglBase?.release()
        FamilyLink.clearWebRtcSession(this)
        // Sengaja TIDAK memanggil FamilyLink.sendStopScreenShare() di sini lagi --
        // capturer & MediaProjection di HP anak dibiarkan tetap hidup di background
        // supaya sesi pemantauan berikutnya tidak perlu consent dialog lagi. Yang
        // ditutup cuma koneksi WebRTC sisi kita; HP anak otomatis membersihkan peer
        // connection lamanya sendiri lewat onIceConnectionChange (lihat ScreenCaptureService).
    }
}

private open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}