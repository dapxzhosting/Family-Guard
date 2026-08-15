package com.familyguard.sync

import android.content.Context
import android.util.Log
import com.familyguard.admin.LockManager
import com.familyguard.utils.AppLockPrefs
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

object FamilyLink {

    private const val TAG = "FamilyLink"
    private val db = FirebaseDatabase.getInstance().reference

    fun registerDevice(context: Context) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        val id = AppLockPrefs.getDeviceId(context)
        val role = AppLockPrefs.getRole(context) ?: return

        deviceRef(code, id).apply {
            child("role").setValue(role)
            child("online").setValue(true)
            child("lastSeen").setValue(System.currentTimeMillis())
            child("model").setValue(android.os.Build.MODEL)
            child("online").onDisconnect().setValue(false)
            child("lastSeen").onDisconnect().setValue(System.currentTimeMillis())
        }
        Log.d(TAG, "Device registered: $id as $role in family $code")
    }

    /**
     * Heartbeat berkala supaya status "online" & "lastSeen" di dashboard orang tua
     * selalu akurat selama app/service masih hidup — bukan cuma sekali pas pairing.
     *
     * PENTING: onDisconnect() itu terikat ke KONEKSI Firebase yang aktif saat itu.
     * Kalau koneksi terputus & sambung ulang (device restart, app di-kill lalu
     * dibuka lagi, ganti jaringan), handler onDisconnect yang lama otomatis hilang
     * dan HARUS didaftarkan ulang — makanya di sini juga dipanggil ulang, tidak cukup
     * cuma di registerDevice() pas pairing pertama kali.
     */
    fun sendHeartbeat(context: Context) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        val id = AppLockPrefs.getDeviceId(context)

        deviceRef(code, id).apply {
            child("online").setValue(true)
            child("lastSeen").setValue(System.currentTimeMillis())
            child("online").onDisconnect().setValue(false)
            child("lastSeen").onDisconnect().setValue(System.currentTimeMillis())
        }
    }

    fun updateAppList(context: Context, apps: List<com.familyguard.model.AppInfo>) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        val id = AppLockPrefs.getDeviceId(context)

        val appData = apps.map { app ->
            mapOf(
                "packageName" to app.packageName,
                "appName" to app.appName,
                "isLocked" to app.isLocked,
                "isNotifBlocked" to app.isNotifBlocked
            )
        }
        deviceRef(code, id).child("appList").setValue(appData)
    }

    fun updateLocation(context: Context, lat: Double, lng: Double) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        val id = AppLockPrefs.getDeviceId(context)

        val locData = mapOf(
            "lat" to lat,
            "lng" to lng,
            "timestamp" to System.currentTimeMillis()
        )
        deviceRef(code, id).child("location").setValue(locData)
    }

    fun sendLockScreen(context: Context) =
        sendCommand(context, "lock_screen", emptyMap())

    fun sendUnlockScreen(context: Context) =
        sendCommand(context, "unlock_screen", emptyMap())

    fun sendSetPin(context: Context, pin: String) =
        sendCommand(context, "set_pin", mapOf("pin" to pin))

    fun sendMessage(context: Context, title: String, message: String) =
        sendCommand(context, "send_message", mapOf("title" to title, "message" to message))

    fun sendLockApp(context: Context, packageName: String, lock: Boolean) =
        sendCommand(
            context, "lock_app",
            mapOf("package_name" to packageName, "action" to if (lock) "add" else "remove")
        )

    fun sendBlockNotif(context: Context, packageName: String, block: Boolean) =
        sendCommand(
            context, "block_notif",
            mapOf("package_name" to packageName, "action" to if (block) "add" else "remove")
        )

    // ===== Lihat Layar & Kontrol Jarak Jauh =====
    // Dikirim dari HP ORANG TUA ke HP ANAK lewat channel command yang sama.

    /** Minta HP anak mulai membagikan layarnya. Anak akan melihat dialog izin
     * "Mulai merekam layar?" dari sistem Android sekali (ini WAJIB dari Android,
     * tidak bisa dilewati oleh aplikasi manapun tanpa akses Device Owner). */
    fun sendRequestScreenShare(context: Context) =
        sendCommand(context, "start_screen_share", emptyMap())

    fun sendStopScreenShare(context: Context) =
        sendCommand(context, "stop_screen_share", emptyMap())

    /** Kirim tap jarak jauh. x, y dinormalisasi 0.0-1.0 relatif terhadap lebar/tinggi
     * layar HP anak (bukan pixel absolut), supaya rasio tetap benar walau resolusi beda. */
    fun sendRemoteTap(context: Context, xNorm: Float, yNorm: Float) =
        sendCommand(context, "remote_tap", mapOf("x" to xNorm, "y" to yNorm))

    /** Kirim swipe/drag jarak jauh (dipakai untuk scroll, swipe, dsb saat Mode Kontrol aktif). */
    fun sendRemoteSwipe(context: Context, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) =
        sendCommand(
            context, "remote_swipe",
            mapOf("x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2, "duration" to durationMs)
        )

    /** Kirim tombol back jarak jauh (tidak bisa lewat dispatchGesture biasa). */
    fun sendRemoteBack(context: Context) =
        sendCommand(context, "remote_back", emptyMap())

    private fun sendCommand(context: Context, type: String, payload: Map<String, Any>) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        val deviceId = AppLockPrefs.getDeviceId(context)

        val cmdRef = commandsRef(code).push()
        cmdRef.setValue(
            mapOf(
                "type" to type,
                "payload" to payload,
                "from" to deviceId,
                "timestamp" to System.currentTimeMillis(),
                "done" to false
            )
        ).addOnSuccessListener {
            Log.d(TAG, "Command sent: $type")
        }.addOnFailureListener {
            Log.e(TAG, "Failed to send command: $type", it)
        }
    }

    private var commandListener: ValueEventListener? = null
    private var isGlobalListener = false

    fun startListening(context: Context, isGlobal: Boolean = false, onMessage: (title: String, body: String) -> Unit) {
        val code = AppLockPrefs.getFamilyCode(context) ?: run {
            Log.w(TAG, "No family code — not listening")
            return
        }

        // Jangan timpa listener global dengan listener activity
        if (commandListener != null && isGlobalListener && !isGlobal) return

        if (commandListener != null) {
            stopListening(context, true)
        }

        isGlobalListener = isGlobal
        val lockManager = LockManager(context)

        commandListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (cmdSnap in snapshot.children) {
                    val done = cmdSnap.child("done").getValue(Boolean::class.java) ?: true
                    if (done) continue

                    val type = cmdSnap.child("type").getValue(String::class.java) ?: continue
                    val payload = cmdSnap.child("payload")

                    Log.d(TAG, "Executing command: $type")

                    when (type) {
                        "lock_screen" -> {
                            // Simpan status kunci secara lokal
                            AppLockPrefs.setDeviceLocked(context, true)
                            // 1. Kunci sistem (jika admin aktif)
                            lockManager.lockScreen()
                            // 2. Munculkan overlay LockScreenActivity kita
                            val intent = android.content.Intent(context, com.familyguard.ui.LockScreenActivity::class.java).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                putExtra(com.familyguard.ui.LockScreenActivity.EXTRA_MODE, com.familyguard.ui.LockScreenActivity.MODE_DEVICE_LOCK)
                            }
                            context.startActivity(intent)
                        }

                        "unlock_screen" -> {
                            // Hapus status kunci secara lokal
                            AppLockPrefs.setDeviceLocked(context, false)
                            // Kirim broadcast atau gunakan EventBus/LocalBroadcast untuk menutup LockScreenActivity
                            val intent = android.content.Intent("com.familyguard.ACTION_UNLOCK").apply {
                                `package` = context.packageName
                            }
                            context.sendBroadcast(intent)
                        }

                        "set_pin" -> {
                            val pin = payload.child("pin").getValue(String::class.java) ?: ""
                            if (pin.isNotEmpty()) {
                                AppLockPrefs.savePin(context, pin)
                            }
                        }

                        "send_message" -> {
                            val title = payload.child("title").getValue(String::class.java)
                                ?: "Pesan dari Orang Tua"
                            val msg = payload.child("message").getValue(String::class.java) ?: ""
                            onMessage(title, msg)
                        }

                        "lock_app" -> {
                            val pkg = payload.child("package_name").getValue(String::class.java) ?: continue
                            val action = payload.child("action").getValue(String::class.java)
                            if (action == "add") AppLockPrefs.addLockedApp(context, pkg)
                            else AppLockPrefs.removeLockedApp(context, pkg)
                        }

                        "block_notif" -> {
                            val pkg = payload.child("package_name").getValue(String::class.java) ?: continue
                            val action = payload.child("action").getValue(String::class.java)
                            if (action == "add") AppLockPrefs.addBlockedNotifApp(context, pkg)
                            else AppLockPrefs.removeBlockedNotifApp(context, pkg)
                        }

                        "start_screen_share" -> {
                            // Diproses HP ANAK: minta izin MediaProjection ke sistem (sekali,
                            // wajib dari Android) lalu mulai capture layar. Lewat activity
                            // transparan karena createScreenCaptureIntent() butuh Activity context.
                            val i = android.content.Intent(context, com.familyguard.ui.ScreenCaptureRequestActivity::class.java).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(i)
                        }

                        "stop_screen_share" -> {
                            context.stopService(android.content.Intent(context, com.familyguard.service.ScreenCaptureService::class.java))
                        }

                        "remote_tap", "remote_swipe", "remote_back" -> {
                            // Diproses HP ANAK: teruskan ke AccessibilityService yang sedang
                            // aktif untuk benar-benar men-simulasikan sentuhan di layar.
                            com.familyguard.service.AppLockAccessibilityService.instance
                                ?.executeRemoteInput(type, payload)
                        }
                    }

                    cmdSnap.ref.child("done").setValue(true)
                    cmdSnap.ref.removeValue()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Command listener cancelled: ${error.message}")
            }
        }

        commandsRef(code).addValueEventListener(commandListener!!)
        Log.d(TAG, "Listening for commands in family: $code")
    }

    fun stopListening(context: Context, force: Boolean = false) {
        if (isGlobalListener && !force) return // Jangan stop jika ini listener global dari Service

        val code = AppLockPrefs.getFamilyCode(context) ?: return
        commandListener?.let { commandsRef(code).removeEventListener(it) }
        commandListener = null
        isGlobalListener = false
    }

    fun observeDevices(
        context: Context,
        onChange: (List<FamilyDevice>) -> Unit
    ): ValueEventListener {
        val code = AppLockPrefs.getFamilyCode(context) ?: ""
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = snapshot.children.mapNotNull { snap ->
                    val id = snap.key ?: return@mapNotNull null
                    val role = snap.child("role").getValue(String::class.java) ?: return@mapNotNull null
                    val online = snap.child("online").getValue(Boolean::class.java) ?: false
                    val lastSeen = snap.child("lastSeen").getValue(Long::class.java) ?: 0L
                    FamilyDevice(id, role, online, lastSeen)
                }
                onChange(list)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Device observer cancelled: ${error.message}")
            }
        }
        devicesRef(code).addValueEventListener(listener)
        return listener
    }

    fun removeDeviceObserver(context: Context, listener: ValueEventListener) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        devicesRef(code).removeEventListener(listener)
    }

    /**
     * Observe lokasi HP anak secara real-time (dipakai oleh LocationMapActivity).
     * Ambil dari device pertama yang role-nya CHILD dalam family yang sama.
     */
    fun observeChildLocation(
        context: Context,
        onUpdate: (lat: Double, lng: Double, timestamp: Long) -> Unit,
        onNoData: () -> Unit = {}
    ): ValueEventListener {
        val code = AppLockPrefs.getFamilyCode(context) ?: ""
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val childNode = snapshot.children.firstOrNull {
                    it.child("role").getValue(String::class.java) == AppLockPrefs.ROLE_CHILD
                }
                val loc = childNode?.child("location")
                val lat = loc?.child("lat")?.getValue(Double::class.java)
                val lng = loc?.child("lng")?.getValue(Double::class.java)
                val ts = loc?.child("timestamp")?.getValue(Long::class.java) ?: 0L

                if (lat != null && lng != null) {
                    onUpdate(lat, lng, ts)
                } else {
                    onNoData()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Location observer cancelled: ${error.message}")
            }
        }
        devicesRef(code).addValueEventListener(listener)
        return listener
    }

    fun removeLocationObserver(context: Context, listener: ValueEventListener) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        devicesRef(code).removeEventListener(listener)
    }

    private fun familyRef(code: String) = db.child("families").child(code)
    private fun devicesRef(code: String) = familyRef(code).child("devices")
    private fun deviceRef(code: String, deviceId: String) = devicesRef(code).child(deviceId)
    private fun commandsRef(code: String) = familyRef(code).child("commands")

    // ===== Streaming layar (frame demi frame lewat RTDB) =====
    // Cukup 1 node yang DITIMPA tiap frame baru (bukan ditambah/push), supaya
    // data lama otomatis "hilang" dan tidak menumpuk di database.

    /** Dipanggil dari HP ANAK (ScreenCaptureService) untuk mengirim 1 frame layar. */
    fun uploadScreenFrame(context: Context, base64Jpeg: String, width: Int, height: Int) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        val id = AppLockPrefs.getDeviceId(context)
        deviceRef(code, id).child("screen_stream").setValue(
            mapOf(
                "frame" to base64Jpeg,
                "width" to width,
                "height" to height,
                "timestamp" to System.currentTimeMillis()
            )
        )
    }

    /** Dipanggil dari HP ORANG TUA (ChildScreenViewActivity) untuk memantau frame terbaru.
     *
     * PENTING (fix lag parah): sebelumnya listener dipasang di SELURUH node
     * "devices" (semua data semua device -- lokasi, app list, dll), jadi tiap
     * ada 1 frame baru, SELURUH pohon data ikut ke-download ulang, bukan cuma
     * framenya. Sekarang: cari deviceId anak SEKALI (single read, ringan),
     * lalu pasang listener LANGSUNG ke node screen_stream anak itu saja --
     * jadi tiap update cuma ngirim payload frame doang, jauh lebih ringan &
     * lebih cepat sampai. */
    private var screenStreamRef: DatabaseReference? = null

    fun observeScreenStream(
        context: Context,
        onFrame: (base64Jpeg: String, width: Int, height: Int) -> Unit
    ): ValueEventListener {
        val code = AppLockPrefs.getFamilyCode(context) ?: ""
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val frame = snapshot.child("frame").getValue(String::class.java) ?: return
                val width = snapshot.child("width").getValue(Int::class.java) ?: return
                val height = snapshot.child("height").getValue(Int::class.java) ?: return
                onFrame(frame, width, height)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Screen stream observer cancelled: ${error.message}")
            }
        }

        // Cari deviceId anak sekali saja (bukan tiap frame), baru pasang
        // listener khusus di node screen_stream-nya.
        devicesRef(code).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val childId = snapshot.children.firstOrNull {
                    it.child("role").getValue(String::class.java) == AppLockPrefs.ROLE_CHILD
                }?.key ?: return
                val ref = deviceRef(code, childId).child("screen_stream")
                screenStreamRef = ref
                ref.addValueEventListener(listener)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Gagal cari device anak: ${error.message}")
            }
        })

        return listener
    }

    fun removeScreenStreamObserver(context: Context, listener: ValueEventListener) {
        screenStreamRef?.removeEventListener(listener)
        screenStreamRef = null
    }
}

data class FamilyDevice(
    val deviceId: String,
    val role: String,
    val online: Boolean,
    val lastSeen: Long
)