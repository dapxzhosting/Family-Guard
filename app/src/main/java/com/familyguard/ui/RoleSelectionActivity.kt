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

        val existingRole = AppLockPrefs.getRole(this)
        val existingCode = AppLockPrefs.getFamilyCode(this)

        // Jika sudah ada role DAN sudah ada kode, langsung ke Dashboard
        if (existingRole != null && existingCode != null) {
            goToRoleHome(existingRole)
            return
        }

        // Jika sudah pilih role tapi belum ada kode, ke layar kode
        if (existingRole != null && existingCode == null) {
            startActivity(Intent(this, FamilyCodeActivity::class.java))
            finish()
            return
        }

        binding = ActivityRoleSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRoleParent.setOnClickListener {
            AppLockPrefs.saveRole(this, AppLockPrefs.ROLE_PARENT)
            startActivity(Intent(this, FamilyCodeActivity::class.java))
            finish()
        }

        binding.btnRoleChild.setOnClickListener {
            AppLockPrefs.saveRole(this, AppLockPrefs.ROLE_CHILD)
            startActivity(Intent(this, FamilyCodeActivity::class.java))
            finish()
        }
    }

    private fun goToRoleHome(role: String) {
        val target = if (role == AppLockPrefs.ROLE_PARENT) {
            ParentDashboardActivity::class.java
        } else {
            ChildHomeActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }
}
