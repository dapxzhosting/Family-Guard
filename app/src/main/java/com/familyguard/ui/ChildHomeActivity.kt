package com.familyguard.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.familyguard.R
import com.familyguard.databinding.ActivityChildHomeBinding
import com.familyguard.sync.FamilyLink
import com.familyguard.utils.AppLockPrefs
import com.familyguard.utils.LocationHelper

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import com.familyguard.admin.DeviceAdminReceiver
import com.familyguard.service.AppLockAccessibilityService

class ChildHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChildHomeBinding

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            LocationHelper.updateCurrentLocation(this)
        }
        updateStatusIcons()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChildHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val code = AppLockPrefs.getFamilyCode(this) ?: "—"
        binding.tvFamilyCode.text = "Kode keluarga: ${formatCode(code)}"

        FamilyLink.startListening(this) { title, message ->
            showMessageNotification(title, message)
            // showMessageDialog(title, message) // Dihapus karena sudah ditangani GuardService secara global
        }

        checkPermissions()
        setupStatusButtons()
        
        binding.root.setOnLongClickListener {
            AppLockPrefs.saveRole(this, "")
            AppLockPrefs.saveFamilyCode(this, "")
            Toast.makeText(this, "Data reset. App akan tertutup.", Toast.LENGTH_SHORT).show()
            binding.root.postDelayed({
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 1000)
            true
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatusIcons()
    }

    private fun setupStatusButtons() {
        binding.btnActivateAdmin.setOnClickListener {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, DeviceAdminReceiver.getComponentName(this@ChildHomeActivity))
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Dibutuhkan untuk mengunci perangkat dari jauh.")
            }
            startActivity(intent)
        }

        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnOverlay.setOnClickListener {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        }

        binding.btnNotifListener.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.btnSetPinLocal.setOnClickListener {
            startActivity(Intent(this, LockScreenActivity::class.java))
        }
    }

    private fun updateStatusIcons() {
        // 1. Device Admin
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val isAdminActive = dpm.isAdminActive(DeviceAdminReceiver.getComponentName(this))
        setStatus(binding.statusAdmin, binding.btnActivateAdmin, isAdminActive)

        // 2. Accessibility
        val isAccessibilityActive = isAccessibilityServiceEnabled()
        setStatus(binding.statusAccessibility, binding.btnAccessibility, isAccessibilityActive)

        // 3. Overlay
        val isOverlayActive = Settings.canDrawOverlays(this)
        setStatus(binding.statusOverlay, binding.btnOverlay, isOverlayActive)

        // 4. Notification Listener
        val isNotifActive = isNotificationServiceEnabled()
        setStatus(binding.statusNotif, binding.btnNotifListener, isNotifActive)

        // PIN Status
        if (AppLockPrefs.hasPin(this)) {
            binding.tvPinStatus.text = "PIN Aktif ✓"
            binding.tvPinStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            binding.btnSetPinLocal.text = "Ubah PIN"
        } else {
            binding.tvPinStatus.text = "PIN belum diset"
            binding.tvPinStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            binding.btnSetPinLocal.text = "Atur PIN (Lokal)"
        }
    }

    private fun setStatus(icon: android.widget.ImageView, button: android.widget.Button, active: Boolean) {
        if (active) {
            icon.setImageResource(R.drawable.ic_status_ok)
            button.visibility = View.GONE
        } else {
            icon.setImageResource(R.drawable.ic_status_error)
            button.visibility = View.VISIBLE
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "${packageName}/${AppLockAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServices?.contains(expected) == true
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(pkgName) == true
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            LocationHelper.updateCurrentLocation(this)
        } else {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        FamilyLink.stopListening(this)
    }

    private fun showMessageDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle("📩 $title")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showMessageNotification(title: String, body: String) {
        val channelId = "family_message_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannel(
            NotificationChannel(channelId, "Pesan Keluarga", NotificationManager.IMPORTANCE_HIGH)
                .apply { enableVibration(true) }
        )

        val notif = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_family)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        nm.notify(System.currentTimeMillis().toInt(), notif)
    }

    private fun formatCode(code: String) =
        if (code.length == 6) "${code.take(3)}-${code.drop(3)}" else code
}
