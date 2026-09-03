package com.familyguard.utils

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hitung durasi pemakaian per aplikasi per hari dengan numpang di polling
 * foreground-app yang SUDAH ADA di AppLockAccessibilityService (dipakai buat
 * fitur kunci aplikasi) -- jadi TIDAK perlu izin tambahan seperti
 * UsageStatsManager/PACKAGE_USAGE_STATS yang butuh flow "buka Setelan"
 * terpisah dan sering bikin user bingung.
 *
 * Alurnya: AppLockAccessibilityService.evaluatePackage() sudah tahu persis
 * kapan foreground app berganti (dan sudah dipanggil tiap ada event window
 * berubah + fallback polling tiap 600ms) -- tinggal dikasih tahu berapa lama
 * package SEBELUMNYA ada di foreground sebelum ganti, lewat recordSession().
 *
 * Data disimpan lokal dulu (SharedPreferences, per HARI+PACKAGE) supaya tahan
 * kalau app di-kill sebentar, lalu disinkron ke Firebase HANYA kalau angka
 * menitnya beneran naik (bukan tiap event kecil) supaya hemat write.
 * Dipakai oleh ScreenTimeReportGenerator + ScreenTimeReportActivity di sisi
 * Orang Tua untuk bikin laporan mingguan.
 */
object UsageTracker {

    private const val PREFS_NAME = "usage_tracker_prefs"

    // Cap durasi 1 sesi supaya 1 kejadian aneh (mis. watchdog sempat mati lama,
    // HP di-charge semalaman dengan 1 app nyangkut di foreground) tidak
    // mencemari laporan dengan angka yang tidak masuk akal.
    private const val MAX_SESSION_MS = 2 * 60 * 60 * 1000L // 2 jam

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private fun todayKey(): String = dateFormat.format(Date())

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Tambahkan [sessionMs] (durasi package ini ada di foreground sejak
     * terakhir kali berganti) ke akumulasi hari ini untuk [packageName].
     * Dipanggil dari AppLockAccessibilityService setiap foreground app
     * BERGANTI (bukan tiap poll), dengan [packageName] = package yang BARU
     * SAJA ditinggalkan (bukan yang baru dibuka).
     */
    fun recordSession(context: Context, packageName: String, sessionMs: Long) {
        if (sessionMs <= 0 || packageName.isBlank() || packageName == context.packageName) return
        val cappedMs = sessionMs.coerceAtMost(MAX_SESSION_MS)

        val date = todayKey()
        val key = "$date:$packageName"
        val p = prefs(context)
        val existingMs = p.getLong(key, 0L)
        val newMs = existingMs + cappedMs
        p.edit().putLong(key, newMs).apply()

        val existingMinutes = (existingMs / 60000L).toInt()
        val newMinutes = (newMs / 60000L).toInt()
        // Throttle: cuma tulis ke Firebase kalau angka MENIT genapnya naik,
        // bukan tiap detik -- hemat write RTDB secara signifikan.
        if (newMinutes > existingMinutes) {
            com.familyguard.sync.FamilyLink.syncUsageMinutes(context, date, packageName, newMinutes)
        }
    }
}
