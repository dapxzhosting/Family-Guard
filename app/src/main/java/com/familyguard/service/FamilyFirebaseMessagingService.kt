package com.familyguard.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.familyguard.R
import com.familyguard.admin.LockManager
import com.familyguard.ui.RoleSelectionActivity
import com.familyguard.utils.AppLockPrefs
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FamilyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)

        AppLockPrefs.saveFcmToken(this, token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val data = remoteMessage.data
        val command = data["command"] ?: run {

            remoteMessage.notification?.let { showMessageNotification(it.title, it.body) }
            return
        }

        when (command) {
            CMD_LOCK_SCREEN -> handleLockScreen()
            CMD_SEND_MESSAGE -> handleSendMessage(data["title"], data["message"])
            CMD_LOCK_APP -> handleLockApp(data["package_name"], data["action"])
            CMD_BLOCK_NOTIF -> handleBlockNotif(data["package_name"], data["action"])
            else -> Unit
        }
    }

    private fun handleLockScreen() {

        val lockManager = LockManager(this)
        val result = lockManager.lockScreen()
        result.onFailure { e ->

            showMessageNotification("Kunci Gagal", "Device Admin belum diaktifkan di HP ini.")
        }
    }

    private fun handleSendMessage(title: String?, message: String?) {

        showMessageNotification(
            title = title ?: "Pesan dari Orang Tua",
            body = message ?: ""
        )
    }

    private fun handleLockApp(packageName: String?, action: String?) {
        packageName ?: return

        when (action) {
            ACTION_ADD -> AppLockPrefs.addLockedApp(this, packageName)
            ACTION_REMOVE -> AppLockPrefs.removeLockedApp(this, packageName)
        }
    }

    private fun handleBlockNotif(packageName: String?, action: String?) {
        packageName ?: return

        when (action) {
            ACTION_ADD -> AppLockPrefs.addBlockedNotifApp(this, packageName)
            ACTION_REMOVE -> AppLockPrefs.removeBlockedNotifApp(this, packageName)
        }
    }

    private fun showMessageNotification(title: String?, body: String?) {
        val channelId = "family_message_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            channelId,
            "Pesan Keluarga",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Pesan dari orang tua"
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)

        val intent = Intent(this, RoleSelectionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_family)
            .setContentTitle(title ?: "FamilyGuard")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        nm.notify(System.currentTimeMillis().toInt(), notif)
    }

    companion object {

        const val CMD_LOCK_SCREEN = "lock_screen"
        const val CMD_SEND_MESSAGE = "send_message"
        const val CMD_LOCK_APP = "lock_app"
        const val CMD_BLOCK_NOTIF = "block_notif"

        const val ACTION_ADD = "add"
        const val ACTION_REMOVE = "remove"
    }
}

