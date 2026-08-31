package com.familyguard.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
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
import com.familyguard.service.GuardService

class ChildHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChildHomeBinding
    private lateinit var memberAdapter: FamilyMemberAdapter
    private var devicesListener: com.google.firebase.database.ValueEventListener? = null
    private var lastSyncedAccessibilityStatus: Boolean? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            LocationHelper.updateCurrentLocation(this)
            startGuardService()

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    showBackgroundLocationDialog()
                }
            }
        } else {
            Toast.makeText(
                this,
                "Izin lokasi ditolak — lokasi HP anak tidak akan terlihat di dashboard orang tua.",
                Toast.LENGTH_LONG
            ).show()
        }
        updateStatusIcons()
    }

    private fun showBackgroundLocationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Izin Lokasi Latar Belakang")
            .setMessage("Agar lokasi anak selalu terpantau walau layar mati atau aplikasi tertutup, pilih 'Izinkan sepanjang waktu' (Allow all the time) pada menu pengaturan lokasi berikutnya.")
            .setPositiveButton("Buka Pengaturan") { _, _ ->

                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Nanti", null)
            .show()
    }

    private fun startGuardService() {
        AppLockPrefs.setGuardEnabled(this, true)
        GuardService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChildHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val code = AppLockPrefs.getFamilyCode(this) ?: "—"
        binding.tvFamilyCode.text = "Kode keluarga: ${formatCode(code)}"
        binding.tvFamilyCode.typeface = Typeface.DEFAULT
        setupFamilyNameHeader(code)

        setupFamilyMembersList()

        binding.btnOpenChildMenu.setOnClickListener {
            startActivity(Intent(this, ChildMenuActivity::class.java))
        }

        FamilyLink.startListening(this) { title, message ->
            showMessageNotification(title, message)

        }

        checkPermissions()
        setupStatusButtons()
        syncInstalledAppsToCloud()
        com.familyguard.receiver.GuardWatchdogReceiver.schedule(this)
        requestIgnoreBatteryOptimization()

        binding.btnResetRoleChild.setOnClickListener {
            com.familyguard.utils.AccountActions.resetRole(this)
        }
        binding.btnChangeFamilyChild.setOnClickListener {
            com.familyguard.utils.AccountActions.changeFamily(this)
        }
        binding.btnLogoutChild.setOnClickListener {
            com.familyguard.utils.AccountActions.logout(this)
        }

        setupAnimations()
    }

    private fun setupAnimations() {
        com.familyguard.utils.AnimUtils.staggerFadeSlideIn(binding.rootContent)
        com.familyguard.utils.AnimUtils.attachPressAnimationRecursively(binding.rootContent)
    }

    override fun onResume() {
        super.onResume()
        updateStatusIcons()
    }

    private fun syncInstalledAppsToCloud() {
        Thread {
            try {
                val locked = AppLockPrefs.getLockedApps(this)
                val blockedNotif = AppLockPrefs.getBlockedNotificationApps(this)
                val apps = com.familyguard.utils.InstalledAppsHelper.getInstalledApps(this).map { app ->
                    app.copy(
                        isLocked = locked.contains(app.packageName),
                        isNotifBlocked = blockedNotif.contains(app.packageName)
                    )
                }
                runOnUiThread {
                    FamilyLink.updateAppList(this, apps)
                }
            } catch (e: Exception) {
                android.util.Log.e("ChildHomeActivity", "Gagal sync app list: ${e.message}", e)
            }
        }.start()
    }

    private fun requestIgnoreBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Izinkan Berjalan di Latar Belakang")
                .setMessage(
                    "Supaya FamilyGuard tidak dimatikan sistem dan perintah dari orang tua " +
                            "selalu bisa diterima, aktifkan 'Izinkan' pada dialog berikutnya.\n\n" +
                            "Kalau HP ini merk itel/Tecno/Infinix, tolong juga buka Pengaturan > " +
                            "Baterai > Manajemen Aplikasi > FamilyGuard, lalu aktifkan 'Autostart' " +
                            "dan set batasan baterai ke 'Tanpa batasan'."
                )
                .setPositiveButton("Lanjutkan") { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (e: Exception) {

                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }
                }
                .setNegativeButton("Nanti", null)
                .show()
        }
    }

    private fun setupFamilyNameHeader(code: String) {
        val localFamilyName = AppLockPrefs.getFamilyName(this)
        if (!localFamilyName.isNullOrBlank()) {
            binding.tvFamilyName.text = "Keluarga: $localFamilyName"
            return
        }

        if (code == "—") return
        com.google.firebase.database.FirebaseDatabase.getInstance().reference
            .child("families").child(code).child("familyName")
            .get()
            .addOnSuccessListener { snapshot ->
                val remoteFamilyName = snapshot.getValue(String::class.java)
                if (!remoteFamilyName.isNullOrBlank()) {
                    binding.tvFamilyName.text = "Keluarga: $remoteFamilyName"
                    AppLockPrefs.saveFamilyName(this, remoteFamilyName)
                }
            }
    }

    private fun setupFamilyMembersList() {
        memberAdapter = FamilyMemberAdapter(myDeviceId = AppLockPrefs.getDeviceId(this))
        binding.rvFamilyMembers.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.rvFamilyMembers.adapter = memberAdapter
        binding.rvFamilyMembers.isNestedScrollingEnabled = false
        com.familyguard.utils.AnimUtils.applySlideInItemAnimator(binding.rvFamilyMembers)

        devicesListener = FamilyLink.observeDevices(this) { devices ->
            memberAdapter.submitList(devices)
        }
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

        binding.btnLocation.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                requestPermissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            } else {

                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    private fun updateStatusIcons() {

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val isAdminActive = dpm.isAdminActive(DeviceAdminReceiver.getComponentName(this))
        setStatus(binding.statusAdmin, binding.btnActivateAdmin, isAdminActive)

        val isAccessibilityActive = isAccessibilityServiceEnabled()
        setStatus(binding.statusAccessibility, binding.btnAccessibility, isAccessibilityActive)

        if (isAccessibilityActive != lastSyncedAccessibilityStatus) {
            lastSyncedAccessibilityStatus = isAccessibilityActive
            com.familyguard.sync.FamilyLink.updateAccessibilityStatus(this, isAccessibilityActive)
        }

        val isOverlayActive = Settings.canDrawOverlays(this)
        setStatus(binding.statusOverlay, binding.btnOverlay, isOverlayActive)

        val isNotifActive = isNotificationServiceEnabled()
        setStatus(binding.statusNotif, binding.btnNotifListener, isNotifActive)

        val isLocationActive = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        setStatus(binding.statusLocation, binding.btnLocation, isLocationActive)
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
            startGuardService()
        } else {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        FamilyLink.stopListening(this)
        devicesListener?.let { FamilyLink.removeDeviceObserver(this, it) }
    }

    private fun showMessageDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle("Pesan: $title")
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