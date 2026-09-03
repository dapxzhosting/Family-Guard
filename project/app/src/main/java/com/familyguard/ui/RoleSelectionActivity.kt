package com.familyguard.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.familyguard.databinding.ActivityRoleSelectionBinding
import com.familyguard.utils.AppLockPrefs

class RoleSelectionActivity : BaseActivity() {

    private lateinit var binding: ActivityRoleSelectionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val existingRole = AppLockPrefs.getRole(this)
        val existingCode = AppLockPrefs.getFamilyCode(this)

        if (existingRole != null && existingCode != null) {
            goToRoleHome(existingRole)
            return
        }

        if (existingRole != null && existingCode == null) {
            goToNextStep(existingRole)
            return
        }

        binding = ActivityRoleSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnRoleSelectionBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

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

    /** Baik Orang Tua maupun Anak sekarang sama-sama masuk ke menu dulu
     *  (ParentMenuActivity / ChildMenuActivity) -- BUKAN langsung dipaksa
     *  masukkan kode keluarga. Kalau anak dipaksa ke FamilyCodeActivity
     *  duluan tanpa kode yang valid di tangan, dia kejebak di situ tanpa
     *  jalan keluar (dulu tidak ada tombol Logout/Reset Role di layar itu).
     *  Sekarang "Gabung Keluarga" jadi salah satu kartu di ChildMenuActivity,
     *  sama seperti "Buat Keluarga" di menu Orang Tua. */
    private fun goToNextStep(role: String) {
        val target = if (role == AppLockPrefs.ROLE_PARENT) {
            ParentMenuActivity::class.java
        } else {
            ChildMenuActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }

    private fun goToRoleHome(role: String) {
        val target = if (role == AppLockPrefs.ROLE_PARENT) {
            ParentMenuActivity::class.java
        } else {
            ChildMenuActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }
}