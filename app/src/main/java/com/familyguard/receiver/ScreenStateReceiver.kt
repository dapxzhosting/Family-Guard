package com.familyguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.familyguard.ui.LockScreenActivity
import com.familyguard.utils.AppLockPrefs

/**
 * FIX BUG: "HP masih bisa dimatiin di lockscreen & pas dibuka lagi jadi
 * ga kekunci".
 *
 * Sebelumnya, LockScreenActivity (mode DEVICE_LOCK) cuma dimunculkan SEKALI
 * waktu GuardService.onCreate() dipanggil. Kalau layar dimatikan lalu
 * dinyalakan lagi TANPA GuardService ikut mati (kasus paling umum: anak
 * tekan tombol power sebentar buat matiin layar, lalu nyalain lagi) --
 * tidak ada satupun listener yang munculin lagi LockScreen-nya, jadi anak
 * langsung nyampe di homescreen tanpa PIN sama sekali.
 *
 * ACTION_SCREEN_ON dan ACTION_USER_PRESENT TIDAK BISA didaftarkan lewat
 * <receiver> di AndroidManifest (dibatasi sejak Android 8/API 26) --
 * makanya receiver ini didaftarkan secara dinamis (registerReceiver) dari
 * GuardService selama service itu hidup, bukan lewat manifest.
 *
 * ACTION_SCREEN_ON: dikirim SEBELUM keyguard sistem hilang -- kita pasang
 * LockScreenActivity di sini duluan (activity ini sudah showWhenLocked +
 * turnScreenOn) supaya begitu keyguard sistem terbuka/swipe, yang kelihatan
 * langsung layar kunci FamilyGuard, bukan homescreen.
 * ACTION_USER_PRESENT: dikirim SETELAH keyguard sistem berhasil dilewati --
 * dipasang juga sebagai jaring pengaman kedua kalau ACTION_SCREEN_ON telat
 * atau race dengan sistem di beberapa custom ROM.
 */
class ScreenStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                if (AppLockPrefs.isDeviceLocked(context)) {
                    showDeviceLock(context)
                }
            }
        }
    }

    private fun showDeviceLock(context: Context) {
        LockScreenActivity.requestDeviceLock(context)
    }

    companion object {
        fun register(context: Context): ScreenStateReceiver {
            val receiver = ScreenStateReceiver()
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            // Broadcast sistem seperti ini tidak butuh RECEIVER_EXPORTED/NOT_EXPORTED
            // secara eksplisit di semua versi, tapi kita set NOT_EXPORTED di API 33+
            // supaya lolos pemeriksaan lint & tidak bisa dipicu app lain dari luar.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            return receiver
        }

        fun unregister(context: Context, receiver: ScreenStateReceiver?) {
            if (receiver == null) return
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                // sudah unregistered / tidak pernah registered, aman diabaikan
            }
        }
    }
}
