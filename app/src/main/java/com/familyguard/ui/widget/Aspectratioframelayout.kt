package com.familyguard.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

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

    var rotated90: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    fun currentAspectRatio(): Float = aspectRatio

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var parentWidth = MeasureSpec.getSize(widthMeasureSpec)
        var parentHeight = MeasureSpec.getSize(heightMeasureSpec)

        if (rotated90) {
            val tmp = parentWidth
            parentWidth = parentHeight
            parentHeight = tmp
        }

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
