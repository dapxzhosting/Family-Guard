package com.familyguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import com.familyguard.R
import com.familyguard.sync.FamilyLink
import java.io.ByteArrayOutputStream

/**
 * Service yang menangkap layar HP anak secara berkala (bukan video real-time --
 * cukup screenshot berulang tiap beberapa ratus ms, jauh lebih ringan untuk
 * kuota data & baterai daripada streaming video sungguhan) dan mengirimkannya
 * ke Firebase Realtime Database supaya bisa dilihat HP orang tua.
 *
 * PENTING soal privasi/keamanan: MediaProjection WAJIB izin eksplisit dari
 * pengguna di HP ini (lihat ScreenCaptureRequestActivity) -- ini aturan Android,
 * bukan pilihan aplikasi. Notifikasi foreground service di bawah ini juga WAJIB
 * tetap tampil selama capture aktif (tidak bisa disembunyikan), supaya siapapun
 * yang pegang HP tetap tahu layarnya sedang dibagikan.
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private val mainHandler = Handler(android.os.Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
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
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        // Resolusi ditekan (maks 480px lebar) supaya ukuran base64 tiap frame
        // kecil -- ini bukan video, cukup untuk memantau & menargetkan tap,
        // jangan kirim resolusi penuh (boros kuota & bikin RTDB lambat).
        val scale = MAX_CAPTURE_WIDTH.toFloat() / screenWidth
        val captureWidth = MAX_CAPTURE_WIDTH
        val captureHeight = (screenHeight * scale).toInt()

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            Log.w(TAG, "MediaProjection gagal dibuat (izin ditolak/tidak valid)")
            stopSelf()
            return START_NOT_STICKY
        }
        mediaProjection = projection

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, mainHandler)

        captureThread = HandlerThread("ScreenCaptureThread").apply { start() }
        captureHandler = Handler(captureThread!!.looper)

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, android.graphics.PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "FamilyGuardScreenCapture",
            captureWidth, captureHeight, screenDensity,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, captureHandler
        )

        startFrameListener(captureWidth, captureHeight)

        // Simpan resolusi ASLI layar (bukan resolusi capture yang dikecilkan) supaya
        // HP orang tua bisa mengonversi tap balik ke koordinat piksel yang benar.
        RemoteControlState.realScreenWidth = screenWidth
        RemoteControlState.realScreenHeight = screenHeight

        return START_STICKY
    }

    private fun windowManagerCompatDefaultDisplay(): android.view.Display? {
        // PENTING: Service BUKAN "visual Context" (beda dari Activity), jadi
        // Context#getDisplay() akan CRASH sejak Android 11 (R) dengan
        // UnsupportedOperationException. Harus ambil display lewat DisplayManager
        // (yang bisa diakses dari context apapun, termasuk Service), bukan lewat
        // context.display / windowManager.defaultDisplay langsung.
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        return displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
    }

    /**
     * FPS FIX: sebelumnya capture jalan lewat polling tetap tiap 600ms di MAIN
     * THREAD (nunggu delay walau belum tentu ada frame baru, dan encode JPEG-nya
     * numpuk di main thread yang sama dipakai UI -- bikin lambat & nge-lag).
     *
     * Sekarang capture dipicu langsung oleh ImageReader begitu ADA FRAME BARU
     * (event-driven, bukan polling), dan semua kerja berat (decode buffer,
     * compress JPEG, upload) jalan di captureHandler (background thread) --
     * bukan main thread. Throttle MIN_FRAME_INTERVAL_MS tetap ada supaya tidak
     * upload lebih cepat dari yang dibutuhkan (boros kuota RTDB & baterai),
     * tapi begitu ada kuota, frame langsung dikirim tanpa nunggu delay tetap
     * seperti sebelumnya -- hasilnya terasa lebih responsif & lebih tinggi FPS
     * efektifnya di sisi orang tua.
     */
    private var lastUploadTime = 0L

    private fun startFrameListener(width: Int, height: Int) {
        imageReader?.setOnImageAvailableListener({ reader ->
            val now = System.currentTimeMillis()
            if (now - lastUploadTime < MIN_FRAME_INTERVAL_MS) {
                // Belum waktunya kirim frame berikutnya -- buang frame ini supaya
                // tidak menumpuk di buffer (ImageReader cuma punya 2 buffer slot).
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastUploadTime = now
            try {
                captureAndUploadFrame(reader, width, height)
            } catch (e: Exception) {
                Log.w(TAG, "Gagal ambil frame: ${e.message}")
            }
        }, captureHandler)
    }

    private fun captureAndUploadFrame(reader: ImageReader, width: Int, height: Int) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            val cropped = if (bitmap.width != width) Bitmap.createBitmap(bitmap, 0, 0, width, height) else bitmap

            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            val base64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)

            FamilyLink.uploadScreenFrame(this, base64, RemoteControlState.realScreenWidth, RemoteControlState.realScreenHeight)

            if (cropped !== bitmap) bitmap.recycle()
            cropped.recycle()
        } finally {
            image.close()
        }
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
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        captureThread?.quitSafely()
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIF_ID = 9911
        private const val MAX_CAPTURE_WIDTH = 420
        // Batas MINIMAL jarak antar upload -- bukan delay tetap seperti sebelumnya.
        // 250ms = maksimal ~4 fps efektif, cukup mulus untuk memantau & mengarahkan
        // tap tanpa membanjiri Firebase RTDB / kuota data HP anak.
        private const val MIN_FRAME_INTERVAL_MS = 250L
        private const val JPEG_QUALITY = 35

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }
}

/** Menyimpan resolusi ASLI layar HP anak supaya AppLockAccessibilityService bisa
 * mengonversi koordinat tap ternormalisasi (0.0-1.0) dari HP orang tua kembali
 * ke koordinat piksel yang benar saat dispatchGesture(). */
object RemoteControlState {
    @Volatile var realScreenWidth: Int = 1080
    @Volatile var realScreenHeight: Int = 2400
}