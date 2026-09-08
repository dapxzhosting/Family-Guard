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

class ChildScreenViewActivity : BaseActivity() {

    companion object {
        const val EXTRA_DEVICE_ID = "extra_device_id"
    }

    private lateinit var binding: ActivityChildScreenViewBinding
    private var controlModeOn = false
    private lateinit var targetDeviceId: String

    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

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

        targetDeviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: run {
            Toast.makeText(this, "HP anak belum dipilih", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupRenderer()
        setupWebRtc()

        FamilyLink.sendRequestScreenShare(this, targetDeviceId)
        binding.tvStatus.text = "Meminta izin ke HP anak…"

        setupControlModeSwitch()

        binding.btnRemoteBack.setOnClickListener {
            sendControlCommand(org.json.JSONObject().apply { put("type", "remote_back") })
        }
        binding.btnRemoteHome.setOnClickListener {
            sendControlCommand(org.json.JSONObject().apply { put("type", "remote_home") })
        }
        binding.btnRemoteRecents.setOnClickListener {
            sendControlCommand(org.json.JSONObject().apply { put("type", "remote_recents") })
        }
        binding.btnRemoteVolumeUp.setOnClickListener {
            sendControlCommand(org.json.JSONObject().apply { put("type", "remote_volume_up") })
        }
        binding.btnRemoteVolumeDown.setOnClickListener {
            sendControlCommand(org.json.JSONObject().apply { put("type", "remote_volume_down") })
        }
        binding.btnRemotePower.setOnClickListener {
            sendControlCommand(org.json.JSONObject().apply { put("type", "remote_power") })
        }

        setupRemoteTouchHandling()
    }

    private fun setupControlModeSwitch() {
        binding.switchControlMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                checkAccessibilityBeforeEnablingControl()
            } else {
                controlModeOn = false
                binding.controlBar.visibility = android.view.View.GONE
                Toast.makeText(this, "Mode Kontrol nonaktif — hanya melihat", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkAccessibilityBeforeEnablingControl() {
        val code = com.familyguard.utils.AppLockPrefs.getFamilyCode(this)
        if (code == null) {
            enableControlMode()
            return
        }
        com.google.firebase.database.FirebaseDatabase.getInstance().reference
            .child("families").child(code).child("devices").child(targetDeviceId)
            .child("accessibilityEnabled")
            .get()
            .addOnSuccessListener { snapshot ->
                val enabled = snapshot.getValue(Boolean::class.java) ?: false
                if (enabled) {
                    enableControlMode()
                } else {

                    binding.switchControlMode.setOnCheckedChangeListener(null)
                    binding.switchControlMode.isChecked = false
                    setupControlModeSwitch()
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Accessibility Belum Aktif")
                        .setMessage(
                            "HP anak belum mengaktifkan Accessibility Service, jadi " +
                                    "sentuhan dari sini TIDAK akan sampai ke HP anak. Minta " +
                                    "anak buka Dashboard di HP-nya dan aktifkan Accessibility " +
                                    "dulu, baru coba lagi."
                        )
                        .setPositiveButton("Mengerti", null)
                        .show()
                }
            }
            .addOnFailureListener {

                enableControlMode()
            }
    }

    private fun enableControlMode() {
        controlModeOn = true
        binding.switchControlMode.isChecked = true
        binding.controlBar.visibility = android.view.View.VISIBLE
        Toast.makeText(this, "Mode Kontrol AKTIF — sentuhan akan diteruskan ke HP anak", Toast.LENGTH_SHORT).show()
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

        val rtcConfig = com.familyguard.sync.WebRtcIceConfig.buildRtcConfiguration()

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
                        binding.tvStatus.text = "Menghubungkan video…"
                    }
                }
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                runOnUiThread {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            binding.progressLoading.visibility = android.view.View.GONE
                            binding.tvStatus.text = "Terhubung • video real-time"
                        }
                        PeerConnection.IceConnectionState.CHECKING -> {
                            binding.tvStatus.text = "Mencari jalur koneksi…"
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            binding.progressLoading.visibility = android.view.View.VISIBLE
                            binding.tvStatus.text = "Gagal terhubung • jaringan HP anak tidak bisa dijangkau"
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            binding.tvStatus.text = "Koneksi terputus • mencoba lagi…"
                        }
                        PeerConnection.IceConnectionState.CLOSED -> {
                            binding.tvStatus.text = "Sesi ditutup"
                        }
                        else -> {}
                    }
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: org.webrtc.DataChannel?) {

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

    private fun sendControlCommand(json: org.json.JSONObject) {
        val ch = controlChannel
        if (ch != null && ch.state() == org.webrtc.DataChannel.State.OPEN) {
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            ch.send(org.webrtc.DataChannel.Buffer(java.nio.ByteBuffer.wrap(bytes), false))
        } else {
            when (json.optString("type")) {
                "remote_tap" -> FamilyLink.sendRemoteTap(this, json.getDouble("x").toFloat(), json.getDouble("y").toFloat(), targetDeviceId)
                "remote_swipe" -> FamilyLink.sendRemoteSwipe(
                    this, json.getDouble("x1").toFloat(), json.getDouble("y1").toFloat(),
                    json.getDouble("x2").toFloat(), json.getDouble("y2").toFloat(), json.optLong("duration", 150L), targetDeviceId
                )
                "remote_back" -> FamilyLink.sendRemoteBack(this, targetDeviceId)
                "remote_home" -> FamilyLink.sendRemoteHome(this, targetDeviceId)
                "remote_recents" -> FamilyLink.sendRemoteRecents(this, targetDeviceId)
                "remote_volume_up" -> FamilyLink.sendRemoteVolumeUp(this, targetDeviceId)
                "remote_volume_down" -> FamilyLink.sendRemoteVolumeDown(this, targetDeviceId)
                "remote_power" -> FamilyLink.sendRemotePower(this, targetDeviceId)
            }
        }
    }

    private fun setupRemoteTouchHandling() {
        binding.rendererScreen.setOnTouchListener { view, event ->
            if (!controlModeOn) return@setOnTouchListener false

            val viewW = view.width.toFloat()
            val viewH = view.height.toFloat()
            if (viewW <= 0 || viewH <= 0) return@setOnTouchListener false

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

    }
}

private open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}

