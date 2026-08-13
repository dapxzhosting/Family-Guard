package com.familyguard.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.familyguard.databinding.ActivityParentDashboardBinding
import com.familyguard.sync.FamilyDevice
import com.familyguard.sync.FamilyLink
import com.familyguard.utils.AppLockPrefs
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dashboard orang tua — mengirim perintah real-time ke HP anak.
 *
 * Semua perintah dikirim lewat FamilyLink → Firebase RTDB.
 * HP anak langsung eksekusi tanpa delay.
 */
class ParentDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityParentDashboardBinding
    private var deviceObserver: ValueEventListener? = null
    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParentDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val code = AppLockPrefs.getFamilyCode(this) ?: "—"
        binding.tvFamilyCode.text = "Kode keluarga: ${formatCode(code)}"

        setupButtons()
        observeConnectedDevices()
    }

    // ─── TOMBOL PERINTAH ─────────────────────────────────────────

    private fun setupButtons() {

        // Kunci layar HP anak
        binding.btnLockScreen.setOnClickListener {
            confirmAction("Kunci layar HP anak sekarang?") {
                FamilyLink.sendLockScreen(this)
                toast("Perintah kunci layar dikirim ✓")
            }
        }

        // Kirim pesan ke HP anak
        binding.btnSendMessage.setOnClickListener {
            showSendMessageDialog()
        }
    }

    private fun showSendMessageDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Ketik pesan..."
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("Kirim Pesan ke HP Anak")
            .setView(input)
            .setPositiveButton("Kirim") { _, _ ->
                val msg = input.text.toString().trim()
                if (msg.isNotBlank()) {
                    FamilyLink.sendMessage(this, "Pesan dari Orang Tua", msg)
                    toast("Pesan dikirim ✓")
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ─── OBSERVE PERANGKAT TERHUBUNG ──────────────────────────────

    private fun observeConnectedDevices() {
        deviceObserver = FamilyLink.observeDevices(this) { devices ->
            updateDeviceList(devices)
        }
    }

    private fun updateDeviceList(devices: List<FamilyDevice>) {
        val sb = StringBuilder()
        val children = devices.filter { it.role == AppLockPrefs.ROLE_CHILD }

        if (children.isEmpty()) {
            binding.tvDeviceStatus.text = "⏳ Belum ada HP anak yang terhubung.\nBagikan kode keluarga ke HP anak."
            setControlsEnabled(false)
            return
        }

        children.forEach { device ->
            val status = if (device.online) "🟢 Online" else "⚫ Offline"
            val lastSeen = if (!device.online) " (${sdf.format(Date(device.lastSeen))})" else ""
            sb.appendLine("HP Anak $status$lastSeen")
        }
        binding.tvDeviceStatus.text = sb.toString().trim()
        setControlsEnabled(true)
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding.btnLockScreen.isEnabled = enabled
        binding.btnSendMessage.isEnabled = enabled
    }

    // ─── HELPERS ─────────────────────────────────────────────────

    private fun confirmAction(message: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("Ya") { _, _ -> onConfirm() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun formatCode(code: String) =
        if (code.length == 6) "${code.take(3)}-${code.drop(3)}" else code

    override fun onDestroy() {
        super.onDestroy()
        deviceObserver?.let { FamilyLink.removeDeviceObserver(this, it) }
    }
}
