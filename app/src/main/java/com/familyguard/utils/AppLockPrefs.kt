package com.familyguard.utils

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings

object AppLockPrefs {

    private const val PREF_NAME = "family_guard_prefs"
    private const val KEY_LOCKED_APPS = "locked_apps"
    private const val KEY_BLOCKED_NOTIF_APPS = "blocked_notif_apps"
    private const val KEY_GUARD_ENABLED = "guard_enabled"
    private const val KEY_FCM_TOKEN = "fcm_token"
    private const val KEY_DEVICE_PIN = "device_pin"
    private const val KEY_FAMILY_CODE = "family_code"
    private const val KEY_ROLE = "role"
    private const val KEY_DEVICE_LOCKED = "device_locked"
    private const val KEY_LAST_UNLOCKED_PACKAGE = "last_unlocked_pkg"
    private const val KEY_LAST_UNLOCKED_TIME = "last_unlocked_time"
    private const val KEY_APPROVED_UNLOCK_PACKAGE = "approved_unlock_pkg"
    private const val KEY_APPROVED_UNLOCK_UNTIL = "approved_unlock_until"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_FAMILY_NAME = "family_name"

    const val ROLE_PARENT = "PARENT"
    const val ROLE_CHILD = "CHILD"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getLockedApps(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_LOCKED_APPS, emptySet()) ?: emptySet()

    fun addLockedApp(context: Context, packageName: String) {
        val current = getLockedApps(context).toMutableSet()
        current.add(packageName)
        prefs(context).edit().putStringSet(KEY_LOCKED_APPS, current).apply()
    }

    fun removeLockedApp(context: Context, packageName: String) {
        val current = getLockedApps(context).toMutableSet()
        current.remove(packageName)
        prefs(context).edit().putStringSet(KEY_LOCKED_APPS, current).apply()
    }

    /** Tambah banyak package ke locked_apps dalam SATU write (atomic, bukan loop per-app). */
    fun addLockedApps(context: Context, packageNames: Collection<String>) {
        val current = getLockedApps(context).toMutableSet()
        current.addAll(packageNames)
        prefs(context).edit().putStringSet(KEY_LOCKED_APPS, current).apply()
    }

    /** Hapus banyak package dari locked_apps dalam SATU write (atomic, bukan loop per-app). */
    fun removeLockedApps(context: Context, packageNames: Collection<String>) {
        val current = getLockedApps(context).toMutableSet()
        current.removeAll(packageNames.toSet())
        prefs(context).edit().putStringSet(KEY_LOCKED_APPS, current).apply()
    }

    fun isAppLocked(context: Context, packageName: String): Boolean =
        getLockedApps(context).contains(packageName)

    fun getBlockedNotificationApps(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_BLOCKED_NOTIF_APPS, emptySet()) ?: emptySet()

    fun addBlockedNotifApp(context: Context, packageName: String) {
        val current = getBlockedNotificationApps(context).toMutableSet()
        current.add(packageName)
        prefs(context).edit().putStringSet(KEY_BLOCKED_NOTIF_APPS, current).apply()
    }

    fun removeBlockedNotifApp(context: Context, packageName: String) {
        val current = getBlockedNotificationApps(context).toMutableSet()
        current.remove(packageName)
        prefs(context).edit().putStringSet(KEY_BLOCKED_NOTIF_APPS, current).apply()
    }

