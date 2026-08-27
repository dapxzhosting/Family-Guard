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
import android.util.Log
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.familyguard.databinding.ActivitySplashBinding
import com.familyguard.utils.AppLockPrefs
import com.google.firebase.auth.FirebaseAuth

/**
 * Splash screen pertama yang tampil saat apk dibuka.
 *
 * Splash ini menggantikan loading spinner yang dulu muncul sendirian di
 * bawah tombol Google pada LoginActivity setiap kali user sudah pernah
 * login sebelumnya (auto sign-in). Proses cek "sudah login atau belum" +
 * fetch profil dari Firebase dijalankan DI SINI, di belakang layar,
 * sementara animasi logo + progress bar 0%-100% berjalan smooth di depan
 * mengikuti tahapan proses yang nyata (bukan animasi kosong berbasis timer
 * semata). Activity baru berpindah setelah animasi minimum selesai DAN
 * proses pengecekan akun benar-benar selesai.
 *
 * Progress bar dibagi jadi beberapa tahap (checkpoint) yang merepresentasikan
 * proses nyata:
 *  - 0%   -> 12%  : mengecek ketersediaan jaringan
 *  - 12%  -> 35%  : mengecek status login (Firebase Auth)
 *  - 35%  -> 92%  : mengambil data profil dari server (request jaringan asli)
 *  - 92%  -> 100% : finalisasi sebelum pindah halaman
 * Progress dianimasikan smooth antar checkpoint begitu tahap itu benar-benar
 * selesai, jadi kecepatan mengisi progress bar mengikuti kecepatan proses
 * jaringan yang sesungguhnya -- kalau jaringan lambat, progress akan
 * "menempel" lebih lama di satu checkpoint sampai tahap itu selesai.
 *
 * Kalau tidak ada jaringan sama sekali, splash berhenti di progress terakhir
 * yang berhasil dicapai dan menampilkan ikon + tulisan "Jaringan tidak
 * tersedia". Splash otomatis melanjutkan proses begitu jaringan kembali
 * tersedia (dipantau real-time lewat ConnectivityManager).
 */
class SplashActivity : AppCompatActivity() {

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

    // "Nomor percobaan" proses loading saat ini. Setiap kali proses loading
    // (ulang) dimulai, angka ini bertambah. Callback async (misalnya hasil
    // fetch profil Firebase) HANYA dianggap valid kalau nomor percobaan saat
    // callback itu dibuat masih sama dengan yang aktif sekarang -- kalau
    // sudah berubah (karena sempat retry akibat jaringan putus-nyambung),
    // callback lama itu diabaikan. Ini mencegah splash "stuck" karena
    // menunggu request lama yang sudah tidak relevan lagi.
    private var loadingGeneration = 0
    private var isWaitingForNetwork = false

    companion object {
        // Durasi minimum animasi splash tampil, supaya animasi logo & progress
        // sempat "kelihatan" jelas walaupun proses cek akun selesai sangat cepat.
        private const val SPLASH_MIN_DURATION_MS = 3200L

        // Kalau satu percobaan macet lebih lama dari ini (misalnya request ke
        // Firebase menggantung tanpa callback sukses/gagal), anggap sebagai
        // masalah jaringan dan tawarkan retry, alih-alih splash diam selamanya.
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

        playEntranceAnimation()
        registerNetworkMonitor()
        beginLoadingProcess()

        binding.root.postDelayed({
            minDurationElapsed = true
            tryNavigate()
        }, SPLASH_MIN_DURATION_MS)
    }

