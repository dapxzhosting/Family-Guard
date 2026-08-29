package com.familyguard.ui

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.familyguard.databinding.ActivitySettingsBinding
import com.familyguard.sync.FamilyLink
import com.familyguard.utils.AnimUtils
import com.familyguard.utils.AppLockPrefs
import com.google.firebase.auth.FirebaseAuth

/**
 * Halaman Pengaturan (khusus Orang Tua, dibuka dari kartu "Pengaturan" di
 * DashboardActivity). Isinya:
 *
 * Akun:
 * - Nama   -> bisa diklik untuk diedit (dialog input), tersinkron ke Firebase
 *             lewat FamilyLink.updateUserName() -- lihat rowSettingsName.
 * - Email  -> info doang (akun Google yang lagi login, tidak bisa diubah
 *             dari sini karena terikat ke akun Google).
 *
 * Lainnya:
 * - Kebijakan Privasi & Syarat -> buka PrivacyPolicyActivity (mode "hanya
 *   dibaca", sama seperti shortcut yang ada di DashboardActivity).
 * - Tentang Aplikasi -> info versi aplikasi (dari PackageInfo), tidak bisa
 *   diklik.
 *
 * Transisi masuk/keluar activity ini pakai slide_up_in/slide_down_out
 * (bukan animasi default fade), dipasang lewat overridePendingTransition()
 * -- lihat juga DashboardActivity untuk sisi pemanggilnya.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        refreshName()
        binding.tvSettingsEmail.text =
            FirebaseAuth.getInstance().currentUser?.email?.takeIf { it.isNotBlank() } ?: "-"
        binding.tvSettingsVersion.text = getAppVersionLabel()

        binding.btnSettingsBack.setOnClickListener {
            closeWithSlideDown()
        }

        binding.rowSettingsName.setOnClickListener {
            showEditNameDialog()
        }

        binding.rowSettingsPrivacy.setOnClickListener {
            val intent = Intent(this, PrivacyPolicyActivity::class.java).apply {
                putExtra(PrivacyPolicyActivity.EXTRA_IS_FROM_SETTINGS, true)
            }
            startActivity(intent)
        }

        AnimUtils.staggerFadeSlideIn(binding.rootSettingsContent)
        AnimUtils.attachPressAnimationRecursively(binding.rootSettingsContent)
    }

    private fun refreshName() {
        binding.tvSettingsName.text =
            AppLockPrefs.getUserName(this)?.takeIf { it.isNotBlank() } ?: "-"
    }

    /** Dialog kecil buat ganti nama -- disimpan lokal + disinkron ke Firebase
     *  (profil akun & record device kalau sudah tergabung keluarga). */
    private fun showEditNameDialog() {
        val input = EditText(this).apply {
            setText(AppLockPrefs.getUserName(this@SettingsActivity) ?: "")
            setSelection(text.length)
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Ubah Nama")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, "Nama tidak boleh kosong", Toast.LENGTH_SHORT).show()
                } else {
                    FamilyLink.updateUserName(this, newName) {
                        runOnUiThread {
                            refreshName()
                            Toast.makeText(this, "Nama berhasil diubah", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun getAppVersionLabel(): String {
        return try {
            val info = packageManager.getPackageInfo(packageName, 0)
            "v${info.versionName}"
        } catch (e: Exception) {
            "-"
        }
    }

    /** Tombol back sistem/gesture juga pakai animasi slide-down yang sama,
     *  bukan animasi default, biar konsisten dengan tombol back di header. */
    override fun onBackPressed() {
        closeWithSlideDown()
    }

    private fun closeWithSlideDown() {
        super.onBackPressed()
        @Suppress("DEPRECATION")
        overridePendingTransition(com.familyguard.R.anim.stay_undim, com.familyguard.R.anim.slide_down_out)
    }
}
