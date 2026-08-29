package com.familyguard.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.familyguard.R
import com.familyguard.databinding.ActivityDashboardBinding
import com.familyguard.utils.AccountActions
import com.familyguard.utils.AnimUtils
import com.familyguard.utils.AppLockPrefs

/**
 * Menu utama khusus role ORANG TUA, muncul setelah pilih role di
 * RoleSelectionActivity. Isinya:
 * - Dashboard        -> ParentDashboardActivity (pantau & kontrol HP anak).
 *                        Kartu ini disembunyikan sama sekali kalau belum
 *                        ada keluarga (lihat updateFamilyStatus()).
 * - Buat Keluarga    -> FamilyNameActivity (alur bikin nama + kode keluarga)
 * - Pengaturan       -> SettingsActivity (info akun: nama & email)
 * - Kebijakan Privasi -> PrivacyPolicyActivity (tampilan untuk dibaca)
 * - Hapus Keluarga   -> AccountActions.deleteFamily() (hanya tampil kalau
 *                        sudah ada keluarga)
 * - Reset Role       -> AccountActions.resetRole()
 * - Logout Akun      -> AccountActions.logout()
 *
 * Reset Role, Ganti Keluarga & Logout SEBELUMNYA ada di section "Lainnya"
 * pada activity_parent_dashboard.xml -- sekarang dipindah/dilengkapi di
 * sini supaya semua aksi akun terpusat di satu menu, jadi section itu
 * sudah dihapus dari ParentDashboardActivity.
 *
 * Role ANAK tidak lewat activity ini -- alurnya tetap langsung ke
 * FamilyCodeActivity / ChildHomeActivity seperti sebelumnya.
 */
