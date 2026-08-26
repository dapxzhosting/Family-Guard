package com.familyguard.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.familyguard.databinding.ActivityLoginBinding
import com.familyguard.utils.AppLockPrefs
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

/**
 * Layar paling pertama yang dibuka user (launcher activity, gantiin posisi
 * RoleSelectionActivity yang lama). Alurnya:
 *
 * LoginActivity (Google Sign-In)
 *   -> NameInputActivity (isi nama, cuma sekali di login pertama)
 *     -> RoleSelectionActivity (pilih Orang Tua / Anak, alur lama tidak berubah)
 *       -> [khusus Orang Tua] FamilyNameActivity (isi nama keluarga)
 *         -> FamilyCodeActivity (generate/masukkan kode, alur lama tidak berubah)
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var auth: FirebaseAuth

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            firebaseAuthWithGoogle(account.idToken)
        } catch (e: ApiException) {
            binding.progressBar.visibility = android.view.View.GONE
            Toast.makeText(this, "Login dibatalkan atau gagal (${e.statusCode})", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        auth = FirebaseAuth.getInstance()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Sudah pernah login sebelumnya -> lewati layar ini
        if (auth.currentUser != null) {
            routeNext()
            return
        }

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(com.familyguard.R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        binding.btnGoogleSignIn.setOnClickListener {
            binding.progressBar.visibility = android.view.View.VISIBLE
            signInLauncher.launch(googleSignInClient.signInIntent)
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String?) {
        if (idToken == null) {
            binding.progressBar.visibility = android.view.View.GONE
            Toast.makeText(this, "Gagal mendapatkan token Google", Toast.LENGTH_SHORT).show()
            return
        }
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnSuccessListener {
                routeNext()
            }
            .addOnFailureListener { e ->
                binding.progressBar.visibility = android.view.View.GONE
                Toast.makeText(this, "Login gagal: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    /**
     * Setelah login sukses: cek dulu apakah akun ini sudah pernah setup di HP
     * lain (nama/role/kode keluarga tersimpan di Firebase). Kalau ada, isi
     * otomatis & lompat langsung ke home -- tidak perlu isi ulang dari nol.
     */
    private fun routeNext() {
        binding.progressBar.visibility = android.view.View.VISIBLE
        com.familyguard.sync.FamilyLink.fetchUserProfile(this) { found ->
            val role = AppLockPrefs.getRole(this)
            val code = AppLockPrefs.getFamilyCode(this)
            val name = AppLockPrefs.getUserName(this)

            // Kalau fetch profil dari Firebase gagal TAPI ternyata memang belum ada
            // apa-apa tersimpan lokal juga -> kemungkinan besar ini akun yang benar-benar
            // baru pertama kali pakai app, arahkan ke NameInputActivity seperti biasa
            // tanpa perlu kasih tahu apa-apa (ini kasus normal, bukan bug).
            //
            // Tapi kalau HP ini SUDAH pernah setup sebelumnya di sesi yang sama (name
            // sudah ada dari onCreate awal misalnya) dan tiba-tiba butuh isi ulang, atau
            // developer lagi nge-test cross-device dan yakin akun ini sudah pernah
            // setup di HP lain -- Toast ini bantu bedain "memang belum pernah setup"
            // vs "gagal fetch" tanpa perlu buka Logcat.
            if (!found && name.isNullOrBlank()) {
                Log.d(
                    "LoginActivity",
                    "Tidak ada profil ditemukan untuk akun ini di Firebase -- kalau " +
                            "seharusnya akun ini SUDAH pernah setup di HP lain, cek Firebase " +
                            "Realtime Database Rules untuk path /users/{uid} (lihat log tag FamilyLink)."
                )
            }

            val target = when {
                name.isNullOrBlank() -> NameInputActivity::class.java
                role.isNullOrBlank() -> RoleSelectionActivity::class.java
                code.isNullOrBlank() && role == AppLockPrefs.ROLE_PARENT -> FamilyNameActivity::class.java
                code.isNullOrBlank() -> FamilyCodeActivity::class.java
                role == AppLockPrefs.ROLE_PARENT -> ParentDashboardActivity::class.java
                else -> ChildHomeActivity::class.java
            }
            startActivity(Intent(this, target))
            finish()
        }
    }
}