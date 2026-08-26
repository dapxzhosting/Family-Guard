package com.familyguard.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.familyguard.R
import com.familyguard.sync.FamilyDevice
import com.familyguard.utils.AppLockPrefs

/**
 * Menampilkan daftar anggota keluarga (semua device yang terdaftar di
 * families/{code}/devices) dengan ikon berbeda untuk Orang Tua/Anak,
 * nama masing-masing, dan status online/offline.
 *
 * Dipakai di ChildHomeActivity supaya anak juga bisa lihat siapa saja
 * yang tergabung di keluarganya (sebelumnya cuma ada di dashboard ortu).
 */
class FamilyMemberAdapter(
    private var members: List<FamilyDevice> = emptyList(),
    private val myDeviceId: String? = null
) : RecyclerView.Adapter<FamilyMemberAdapter.ViewHolder>() {

    class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val icon: android.widget.ImageView = view.findViewById(R.id.ivMemberIcon)
        val name: android.widget.TextView = view.findViewById(R.id.tvMemberName)
        val role: android.widget.TextView = view.findViewById(R.id.tvMemberRole)
        val dot: android.view.View = view.findViewById(R.id.dotMemberOnline)
    }

    fun submitList(newMembers: List<FamilyDevice>) {
        members = newMembers
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_family_member, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val member = members[position]
        val context = holder.itemView.context
        val isParent = member.role == AppLockPrefs.ROLE_PARENT

        holder.icon.setImageResource(if (isParent) R.drawable.ic_role_parent else R.drawable.ic_role_child)
        holder.role.text = if (isParent) "Orang Tua" else "Anak"
        val baseName = member.userName?.takeIf { it.isNotBlank() }
            ?: if (isParent) "Orang Tua" else "Anak"
        holder.name.text = if (member.deviceId == myDeviceId) "$baseName (kamu)" else baseName
        holder.dot.setBackgroundResource(
            if (member.online) R.drawable.dot_online else R.drawable.dot_offline
        )
    }

    override fun getItemCount(): Int = members.size
}
