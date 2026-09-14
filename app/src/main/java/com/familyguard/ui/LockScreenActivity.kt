package com.familyguard.ui

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import com.familyguard.databinding.ActivityLockScreenBinding
import com.familyguard.sync.FamilyLink
import com.familyguard.utils.AppLockPrefs

class LockScreenActivity : Activity() {

    private lateinit var binding: ActivityLockScreenBinding
    private var lockedPackage: String? = null
    private var mode: String = MODE_APP_LOCK
    private var messageFromDeviceId: String = ""
    private var pendingApprovalRequestId: String? = null
    private var approvalStatusListener: com.google.firebase.database.ValueEventListener? = null

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
                    android.view.WindowManager.LayoutParams.FLAG_SECURE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        @Suppress("DEPRECATION")
        window.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

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
                binding.tvTitle.text = "Perangkat Terkunci"
                binding.tvSubtitle.text = "Gunakan PIN Orang Tua untuk Membuka Sesi"
                binding.btnGoHome.visibility = android.view.View.GONE
                binding.btnUnlock.visibility = android.view.View.GONE
            }
            MODE_MESSAGE -> {
                val title = intent.getStringExtra(EXTRA_MESSAGE_TITLE) ?: "Pesan"
                val body = intent.getStringExtra(EXTRA_MESSAGE_BODY) ?: ""
                messageFromDeviceId = intent.getStringExtra(EXTRA_MESSAGE_FROM) ?: ""

                binding.tvTitle.text = title
                binding.tvTitle.textSize = 18f

                binding.tvSubtitle.text = body
                binding.tvSubtitle.textSize = 19f
                binding.tvSubtitle.setTextColor(0xFFFFFFFF.toInt())
                binding.tvSubtitle.gravity = android.view.Gravity.START
                binding.tvSubtitle.setLineSpacing(6f, 1.1f)
                binding.tvSubtitle.layoutParams = (binding.tvSubtitle.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                    width = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                    topMargin = dpToPx(16)
                }

                binding.btnUnlock.text = "Tutup Pesan"
                binding.pinInputArea.visibility = android.view.View.GONE
                binding.btnGoHome.visibility = android.view.View.GONE
                binding.btnUnlock.setOnClickListener { finish() }

                if (messageFromDeviceId.isNotBlank()) {
                    binding.etReplyMessage.visibility = android.view.View.VISIBLE
                    binding.btnSendReply.visibility = android.view.View.VISIBLE
                    binding.btnSendReply.setOnClickListener { sendReply() }
                }
            }
            else -> {
                if (pin == null) {
                    binding.tvTitle.text = "Buat PIN Pertama Kali"
                    binding.tvSubtitle.text = "Masukkan PIN 4-6 digit untuk melindungi aplikasi ini"
                    binding.btnUnlock.text = "Simpan PIN"
                    binding.btnUnlock.setOnClickListener { setFirstPin() }
                } else {
                    binding.tvTitle.text = "Aplikasi Dikunci"
                    binding.tvSubtitle.text = "Masukkan PIN orang tua untuk membuka"

                    binding.btnUnlock.visibility = android.view.View.GONE

                    if (mode == MODE_APP_LOCK && lockedPackage != null) {
                        binding.btnRequestApproval.visibility = android.view.View.VISIBLE
                        binding.btnRequestApproval.setOnClickListener { requestApproval() }
                    }
                }
            }
        }

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

    private fun setupPinInputAndKeyboard() {
        binding.etPin.isFocusableInTouchMode = true
        binding.etPin.requestFocus()

        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager

        binding.etPin.post {
            binding.etPin.requestFocus()
            imm.showSoftInput(binding.etPin, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
        }

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

    private fun setupPinDotsCount(count: Int) {
        val dots = listOf(binding.dot1, binding.dot2, binding.dot3, binding.dot4, binding.dot5, binding.dot6)
        dots.forEachIndexed { index, dot ->
            dot.visibility = if (index < count) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun updatePinDots(filledCount: Int) {
        val dots = listOf(binding.dot1, binding.dot2, binding.dot3, binding.dot4, binding.dot5, binding.dot6)
        dots.forEachIndexed { index, dot ->
            dot.setBackgroundResource(
                if (index < filledCount) com.familyguard.R.drawable.pin_dot_filled
                else com.familyguard.R.drawable.pin_dot_empty
            )
        }
    }

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

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun goHome() {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
        intent.addCategory(android.content.Intent.CATEGORY_HOME)
        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    private fun sendReply() {
        val text = binding.etReplyMessage.text?.toString()?.trim() ?: ""
        if (text.isEmpty()) {
            Toast.makeText(this, "Tulis balasan dulu", Toast.LENGTH_SHORT).show()
            return
        }
        if (messageFromDeviceId.isBlank()) {
            finish()
            return
        }

        val myRole = AppLockPrefs.getRole(this)
        val myName = AppLockPrefs.getUserName(this)?.takeIf { it.isNotBlank() }
        val senderLabel = myName ?: if (myRole == AppLockPrefs.ROLE_PARENT) "Orang Tua" else "Anak"

        FamilyLink.sendMessage(this, "Balasan dari $senderLabel", text, messageFromDeviceId)
        Toast.makeText(this, "Balasan terkirim", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun requestApproval() {
        val pkg = lockedPackage ?: return
        val appName = try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }

        binding.btnRequestApproval.isEnabled = false
        binding.btnRequestApproval.text = "Mengirim permintaan..."

        FamilyLink.sendApprovalRequest(this, pkg, appName, REQUEST_DURATION_MINUTES) { requestId ->
            if (requestId == null) {
                Toast.makeText(this, "Gagal mengirim permintaan, coba lagi", Toast.LENGTH_SHORT).show()
                binding.btnRequestApproval.isEnabled = true
                binding.btnRequestApproval.text = "Minta Izin ke Orang Tua"
                return@sendApprovalRequest
            }

            pendingApprovalRequestId = requestId
            binding.btnRequestApproval.text = "Menunggu persetujuan..."
            binding.tvApprovalStatus.visibility = android.view.View.VISIBLE
            binding.tvApprovalStatus.text = "Permintaan terkirim ke Orang Tua, mohon tunggu ($REQUEST_DURATION_MINUTES menit jika disetujui)"

            approvalStatusListener = FamilyLink.observeApprovalRequestStatus(this, requestId) { status, durationMinutes ->
                when (status) {
                    "approved" -> {
                        AppLockPrefs.setApprovedTemporaryUnlock(this, pkg, durationMinutes)
                        Toast.makeText(this, "Disetujui! Aplikasi dibuka $durationMinutes menit", Toast.LENGTH_LONG).show()
                        FamilyLink.deleteApprovalRequest(this, requestId)
                        reopenLockedAppThenFinish(pkg)
                    }
                    "rejected" -> {
                        binding.tvApprovalStatus.text = "Permintaan ditolak oleh Orang Tua"
                        binding.btnRequestApproval.visibility = android.view.View.GONE
                        FamilyLink.deleteApprovalRequest(this, requestId)
                    }
                }
            }
        }
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

                AppLockPrefs.setPackageUnlocked(this, lockedPackage!!)
                Toast.makeText(this, "Berhasil dibuka", Toast.LENGTH_SHORT).show()
                reopenLockedAppThenFinish(lockedPackage!!)
            } else {
                if (mode == MODE_DEVICE_LOCK) {
                    AppLockPrefs.setDeviceLocked(this, false)
                }
                Toast.makeText(this, "Perangkat dibuka", Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            binding.etPin.text?.clear()
            binding.etPin.error = "PIN salah!"
            Toast.makeText(this, "PIN salah, coba lagi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun reopenLockedAppThenFinish(packageName: String) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
                startActivity(launchIntent)
            }
        } catch (e: Exception) {

        }
        finish()
    }

    private fun startPersistenceTimer() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {

                if (!isFinishing && mode == MODE_DEVICE_LOCK && !hasWindowFocus()) {
                    requestDeviceLock(this@LockScreenActivity)
                }
                if (!isFinishing && mode == MODE_DEVICE_LOCK) {
                    handler.postDelayed(this, 2000)
                }
            }
        }
        handler.postDelayed(runnable, 2000)
    }

    private fun hideSystemUI() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {

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

        if (!isFinishing && mode == MODE_DEVICE_LOCK) {
            requestDeviceLock(this)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (mode == MODE_MESSAGE) return super.dispatchKeyEvent(event)

        val blockedKeys = listOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_BACK
        )
        if (blockedKeys.contains(event.keyCode)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        @Suppress("DEPRECATION")
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (mode == MODE_MESSAGE) finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        if (mode == MODE_DEVICE_LOCK) {
            requestDeviceLock(this)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && (mode == MODE_DEVICE_LOCK || mode == MODE_APP_LOCK)) {

        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isForeground = false
        pendingApprovalRequestId?.let { reqId ->
            approvalStatusListener?.let { FamilyLink.removeApprovalRequestStatusListener(this, reqId, it) }
        }
        try {
            unregisterReceiver(unlockReceiver)
        } catch (e: Exception) {

        }
    }

    companion object {

        @Volatile
        var isForeground: Boolean = false

        @Volatile
        private var lastRelaunchAttemptMs: Long = 0L
        private const val RELAUNCH_COOLDOWN_MS = 1500L

        @Synchronized
        fun requestDeviceLock(context: android.content.Context) {
            if (isForeground) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastRelaunchAttemptMs < RELAUNCH_COOLDOWN_MS) return
            lastRelaunchAttemptMs = now

            try {
                val intent = android.content.Intent(context, LockScreenActivity::class.java).apply {
                    addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                    putExtra(EXTRA_MODE, MODE_DEVICE_LOCK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {

            }
        }

        const val EXTRA_LOCKED_PACKAGE = "locked_package"
        private const val REQUEST_DURATION_MINUTES = 15
        const val EXTRA_MODE = "mode"
        const val EXTRA_MESSAGE_TITLE = "msg_title"
        const val EXTRA_MESSAGE_BODY = "msg_body"
        const val EXTRA_MESSAGE_FROM = "msg_from"

        const val MODE_APP_LOCK = "app_lock"
        const val MODE_DEVICE_LOCK = "device_lock"
        const val MODE_MESSAGE = "message"

        const val MAX_PIN_LENGTH = 6
    }
}
