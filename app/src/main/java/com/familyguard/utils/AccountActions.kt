package com.familyguard.utils

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.familyguard.sync.FamilyLink
import com.familyguard.ui.LoginActivity
import com.familyguard.ui.RoleSelectionActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth

object AccountActions {

    fun resetRole(activity: Activity) {
        val role = AppLockPrefs.getRole(activity)
        val isParentWithFamily = role == AppLockPrefs.ROLE_PARENT &&
                !AppLockPrefs.getFamilyCode(activity).isNullOrBlank()

        val message = if (isParentWithFamily) {
            "Perangkat ini akan keluar dari role & keluarga saat ini. " +
                    "Karena kamu Orang Tua, seluruh keluarga ini beserta semua " +
                    "HP anak yang terhubung akan ikut terhapus permanen, " +
                    "mereka akan otomatis terputus. Kamu tetap login dengan akun " +
                    "Google yang sama, dan bisa pilih role serta keluarga dari awal lagi."
        } else {
            "Perangkat ini akan keluar dari role & keluarga saat ini. " +
                    "Kamu tetap login dengan akun Google yang sama, dan bisa " +
                    "pilih role (Orang Tua/Anak) serta keluarga dari awal lagi."
        }

        showConfirmDialog(
            activity = activity,
            iconRes = com.familyguard.R.drawable.ic_menu_reset,
            title = "Reset Role?",
            message = message,
            confirmText = "Reset"
        ) {
            Toast.makeText(activity, "Mereset role...", Toast.LENGTH_SHORT).show()
            FamilyLink.stopListeningForKick()

            if (isParentWithFamily) {
                FamilyLink.deleteFamilyEntirely(activity) {
                    FamilyLink.clearRemoteRoleAndFamily(activity) {
                        AppLockPrefs.clearRoleAndFamily(activity)
                        goToRoleSelection(activity)
                    }
                }
            } else {
                FamilyLink.removeDeviceFromCurrentFamily(activity) {
                    FamilyLink.clearRemoteRoleAndFamily(activity) {
                        AppLockPrefs.clearRoleAndFamily(activity)
                        goToRoleSelection(activity)
                    }
                }
            }
        }
    }

    fun leaveCurrentFamily(activity: Activity, onDone: () -> Unit) {

        FamilyLink.stopListeningForKick()

        FamilyLink.removeDeviceFromCurrentFamily(activity) {
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (uid != null) {
                com.google.firebase.database.FirebaseDatabase.getInstance().reference
                    .child("users").child(uid).child("familyCode").removeValue()
                    .addOnCompleteListener {
                        AppLockPrefs.saveFamilyCode(activity, "")
                        AppLockPrefs.saveFamilyName(activity, "")
                        AppLockPrefs.setDeviceLocked(activity, false)
                        AppLockPrefs.clearFamilySecurityState(activity)
                        onDone()
                    }
            } else {
                AppLockPrefs.saveFamilyCode(activity, "")
                AppLockPrefs.saveFamilyName(activity, "")
                onDone()
            }
        }
    }

    fun changeFamily(activity: Activity) {
        val role = AppLockPrefs.getRole(activity)
        showConfirmDialog(
            activity = activity,
            iconRes = com.familyguard.R.drawable.ic_menu_family,
            title = "Ganti Keluarga?",
            message = "Kamu akan diminta memasukkan kode keluarga baru. " +
                    "Keanggotaan di keluarga saat ini baru akan dilepas " +
                    "setelah kode baru berhasil dimasukkan. Role kamu " +
                    "(${roleLabel(role)}) tidak berubah.",
            confirmText = "Lanjut"
        ) {
            goToFamilyEntry(activity, role)
        }
    }

