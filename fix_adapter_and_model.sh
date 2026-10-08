#!/bin/bash
set -e

# 1. إنشاء ملف كلاس البيانات DriveLawFile.kt
cat << 'KOTLIN_MODEL' > app/src/main/java/com/maen/almustashar/DriveLawFile.kt
package com.maen.almustashar

data class DriveLawFile(
    val id: String,
    val name: String,
    val mimeType: String? = "application/pdf",
    val webViewLink: String? = null,
    val webContentLink: String? = null
)
KOTLIN_MODEL

# 2. تصحيح مهايئ العناصر DriveLawAdapter.kt ليعتمد على المعرفات الموجودة فقط
cat << 'KOTLIN_ADAPTER' > app/src/main/java/com/maen/almustashar/DriveLawAdapter.kt
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
KOTLIN_ADAPTER

# 3. التأكد من ملف تخطيط العنصر item_drive_law.xml
cat << 'XML_LAYOUT' > app/src/main/res/layout/item_drive_law.xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:padding="12dp"
    android:gravity="center_vertical"
    android:background="?android:attr/selectableItemBackground">

    <TextView
        android:id="@+id/tvDriveFileName"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:textColor="#1A237E"
        android:textSize="15sp"
        android:textStyle="bold"
        android:gravity="right" />

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="📄 PDF"
        android:textColor="#D32F2F"
        android:textSize="14sp"
        android:textStyle="bold"
        android:layout_marginStart="8dp" />

</LinearLayout>
XML_LAYOUT

# 4. رفع التعديلات فوراً إلى GitHub
git add app/src/main/java/com/maen/almustashar/DriveLawFile.kt \
        app/src/main/java/com/maen/almustashar/DriveLawAdapter.kt \
        app/src/main/res/layout/item_drive_law.xml
git commit -m "fix(drive): add DriveLawFile data class and align adapter view bindings"
git push

echo "✅ تم حل النقص البرمجي ورفع الكود بنجاح تام."
