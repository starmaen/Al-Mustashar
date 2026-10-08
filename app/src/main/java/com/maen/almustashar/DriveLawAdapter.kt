package com.maen.almustashar

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class DriveLawAdapter : ListAdapter<DriveLawFile, DriveLawAdapter.DriveViewHolder>(DiffCallback) {

    class DriveViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tvDriveFileName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DriveViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_drive_law, parent, false)
        return DriveViewHolder(view)
    }

    override fun onBindViewHolder(holder: DriveViewHolder, position: Int) {
        val item = getItem(position)
        holder.tvName.text = item.name

        holder.itemView.setOnClickListener {
            val context = holder.itemView.context
            val url = item.webViewLink ?: item.webContentLink ?: "https://drive.google.com/drive/folders/1sPjdzMBeun-H-P5gSTujESzdMR0SpMm3"
            
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                val pm = context.packageManager
                val resolveInfos = pm.queryIntentActivities(browserIntent, PackageManager.MATCH_DEFAULT_ONLY)
                val nonDrive = resolveInfos.firstOrNull { 
                    !it.activityInfo.packageName.contains("com.google.android.apps.docs") &&
                    !it.activityInfo.packageName.contains("drive")
                }
                if (nonDrive != null) {
                    browserIntent.setPackage(nonDrive.activityInfo.packageName)
                    context.startActivity(browserIntent)
                } else {
                    context.startActivity(Intent.createChooser(browserIntent, "فتح عبر المتصفح"))
                }
            } catch (_: Exception) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<DriveLawFile>() {
        override fun areItemsTheSame(oldItem: DriveLawFile, newItem: DriveLawFile): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: DriveLawFile, newItem: DriveLawFile): Boolean = oldItem == newItem
    }
}