    // ============================================================
    //  CEK JARINGAN
    // ============================================================

    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Pantau perubahan jaringan secara real-time. Kalau jaringan hilang di
     * tengah proses, batalkan percobaan yang sedang berjalan (naikkan
     * loadingGeneration) supaya callback lama yang mungkin masih menggantung
     * tidak lagi dianggap valid, lalu tampilkan peringatan. Begitu jaringan
     * kembali tersedia, mulai percobaan baru dari awal secara otomatis --
     * ini yang memperbaiki splash yang dulu "stuck" walau jaringan sudah
     * kembali normal.
     */
    /**
     * Pantau perubahan jaringan secara real-time.
     */
    private fun registerNetworkMonitor() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Saat ada jaringan terhubung, cek apakah jaringan tersebut valid/bisa akses internet
                checkAndResumeLoading()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                // Dipanggil saat status internet terverifikasi (VALIDATED)
                checkAndResumeLoading()
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    if (!isNetworkAvailable()) {
                        isWaitingForNetwork = true
                        loadingGeneration++ // Batalkan percobaan yang sedang berjalan
                        showNetworkWarning()
                    }
                }
            }
        }
        connectivityManager.registerNetworkCallback(request, networkCallback as ConnectivityManager.NetworkCallback)
    }

    private fun checkAndResumeLoading() {
        runOnUiThread {
            // Jika jaringan benar-benar valid dan kita sedang belum bernavigasi/sedang menunggu
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

        // Ikon jaringan berdenyut pelan (pulse) selama menunggu koneksi kembali
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

    // ============================================================
    //  PROSES LOADING (progress 0% -> 100%)
    // ============================================================

    private fun beginLoadingProcess() {
        if (!isNetworkAvailable()) {
            isWaitingForNetwork = true
            showNetworkWarning()
            return
        }
        hideNetworkWarning()
        isWaitingForNetwork = false

        val myGeneration = ++loadingGeneration

        // Tahap 1: jaringan terdeteksi tersedia
        animateProgressTo(PROGRESS_NETWORK_CHECKED, 260L)

        resolveDestination(myGeneration)

        // Jaring pengaman: kalau setelah beberapa detik percobaan ini masih
        // berjalan (belum ada tujuan & belum diganti oleh percobaan baru),
        // anggap koneksi bermasalah (misalnya request menggantung) dan
        // tampilkan peringatan supaya splash tidak diam selamanya.
        binding.root.postDelayed({
            if (myGeneration == loadingGeneration && pendingDestination == null) {
                isWaitingForNetwork = true
                showNetworkWarning()
            }
        }, NETWORK_STALL_TIMEOUT_MS)
    }

    /**
     * Menentukan activity tujuan berikutnya, PERSIS logika yang sebelumnya
     * ada di LoginActivity.routeNext() -- kalau user belum pernah login,
     * tujuannya LoginActivity seperti biasa. Kalau user sudah login, proses
     * fetch profil dijalankan di sini supaya transisinya langsung ke halaman
     * akhir tanpa loading tambahan lagi, dan progress bar mengikuti setiap
     * tahap proses jaringan yang sesungguhnya.
     *
     * [myGeneration] adalah nomor percobaan saat fungsi ini dipanggil --
     * dipakai untuk memastikan hasil callback Firebase di bawah hanya
     * diproses kalau ini masih percobaan yang aktif (belum dibatalkan oleh
     * putusnya jaringan di tengah jalan).
     */
    private fun resolveDestination(myGeneration: Int) {
        val currentUser = auth.currentUser

        // Tahap 2: status login sudah diketahui
        animateProgressTo(PROGRESS_AUTH_CHECKED, 260L)

        if (currentUser == null) {
            animateProgressTo(PROGRESS_DONE, 220L)
            pendingDestination = { goTo(LoginActivity::class.java) }
            tryNavigate()
            return
        }

        com.familyguard.sync.FamilyLink.fetchUserProfile(this) { found ->
            // Kalau percobaan ini sudah dibatalkan (jaringan sempat putus di
            // tengah proses fetch), abaikan hasilnya -- percobaan baru sudah
            // atau akan berjalan sendiri lewat beginLoadingProcess().
            if (myGeneration != loadingGeneration) return@fetchUserProfile

            if (!isNetworkAvailable()) {
                isWaitingForNetwork = true
                loadingGeneration++
                showNetworkWarning()
                return@fetchUserProfile
            }

            // Tahap 3: data profil selesai diambil dari server
            animateProgressTo(PROGRESS_PROFILE_FETCHED, 320L)

            val role = AppLockPrefs.getRole(this)
            val code = AppLockPrefs.getFamilyCode(this)
            val name = AppLockPrefs.getUserName(this)

            if (!found && name.isNullOrBlank()) {
                Log.d(
                    "SplashActivity",
                    "Tidak ada profil ditemukan untuk akun ini di Firebase -- kalau " +
                            "seharusnya akun ini SUDAH pernah setup di HP lain, cek Firebase " +
                            "Realtime Database Rules untuk path /users/{uid} (lihat log tag FamilyLink)."
                )
            }

            val target = when {
                name.isNullOrBlank() -> NameInputActivity::class.java
                role.isNullOrBlank() -> RoleSelectionActivity::class.java
                code.isNullOrBlank() && role == AppLockPrefs.ROLE_PARENT -> DashboardActivity::class.java
                code.isNullOrBlank() -> FamilyCodeActivity::class.java
                role == AppLockPrefs.ROLE_PARENT -> DashboardActivity::class.java
                else -> ChildHomeActivity::class.java
            }

            // Tahap 4: finalisasi, siap berpindah halaman
            animateProgressTo(PROGRESS_DONE, 220L)

            pendingDestination = { goTo(target) }
            tryNavigate()
        }
    }

    /**
     * Animasikan progress bar dari nilai saat ini menuju [target] secara
     * smooth, lalu update juga teks persentasenya. Dipanggil setiap satu
     * tahap proses nyata selesai, jadi kecepatan pengisian bar merefleksikan
     * kecepatan proses jaringan yang sesungguhnya, bukan sekadar hitungan
     * mundur waktu tetap.
     */
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

    /**
     * Baru benar-benar pindah activity kalau DUA syarat sudah terpenuhi:
     * animasi minimum sudah selesai diputar, DAN tujuan berikutnya sudah
     * ditentukan (progress sudah mencapai 100%). Kalau salah satu belum
     * siap, cukup menunggu -- animasi logo tetap looping smooth di layar.
     */
    private fun tryNavigate() {
        if (alreadyNavigated) return
        if (!minDurationElapsed) return
        val destination = pendingDestination ?: return
        alreadyNavigated = true
        destination.invoke()
    }

    // ============================================================
    //  ANIMASI LOGO
    // ============================================================

    private fun playEntranceAnimation() {
        // Glow fade-in halus di belakang logo
        binding.glowCircle.animate()
            .alpha(1f)
            .setDuration(700L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // Logo: scale up dari 0.4 -> 1 dengan overshoot ringan + fade-in + rotasi kecil
        val logoScaleX = ObjectAnimator.ofFloat(binding.logoImage, "scaleX", 0.4f, 1f)
        val logoScaleY = ObjectAnimator.ofFloat(binding.logoImage, "scaleY", 0.4f, 1f)
        val logoAlpha = ObjectAnimator.ofFloat(binding.logoImage, "alpha", 0f, 1f)
        val logoRotate = ObjectAnimator.ofFloat(binding.logoImage, "rotation", -18f, 0f)

        val logoEntrance = AnimatorSet().apply {
            playTogether(logoScaleX, logoScaleY, logoAlpha, logoRotate)
            duration = 1100L
            interpolator = OvershootInterpolator(1.1f)
        }

        // Teks nama app & tagline menyusul dengan stagger, fade + slide-up
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

        // Progress container menyusul paling akhir
        val progressAlphaSet = ObjectAnimator.ofFloat(binding.progressContainer, "alpha", 0f, 1f).apply {
            duration = 500L
            interpolator = DecelerateInterpolator()
            startDelay = 950L
        }

        logoEntrance.start()
        nameSet.start()
        taglineSet.start()
        progressAlphaSet.start()

        // Logo "mengambang" naik-turun terus-menerus supaya splash terasa hidup,
        // dimulai setelah entrance animation logo selesai.
        binding.logoImage.postDelayed({ startFloatingLoop() }, logoEntrance.duration)
    }

    private fun startFloatingLoop() {
        // Loop ini berjalan terus (INFINITE) selama splash tampil, termasuk
        // kalau proses cek akun di background ternyata butuh waktu lebih
        // lama dari SPLASH_MIN_DURATION_MS -- animasi tidak akan terlihat
        // berhenti/macet sambil menunggu.
        floatingAnimator = ValueAnimator.ofFloat(0f, -16f, 0f).apply {
            duration = 2000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { anim ->
                binding.logoImage.translationY = anim.animatedValue as Float
            }
            start()
        }

        // Glow ikut "bernapas" (scale halus) mengikuti irama logo
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

        // Fade-out seluruh konten splash sebelum pindah activity, biar transisinya smooth
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
                // Callback sudah tidak terdaftar, aman diabaikan
            }
        }
    }
}
