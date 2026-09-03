package com.familyguard.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.familyguard.databinding.ActivityMessageInboxBinding
import com.familyguard.sync.FamilyLink
import com.familyguard.ui.adapter.MessageAdapter
import com.familyguard.utils.AppLockPrefs
import com.google.firebase.database.ValueEventListener

/**
 * Riwayat pesan (families/{code}/messages/{myDeviceId}) untuk device INI --
 * dipakai baik oleh Orang Tua maupun Anak, keduanya lewat activity yang
 * sama, karena FamilyLink.sendMessage() menyimpan salinan persisten di
 * kedua arah (lihat FamilyLink.kt). Dibuka dari tombol inbox di dashboard/
 * menu masing-masing role. Tap 1 pesan -> tandai terbaca. Tombol X di baris
 * -> hapus pesan itu saja.
 */
class MessageInboxActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMessageInboxBinding
    private lateinit var adapter: MessageAdapter
    private var messagesListener: ValueEventListener? = null
    private var myDeviceId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMessageInboxBinding.inflate(layoutInflater)
        setContentView(binding.root)

        myDeviceId = AppLockPrefs.getDeviceId(this)

        binding.btnInboxBack.setOnClickListener { finish() }

        adapter = MessageAdapter(
            onClick = { msg ->
                if (!msg.read) {
                    FamilyLink.markMessageRead(this, myDeviceId, msg.id)
                }
                AlertDialog.Builder(this)
                    .setTitle("${msg.title}\n— ${msg.fromName}")
                    .setMessage(msg.body)
                    .setPositiveButton("Tutup", null)
                    .show()
            },
            onDelete = { msg ->
                FamilyLink.deleteMessage(this, myDeviceId, msg.id)
            }
        )
        binding.rvMessages.layoutManager = LinearLayoutManager(this)
        binding.rvMessages.adapter = adapter

        messagesListener = FamilyLink.observeMessages(this, myDeviceId) { messages ->
            adapter.submitList(messages)
            binding.layoutInboxEmpty.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
            binding.rvMessages.visibility = if (messages.isEmpty()) View.GONE else View.VISIBLE

            val unread = messages.count { !it.read }
            binding.tvInboxSubtitle.text = if (unread > 0) {
                "$unread pesan belum dibaca"
            } else {
                "Riwayat pesan dari keluarga"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        messagesListener?.let { FamilyLink.removeMessagesListener(this, myDeviceId, it) }
    }
}
