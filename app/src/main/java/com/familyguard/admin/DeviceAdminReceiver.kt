package com.familyguard.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class DeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, "FamilyGuard: Device Admin aktif ✓", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, "FamilyGuard: Device Admin dinonaktifkan", Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun getComponentName(context: Context) =
            android.content.ComponentName(context, DeviceAdminReceiver::class.java)
    }
}
