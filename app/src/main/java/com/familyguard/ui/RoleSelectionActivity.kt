package com.familyguard.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.familyguard.databinding.ActivityRoleSelectionBinding
import com.familyguard.utils.AppLockPrefs

/**
 * Layar pertama yang muncul saat app dibuka. APK yang sama dipakai di HP
 * orang tua maupun HP anak — bedanya cuma role yang dipilih di sini.
 * Setelah role dipilih sekali, layar ini dilewati di kunjungan berikutnya.
 */
class RoleSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoleSelectionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Jaga-jaga kalau activity ini kebuka langsung tanpa lewat LoginActivity
        if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val existingRole = AppLockPrefs.getRole(this)
        val existingCode = AppLockPrefs.getFamilyCode(this)

        // Jika sudah ada role DAN sudah ada kode, langsung ke Dashboard
        if (existingRole != null && existingCode != null) {
            goToRoleHome(existingRole)
            return
        }

        // Jika sudah pilih role tapi belum ada kode, lanjutkan dari titik yang sesuai
        if (existingRole != null && existingCode == null) {
            goToNextStep(existingRole)
            return
        }

        binding = ActivityRoleSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRoleParent.setOnClickListener {
            AppLockPrefs.saveRole(this, AppLockPrefs.ROLE_PARENT)
            com.familyguard.sync.FamilyLink.saveUserProfile(this)
            goToNextStep(AppLockPrefs.ROLE_PARENT)
        }

        binding.btnRoleChild.setOnClickListener {
            AppLockPrefs.saveRole(this, AppLockPrefs.ROLE_CHILD)
            com.familyguard.sync.FamilyLink.saveUserProfile(this)
            goToNextStep(AppLockPrefs.ROLE_CHILD)
        }
    }

    /** Orang tua masuk ke menu utama dulu (Dashboard/Pengaturan/Buat Keluarga/Logout);
     *  anak langsung ke layar kode seperti alur lama. */
    private fun goToNextStep(role: String) {
        val target = if (role == AppLockPrefs.ROLE_PARENT) {
            DashboardActivity::class.java
        } else {
            FamilyCodeActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }

    private fun goToRoleHome(role: String) {
        val target = if (role == AppLockPrefs.ROLE_PARENT) {
            DashboardActivity::class.java
        } else {
            ChildHomeActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }
}