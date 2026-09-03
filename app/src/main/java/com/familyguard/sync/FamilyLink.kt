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

    private var familyDeletionListener: ValueEventListener? = null
    private var familyDeletionListenerCode: String? = null

    private fun userRef(uid: String) = db.child("users").child(uid)

    /**
     * Memantau node families/{code} secara realtime. Kalau orang tua
     * menghapus keluarga (families/{code} dihapus dari Firebase), node ini
     * jadi tidak ada lagi -- callback [onDeleted] dipanggil supaya UI bisa
     * kasih tau anak & sembunyiin akses ke dashboard. Listener otomatis
     * langsung dicek juga saat dipasang, jadi kasus "keluarga sudah
     * dihapus sebelum app dibuka lagi" ikut ketangkep.
     */
    fun listenFamilyDeletion(context: Context, onDeleted: () -> Unit) {
        val code = AppLockPrefs.getFamilyCode(context)
        if (code.isNullOrBlank()) return

        stopListeningFamilyDeletion()

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    onDeleted()
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "listenFamilyDeletion cancelled: ${error.message}")
            }
        }
        familyDeletionListener = listener
        familyDeletionListenerCode = code
        familyRef(code).addValueEventListener(listener)
    }

    fun stopListeningFamilyDeletion() {
        val code = familyDeletionListenerCode
        val listener = familyDeletionListener
        if (code != null && listener != null) {
            familyRef(code).removeEventListener(listener)
        }
        familyDeletionListener = null
        familyDeletionListenerCode = null
    }

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

                    Log.e(TAG, "GAGAL simpan profil user ke /users/$uid -- kemungkinan besar " +
                            "Firebase Realtime Database Rules belum izinkan path ini. " +
                            "Error: ${e.message}", e)
                }
        }
    }

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

                    registerDevice(context)
                }
                onResult(!name.isNullOrBlank())
            }
            .addOnFailureListener { e ->

                Log.e(TAG, "GAGAL ambil profil user dari /users/$uid -- kemungkinan besar " +
                        "Firebase Realtime Database Rules belum izinkan path ini (bukan " +
                        "berarti user memang belum pernah setup). Error: ${e.message}", e)
                onResult(false)
            }
    }

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
     * Dipanggil dari sisi ANAK saat memilih "Keluar dari Keluarga" (beda
     * dengan Ganti Keluarga -- di sini anak TIDAK langsung join keluarga
     * lain). Ditulis SEBELUM device node dihapus (lihat
     * removeDeviceFromCurrentFamily / AccountActions.leaveFamily) karena
     * begitu device node hilang, nama anak sudah tidak bisa diambil lagi.
     * Disimpan di families/{code}/childLeftNotice (bukan di node devices) supaya
     * tetap ada walau device-nya sudah tercabut, dan dibaca realtime oleh
     * ParentDashboardActivity untuk nampilin banner peringatan.
     */
    fun notifyChildLeftFamily(context: Context, onComplete: (() -> Unit)? = null) {
        val code = AppLockPrefs.getFamilyCode(context)
        if (code.isNullOrBlank()) {
            onComplete?.invoke()
            return
        }
        val userName = AppLockPrefs.getUserName(context)?.takeIf { it.isNotBlank() }
        familyRef(code).child("childLeftNotice").setValue(
            mapOf(
                "userName" to (userName ?: "Anak"),
                "timestamp" to System.currentTimeMillis()
            )
        ).addOnCompleteListener { onComplete?.invoke() }
    }

    /**
     * Dengarkan notice "anak keluar dari keluarga" secara realtime di sisi
     * Orang Tua. onChange dipanggil dengan null kalau notice-nya sudah
     * di-dismiss/dihapus (lihat dismissChildLeftNotice).
     */
    fun observeChildLeftNotice(
        context: Context,
        onChange: (userName: String?, timestamp: Long) -> Unit
    ): ValueEventListener {
        val code = AppLockPrefs.getFamilyCode(context) ?: ""
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    onChange(null, 0L)
                    return
                }
                val userName = snapshot.child("userName").getValue(String::class.java)
                val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L
                onChange(userName, timestamp)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "childLeftNotice observer cancelled: ${error.message}")
            }
        }
        familyRef(code).child("childLeftNotice").addValueEventListener(listener)
        return listener
    }

    fun removeChildLeftNoticeListener(context: Context, listener: ValueEventListener) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        familyRef(code).child("childLeftNotice").removeEventListener(listener)
    }

    /** Dipanggil saat Orang Tua menutup (dismiss) banner notice di dashboard. */
    fun dismissChildLeftNotice(context: Context) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        familyRef(code).child("childLeftNotice").removeValue()
    }

    /**
     * Dipanggil dari sisi ORANG TUA untuk mengeluarkan satu anggota
     * keluarga (biasanya anak) tanpa menghapus keluarganya secara
     * keseluruhan. Cukup hapus node families/{code}/devices/{targetDeviceId}
     * -- anggota lain tetap ada, hanya device ini yang tercabut.
     */
    fun kickDevice(context: Context, targetDeviceId: String, onComplete: (() -> Unit)? = null) {
        val code = AppLockPrefs.getFamilyCode(context)
        if (code.isNullOrBlank()) {
            onComplete?.invoke()
            return
        }
        deviceRef(code, targetDeviceId).removeValue()
            .addOnCompleteListener { onComplete?.invoke() }
    }

    private var kickListenerRef: DatabaseReference? = null
    private var kickListener: ValueEventListener? = null

    /**
     * Dipantau dari sisi ANAK. Kalau node device milik HP ini
     * (families/{code}/devices/{myDeviceId}) tiba-tiba hilang padahal
     * keluarganya sendiri masih ada, berarti orang tua baru saja
     * mengeluarkan (kick) device ini secara spesifik -- beda dengan kasus
     * keluarga dihapus total (itu ditangani listenFamilyDeletion()).
     */
    fun listenForKick(context: Context, onKicked: () -> Unit) {
        val code = AppLockPrefs.getFamilyCode(context)
        if (code.isNullOrBlank()) return
        val myDeviceId = AppLockPrefs.getDeviceId(context)

        stopListeningForKick()

        val ref = deviceRef(code, myDeviceId)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    familyRef(code).get().addOnSuccessListener { familySnap ->
                        if (familySnap.exists()) {
                            onKicked()
                        }
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "listenForKick cancelled: ${error.message}")
            }
        }
        kickListenerRef = ref
        kickListener = listener
        ref.addValueEventListener(listener)
    }

    fun stopListeningForKick() {
        val ref = kickListenerRef
        val listener = kickListener
        if (ref != null && listener != null) {
            ref.removeEventListener(listener)
        }
        kickListenerRef = null
        kickListener = null
    }

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

    fun deleteFamilyEntirely(context: Context, onComplete: (() -> Unit)? = null) {
        val code = AppLockPrefs.getFamilyCode(context)
        if (code.isNullOrBlank()) {
            onComplete?.invoke()
            return
        }
        familyRef(code).removeValue()
            .addOnCompleteListener { onComplete?.invoke() }
    }

    fun updateUserName(context: Context, newName: String, onComplete: (() -> Unit)? = null) {
        AppLockPrefs.saveUserName(context, newName)

        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        if (uid != null) {
            userRef(uid).child("userName").setValue(newName)
        }
        if (!AppLockPrefs.getFamilyCode(context).isNullOrBlank()) {
            registerDevice(context)
        }
        onComplete?.invoke()
    }

    fun markLoggedOut(context: Context, onComplete: (() -> Unit)? = null) {
        val code = AppLockPrefs.getFamilyCode(context)
        if (code.isNullOrBlank()) {
            onComplete?.invoke()
            return
        }
        val id = AppLockPrefs.getDeviceId(context)
        deviceRef(code, id).updateChildren(
            mapOf("loggedOut" to true, "online" to false)
        ).addOnCompleteListener { onComplete?.invoke() }
    }

    fun syncPermissionStatus(
        context: Context,
        deviceAdminActive: Boolean,
        accessibilityActive: Boolean,
        overlayActive: Boolean,
        notifListenerActive: Boolean,
        locationActive: Boolean
    ) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        val id = AppLockPrefs.getDeviceId(context)
        deviceRef(code, id).updateChildren(
            mapOf(
                "deviceAdminActive" to deviceAdminActive,
                "accessibilityEnabled" to accessibilityActive,
                "overlayActive" to overlayActive,
                "notifListenerActive" to notifListenerActive,
                "locationActive" to locationActive
            )
        )
    }

    /**
     * Firebase RTDB key TIDAK BOLEH mengandung titik (`.`), padahal package
     * name Android selalu pakai titik (mis. "com.whatsapp") -- jadi titiknya
     * diganti koma (karakter yang tidak pernah muncul di package name asli)
     * supaya bisa dipakai sebagai key node, dan dibalikin lagi pas dibaca.
     */
    private fun encodePackageKey(packageName: String) = packageName.replace(".", ",")
    private fun decodePackageKey(key: String) = key.replace(",", ".")

    /**
     * Sinkron total menit pemakaian [packageName] pada tanggal [date]
     * (format yyyy-MM-dd) ke families/{code}/devices/{deviceId}/usage/{date}.
     * Dipanggil dari UsageTracker, throttled supaya cuma nulis kalau angka
     * menitnya beneran naik. Dibaca lagi oleh ScreenTimeReportActivity di
     * sisi Orang Tua untuk generate laporan mingguan.
     */
    fun syncUsageMinutes(context: Context, date: String, packageName: String, minutes: Int) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        val id = AppLockPrefs.getDeviceId(context)
        deviceRef(code, id).child("usage").child(date).child(encodePackageKey(packageName)).setValue(minutes)
    }

    /**
     * Update aplikasi yang SEDANG dibuka (realtime) ke
     * families/{code}/devices/{deviceId}/currentApp -- dipanggil dari
     * AppLockAccessibilityService.evaluatePackage() setiap foreground app
     * berganti (bukan numpang di flow menit UsageTracker, supaya update-nya
     * instan tanpa nunggu threshold 1 menit). Dibaca via listener realtime
     * oleh ParentDashboardActivity, bukan one-shot get() seperti laporan
     * mingguan.
     */
    fun updateCurrentApp(context: Context, packageName: String) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        val id = AppLockPrefs.getDeviceId(context)
        deviceRef(code, id).child("currentApp").setValue(
            mapOf(
                "packageName" to packageName,
                "since" to System.currentTimeMillis()
            )
        )
    }

    /**
     * Dengarkan perubahan currentApp milik [targetDeviceId] secara realtime.
     * Dipanggil saat orang tua memilih/melihat detail 1 HP anak, dilepas
     * lewat removeCurrentAppListener() saat pindah anak / activity ditutup.
     */
    fun observeCurrentApp(
        context: Context,
        targetDeviceId: String,
        onChange: (packageName: String?, since: Long) -> Unit
    ): ValueEventListener {
        val code = AppLockPrefs.getFamilyCode(context) ?: ""
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val pkg = snapshot.child("packageName").getValue(String::class.java)
                val since = snapshot.child("since").getValue(Long::class.java) ?: 0L
                onChange(pkg, since)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "currentApp observer cancelled: ${error.message}")
            }
        }
        deviceRef(code, targetDeviceId).child("currentApp").addValueEventListener(listener)
        return listener
    }

    fun removeCurrentAppListener(context: Context, targetDeviceId: String, listener: ValueEventListener) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        deviceRef(code, targetDeviceId).child("currentApp").removeEventListener(listener)
    }

    /**
     * Ambil rekap pemakaian [daysBack] hari terakhir (termasuk hari ini) dari
     * HP anak [targetDeviceId], lalu kembalikan sebagai flat list yang siap
     * dipakai ScreenTimeReportGenerator. Satu-shot read (bukan listener
     * real-time) karena laporan cukup di-refresh manual/tiap buka halaman.
     */
    fun fetchUsageHistory(
        context: Context,
        targetDeviceId: String,
        daysBack: Int,
        onResult: (List<com.familyguard.utils.ScreenTimeReportGenerator.DailyUsage>) -> Unit
    ) {
        val code = AppLockPrefs.getFamilyCode(context)
        if (code.isNullOrBlank()) {
            onResult(emptyList())
            return
        }
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val calendar = java.util.Calendar.getInstance()
        val dates = (0 until daysBack).map {
            val d = sdf.format(calendar.time)
            calendar.add(java.util.Calendar.DAY_OF_YEAR, -1)
            d
        }

        deviceRef(code, targetDeviceId).child("usage")
            .get()
            .addOnSuccessListener { snapshot ->
                val result = mutableListOf<com.familyguard.utils.ScreenTimeReportGenerator.DailyUsage>()
                for (date in dates) {
                    val dayNode = snapshot.child(date)
                    for (child in dayNode.children) {
                        val minutes = child.getValue(Long::class.java) ?: continue
                        val pkg = decodePackageKey(child.key ?: continue)
                        result.add(
                            com.familyguard.utils.ScreenTimeReportGenerator.DailyUsage(
                                date = date,
                                appPackage = pkg,
                                durationMinutes = minutes
                            )
                        )
                    }
                }
                onResult(result)
            }
            .addOnFailureListener { onResult(emptyList()) }
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

            // JANGAN timpa hasPin jadi false di sini kalau baca lokalnya false --
            // status "PIN sudah diset" harus monoton (sekali true, jangan pernah
            // balik false lewat re-registrasi). Kalau kita selalu ikutin nilai
            // lokal apa adanya, proses yang sempat kebunuh & restart sebelum
            // SharedPreferences ke-flush ke disk bisa bikin flag ini kebalik ke
            // false di Firebase padahal PIN sebenarnya sudah ada -- efeknya SEMUA
            // tombol fitur yang butuh PIN (Kunci Semua, Kunci Layar, dll) jadi
            // minta set PIN lagi terus-menerus. Command "set_pin" sendiri tetap
            // yang berwenang menuliskan hasPin=true begitu PIN baru masuk.
            if (AppLockPrefs.hasPin(context)) {
                child("hasPin").setValue(true)
            }

            child("loggedOut").setValue(false)
            child("online").onDisconnect().setValue(false)
            child("lastSeen").onDisconnect().setValue(System.currentTimeMillis())
        }
        Log.d(TAG, "Device registered: $id as $role in family $code")
    }

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

    /**
     * Kirim pesan popup (send_message command -- tetap sama seperti
     * sebelumnya, dibaca via startListening() lalu ditampilkan sebagai
     * LockScreenActivity mode MESSAGE) SEKALIGUS simpan salinannya secara
     * persisten ke families/{code}/messages/{targetDeviceId}/{pushId}
     * supaya tidak hilang kalau popup-nya kelewat/di-dismiss -- ini yang
     * dibaca ParentDashboardActivity/ChildDashboardActivity buat inbox.
     */
    fun sendMessage(context: Context, title: String, message: String, targetDeviceId: String) {
        sendCommand(context, "send_message", mapOf("title" to title, "message" to message), targetDeviceId)

        val code = AppLockPrefs.getFamilyCode(context) ?: return
        val myId = AppLockPrefs.getDeviceId(context)
        val myRole = AppLockPrefs.getRole(context)
        val myName = AppLockPrefs.getUserName(context)?.takeIf { it.isNotBlank() }
            ?: if (myRole == AppLockPrefs.ROLE_PARENT) "Orang Tua" else "Anak"

        familyRef(code).child("messages").child(targetDeviceId).push().setValue(
            mapOf(
                "title" to title,
                "body" to message,
                "fromDeviceId" to myId,
                "fromName" to myName,
                "fromRole" to (myRole ?: ""),
                "timestamp" to System.currentTimeMillis(),
                "read" to false
            )
        )
    }

    data class ChatMessage(
        val id: String,
        val title: String,
        val body: String,
        val fromDeviceId: String,
        val fromName: String,
        val timestamp: Long,
        val read: Boolean
    )

    /**
     * Dengarkan seluruh pesan masuk untuk [ownDeviceId] secara realtime,
     * diurutkan dari yang terbaru. Dipakai oleh MessageInboxActivity (list
     * lengkap) dan dashboard (badge jumlah belum dibaca).
     */
    fun observeMessages(
        context: Context,
        ownDeviceId: String,
        onChange: (List<ChatMessage>) -> Unit
    ): ValueEventListener {
        val code = AppLockPrefs.getFamilyCode(context) ?: ""
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = snapshot.children.mapNotNull { child ->
                    val title = child.child("title").getValue(String::class.java) ?: return@mapNotNull null
                    val body = child.child("body").getValue(String::class.java) ?: ""
                    val fromDeviceId = child.child("fromDeviceId").getValue(String::class.java) ?: ""
                    val fromName = child.child("fromName").getValue(String::class.java) ?: "Anggota Keluarga"
                    val timestamp = child.child("timestamp").getValue(Long::class.java) ?: 0L
                    val read = child.child("read").getValue(Boolean::class.java) ?: false
                    ChatMessage(child.key ?: return@mapNotNull null, title, body, fromDeviceId, fromName, timestamp, read)
                }.sortedByDescending { it.timestamp }
                onChange(list)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "observeMessages cancelled: ${error.message}")
            }
        }
        familyRef(code).child("messages").child(ownDeviceId).addValueEventListener(listener)
        return listener
    }

    fun removeMessagesListener(context: Context, ownDeviceId: String, listener: ValueEventListener) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        familyRef(code).child("messages").child(ownDeviceId).removeEventListener(listener)
    }

    fun markMessageRead(context: Context, ownDeviceId: String, messageId: String) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        familyRef(code).child("messages").child(ownDeviceId).child(messageId).child("read").setValue(true)
    }

    fun deleteMessage(context: Context, ownDeviceId: String, messageId: String) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        familyRef(code).child("messages").child(ownDeviceId).child(messageId).removeValue()
    }

    fun sendLockApp(context: Context, packageName: String, lock: Boolean, targetDeviceId: String) =
        sendCommand(
            context, "lock_app",
            mapOf("package_name" to packageName, "action" to if (lock) "add" else "remove"),
            targetDeviceId
        )

    /**
     * Kirim aksi kunci/buka untuk BANYAK aplikasi sekaligus dalam SATU command,
     * bukan satu command per-aplikasi. Dipakai oleh tombol "Kunci Semua" /
     * "Buka Semua" supaya diproses atomic di HP anak (lihat catatan bug lama:
     * mengirim N command terpisah untuk aksi massal bikin sebagian command
     * "remove" telat/ke-skip saat listener commands memproses ulang seluruh
     * daftar berkali-kali secara reentrant).
     */
    fun sendLockAppsBulk(context: Context, packageNames: List<String>, lock: Boolean, targetDeviceId: String) {
        if (packageNames.isEmpty()) return
        sendCommand(
            context, "lock_apps_bulk",
            mapOf("package_names" to packageNames, "action" to if (lock) "add" else "remove"),
            targetDeviceId
        )
    }

    fun sendBlockNotif(context: Context, packageName: String, block: Boolean, targetDeviceId: String) =
        sendCommand(
            context, "block_notif",
            mapOf("package_name" to packageName, "action" to if (block) "add" else "remove"),
            targetDeviceId
        )

    fun sendRequestScreenShare(context: Context, targetDeviceId: String) =
        sendCommand(context, "start_screen_share", emptyMap(), targetDeviceId)

    fun sendStopScreenShare(context: Context, targetDeviceId: String) =
        sendCommand(context, "stop_screen_share", emptyMap(), targetDeviceId)

    fun sendRemoteTap(context: Context, xNorm: Float, yNorm: Float, targetDeviceId: String) =
        sendCommand(context, "remote_tap", mapOf("x" to xNorm, "y" to yNorm), targetDeviceId)

    fun sendRemoteSwipe(context: Context, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long, targetDeviceId: String) =
        sendCommand(
            context, "remote_swipe",
            mapOf("x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2, "duration" to durationMs),
            targetDeviceId
        )

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

    fun startListening(context: Context, isGlobal: Boolean = false, onMessage: (title: String, body: String, fromDeviceId: String) -> Unit) {
        val code = AppLockPrefs.getFamilyCode(context) ?: run {
            Log.w(TAG, "No family code — not listening")
            return
        }

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

                    val target = cmdSnap.child("target").getValue(String::class.java)
                    if (target != null && target != myDeviceId) continue

                    val type = cmdSnap.child("type").getValue(String::class.java) ?: continue
                    val payload = cmdSnap.child("payload")

                    Log.d(TAG, "Executing command: $type")

                    when (type) {
                        "lock_screen" -> {

                            AppLockPrefs.setDeviceLocked(context, true)

                            lockManager.lockScreen()

                            val intent = android.content.Intent(context, com.familyguard.ui.LockScreenActivity::class.java).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                putExtra(com.familyguard.ui.LockScreenActivity.EXTRA_MODE, com.familyguard.ui.LockScreenActivity.MODE_DEVICE_LOCK)
                            }
                            context.startActivity(intent)
                        }

                        "unlock_screen" -> {

                            AppLockPrefs.setDeviceLocked(context, false)

                            val intent = android.content.Intent("com.familyguard.ACTION_UNLOCK").apply {
                                `package` = context.packageName
                            }
                            context.sendBroadcast(intent)
                        }

                        "set_pin" -> {
                            val pin = payload.child("pin").getValue(String::class.java) ?: ""
                            if (pin.isNotEmpty()) {
                                AppLockPrefs.savePin(context, pin)

                                val code = AppLockPrefs.getFamilyCode(context)
                                val id = AppLockPrefs.getDeviceId(context)
                                if (!code.isNullOrBlank()) {
                                    // Satu write batch (updateChildren) supaya hasPin & currentPin
                                    // konsisten diterapkan bareng, dan pasang failure listener
                                    // supaya kegagalan (mis. Firebase rules, offline) kelihatan
                                    // di log, bukan gagal diam-diam seperti sebelumnya.
                                    deviceRef(code, id).updateChildren(
                                        mapOf("hasPin" to true, "currentPin" to pin)
                                    ).addOnSuccessListener {
                                        Log.d(TAG, "hasPin/currentPin berhasil disinkron ke Firebase")
                                    }.addOnFailureListener { e ->
                                        Log.e(TAG, "GAGAL sinkron hasPin/currentPin ke Firebase: ${e.message}", e)
                                    }
                                }
                            }
                        }

                        "send_message" -> {
                            val title = payload.child("title").getValue(String::class.java)
                                ?: "Pesan dari Orang Tua"
                            val msg = payload.child("message").getValue(String::class.java) ?: ""
                            val fromDeviceId = cmdSnap.child("from").getValue(String::class.java) ?: ""
                            onMessage(title, msg, fromDeviceId)
                        }

                        "lock_app" -> {
                            val pkg = payload.child("package_name").getValue(String::class.java) ?: continue
                            val action = payload.child("action").getValue(String::class.java)
                            if (action == "add") AppLockPrefs.addLockedApp(context, pkg)
                            else AppLockPrefs.removeLockedApp(context, pkg)
                        }

                        "lock_apps_bulk" -> {
                            val pkgs = payload.child("package_names").children.mapNotNull {
                                it.getValue(String::class.java)
                            }
                            val action = payload.child("action").getValue(String::class.java)
                            if (pkgs.isNotEmpty()) {
                                if (action == "add") AppLockPrefs.addLockedApps(context, pkgs)
                                else AppLockPrefs.removeLockedApps(context, pkgs)
                            }
                        }

                        "block_notif" -> {
                            val pkg = payload.child("package_name").getValue(String::class.java) ?: continue
                            val action = payload.child("action").getValue(String::class.java)
                            if (action == "add") AppLockPrefs.addBlockedNotifApp(context, pkg)
                            else AppLockPrefs.removeBlockedNotifApp(context, pkg)
                        }

                        "start_screen_share" -> {

                            val running = com.familyguard.service.ScreenCaptureService.instance
                            if (running != null) {
                                running.reconnectPeer()
                            } else {

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
        if (isGlobalListener && !force) return

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
                    val hasPin = snap.child("hasPin").getValue(Boolean::class.java) ?: false
                    val currentPin = snap.child("currentPin").getValue(String::class.java)?.takeIf { it.isNotBlank() }
                    val loggedOut = snap.child("loggedOut").getValue(Boolean::class.java) ?: false
                    val accessibilityEnabled = snap.child("accessibilityEnabled").getValue(Boolean::class.java) ?: false
                    val deviceAdminActive = snap.child("deviceAdminActive").getValue(Boolean::class.java) ?: false
                    val overlayActive = snap.child("overlayActive").getValue(Boolean::class.java) ?: false
                    val notifListenerActive = snap.child("notifListenerActive").getValue(Boolean::class.java) ?: false
                    val locationActive = snap.child("locationActive").getValue(Boolean::class.java) ?: false
                    FamilyDevice(
                        id, role, online, lastSeen, userName, hasPin, currentPin, loggedOut,
                        accessibilityEnabled, deviceAdminActive, overlayActive, notifListenerActive, locationActive
                    )
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

    private fun webrtcRef(code: String) = familyRef(code).child("webrtc")

    fun sendWebRtcOffer(context: Context, sdp: String) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        webrtcRef(code).apply {
            child("offer").setValue(sdp)
            child("answer").removeValue()
            child("candidates_child").removeValue()
            child("candidates_parent").removeValue()
        }
    }

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

        if (fromChild) childCandidatesListener = listener else parentCandidatesListener = listener
    }

    fun clearWebRtcSession(context: Context) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        offerListener?.let { webrtcRef(code).child("offer").removeEventListener(it) }
        answerListener?.let { webrtcRef(code).child("answer").removeEventListener(it) }

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
    val userName: String? = null,
    val hasPin: Boolean = false,
    val currentPin: String? = null,
    val loggedOut: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val deviceAdminActive: Boolean = false,
    val overlayActive: Boolean = false,
    val notifListenerActive: Boolean = false,
    val locationActive: Boolean = false
)