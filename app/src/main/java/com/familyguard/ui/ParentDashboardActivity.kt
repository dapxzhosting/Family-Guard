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
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
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
    private lateinit var appAdapter: com.familyguard.ui.adapter.AppListAdapter
    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Daftar HP anak yang terhubung & yang lagi dipilih untuk dikontrol/dilihat.
    // Sebelumnya dashboard cuma nampilin/ngontrol device anak PERTAMA yang ketemu --
    // sekarang bisa 2-10 anak, dipilih lewat spinnerChildSelector.
    private var childDevices: List<FamilyDevice> = emptyList()
    private var selectedChildId: String? = null
    private var appListListener: ValueEventListener? = null

    /** Device ID anak yang sedang dipilih di dashboard. Dipakai semua tombol kontrol. */
    private fun requireSelectedChildId(): String? {
        if (selectedChildId == null) {
            toast("Belum ada HP anak yang dipilih")
        }
        return selectedChildId
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // OSMDroid Configuration
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().load(this, android.preference.PreferenceManager.getDefaultSharedPreferences(this))

        binding = ActivityParentDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val code = AppLockPrefs.getFamilyCode(this) ?: "—"
        binding.tvFamilyCode.text = formatCode(code)

        setupAppRecyclerView()
        setupButtons()
        setupMap()
        observeConnectedDevices()
    }

    private fun setupMap() {
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.controller.setZoom(15.0)

        binding.btnOpenMap.setOnClickListener {
            startActivity(android.content.Intent(this, LocationMapActivity::class.java))
        }
    }
    private fun updateMapLocation(lat: Double, lng: Double) {
        binding.mapView.visibility = android.view.View.VISIBLE
        val point = GeoPoint(lat, lng)
        binding.mapView.controller.setCenter(point)

        binding.mapView.overlays.clear()
        val marker = Marker(binding.mapView)
        marker.position = point
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        marker.title = "Lokasi Anak"
        binding.mapView.overlays.add(marker)
        binding.mapView.invalidate()
    }

    private fun setupAppRecyclerView() {
        appAdapter = com.familyguard.ui.adapter.AppListAdapter(
            onLockToggle = { appInfo, locked ->
                val target = requireSelectedChildId() ?: return@AppListAdapter
                FamilyLink.sendLockApp(this, appInfo.packageName, locked, target)
                toast(if (locked) "Kunci ${appInfo.appName} dikirim" else "Buka ${appInfo.appName} dikirim")
            },
            onNotifToggle = { appInfo, blocked ->
                val target = requireSelectedChildId() ?: return@AppListAdapter
                FamilyLink.sendBlockNotif(this, appInfo.packageName, blocked, target)
                toast(if (blocked) "Blokir notif ${appInfo.appName} dikirim" else "Buka notif ${appInfo.appName} dikirim")
            }
        )
        binding.rvChildApps.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.rvChildApps.adapter = appAdapter
    }

    // ─── TOMBOL PERINTAH ─────────────────────────────────────────

    private fun setupButtons() {
        // Cegah NestedScrollView "mencuri" gesture tap sebagai scroll -- tanpa ini,
        // sedikit saja jari bergeser saat menekan tile (sangat umum terjadi di
        // layar sentuh), Android membatalkan klik dan menganggapnya scroll. Ini
        // penyebab utama tombol terasa "susah dipencet" padahal listener sudah benar.
        preventScrollInterceptOnTouch(
            binding.btnLockScreen, binding.btnUnlockScreen,
            binding.btnSendMessage, binding.btnSetPin,
            binding.btnViewScreen, binding.btnResetRole, binding.btnOpenMap
        )

        // Kunci layar HP anak
        binding.btnLockScreen.setOnClickListener {
            val target = requireSelectedChildId() ?: return@setOnClickListener
            confirmAction("Kunci layar HP anak sekarang?") {
                FamilyLink.sendLockScreen(this, target)
                toast("Perintah kunci layar dikirim ✓")
            }
        }

        // Buka kunci layar HP anak
        binding.btnUnlockScreen.setOnClickListener {
            val target = requireSelectedChildId() ?: return@setOnClickListener
            confirmAction("Buka kunci layar HP anak?") {
                FamilyLink.sendUnlockScreen(this, target)
                toast("Perintah buka kunci dikirim ✓")
            }
        }

        // Kirim pesan ke HP anak
        binding.btnSendMessage.setOnClickListener {
            showSendMessageDialog()
        }

        // Atur PIN Anak
        binding.btnSetPin.setOnClickListener {
            showSetPinDialog()
        }

        // Reset Role (untuk testing/pindah device)
        binding.btnResetRole.setOnClickListener {
            confirmAction("Reset semua data dan kembali ke awal?") {
                AppLockPrefs.saveRole(this, "")
                AppLockPrefs.saveFamilyCode(this, "")
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }

        // Lihat & kontrol layar HP anak secara real-time
        binding.btnViewScreen.setOnClickListener {
            val target = requireSelectedChildId() ?: return@setOnClickListener
            startActivity(android.content.Intent(this, ChildScreenViewActivity::class.java).apply {
                putExtra(ChildScreenViewActivity.EXTRA_DEVICE_ID, target)
            })
        }
    }

    /** Beritahu parent (NestedScrollView) untuk tidak intercept touch begitu jari
     * menyentuh salah satu view ini, sehingga klik pendek tidak dibatalkan gara-gara
     * sedikit pergeseran jari yang biasanya dianggap gestur scroll. */
    private fun preventScrollInterceptOnTouch(vararg views: android.view.View) {
        views.forEach { v ->
            v.setOnTouchListener { view, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                } else if (event.action == android.view.MotionEvent.ACTION_UP ||
                    event.action == android.view.MotionEvent.ACTION_CANCEL) {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false // tetap teruskan event supaya setOnClickListener tetap jalan normal
            }
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
                val target = requireSelectedChildId()
                if (msg.isNotBlank() && target != null) {
                    FamilyLink.sendMessage(this, "Pesan dari Orang Tua", msg, target)
                    toast("Pesan dikirim ✓")
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showSetPinDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Masukkan 4 digit PIN baru"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("Atur PIN HP Anak")
            .setMessage("PIN ini akan digunakan anak untuk membuka aplikasi yang dikunci.")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val pin = input.text.toString().trim()
                val target = requireSelectedChildId()
                if (pin.length == 4 && target != null) {
                    FamilyLink.sendSetPin(this, pin, target)
                    toast("Perintah atur PIN dikirim ✓")
                } else if (pin.length != 4) {
                    toast("PIN harus 4 digit!")
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ─── OBSERVE PERANGKAT TERHUBUNG ──────────────────────────────

    private fun observeConnectedDevices() {
        // Observer dasar untuk status online + isi spinner pilihan anak
        deviceObserver = FamilyLink.observeDevices(this) { devices ->
            updateDeviceStatusUI(devices)
            updateChildSelector(devices)
        }
    }

    /** Isi spinner dengan semua HP anak (role CHILD) yang terhubung ke family ini. */
    private fun updateChildSelector(devices: List<FamilyDevice>) {
        childDevices = devices.filter { it.role == AppLockPrefs.ROLE_CHILD }

        if (childDevices.isEmpty()) {
            binding.layoutChildSelector.visibility = android.view.View.GONE
            selectedChildId = null
            return
        }

        // Spinner cuma ditampilkan kalau lebih dari 1 anak -- kalau cuma 1, langsung
        // dipilih otomatis tanpa perlu user pilih apa-apa.
        binding.layoutChildSelector.visibility =
            if (childDevices.size > 1) android.view.View.VISIBLE else android.view.View.GONE

        val labels = childDevices.map { device ->
            val name = device.userName?.takeIf { it.isNotBlank() } ?: "HP Anak (${device.deviceId.take(6)})"
            val status = if (device.online) "🟢" else "⚪"
            "$status $name"
        }

        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerChildSelector.adapter = adapter

        // Pertahankan pilihan sebelumnya kalau device itu masih ada di list terbaru,
        // supaya spinner gak "reset" ke device pertama tiap kali Firebase update.
        val previousIndex = childDevices.indexOfFirst { it.deviceId == selectedChildId }
        val indexToSelect = if (previousIndex >= 0) previousIndex else 0
        binding.spinnerChildSelector.setSelection(indexToSelect, false)
        selectChild(childDevices[indexToSelect].deviceId)

        binding.spinnerChildSelector.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectChild(childDevices[position].deviceId)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    /** Pindah tampilan appList & lokasi ke HP anak tertentu. */
    private fun selectChild(deviceId: String) {
        if (selectedChildId == deviceId && appListListener != null) return
        selectedChildId = deviceId

        val code = AppLockPrefs.getFamilyCode(this) ?: return
        val db = com.google.firebase.database.FirebaseDatabase.getInstance().reference
        val deviceRef = db.child("families").child(code).child("devices").child(deviceId)

        // Lepas listener device sebelumnya (kalau ada) sebelum pasang yang baru,
        // supaya appList/lokasi anak lama gak "nyampur" ke tampilan anak yang baru dipilih.
        appListListener?.let { deviceRef.removeEventListener(it) }

        appListListener = object : ValueEventListener {
            override fun onDataChange(node: com.google.firebase.database.DataSnapshot) {
                // Update Lokasi
                val loc = node.child("location")
                val lat = loc.child("lat").getValue(Double::class.java)
                val lng = loc.child("lng").getValue(Double::class.java)
                if (lat != null && lng != null) {
                    binding.tvLocation.text = "Terakhir terlihat di: $lat, $lng"
                    updateMapLocation(lat, lng)
                }

                // Update App List
                val appListData = node.child("appList").children.mapNotNull { appSnap ->
                    val pkg = appSnap.child("packageName").getValue(String::class.java) ?: return@mapNotNull null
                    val name = appSnap.child("appName").getValue(String::class.java) ?: "App"
                    val locked = appSnap.child("isLocked").getValue(Boolean::class.java) ?: false
                    val notifBlocked = appSnap.child("isNotifBlocked").getValue(Boolean::class.java) ?: false
                    val iconB64 = appSnap.child("icon").getValue(String::class.java)?.takeIf { it.isNotEmpty() }

                    com.familyguard.model.AppInfo(pkg, name, null, locked, notifBlocked, iconB64)
                }
                if (appListData.isNotEmpty()) {
                    appAdapter.submitList(appListData)
                }
            }
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
        }
        deviceRef.addValueEventListener(appListListener!!)
    }

    private fun updateDeviceStatusUI(devices: List<FamilyDevice>) {
        val sb = StringBuilder()
        val children = devices.filter { it.role == AppLockPrefs.ROLE_CHILD }
        val anyOnline = children.any { it.online }

        // Dot + chip status di kartu status device
        binding.dotDeviceStatus.setBackgroundResource(
            if (anyOnline) com.familyguard.R.drawable.dot_online else com.familyguard.R.drawable.dot_offline
        )
        // Dot + chip status ringkas di header
        binding.dotHeaderStatus.setBackgroundResource(
            if (anyOnline) com.familyguard.R.drawable.dot_online else com.familyguard.R.drawable.dot_offline
        )
        binding.tvHeaderStatus.text = if (anyOnline) "Terhubung" else "Terputus"

        if (children.isEmpty()) {
            binding.tvDeviceStatus.text = "Belum ada HP anak yang terhubung"
            setControlsEnabled(false)
            return
        }

        children.forEach { device ->
            val status = if (device.online) "Online" else "Offline"
            val lastSeen = if (!device.online) " · terakhir ${sdf.format(Date(device.lastSeen))}" else ""
            sb.appendLine("HP Anak · $status$lastSeen")
        }
        binding.tvDeviceStatus.text = sb.toString().trim()
        setControlsEnabled(true)
    }

    private fun setControlsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1.0f else 0.4f
        listOf(binding.btnLockScreen, binding.btnUnlockScreen, binding.btnSendMessage, binding.btnSetPin).forEach {
            it.isEnabled = enabled
            it.alpha = alpha
        }
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
        appListListener?.let { listener ->
            val code = AppLockPrefs.getFamilyCode(this)
            val id = selectedChildId
            if (code != null && id != null) {
                com.google.firebase.database.FirebaseDatabase.getInstance().reference
                    .child("families").child(code).child("devices").child(id)
                    .removeEventListener(listener)
            }
        }
    }
}