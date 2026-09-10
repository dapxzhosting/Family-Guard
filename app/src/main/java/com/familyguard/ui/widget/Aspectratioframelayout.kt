package com.familyguard.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/**
 * FrameLayout yang menjaga rasio lebar:tinggi tertentu, dibatasi supaya muat di dalam parent-nya
 * (jadi keliatan kayak "bingkai HP" yang di-center di tengah layar, mirip tampilan emulator).
 *
 * Dipakai buat bungkus rendererScreen: begitu HP anak rotate (misal lagi main game),
 * tinggal panggil setAspectRatio() dengan rasio baru dan bingkainya animasi muter/resize halus,
 * bukan lompat tiba-tiba.
 */
class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    // Default: rasio HP portrait biasa (9:19.5) sebelum video pertama datang.
    private var aspectRatio: Float = 9f / 19.5f
    private var sizeAnimator: ValueAnimator? = null

    fun setAspectRatio(ratio: Float, animate: Boolean = true) {
        if (ratio <= 0f) return
        if (kotlin.math.abs(ratio - aspectRatio) < 0.005f) return

        val oldRatio = aspectRatio

        if (!animate || width == 0 || height == 0) {
            aspectRatio = ratio
            requestLayout()
            return
        }

        sizeAnimator?.cancel()
        sizeAnimator = ValueAnimator.ofFloat(oldRatio, ratio).apply {
            duration = 320L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                aspectRatio = anim.animatedValue as Float
                requestLayout()
            }
            start()
        }
    }

    fun currentAspectRatio(): Float = aspectRatio

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
        val parentHeight = MeasureSpec.getSize(heightMeasureSpec)

        if (parentWidth <= 0 || parentHeight <= 0 || aspectRatio <= 0f) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        var targetW = parentWidth.toFloat()
        var targetH = targetW / aspectRatio
        if (targetH > parentHeight) {
            targetH = parentHeight.toFloat()
            targetW = targetH * aspectRatio
        }

        val exactW = MeasureSpec.makeMeasureSpec(targetW.toInt(), MeasureSpec.EXACTLY)
        val exactH = MeasureSpec.makeMeasureSpec(targetH.toInt(), MeasureSpec.EXACTLY)
        super.onMeasure(exactW, exactH)
        setMeasuredDimension(targetW.toInt(), targetH.toInt())
    }
}
