package com.familyguard.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.familyguard.databinding.ItemAppBinding
import com.familyguard.model.AppInfo

class AppListAdapter(
    private val onLockToggle: (AppInfo, Boolean) -> Unit,
    private val onNotifToggle: (AppInfo, Boolean) -> Unit
) : ListAdapter<AppInfo, AppListAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(app: AppInfo) {
            val context = binding.root.context
            val pm = context.packageManager

            val icon = app.icon
                ?: app.iconBase64?.let {
                    com.familyguard.utils.InstalledAppsHelper.base64ToDrawable(context, it)
                }
                ?: try {
                    pm.getApplicationIcon(app.packageName)
                } catch (e: Exception) {
                    androidx.core.content.ContextCompat.getDrawable(context, com.familyguard.R.drawable.ic_family)
                }

            binding.ivAppIcon.setImageDrawable(icon)
            binding.tvAppName.text = app.appName
            binding.tvPackageName.text = app.packageName

            binding.switchLock.setOnCheckedChangeListener(null)
            binding.switchNotif.setOnCheckedChangeListener(null)

            binding.switchLock.isChecked = app.isLocked
            binding.switchNotif.isChecked = app.isNotifBlocked

            binding.switchLock.setOnCheckedChangeListener { _, isChecked ->
                app.isLocked = isChecked
                onLockToggle(app, isChecked)
            }

            binding.switchNotif.setOnCheckedChangeListener { _, isChecked ->
                app.isNotifBlocked = isChecked
                onNotifToggle(app, isChecked)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(old: AppInfo, new: AppInfo) = old.packageName == new.packageName
        override fun areContentsTheSame(old: AppInfo, new: AppInfo) =
            old.isLocked == new.isLocked &&
                    old.isNotifBlocked == new.isNotifBlocked &&
                    old.iconBase64 == new.iconBase64
    }
}