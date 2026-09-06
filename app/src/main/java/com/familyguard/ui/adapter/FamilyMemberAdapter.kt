package com.familyguard.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.familyguard.R
import com.familyguard.sync.FamilyDevice
import com.familyguard.utils.AppLockPrefs

class FamilyMemberAdapter(
    private var members: List<FamilyDevice> = emptyList(),
    private val myDeviceId: String? = null,
    private var selectedDeviceId: String? = null,
    private val onMemberClick: ((FamilyDevice) -> Unit)? = null,

    private val isParentView: Boolean = false,
    private val onKickClick: ((FamilyDevice) -> Unit)? = null
) : RecyclerView.Adapter<FamilyMemberAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.ivMemberIcon)
        val name: TextView = view.findViewById(R.id.tvMemberName)
        val role: TextView = view.findViewById(R.id.tvMemberRole)
        val dot: View = view.findViewById(R.id.dotMemberOnline)
        val btnKick: ImageButton = view.findViewById(R.id.btnKickMember)
        // Simpan ripple bawaan dari XML, supaya bisa dimatikan kalau item ini
        // memang tidak bisa di-tap (biar tidak terlihat seperti tombol padahal bukan).
        val defaultForeground: android.graphics.drawable.Drawable? = view.foreground
    }

    fun submitList(newMembers: List<FamilyDevice>) {
        members = newMembers
        notifyDataSetChanged()
    }

    fun setSelectedDeviceId(deviceId: String?) {
        selectedDeviceId = deviceId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_family_member, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val member = members[position]
        val isParent = member.role == AppLockPrefs.ROLE_PARENT

        holder.icon.setImageResource(if (isParent) R.drawable.ic_role_parent else R.drawable.ic_role_child)
        val baseName = member.userName?.takeIf { it.isNotBlank() }
            ?: if (isParent) "Orang Tua" else "Anak"
        holder.name.text = if (member.deviceId == myDeviceId) "$baseName (kamu)" else baseName

        if (member.loggedOut) {

            holder.role.text = if (isParent) {
                "Orang tua telah logout dari akun ini"
            } else {
                "Anak telah logout dari akun ini"
            }
            holder.role.setTextColor(0xFFC62828.toInt())
            holder.dot.setBackgroundResource(R.drawable.dot_offline)
        } else {
            holder.role.text = if (isParent) "Orang Tua" else "Anak"
            holder.role.setTextColor(0xFF757575.toInt())
            holder.dot.setBackgroundResource(
                if (member.online) R.drawable.dot_online else R.drawable.dot_offline
            )
        }

        val isSelected = member.deviceId == selectedDeviceId
        holder.itemView.setBackgroundColor(
            if (isSelected) 0xFFE8EAF6.toInt() else 0x00000000
        )
        holder.name.setTextColor(
            if (isSelected) 0xFF1A237E.toInt() else 0xFF212121.toInt()
        )

        if (onMemberClick != null) {
            holder.itemView.isClickable = true
            holder.itemView.foreground = holder.defaultForeground
            holder.itemView.setOnClickListener { onMemberClick.invoke(member) }
        } else {
            // Bukan tombol -> jangan tampilkan efek ripple/tekan sama sekali
            holder.itemView.isClickable = false
            holder.itemView.foreground = null
            holder.itemView.setOnClickListener(null)
        }
        val canKick = isParentView && onKickClick != null && member.deviceId != myDeviceId
        if (canKick) {
            holder.btnKick.visibility = View.VISIBLE
            holder.btnKick.setOnClickListener { onKickClick.invoke(member) }
        } else {
            holder.btnKick.visibility = View.GONE
            holder.btnKick.setOnClickListener(null)
        }
    }

    override fun getItemCount(): Int = members.size
}

