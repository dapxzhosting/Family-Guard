package com.familyguard.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.familyguard.R
import com.familyguard.databinding.ActivityParentMenuBinding
import com.familyguard.sync.FamilyLink
import com.familyguard.utils.AccountActions
import com.familyguard.utils.AnimUtils
import com.familyguard.utils.AppLockPrefs

class ParentMenuActivity : BaseActivity() {

    private lateinit var binding: ActivityParentMenuBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParentMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateFamilyStatus()

        FamilyLink.listenFamilyDeletion(this) {
            FamilyLink.clearLocalFamilyState(this)
            Toast.makeText(this, "Keluarga telah dihapus", Toast.LENGTH_LONG).show()
            updateFamilyStatus()
        }

        binding.btnMenuDashboard.setOnClickListener {
            if (AppLockPrefs.getFamilyCode(this).isNullOrBlank()) {
                Toast.makeText(this, "Buat keluarga dulu sebelum buka dashboard", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, ParentDashboardActivity::class.java))
            }
        }

        binding.btnMenuCreateFamily.setOnClickListener {
            startActivity(Intent(this, FamilyNameActivity::class.java))
        }

        binding.btnMenuSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_up_in, R.anim.stay_dim)
        }

        binding.btnMenuDeleteFamily.setOnClickListener {
            if (AppLockPrefs.getFamilyCode(this).isNullOrBlank()) {
                Toast.makeText(this, "Belum ada keluarga untuk dihapus", Toast.LENGTH_SHORT).show()
            } else {
                AccountActions.deleteFamily(this) {

                    runOnUiThread { animateFamilyDeleted() }
                }
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

    private fun animateFamilyDeleted() {
        binding.tvMenuFamilyTitle.text = "Buat Keluarga"
        binding.tvMenuFamilyStatus.text = "Belum ada keluarga"
        binding.ivMenuFamilyIcon.setImageResource(R.drawable.ic_menu_family)
        binding.ivMenuFamilyIcon.imageTintList =
            androidx.core.content.ContextCompat.getColorStateList(this, R.color.dash_text_primary)
        binding.ivMenuFamilyChevron.visibility = View.VISIBLE
        binding.btnMenuCreateFamily.isClickable = true
        binding.btnMenuCreateFamily.isFocusable = true
        binding.btnMenuCreateFamily.cardElevation = resources.displayMetrics.density * 3
        binding.btnMenuCreateFamily.setCardBackgroundColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.dash_surface)
        )
        val outValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        binding.btnMenuCreateFamily.foreground = androidx.core.content.ContextCompat.getDrawable(this, outValue.resourceId)
        AnimUtils.attachPressAnimation(binding.btnMenuCreateFamily)
        AnimUtils.collapseAndHide(binding.btnMenuDashboard)
        AnimUtils.collapseAndHide(binding.btnMenuDeleteFamily)
    }

    override fun onResume() {
        super.onResume()
        updateFamilyStatus()
    }

    private fun updateFamilyStatus() {
        val code = AppLockPrefs.getFamilyCode(this)
        val name = AppLockPrefs.getFamilyName(this)
        val hasFamily = !code.isNullOrBlank()

        binding.btnMenuDashboard.visibility = if (hasFamily) View.VISIBLE else View.GONE
        binding.btnMenuDeleteFamily.visibility = if (hasFamily) View.VISIBLE else View.GONE

        if (hasFamily) {
            binding.tvMenuFamilyTitle.text = "Sudah Terhubung"
            binding.tvMenuFamilyStatus.text = "Keluarga: ${name?.takeIf { it.isNotBlank() } ?: code}"
            binding.ivMenuFamilyIcon.setImageResource(R.drawable.ic_check_circle)
            binding.ivMenuFamilyIcon.imageTintList =
                androidx.core.content.ContextCompat.getColorStateList(this, R.color.dash_info)
            binding.ivMenuFamilyChevron.visibility = View.GONE
            binding.btnMenuCreateFamily.isClickable = false
            binding.btnMenuCreateFamily.isFocusable = false

            binding.btnMenuCreateFamily.cardElevation = 0f
            binding.btnMenuCreateFamily.setCardBackgroundColor(
                androidx.core.content.ContextCompat.getColor(this, R.color.dash_info_soft)
            )
            binding.btnMenuCreateFamily.foreground = null
            binding.btnMenuCreateFamily.setOnTouchListener(null)
        } else {
            binding.tvMenuFamilyTitle.text = "Buat Keluarga"
            binding.tvMenuFamilyStatus.text = "Belum ada keluarga"
            binding.ivMenuFamilyIcon.setImageResource(R.drawable.ic_menu_family)
            binding.ivMenuFamilyIcon.imageTintList =
                androidx.core.content.ContextCompat.getColorStateList(this, R.color.dash_text_primary)
            binding.ivMenuFamilyChevron.visibility = View.VISIBLE
            binding.btnMenuCreateFamily.isClickable = true
            binding.btnMenuCreateFamily.isFocusable = true
            binding.btnMenuCreateFamily.cardElevation = resources.displayMetrics.density * 3
            binding.btnMenuCreateFamily.setCardBackgroundColor(
                androidx.core.content.ContextCompat.getColor(this, R.color.dash_surface)
            )
            val outValue = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            binding.btnMenuCreateFamily.foreground = androidx.core.content.ContextCompat.getDrawable(this, outValue.resourceId)
            AnimUtils.attachPressAnimation(binding.btnMenuCreateFamily)
        }
    }
}
