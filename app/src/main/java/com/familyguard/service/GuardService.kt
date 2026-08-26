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
import com.familyguard.utils.AppLockPrefs
import com.familyguard.utils.LocationHelper

class GuardService : Service() {

    private var screenStateReceiver: com.familyguard.receiver.ScreenStateReceiver? = null
    private val lockWatchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lockWatchRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())

        // FIX: pasang listener SCREEN_ON/USER_PRESENT selama GuardService hidup,
        // supaya begitu layar dinyalakan lagi (habis dimatiin sebentar lewat
        // tombol power), LockScreenActivity (mode DEVICE_LOCK) langsung muncul
        // lagi otomatis kalau memang statusnya sedang locked. Sebelumnya lock
        // screen cuma dimunculkan sekali waktu service ini pertama dibuat,
        // jadi begitu layar off/on, anak langsung nyampe ke homescreen tanpa PIN.
        screenStateReceiver = com.familyguard.receiver.ScreenStateReceiver.register(this)

        // Registrasi ulang device setiap kali service start (bukan cuma sekali pas
        // pairing) supaya status "online" & onDisconnect handler selalu ter-arm dengan
        // koneksi Firebase yang aktif saat ini. Lihat catatan di FamilyLink.sendHeartbeat().
        FamilyLink.registerDevice(this)

        // Start listening for remote commands globally
        FamilyLink.startListening(this, isGlobal = true) { title, message ->
            showGlobalMessage(title, message)
        }

        if (AppLockPrefs.isDeviceLocked(this)) {
            showDeviceLockScreen()
        }

        // FIX: "tinggal hapus/swipe jendela LockScreen dari recents malah bisa
        // kebuka". Sebelumnya LockScreenActivity cuma relaunch dirinya sendiri
        // lewat onPause()/onUserLeaveHint() -- tapi kalau task-nya di-remove
        // paksa dari recents (swipe di overview, atau "close" di multi-window),
        // activity langsung ke onDestroy dan TIDAK ADA yang munculin lagi
        // (proses relaunch dari dalam activity yang sedang dihancurkan sendiri
        // gampang gagal/ke-cancel bareng task-nya).
        //
        // Makanya pengecekan "apakah lock screen masih tampil" dipindah ke SINI,
        // di GuardService yang berjalan independen (foreground service, task
        // terpisah dari LockScreenActivity) -- polling ketat tiap 1 detik selama
        // status masih locked, dan langsung relaunch begitu terdeteksi hilang,
        // dari LUAR activity itu sendiri, jadi tidak ikut mati kalau task-nya
        // di-swipe/dihapus.
        startLockWatchdog()
        startPeriodicLocationUpdates()

        // Re-arm watchdog setiap kali service ini hidup (baik start normal
        // maupun di-restart otomatis oleh watchdog itu sendiri).
        com.familyguard.receiver.GuardWatchdogReceiver.schedule(this)
    }

    private fun showDeviceLockScreen() {
        LockScreenActivity.requestDeviceLock(this)
    }

    /**
     * Polling ketat dari dalam Service (bukan dari Activity) yang memastikan
     * LockScreenActivity SELALU tampil selama AppLockPrefs.isDeviceLocked==true.
     * Dicek tiap 1 detik -- cukup rapat supaya celah waktu anak bisa pakai HP
     * setelah swipe/hapus jendela lock screen dari recents jadi sangat singkat,
     * tapi tidak terlalu rapat untuk baterai/CPU.
     */
    private fun startLockWatchdog() {
        // Hindari dobel loop kalau onCreate ke-trigger lagi tanpa onDestroy dulu
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
                handler.postDelayed(this, 60_000) // Update setiap 1 menit
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