    fun isGuardEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GUARD_ENABLED, false)

    fun setGuardEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GUARD_ENABLED, enabled).apply()
    }

    fun saveFcmToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_FCM_TOKEN, token).apply()
    }

    fun getFcmToken(context: Context): String? =
        prefs(context).getString(KEY_FCM_TOKEN, null)

    fun savePin(context: Context, pin: String) {
        // Pakai commit() (sinkron), BUKAN apply() (async) -- PIN ini status kritis
        // yang harus sudah pasti nyimpen ke disk sebelum command "set_pin" dianggap
        // selesai. Kalau proses HP anak kebunuh (battery killer OEM semacam
        // Itel/Tecno/Infinix) tepat setelah apply() tapi sebelum write ke disk
        // kelar, restart berikutnya bakal baca PIN sebagai "belum ada" lagi --
        // dan itu bikin registerDevice() menimpa balik hasPin jadi false di
        // Firebase padahal barusan sudah diset (lihat registerDevice()).
        prefs(context).edit().putString(KEY_DEVICE_PIN, pin).commit()
    }

    fun getPin(context: Context): String? =
        prefs(context).getString(KEY_DEVICE_PIN, null)

    fun hasPin(context: Context): Boolean = getPin(context) != null

    /**
     * Reset SEMUA status keamanan yang tersimpan lokal di HP ini (PIN,
     * daftar app terkunci, blokir notif, status kunci perangkat, unlock
     * sementara yang disetujui).
     *
     * WAJIB dipanggil setiap kali hubungan device<->keluarga berubah (keluar
     * keluarga, keluarga dihapus orang tua, kena kick, ganti keluarga) --
     * kalau tidak, status lama nyangkut dan ke-carry over ke keluarga baru:
     * PIN lama tetap aktif, dan registerDevice() bakal langsung nulis
     * hasPin=true ke keluarga baru padahal orang tua belum pernah atur PIN
     * untuk keluarga itu, jadi fitur Kunci Layar/Kunci App bisa langsung
     * dipakai tanpa PIN baru pernah diset -- sekaligus bikin dialog "Atur
     * PIN" di sisi orang tua gak nemu currentPin (karena currentPin memang
     * belum pernah ditulis untuk keluarga baru ini) walau hasPin sudah true.
     */
    fun clearFamilySecurityState(context: Context) {
        prefs(context).edit()
            .remove(KEY_DEVICE_PIN)
            .remove(KEY_LOCKED_APPS)
            .remove(KEY_BLOCKED_NOTIF_APPS)
            .remove(KEY_DEVICE_LOCKED)
            .remove(KEY_LAST_UNLOCKED_PACKAGE)
            .remove(KEY_LAST_UNLOCKED_TIME)
            .remove(KEY_APPROVED_UNLOCK_PACKAGE)
            .remove(KEY_APPROVED_UNLOCK_UNTIL)
            .commit()
    }

    fun saveFamilyCode(context: Context, code: String) {
        prefs(context).edit().putString(KEY_FAMILY_CODE, code).apply()
    }

    fun getFamilyCode(context: Context): String? =
        prefs(context).getString(KEY_FAMILY_CODE, null)?.takeIf { it.isNotBlank() }

    fun saveRole(context: Context, role: String) {
        prefs(context).edit().putString(KEY_ROLE, role).apply()
    }

    fun getRole(context: Context): String? =
        prefs(context).getString(KEY_ROLE, null)

    fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
    }

    fun setDeviceLocked(context: Context, locked: Boolean) {
        prefs(context).edit().putBoolean(KEY_DEVICE_LOCKED, locked).apply()
    }
    fun isDeviceLocked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEVICE_LOCKED, false)

    fun setPackageUnlocked(context: Context, packageName: String) {
        prefs(context).edit()
            .putString(KEY_LAST_UNLOCKED_PACKAGE, packageName)
            .putLong(KEY_LAST_UNLOCKED_TIME, System.currentTimeMillis())
            .apply()
    }

    fun isPackageTemporarilyUnlocked(context: Context, packageName: String): Boolean {
        val lastPkg = prefs(context).getString(KEY_LAST_UNLOCKED_PACKAGE, null)
        val lastTime = prefs(context).getLong(KEY_LAST_UNLOCKED_TIME, 0L)

        if (lastPkg == packageName) {
            val elapsed = System.currentTimeMillis() - lastTime
            if (elapsed < 30_000) return true
        }
        return isPackageApprovedUnlocked(context, packageName)
    }

    /**
     * Buka sementara satu app untuk [durationMinutes] menit setelah Orang
     * Tua menyetujui approval request (lihat FamilyLink.respondApprovalRequest
     * dan FamilyFirebaseMessagingService/GuardCommand handler untuk
     * "temp_unlock_app"). Beda dengan setPackageUnlocked() yang cuma untuk
     * jeda 30 detik pasca-PIN -- ini bisa berdurasi berjam-jam sesuai yang
     * disetujui Orang Tua.
     */
    fun setApprovedTemporaryUnlock(context: Context, packageName: String, durationMinutes: Int) {
        val untilMillis = System.currentTimeMillis() + (durationMinutes.coerceAtLeast(1) * 60_000L)
        prefs(context).edit()
            .putString(KEY_APPROVED_UNLOCK_PACKAGE, packageName)
            .putLong(KEY_APPROVED_UNLOCK_UNTIL, untilMillis)
            .apply()
    }

    private fun isPackageApprovedUnlocked(context: Context, packageName: String): Boolean {
        val approvedPkg = prefs(context).getString(KEY_APPROVED_UNLOCK_PACKAGE, null)
        val until = prefs(context).getLong(KEY_APPROVED_UNLOCK_UNTIL, 0L)
        return approvedPkg == packageName && System.currentTimeMillis() < until
    }

    fun saveUserName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_USER_NAME, name).apply()
    }

    fun getUserName(context: Context): String? =
        prefs(context).getString(KEY_USER_NAME, null)

    fun saveFamilyName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_FAMILY_NAME, name).apply()
    }

    fun getFamilyName(context: Context): String? =
        prefs(context).getString(KEY_FAMILY_NAME, null)

    fun clearRoleAndFamily(context: Context) {
        prefs(context).edit()
            .remove(KEY_ROLE)
            .remove(KEY_FAMILY_CODE)
            .remove(KEY_FAMILY_NAME)
            .remove(KEY_DEVICE_LOCKED)
            .apply()
    }

    fun clearAllForLogout(context: Context) {
        prefs(context).edit().clear().apply()
    }
}