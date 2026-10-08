package com.maen.almustashar

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
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
            var rawUrl = item.webViewLink ?: item.webContentLink ?: "https://drive.google.com/drive/folders/1sPjdzMBeun-H-P5gSTujESzdMR0SpMm3"

            // تحويل رابط view إلى preview لمنع فتح تطبيق Drive الداخلي
            if (rawUrl.contains("/view")) {
                rawUrl = rawUrl.replace("/view", "/preview")
            }

            try {
                // الفتح عبر CustomTabsIntent يضمن الفتح في المتصفح المدمج بدون طلب حسابات درايف
                val customTabs = CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                customTabs.intent.setPackage("com.android.chrome")
                customTabs.launchUrl(context, Uri.parse(rawUrl))
            } catch (_: Exception) {
                try {
                    val customTabsFallback = CustomTabsIntent.Builder().setShowTitle(true).build()
                    customTabsFallback.launchUrl(context, Uri.parse(rawUrl))
                } catch (_: Exception) {
                    try {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(rawUrl)).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(browserIntent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "تعذر فتح المستند", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<DriveLawFile>() {
        override fun areItemsTheSame(oldItem: DriveLawFile, newItem: DriveLawFile): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: DriveLawFile, newItem: DriveLawFile): Boolean = oldItem == newItem
    }
}
