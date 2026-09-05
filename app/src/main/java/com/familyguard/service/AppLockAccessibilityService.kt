package com.familyguard.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.familyguard.ui.LockScreenActivity
import com.familyguard.utils.AppLockPrefs

class AppLockAccessibilityService : AccessibilityService() {

    private var lastPackage: String = ""
    private var packageStartTime: Long = System.currentTimeMillis()

    private val mainHandler = Handler(Looper.getMainLooper())

    private var watchdogThread: HandlerThread? = null
    private var watchdogHandler: Handler? = null
    private var watchdogRunning = false

    private val watchdog = object : Runnable {
        override fun run() {

            if (!LockScreenActivity.isForeground) {
                checkForegroundApp()
            }
            watchdogHandler?.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            evaluatePackage(packageName)
        }
    }

    private fun checkForegroundApp() {
        try {
            val activeWindow = windows?.firstOrNull { it.isFocused }
                ?: windows?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            val packageName = activeWindow?.root?.packageName?.toString() ?: return
            evaluatePackage(packageName)
        } catch (e: Exception) {

            Log.w(TAG, "checkForegroundApp gagal: ${e.message}")
        }
    }

    private fun evaluatePackage(packageName: String) {

        if (AppLockPrefs.isDeviceLocked(this)) {
            if (packageName != "com.familyguard") {

                mainHandler.post { LockScreenActivity.requestDeviceLock(this) }
                return
            }
        }

        if (packageName == lastPackage) return

        // Catat durasi package SEBELUMNYA (yang baru saja ditinggalkan) untuk
        // laporan screen time -- lihat UsageTracker untuk kenapa ini numpang
        // di sini (tidak butuh izin UsageStatsManager terpisah).
        val previousPackage = lastPackage
        val now = System.currentTimeMillis()
        if (previousPackage.isNotEmpty()) {
            com.familyguard.utils.UsageTracker.recordSession(this, previousPackage, now - packageStartTime)
        }
        packageStartTime = now
        lastPackage = packageName

        if (packageName == "com.familyguard") return

        // Update "sedang dipakai sekarang" secara realtime ke dashboard --
        // terpisah dari UsageTracker (yang cuma nyatat total menit per
        // hari) supaya orang tua bisa lihat app apa yang aktif SEKARANG
        // tanpa nunggu package-nya ditinggalkan dulu. Skip komponen sistem
        // (systemui) & home launcher supaya dashboard gak nampilin "sedang
        // pakai com.android.systemui" pas anak cuma di lock screen/home.
        if (com.familyguard.utils.AppFilter.isTrackableApp(this, packageName)) {
            com.familyguard.sync.FamilyLink.updateCurrentApp(this, packageName)
        }

        val lockedApps = AppLockPrefs.getLockedApps(this)
        if (lockedApps.contains(packageName)) {

            if (AppLockPrefs.isPackageTemporarilyUnlocked(this, packageName)) {
                Log.d(TAG, "App $packageName is temporarily unlocked, skipping lock")
                return
            }

            Log.d(TAG, "Blocked app detected: $packageName → showing lock screen")
            showLockScreen(packageName, false)
        }
    }

    private fun showLockScreen(packageName: String, isDeviceLock: Boolean) {
        mainHandler.post {
            val intent = Intent(this, LockScreenActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(LockScreenActivity.EXTRA_LOCKED_PACKAGE, packageName)
                putExtra(LockScreenActivity.EXTRA_MODE, if (isDeviceLock) LockScreenActivity.MODE_DEVICE_LOCK else LockScreenActivity.MODE_APP_LOCK)
            }
            startActivity(intent)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AppLockAccessibilityService interrupted")
    }

    fun executeRemoteInput(type: String, payload: com.google.firebase.database.DataSnapshot) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return

        val w = com.familyguard.service.RemoteControlState.realScreenWidth.toFloat()
        val h = com.familyguard.service.RemoteControlState.realScreenHeight.toFloat()

        when (type) {
            "remote_tap" -> {
                val xNorm = payload.child("x").getValue(Double::class.java)?.toFloat() ?: return
                val yNorm = payload.child("y").getValue(Double::class.java)?.toFloat() ?: return
                dispatchTap(xNorm * w, yNorm * h)
            }
            "remote_swipe" -> {
                val x1 = payload.child("x1").getValue(Double::class.java)?.toFloat() ?: return
                val y1 = payload.child("y1").getValue(Double::class.java)?.toFloat() ?: return
                val x2 = payload.child("x2").getValue(Double::class.java)?.toFloat() ?: return
                val y2 = payload.child("y2").getValue(Double::class.java)?.toFloat() ?: return
                val duration = payload.child("duration").getValue(Long::class.java) ?: 150L
                dispatchSwipe(x1 * w, y1 * h, x2 * w, y2 * h, duration.coerceIn(50L, 2000L))
            }
            "remote_back" -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
    }

    fun executeRemoteInputJson(json: org.json.JSONObject) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return

        val w = com.familyguard.service.RemoteControlState.realScreenWidth.toFloat()
        val h = com.familyguard.service.RemoteControlState.realScreenHeight.toFloat()

        when (json.optString("type")) {
            "remote_tap" -> {
                val xNorm = json.optDouble("x", -1.0).toFloat()
                val yNorm = json.optDouble("y", -1.0).toFloat()
                if (xNorm < 0 || yNorm < 0) return
                dispatchTap(xNorm * w, yNorm * h)
            }
            "remote_swipe" -> {
                val x1 = json.optDouble("x1", -1.0).toFloat()
                val y1 = json.optDouble("y1", -1.0).toFloat()
                val x2 = json.optDouble("x2", -1.0).toFloat()
                val y2 = json.optDouble("y2", -1.0).toFloat()
                if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return
                val duration = json.optLong("duration", 150L)
                dispatchSwipe(x1 * w, y1 * h, x2 * w, y2 * h, duration.coerceIn(50L, 2000L))
            }
            "remote_back" -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
    }

    private fun dispatchTap(x: Float, y: Float) {
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun dispatchSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val path = android.graphics.Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "AppLock Accessibility Service connected")
        instance = this
        if (!watchdogRunning) {
            watchdogRunning = true
            val thread = HandlerThread("AppLockWatchdog").apply { start() }
            watchdogThread = thread
            val bgHandler = Handler(thread.looper)
            watchdogHandler = bgHandler
            bgHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
        }

        // Accessibility Service ini jauh lebih jarang dibunuh & lebih cepat
        // di-restart otomatis oleh sistem Android dibanding foreground
        // service biasa (OS emang didesain buat selalu jaga koneksi ke
        // service accessibility yang aktif) -- jadi setiap kali service ini
        // (re)connect, pastikan juga GuardService (pemegang listener command
        // Firebase, termasuk "set_pin") hidup. GuardService.start() aman
        // dipanggil berkali-kali (idempotent lewat startForegroundService).
        if (com.familyguard.utils.AppLockPrefs.isGuardEnabled(this)) {
            com.familyguard.service.GuardService.start(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        watchdogRunning = false
        watchdogHandler?.removeCallbacks(watchdog)
        watchdogThread?.quitSafely()
        watchdogThread = null
        watchdogHandler = null
    }

    companion object {
        private const val TAG = "AppLockService"
        private const val WATCHDOG_INTERVAL_MS = 600L

        @Volatile
        var instance: AppLockAccessibilityService? = null
    }
}