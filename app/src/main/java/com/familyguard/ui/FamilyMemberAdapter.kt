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
 * Dipakai di ChildHomeActivity (read-only, myDeviceId dipakai buat label
 * "(kamu)") dan di ParentDashboardActivity (bisa DIKLIK -- lihat
 * onMemberClick & selectedDeviceId untuk kontrol pindah HP anak).
 */
class FamilyMemberAdapter(
    private var members: List<FamilyDevice> = emptyList(),
    private val myDeviceId: String? = null,
    private var selectedDeviceId: String? = null,
    private val onMemberClick: ((FamilyDevice) -> Unit)? = null
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

    /** Update baris mana yang lagi "aktif dikontrol" (dashboard ortu) tanpa
     *  perlu bikin ulang adapter dari nol -- dipanggil tiap kali tap nama
     *  anak lain di list. */
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
            // Device masih anggota keluarga (node-nya tidak dihapus), tapi
            // akunnya sengaja logout -- beda dari offline biasa (mati/no
            // internet), jadi statusnya ditulis eksplisit biar Orang Tua
            // (atau Anak lain) tidak salah kira ini cuma HP mati.
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

        // Baris yang lagi "dikontrol" ditandai beda (dipakai di dashboard ortu
        // supaya kelihatan jelas HP anak mana yang aktif dikendalikan sekarang
        // -- terutama penting kalau anaknya banyak, misal 5 anak sekaligus).
        val isSelected = member.deviceId == selectedDeviceId
        holder.itemView.setBackgroundColor(
            if (isSelected) 0xFFE8EAF6.toInt() else 0x00000000
        )
        holder.name.setTextColor(
            if (isSelected) 0xFF1A237E.toInt() else 0xFF212121.toInt()
        )

        if (onMemberClick != null) {
            holder.itemView.isClickable = true
            holder.itemView.setOnClickListener { onMemberClick.invoke(member) }
        }
    }

    override fun getItemCount(): Int = members.size
}
