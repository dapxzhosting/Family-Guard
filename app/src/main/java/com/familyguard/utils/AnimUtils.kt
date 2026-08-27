package com.familyguard.utils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.recyclerview.widget.RecyclerView

/**
 * Kumpulan animasi native Android (pakai ViewPropertyAnimator, bukan library
 * eksternal) supaya dashboard terasa smooth mirip Framer Motion/GSAP di web:
 * - Entrance animation (fade + slide-up) yang staggered per card
 * - Press feedback (scale down sedikit saat ditekan) buat tombol & item
 * - Staggered fade+slide untuk item RecyclerView (dipakai di list anggota keluarga)
 */
object AnimUtils {

    /**
     * Animasikan semua direct child dari [container] satu per satu (staggered):
     * mulai dari transparan + turun sedikit dari atas, lalu fade-in + slide ke
     * posisi asli. [staggerDelayMs] adalah jeda antar child supaya urutannya
     * kelihatan "mengalir" dari atas ke bawah, bukan muncul bareng semua.
     */
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

    /**
     * Pasang efek "press" (scale down halus saat ditekan, kembali membesar
     * dengan sedikit overshoot saat dilepas) ke sebuah View -- biasanya
     * dipakai untuk Button atau CardView yang clickable, supaya ada feedback
     * visual yang smooth setiap kali disentuh.
     */
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
            // return false supaya click listener/ripple bawaan tetap jalan normal
            false
        }
    }

    /**
     * Terapkan attachPressAnimation ke semua Button & CardView di dalam
     * [root] secara rekursif -- jadi tidak perlu pasang manual satu-satu ke
     * setiap tombol di layout yang isinya banyak card.
     */
    fun attachPressAnimationRecursively(root: View) {
        if (root is android.widget.Button || root is androidx.cardview.widget.CardView) {
            attachPressAnimation(root)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                attachPressAnimationRecursively(root.getChildAt(i))
            }
        }
    }

    /**
     * Sembunyikan sebuah View dengan animasi smooth: fade-out + sedikit
     * mengecil (scaleY), lalu tinggi View-nya di-collapse pelan-pelan ke 0
     * (bukan langsung GONE tiba-tiba) supaya card di bawahnya "naik" mengisi
     * ruang kosong secara halus. Dipakai misalnya saat kartu "Dashboard"/
     * "Hapus Keluarga" harus hilang setelah keluarga dihapus.
     *
     * Kalau View ini nanti perlu dimunculkan lagi (mis. user bikin keluarga
     * baru), height dikembalikan ke WRAP_CONTENT di akhir supaya tidak
     * "nyangkut" di tinggi 0 selamanya.
     */
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
                        // Reset supaya kalau di-VISIBLE-kan lagi nanti, tingginya normal.
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

    /**
     * RecyclerView.ItemAnimator kustom yang bikin item baru muncul dengan
     * fade + slide dari kanan (bukan langsung "muncul" begitu saja) --
     * dipakai di daftar anggota keluarga supaya kelihatan hidup tiap kali
     * data online/offline berubah dari Firebase.
     */
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
