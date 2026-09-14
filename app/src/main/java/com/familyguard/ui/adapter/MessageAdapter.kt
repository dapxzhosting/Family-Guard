package com.familyguard.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.familyguard.R
import com.familyguard.sync.FamilyLink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter(
    private var items: List<FamilyLink.ChatMessage> = emptyList(),
    private val onClick: (FamilyLink.ChatMessage) -> Unit,
    private val onDelete: (FamilyLink.ChatMessage) -> Unit
) : RecyclerView.Adapter<MessageAdapter.ViewHolder>() {

    private val timeFormat = SimpleDateFormat("dd MMM, HH:mm", Locale("id", "ID"))

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dot: View = view.findViewById(R.id.dotUnreadMessage)
        val from: TextView = view.findViewById(R.id.tvMessageFrom)
        val time: TextView = view.findViewById(R.id.tvMessageTime)
        val title: TextView = view.findViewById(R.id.tvMessageTitle)
        val body: TextView = view.findViewById(R.id.tvMessageBody)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteMessage)
    }

    fun submitList(newItems: List<FamilyLink.ChatMessage>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val msg = items[position]
        holder.from.text = msg.fromName
        holder.title.text = msg.title
        holder.body.text = msg.body
        holder.time.text = if (msg.timestamp > 0) timeFormat.format(Date(msg.timestamp)) else ""
        holder.dot.visibility = if (msg.read) View.INVISIBLE else View.VISIBLE

        holder.itemView.setOnClickListener { onClick(msg) }
        holder.btnDelete.setOnClickListener { onDelete(msg) }
    }

    override fun getItemCount(): Int = items.size
}
