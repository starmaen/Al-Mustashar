package com.maen.almustashar

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DecisionAdapter(
    private val items: MutableList<CourtDecision>,
    private val onClick: (CourtDecision) -> Unit
) : RecyclerView.Adapter<DecisionAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvNumber: TextView = v.findViewById(R.id.tvNumber)
        val tvDate: TextView = v.findViewById(R.id.tvDate)
        val tvContent: TextView = v.findViewById(R.id.tvContent)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_decision, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val d = items[position]
        holder.tvNumber.text = "قرار رقم: ${d.number.ifEmpty { "-" }}"
        holder.tvDate.text = "التاريخ: ${d.date.ifEmpty { "-" }}"
        holder.tvContent.text = d.content.ifEmpty { "لا يوجد فحوى" }
        holder.itemView.setOnClickListener { onClick(d) }
    }

    override fun getItemCount() = items.size
}
