package com.familyguard.ui.adapter

import android.graphics.BitmapFactory
import android.util.Base64
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.familyguard.R

class UsageAppAdapter(
    private val items: List<Pair<String, Long>>,
    private val nameLookup: Map<String, String>,
    private val iconLookup: Map<String, String>
) : RecyclerView.Adapter<UsageAppAdapter.ViewHolder>() {

    class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val rank: TextView = view.findViewById(R.id.tvUsageRank)
        val icon: ImageView = view.findViewById(R.id.ivUsageIcon)
        val name: TextView = view.findViewById(R.id.tvUsageAppName)
        val duration: TextView = view.findViewById(R.id.tvUsageDuration)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_usage_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (packageName, minutes) = items[position]
        holder.rank.text = (position + 1).toString()
        holder.name.text = nameLookup[packageName] ?: packageName

        val hours = minutes / 60
        val mins = minutes % 60
        holder.duration.text = if (hours > 0) "${hours}j ${mins}m" else "${mins} menit"

        val iconB64 = iconLookup[packageName]
        if (!iconB64.isNullOrEmpty()) {
            try {
                val bytes = Base64.decode(iconB64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                holder.icon.setImageBitmap(bitmap)
            } catch (e: Exception) {
                holder.icon.setImageDrawable(null)
            }
        } else {
            holder.icon.setImageDrawable(null)
        }
    }

    override fun getItemCount(): Int = items.size
}
