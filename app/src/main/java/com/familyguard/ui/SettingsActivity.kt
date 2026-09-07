package com.familyguard.ui

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.familyguard.databinding.ActivitySettingsBinding
import com.familyguard.sync.FamilyLink
import com.familyguard.utils.AnimUtils
import com.familyguard.utils.AppLockPrefs
import com.google.firebase.auth.FirebaseAuth
import com.familyguard.R

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (AppLockPrefs.getRole(this) == AppLockPrefs.ROLE_CHILD) {
            binding.settingsHeader.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, R.color.dash_child_primary))
            window.statusBarColor = androidx.core.content.ContextCompat.getColor(this, R.color.dash_child_primary)

            val roleColor = androidx.core.content.ContextCompat.getColor(this, R.color.dash_child_primary)
            binding.circleSettingsName.backgroundTintList = android.content.res.ColorStateList.valueOf(roleColor)
            binding.circleSettingsEmail.backgroundTintList = android.content.res.ColorStateList.valueOf(roleColor)
            binding.ivSettingsPrivacyIcon.imageTintList =
                androidx.core.content.ContextCompat.getColorStateList(this, R.color.dash_child_primary)
        } else {
            val roleColor = androidx.core.content.ContextCompat.getColor(this, R.color.dash_primary)
            binding.circleSettingsName.backgroundTintList = android.content.res.ColorStateList.valueOf(roleColor)
            binding.circleSettingsEmail.backgroundTintList = android.content.res.ColorStateList.valueOf(roleColor)
            binding.ivSettingsPrivacyIcon.imageTintList =
                androidx.core.content.ContextCompat.getColorStateList(this, R.color.dash_primary)
        }

        refreshName()
        binding.tvSettingsEmail.text =
            FirebaseAuth.getInstance().currentUser?.email?.takeIf { it.isNotBlank() } ?: "-"
        binding.tvSettingsVersion.text = getAppVersionLabel()

        binding.btnSettingsBack.setOnClickListener {
            closeWithSlideDown()
        }

        binding.rowSettingsName.setOnClickListener {
            showEditNameDialog()
        }

        binding.rowSettingsPrivacy.setOnClickListener {
            val intent = Intent(this, PrivacyPolicyActivity::class.java).apply {
                putExtra(PrivacyPolicyActivity.EXTRA_IS_FROM_SETTINGS, true)
            }
            startActivity(intent)
        }

        AnimUtils.staggerFadeSlideIn(binding.rootSettingsContent)
        AnimUtils.attachPressAnimationRecursively(binding.rootSettingsContent)
    }

    private fun refreshName() {
        binding.tvSettingsName.text =
            AppLockPrefs.getUserName(this)?.takeIf { it.isNotBlank() } ?: "-"
    }

    private fun showEditNameDialog() {
        val input = EditText(this).apply {
            setText(AppLockPrefs.getUserName(this@SettingsActivity) ?: "")
            setSelection(text.length)
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Ubah Nama")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, "Nama tidak boleh kosong", Toast.LENGTH_SHORT).show()
                } else {
                    FamilyLink.updateUserName(this, newName) {
                        runOnUiThread {
                            refreshName()
                            Toast.makeText(this, "Nama berhasil diubah", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun getAppVersionLabel(): String {
        return try {
            val info = packageManager.getPackageInfo(packageName, 0)
            "v${info.versionName}"
        } catch (e: Exception) {
            "-"
        }
    }

    override fun onBackPressed() {
        closeWithSlideDown()
    }

    private fun closeWithSlideDown() {
        super.onBackPressed()
        @Suppress("DEPRECATION")
        overridePendingTransition(com.familyguard.R.anim.stay_undim, com.familyguard.R.anim.slide_down_out)
    }
}