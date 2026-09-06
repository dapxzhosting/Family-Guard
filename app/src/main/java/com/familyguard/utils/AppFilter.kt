package com.familyguard.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object AppFilter {

    const val STATUS_HOME_SCREEN = "familyguard.status.home_screen"
    const val STATUS_LOCK_SCREEN = "familyguard.status.lock_screen"

    fun classifyForCurrentApp(context: Context, packageName: String): String {
        if (isTrackableApp(context, packageName)) return packageName

        val pm = context.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homePackage = pm.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
        if (homePackage != null && homePackage == packageName) return STATUS_HOME_SCREEN

        return STATUS_LOCK_SCREEN
    }

    private val alwaysExclude = setOf(
        "com.familyguard",
        "android",
        "com.android.systemui",
        "com.android.settings"
    )

    fun isTrackableApp(context: Context, packageName: String): Boolean {
        if (packageName.isBlank() || alwaysExclude.contains(packageName)) return false

        val pm = context.packageManager

        if (pm.getLaunchIntentForPackage(packageName) == null) return false

        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homePackage = pm.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
        if (homePackage != null && homePackage == packageName) return false

        return true
    }

    fun isTrackableApp(pm: PackageManager, packageName: String): Boolean {
        if (packageName.isBlank() || alwaysExclude.contains(packageName)) return false
        if (pm.getLaunchIntentForPackage(packageName) == null) return false
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homePackage = pm.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
        return !(homePackage != null && homePackage == packageName)
    }
}

