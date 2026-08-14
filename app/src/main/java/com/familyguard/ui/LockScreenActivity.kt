package com.familyguard.ui

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import com.familyguard.databinding.ActivityLockScreenBinding
import com.familyguard.utils.AppLockPrefs

/**
 * Layar kunci yang tampil ketika pengguna mencoba membuka app yang dikunci.
 * Meminta PIN untuk membuka.
 */
class LockScreenActivity : Activity() {

    private lateinit var binding: ActivityLockScreenBinding
    private var lockedPackage: String? = null
    private var mode: String = MODE_APP_LOCK

    private val unlockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == "com.familyguard.ACTION_UNLOCK") {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Buat activity muncul di atas segala hal
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_SECURE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        // Sembunyikan navigasi dan status bar (Immersive Mode)
        // PENTING: harus dipanggil SETELAH setContentView(), karena window.insetsController
        // butuh DecorView yang baru dibuat saat setContentView() dijalankan. Kalau dipanggil
        // sebelumnya, DecorView masih null -> NullPointerException.
        binding = ActivityLockScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemUI()

        setFinishOnTouchOutside(false)

        val filter = android.content.IntentFilter("com.familyguard.ACTION_UNLOCK")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(unlockReceiver, filter, android.content.Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(unlockReceiver, filter)
        }

        lockedPackage = intent.getStringExtra(EXTRA_LOCKED_PACKAGE)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_APP_LOCK

        val pin = AppLockPrefs.getPin(this)

        when (mode) {
            MODE_DEVICE_LOCK -> {
                binding.tvTitle.text = "Perangkat Terkunci 🛑"
                binding.tvSubtitle.text = "Gunakan PIN Orang Tua untuk Membuka Sesi"
                binding.btnGoHome.visibility = android.view.View.GONE
                binding.btnUnlock.text = "Buka Perangkat"
            }
            MODE_MESSAGE -> {
                val title = intent.getStringExtra(EXTRA_MESSAGE_TITLE) ?: "Pesan"
                val body = intent.getStringExtra(EXTRA_MESSAGE_BODY) ?: ""
                binding.tvTitle.text = "📩 $title"
                binding.tvSubtitle.text = body
                binding.btnUnlock.text = "Tutup Pesan"
                binding.etPin.visibility = android.view.View.GONE
                binding.btnUnlock.setOnClickListener { finish() }
            }
            else -> { // APP_LOCK
                if (pin == null) {
                    binding.tvTitle.text = "Buat PIN Pertama Kali"
                    binding.tvSubtitle.text = "Masukkan PIN 4 digit untuk melindungi aplikasi ini"
                    binding.btnUnlock.text = "Simpan PIN"
                    binding.btnUnlock.setOnClickListener { setFirstPin() }
                } else {
                    binding.tvTitle.text = "Aplikasi Dikunci 🔒"
                    binding.tvSubtitle.text = "Masukkan PIN orang tua untuk membuka"
                }
            }
        }

        if (mode != MODE_MESSAGE && pin != null) {
            binding.btnUnlock.setOnClickListener { verifyPin(pin) }
        }

        binding.btnGoHome.setOnClickListener { goHome() }

        if (mode == MODE_DEVICE_LOCK) {
            startPersistenceTimer()
        }
    }

    private fun goHome() {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
        intent.addCategory(android.content.Intent.CATEGORY_HOME)
        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    private fun setFirstPin() {
        val input = binding.etPin.text.toString()
        if (input.length < 4) {
            Toast.makeText(this, "PIN minimal 4 digit", Toast.LENGTH_SHORT).show()
            return
        }
        AppLockPrefs.savePin(this, input)
        Toast.makeText(this, "PIN berhasil disimpan", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun verifyPin(savedPin: String) {
        val input = binding.etPin.text?.toString() ?: ""
        if (input == savedPin) {
            if (mode == MODE_APP_LOCK && lockedPackage != null) {
                // Beri waktu 30 detik akses
                AppLockPrefs.setPackageUnlocked(this, lockedPackage!!)
                Toast.makeText(this, "✓ Berhasil dibuka", Toast.LENGTH_SHORT).show()
                finish() // Kembali ke aplikasi yang sedang dibuka
            } else {
                if (mode == MODE_DEVICE_LOCK) {
                    AppLockPrefs.setDeviceLocked(this, false)
                }
                Toast.makeText(this, "✓ Perangkat dibuka", Toast.LENGTH_SHORT).show()
                finish() // Jika device lock, kembali ke apa yang ada di belakangnya
            }
        } else {
            binding.etPin.text?.clear()
            binding.etPin.error = "PIN salah!"
            Toast.makeText(this, "PIN salah, coba lagi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startPersistenceTimer() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (!isFinishing && mode == MODE_DEVICE_LOCK) {
                    // Pastikan kita tetap di depan
                    val intent = android.content.Intent(this@LockScreenActivity, LockScreenActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        putExtra(EXTRA_MODE, mode)
                    }
                    startActivity(intent)
                    handler.postDelayed(this, 2000) // Cek setiap 2 detik
                }
            }
        }
        handler.postDelayed(runnable, 2000)
    }

    private fun hideSystemUI() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // window.decorView dipanggil dulu untuk memastikan DecorView sudah ada
            // sebelum window.insetsController diakses (mencegah NPE).
            window.decorView
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    )
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
    }

    override fun onPause() {
        super.onPause()
        // Jika dipaksa keluar (misal lewat Assistant atau cara cerdik lainnya)
        if (!isFinishing && (mode == MODE_DEVICE_LOCK || mode == MODE_APP_LOCK)) {
            val intent = android.content.Intent(this, LockScreenActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra(EXTRA_MODE, mode)
                putExtra(EXTRA_LOCKED_PACKAGE, lockedPackage)
            }
            startActivity(intent)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (mode == MODE_MESSAGE) return super.dispatchKeyEvent(event)

        // Blokir hampir semua tombol fisik
        val blockedKeys = listOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH, // Recent apps
            KeyEvent.KEYCODE_BACK
        )
        if (blockedKeys.contains(event.keyCode)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Cegah interaksi dengan panel sistem di level window
        @Suppress("DEPRECATION")
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
    }

    // Blokir tombol back agar tidak bisa keluar tanpa PIN
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (mode == MODE_MESSAGE) finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Jika user mencoba memencet HOME (terutama di Android versi lama)
        if (mode == MODE_DEVICE_LOCK || mode == MODE_APP_LOCK) {
            val intent = android.content.Intent(this, LockScreenActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_MODE, mode)
                putExtra(EXTRA_LOCKED_PACKAGE, lockedPackage)
            }
            startActivity(intent)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && (mode == MODE_DEVICE_LOCK || mode == MODE_APP_LOCK)) {
            // CATATAN: ACTION_CLOSE_SYSTEM_DIALOGS TIDAK BOLEH dikirim oleh aplikasi biasa
            // mulai Android 12 (API 31) -- broadcast ini memerlukan permission system-only
            // (android.permission.BROADCAST_CLOSE_SYSTEM_DIALOGS) dan akan membuat app
            // CRASH dengan SecurityException kalau tetap dipaksa kirim. Baris ini dihapus.
            // Immersive mode + FLAG_SECURE + flag lain di onCreate/hideSystemUI() sudah cukup
            // untuk mencegah user membuka status bar/quick settings di lock screen ini.
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(unlockReceiver)
        } catch (e: Exception) {
            // ignore
        }
    }

    companion object {
        const val EXTRA_LOCKED_PACKAGE = "locked_package"
        const val EXTRA_MODE = "mode"
        const val EXTRA_MESSAGE_TITLE = "msg_title"
        const val EXTRA_MESSAGE_BODY = "msg_body"

        const val MODE_APP_LOCK = "app_lock"
        const val MODE_DEVICE_LOCK = "device_lock"
        const val MODE_MESSAGE = "message"
    }
}