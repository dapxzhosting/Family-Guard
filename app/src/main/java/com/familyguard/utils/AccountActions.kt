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

/**
 * Aksi akun yang dipakai bersama oleh ParentDashboardActivity & ChildHomeActivity,
 * supaya logikanya SATU tempat dan konsisten di kedua dashboard (sebelumnya
 * masing-masing dashboard punya implementasi sendiri-sendiri yang tidak lengkap,
 * mis. cuma hapus data lokal tapi lupa hapus data remote -> data lama "kembali"
 * lagi pas login/fetch ulang).
 */
object AccountActions {

    /**
     * RESET ROLE: keluar dari role & keluarga saat ini, tapi TETAP login
     * dengan akun Google yang sama. Dipakai kalau salah pilih role (Orang
     * Tua/Anak) atau mau setup ulang device ini dari awal.
     *
     * Urutan penting: hapus data REMOTE dulu (device dari keluarga lama +
     * role/kode di profil), baru hapus data LOKAL, baru pindah activity.
     * Kalau urutannya dibalik (lokal dulu baru remote), ada risiko user
     * keburu pindah activity duluan sebelum panggilan remote selesai.
     */
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
     * GANTI / TAMBAH KELUARGA: mirip resetRole, tapi role (Orang Tua/Anak)
     * TETAP dipertahankan -- cuma keluarnya dari keluarga yang sekarang,
     * lalu langsung diarahkan ke alur masuk/buat keluarga baru (skip halaman
     * pilih role, karena role-nya tidak berubah).
     */
    fun changeFamily(activity: Activity) {
        val role = AppLockPrefs.getRole(activity)
        AlertDialog.Builder(activity)
            .setTitle("Ganti Keluarga?")
            .setMessage(
                "Perangkat ini akan keluar dari keluarga saat ini dan bisa " +
                        "gabung atau buat keluarga baru. Role kamu (${roleLabel(role)}) " +
                        "tidak berubah."
            )
            .setPositiveButton("Lanjut") { _, _ ->
                Toast.makeText(activity, "Keluar dari keluarga saat ini...", Toast.LENGTH_SHORT).show()
                FamilyLink.removeDeviceFromCurrentFamily(activity) {
                    // Hanya hapus familyCode di remote, role tetap dipertahankan
                    val uid = FirebaseAuth.getInstance().currentUser?.uid
                    if (uid != null) {
                        com.google.firebase.database.FirebaseDatabase.getInstance().reference
                            .child("users").child(uid).child("familyCode").removeValue()
                            .addOnCompleteListener {
                                AppLockPrefs.saveFamilyCode(activity, "")
                                AppLockPrefs.saveFamilyName(activity, "")
                                AppLockPrefs.setDeviceLocked(activity, false)
                                goToFamilyEntry(activity, role)
                            }
                    } else {
                        AppLockPrefs.saveFamilyCode(activity, "")
                        AppLockPrefs.saveFamilyName(activity, "")
                        goToFamilyEntry(activity, role)
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /**
     * LOGOUT: keluar dari akun Google sepenuhnya (Firebase Auth + Google
     * Sign-In session), hapus SEMUA data lokal, kembali ke LoginActivity.
     * Device TIDAK dikeluarkan dari keluarga (kalau nanti login lagi pakai
     * akun yang sama, harusnya balik ke keluarga yang sama juga -- beda
     * dengan Reset Role/Ganti Keluarga yang memang sengaja melepas ikatan
     * keluarga).
     */
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

    /**
     * HAPUS KELUARGA: berbeda dari changeFamily() -- ini menghapus SELURUH
     * node keluarga di Firebase (families/{code}), bukan cuma device ini
     * yang keluar. Semua HP anak yang masih terhubung otomatis kehilangan
     * koneksi karena keluarganya sudah tidak ada lagi. Role tetap Orang Tua,
     * cuma kode & nama keluarga lokal ikut dibersihkan supaya bisa langsung
     * "Buat Keluarga" baru dari menu. Dipakai khusus dari DashboardActivity
     * (menu utama Orang Tua).
     */
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
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
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
