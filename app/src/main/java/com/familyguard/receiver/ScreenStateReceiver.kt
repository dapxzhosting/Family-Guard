package com.familyguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.familyguard.ui.LockScreenActivity
import com.familyguard.utils.AppLockPrefs

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

            }
        }
    }
}

