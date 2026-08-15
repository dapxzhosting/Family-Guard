package com.familyguard.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import com.familyguard.service.ScreenCaptureService

/**
 * Activity transparan (tidak ada UI) yang HANYA bertugas memunculkan dialog izin
 * sistem "Mulai merekam atau transmisikan layar?" -- ini WAJIB dari Android
 * sendiri untuk fitur MediaProjection (screen capture), TIDAK BISA dilewati oleh
 * aplikasi manapun tanpa akses Device Owner penuh. Anak/siapapun yang pegang HP
 * perlu tap "Mulai Sekarang" satu kali setiap sesi lihat-layar dimulai.
 *
 * Alurnya: orang tua kirim command "start_screen_share" -> FamilyLink di HP anak
 * membuka activity ini -> dialog sistem muncul -> hasil izin diteruskan ke
 * ScreenCaptureService lewat Intent (data hasil izin TIDAK BOLEH disimpan lama,
 * cukup dipakai sekali untuk startForegroundService).
 */
class ScreenCaptureRequestActivity : Activity() {

    private lateinit var projectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        @Suppress("DEPRECATION")
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_CODE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } else {
                Toast.makeText(this, "Izin lihat layar ditolak", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    companion object {
        private const val REQ_CODE = 5501
    }
}