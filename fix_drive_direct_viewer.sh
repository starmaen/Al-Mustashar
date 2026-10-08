#!/bin/bash
set -e

# 1. تحديث دالة فتح الرابط لتستخدم رابط المعاينة الصريح وتمنع تطبيق درايف تماماً
cat << 'KOTLIN_ADAPTER' > app/src/main/java/com/maen/almustashar/DriveLawAdapter.kt
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
KOTLIN_ADAPTER

# 2. تحديث دالة فتح زر PDF في SearchActivity.kt بنفس الآلية المانعة لتطبيق درايف
python3 - << 'PYEOF'
path = "app/src/main/java/com/maen/almustashar/SearchActivity.kt"
with open(path, "r", encoding="utf-8") as f:
    c = f.read()

import re
old_func = """    private fun openDirectInBrowserOnly(url: String) {
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val pm = packageManager
            val resolveInfos = pm.queryIntentActivities(browserIntent, PackageManager.MATCH_DEFAULT_ONLY)
            val nonDriveBrowser = resolveInfos.firstOrNull { 
                !it.activityInfo.packageName.contains("com.google.android.apps.docs") &&
                !it.activityInfo.packageName.contains("drive")
            }

            if (nonDriveBrowser != null) {
                browserIntent.setPackage(nonDriveBrowser.activityInfo.packageName)
                startActivity(browserIntent)
            } else {
                startActivity(Intent.createChooser(browserIntent, "فتح الرابط عبر المتصفح"))
            }
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(this, "تعذر فتح الرابط", Toast.LENGTH_SHORT).show()
            }
        }
    }"""

new_func = """    private fun openDirectInBrowserOnly(url: String) {
        var target = url
        if (target.contains("/view")) {
            target = target.replace("/view", "/preview")
        }
        try {
            val customTabs = androidx.browser.customtabs.CustomTabsIntent.Builder().setShowTitle(true).build()
            customTabs.intent.setPackage("com.android.chrome")
            customTabs.launchUrl(this, Uri.parse(target))
        } catch (_: Exception) {
            try {
                val customTabs = androidx.browser.customtabs.CustomTabsIntent.Builder().setShowTitle(true).build()
                customTabs.launchUrl(this, Uri.parse(target))
            } catch (_: Exception) {
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
                        addCategory(Intent.CATEGORY_BROWSABLE)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(browserIntent)
                } catch (e: Exception) {
                    Toast.makeText(this, "تعذر فتح الرابط", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }"""

if "openDirectInBrowserOnly" in c:
    c = re.sub(r'private fun openDirectInBrowserOnly[\s\S]*?\n    \}', new_func.strip(), c)
    with open(path, "w", encoding="utf-8") as f:
        f.write(c)
    print("SearchActivity updated successfully.")
PYEOF

# 3. رفع التعديلات مباشرة
git add app/src/main/java/com/maen/almustashar/DriveLawAdapter.kt app/src/main/java/com/maen/almustashar/SearchActivity.kt
git commit -m "fix(drive): force Chrome CustomTabs with preview mode to strictly bypass drive app"
git push

echo "🚀 تم رفع التعديل! الملفات ستفتح الآن في متصفح فوري دون تطبيق درايف ودون طلب أي حساب."
