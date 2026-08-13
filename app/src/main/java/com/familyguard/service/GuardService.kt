package com.familyguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.familyguard.R
import com.familyguard.ui.MainActivity

/**
 * Foreground Service agar FamilyGuard tetap berjalan di background.
 * Tanpa ini, Android dapat mematikan proses saat tidak aktif.
 */
import com.familyguard.sync.FamilyLink
import com.familyguard.ui.LockScreenActivity

class GuardService : Service() {

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        
        // Start listening for remote commands globally
        FamilyLink.startListening(this, isGlobal = true) { title, message ->
            showGlobalMessage(title, message)
        }
    }

    private fun showGlobalMessage(title: String, message: String) {
        val intent = Intent(this, LockScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(LockScreenActivity.EXTRA_MODE, LockScreenActivity.MODE_MESSAGE)
            putExtra(LockScreenActivity.EXTRA_MESSAGE_TITLE, title)
            putExtra(LockScreenActivity.EXTRA_MESSAGE_BODY, message)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        FamilyLink.stopListening(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY // Restart otomatis jika dimatikan sistem
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val channelId = "guard_service_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            channelId,
            "FamilyGuard Active",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)

        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_family)
            .setContentTitle("FamilyGuard Aktif")
            .setContentText("Melindungi perangkat ini")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, GuardService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, GuardService::class.java)
            context.stopService(intent)
        }
    }
}
