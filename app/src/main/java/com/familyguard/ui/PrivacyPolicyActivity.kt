package com.familyguard.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.familyguard.databinding.ActivityPrivacyPolicyBinding

class PrivacyPolicyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrivacyPolicyBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacyPolicyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val isFromSettings = intent.getBooleanExtra(EXTRA_IS_FROM_SETTINGS, false)

        if (isFromSettings) {
            binding.bottomActionContainer.visibility = View.GONE
        }

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnAccept.setOnClickListener {

            setResult(RESULT_OK)
            finish()
        }
    }

    companion object {
        const val EXTRA_IS_FROM_SETTINGS = "extra_is_from_settings"
    }
}