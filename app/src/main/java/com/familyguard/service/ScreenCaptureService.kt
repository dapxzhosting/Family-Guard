package com.familyguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import com.familyguard.R
import com.familyguard.sync.FamilyLink
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Service yang membagikan layar HP anak ke HP orang tua lewat WebRTC (video
 * real-time, bukan lagi screenshot berulang lewat Firebase RTDB).
 *
 * Kenapa WebRTC: encoding hardware H.264 + jalur video P2P/relay bisa dapat
 * 30 fps+ dengan delay jauh lebih rendah daripada kirim JPEG lewat RTDB
 * (yang dulunya dibatasi ~4 fps karena tiap frame = 1 write database).
 * Firebase RTDB di sini HANYA dipakai untuk signaling (tukar SDP + ICE
 * candidate) lewat FamilyLink.sendWebRtcOffer/observeWebRtcAnswer/dst --
 * video-nya sendiri tidak lewat RTDB sama sekali.
 *
 * PENTING soal privasi/keamanan tetap sama seperti sebelumnya: MediaProjection
 * WAJIB izin eksplisit dari pengguna di HP ini, dan notifikasi foreground
 * service WAJIB tetap tampil selama capture aktif -- ini aturan Android.
 */
class ScreenCaptureService : Service() {

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var eglBase: EglBase? = null
    private var controlChannel: org.webrtc.DataChannel? = null

    private val mainHandler = Handler(android.os.Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManagerCompatDefaultDisplay()?.getRealMetrics(metrics)
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val screenDensity = metrics.densityDpi

        RemoteControlState.realScreenWidth = screenWidth
        RemoteControlState.realScreenHeight = screenHeight

        // Catatan: ScreenCapturerAndroid MEMBUAT MediaProjection-nya SENDIRI secara
        // internal dari Intent hasil izin (resultData) + callback -- jangan panggil
        // projectionManager.getMediaProjection() secara terpisah di sini, karena
        // MediaProjection cuma boleh "dipakai" sekali oleh satu consumer.
        startWebRtc(resultData, screenWidth, screenHeight)

        return START_STICKY
    }

    private fun startWebRtc(resultData: Intent, width: Int, height: Int) {
        eglBase = EglBase.create()

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .createInitializationOptions()
        )
        val factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
            .createPeerConnectionFactory()
        peerConnectionFactory = factory

