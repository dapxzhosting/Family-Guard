package com.familyguard.utils

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object UsageTracker {

    private const val PREFS_NAME = "usage_tracker_prefs"

    private const val MAX_SESSION_MS = 2 * 60 * 60 * 1000L

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private fun todayKey(): String = dateFormat.format(Date())

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun recordSession(context: Context, packageName: String, sessionMs: Long) {
        if (sessionMs <= 0 || packageName.isBlank() || packageName == context.packageName) return
        if (!AppFilter.isTrackableApp(context, packageName)) return
        val cappedMs = sessionMs.coerceAtMost(MAX_SESSION_MS)

        val date = todayKey()
        val key = "$date:$packageName"
        val p = prefs(context)
        val existingMs = p.getLong(key, 0L)
        val newMs = existingMs + cappedMs
        p.edit().putLong(key, newMs).apply()

        val existingMinutes = (existingMs / 60000L).toInt()
        val newMinutes = (newMs / 60000L).toInt()

        if (newMinutes > existingMinutes) {
            com.familyguard.sync.FamilyLink.syncUsageMinutes(context, date, packageName, newMinutes)
        }
    }
}

