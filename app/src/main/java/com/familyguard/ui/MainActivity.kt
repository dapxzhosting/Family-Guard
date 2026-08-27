package com.familyguard.ui

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.familyguard.admin.LockManager
import com.familyguard.databinding.ActivityMainBinding
import com.familyguard.model.AppInfo
import com.familyguard.service.GuardService
import com.familyguard.sync.FamilyLink
import com.familyguard.ui.adapter.AppListAdapter
import com.familyguard.utils.AppLockPrefs
import com.familyguard.utils.InstalledAppsHelper
import com.familyguard.utils.LocationHelper
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var lockManager: LockManager
    private lateinit var adapter: AppListAdapter
    private var appList: List<AppInfo> = emptyList()

    private val adminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ -> updateStatusUI() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lockManager = LockManager(this)

        setupRecyclerView()
        setupClickListeners()
        initFcmToken()
        startGuardService()
        LocationHelper.updateCurrentLocation(this)
    }

    override fun onResume() {
        super.onResume()
        updateStatusUI()
        loadInstalledApps()
    }

    private fun setupRecyclerView() {
        adapter = AppListAdapter(
            onLockToggle = { appInfo, locked ->
                if (locked) AppLockPrefs.addLockedApp(this, appInfo.packageName)
                else AppLockPrefs.removeLockedApp(this, appInfo.packageName)
                Toast.makeText(
                    this,
                    if (locked) "${appInfo.appName} dikunci" else "${appInfo.appName} dibuka",
                    Toast.LENGTH_SHORT
                ).show()
            },
            onNotifToggle = { appInfo, blocked ->
                if (blocked) AppLockPrefs.addBlockedNotifApp(this, appInfo.packageName)
                else AppLockPrefs.removeBlockedNotifApp(this, appInfo.packageName)
                Toast.makeText(
                    this,
                    if (blocked) "Notif ${appInfo.appName} diblokir" else "Notif ${appInfo.appName} diaktifkan",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = adapter
    }

    private fun setupClickListeners() {
        // Tombol Lock HP
        binding.btnLockScreen.setOnClickListener {
            if (lockManager.isAdminActive) {
                lockManager.lockScreen()
            } else {
                showAdminRequiredDialog()
            }
        }

        // Tombol Aktifkan Device Admin
        binding.btnActivateAdmin.setOnClickListener {
            val intent = lockManager.getActivationIntent()
            adminLauncher.launch(intent)
        }

        // Tombol Aktifkan Accessibility (App Lock)
        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Aktifkan 'FamilyGuard' di daftar Accessibility Services", Toast.LENGTH_LONG).show()
        }

        // Tombol Aktifkan Notification Listener
        binding.btnNotifListener.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Toast.makeText(this, "Aktifkan 'FamilyGuard' di Notification Access", Toast.LENGTH_LONG).show()
        }

        // Tombol Kirim Pesan (lokal, simulasi)
        binding.btnSendMessage.setOnClickListener {
            showSendMessageDialog()
        }

        // Tab toggle: semua app vs terkunci
        binding.chipAll.setOnClickListener { filterApps("all") }
        binding.chipLocked.setOnClickListener { filterApps("locked") }
        binding.chipNotifBlocked.setOnClickListener { filterApps("notif") }
    }

    private fun updateStatusUI() {
        val adminOk = lockManager.isAdminActive
        val accessOk = isAccessibilityEnabled()
        val notifOk = isNotificationListenerEnabled()

        binding.statusAdmin.setStatus(adminOk)
        binding.statusAccessibility.setStatus(accessOk)
        binding.statusNotif.setStatus(notifOk)

        binding.btnActivateAdmin.visibility = if (adminOk) View.GONE else View.VISIBLE
        binding.btnAccessibility.visibility = if (accessOk) View.GONE else View.VISIBLE
        binding.btnNotifListener.visibility = if (notifOk) View.GONE else View.VISIBLE

        binding.btnLockScreen.isEnabled = adminOk
    }

    private fun loadInstalledApps() {
        binding.progressBar.visibility = View.VISIBLE
        Thread {
            val locked = AppLockPrefs.getLockedApps(this)
            val blockedNotif = AppLockPrefs.getBlockedNotificationApps(this)
            appList = InstalledAppsHelper.getInstalledApps(this).map { app ->
                app.copy(
                    isLocked = locked.contains(app.packageName),
                    isNotifBlocked = blockedNotif.contains(app.packageName)
                )
            }
            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                adapter.submitList(appList)
                // Kirim daftar aplikasi ke cloud agar orang tua bisa lihat
                FamilyLink.updateAppList(this, appList)
            }
        }.start()
    }

    private fun filterApps(filter: String) {
        val filtered = when (filter) {
            "locked" -> appList.filter { it.isLocked }
            "notif" -> appList.filter { it.isNotifBlocked }
            else -> appList
        }
        adapter.submitList(filtered)
    }

    private fun showSendMessageDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Ketik pesan untuk anak..."
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("Kirim Pesan ke HP Ini")
            .setView(input)
            .setPositiveButton("Kirim") { _, _ ->
                val msg = input.text.toString()
                if (msg.isNotBlank()) {
                    // Local notification (tanpa internet)
                    showLocalMessage("Pesan dari Orang Tua", msg)
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showLocalMessage(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle("Pesan: $title")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showAdminRequiredDialog() {
        AlertDialog.Builder(this)
            .setTitle("Device Admin Diperlukan")
            .setMessage("Untuk mengunci layar, aktifkan Device Admin FamilyGuard terlebih dahulu.")
            .setPositiveButton("Aktifkan") { _, _ ->
                adminLauncher.launch(lockManager.getActivationIntent())
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun initFcmToken() {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            AppLockPrefs.saveFcmToken(this, token)
            // Note: binding.tvFcmToken might be missing in some layouts, safely handle it
            try {
                // binding.tvFcmToken.text = "Token: ${token.take(20)}..."
            } catch (e: Exception) {}
        }
    }

    private fun startGuardService() {
        AppLockPrefs.setGuardEnabled(this, true)
        GuardService.start(this)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val serviceName = "${packageName}/com.familyguard.service.AppLockAccessibilityService"
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            enabled?.contains(serviceName) == true
        } catch (e: Exception) { false }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )
        return flat?.contains(packageName) == true
    }
}

// Extension untuk update status indicator
private fun android.widget.ImageView.setStatus(active: Boolean) {
    setImageResource(
        if (active) android.R.drawable.presence_online
        else android.R.drawable.presence_offline
    )
}
