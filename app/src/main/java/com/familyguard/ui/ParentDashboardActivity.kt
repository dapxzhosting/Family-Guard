package com.familyguard.ui

import android.graphics.Typeface
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.familyguard.R
import com.familyguard.databinding.ActivityParentDashboardBinding
import com.familyguard.sync.FamilyDevice
import com.familyguard.sync.FamilyLink
import com.familyguard.ui.adapter.FamilyMemberAdapter
import com.familyguard.utils.AppLockPrefs
import com.google.firebase.database.ValueEventListener
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ParentDashboardActivity : BaseActivity() {

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
    private var childLeftNoticeListener: ValueEventListener? = null
    private var inboxListener: ValueEventListener? = null
    private var approvalRequestsListener: ValueEventListener? = null
    private var pendingApprovalRequests: List<com.familyguard.sync.FamilyLink.ApprovalRequest> = emptyList()
    private var seenApprovalRequestIds: MutableSet<String> = mutableSetOf()

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

    private fun requireDeviceAdminActive(targetDeviceId: String): Boolean {
        val device = childDevices.firstOrNull { it.deviceId == targetDeviceId }
        if (device?.deviceAdminActive == true) return true

        AlertDialog.Builder(this)
            .setTitle("Device Admin Belum Aktif")
            .setMessage(
                "HP anak ini belum mengaktifkan izin Device Admin, jadi " +
                        "Kunci Layar tidak akan berfungsi penuh. Minta anak buka " +
                        "Dashboard-nya dan aktifkan semua status keamanan dulu " +
                        "(termasuk Device Admin) sebelum kamu mengunci layarnya."
            )
            .setPositiveButton("Mengerti", null)
            .show()
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().load(this, android.preference.PreferenceManager.getDefaultSharedPreferences(this))

        binding = ActivityParentDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnParentDashboardBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val code = AppLockPrefs.getFamilyCode(this) ?: "—"
        binding.tvFamilyCode.text = formatCode(code)
        binding.tvFamilyCode.typeface = Typeface.DEFAULT
        setupFamilyNameHeader(code)

        setupAppRecyclerView()
        setupMemberRecyclerView()
        setupButtons()
        setupMap()
        observeConnectedDevices()
        observeChildLeftNotice()
        setupInbox()
        setupApprovalRequests()
    }

    private fun setupApprovalRequests() {
        binding.btnApprovalApprove.setOnClickListener {
            pendingApprovalRequests.firstOrNull()?.let { req ->
                FamilyLink.respondApprovalRequest(this, req, approve = true)
                toast("Disetujui — ${req.appName} dibuka ${req.durationMinutes} menit untuk ${req.childName}")
            }
        }
        binding.btnApprovalReject.setOnClickListener {
            pendingApprovalRequests.firstOrNull()?.let { req ->
                FamilyLink.respondApprovalRequest(this, req, approve = false)
                toast("Permintaan ${req.childName} ditolak")
            }
        }

        approvalRequestsListener = FamilyLink.observeApprovalRequests(this) { requests ->
            val pending = requests.filter { it.status == "pending" }

            pending.forEach { req ->
                if (seenApprovalRequestIds.add(req.id)) {
                    showApprovalNotification(req)
                }
            }

            pendingApprovalRequests = pending
            if (pending.isEmpty()) {
                binding.cardApprovalRequest.visibility = android.view.View.GONE
            } else {
                val first = pending.first()
                binding.cardApprovalRequest.visibility = android.view.View.VISIBLE
                binding.tvApprovalRequestDetail.text =
                    "${first.childName} minta izin buka \"${first.appName}\" (${first.durationMinutes} menit)"
                binding.tvApprovalRequestCount.visibility =
                    if (pending.size > 1) android.view.View.VISIBLE else android.view.View.GONE
                binding.tvApprovalRequestCount.text = "+${pending.size - 1} lainnya"
            }
        }
    }

    private fun showApprovalNotification(request: com.familyguard.sync.FamilyLink.ApprovalRequest) {
        val channelId = "family_approval_channel"
        val nm = getSystemService(android.app.NotificationManager::class.java)
        val channel = android.app.NotificationChannel(
            channelId, "Permintaan Izin Anak", android.app.NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Notifikasi saat anak minta izin buka aplikasi terkunci" }
        nm.createNotificationChannel(channel)

        val intent = Intent(this, ParentDashboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = android.app.PendingIntent.getActivity(
            this, request.id.hashCode(), intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notif = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(com.familyguard.R.drawable.ic_family)
            .setContentTitle("${request.childName} minta izin")
            .setContentText("Buka \"${request.appName}\" selama ${request.durationMinutes} menit")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        nm.notify(request.id.hashCode(), notif)
    }

    private fun setupInbox() {
        val myDeviceId = AppLockPrefs.getDeviceId(this)
        binding.btnOpenInbox.setOnClickListener {
            startActivity(Intent(this, MessageInboxActivity::class.java))
        }
        inboxListener = FamilyLink.observeMessages(this, myDeviceId) { messages ->
            val unread = messages.count { !it.read }
            if (unread > 0) {
                binding.tvInboxBadge.text = if (unread > 9) "9+" else unread.toString()
                binding.tvInboxBadge.visibility = android.view.View.VISIBLE
            } else {
                binding.tvInboxBadge.visibility = android.view.View.GONE
            }
        }
    }

    private fun observeChildLeftNotice() {
        childLeftNoticeListener = FamilyLink.observeChildLeftNotice(this) { userName, _ ->
            if (userName != null) {
                binding.tvChildLeftNotice.text = "$userName telah keluar dari keluarga"
                binding.cardChildLeftNotice.visibility = android.view.View.VISIBLE
            } else {
                binding.cardChildLeftNotice.visibility = android.view.View.GONE
            }
        }
        binding.btnDismissChildLeftNotice.setOnClickListener {
            FamilyLink.dismissChildLeftNotice(this)
        }
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
            isParentView = true,
            onMemberClick = { member ->
                when {
                    member.role != AppLockPrefs.ROLE_CHILD -> toast("Orang Tua tidak bisa dikontrol")
                    member.loggedOut -> toast("Anak ini sedang logout, tidak bisa dikontrol sampai login lagi")
                    else -> {
                        selectChild(member.deviceId)
                        memberAdapter.setSelectedDeviceId(member.deviceId)
                    }
                }
            },
            onKickClick = { member ->
                val displayName = member.userName?.takeIf { it.isNotBlank() }
                    ?: if (member.role == AppLockPrefs.ROLE_PARENT) "Orang Tua ini" else "Anak ini"
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Keluarkan dari keluarga?")
                    .setMessage("$displayName akan dikeluarkan dari keluarga dan kehilangan akses ke dashboard. Anggota ini bisa gabung lagi nanti kalau punya kode keluarga.")
                    .setPositiveButton("Keluarkan") { _, _ ->
                        FamilyLink.kickDevice(this, member.deviceId) {
                            toast("${displayName} telah dikeluarkan dari keluarga")
                        }
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }
        )
        binding.rvFamilyMembers.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.rvFamilyMembers.adapter = memberAdapter
        binding.rvFamilyMembers.isNestedScrollingEnabled = false
        memberSkeletonAnimator = com.familyguard.utils.AnimUtils.startSkeletonPulse(binding.layoutMemberSkeleton)
        memberSkeletonAnimator?.let { registerSkeletonAnimator(it) }
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
            if (!requireDeviceAdminActive(target)) return@setOnClickListener
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

                memberSkeletonAnimator?.cancel()
                binding.layoutMemberSkeleton.visibility = android.view.View.GONE
                binding.rvFamilyMembers.visibility = android.view.View.VISIBLE
            }
        }

        updateControlsVisibility()
        updatePermissionWarning()
    }

    private fun updatePermissionWarning() {
        val device = childDevices.firstOrNull { it.deviceId == selectedChildId }
        if (device == null) {
            binding.cardPermissionWarning.visibility = android.view.View.GONE
            return
        }

        val missing = mutableListOf<String>()
        if (!device.deviceAdminActive) missing.add("Device Admin")
        if (!device.accessibilityEnabled) missing.add("Accessibility")
        if (!device.overlayActive) missing.add("Tampil di Atas Aplikasi Lain")
        if (!device.notifListenerActive) missing.add("Akses Notifikasi")
        if (!device.locationActive) missing.add("Lokasi")

        if (missing.isEmpty()) {
            binding.cardPermissionWarning.visibility = android.view.View.GONE
        } else {
            binding.cardPermissionWarning.visibility = android.view.View.VISIBLE
            binding.tvPermissionWarningDetail.text =
                "Belum aktif: ${missing.joinToString(", ")}. Minta anak buka Dashboard-nya dan izinkan semua status keamanan."
        }
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
        updatePermissionWarning()

        binding.tvCurrentAppName.text = "Memuat..."
        binding.tvCurrentAppSince.text = ""
        binding.ivCurrentAppIcon.setImageDrawable(null)

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

                updateCurrentAppUI(node.child("currentApp"), appListData)
            }
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
        }
        deviceRef.addValueEventListener(appListListener!!)
    }

    private fun updateCurrentAppUI(
        currentAppSnap: com.google.firebase.database.DataSnapshot,
        appListData: List<com.familyguard.model.AppInfo>
    ) {
        val pkg = currentAppSnap.child("packageName").getValue(String::class.java)
        val since = currentAppSnap.child("since").getValue(Long::class.java) ?: 0L

        if (pkg.isNullOrBlank()) {
            binding.tvCurrentAppName.text = "Tidak ada data"
            binding.tvCurrentAppSince.text = ""
            binding.ivCurrentAppIcon.setImageDrawable(null)
            return
        }

        if (pkg == com.familyguard.utils.AppFilter.STATUS_HOME_SCREEN) {
            binding.tvCurrentAppName.text = "Layar Utama"
            binding.ivCurrentAppIcon.setImageResource(R.drawable.ic_home)
            binding.tvCurrentAppSince.text =
                if (since > 0) "sejak ${sdf.format(Date(since))}" else ""
            return
        }
        if (pkg == com.familyguard.utils.AppFilter.STATUS_LOCK_SCREEN) {
            binding.tvCurrentAppName.text = "Layar Terkunci"
            binding.ivCurrentAppIcon.setImageResource(R.drawable.ic_lock)
            binding.tvCurrentAppSince.text =
                if (since > 0) "sejak ${sdf.format(Date(since))}" else ""
            return
        }

        val info = appListData.firstOrNull { it.packageName == pkg }
        binding.tvCurrentAppName.text = info?.appName ?: pkg

        if (since > 0) {
            binding.tvCurrentAppSince.text = "sejak ${sdf.format(Date(since))}"
        } else {
            binding.tvCurrentAppSince.text = ""
        }

        val iconB64 = info?.iconBase64
        if (!iconB64.isNullOrBlank()) {
            try {
                val bytes = android.util.Base64.decode(iconB64, android.util.Base64.DEFAULT)
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                binding.ivCurrentAppIcon.setImageBitmap(bmp)
            } catch (e: Exception) {
                binding.ivCurrentAppIcon.setImageDrawable(null)
            }
        } else {
            binding.ivCurrentAppIcon.setImageDrawable(null)
        }
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
        childLeftNoticeListener?.let { FamilyLink.removeChildLeftNoticeListener(this, it) }
        inboxListener?.let { FamilyLink.removeMessagesListener(this, AppLockPrefs.getDeviceId(this), it) }
        approvalRequestsListener?.let { FamilyLink.removeApprovalRequestsListener(this, it) }
    }
}

