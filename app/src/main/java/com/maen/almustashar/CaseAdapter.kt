package com.maen.almustashar

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CaseAdapter(
    private val items: MutableList<Case>,
    private val onClick: (Case) -> Unit
) : RecyclerView.Adapter<CaseAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTitle: TextView = v.findViewById(R.id.tvCaseTitle)
        val tvCourt: TextView = v.findViewById(R.id.tvCaseCourt)
        val tvDate: TextView = v.findViewById(R.id.tvCaseDate)
        val tvClient: TextView = v.findViewById(R.id.tvCaseClient)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_case, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        holder.tvTitle.text = c.title.ifEmpty { "بدون عنوان" }
        holder.tvCourt.text = "المحكمة: ${c.court.ifEmpty { "-" }}"
        holder.tvDate.text = "التاريخ: ${c.date.ifEmpty { "-" }}"
        holder.tvClient.text = c.clientName.ifEmpty { "" }
        holder.itemView.setOnClickListener { onClick(c) }
    }

    override fun getItemCount() = items.size
}
