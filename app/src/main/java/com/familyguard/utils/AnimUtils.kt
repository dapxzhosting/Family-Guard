package com.familyguard.utils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.recyclerview.widget.RecyclerView

object AnimUtils {

    fun startSkeletonPulse(view: View): android.animation.ObjectAnimator {
        val animator = android.animation.ObjectAnimator.ofFloat(view, "alpha", 1f, 0.4f, 1f)
        animator.duration = 900L
        animator.repeatCount = android.animation.ValueAnimator.INFINITE
        animator.start()
        return animator
    }

    const val MIN_SKELETON_DURATION_MS = 500L

    fun finishSkeleton(
        skeleton: View,
        content: View,
        skeletonAnimator: android.animation.ObjectAnimator?,
        startedAtMs: Long,
        hasContent: Boolean = true,
        minDurationMs: Long = MIN_SKELETON_DURATION_MS
    ) {
        val elapsed = System.currentTimeMillis() - startedAtMs
        val remaining = (minDurationMs - elapsed).coerceAtLeast(0)
        skeleton.postDelayed({
            if (hasContent) {
                crossFadeToContent(skeleton, content, skeletonAnimator)
            } else {
                skeletonAnimator?.cancel()
                skeleton.visibility = View.GONE
                content.visibility = View.VISIBLE
            }
        }, remaining)
    }

    fun crossFadeToContent(
        skeleton: View,
        content: View,
        skeletonAnimator: android.animation.ObjectAnimator?,
        durationMs: Long = 250L
    ) {
        skeletonAnimator?.cancel()
        skeleton.animate()
            .alpha(0f)
            .setDuration(durationMs)
            .withEndAction { skeleton.visibility = View.GONE }
            .start()

        content.alpha = 0f
        content.visibility = View.VISIBLE
        content.animate()
            .alpha(1f)
            .setDuration(durationMs)
            .setStartDelay(80L)
            .start()
    }

    fun staggerFadeSlideIn(
        container: ViewGroup,
        staggerDelayMs: Long = 80L,
        durationMs: Long = 420L
    ) {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            child.alpha = 0f
            child.translationY = 60f
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(i * staggerDelayMs)
                .setDuration(durationMs)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .start()
        }
    }

    fun attachPressAnimation(view: View, scaleDown: Float = 0.96f) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(scaleDown)
                        .scaleY(scaleDown)
                        .setDuration(120L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(180L)
                        .setInterpolator(OvershootInterpolator(2f))
                        .start()
                }
            }

            false
        }
    }

    fun attachPressAnimationRecursively(root: View) {
        if (root is android.widget.Button || (root is androidx.cardview.widget.CardView && root.isClickable)) {
            attachPressAnimation(root)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                attachPressAnimationRecursively(root.getChildAt(i))
            }
        }
    }

    fun collapseAndHide(view: View, durationMs: Long = 260L) {
        if (view.visibility != View.VISIBLE) return

        val originalHeight = view.height.takeIf { it > 0 } ?: view.measuredHeight

        view.animate()
            .alpha(0f)
            .scaleY(0.85f)
            .setDuration(durationMs)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                val params = view.layoutParams
                val heightAnimator = android.animation.ValueAnimator.ofInt(originalHeight, 0)
                heightAnimator.duration = 180L
                heightAnimator.interpolator = DecelerateInterpolator()
                heightAnimator.addUpdateListener { anim ->
                    params.height = anim.animatedValue as Int
                    view.layoutParams = params
                }
                heightAnimator.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.visibility = View.GONE

                        params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        view.layoutParams = params
                        view.alpha = 1f
                        view.scaleY = 1f
                    }
                })
                heightAnimator.start()
            }
            .start()
    }

    fun applySlideInItemAnimator(recyclerView: RecyclerView) {
        recyclerView.itemAnimator = object : androidx.recyclerview.widget.DefaultItemAnimator() {
            override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
                holder.itemView.alpha = 0f
                holder.itemView.translationX = 80f
                holder.itemView.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(300L)
                    .setInterpolator(DecelerateInterpolator())
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            dispatchAddFinished(holder)
                        }
                    })
                    .start()
                return true
            }
        }
    }
}