        // ScreenCapturerAndroid membuat MediaProjection-nya sendiri dari resultData
        // ini (izin sistem dari dialog "Mulai merekam layar?" yang HANYA muncul di
        // sini, sekali). Selama service ini (dan capturer/videoTrack-nya) tidak
        // di-dispose, MediaProjection ini tetap valid dan bisa dipakai berkali-kali
        // untuk peer connection BARU tanpa perlu consent ulang -- lihat reconnectPeer().
        val capturer = ScreenCapturerAndroid(resultData, object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        })
        videoCapturer = capturer

        surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase!!.eglBaseContext)
        videoSource = factory.createVideoSource(true)
        capturer.initialize(surfaceTextureHelper, applicationContext, videoSource!!.capturerObserver)

        // 30 fps target -- lebar dibatasi supaya bitrate tetap wajar untuk
        // koneksi seluler anak, tapi jauh di atas 4 fps implementasi lama.
        val targetWidth = if (width > MAX_CAPTURE_WIDTH) MAX_CAPTURE_WIDTH else width
        val scale = targetWidth.toFloat() / width
        val targetHeight = (height * scale).toInt()
        capturer.startCapture(targetWidth, targetHeight, TARGET_FPS)

        videoTrack = factory.createVideoTrack("familyguard_screen_v0", videoSource)

        // Buat koneksi WebRTC pertama kali.
        setupPeerConnectionAndOffer()
    }

    /**
     * Dipanggil setiap orang tua membuka layar "Lihat Layar Anak" -- baik yang
     * PERTAMA KALI (dari startWebRtc) maupun sesi BERIKUTNYA setelah orang tua
     * sempat menutup layar sebelumnya. videoCapturer/videoTrack/MediaProjection
     * TIDAK dibuat ulang di sini, cuma peer connection + offer baru -- makanya
     * tidak perlu dialog izin lagi selama service ini masih hidup.
     */
    fun reconnectPeer() {
        if (videoTrack == null || peerConnectionFactory == null) {
            Log.w(TAG, "reconnectPeer dipanggil tapi capturer belum siap")
            return
        }
        // Buang peer connection lama (kalau ada sisa dari sesi sebelumnya) sebelum
        // bikin yang baru, supaya tidak ada 2 koneksi nyala bersamaan.
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        controlChannel = null
        FamilyLink.clearWebRtcSession(this)
        setupPeerConnectionAndOffer()
    }

    private fun setupPeerConnectionAndOffer() {
        val factory = peerConnectionFactory ?: return
        val videoTrack = this.videoTrack ?: return

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            // TODO: tambahkan TURN server sendiri di sini kalau anak sering di
            // jaringan dengan NAT/firewall ketat (mis. sekolah), supaya P2P
            // tetap bisa connect lewat relay. STUN publik saja cukup untuk
            // kebanyakan jaringan rumah/data seluler.
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : org.webrtc.PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                FamilyLink.sendIceCandidate(
                    this@ScreenCaptureService, fromChild = true,
                    sdpMid = candidate.sdpMid, sdpMLineIndex = candidate.sdpMLineIndex,
                    candidate = candidate.sdp
                )
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                // Kalau orang tua menutup layar / koneksi putus, bersihkan peer
                // connection ini secara otomatis supaya reconnectPeer() berikutnya
                // mulai dari kondisi bersih (capturer/videoTrack tetap jalan terus).
                if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                    state == PeerConnection.IceConnectionState.FAILED ||
                    state == PeerConnection.IceConnectionState.CLOSED) {
                    mainHandler.post {
                        peerConnection?.close()
                        peerConnection?.dispose()
                        peerConnection = null
                        controlChannel = null
                    }
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out MediaStream>?) {}
        })

        val sender = peerConnection?.addTrack(videoTrack, listOf("familyguard_stream_v0"))
        applyEncodingConstraints(sender)

        // DataChannel untuk terima command kontrol (tap/swipe/back) dari HP orang tua.
        // Ini jauh lebih cepat daripada lewat RTDB karena P2P langsung, tanpa
        // round-trip ke server Firebase tiap kali orang tua nyentuh layar.
        val dcInit = org.webrtc.DataChannel.Init().apply { ordered = true }
        controlChannel = peerConnection?.createDataChannel("control", dcInit)
        controlChannel?.registerObserver(object : org.webrtc.DataChannel.Observer {
            override fun onBufferedAmountChange(amount: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: org.webrtc.DataChannel.Buffer) {
                try {
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    val json = org.json.JSONObject(String(bytes, Charsets.UTF_8))
                    com.familyguard.service.AppLockAccessibilityService.instance
                        ?.executeRemoteInputJson(json)
                } catch (e: Exception) {
                    Log.w(TAG, "Gagal parse data channel message: ${e.message}")
                }
            }
        })

        // Log fps aktual tiap 5 detik lewat getStats(), buat memastikan encoder di
        // HP anak beneran ngirim ~30fps atau nggak (kalau di sini rendah, masalahnya
        // di ENCODING/device anak, bukan di jaringan atau di sisi orang tua).
        startStatsLogging()

        // Dengarkan ICE candidate dari HP orang tua (dikirim balik lewat signaling).
        FamilyLink.observeIceCandidates(this, fromChild = false) { mid, idx, cand ->
            peerConnection?.addIceCandidate(IceCandidate(mid, idx, cand))
        }

        createAndSendOffer()
    }

    /**
     * Paksa bitrate & prioritas fps, jangan biarkan WebRTC Bandwidth Estimation
     * (BWE) menebak sendiri -- di jaringan lokal/WiFi rumah BWE kadang salah baca
     * dan nge-drop bitrate/resolusi meski koneksi sebenarnya bagus, hasilnya
     * terlihat "patah-patah" padahal bukan masalah jaringan beneran.
     */
    private fun applyEncodingConstraints(sender: org.webrtc.RtpSender?) {
        val s = sender ?: return
        val params = s.parameters
        if (params.encodings.isEmpty()) return
        for (enc in params.encodings) {
            enc.maxBitrateBps = 700_000     // diturunkan -- device Unisoc lemah, bitrate tinggi bikin encoder makin ketinggalan
            enc.minBitrateBps = 150_000
            enc.maxFramerate = TARGET_FPS
        }
        // MAINTAIN_FRAMERATE: kalau jaringan/CPU kewalahan, WebRTC akan turunin
        // RESOLUSI dulu supaya fps tetap terjaga -- ini yang paling penting buat
        // kasus "biar 30fps+", karena defaultnya (BALANCED) sering korbanin fps duluan.
        params.degradationPreference = org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
        s.parameters = params
    }

    private fun startStatsLogging() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                peerConnection?.getStats { report ->
                    for (stat in report.statsMap.values) {
                        if (stat.type == "outbound-rtp") {
                            val fps = stat.members["framesPerSecond"]
                            val bitrate = stat.members["bytesSent"]
                            val width = stat.members["frameWidth"]
                            val height = stat.members["frameHeight"]
                            Log.d(TAG, "STATS outbound: fps=$fps res=${width}x${height} bytesSent=$bitrate")
                        }
                    }
                }
                handler.postDelayed(this, 5000)
            }
        }
        handler.postDelayed(runnable, 5000)
    }

    private fun createAndSendOffer() {
        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                FamilyLink.sendWebRtcOffer(this@ScreenCaptureService, desc.description)
                // Dengarkan answer dari HP orang tua supaya koneksi bisa terbentuk.
                FamilyLink.observeWebRtcAnswer(this@ScreenCaptureService) { sdp ->
                    val answerDesc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
                    peerConnection?.setRemoteDescription(SimpleSdpObserver(), answerDesc)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) { Log.w(TAG, "Gagal buat offer: $error") }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    private fun windowManagerCompatDefaultDisplay(): android.view.Display? {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        return displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
    }

    private fun buildNotification(): Notification {
        val channelId = "screen_share_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Berbagi Layar", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Layar sedang dibagikan")
            .setContentText("Orang tua sedang memantau layar HP ini")
            .setSmallIcon(R.drawable.ic_family)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        FamilyLink.clearWebRtcSession(this)
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoSource?.dispose()
        surfaceTextureHelper?.dispose()
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnectionFactory?.dispose()
        eglBase?.release()
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIF_ID = 9911
        private const val MAX_CAPTURE_WIDTH = 480
        private const val TARGET_FPS = 30

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        /** Referensi instance service yang aktif, dipakai FamilyLink untuk cek
         * apakah screen capture sudah jalan (reconnect cepat) atau belum (perlu
         * consent baru lewat ScreenCaptureRequestActivity). */
        @Volatile var instance: ScreenCaptureService? = null
    }
}

private class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}

/** Menyimpan resolusi ASLI layar HP anak supaya AppLockAccessibilityService bisa
 * mengonversi koordinat tap ternormalisasi (0.0-1.0) dari HP orang tua kembali
 * ke koordinat piksel yang benar saat dispatchGesture(). */
object RemoteControlState {
    @Volatile var realScreenWidth: Int = 1080
    @Volatile var realScreenHeight: Int = 2400
}