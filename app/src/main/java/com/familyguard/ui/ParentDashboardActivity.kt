package com.familyguard.ui

import android.graphics.Typeface
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

class ParentDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityParentDashboardBinding
    private var deviceObserver: ValueEventListener? = null
    private lateinit var appAdapter: com.familyguard.ui.adapter.AppListAdapter
    private lateinit var memberAdapter: FamilyMemberAdapter
    private var memberSkeletonAnimator: android.animation.ObjectAnimator? = null
    private var isFirstMemberLoad = true
    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var childDevices: List<FamilyDevice> = emptyList()
    private var selectedChildId: String? = null
    private var appListListener: ValueEventListener? = null

    private fun requireSelectedChildId(): String? {
        if (selectedChildId == null) {
            toast("Belum ada HP anak yang dipilih")
        }
        return selectedChildId
    }

    private fun requirePinSet(targetDeviceId: String): Boolean {
        val hasPin = childDevices.firstOrNull { it.deviceId == targetDeviceId }?.hasPin ?: false
        if (hasPin) return true

        AlertDialog.Builder(this)
            .setTitle("PIN Belum Diset")
            .setMessage(
                "HP anak ini belum punya PIN. Kunci layar/aplikasi butuh PIN " +
                        "supaya anak bisa membuka kuncinya sendiri -- kalau dikunci " +
                        "sekarang tanpa PIN, anak tidak akan tahu kode apa yang harus " +
                        "dimasukkan. Atur PIN dulu sebelum mengunci."
            )
            .setPositiveButton("Atur PIN Sekarang") { _, _ -> showSetPinDialog() }
            .setNegativeButton("Batal", null)
            .show()
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().load(this, android.preference.PreferenceManager.getDefaultSharedPreferences(this))

        binding = ActivityParentDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val code = AppLockPrefs.getFamilyCode(this) ?: "—"
        binding.tvFamilyCode.text = formatCode(code)
        binding.tvFamilyCode.typeface = Typeface.DEFAULT
        setupFamilyNameHeader(code)

        setupAppRecyclerView()
        setupMemberRecyclerView()
        setupButtons()
        setupMap()
        observeConnectedDevices()
    }

    private fun setupFamilyNameHeader(code: String) {
        val localFamilyName = AppLockPrefs.getFamilyName(this)
        if (!localFamilyName.isNullOrBlank()) {
            binding.tvFamilyName.text = "Keluarga $localFamilyName"
            binding.tvFamilyName.visibility = android.view.View.VISIBLE
            return
        }

        if (code == "—") return
        com.google.firebase.database.FirebaseDatabase.getInstance().reference
            .child("families").child(code).child("familyName")
            .get()
            .addOnSuccessListener { snapshot ->
                val remoteFamilyName = snapshot.getValue(String::class.java)
                if (!remoteFamilyName.isNullOrBlank()) {
                    binding.tvFamilyName.text = "Keluarga $remoteFamilyName"
                    binding.tvFamilyName.visibility = android.view.View.VISIBLE

                    AppLockPrefs.saveFamilyName(this, remoteFamilyName)
                }
            }
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

    private fun setupMemberRecyclerView() {
        memberAdapter = FamilyMemberAdapter(
            myDeviceId = AppLockPrefs.getDeviceId(this),
            selectedDeviceId = selectedChildId,
            onMemberClick = { member ->
                when {
                    member.role != AppLockPrefs.ROLE_CHILD -> toast("Orang Tua tidak bisa dikontrol")
                    member.loggedOut -> toast("Anak ini sedang logout, tidak bisa dikontrol sampai login lagi")
                    else -> {
                        selectChild(member.deviceId)
                        memberAdapter.setSelectedDeviceId(member.deviceId)
                    }
                }
            }
        )
        binding.rvFamilyMembers.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.rvFamilyMembers.adapter = memberAdapter
        binding.rvFamilyMembers.isNestedScrollingEnabled = false
        memberSkeletonAnimator = com.familyguard.utils.AnimUtils.startSkeletonPulse(binding.layoutMemberSkeleton)
    }

    private fun setupAppRecyclerView() {
        appAdapter = com.familyguard.ui.adapter.AppListAdapter(
            onLockToggle = { appInfo, locked ->
                val target = requireSelectedChildId() ?: return@AppListAdapter

                if (locked && !requirePinSet(target)) return@AppListAdapter
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

        binding.btnViewAllApps.setOnClickListener {
            val target = requireSelectedChildId() ?: return@setOnClickListener
            val childName = childDevices.firstOrNull { it.deviceId == target }?.userName
                ?.takeIf { it.isNotBlank() } ?: "HP Anak"
            val intent = android.content.Intent(this, AppListActivity::class.java).apply {
                putExtra(AppListActivity.EXTRA_DEVICE_ID, target)
                putExtra(AppListActivity.EXTRA_CHILD_NAME, childName)
            }
            startActivity(intent)
        }

        binding.btnScreenTimeReport.setOnClickListener {
            val target = requireSelectedChildId() ?: return@setOnClickListener
            val childName = childDevices.firstOrNull { it.deviceId == target }?.userName
                ?.takeIf { it.isNotBlank() } ?: "HP Anak"
            val intent = android.content.Intent(this, ScreenTimeReportActivity::class.java).apply {
                putExtra(ScreenTimeReportActivity.EXTRA_DEVICE_ID, target)
                putExtra(ScreenTimeReportActivity.EXTRA_CHILD_NAME, childName)
            }
            startActivity(intent)
        }
    }

    private fun setupButtons() {

        preventScrollInterceptOnTouch(
            binding.btnLockScreen, binding.btnUnlockScreen,
            binding.btnSendMessage, binding.btnSetPin,
            binding.btnViewScreen, binding.btnOpenMap
        )

        binding.btnLockScreen.setOnClickListener {
            val target = requireSelectedChildId() ?: return@setOnClickListener
            if (!requirePinSet(target)) return@setOnClickListener
            confirmAction("Kunci layar HP anak sekarang?") {
                FamilyLink.sendLockScreen(this, target)
                toast("Perintah kunci layar dikirim")
            }
        }

        binding.btnUnlockScreen.setOnClickListener {
            val target = requireSelectedChildId() ?: return@setOnClickListener
            confirmAction("Buka kunci layar HP anak?") {
                FamilyLink.sendUnlockScreen(this, target)
                toast("Perintah buka kunci dikirim")
            }
        }

        binding.btnSendMessage.setOnClickListener {
            showSendMessageDialog()
        }

        binding.btnSetPin.setOnClickListener {
            showSetPinDialog()
        }

        binding.btnViewScreen.setOnClickListener {
            val target = requireSelectedChildId() ?: return@setOnClickListener
            startActivity(android.content.Intent(this, ChildScreenViewActivity::class.java).apply {
                putExtra(ChildScreenViewActivity.EXTRA_DEVICE_ID, target)
            })
        }
    }

    private fun preventScrollInterceptOnTouch(vararg views: android.view.View) {
        views.forEach { v ->
            v.setOnTouchListener { view, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                } else if (event.action == android.view.MotionEvent.ACTION_UP ||
                    event.action == android.view.MotionEvent.ACTION_CANCEL) {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
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
                    toast("Pesan dikirim")
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showSetPinDialog() {
        val currentPin = childDevices.firstOrNull { it.deviceId == selectedChildId }?.currentPin

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
                val target = requireSelectedChildId()
                if (pin.length == 4 && target != null) {
                    FamilyLink.sendSetPin(this, pin, target)
                    toast("Perintah atur PIN dikirim")
                } else if (pin.length != 4) {
                    toast("PIN harus 4 digit!")
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun observeConnectedDevices() {

        deviceObserver = FamilyLink.observeDevices(this) { devices ->
            updateDeviceStatusUI(devices)
            updateChildSelector(devices)
        }
    }

    private fun updateChildSelector(devices: List<FamilyDevice>) {
        childDevices = devices.filter { it.role == AppLockPrefs.ROLE_CHILD }

        val selected = childDevices.firstOrNull { it.deviceId == selectedChildId }
        if (selectedChildId != null && (selected == null || selected.loggedOut)) {
            val code = AppLockPrefs.getFamilyCode(this)
            if (code != null && appListListener != null) {
                com.google.firebase.database.FirebaseDatabase.getInstance().reference
                    .child("families").child(code).child("devices").child(selectedChildId!!)
                    .removeEventListener(appListListener!!)
            }
            selectedChildId = null
            appListListener = null
        }

        binding.layoutChildSelector.visibility =
            if (childDevices.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE

        val ordered = devices.sortedBy { it.role != AppLockPrefs.ROLE_PARENT }
        memberAdapter.submitList(ordered)
        memberAdapter.setSelectedDeviceId(selectedChildId)

        selectedChildId?.let { id ->
            val name = childDevices.firstOrNull { it.deviceId == id }?.userName
                ?.takeIf { it.isNotBlank() } ?: "HP Anak"
            binding.tvControllingChildName.text = name
        }

        if (isFirstMemberLoad) {
            isFirstMemberLoad = false
            if (childDevices.isNotEmpty()) {
                com.familyguard.utils.AnimUtils.crossFadeToContent(
                    binding.layoutMemberSkeleton, binding.rvFamilyMembers, memberSkeletonAnimator
                )
            } else {
                // Belum ada anak SAAT INI, tapi rvFamilyMembers tetap harus
                // di-reveal sekarang -- kalau tidak, dia nyangkut di GONE
                // selamanya (cuma crossFadeToContent() di atas yang set jadi
                // VISIBLE, dan isFirstMemberLoad cuma true sekali). Update
                // berikutnya pas anak beneran join (real-time dari Firebase)
                // jadi tidak pernah kelihatan sampai activity dibuka ulang --
                // ini akar masalah "harus keluar-masuk dashboard dulu baru
                // muncul".
                memberSkeletonAnimator?.cancel()
                binding.layoutMemberSkeleton.visibility = android.view.View.GONE
                binding.rvFamilyMembers.visibility = android.view.View.VISIBLE
            }
        }

        updateControlsVisibility()
    }

    private fun updateControlsVisibility() {
        val hasChild = childDevices.isNotEmpty()
        val hasSelection = selectedChildId != null

        binding.layoutNoChildYet.visibility = if (!hasChild) android.view.View.VISIBLE else android.view.View.GONE
        binding.layoutNoChildSelected.visibility = if (hasChild && !hasSelection) android.view.View.VISIBLE else android.view.View.GONE
        binding.layoutDeviceControls.visibility = if (hasSelection) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun selectChild(deviceId: String) {
        if (selectedChildId == deviceId && appListListener != null) return
        selectedChildId = deviceId

        val childName = childDevices.firstOrNull { it.deviceId == deviceId }?.userName
            ?.takeIf { it.isNotBlank() } ?: "HP Anak"
        binding.tvControllingChildName.text = childName
        updateControlsVisibility()

        val code = AppLockPrefs.getFamilyCode(this) ?: return
        val db = com.google.firebase.database.FirebaseDatabase.getInstance().reference
        val deviceRef = db.child("families").child(code).child("devices").child(deviceId)

        appListListener?.let { deviceRef.removeEventListener(it) }

        appListListener = object : ValueEventListener {
            override fun onDataChange(node: com.google.firebase.database.DataSnapshot) {

                val loc = node.child("location")
                val lat = loc.child("lat").getValue(Double::class.java)
                val lng = loc.child("lng").getValue(Double::class.java)
                if (lat != null && lng != null) {
                    binding.tvLocation.text = "Terakhir terlihat di: $lat, $lng"
                    updateMapLocation(lat, lng)
                }

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

        binding.dotDeviceStatus.setBackgroundResource(
            if (anyOnline) com.familyguard.R.drawable.dot_online else com.familyguard.R.drawable.dot_offline
        )

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
            val childName = device.userName?.takeIf { it.isNotBlank() }
                ?: "HP Anak (${device.deviceId.take(6)})"
            val status = if (device.online) "Online" else "Offline"
            val lastSeen = if (!device.online) " · terakhir ${sdf.format(Date(device.lastSeen))}" else ""
            sb.appendLine("$childName · $status$lastSeen")
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
        memberSkeletonAnimator?.cancel()
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