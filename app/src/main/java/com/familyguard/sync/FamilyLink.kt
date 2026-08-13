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
            child("online").onDisconnect().setValue(false)
            child("lastSeen").onDisconnect().setValue(System.currentTimeMillis())
        }
        Log.d(TAG, "Device registered: $id as $role in family $code")
    }

    fun sendLockScreen(context: Context) =
        sendCommand(context, "lock_screen", emptyMap())

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

    fun startListening(context: Context, onMessage: (title: String, body: String) -> Unit) {
        val code = AppLockPrefs.getFamilyCode(context) ?: run {
            Log.w(TAG, "No family code — not listening")
            return
        }
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
                        "lock_screen" -> lockManager.lockScreen()

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

    fun stopListening(context: Context) {
        val code = AppLockPrefs.getFamilyCode(context) ?: return
        commandListener?.let { commandsRef(code).removeEventListener(it) }
        commandListener = null
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

    private fun familyRef(code: String) = db.child("families").child(code)
    private fun devicesRef(code: String) = familyRef(code).child("devices")
    private fun deviceRef(code: String, deviceId: String) = devicesRef(code).child(deviceId)
    private fun commandsRef(code: String) = familyRef(code).child("commands")
}

data class FamilyDevice(
    val deviceId: String,
    val role: String,
    val online: Boolean,
    val lastSeen: Long
)