package com.familyguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.familyguard.service.GuardService
import com.familyguard.utils.AppLockPrefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            // Cek apakah mode lock sedang aktif
            if (AppLockPrefs.isDeviceLocked(context)) {
                // Beri jeda sedikit agar sistem stabil lalu kunci lagi
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                handler.postDelayed({
                    GuardService.start(context)
                }, 5000)
            } else if (AppLockPrefs.isGuardEnabled(context)) {
                // Restart guard service setelah reboot
                GuardService.start(context)
            }
        }
    }
}
