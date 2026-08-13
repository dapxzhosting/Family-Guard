package com.familyguard.utils

import android.content.Context
import android.content.SharedPreferences

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
}
