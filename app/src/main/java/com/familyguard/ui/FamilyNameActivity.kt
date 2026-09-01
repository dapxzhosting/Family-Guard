package com.familyguard.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.familyguard.databinding.ActivityFamilyNameBinding
import com.familyguard.utils.AppLockPrefs

class FamilyNameActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFamilyNameBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!AppLockPrefs.getFamilyName(this).isNullOrBlank()) {
            goToFamilyCode()
            return
        }

        binding = ActivityFamilyNameBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnFamilyNameBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.btnContinue.setOnClickListener {
            val name = binding.etFamilyName.text.toString().trim()
            if (name.isEmpty()) {
                binding.etFamilyName.error = "Nama keluarga tidak boleh kosong"
                return@setOnClickListener
            }
            AppLockPrefs.saveFamilyName(this, name)
            goToFamilyCode()
        }
    }

    private fun goToFamilyCode() {
        startActivity(Intent(this, FamilyCodeActivity::class.java))
        finish()
    }
}