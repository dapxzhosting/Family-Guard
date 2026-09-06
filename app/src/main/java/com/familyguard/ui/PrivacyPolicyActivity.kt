package com.familyguard.ui

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import com.familyguard.R
import com.familyguard.utils.AppLockPrefs

class PrivacyPolicyActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val role = AppLockPrefs.getRole(this)
        val layoutRes = if (role == AppLockPrefs.ROLE_CHILD) {
            R.layout.activity_privacy_policy_child
        } else {
            R.layout.activity_privacy_policy
        }
        setContentView(layoutRes)

        if (role == AppLockPrefs.ROLE_CHILD) {
            window.statusBarColor = androidx.core.content.ContextCompat.getColor(this, R.color.dash_child_primary)
        }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.btnAccept).setOnClickListener {
            setResult(RESULT_OK)
            finish()
        }
    }

    companion object {
        const val EXTRA_IS_FROM_SETTINGS = "extra_is_from_settings"
    }
}