class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateFamilyStatus()

        binding.btnMenuDashboard.setOnClickListener {
            if (AppLockPrefs.getFamilyCode(this).isNullOrBlank()) {
                Toast.makeText(this, "Buat keluarga dulu sebelum buka dashboard", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, ParentDashboardActivity::class.java))
            }
        }

        binding.btnMenuCreateFamily.setOnClickListener {
            startActivity(Intent(this, FamilyNameActivity::class.java))
        }

        binding.btnMenuSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_up_in, R.anim.stay_dim)
        }

        // Aksi klik untuk membuka halaman Kebijakan Privasi dari Dashboard
        binding.btnPrivacyPolicy.setOnClickListener {
            val intent = Intent(this, PrivacyPolicyActivity::class.java).apply {
                // Karena dibuka dari Dashboard, kita kirim status "true" agar tombol "Setuju" dihilangkan (hanya untuk dibaca)
                putExtra(PrivacyPolicyActivity.EXTRA_IS_FROM_SETTINGS, true)
            }
            startActivity(intent)
        }

        binding.btnMenuDeleteFamily.setOnClickListener {
            if (AppLockPrefs.getFamilyCode(this).isNullOrBlank()) {
                Toast.makeText(this, "Belum ada keluarga untuk dihapus", Toast.LENGTH_SHORT).show()
            } else {
                AccountActions.deleteFamily(this) {
                    // Jangan panggil updateFamilyStatus() di sini -- itu langsung
                    // GONE tanpa animasi. Card "Dashboard" & "Hapus Keluarga"
                    // perlu hilang smooth (fade + collapse), baru teks status
                    // di kartu "Buat Keluarga" diperbarui.
                    runOnUiThread { animateFamilyDeleted() }
                }
            }
        }

        binding.btnMenuResetRole.setOnClickListener {
            AccountActions.resetRole(this)
        }

        binding.btnMenuLogout.setOnClickListener {
            AccountActions.logout(this)
        }

        AnimUtils.staggerFadeSlideIn(binding.rootMenuContent)
        AnimUtils.attachPressAnimationRecursively(binding.rootMenuContent)
    }

    /** Dipanggil setelah AccountActions.deleteFamily() berhasil: kartu
     *  "Dashboard" & "Hapus Keluarga" hilang dengan animasi fade+collapse
     *  yang smooth, dan kartu "Buat Keluarga" dikembalikan ke state semula
     *  (klik-able lagi, ikon & judul balik seperti awal). */
    private fun animateFamilyDeleted() {
        binding.tvMenuFamilyTitle.text = "Buat Keluarga"
        binding.tvMenuFamilyStatus.text = "Belum ada keluarga"
        binding.ivMenuFamilyIcon.setImageResource(R.drawable.ic_menu_family)
        binding.ivMenuFamilyChevron.visibility = View.VISIBLE
        binding.btnMenuCreateFamily.isClickable = true
        binding.btnMenuCreateFamily.isFocusable = true
        binding.btnMenuCreateFamily.cardElevation = resources.displayMetrics.density * 3
        binding.btnMenuCreateFamily.setCardBackgroundColor(android.graphics.Color.WHITE)
        val outValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        binding.btnMenuCreateFamily.foreground = androidx.core.content.ContextCompat.getDrawable(this, outValue.resourceId)
        AnimUtils.attachPressAnimation(binding.btnMenuCreateFamily)
        AnimUtils.collapseAndHide(binding.btnMenuDashboard)
        AnimUtils.collapseAndHide(binding.btnMenuDeleteFamily)
    }

    override fun onResume() {
        super.onResume()
        updateFamilyStatus()
    }

    /** Tampilkan status singkat di kartu "Buat Keluarga", dan sembunyikan
     *  kartu "Dashboard" & "Hapus Keluarga" sama sekali kalau akun ini
     *  belum tergabung di keluarga manapun -- daripada cuma toast pas
     *  diklik, biar dari awal jelas menu itu belum relevan.
     *
     *  Kartu "Buat Keluarga" sendiri TIDAK disembunyikan, tapi
     *  "bertransformasi": kalau sudah ada keluarga, jadi kartu info
     *  non-klik "Sudah Terhubung: <nama keluarga>" (tombolnya "hilang"
     *  secara fungsional -- tidak bisa diklik lagi & panah navigasinya
     *  disembunyikan), karena tidak relevan lagi bikin keluarga baru
     *  selama masih tergabung di satu keluarga. */
    private fun updateFamilyStatus() {
        val code = AppLockPrefs.getFamilyCode(this)
        val name = AppLockPrefs.getFamilyName(this)
        val hasFamily = !code.isNullOrBlank()

        binding.btnMenuDashboard.visibility = if (hasFamily) View.VISIBLE else View.GONE
        binding.btnMenuDeleteFamily.visibility = if (hasFamily) View.VISIBLE else View.GONE

        if (hasFamily) {
            binding.tvMenuFamilyTitle.text = "Sudah Terhubung"
            binding.tvMenuFamilyStatus.text = "Keluarga: ${name?.takeIf { it.isNotBlank() } ?: code}"
            binding.ivMenuFamilyIcon.setImageResource(R.drawable.ic_check_circle)
            binding.ivMenuFamilyChevron.visibility = View.GONE
            binding.btnMenuCreateFamily.isClickable = false
            binding.btnMenuCreateFamily.isFocusable = false
            // Bukan cuma dimatikan kliknya -- tampilannya juga diratain jadi
            // info biasa (bukan elevasi/warna kartu ala tombol), supaya
            // gak kelihatan lagi seperti sesuatu yang bisa dipencet.
            binding.btnMenuCreateFamily.cardElevation = 0f
            binding.btnMenuCreateFamily.setCardBackgroundColor(android.graphics.Color.parseColor("#EDEFF7"))
            binding.btnMenuCreateFamily.foreground = null
            binding.btnMenuCreateFamily.setOnTouchListener(null)
        } else {
            binding.tvMenuFamilyTitle.text = "Buat Keluarga"
            binding.tvMenuFamilyStatus.text = "Belum ada keluarga"
            binding.ivMenuFamilyIcon.setImageResource(R.drawable.ic_menu_family)
            binding.ivMenuFamilyChevron.visibility = View.VISIBLE
            binding.btnMenuCreateFamily.isClickable = true
            binding.btnMenuCreateFamily.isFocusable = true
            binding.btnMenuCreateFamily.cardElevation = resources.displayMetrics.density * 3
            binding.btnMenuCreateFamily.setCardBackgroundColor(android.graphics.Color.WHITE)
            val outValue = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            binding.btnMenuCreateFamily.foreground = androidx.core.content.ContextCompat.getDrawable(this, outValue.resourceId)
            AnimUtils.attachPressAnimation(binding.btnMenuCreateFamily)
        }
    }
}
