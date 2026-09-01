package com.familyguard.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.familyguard.databinding.ActivityNameInputBinding
import com.familyguard.utils.AppLockPrefs
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.userProfileChangeRequest

class NameInputActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNameInputBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNameInputBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnNameInputBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        FirebaseAuth.getInstance().currentUser?.displayName?.let {
            binding.etName.setText(it)
        }

        binding.btnContinue.setOnClickListener {
            val name = binding.etName.text.toString().trim()
            if (name.isEmpty()) {
                binding.etName.error = "Nama tidak boleh kosong"
                return@setOnClickListener
            }

            AppLockPrefs.saveUserName(this, name)
            com.familyguard.sync.FamilyLink.saveUserProfile(this)

            val user = FirebaseAuth.getInstance().currentUser
            user?.updateProfile(userProfileChangeRequest { displayName = name })

            startActivity(Intent(this, RoleSelectionActivity::class.java))
            finish()
        }
    }
}