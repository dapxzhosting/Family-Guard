package com.familyguard.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast

class FamilyDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, "FamilyGuard: Device Admin aktif", Toast.LENGTH_SHORT).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Menonaktifkan ini akan mematikan kunci layar & kunci aplikasi FamilyGuard di HP ini. Orang Tua akan mendapat notifikasi kalau ini dinonaktifkan."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, "FamilyGuard: Device Admin dinonaktifkan", Toast.LENGTH_SHORT).show()

        com.familyguard.sync.FamilyLink.markDeviceAdminDisabled(context)
    }

    companion object {
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, FamilyDeviceAdminReceiver::class.java)
        }
    }
}
