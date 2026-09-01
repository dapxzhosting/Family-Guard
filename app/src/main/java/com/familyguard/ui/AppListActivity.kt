package com.familyguard.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.familyguard.databinding.ActivityListAppBinding
import com.familyguard.model.AppInfo
import com.familyguard.sync.FamilyLink
import com.familyguard.ui.adapter.AppListAdapter
import com.familyguard.utils.AppLockPrefs
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class AppListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListAppBinding
    private lateinit var appAdapter: AppListAdapter

    private var deviceId: String? = null
    private var deviceListener: ValueEventListener? = null

    private var fullAppList: List<AppInfo> = emptyList()

    private var childHasPin: Boolean = false
    private var pinStatusListener: ValueEventListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListAppBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        binding.tvAppListChildName.text =
            intent.getStringExtra(EXTRA_CHILD_NAME)?.takeIf { it.isNotBlank() } ?: "HP Anak"

        setupList()
        setupSearch()
        setupBulkActionButtons()

        binding.btnAppListBack.setOnClickListener { onBackPressed() }

        if (deviceId == null) {
            binding.tvAppListEmpty.visibility = View.VISIBLE
            binding.tvAppListEmpty.text = "Tidak ada HP anak yang dipilih"
        } else {
            observeAppList(deviceId!!)
            observePinStatus(deviceId!!)
        }
    }

    private fun setupList() {
        appAdapter = AppListAdapter(
            onLockToggle = { appInfo, locked ->
                val target = deviceId ?: return@AppListAdapter
                if (locked && !requirePinSet()) return@AppListAdapter
                FamilyLink.sendLockApp(this, appInfo.packageName, locked, target)
            },
            onNotifToggle = { appInfo, blocked ->
                val target = deviceId ?: return@AppListAdapter
                FamilyLink.sendBlockNotif(this, appInfo.packageName, blocked, target)
            }
        )
        binding.rvAllApps.layoutManager = LinearLayoutManager(this)
        binding.rvAllApps.adapter = appAdapter
    }

    private fun setupSearch() {
        binding.etAppSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })
    }

    private fun applyFilter(query: String) {
        val filtered = if (query.isBlank()) {
            fullAppList
        } else {
            fullAppList.filter { it.appName.contains(query, ignoreCase = true) }
        }
        appAdapter.submitList(filtered)
    }

    private fun setupBulkActionButtons() {
        binding.btnLockAll.setOnClickListener {
            if (!requirePinSet()) return@setOnClickListener
            confirmBulkAction("Kunci SEMUA aplikasi di HP anak?") {
                fullAppList.forEach { FamilyLink.sendLockApp(this, it.packageName, true, deviceId!!) }
                toast("Perintah kunci semua aplikasi dikirim")
            }
        }
        binding.btnUnlockAll.setOnClickListener {
            confirmBulkAction("Buka kunci SEMUA aplikasi di HP anak?") {
                fullAppList.forEach { FamilyLink.sendLockApp(this, it.packageName, false, deviceId!!) }
                toast("Perintah buka semua aplikasi dikirim")
            }
        }
        binding.btnBlockNotifAll.setOnClickListener {
            confirmBulkAction("Blokir notifikasi SEMUA aplikasi di HP anak?") {
                fullAppList.forEach { FamilyLink.sendBlockNotif(this, it.packageName, true, deviceId!!) }
                toast("Perintah blokir semua notifikasi dikirim")
            }
        }
        binding.btnAllowNotifAll.setOnClickListener {
            confirmBulkAction("Izinkan notifikasi SEMUA aplikasi di HP anak?") {
                fullAppList.forEach { FamilyLink.sendBlockNotif(this, it.packageName, false, deviceId!!) }
                toast("Perintah izinkan semua notifikasi dikirim")
            }
        }
    }

    private fun confirmBulkAction(message: String, onConfirm: () -> Unit) {
        if (deviceId == null || fullAppList.isEmpty()) {
            toast("Belum ada daftar aplikasi untuk diproses")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Konfirmasi")
            .setMessage(message)
            .setPositiveButton("Ya, Lanjutkan") { _, _ -> onConfirm() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun observeAppList(id: String) {
        val code = AppLockPrefs.getFamilyCode(this) ?: return
        val deviceRef = FirebaseDatabase.getInstance().reference
            .child("families").child(code).child("devices").child(id)

        deviceListener = object : ValueEventListener {
            override fun onDataChange(node: DataSnapshot) {
                val appListData = node.child("appList").children.mapNotNull { appSnap ->
                    val pkg = appSnap.child("packageName").getValue(String::class.java) ?: return@mapNotNull null
                    val name = appSnap.child("appName").getValue(String::class.java) ?: "App"
                    val locked = appSnap.child("isLocked").getValue(Boolean::class.java) ?: false
                    val notifBlocked = appSnap.child("isNotifBlocked").getValue(Boolean::class.java) ?: false
                    val iconB64 = appSnap.child("icon").getValue(String::class.java)?.takeIf { it.isNotEmpty() }
                    AppInfo(pkg, name, null, locked, notifBlocked, iconB64)
                }.sortedBy { it.appName.lowercase() }

                fullAppList = appListData
                binding.tvAppListEmpty.visibility = if (appListData.isEmpty()) View.VISIBLE else View.GONE
                applyFilter(binding.etAppSearch.text?.toString().orEmpty())
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        deviceRef.addValueEventListener(deviceListener!!)
    }

    private fun observePinStatus(id: String) {
        val code = AppLockPrefs.getFamilyCode(this) ?: return
        val pinRef = FirebaseDatabase.getInstance().reference
            .child("families").child(code).child("devices").child(id).child("hasPin")

        pinStatusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                childHasPin = snapshot.getValue(Boolean::class.java) ?: false
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        pinRef.addValueEventListener(pinStatusListener!!)
    }

    private fun requirePinSet(): Boolean {
        if (childHasPin) return true
        AlertDialog.Builder(this)
            .setTitle("PIN Belum Diset")
            .setMessage(
                "HP anak ini belum punya PIN. Kunci aplikasi butuh PIN supaya " +
                        "anak bisa membuka kuncinya sendiri -- atur PIN dulu sebelum " +
                        "mengunci aplikasi."
            )
            .setPositiveButton("Atur PIN Sekarang") { _, _ -> showSetPinDialog() }
            .setNegativeButton("Batal", null)
            .show()
        return false
    }

    private fun showSetPinDialog() {
        val target = deviceId ?: return
        val code = AppLockPrefs.getFamilyCode(this) ?: return

        FirebaseDatabase.getInstance().reference
            .child("families").child(code).child("devices").child(target).child("currentPin")
            .get()
            .addOnSuccessListener { snapshot ->
                val currentPin = snapshot.getValue(String::class.java)?.takeIf { it.isNotBlank() }
                showSetPinDialogInternal(target, currentPin)
            }
            .addOnFailureListener {
                showSetPinDialogInternal(target, null)
            }
    }

    private fun showSetPinDialogInternal(target: String, currentPin: String?) {
        val input = android.widget.EditText(this).apply {
            hint = "Masukkan 4 digit PIN baru"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            setPadding(48, 32, 48, 16)
        }
        val message = if (currentPin != null) {
            "PIN saat ini: $currentPin\n\nPIN baru akan menggantikan PIN ini dan digunakan anak untuk membuka aplikasi yang dikunci."
        } else {
            "PIN ini akan digunakan anak untuk membuka aplikasi yang dikunci."
        }
        AlertDialog.Builder(this)
            .setTitle("Atur PIN HP Anak")
            .setMessage(message)
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.length == 4) {
                    FamilyLink.sendSetPin(this, pin, target)
                    toast("Perintah atur PIN dikirim")
                } else {
                    toast("PIN harus 4 digit!")
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        val code = AppLockPrefs.getFamilyCode(this)
        if (code != null && deviceId != null) {
            val devicesRef = FirebaseDatabase.getInstance().reference
                .child("families").child(code).child("devices").child(deviceId!!)
            deviceListener?.let { devicesRef.removeEventListener(it) }
            pinStatusListener?.let { devicesRef.child("hasPin").removeEventListener(it) }
        }
    }

    companion object {
        const val EXTRA_DEVICE_ID = "extra_device_id"
        const val EXTRA_CHILD_NAME = "extra_child_name"
    }
}
