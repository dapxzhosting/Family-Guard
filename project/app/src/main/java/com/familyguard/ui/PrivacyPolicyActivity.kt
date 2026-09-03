package com.familyguard.ui

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import com.familyguard.R
import com.familyguard.utils.AppLockPrefs

/**
 * Menampilkan Kebijakan Privasi & Ketentuan.
 *
 * Kontennya berbeda tergantung role yang sedang login di HP ini:
 * - Role ANAK -> activity_privacy_policy_child.xml (bahasa sederhana,
 *   fokus menjelaskan dengan jujur apa saja yang bisa dilihat orang tua,
 *   supaya anak tidak merasa diam-diam diawasi).
 * - Role ORANG TUA / belum ada role -> activity_privacy_policy.xml
 *   (versi lengkap/formal seperti sebelumnya).
 *
 * Kedua layout sengaja memakai id view yang sama (btnBack, btnAccept,
 * bottomActionContainer) supaya logic di bawah ini tidak perlu duplikasi.
 *
 * Tombol "Aku Mengerti / Setuju" SELALU ditampilkan, baik dibuka saat
 * onboarding pertama kali maupun dibuka ulang dari menu Pengaturan.
 */
class PrivacyPolicyActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val role = AppLockPrefs.getRole(this)
        val layoutRes = if (role == AppLockPrefs.ROLE_CHILD) {
            R.layout.activity_privacy_policy_child
        } else {
            R.layout.activity_privacy_policy
        }
        setContentView(layoutRes)

        // Status bar ikut menyesuaikan tema hijau kalau role-nya anak,
        // biar konsisten dengan Splash/Menu Anak yang lain.
        if (role == AppLockPrefs.ROLE_CHILD) {
            window.statusBarColor = Color.parseColor("#00695C")
        }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.btnAccept).setOnClickListener {
            setResult(RESULT_OK)
            finish()
        }
    }

    companion object {
        const val EXTRA_IS_FROM_SETTINGS = "extra_is_from_settings"
    }
}