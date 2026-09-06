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

            if (AppLockPrefs.isDeviceLocked(context)) {

                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                handler.postDelayed({
                    GuardService.start(context)
                }, 5000)
            } else if (AppLockPrefs.isGuardEnabled(context)) {

                GuardService.start(context)
            }

            if (AppLockPrefs.isGuardEnabled(context)) {
                com.familyguard.receiver.GuardWatchdogReceiver.schedule(context)
            }
        }
    }
}

