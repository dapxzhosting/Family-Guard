package com.familyguard.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.familyguard.databinding.ActivityChildScreenViewBinding
import com.familyguard.sync.FamilyLink
import com.google.firebase.database.ValueEventListener

/**
 * Layar orang tua untuk MEMANTAU layar HP anak secara real-time, dengan toggle
 * "Mode Kontrol": kalau OFF, ini murni tampilan (view-only). Kalau ON, setiap
 * tap/geser di gambar layar HP anak diteruskan sebagai sentuhan SUNGGUHAN di
 * HP anak (lewat AccessibilityService.dispatchGesture di sisi anak).
 *
 * Frame layar dikirim HP anak lewat ScreenCaptureService -> Firebase RTDB
 * (throttled ~1-2 fps, bukan video streaming sungguhan -- cukup untuk memantau
 * & mengarahkan tap, jauh lebih hemat kuota & baterai).
 */
class ChildScreenViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChildScreenViewBinding
    private var streamListener: ValueEventListener? = null
    private var controlModeOn = false

    // Resolusi ASLI layar HP anak, dikirim bersama tiap frame -- dipakai untuk
    // menormalisasi koordinat tap (0.0-1.0) supaya akurat walau ukuran ImageView
    // di HP orang tua beda rasio dengan layar HP anak.
    private var remoteWidth = 0
    private var remoteHeight = 0

    // Untuk mendeteksi swipe vs tap sederhana
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

        binding.btnRemoteBack.setOnClickListener { FamilyLink.sendRemoteBack(this) }

        setupRemoteTouchHandling()
        observeStream()
    }

    /**
     * Tangkap sentuhan di ImageView (gambar layar HP anak), konversi ke koordinat
     * ternormalisasi (0.0-1.0) relatif terhadap RESOLUSI ASLI HP anak (bukan
     * ukuran ImageView di layar ini), lalu kirim sebagai tap/swipe jarak jauh.
     * Hanya aktif kalau Mode Kontrol ON.
     */
    private fun setupRemoteTouchHandling() {
        binding.ivScreen.setOnTouchListener { view, event ->
            if (!controlModeOn || remoteWidth == 0 || remoteHeight == 0) {
                return@setOnTouchListener false
            }

            // Hitung area gambar yang benar-benar terisi di dalam ImageView
            // (scaleType fitCenter bisa menyisakan letterbox di kiri-kanan/atas-bawah).
            val drawable = binding.ivScreen.drawable ?: return@setOnTouchListener false
            val viewW = view.width.toFloat()
            val viewH = view.height.toFloat()
            val imgW = drawable.intrinsicWidth.toFloat()
            val imgH = drawable.intrinsicHeight.toFloat()
            if (imgW <= 0 || imgH <= 0) return@setOnTouchListener false

            val scale = minOf(viewW / imgW, viewH / imgH)
            val displayedW = imgW * scale
            val displayedH = imgH * scale
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
                        FamilyLink.sendRemoteSwipe(this, touchDownX, touchDownY, relX, relY, duration.coerceIn(50L, 1000L))
                    } else {
                        FamilyLink.sendRemoteTap(this, relX, relY)
                    }
                }
            }
            true
        }
    }

    // FPS FIX (sisi orang tua): decode Base64 -> Bitmap dipindah ke background
    // thread, karena sebelumnya decode dilakukan LANGSUNG di runOnUiThread {}
    // yang blocking UI thread tiap frame -- kalau frame datang cepat (setelah
    // fix di sisi anak), UI jadi nge-lag/patah-patah malah lebih parah.
    // Ditambah flag "sedang decode" supaya frame yang menumpuk saat decode
    // lambat tidak ikut diproses semua (skip ke frame TERBARU saja).
    private val decodeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var isDecoding = false

    private fun observeStream() {
        streamListener = FamilyLink.observeScreenStream(this) { base64Jpeg, width, height ->
            if (isDecoding) return@observeScreenStream // skip, sedang proses frame sebelumnya
            isDecoding = true
            remoteWidth = width
            remoteHeight = height
            decodeExecutor.execute {
                try {
                    val bytes = Base64.decode(base64Jpeg, Base64.NO_WRAP)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    runOnUiThread {
                        binding.progressLoading.visibility = android.view.View.GONE
                        binding.tvStatus.text = "Terhubung • diperbarui otomatis"
                        if (bitmap != null) binding.ivScreen.setImageBitmap(bitmap)
                    }
                } catch (e: Exception) {
                    // Frame korup/tidak lengkap, abaikan -- frame berikutnya akan datang
                } finally {
                    isDecoding = false
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        streamListener?.let { FamilyLink.removeScreenStreamObserver(this, it) }
        decodeExecutor.shutdownNow()
        // Hentikan screen share di HP anak begitu orang tua menutup layar ini,
        // supaya tidak terus menguras baterai/kuota HP anak tanpa disadari.
        FamilyLink.sendStopScreenShare(this)
    }
}