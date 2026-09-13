package com.familyguard.ui

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
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
            binding.circleSettingsRole.backgroundTintList = android.content.res.ColorStateList.valueOf(roleColor)
            binding.ivSettingsPrivacyIcon.imageTintList =
                androidx.core.content.ContextCompat.getColorStateList(this, R.color.dash_child_primary)
        } else {
            val roleColor = androidx.core.content.ContextCompat.getColor(this, R.color.dash_primary)
            binding.circleSettingsName.backgroundTintList = android.content.res.ColorStateList.valueOf(roleColor)
            binding.circleSettingsEmail.backgroundTintList = android.content.res.ColorStateList.valueOf(roleColor)
            binding.circleSettingsRole.backgroundTintList = android.content.res.ColorStateList.valueOf(roleColor)
            binding.ivSettingsPrivacyIcon.imageTintList =
                androidx.core.content.ContextCompat.getColorStateList(this, R.color.dash_primary)
        }

        binding.tvSettingsRole.text = if (AppLockPrefs.getRole(this) == AppLockPrefs.ROLE_CHILD) {
            "Anak"
        } else {
            "Orang Tua"
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
        val dialog = android.app.Dialog(this, R.style.BottomSlideDialog)
        dialog.setContentView(R.layout.dialog_edit_name)
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(android.view.Gravity.BOTTOM)

        val root = dialog.findViewById<android.view.View>(R.id.dialogRoot)
        val dragArea = dialog.findViewById<android.view.View>(R.id.dialogDragArea)
        val input = dialog.findViewById<EditText>(R.id.etDialogName)
        val btnCancel = dialog.findViewById<android.widget.Button>(R.id.btnDialogCancel)
        val btnSave = dialog.findViewById<android.widget.Button>(R.id.btnDialogSave)

        val roleColor = if (AppLockPrefs.getRole(this) == AppLockPrefs.ROLE_CHILD) {
            androidx.core.content.ContextCompat.getColor(this, R.color.dash_child_primary)
        } else {
            androidx.core.content.ContextCompat.getColor(this, R.color.dash_primary)
        }
        btnSave.backgroundTintList = android.content.res.ColorStateList.valueOf(roleColor)

        input.setText(AppLockPrefs.getUserName(this) ?: "")
        input.setSelection(input.text.length)

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
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
                dialog.dismiss()
            }
        }

        setupDialogDrag(root, dragArea, dialog)

        dialog.show()
    }

    private fun setupDialogDrag(
        root: android.view.View,
        dragArea: android.view.View,
        dialog: android.app.Dialog
    ) {
        var downY = 0f
        val dismissThreshold = 180 * resources.displayMetrics.density

        dragArea.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val delta = event.rawY - downY
                    if (delta > 0) {
                        root.translationY = delta
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (root.translationY > dismissThreshold) {
                        root.animate()
                            .translationY(root.height.toFloat() + root.translationY)
                            .setDuration(200)
                            .withEndAction { dialog.dismiss() }
                            .start()
                    } else {
                        root.animate()
                            .translationY(0f)
                            .setDuration(200)
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .start()
                    }
                    true
                }
                else -> false
            }
        }
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