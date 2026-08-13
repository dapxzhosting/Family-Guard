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
            // Restart guard service setelah reboot
            if (AppLockPrefs.isGuardEnabled(context)) {
                GuardService.start(context)
            }
        }
    }
}
