package com.familyguard.utils

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings

/**
 * Utility untuk menyimpan konfigurasi FamilyGuard secara lokal:
 * - Daftar app yang dikunci
 * - Daftar app yang notifikasinya diblokir
 * - Status guard service
 * - FCM token
 */
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

    const val ROLE_PARENT = "PARENT"
    const val ROLE_CHILD = "CHILD"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ──────────────────────────────────────────────
    // LOCKED APPS
    // ──────────────────────────────────────────────

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

    fun isAppLocked(context: Context, packageName: String): Boolean =
        getLockedApps(context).contains(packageName)

    // ──────────────────────────────────────────────
    // BLOCKED NOTIFICATION APPS
    // ──────────────────────────────────────────────

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

    // ──────────────────────────────────────────────
    // GUARD SERVICE
    // ──────────────────────────────────────────────

    fun isGuardEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GUARD_ENABLED, false)

    fun setGuardEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GUARD_ENABLED, enabled).apply()
    }

    // ──────────────────────────────────────────────
    // FCM TOKEN
    // ──────────────────────────────────────────────

    fun saveFcmToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_FCM_TOKEN, token).apply()
    }

    fun getFcmToken(context: Context): String? =
        prefs(context).getString(KEY_FCM_TOKEN, null)

    // ──────────────────────────────────────────────
    // DEVICE PIN (untuk app lock screen)
    // ──────────────────────────────────────────────

    fun savePin(context: Context, pin: String) {
        prefs(context).edit().putString(KEY_DEVICE_PIN, pin).apply()
    }

    fun getPin(context: Context): String? =
        prefs(context).getString(KEY_DEVICE_PIN, null)

    fun hasPin(context: Context): Boolean = getPin(context) != null

    // ──────────────────────────────────────────────
    // FAMILY CODE & ROLE
    // ──────────────────────────────────────────────

    fun saveFamilyCode(context: Context, code: String) {
        prefs(context).edit().putString(KEY_FAMILY_CODE, code).apply()
    }

    fun getFamilyCode(context: Context): String? =
        prefs(context).getString(KEY_FAMILY_CODE, null)

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
            return elapsed < 30_000 // 30 detik grace period
        }
        return false
    }
}
