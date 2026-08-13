package com.familyguard.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import com.familyguard.R
import com.familyguard.databinding.ActivityChildHomeBinding
import com.familyguard.sync.FamilyLink
import com.familyguard.utils.AppLockPrefs

class ChildHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChildHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChildHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val code = AppLockPrefs.getFamilyCode(this) ?: "—"
        binding.tvFamilyCode.text = "Kode keluarga: ${formatCode(code)}"

        FamilyLink.startListening(this) { title, message ->
            showMessageNotification(title, message)
            showMessageDialog(title, message)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        FamilyLink.stopListening(this)
    }

    private fun showMessageDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle("📩 $title")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showMessageNotification(title: String, body: String) {
        val channelId = "family_message_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannel(
            NotificationChannel(channelId, "Pesan Keluarga", NotificationManager.IMPORTANCE_HIGH)
                .apply { enableVibration(true) }
        )

        val notif = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_family)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        nm.notify(System.currentTimeMillis().toInt(), notif)
    }

    private fun formatCode(code: String) =
        if (code.length == 6) "${code.take(3)}-${code.drop(3)}" else code
}