package com.maen.almustashar

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class DriveLawFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val webViewLink: String?,
    val webContentLink: String?
)

class DriveLawAdapter(
    private var items: List<DriveLawFile> = emptyList()
) : RecyclerView.Adapter<DriveLawAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvDriveFileName)
        val tvSubtitle: TextView = view.findViewById(R.id.tvDriveFileSubtitle)
        val btnOpen: Button = view.findViewById(R.id.btnOpenDriveFile)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_drive_law, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name
        holder.tvSubtitle.text = "مستند أصلي موثق (Google Drive)"
        
        holder.btnOpen.setOnClickListener {
            val link = item.webViewLink ?: item.webContentLink
            link?.let { url ->
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                holder.itemView.context.startActivity(browserIntent)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newList: List<DriveLawFile>) {
        items = newList
        notifyDataSetChanged()
    }
}