    fun leaveFamily(activity: Activity, onDone: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("Keluar dari Keluarga?")
            .setMessage(
                "Kamu akan keluar dari keluarga saat ini. Orang tua akan " +
                        "diberi tahu bahwa kamu sudah keluar. Kamu tetap " +
                        "berperan sebagai Anak dan bisa gabung ke keluarga " +
                        "lain kapan saja lewat kode keluarga."
            )
            .setPositiveButton("Keluar") { _, _ ->
                Toast.makeText(activity, "Keluar dari keluarga...", Toast.LENGTH_SHORT).show()
                FamilyLink.notifyChildLeftFamily(activity) {
                    leaveCurrentFamily(activity) {
                        Toast.makeText(activity, "Kamu telah keluar dari keluarga", Toast.LENGTH_SHORT).show()
                        onDone()
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    fun logout(activity: Activity) {
        showConfirmDialog(
            activity = activity,
            iconRes = com.familyguard.R.drawable.ic_logout,
            title = "Keluar Akun?",
            message = "Kamu akan keluar dari akun Google ini di perangkat ini.",
            confirmText = "Keluar"
        ) {
            doLogout(activity)
        }
    }

    fun deleteFamily(activity: Activity, onDone: (() -> Unit)? = null) {
        showConfirmDialog(
            activity = activity,
            iconRes = com.familyguard.R.drawable.ic_menu_delete,
            title = "Hapus Keluarga?",
            message = "Seluruh data keluarga ini akan dihapus permanen, termasuk semua " +
                    "HP anak yang terhubung. Mereka akan otomatis terputus. " +
                    "Tindakan ini tidak bisa dibatalkan.",
            confirmText = "Hapus"
        ) {
            Toast.makeText(activity, "Menghapus keluarga...", Toast.LENGTH_SHORT).show()
            FamilyLink.deleteFamilyEntirely(activity) {
                val uid = FirebaseAuth.getInstance().currentUser?.uid
                if (uid != null) {
                    com.google.firebase.database.FirebaseDatabase.getInstance().reference
                        .child("users").child(uid).child("familyCode").removeValue()
                        .addOnCompleteListener {
                            AppLockPrefs.saveFamilyCode(activity, "")
                            AppLockPrefs.saveFamilyName(activity, "")
                            AppLockPrefs.setDeviceLocked(activity, false)
                            AppLockPrefs.clearFamilySecurityState(activity)
                            Toast.makeText(activity, "Keluarga berhasil dihapus", Toast.LENGTH_SHORT).show()
                            onDone?.invoke()
                        }
                } else {
                    AppLockPrefs.saveFamilyCode(activity, "")
                    AppLockPrefs.saveFamilyName(activity, "")
                    AppLockPrefs.clearFamilySecurityState(activity)
                    Toast.makeText(activity, "Keluarga berhasil dihapus", Toast.LENGTH_SHORT).show()
                    onDone?.invoke()
                }
            }
        }
    }

    private fun doLogout(activity: Activity) {
        FamilyLink.markLoggedOut(activity) {
            val googleSignInClient = GoogleSignIn.getClient(
                activity,
                GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
            )
            FirebaseAuth.getInstance().signOut()
            googleSignInClient.signOut().addOnCompleteListener {
                AppLockPrefs.clearAllForLogout(activity)
                val intent = Intent(activity, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                activity.startActivity(intent)
                activity.finish()
            }
        }
    }

    private fun goToRoleSelection(activity: Activity) {
        val intent = Intent(activity, RoleSelectionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        activity.startActivity(intent)
        activity.finish()
    }

    private fun goToFamilyEntry(activity: Activity, role: String?) {
        val target = if (role == AppLockPrefs.ROLE_PARENT) {
            com.familyguard.ui.FamilyNameActivity::class.java
        } else {
            com.familyguard.ui.FamilyCodeActivity::class.java
        }
        val intent = Intent(activity, target).apply {
            putExtra(com.familyguard.ui.FamilyCodeActivity.EXTRA_IS_CHANGE_FAMILY, true)
        }
        activity.startActivity(intent)
        activity.finish()
    }

    private fun roleLabel(role: String?) = when (role) {
        AppLockPrefs.ROLE_PARENT -> "Orang Tua"
        AppLockPrefs.ROLE_CHILD -> "Anak"
        else -> "-"
    }

    private fun roleAccentColor(activity: Activity): Int {
        val role = AppLockPrefs.getRole(activity)
        val colorRes = if (role == AppLockPrefs.ROLE_CHILD) {
            com.familyguard.R.color.dash_child_primary
        } else {
            com.familyguard.R.color.dash_primary
        }
        return androidx.core.content.ContextCompat.getColor(activity, colorRes)
    }

    private fun showConfirmDialog(
        activity: Activity,
        iconRes: Int,
        title: String,
        message: String,
        confirmText: String,
        onConfirm: () -> Unit
    ) {
        val dialog = android.app.Dialog(activity, com.familyguard.R.style.BottomSlideDialog)
        dialog.setContentView(com.familyguard.R.layout.dialog_confirm_action)
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(android.view.Gravity.BOTTOM)

        val root = dialog.findViewById<android.view.View>(com.familyguard.R.id.dialogRoot)
        val dragArea = dialog.findViewById<android.view.View>(com.familyguard.R.id.dialogDragArea)
        val iconCircle = dialog.findViewById<android.view.View>(com.familyguard.R.id.confirmIconCircle)
        val icon = dialog.findViewById<android.widget.ImageView>(com.familyguard.R.id.ivConfirmIcon)
        val tvTitle = dialog.findViewById<android.widget.TextView>(com.familyguard.R.id.tvConfirmTitle)
        val tvMessage = dialog.findViewById<android.widget.TextView>(com.familyguard.R.id.tvConfirmMessage)
        val btnCancel = dialog.findViewById<android.widget.Button>(com.familyguard.R.id.btnConfirmCancel)
        val btnOk = dialog.findViewById<android.widget.Button>(com.familyguard.R.id.btnConfirmOk)

        val roleColor = roleAccentColor(activity)

        iconCircle.backgroundTintList = android.content.res.ColorStateList.valueOf(roleColor)
        icon.setImageResource(iconRes)
        icon.imageTintList = android.content.res.ColorStateList.valueOf(roleColor)
        tvTitle.text = title
        tvMessage.text = message
        btnOk.text = confirmText
        btnOk.backgroundTintList = android.content.res.ColorStateList.valueOf(roleColor)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnOk.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }

        val dismissThreshold = 180 * activity.resources.displayMetrics.density
        var downY = 0f
        dragArea.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val delta = event.rawY - downY
                    if (delta > 0) root.translationY = delta
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

        dialog.show()
    }
}
