package com.familyguard.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.familyguard.service.GuardService
import com.familyguard.utils.AppLockPrefs

/**
 * Watchdog untuk GuardService.
 *
 * MASALAH: banyak HP (terutama merk dengan custom OS agresif seperti
 * Transsion/HiOS -- itel, Tecno, Infinix) tetap bisa membunuh foreground
 * service walau sudah START_STICKY, kalau app tidak di-whitelist battery
 * optimization secara manual oleh user. Begitu GuardService mati, listener
 * perintah Firebase (FamilyLink.startListening) & heartbeat ikut mati,
 * jadi semua fitur dashboard orang tua berhenti sampai HP anak buka app
 * lagi secara manual.
 *
 * SOLUSI: alarm berkala (AlarmManager, exact & allow-while-idle supaya
 * tetap jalan walau device masuk Doze) yang cek & start ulang GuardService
 * kalau ternyata sudah mati -- tanpa perlu user buka app secara manual.
 * Alarm ini menjadwalkan dirinya sendiri lagi tiap kali dipanggil (chain),
 * dan juga dijadwalkan ulang dari BootReceiver & ChildHomeActivity supaya
 * selalu ter-arm.
 */
class GuardWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Cuma jalan kalau memang role-nya CHILD dan guard sedang diaktifkan
        if (AppLockPrefs.isGuardEnabled(context)) {
            GuardService.start(context)
        }
        // Jadwalkan alarm berikutnya (chain) supaya watchdog terus berjalan
        schedule(context)
    }

    companion object {
        private const val REQUEST_CODE = 4471
        private const val INTERVAL_MS = 15 * 60 * 1000L // cek tiap 15 menit

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, GuardWatchdogReceiver::class.java)
            return PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /** Panggil ini dari BootReceiver & ChildHomeActivity.onCreate() supaya watchdog selalu ter-arm. */
        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent(context)
                    )
                } else {
                    am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent(context))
                }
            } catch (e: SecurityException) {
                // Beberapa OEM membatasi exact alarm tanpa izin khusus -- fallback ke inexact
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent(context))
            }
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pendingIntent(context))
        }
    }
}
