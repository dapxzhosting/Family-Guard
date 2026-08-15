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

/**
 * AccessibilityService untuk mendeteksi pergantian aplikasi
 * dan menampilkan layar kunci jika app tersebut dikunci.
 *
 * Menggunakan DUA mekanisme deteksi:
 * 1. onAccessibilityEvent() - reaktif, cepat, dipicu tiap window berganti.
 * 2. Watchdog polling (checkForegroundApp) - jaring pengaman untuk celah
 *    "swipe kartu lock screen di Recents lalu balik ke app terkunci" yang
 *    kadang tidak memicu event TYPE_WINDOW_STATE_CHANGED sama sekali.
 *
 * PENTING (fix lag mengetik PIN + PIN benar dianggap salah):
 * Watchdog SEBELUMNYA jalan di main thread tiap 600ms dan memanggil
 * `windows` (binder call ke sistem, cukup berat). Karena
 * AccessibilityService ini satu proses dengan Activity (termasuk
 * LockScreenActivity), polling itu ikut numpuk di main thread yang sama
 * dipakai buat proses keystroke EditText -> ngetik jadi lag, dan sesekali
 * ada karakter yang delay/kelewat sampai PIN yang benar-benar tersimpan di
 * EditText beda dengan yang diketik (terlihat benar di layar padahal
 * sebenarnya salah).
 *
 * Fix: (a) watchdog dipindah ke background thread sendiri (HandlerThread),
 * bukan main thread lagi, dan (b) watchdog di-skip total selama
 * LockScreenActivity sedang tampil di depan -- karena pada saat itu kita
 * memang sudah dalam status "terkunci", tidak perlu polling apa pun, jadi
 * nol overhead pas anak lagi mengetik PIN.
 */
class AppLockAccessibilityService : AccessibilityService() {

    private var lastPackage: String = ""
    // Handler untuk hal yang WAJIB di main thread (startActivity dari intent baru,
    // aman dari thread manapun sebenarnya, tapi kita jaga konsisten di main thread).
    private val mainHandler = Handler(Looper.getMainLooper())

    // Thread terpisah khusus watchdog, supaya tidak mengganggu main thread
    // (yang dipakai UI, termasuk mengetik PIN di LockScreenActivity).
    private var watchdogThread: HandlerThread? = null
    private var watchdogHandler: Handler? = null
    private var watchdogRunning = false

    private val watchdog = object : Runnable {
        override fun run() {
            // Skip total kalau lock screen kita sendiri sedang tampil di depan --
            // tidak perlu polling window sama sekali, dan ini yang menghindari
            // beban ke main thread pas anak lagi mengetik PIN.
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

    /**
     * Watchdog: ambil window aktif (yang sedang fokus) langsung dari sistem,
     * bukan menunggu event. Ini yang menutup celah "swipe recents lalu balik
     * ke app terkunci tanpa PIN". Dipanggil dari background thread.
     */
    private fun checkForegroundApp() {
        try {
            val activeWindow = windows?.firstOrNull { it.isFocused }
                ?: windows?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            val packageName = activeWindow?.root?.packageName?.toString() ?: return
            evaluatePackage(packageName)
        } catch (e: Exception) {
            // windows/root bisa throw kalau service sedang dalam transisi state
            Log.w(TAG, "checkForegroundApp gagal: ${e.message}")
        }
    }

    private fun evaluatePackage(packageName: String) {
        // Periksa apakah perangkat sedang dikunci total (Device Lock)
        if (AppLockPrefs.isDeviceLocked(this)) {
            if (packageName != "com.familyguard") {
                showLockScreen("", true)
                return
            }
        }

        // PENTING: jangan return sebelum lastPackage diupdate!
        // Kalau packageName == "com.familyguard" langsung di-skip SEBELUM
        // lastPackage diupdate, lastPackage bisa nyangkut ke app terkunci
        // yang terakhir dibuka -> pergantian berikutnya ke app yang sama
        // dianggap "tidak berubah" -> cek lock ke-skip -> lolos tanpa PIN.
        if (packageName == lastPackage) return
        lastPackage = packageName

        if (packageName == "com.familyguard") return

        val lockedApps = AppLockPrefs.getLockedApps(this)
        if (lockedApps.contains(packageName)) {
            // Cek apakah baru saja dibuka dengan PIN (grace period 30 detik)
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

    /**
     * Eksekusi input jarak jauh dari HP orang tua (Mode Kontrol di ChildScreenViewActivity).
     * Koordinat yang diterima dari Firebase dinormalisasi (0.0-1.0), dikonversi kembali
     * ke koordinat piksel ASLI layar HP anak (RemoteControlState) sebelum dipakai
     * dispatchGesture() -- API resmi Android untuk mensimulasikan sentuhan lewat
     * Accessibility Service, tersedia sejak API 24.
     */
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

        // Referensi instance service yang sedang aktif, dibaca oleh FamilyLink saat
        // menerima command remote_tap/remote_swipe/remote_back dari HP orang tua.
        @Volatile
        var instance: AppLockAccessibilityService? = null
    }
}