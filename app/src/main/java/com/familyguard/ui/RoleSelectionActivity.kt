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
