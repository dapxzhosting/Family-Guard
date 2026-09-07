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

class FamilyCodeActivity : BaseActivity() {

    companion object {
        const val EXTRA_IS_CHANGE_FAMILY = "extra_is_change_family"
    }

    private lateinit var binding: ActivityFamilyCodeBinding
    private var generatedCode: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isChangeFamily = intent.getBooleanExtra(EXTRA_IS_CHANGE_FAMILY, false)

        if (!isChangeFamily && !AppLockPrefs.getFamilyCode(this).isNullOrBlank()) {
            goToHome()
            return
        }

        binding = ActivityFamilyCodeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val role = AppLockPrefs.getRole(this)
        if (role == AppLockPrefs.ROLE_PARENT) {
            binding.headerFamilyCode.background =
                androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_parent_hero)
            window.statusBarColor =
                androidx.core.content.ContextCompat.getColor(this, R.color.dash_primary)
        } else {
            window.statusBarColor =
                androidx.core.content.ContextCompat.getColor(this, R.color.dash_child_primary)
        }
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

        binding.btnBackToDashboardChild.visibility = View.VISIBLE
        if (intent.getBooleanExtra(EXTRA_IS_CHANGE_FAMILY, false)) {
            binding.btnBackToDashboardChild.setOnClickListener {
                startActivity(
                    Intent(this, ChildMenuActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                )
                finish()
            }
        } else {
            binding.btnBackToDashboardChild.setOnClickListener {
                onBackPressedDispatcher.onBackPressed()
            }
        }

        binding.etFamilyCode.addTextChangedListener(FamilyCodeTextWatcher(binding.etFamilyCode))

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
        if (intent.getBooleanExtra(EXTRA_IS_CHANGE_FAMILY, false)) {
            com.familyguard.utils.AccountActions.leaveCurrentFamily(this) {
                commitNewFamilyCode(code)
            }
        } else {
            commitNewFamilyCode(code)
        }
    }

    private fun commitNewFamilyCode(code: String) {
        AppLockPrefs.saveFamilyCode(this, code)
        FamilyLink.registerDevice(this)
        FamilyLink.saveUserProfile(this)

        if (AppLockPrefs.getRole(this) == AppLockPrefs.ROLE_PARENT) {
            val familyName = AppLockPrefs.getFamilyName(this)
            val userName = AppLockPrefs.getUserName(this)
            val db = com.google.firebase.database.FirebaseDatabase.getInstance().reference
            val updates = mutableMapOf<String, Any>()
            if (!familyName.isNullOrBlank()) updates["familyName"] = familyName
            if (!userName.isNullOrBlank()) updates["parentName"] = userName
            if (updates.isNotEmpty()) {
                db.child("families").child(code).updateChildren(updates)
            }
        }

        goToHome()
    }

    private fun goToHome() {
        val role = AppLockPrefs.getRole(this)
        val target = if (role == AppLockPrefs.ROLE_PARENT) {
            ParentMenuActivity::class.java
        } else {
            ChildDashboardActivity::class.java
        }
        startActivity(
            Intent(this, target).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        )
        finish()
    }

    private fun generateCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    private fun formatCode(code: String) = "${code.take(3)}-${code.drop(3)}"
}

class FamilyCodeTextWatcher(
    private val editText: android.widget.EditText
) : android.text.TextWatcher {

    private var isEditing = false

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

    override fun afterTextChanged(s: android.text.Editable?) {
        if (isEditing || s == null) return
        isEditing = true

        val raw = s.toString().uppercase().filter { it.isLetterOrDigit() }.take(6)

        val formatted = if (raw.length > 3) {
            "${raw.substring(0, 3)}-${raw.substring(3)}"
        } else {
            raw
        }

        if (formatted != s.toString()) {
            editText.setText(formatted)
            editText.setSelection(formatted.length)
        }

        isEditing = false
    }
}

