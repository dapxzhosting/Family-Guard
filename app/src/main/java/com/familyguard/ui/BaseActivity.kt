package com.familyguard.ui

import android.animation.ObjectAnimator
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.familyguard.R
import com.familyguard.utils.NetworkStatusHelper

abstract class BaseActivity : AppCompatActivity() {

    private var unregisterNetworkCallback: (() -> Unit)? = null
    private var networkBanner: View? = null
    private val skeletonAnimators = mutableListOf<ObjectAnimator>()
    private var isConnected = true

    protected fun registerSkeletonAnimator(animator: ObjectAnimator) {
        skeletonAnimators.add(animator)
        if (!isConnected) animator.pause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        unregisterNetworkCallback = NetworkStatusHelper.observe(this) { connected ->
            runOnUiThread { onConnectivityChanged(connected) }
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterNetworkCallback?.invoke()
        unregisterNetworkCallback = null
    }

    private fun onConnectivityChanged(connected: Boolean) {
        isConnected = connected
        if (connected) {
            skeletonAnimators.forEach { if (it.isPaused) it.resume() }
            networkBanner?.visibility = View.GONE
        } else {

            skeletonAnimators.forEach { if (it.isRunning) it.pause() }
            showNetworkBanner()
        }
    }

    private fun showNetworkBanner() {
        val root = (findViewById<View>(android.R.id.content) as ViewGroup)
        if (networkBanner == null) {
            networkBanner = buildBannerView()
            root.addView(networkBanner)
        }
        networkBanner?.visibility = View.VISIBLE
        networkBanner?.bringToFront()
    }

    private fun buildBannerView(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#B3261E"))
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_network_off)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                marginEnd = dp(8)
            }
        }
        val text = TextView(this).apply {
            text = "Tidak ada jaringan"
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        bar.addView(icon)
        bar.addView(text)

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        )
        bar.layoutParams = params
        bar.elevation = dp(8).toFloat()
        return bar
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

