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

        // PENTING (fix keyboard tidak muncul): activity yang tampil DI ATAS keyguard
        // (FLAG_SHOW_WHEN_LOCKED) sering tidak auto-munculin keyboard di banyak HP,
        // terutama custom ROM (HiOS/Tecno, dll). Paksa mode soft input di sini.
        @Suppress("DEPRECATION")
        window.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        // Sembunyikan navigasi dan status bar (Immersive Mode)
        // PENTING: harus dipanggil SETELAH setContentView(), karena window.insetsController
        // butuh DecorView yang baru dibuat saat setContentView() dijalankan. Kalau dipanggil
        // sebelumnya, DecorView masih null -> NullPointerException.
        binding = ActivityLockScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemUI()
        setupKeyboardInsetsFix()

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
                binding.btnUnlock.visibility = android.view.View.GONE
            }
            MODE_MESSAGE -> {
                val title = intent.getStringExtra(EXTRA_MESSAGE_TITLE) ?: "Pesan"
                val body = intent.getStringExtra(EXTRA_MESSAGE_BODY) ?: ""
                binding.tvTitle.text = "📩 $title"
                binding.tvSubtitle.text = body
                binding.btnUnlock.text = "Tutup Pesan"
                binding.pinInputArea.visibility = android.view.View.GONE
                binding.btnUnlock.setOnClickListener { finish() }
            }
            else -> { // APP_LOCK
                if (pin == null) {
                    binding.tvTitle.text = "Buat PIN Pertama Kali"
                    binding.tvSubtitle.text = "Masukkan PIN 4-6 digit untuk melindungi aplikasi ini"
                    binding.btnUnlock.text = "Simpan PIN"
                    binding.btnUnlock.setOnClickListener { setFirstPin() }
                } else {
                    binding.tvTitle.text = "Aplikasi Dikunci 🔒"
                    binding.tvSubtitle.text = "Masukkan PIN orang tua untuk membuka"
                    // Tombol "Buka Aplikasi" dihapus -- PIN akan diverifikasi
                    // OTOMATIS begitu jumlah digit yang diketik sudah pas
                    // sepanjang PIN yang di-set orang tua (lihat setupPinInputAndKeyboard).
                    binding.btnUnlock.visibility = android.view.View.GONE
                }
            }
        }

        // PENTING: jumlah titik PIN yang ditampilkan & batas digit yang bisa
        // diketik mengikuti PANJANG PIN ASLI yang di-set orang tua (4-6 digit),
        // bukan selalu 6 titik. Kalau belum ada PIN sama sekali (setup pertama
        // kali), tampilkan 6 titik sebagai batas maksimal yang boleh dibuat.
        val pinLength = pin?.length?.coerceIn(4, MAX_PIN_LENGTH) ?: MAX_PIN_LENGTH
        setupPinDotsCount(pinLength)
        binding.etPin.filters = arrayOf(android.text.InputFilter.LengthFilter(pinLength))

        if (mode != MODE_MESSAGE && pin != null) {
            binding.btnUnlock.setOnClickListener { verifyPin(pin) }
        }

        binding.btnGoHome.setOnClickListener { goHome() }

        if (mode == MODE_DEVICE_LOCK) {
            startPersistenceTimer()
        }

        if (mode != MODE_MESSAGE) {
            setupPinInputAndKeyboard()
        }
    }

    /**
     * Fix "keyboard tidak muncul": minta fokus ke etPin lalu paksa tampilkan
     * keyboard lewat InputMethodManager, dengan sedikit delay supaya window
     * benar-benar sudah attached (butuh delay khusus untuk window yang tampil
     * di atas keyguard). Ditambah fallback: tap di kolom PIN akan memaksa
     * keyboard muncul lagi kalau OEM tetap menutupnya otomatis, dan tombol
     * "Done" di keyboard langsung memicu verifikasi PIN.
     */
    private fun setupPinInputAndKeyboard() {
        binding.etPin.isFocusableInTouchMode = true
        binding.etPin.requestFocus()

        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager

        binding.etPin.post {
            binding.etPin.requestFocus()
            imm.showSoftInput(binding.etPin, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
        }
        // Delay tambahan sebagai jaring pengaman -- beberapa OEM butuh sedikit
        // jeda lebih lama setelah window over-keyguard benar-benar siap.
        binding.etPin.postDelayed({
            binding.etPin.requestFocus()
            imm.showSoftInput(binding.etPin, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
        }, 300)

        binding.etPin.setOnClickListener {
            binding.etPin.requestFocus()
            imm.showSoftInput(binding.etPin, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
        }

        binding.etPin.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                binding.btnUnlock.performClick()
                true
            } else {
                false
            }
        }

        // Update titik-titik PIN setiap kali teks berubah, dan tap di area
        // manapun di kotak PIN (termasuk di sekitar titik-titik) tetap fokus
        // ke EditText tersembunyi. Kalau ini layar VERIFIKASI (PIN sudah ada),
        // begitu jumlah digit yang diketik sudah PAS sepanjang PIN tersimpan,
        // langsung verifikasi otomatis -- tidak perlu tombol "Buka" lagi.
        val savedPinForAutoSubmit = AppLockPrefs.getPin(this)
        binding.etPin.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val length = s?.length ?: 0
                updatePinDots(length)
                if (mode != MODE_MESSAGE && savedPinForAutoSubmit != null && length == savedPinForAutoSubmit.length) {
                    verifyPin(savedPinForAutoSubmit)
                }
            }
        })
        binding.pinInputArea.setOnClickListener {
            binding.etPin.requestFocus()
            imm.showSoftInput(binding.etPin, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
        }
    }

    /**
     * Tampilkan hanya sejumlah `count` titik PIN (menyembunyikan sisanya),
     * supaya jumlah titik yang kelihatan PERSIS sama dengan panjang PIN asli
     * yang di-set orang tua -- bukan selalu 6 titik.
     */
    private fun setupPinDotsCount(count: Int) {
        val dots = listOf(binding.dot1, binding.dot2, binding.dot3, binding.dot4, binding.dot5, binding.dot6)
        dots.forEachIndexed { index, dot ->
            dot.visibility = if (index < count) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    /**
     * Isi/kosongkan titik-titik PIN sesuai jumlah karakter yang sudah diketik,
     * supaya kelihatan seperti kotak input PIN asli (bukan angka polos).
     */
    private fun updatePinDots(filledCount: Int) {
        val dots = listOf(binding.dot1, binding.dot2, binding.dot3, binding.dot4, binding.dot5, binding.dot6)
        dots.forEachIndexed { index, dot ->
            dot.setBackgroundResource(
                if (index < filledCount) com.familyguard.R.drawable.pin_dot_filled
                else com.familyguard.R.drawable.pin_dot_empty
            )
        }
    }

    /**
     * Fix "tombol Buka Aplikasi/Perangkat ketutup keyboard": karena hideSystemUI()
     * memanggil setDecorFitsSystemWindows(false) untuk mode immersive, sistem
     * BERHENTI otomatis menyusutkan layout saat keyboard muncul (konflik dengan
     * android:windowSoftInputMode="adjustResize"). Jadi kita tangani manual di
     * sini: dengarkan inset IME (keyboard), lalu kasih padding-bottom ke
     * ScrollView sebesar tinggi keyboard supaya seluruh konten -- termasuk
     * tombol paling bawah -- tetap bisa di-scroll sampai kelihatan penuh.
     */
    private fun setupKeyboardInsetsFix() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.rootScroll) { view, insets ->
            val imeHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, imeHeight)
            if (imeHeight > 0) {
                binding.rootScroll.post { binding.rootScroll.fullScroll(android.view.View.FOCUS_DOWN) }
            }
            insets
        }
    }

    private fun goHome() {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
        intent.addCategory(android.content.Intent.CATEGORY_HOME)
        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    private fun setFirstPin() {
        val input = binding.etPin.text.toString().trim()
        if (input.length < 4) {
            Toast.makeText(this, "PIN minimal 4 digit", Toast.LENGTH_SHORT).show()
            return
        }
        if (input.length > MAX_PIN_LENGTH) {
            Toast.makeText(this, "PIN maksimal $MAX_PIN_LENGTH digit", Toast.LENGTH_SHORT).show()
            return
        }
        AppLockPrefs.savePin(this, input)
        Toast.makeText(this, "PIN berhasil disimpan", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun verifyPin(savedPin: String) {
        val input = binding.etPin.text?.toString()?.trim() ?: ""
        if (input == savedPin.trim()) {
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
                // PENTING (fix lag & patah-patah pas ngetik PIN di Device Lock):
                // Sebelumnya startActivity() dipanggil TIAP 2 DETIK TANPA SYARAT,
                // termasuk saat activity ini sendiri sedang difokus & anak lagi
                // mengetik PIN -- tiap panggilan startActivity() ke sistem bikin
                // main thread kena hentakan (transaction ke ActivityManager), jadi
                // ketikan kerasa patah-patah. Sekarang HANYA relaunch kalau window
                // ini benar-benar SUDAH KEHILANGAN FOKUS (artinya ada yang berhasil
                // menutupi/mengalihkan layar ini) -- bukan proaktif tiap 2 detik.
                if (!isFinishing && mode == MODE_DEVICE_LOCK && !hasWindowFocus()) {
                    val intent = android.content.Intent(this@LockScreenActivity, LockScreenActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        putExtra(EXTRA_MODE, mode)
                    }
                    startActivity(intent)
                }
                if (!isFinishing && mode == MODE_DEVICE_LOCK) {
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
        isForeground = true
        hideSystemUI()
        // Munculin lagi keyboard tiap kali activity ini resume (misal habis watchdog
        // relaunch activity) -- hideSystemUI() kadang ikut menutup keyboard yang sudah tampil.
        if (mode != MODE_MESSAGE && ::binding.isInitialized) {
            binding.etPin.postDelayed({
                if (!isFinishing) {
                    binding.etPin.requestFocus()
                    val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(binding.etPin, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
                }
            }, 200)
        }
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
        // Auto-relaunch HANYA untuk MODE_DEVICE_LOCK (mengunci seluruh perangkat).
        // Untuk MODE_APP_LOCK, anak HARUS bisa keluar (pencet Home/Back) ke home screen
        // tanpa PIN -- yang diblokir itu re-entry ke app yang dikunci (via Accessibility
        // Service), bukan exit dari layar kunci ini. Kalau MODE_APP_LOCK ikut di-relaunch
        // di sini, anak jadi terjebak selamanya di layar kunci walau sudah "keluar".
        if (!isFinishing && mode == MODE_DEVICE_LOCK) {
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
        // Sama seperti onPause(): hanya kejar-kejar balik untuk MODE_DEVICE_LOCK.
        // MODE_APP_LOCK harus dibiarkan pergi ke Home tanpa dipaksa balik.
        if (mode == MODE_DEVICE_LOCK) {
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
        isForeground = false
        try {
            unregisterReceiver(unlockReceiver)
        } catch (e: Exception) {
            // ignore
        }
    }

    companion object {
        // Dibaca oleh AppLockAccessibilityService (di thread berbeda) untuk skip
        // watchdog polling total selama lock screen ini sedang tampil di depan --
        // supaya tidak ada beban tambahan ke main thread pas anak mengetik PIN.
        @Volatile
        var isForeground: Boolean = false

        const val EXTRA_LOCKED_PACKAGE = "locked_package"
        const val EXTRA_MODE = "mode"
        const val EXTRA_MESSAGE_TITLE = "msg_title"
        const val EXTRA_MESSAGE_BODY = "msg_body"

        const val MODE_APP_LOCK = "app_lock"
        const val MODE_DEVICE_LOCK = "device_lock"
        const val MODE_MESSAGE = "message"

        const val MAX_PIN_LENGTH = 6
    }
}