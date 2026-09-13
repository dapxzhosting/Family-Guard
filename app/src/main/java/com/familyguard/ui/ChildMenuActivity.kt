package com.familyguard.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.familyguard.R
import com.familyguard.databinding.ActivityChildMenuBinding
import com.familyguard.sync.FamilyLink
import com.familyguard.utils.AccountActions
import com.familyguard.utils.AnimUtils
import com.familyguard.utils.AppLockPrefs

class ChildMenuActivity : BaseActivity() {

    private lateinit var binding: ActivityChildMenuBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChildMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.statusBarColor = androidx.core.content.ContextCompat.getColor(this, R.color.dash_child_primary)

        updateFamilyStatus()

        FamilyLink.listenFamilyDeletion(this) {
            FamilyLink.clearLocalFamilyState(this)
            binding.tvFamilyNoticeText.text = "Keluarga telah dihapus oleh orang tua. Silakan gabung ke keluarga lain."
            binding.tvFamilyDeletedNotice.visibility = View.VISIBLE
            updateFamilyStatus()
        }

        FamilyLink.listenForKick(this) {
            FamilyLink.clearLocalFamilyState(this)
            binding.tvFamilyNoticeText.text = "Kamu telah dikeluarkan dari keluarga oleh orang tua."
            binding.tvFamilyDeletedNotice.visibility = View.VISIBLE
            updateFamilyStatus()
        }

        binding.btnMenuDashboard.setOnClickListener {
            if (AppLockPrefs.getFamilyCode(this).isNullOrBlank()) {
                Toast.makeText(this, "Gabung keluarga dulu sebelum buka dashboard", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, ChildDashboardActivity::class.java))
            }
        }

        binding.btnMenuSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_up_in, R.anim.stay_dim)
        }

        binding.btnMenuChangeFamily.setOnClickListener {
            if (AppLockPrefs.getFamilyCode(this).isNullOrBlank()) {

                startActivity(Intent(this, FamilyCodeActivity::class.java))
            } else {
                AccountActions.changeFamily(this)
            }
        }

        binding.btnMenuLeaveFamily.setOnClickListener {
            AccountActions.leaveFamily(this) {
                updateFamilyStatus()
            }
        }

        binding.btnMenuResetRole.setOnClickListener {
            AccountActions.resetRole(this)
        }

        binding.btnMenuLogout.setOnClickListener {
            AccountActions.logout(this)
        }

        AnimUtils.staggerFadeSlideIn(binding.rootMenuContent)
        AnimUtils.attachPressAnimationRecursively(binding.rootMenuContent)
    }

    override fun onResume() {
        super.onResume()
        updateFamilyStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        FamilyLink.stopListeningFamilyDeletion()
        FamilyLink.stopListeningForKick()
    }

    private fun updateFamilyStatus() {
        val code = AppLockPrefs.getFamilyCode(this)
        val name = AppLockPrefs.getFamilyName(this)
        val hasFamily = !code.isNullOrBlank()

        binding.btnMenuDashboard.visibility = if (hasFamily) View.VISIBLE else View.GONE
        binding.btnMenuLeaveFamily.visibility = if (hasFamily) View.VISIBLE else View.GONE

        if (hasFamily) {
            binding.tvFamilyDeletedNotice.visibility = View.GONE
        }

        if (hasFamily) {
            binding.tvMenuFamilyTitle.text = "Sudah Terhubung"
            binding.tvMenuFamilyStatus.text = "Keluarga: ${name?.takeIf { it.isNotBlank() } ?: code}"
            binding.tvMenuChangeFamilyTitle.text = "Ganti Keluarga"
            binding.tvMenuChangeFamilySubtitle.text = "Gabung ke keluarga lain"

            binding.ivFamilyStatusIcon.imageTintList =
                androidx.core.content.ContextCompat.getColorStateList(this, R.color.dash_child_primary)
            binding.cardFamilyStatus.setCardBackgroundColor(
                androidx.core.content.ContextCompat.getColor(this, R.color.dash_child_primary_soft)
            )
        } else {
            binding.tvMenuFamilyTitle.text = "Belum Terhubung"
            binding.tvMenuFamilyStatus.text = "Masukkan kode dari orang tua untuk gabung"
            binding.tvMenuChangeFamilyTitle.text = "Gabung Keluarga"
            binding.tvMenuChangeFamilySubtitle.text = "Masukkan kode keluarga"

            binding.ivFamilyStatusIcon.imageTintList =
                androidx.core.content.ContextCompat.getColorStateList(this, R.color.dash_text_secondary)
            binding.cardFamilyStatus.setCardBackgroundColor(
                androidx.core.content.ContextCompat.getColor(this, R.color.dash_surface)
            )
        }
    }
}