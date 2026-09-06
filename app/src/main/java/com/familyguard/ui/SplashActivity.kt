package com.familyguard.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.familyguard.R
import com.familyguard.databinding.ActivitySplashBinding
import com.familyguard.utils.AppLockPrefs
import com.google.firebase.auth.FirebaseAuth

class SplashActivity : BaseActivity() {

    private lateinit var binding: ActivitySplashBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var connectivityManager: ConnectivityManager

    private var floatingAnimator: ValueAnimator? = null
    private var progressAnimator: ValueAnimator? = null
    private var currentProgress = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var pendingDestination: (() -> Unit)? = null
    private var minDurationElapsed = false
    private var alreadyNavigated = false

    private var loadingGeneration = 0
    private var isWaitingForNetwork = false

    companion object {

        private const val SPLASH_MIN_DURATION_MS = 3200L

        private const val NETWORK_STALL_TIMEOUT_MS = 9000L

        private const val PROGRESS_NETWORK_CHECKED = 12
        private const val PROGRESS_AUTH_CHECKED = 35
        private const val PROGRESS_PROFILE_FETCHED = 92
        private const val PROGRESS_DONE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        auth = FirebaseAuth.getInstance()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        applyRoleTheme()

        playEntranceAnimation()
        registerNetworkMonitor()
        beginLoadingProcess()

        binding.root.postDelayed({
            minDurationElapsed = true
            tryNavigate()
        }, SPLASH_MIN_DURATION_MS)
    }

