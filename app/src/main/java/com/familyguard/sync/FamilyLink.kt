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

    // ──────────────────────────────────────────────
    // PROFIL USER (disimpan per akun Google/UID, BUKAN per HP -- supaya
    // login akun yang sama di HP lain tidak perlu isi ulang nama/role/kode)
    // ──────────────────────────────────────────────

    private fun userRef(uid: String) = db.child("users").child(uid)

    fun saveUserProfile(context: Context) {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        val name = AppLockPrefs.getUserName(context)
        val role = AppLockPrefs.getRole(context)
        val code = AppLockPrefs.getFamilyCode(context)

        val updates = mutableMapOf<String, Any>()
        if (!name.isNullOrBlank()) updates["userName"] = name
        if (!role.isNullOrBlank()) updates["role"] = role
        if (!code.isNullOrBlank()) updates["familyCode"] = code
        if (updates.isNotEmpty()) {
            userRef(uid).updateChildren(updates)
                .addOnFailureListener { e ->
                    // FIX: sebelumnya kegagalan di sini (paling sering PERMISSION_DENIED
                    // kalau Firebase Realtime Database Rules belum mengizinkan baca/tulis
                    // ke path /users/{uid}) DIAM-DIAM tidak kelihatan sama sekali --
                    // efeknya profil (nama/role/kode keluarga) tidak pernah benar-benar
                    // tersimpan ke Firebase, jadi login akun yang sama di HP lain tidak
                    // punya apa-apa untuk di-fetch balik, dan user diminta isi ulang dari
                    // nol seolah-olah fiturnya tidak ada.
                    Log.e(TAG, "GAGAL simpan profil user ke /users/$uid -- kemungkinan besar " +
                            "Firebase Realtime Database Rules belum izinkan path ini. " +
                            "Error: ${e.message}", e)
                }
        }
    }

    /**
     * Cek apakah akun Google ini sudah pernah setup sebelumnya (di HP lain).
     * Kalau ada, isi SharedPreferences lokal dari data Firebase supaya user
     * tidak perlu isi nama/pilih role/masukkan kode keluarga lagi.
     */
    fun fetchUserProfile(context: Context, onResult: (found: Boolean) -> Unit) {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            onResult(false)
            return
        }
        userRef(uid).get()
            .addOnSuccessListener { snapshot ->
                val name = snapshot.child("userName").getValue(String::class.java)
                val role = snapshot.child("role").getValue(String::class.java)
                val code = snapshot.child("familyCode").getValue(String::class.java)

                if (!name.isNullOrBlank()) AppLockPrefs.saveUserName(context, name)
                if (!role.isNullOrBlank()) AppLockPrefs.saveRole(context, role)
                if (!code.isNullOrBlank()) {
                    AppLockPrefs.saveFamilyCode(context, code)
                    // Register ulang device ini (device ID beda per HP) ke family yang sama
                    registerDevice(context)
                }
                onResult(!name.isNullOrBlank())
            }
            .addOnFailureListener { e ->
                // FIX: sebelumnya kegagalan fetch (paling sering PERMISSION_DENIED kalau
                // Firebase Rules belum izinkan baca /users/{uid}) cuma di-log ke Logcat --
                // dari sudut pandang user, ini KELIHATAN PERSIS SAMA seperti "memang belum
                // pernah setup akun ini", padahal sebenarnya beda kasus (data ADA tapi
                // GAGAL diambil). onResult(false) di bawah bikin LoginActivity lanjut ke
                // alur onboarding dari nol (isi nama, pilih role lagi) -- makanya perlu
                // penanda jelas di log supaya gampang dibedakan dari kasus "memang baru".
                Log.e(TAG, "GAGAL ambil profil user dari /users/$uid -- kemungkinan besar " +
                        "Firebase Realtime Database Rules belum izinkan path ini (bukan " +
                        "berarti user memang belum pernah setup). Error: ${e.message}", e)
                onResult(false)
            }
    }

    /**
     * Hapus entri device ini dari node keluarga LAMA di Firebase (best-effort,
     * dipanggil SEBELUM role/kode lokal dihapus). Tanpa ini, kalau anak/ortu
     * pindah/ganti keluarga, device lama jadi "hantu" yang masih nongol di
     * dashboard keluarga sebelumnya padahal sudah tidak dipakai lagi di sana.
     */
    fun removeDeviceFromCurrentFamily(context: Context, onComplete: (() -> Unit)? = null) {
        val code = AppLockPrefs.getFamilyCode(context)
        if (code.isNullOrBlank()) {
            onComplete?.invoke()
            return
        }
        val id = AppLockPrefs.getDeviceId(context)
        deviceRef(code, id).removeValue()
            .addOnCompleteListener { onComplete?.invoke() }
    }

    /**
     * Hapus role & kode keluarga dari profil REMOTE (/users/{uid}). PENTING:
     * kalau ini tidak dipanggil, fetchUserProfile() di LoginActivity bakal
     * "mengembalikan" role & kode keluarga LAMA dari Firebase pas user login
     * lagi (di HP yang sama maupun HP lain) -- bikin fitur Reset Role/Ganti
     * Keluarga kelihatan seperti tidak berfungsi sama sekali.
     */
    fun clearRemoteRoleAndFamily(context: Context, onComplete: (() -> Unit)? = null) {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            onComplete?.invoke()
            return
        }
        val updates = mapOf<String, Any?>(
            "role" to null,
            "familyCode" to null
        )
        userRef(uid).updateChildren(updates)
            .addOnCompleteListener { onComplete?.invoke() }
    }

    fun registerDevice(context: Context) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        val id = AppLockPrefs.getDeviceId(context)
        val role = AppLockPrefs.getRole(context) ?: return
        val userName = AppLockPrefs.getUserName(context) ?: ""

        deviceRef(code, id).apply {
            child("role").setValue(role)
            child("userName").setValue(userName)
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
            val iconB64 = try {
                app.icon?.let { com.familyguard.utils.InstalledAppsHelper.iconToBase64(it) }
            } catch (e: Exception) {
                null
            }
            mapOf(
                "packageName" to app.packageName,
                "appName" to app.appName,
                "isLocked" to app.isLocked,
                "isNotifBlocked" to app.isNotifBlocked,
                "icon" to (iconB64 ?: "")
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

    fun sendLockScreen(context: Context, targetDeviceId: String) =
        sendCommand(context, "lock_screen", emptyMap(), targetDeviceId)

    fun sendUnlockScreen(context: Context, targetDeviceId: String) =
        sendCommand(context, "unlock_screen", emptyMap(), targetDeviceId)

    fun sendSetPin(context: Context, pin: String, targetDeviceId: String) =
        sendCommand(context, "set_pin", mapOf("pin" to pin), targetDeviceId)

    fun sendMessage(context: Context, title: String, message: String, targetDeviceId: String) =
        sendCommand(context, "send_message", mapOf("title" to title, "message" to message), targetDeviceId)

    fun sendLockApp(context: Context, packageName: String, lock: Boolean, targetDeviceId: String) =
        sendCommand(
            context, "lock_app",
            mapOf("package_name" to packageName, "action" to if (lock) "add" else "remove"),
            targetDeviceId
        )

    fun sendBlockNotif(context: Context, packageName: String, block: Boolean, targetDeviceId: String) =
        sendCommand(
            context, "block_notif",
            mapOf("package_name" to packageName, "action" to if (block) "add" else "remove"),
            targetDeviceId
        )

    // ===== Lihat Layar & Kontrol Jarak Jauh =====
    // Dikirim dari HP ORANG TUA ke HP ANAK lewat channel command yang sama.

    /** Minta HP anak mulai membagikan layarnya. Anak akan melihat dialog izin
     * "Mulai merekam layar?" dari sistem Android sekali (ini WAJIB dari Android,
     * tidak bisa dilewati oleh aplikasi manapun tanpa akses Device Owner). */
    fun sendRequestScreenShare(context: Context, targetDeviceId: String) =
        sendCommand(context, "start_screen_share", emptyMap(), targetDeviceId)

    fun sendStopScreenShare(context: Context, targetDeviceId: String) =
        sendCommand(context, "stop_screen_share", emptyMap(), targetDeviceId)

    /** Kirim tap jarak jauh. x, y dinormalisasi 0.0-1.0 relatif terhadap lebar/tinggi
     * layar HP anak (bukan pixel absolut), supaya rasio tetap benar walau resolusi beda. */
    fun sendRemoteTap(context: Context, xNorm: Float, yNorm: Float, targetDeviceId: String) =
        sendCommand(context, "remote_tap", mapOf("x" to xNorm, "y" to yNorm), targetDeviceId)

    /** Kirim swipe/drag jarak jauh (dipakai untuk scroll, swipe, dsb saat Mode Kontrol aktif). */
    fun sendRemoteSwipe(context: Context, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long, targetDeviceId: String) =
        sendCommand(
            context, "remote_swipe",
            mapOf("x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2, "duration" to durationMs),
            targetDeviceId
        )

    /** Kirim tombol back jarak jauh (tidak bisa lewat dispatchGesture biasa). */
    fun sendRemoteBack(context: Context, targetDeviceId: String) =
        sendCommand(context, "remote_back", emptyMap(), targetDeviceId)

    private fun sendCommand(context: Context, type: String, payload: Map<String, Any>, targetDeviceId: String) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        val deviceId = AppLockPrefs.getDeviceId(context)

        val cmdRef = commandsRef(code).push()
        cmdRef.setValue(
            mapOf(
                "type" to type,
                "payload" to payload,
                "from" to deviceId,
                "target" to targetDeviceId,
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
                val myDeviceId = AppLockPrefs.getDeviceId(context)
                for (cmdSnap in snapshot.children) {
                    val done = cmdSnap.child("done").getValue(Boolean::class.java) ?: true
                    if (done) continue

                    // Command yang ditarget ke device lain (multi-anak) -- biarkan
                    // saja, jangan diproses & jangan dihapus, supaya device yang
                    // dituju masih bisa membacanya.
                    val target = cmdSnap.child("target").getValue(String::class.java)
                    if (target != null && target != myDeviceId) continue

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
                            // Kalau ScreenCaptureService sudah jalan (dari sesi sebelumnya),
                            // cukup bikin peer connection baru -- TANPA dialog consent lagi.
                            // Consent MediaProjection cuma perlu sekali selama service ini
                            // belum benar-benar dimatikan (lihat ScreenCaptureService.instance).
                            val running = com.familyguard.service.ScreenCaptureService.instance
                            if (running != null) {
                                running.reconnectPeer()
                            } else {
                                // Diproses HP ANAK: minta izin MediaProjection ke sistem (sekali,
                                // wajib dari Android) lalu mulai capture layar. Lewat activity
                                // transparan karena createScreenCaptureIntent() butuh Activity context.
                                val i = android.content.Intent(context, com.familyguard.ui.ScreenCaptureRequestActivity::class.java).apply {
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(i)
                            }
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
                    val userName = snap.child("userName").getValue(String::class.java)?.takeIf { it.isNotBlank() }
                    FamilyDevice(id, role, online, lastSeen, userName)
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

    // ===== WebRTC signaling (SDP + ICE candidate lewat RTDB) =====
    // RTDB di sini CUMA dipakai untuk tukar-menukar "alamat" koneksi (signaling),
    // video sungguhan mengalir langsung peer-to-peer (atau lewat TURN relay kalau
    // NAT strict) via WebRTC -- makanya bisa jauh lebih tinggi fps & rendah delay
    // dibanding kirim tiap frame lewat RTDB.

    private fun webrtcRef(code: String) = familyRef(code).child("webrtc")

    /** Dipanggil HP ANAK setelah PeerConnection membuat SDP offer. */
    fun sendWebRtcOffer(context: Context, sdp: String) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        webrtcRef(code).apply {
            child("offer").setValue(sdp)
            child("answer").removeValue()
            child("candidates_child").removeValue()
            child("candidates_parent").removeValue()
        }
    }

    /** Dipanggil HP ORANG TUA setelah membuat SDP answer dari offer anak. */
    fun sendWebRtcAnswer(context: Context, sdp: String) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        webrtcRef(code).child("answer").setValue(sdp)
    }

    fun sendIceCandidate(context: Context, fromChild: Boolean, sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        val node = if (fromChild) "candidates_child" else "candidates_parent"
        webrtcRef(code).child(node).push().setValue(
            mapOf("sdpMid" to sdpMid, "sdpMLineIndex" to sdpMLineIndex, "candidate" to candidate)
        )
    }

    private var offerListener: ValueEventListener? = null
    private var answerListener: ValueEventListener? = null
    private var childCandidatesListener: ValueEventListener? = null
    private var parentCandidatesListener: ValueEventListener? = null

    /** HP ORANG TUA: dengarkan offer dari anak. */
    fun observeWebRtcOffer(context: Context, onOffer: (String) -> Unit) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        offerListener?.let { webrtcRef(code).child("offer").removeEventListener(it) }
        offerListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                snapshot.getValue(String::class.java)?.let(onOffer)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        webrtcRef(code).child("offer").addValueEventListener(offerListener!!)
    }

    /** HP ANAK: dengarkan answer dari orang tua. */
    fun observeWebRtcAnswer(context: Context, onAnswer: (String) -> Unit) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        answerListener?.let { webrtcRef(code).child("answer").removeEventListener(it) }
        answerListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                snapshot.getValue(String::class.java)?.let(onAnswer)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        webrtcRef(code).child("answer").addValueEventListener(answerListener!!)
    }

    fun observeIceCandidates(context: Context, fromChild: Boolean, onCandidate: (sdpMid: String?, sdpMLineIndex: Int, candidate: String) -> Unit) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        val node = if (fromChild) "candidates_child" else "candidates_parent"

        // Hapus dulu listener LAMA di node yang SAMA (kalau ada, dari sesi sebelumnya)
        // sebelum daftar yang baru -- supaya tidak ada 2 listener numpuk di node
        // yang sama dan menyebabkan command lama (yang nunjuk ke PeerConnection
        // yang sudah di-dispose) tetap ke-trigger dan crash (use-after-free native).
        val oldListener = if (fromChild) childCandidatesListener else parentCandidatesListener
        oldListener?.let { webrtcRef(code).child(node).removeEventListener(it) }

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (c in snapshot.children) {
                    val mid = c.child("sdpMid").getValue(String::class.java)
                    val idx = c.child("sdpMLineIndex").getValue(Int::class.java) ?: 0
                    val cand = c.child("candidate").getValue(String::class.java) ?: continue
                    onCandidate(mid, idx, cand)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        webrtcRef(code).child(node).addValueEventListener(listener)
        // Simpan listener dikunci berdasarkan NODE tempat dia terdaftar (bukan
        // "fromChild" yang gampang ketuker) supaya clearWebRtcSession() pasti
        // menghapusnya dari node yang tepat.
        if (fromChild) childCandidatesListener = listener else parentCandidatesListener = listener
    }

    /** Bersihkan seluruh sesi signaling (dipanggil saat mulai/berhenti berbagi layar). */
    fun clearWebRtcSession(context: Context) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        offerListener?.let { webrtcRef(code).child("offer").removeEventListener(it) }
        answerListener?.let { webrtcRef(code).child("answer").removeEventListener(it) }
        // Node "candidates_child" didengarkan oleh listener yang didaftarkan dengan
        // fromChild=true (childCandidatesListener), dan sebaliknya untuk "candidates_parent".
        childCandidatesListener?.let { webrtcRef(code).child("candidates_child").removeEventListener(it) }
        parentCandidatesListener?.let { webrtcRef(code).child("candidates_parent").removeEventListener(it) }
        offerListener = null; answerListener = null
        childCandidatesListener = null; parentCandidatesListener = null
        webrtcRef(code).removeValue()
    }
}

data class FamilyDevice(
    val deviceId: String,
    val role: String,
    val online: Boolean,
    val lastSeen: Long,
    val userName: String? = null
)