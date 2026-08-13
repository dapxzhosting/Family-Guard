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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lockedPackage = intent.getStringExtra(EXTRA_LOCKED_PACKAGE)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_APP_LOCK

        val pin = AppLockPrefs.getPin(this)
        if (pin == null) {
            // Belum ada PIN → minta set PIN
            binding.tvTitle.text = "Buat PIN Pertama Kali"
            binding.tvSubtitle.text = "Masukkan PIN 4 digit untuk melindungi aplikasi ini"
            binding.btnUnlock.text = "Simpan PIN"
            binding.btnUnlock.setOnClickListener { setFirstPin() }
        } else {
            binding.tvTitle.text = "Aplikasi Dikunci 🔒"
            binding.tvSubtitle.text = "Masukkan PIN orang tua untuk membuka"
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
        val input = binding.etPin.text.toString()
        if (input == savedPin) {
            // Hapus sementara dari daftar terkunci selama 30 detik (opsional)
            Toast.makeText(this, "✓ Dibuka sementara", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            binding.etPin.text.clear()
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
        return if (keyCode == KeyEvent.KEYCODE_BACK) true else super.onKeyDown(keyCode, event)
    }

    companion object {
        const val EXTRA_LOCKED_PACKAGE = "locked_package"
        const val EXTRA_MODE = "mode"
        const val MODE_APP_LOCK = "app_lock"
        const val MODE_DEVICE_LOCK = "device_lock"
    }
}
