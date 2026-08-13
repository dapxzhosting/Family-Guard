package com.familyguard.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.familyguard.model.AppInfo

object InstalledAppsHelper {

    /**
     * Ambil semua aplikasi yang terinstall (bukan system app utama).
     * Filter keluar: launcher, system core, app kita sendiri.
     */
    fun getInstalledApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val skipPackages = setOf(
            "com.familyguard",
            "android",
            "com.android.systemui",
            "com.android.settings"
        )

        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { app ->
                // Hanya user-installed apps atau yang punya launcher icon
                val isUserApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                val hasLaunchIntent = pm.getLaunchIntentForPackage(app.packageName) != null
                (isUserApp || hasLaunchIntent) && !skipPackages.contains(app.packageName)
            }
            .mapNotNull { app ->
                try {
                    AppInfo(
                        packageName = app.packageName,
                        appName = pm.getApplicationLabel(app).toString(),
                        icon = pm.getApplicationIcon(app.packageName)
                    )
                } catch (e: Exception) { null }
            }
            .sortedBy { it.appName.lowercase() }
    }
}
