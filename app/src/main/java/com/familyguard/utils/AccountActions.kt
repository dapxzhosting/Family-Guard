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
        AlertDialog.Builder(activity)
            .setTitle("Reset Role?")
            .setMessage(
                "Perangkat ini akan keluar dari role & keluarga saat ini. " +
                        "Kamu tetap login dengan akun Google yang sama, dan bisa " +
                        "pilih role (Orang Tua/Anak) serta keluarga dari awal lagi."
            )
            .setPositiveButton("Reset") { _, _ ->
                Toast.makeText(activity, "Mereset role...", Toast.LENGTH_SHORT).show()
                FamilyLink.stopListeningForKick()
                FamilyLink.removeDeviceFromCurrentFamily(activity) {
                    FamilyLink.clearRemoteRoleAndFamily(activity) {
                        AppLockPrefs.clearRoleAndFamily(activity)
                        goToRoleSelection(activity)
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /**
     * Melepas keanggotaan dari keluarga saat ini di Firebase & local prefs.
     * Dipanggil setelah kode keluarga baru berhasil divalidasi, supaya
     * pengguna tidak berakhir di kondisi "tanpa keluarga" kalau proses
     * ganti keluarga dibatalkan di tengah jalan.
     */
    fun leaveCurrentFamily(activity: Activity, onDone: () -> Unit) {
        // Matikan dulu listener "kick" SEBELUM device node kita sendiri
        // dihapus -- listenForKick() cuma lihat "device node ini hilang
        // sementara keluarga masih ada" tanpa tahu SIAPA yang menghapusnya,
        // jadi kalau tidak dimatikan dulu, penghapusan yang kita lakukan
        // sendiri (leave/ganti keluarga) akan salah terdeteksi sebagai
        // "dikeluarkan oleh orang tua".
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
        AlertDialog.Builder(activity)
            .setTitle("Ganti Keluarga?")
            .setMessage(
                "Kamu akan diminta memasukkan kode keluarga baru. " +
                        "Keanggotaan di keluarga saat ini baru akan dilepas " +
                        "setelah kode baru berhasil dimasukkan. Role kamu " +
                        "(${roleLabel(role)}) tidak berubah."
            )
            .setPositiveButton("Lanjut") { _, _ ->
                goToFamilyEntry(activity, role)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /**
     * Khusus role Anak: keluar dari keluarga saat ini TANPA langsung join
     * keluarga lain (beda dengan changeFamily). Sebelum device node
     * dihapus, kirim dulu notice ke families/{code}/childLeftNotice supaya
     * Orang Tua tetap tahu anaknya keluar walau device-nya sudah tercabut
     * dari daftar anggota.
     */
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
        AlertDialog.Builder(activity)
            .setTitle("Keluar Akun?")
            .setMessage("Kamu akan keluar dari akun Google ini di perangkat ini.")
            .setPositiveButton("Keluar") { _, _ ->
                doLogout(activity)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    fun deleteFamily(activity: Activity, onDone: (() -> Unit)? = null) {
        AlertDialog.Builder(activity)
            .setTitle("Hapus Keluarga?")
            .setMessage(
                "Seluruh data keluarga ini akan dihapus permanen, termasuk semua " +
                        "HP anak yang terhubung -- mereka akan otomatis terputus. " +
                        "Tindakan ini tidak bisa dibatalkan."
            )
            .setPositiveButton("Hapus") { _, _ ->
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
                                Toast.makeText(activity, "Keluarga berhasil dihapus", Toast.LENGTH_SHORT).show()
                                onDone?.invoke()
                            }
                    } else {
                        AppLockPrefs.saveFamilyCode(activity, "")
                        AppLockPrefs.saveFamilyName(activity, "")
                        Toast.makeText(activity, "Keluarga berhasil dihapus", Toast.LENGTH_SHORT).show()
                        onDone?.invoke()
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
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
}