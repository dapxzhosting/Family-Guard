package com.familyguard.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.familyguard.ui.LockScreenActivity
import com.familyguard.utils.AppLockPrefs

/**
 * AccessibilityService untuk mendeteksi pergantian aplikasi
 * dan menampilkan layar kunci jika app tersebut dikunci.
 */
class AppLockAccessibilityService : AccessibilityService() {

    private var lastPackage: String = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // Periksa apakah perangkat sedang dikunci total (Device Lock)
        if (AppLockPrefs.isDeviceLocked(this)) {
            val packageName = event.packageName?.toString() ?: ""
            if (packageName != "com.familyguard") {
                showLockScreen("", true)
                return
            }
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            
            // Skip sistem & app kita sendiri
            if (packageName == "com.familyguard" || packageName == lastPackage) return
            
            lastPackage = packageName

            val lockedApps = AppLockPrefs.getLockedApps(this)
            if (lockedApps.contains(packageName)) {
                // Cek apakah baru saja dibuka dengan PIN (grace period 30 detik)
                if (AppLockPrefs.isPackageTemporarilyUnlocked(this, packageName)) {
                    Log.d(TAG, "App $packageName is temporarily unlocked, skipping lock")
                    return
                }

                Log.d(TAG, "Blocked app detected: $packageName → showing lock screen")
                showLockScreen(packageName, false)
            }
        }
    }

    private fun showLockScreen(packageName: String, isDeviceLock: Boolean) {
        val intent = Intent(this, LockScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(LockScreenActivity.EXTRA_LOCKED_PACKAGE, packageName)
            putExtra(LockScreenActivity.EXTRA_MODE, if (isDeviceLock) LockScreenActivity.MODE_DEVICE_LOCK else LockScreenActivity.MODE_APP_LOCK)
        }
        startActivity(intent)
    }

    override fun onInterrupt() {
        Log.w(TAG, "AppLockAccessibilityService interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "AppLock Accessibility Service connected")
    }

    companion object {
        private const val TAG = "AppLockService"
    }
}
