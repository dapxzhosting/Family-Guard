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

class MessageInboxActivity : BaseActivity() {

    private lateinit var binding: ActivityMessageInboxBinding
    private lateinit var adapter: MessageAdapter
    private var messagesListener: ValueEventListener? = null
    private var myDeviceId: String = ""
    private var skeletonAnimator: android.animation.ObjectAnimator? = null
    private var skeletonStartedAt: Long = 0L
    private var isFirstLoad = true

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

        skeletonAnimator = com.familyguard.utils.AnimUtils.startSkeletonPulse(binding.layoutInboxSkeleton)
        skeletonAnimator?.let { registerSkeletonAnimator(it) }
        skeletonStartedAt = System.currentTimeMillis()

        messagesListener = FamilyLink.observeMessages(this, myDeviceId) { messages ->
            adapter.submitList(messages)

            val targetContent = if (messages.isEmpty()) binding.layoutInboxEmpty else binding.rvMessages
            val otherContent = if (messages.isEmpty()) binding.rvMessages else binding.layoutInboxEmpty

            if (isFirstLoad) {
                isFirstLoad = false
                otherContent.visibility = View.GONE
                com.familyguard.utils.AnimUtils.finishSkeleton(
                    binding.layoutInboxSkeleton, targetContent, skeletonAnimator, skeletonStartedAt, hasContent = true
                )
            } else {
                targetContent.visibility = View.VISIBLE
                otherContent.visibility = View.GONE
            }

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
        skeletonAnimator?.cancel()
        messagesListener?.let { FamilyLink.removeMessagesListener(this, myDeviceId, it) }
    }
}
