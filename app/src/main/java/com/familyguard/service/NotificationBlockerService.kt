package com.familyguard.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.familyguard.utils.AppLockPrefs

class NotificationBlockerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val packageName = sbn.packageName
        val blockedNotifApps = AppLockPrefs.getBlockedNotificationApps(this)

        if (blockedNotifApps.contains(packageName)) {

            try {
                cancelNotification(sbn.key)
            } catch (e: Exception) {

            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {

    }

    override fun onListenerConnected() {
        super.onListenerConnected()

    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()

    }

    companion object {
    }
}
