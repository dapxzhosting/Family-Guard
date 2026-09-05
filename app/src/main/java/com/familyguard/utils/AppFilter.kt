package com.familyguard.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Satu sumber kebenaran untuk nentuin "apakah package ini layak dianggap
 * aplikasi yang benar-benar dipakai user" -- dipakai bareng oleh:
 *  - UsageTracker (screen time)
 *  - AppLockAccessibilityService (currentApp realtime "sedang dipakai sekarang")
 *  - InstalledAppsHelper (list app buat fitur kunci aplikasi)
 *
 * Sebelum ada ini, tiap tempat punya filter sendiri-sendiri yang beda-beda
 * (ada yang lupa exclude launcher, ada yang lupa exclude systemui), jadi
 * komponen sistem ikut nyangkut di UI orang tua.
 */
object AppFilter {

    // Sentinel value (BUKAN nama package asli) yang dikirim ke
    // currentApp.packageName saat anak lagi di home screen / layar kunci --
    // dashboard orang tua nge-render ini jadi label + ikon khusus, bukan
    // nyari nama/ikon aplikasi lewat lookup biasa.
    const val STATUS_HOME_SCREEN = "familyguard.status.home_screen"
    const val STATUS_LOCK_SCREEN = "familyguard.status.lock_screen"

    /**
     * Tentuin apa yang harus dikirim ke currentApp buat package tertentu:
     * nama package asli kalau itu aplikasi beneran, atau salah satu sentinel
     * di atas kalau anak lagi di home screen / layar kunci-sistem.
     */
    fun classifyForCurrentApp(context: Context, packageName: String): String {
        if (isTrackableApp(context, packageName)) return packageName

        val pm = context.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homePackage = pm.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
        if (homePackage != null && homePackage == packageName) return STATUS_HOME_SCREEN

        // com.android.systemui (atau variannya di OEM tertentu) paling sering
        // muncul sebagai foreground package pas layar kunci aktif.
        return STATUS_LOCK_SCREEN
    }

    // Package non-user-facing yang perlu selalu dikecualikan meski karena
    // alasan tertentu lolos dari cek launcher-intent/home-launcher di bawah
    // (mis. varian OEM yang aneh).
    private val alwaysExclude = setOf(
        "com.familyguard",
        "android",
        "com.android.systemui",
        "com.android.settings"
    )

    /**
     * true kalau [packageName] adalah aplikasi yang muncul di app drawer dan
     * BUKAN home screen launcher / komponen sistem.
     */
    fun isTrackableApp(context: Context, packageName: String): Boolean {
        if (packageName.isBlank() || alwaysExclude.contains(packageName)) return false

        val pm = context.packageManager

        // Harus punya launcher intent -> berarti tampil di app drawer
        // sebagai sesuatu yang bisa dibuka user. com.android.systemui,
        // input method service, dsb TIDAK punya ini.
        if (pm.getLaunchIntentForPackage(packageName) == null) return false

        // Bukan default home launcher -- launcher (mis. com.transsion.hilauncher)
        // biasanya TETAP punya launcher intent, jadi harus dicek terpisah.
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homePackage = pm.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
        if (homePackage != null && homePackage == packageName) return false

        return true
    }

    /** Overload buat kode yang cuma punya PackageManager + nama package. */
    fun isTrackableApp(pm: PackageManager, packageName: String): Boolean {
        if (packageName.isBlank() || alwaysExclude.contains(packageName)) return false
        if (pm.getLaunchIntentForPackage(packageName) == null) return false
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homePackage = pm.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
        return !(homePackage != null && homePackage == packageName)
    }
}