    private fun applyRoleTheme() {
        val role = AppLockPrefs.getRole(this)
        if (role == AppLockPrefs.ROLE_CHILD) {
            window.setBackgroundDrawableResource(R.drawable.bg_splash_gradient_green)
            binding.root.setBackgroundResource(R.drawable.bg_splash_gradient_green)
            binding.glowCircle.setBackgroundResource(R.drawable.bg_splash_logo_glow_green)
            binding.appTagline.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.dash_child_text_muted))
            binding.progressPercentText.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.dash_child_text_soft))
            window.statusBarColor = androidx.core.content.ContextCompat.getColor(this, R.color.dash_child_primary)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun registerNetworkMonitor() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {

                checkAndResumeLoading()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {

                checkAndResumeLoading()
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    if (!isNetworkAvailable()) {
                        isWaitingForNetwork = true
                        loadingGeneration++
                        showNetworkWarning()
                    }
                }
            }
        }
        connectivityManager.registerNetworkCallback(request, networkCallback as ConnectivityManager.NetworkCallback)
    }

    private fun checkAndResumeLoading() {
        runOnUiThread {

            if (isNetworkAvailable() && !alreadyNavigated) {
                if (isWaitingForNetwork || pendingDestination == null) {
                    isWaitingForNetwork = false
                    hideNetworkWarning()
                    beginLoadingProcess()
                }
            }
        }
    }

    private fun showNetworkWarning() {
        if (binding.networkWarningContainer.visibility == View.VISIBLE) return

        binding.networkWarningContainer.alpha = 0f
        binding.networkWarningContainer.visibility = View.VISIBLE
        binding.networkWarningContainer.animate()
            .alpha(1f)
            .setDuration(280L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        binding.networkWarningIcon.animate()
            .alpha(0.4f)
            .setDuration(700L)
            .setInterpolator(android.view.animation.LinearInterpolator())
            .withEndAction {
                binding.networkWarningIcon.animate()
                    .alpha(1f)
                    .setDuration(700L)
                    .withEndAction {
                        if (binding.networkWarningContainer.visibility == View.VISIBLE) {
                            showNetworkWarning()
                        }
                    }
                    .start()
            }
            .start()
    }

    private fun hideNetworkWarning() {
        if (binding.networkWarningContainer.visibility != View.VISIBLE) return
        binding.networkWarningContainer.animate()
            .alpha(0f)
            .setDuration(200L)
            .withEndAction {
                binding.networkWarningContainer.visibility = View.GONE
            }
            .start()
    }

    private fun beginLoadingProcess() {
        if (!isNetworkAvailable()) {
            isWaitingForNetwork = true
            showNetworkWarning()
            return
        }
        hideNetworkWarning()
        isWaitingForNetwork = false

        val myGeneration = ++loadingGeneration

        animateProgressTo(PROGRESS_NETWORK_CHECKED, 260L)

        resolveDestination(myGeneration)

        binding.root.postDelayed({
            if (myGeneration == loadingGeneration && pendingDestination == null) {
                isWaitingForNetwork = true
                showNetworkWarning()
            }
        }, NETWORK_STALL_TIMEOUT_MS)
    }

    private fun resolveDestination(myGeneration: Int) {
        val currentUser = auth.currentUser

        animateProgressTo(PROGRESS_AUTH_CHECKED, 260L)

        if (currentUser == null) {
            animateProgressTo(PROGRESS_DONE, 220L)
            pendingDestination = { goTo(LoginActivity::class.java) }
            tryNavigate()
            return
        }

        com.familyguard.sync.FamilyLink.fetchUserProfile(this) { found ->

            if (myGeneration != loadingGeneration) return@fetchUserProfile

            if (!isNetworkAvailable()) {
                isWaitingForNetwork = true
                loadingGeneration++
                showNetworkWarning()
                return@fetchUserProfile
            }

            animateProgressTo(PROGRESS_PROFILE_FETCHED, 320L)

            val role = AppLockPrefs.getRole(this)
            val name = AppLockPrefs.getUserName(this)

            if (!found && name.isNullOrBlank()) {

            }

            val target = when {
                name.isNullOrBlank() -> NameInputActivity::class.java
                role.isNullOrBlank() -> RoleSelectionActivity::class.java
                role == AppLockPrefs.ROLE_PARENT -> ParentMenuActivity::class.java
                else -> ChildMenuActivity::class.java
            }

            animateProgressTo(PROGRESS_DONE, 220L)

            pendingDestination = { goTo(target) }
            tryNavigate()
        }
    }

    private fun animateProgressTo(target: Int, durationMs: Long) {
        if (target <= currentProgress) return
        progressAnimator?.cancel()
        val start = currentProgress
        progressAnimator = ValueAnimator.ofInt(start, target).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val value = anim.animatedValue as Int
                currentProgress = value
                binding.loadingProgressBar.progress = value
                binding.progressPercentText.text = "$value%"
            }
            start()
        }
    }

    private fun tryNavigate() {
        if (alreadyNavigated) return
        if (!minDurationElapsed) return
        val destination = pendingDestination ?: return
        alreadyNavigated = true
        destination.invoke()
    }

    private fun playEntranceAnimation() {

        binding.glowCircle.animate()
            .alpha(1f)
            .setDuration(700L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        val logoScaleX = ObjectAnimator.ofFloat(binding.logoImage, "scaleX", 0.4f, 1f)
        val logoScaleY = ObjectAnimator.ofFloat(binding.logoImage, "scaleY", 0.4f, 1f)
        val logoAlpha = ObjectAnimator.ofFloat(binding.logoImage, "alpha", 0f, 1f)
        val logoRotate = ObjectAnimator.ofFloat(binding.logoImage, "rotation", -18f, 0f)

        val logoEntrance = AnimatorSet().apply {
            playTogether(logoScaleX, logoScaleY, logoAlpha, logoRotate)
            duration = 1100L
            interpolator = OvershootInterpolator(1.1f)
        }

        val nameAlpha = ObjectAnimator.ofFloat(binding.appNameText, "alpha", 0f, 1f)
        val nameSlide = ObjectAnimator.ofFloat(binding.appNameText, "translationY", 24f, 0f)
        val nameSet = AnimatorSet().apply {
            playTogether(nameAlpha, nameSlide)
            duration = 650L
            interpolator = DecelerateInterpolator()
            startDelay = 550L
        }

        val taglineAlpha = ObjectAnimator.ofFloat(binding.appTagline, "alpha", 0f, 1f)
        val taglineSlide = ObjectAnimator.ofFloat(binding.appTagline, "translationY", 16f, 0f)
        val taglineSet = AnimatorSet().apply {
            playTogether(taglineAlpha, taglineSlide)
            duration = 650L
            interpolator = DecelerateInterpolator()
            startDelay = 750L
        }

        val progressAlphaSet = ObjectAnimator.ofFloat(binding.progressContainer, "alpha", 0f, 1f).apply {
            duration = 500L
            interpolator = DecelerateInterpolator()
            startDelay = 950L
        }

        logoEntrance.start()
        nameSet.start()
        taglineSet.start()
        progressAlphaSet.start()

        binding.logoImage.postDelayed({ startFloatingLoop() }, logoEntrance.duration)
    }

    private fun startFloatingLoop() {

        floatingAnimator = ValueAnimator.ofFloat(0f, -16f, 0f).apply {
            duration = 2000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { anim ->
                binding.logoImage.translationY = anim.animatedValue as Float
            }
            start()
        }

        ValueAnimator.ofFloat(1f, 1.14f, 1f).apply {
            duration = 2000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { anim ->
                val scale = anim.animatedValue as Float
                binding.glowCircle.scaleX = scale
                binding.glowCircle.scaleY = scale
            }
            start()
        }
    }

    private fun <T> goTo(target: Class<T>) {
        floatingAnimator?.cancel()
        progressAnimator?.cancel()

        binding.root.animate()
            .alpha(0f)
            .setDuration(320L)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                startActivity(Intent(this, target))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                finish()
            }
            .start()
    }

    override fun onDestroy() {
        super.onDestroy()
        floatingAnimator?.cancel()
        progressAnimator?.cancel()
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: IllegalArgumentException) {

            }
        }
    }
}

