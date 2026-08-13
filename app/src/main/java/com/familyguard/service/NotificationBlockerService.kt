package com.familyguard.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.familyguard.utils.AppLockPrefs

/**
 * NotificationListenerService untuk intercept dan suppress
 * notifikasi dari aplikasi yang dikunci/diblokir.
 */
class NotificationBlockerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val packageName = sbn.packageName
        val blockedNotifApps = AppLockPrefs.getBlockedNotificationApps(this)

        if (blockedNotifApps.contains(packageName)) {
            Log.d(TAG, "Suppressing notification from: $packageName")
            try {
                cancelNotification(sbn.key)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel notification", e)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Not needed
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "NotificationBlockerService connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "NotificationBlockerService disconnected")
    }

    companion object {
        private const val TAG = "NotifBlockerService"
    }
}
