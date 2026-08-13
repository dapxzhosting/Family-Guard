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
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )

        binding = ActivityLockScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
            } else {
                Toast.makeText(this, "✓ Perangkat dibuka", Toast.LENGTH_SHORT).show()
            }
            finish()
        } else {
            binding.etPin.text?.clear()
            binding.etPin.error = "PIN salah!"
            Toast.makeText(this, "PIN salah, coba lagi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun goHome() {
        val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_HOME)
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
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
