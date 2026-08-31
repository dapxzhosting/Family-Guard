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

import com.familyguard.sync.FamilyLink
import com.familyguard.ui.LockScreenActivity
import com.familyguard.utils.AppLockPrefs
import com.familyguard.utils.LocationHelper

class GuardService : Service() {

    private var screenStateReceiver: com.familyguard.receiver.ScreenStateReceiver? = null
    private val lockWatchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lockWatchRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())

        screenStateReceiver = com.familyguard.receiver.ScreenStateReceiver.register(this)

        FamilyLink.registerDevice(this)

        FamilyLink.startListening(this, isGlobal = true) { title, message ->
            showGlobalMessage(title, message)
        }

        if (AppLockPrefs.isDeviceLocked(this)) {
            showDeviceLockScreen()
        }

        startLockWatchdog()
        startPeriodicLocationUpdates()

        com.familyguard.receiver.GuardWatchdogReceiver.schedule(this)
    }

    private fun showDeviceLockScreen() {
        LockScreenActivity.requestDeviceLock(this)
    }

    private fun startLockWatchdog() {

        lockWatchRunnable?.let { lockWatchHandler.removeCallbacks(it) }

        val runnable = object : Runnable {
            override fun run() {
                if (AppLockPrefs.isDeviceLocked(this@GuardService) && !LockScreenActivity.isForeground) {
                    showDeviceLockScreen()
                }
                lockWatchHandler.postDelayed(this, 1000L)
            }
        }
        lockWatchRunnable = runnable
        lockWatchHandler.postDelayed(runnable, 1000L)
    }

    private fun startPeriodicLocationUpdates() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                LocationHelper.updateCurrentLocation(this@GuardService)
                FamilyLink.sendHeartbeat(this@GuardService)
                handler.postDelayed(this, 60_000)
            }
        }
        handler.post(runnable)
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
        com.familyguard.receiver.ScreenStateReceiver.unregister(this, screenStateReceiver)
        screenStateReceiver = null
        lockWatchRunnable?.let { lockWatchHandler.removeCallbacks(it) }
        lockWatchRunnable = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
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