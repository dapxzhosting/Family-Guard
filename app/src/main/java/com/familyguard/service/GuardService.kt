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
import com.familyguard.ui.RoleSelectionActivity

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

        rebindToCurrentFamily()

        if (AppLockPrefs.isDeviceLocked(this)) {
            showDeviceLockScreen()
        }

        startLockWatchdog()
        startPeriodicLocationUpdates()

        com.familyguard.receiver.GuardWatchdogReceiver.schedule(this)
    }

    /**
     * Daftar ulang device + sambungkan ulang listener command ke kode
     * keluarga yang SEDANG AKTIF sekarang (dibaca ulang dari AppLockPrefs).
     *
     * BUG YANG DIPERBAIKI: FamilyLink.startListening() sebelumnya cuma
     * dipanggil sekali di onCreate() dan meng-capture kode keluarga saat itu
     * ke closure listener-nya. Kalau keluarga dihapus lalu dibuat ulang
     * SELAGI GuardService masih hidup (service foreground jarang mati
     * sendiri), listener itu tetap nempel ke kode keluarga LAMA selamanya --
     * onStartCommand() tidak pernah memanggil ulang onCreate(), jadi
     * memanggil start() lagi (dari onServiceConnected/onTaskRemoved/dll)
     * tidak pernah benar-benar rebind. Semua command baru (termasuk
     * set_pin) numpuk gak pernah keproses karena yang dengerin salah
     * alamat. Rebind ini dipanggil ulang setiap kali service di-start,
     * bukan cuma sekali di onCreate, supaya selalu ikut kode keluarga
     * TERBARU.
     */
    private fun rebindToCurrentFamily() {
        com.familyguard.sync.FamilyLink.verifyFamilyStillExists(
            this,
            onExists = {
                // Listener persisten yang jaga cache "keluarga masih valid"
                // (FamilyLink.isFamilyKnownValid) tetap ke-update REALTIME
                // selama service ini hidup -- ini yang dicek oleh SEMUA
                // fungsi tulis rutin (updateCurrentApp, sendHeartbeat,
                // updateLocation, syncUsageMinutes, syncPermissionStatus)
                // sebelum nulis apapun. Beda dari verifyFamilyStillExists
                // (one-shot, cuma dicek pas rebind/tiap 60 detik) --
                // updateCurrentApp bisa dipanggil tiap beberapa detik
                // (setiap app anak ganti foreground), jadi butuh cache yang
                // selalu fresh, bukan cek satu-satu tiap kali nulis.
                com.familyguard.sync.FamilyLink.startFamilyExistenceGuard(this) {
                    com.familyguard.sync.FamilyLink.clearLocalFamilyState(this)
                    stopSelf()
                }
                FamilyLink.registerDevice(this)
                FamilyLink.stopListening(this, force = true)
                FamilyLink.startListening(this, isGlobal = true) { title, message, fromDeviceId ->
                    showGlobalMessage(title, message, fromDeviceId)
                }
            },
            onGone = {
                // Keluarga sudah dihapus orang tua (atau device sudah
                // dikeluarkan) selagi GuardService ini hidup di background
                // tanpa ada Activity yang membuka listenFamilyDeletion --
                // bersihin state lokal & matikan diri sendiri, JANGAN
                // registerDevice/startListening (itu bakal nulis ulang dan
                // "menghidupkan lagi" node yang sudah dihapus).
                com.familyguard.sync.FamilyLink.clearLocalFamilyState(this)
                stopSelf()
            }
        )
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
                // Cukup cek cache in-memory (di-update realtime oleh
                // startFamilyExistenceGuard di rebindToCurrentFamily) --
                // gak perlu Firebase round-trip di sini lagi.
                if (com.familyguard.sync.FamilyLink.isFamilyKnownValid()) {
                    LocationHelper.updateCurrentLocation(this@GuardService)
                    FamilyLink.sendHeartbeat(this@GuardService)
                }
                handler.postDelayed(this, 60_000)
            }
        }
        handler.post(runnable)
    }

    private fun showGlobalMessage(title: String, message: String, fromDeviceId: String) {
        val intent = Intent(this, LockScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(LockScreenActivity.EXTRA_MODE, LockScreenActivity.MODE_MESSAGE)
            putExtra(LockScreenActivity.EXTRA_MESSAGE_TITLE, title)
            putExtra(LockScreenActivity.EXTRA_MESSAGE_BODY, message)
            putExtra(LockScreenActivity.EXTRA_MESSAGE_FROM, fromDeviceId)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        FamilyLink.stopListening(this, force = true)
        com.familyguard.sync.FamilyLink.stopFamilyExistenceGuard()
        com.familyguard.receiver.ScreenStateReceiver.unregister(this, screenStateReceiver)
        screenStateReceiver = null
        lockWatchRunnable?.let { lockWatchHandler.removeCallbacks(it) }
        lockWatchRunnable = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Setiap kali service ini di-start ULANG (walau prosesnya masih
        // hidup dari sebelumnya), pastikan listener command ikut kode
        // keluarga TERBARU -- lihat penjelasan di rebindToCurrentFamily().
        rebindToCurrentFamily()
        return START_STICKY
    }

    /**
     * Banyak OEM (termasuk Itel/Infinix/Tecno) menganggap "app di-swipe dari
     * recent apps" sebagai sinyal buat langsung bunuh service-nya, terlepas
     * dari START_STICKY atau status foreground. Restart diri sendiri di sini
     * supaya listener command (FamilyLink.startListening) gak nunggu sampai
     * GuardWatchdogReceiver jalan lagi (uang bisa 15 menit lagi).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (AppLockPrefs.isGuardEnabled(this)) {
            start(this)
        }
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

        val intent = Intent(this, RoleSelectionActivity::class.java)
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