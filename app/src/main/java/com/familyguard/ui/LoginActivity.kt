package com.familyguard.ui

import android.content.Intent
import android.os.Bundle
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

class LoginActivity : BaseActivity() {

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

    private fun routeNext() {
        binding.progressBar.visibility = android.view.View.VISIBLE
        com.familyguard.sync.FamilyLink.fetchUserProfile(this) { found ->
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
            startActivity(Intent(this, target))
            finish()
        }
    }
}

