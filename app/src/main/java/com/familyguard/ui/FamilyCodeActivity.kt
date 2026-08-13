package com.familyguard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.familyguard.databinding.ActivityFamilyCodeBinding
import com.familyguard.sync.FamilyLink
import com.familyguard.utils.AppLockPrefs
import kotlin.random.Random

class FamilyCodeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFamilyCodeBinding
    private var generatedCode: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (AppLockPrefs.getFamilyCode(this) != null) {
            goToHome()
            return
        }

        binding = ActivityFamilyCodeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val role = AppLockPrefs.getRole(this)
        if (role == AppLockPrefs.ROLE_PARENT) showParentUI() else showChildUI()
    }

    private fun showParentUI() {
        binding.cardParent.visibility = View.VISIBLE
        binding.cardChild.visibility = View.GONE

        generatedCode = generateCode()
        binding.tvFamilyCode.text = formatCode(generatedCode)

        binding.btnCopyCode.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("FamilyGuard Code", generatedCode))
            Toast.makeText(this, "Kode disalin!", Toast.LENGTH_SHORT).show()
        }

        binding.btnShareCode.setOnClickListener {
            val shareText = "Pakai kode ini untuk menghubungkan HP-mu ke FamilyGuard: $generatedCode"
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareText)
                },
                "Bagikan kode keluarga"
            ))
        }

        binding.btnParentDone.setOnClickListener {
            saveCodeAndContinue(generatedCode)
        }
    }

    private fun showChildUI() {
        binding.cardParent.visibility = View.GONE
        binding.cardChild.visibility = View.VISIBLE

        binding.btnConnect.setOnClickListener {
            val inputCode = binding.etFamilyCode.text.toString()
                .trim().replace("-", "").uppercase()

            if (inputCode.length != 6) {
                binding.etFamilyCode.error = "Kode harus 6 karakter"
                return@setOnClickListener
            }
            saveCodeAndContinue(inputCode)
        }
    }

    private fun saveCodeAndContinue(code: String) {
        AppLockPrefs.saveFamilyCode(this, code)
        FamilyLink.registerDevice(this)
        goToHome()
    }

    private fun goToHome() {
        val role = AppLockPrefs.getRole(this)
        val target = if (role == AppLockPrefs.ROLE_PARENT) {
            ParentDashboardActivity::class.java
        } else {
            ChildHomeActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }

    private fun generateCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    private fun formatCode(code: String) = "${code.take(3)}-${code.drop(3)}"
}