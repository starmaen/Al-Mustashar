package com.maen.almustashar
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
class QueryAdapter(private val items: MutableList<Query>, private val onClick: (Query) -> Unit) : RecyclerView.Adapter<QueryAdapter.VH>() {
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvQuestion: TextView = v.findViewById(R.id.tvQueryQuestion)
        val tvPreview: TextView = v.findViewById(R.id.tvQueryAnswerPreview)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_query, parent, false)
        return VH(v)
    }
    override fun onBindViewHolder(holder: VH, position: Int) {
        val q = items[position]
        holder.tvQuestion.text = q.question
        holder.tvPreview.text = if (q.answer.length > 100) q.answer.take(100) + "..." else q.answer
        holder.itemView.setOnClickListener { onClick(q) }
    }
    override fun getItemCount() = items.size
}
