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

        // Kalau role sudah pernah dipilih, langsung lempar ke layar yang sesuai
        val existingRole = AppLockPrefs.getRole(this)
        if (existingRole != null) {
            goToRoleHome(existingRole)
            return
        }

        binding = ActivityRoleSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRoleParent.setOnClickListener {
            AppLockPrefs.saveRole(this, AppLockPrefs.ROLE_PARENT)
            goToRoleHome(AppLockPrefs.ROLE_PARENT)
        }

        binding.btnRoleChild.setOnClickListener {
            AppLockPrefs.saveRole(this, AppLockPrefs.ROLE_CHILD)
            goToRoleHome(AppLockPrefs.ROLE_CHILD)
        }
    }

    private fun goToRoleHome(role: String) {
        val target = if (role == AppLockPrefs.ROLE_PARENT) {
            ParentDashboardActivity::class.java
        } else {
            MainActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }
}
