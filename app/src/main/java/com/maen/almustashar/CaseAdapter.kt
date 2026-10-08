package com.maen.almustashar

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

class CaseAdapter(
    private val items: MutableList<Case>,
    private val onClick: (Case) -> Unit
) : RecyclerView.Adapter<CaseAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTitle: TextView = v.findViewById(R.id.tvCaseTitle)
        val tvStatus: TextView = v.findViewById(R.id.tvCaseStatusBadge)
        val tvCourtAndBasis: TextView = v.findViewById(R.id.tvCaseCourtAndBasis)
        val tvParties: TextView = v.findViewById(R.id.tvParties)
        val layoutNextSession: LinearLayout = v.findViewById(R.id.layoutNextSession)
        val tvNextSessionDate: TextView = v.findViewById(R.id.tvNextSessionDate)
        val tvNextSessionRequired: TextView = v.findViewById(R.id.tvNextSessionRequired)
        val layoutClientActions: LinearLayout = v.findViewById(R.id.layoutClientActions)
        val btnCall: Button = v.findViewById(R.id.btnCallClient)
        val btnWhatsApp: Button = v.findViewById(R.id.btnWhatsAppClient)
        val tvCreatedAt: TextView = v.findViewById(R.id.tvCreatedAt)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_case, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        val context = holder.itemView.context

        holder.tvTitle.text = c.title.ifEmpty { "دعوى بدون عنوان" }
        holder.tvStatus.text = c.status.ifEmpty { "قيد النظر" }

        // صياغة المحكمة ورقم الأساس
        val basisStr = if (c.basisNumber.isNotEmpty()) "${c.basisNumber} / ${c.caseYear}" else "-"
        val courtStr = if (c.court.isNotEmpty()) "${c.court} (${c.chamber})" else "-"
        holder.tvCourtAndBasis.text = "⚖️ المحكمة: $courtStr | أساس: $basisStr"

        // صياغة أطراف الخصومة
        val clientRoleStr = if (c.clientRole.isNotEmpty()) " (${c.clientRole})" else ""
        val clientStr = if (c.clientName.isNotEmpty()) "${c.clientName}$clientRoleStr" else "-"
        val oppStr = if (c.opponentName.isNotEmpty()) c.opponentName else "-"
        holder.tvParties.text = "👤 الموكل: $clientStr ضد: $oppStr"

        // موعد الجلسة القادمة
        if (c.nextSessionDate.isNotEmpty()) {
            holder.layoutNextSession.visibility = View.VISIBLE
            holder.tvNextSessionDate.text = "📅 موعد الجلسة القادمة: ${c.nextSessionDate}"
            if (c.nextSessionRequired.isNotEmpty()) {
                holder.tvNextSessionRequired.visibility = View.VISIBLE
                holder.tvNextSessionRequired.text = "المطلوب: ${c.nextSessionRequired}"
            } else {
                holder.tvNextSessionRequired.visibility = View.GONE
            }
        } else {
            holder.layoutNextSession.visibility = View.GONE
        }

        // تاريخ افتتاح الدعوى
        holder.tvCreatedAt.text = if (c.date.isNotEmpty()) "افتتاح: ${c.date}" else ""

        // إدارة أزرار الاتصال والواتساب
        val phone = c.clientPhone.trim()
        if (phone.isNotEmpty()) {
            holder.btnCall.visibility = View.VISIBLE
            holder.btnWhatsApp.visibility = View.VISIBLE

            holder.btnCall.setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "تعذر فتح لوحة الاتصال", Toast.LENGTH_SHORT).show()
                }
            }

            holder.btnWhatsApp.setOnClickListener {
                openWhatsApp(context, phone, c)
            }
        } else {
            holder.btnCall.visibility = View.GONE
            holder.btnWhatsApp.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onClick(c) }
    }

    private fun openWhatsApp(context: Context, rawPhone: String, c: Case) {
        try {
            var phone = rawPhone.replace(" ", "").replace("-", "")
            if (phone.startsWith("0")) {
                phone = phone.substring(1) // تحويل للأرقام الدولية إن لزم
            }
            val msg = "تحية طيبة أستاذ ${c.clientName}، نود تذكيركم بموعد جلستكم القادمة بتاريخ ${c.nextSessionDate} في محكمة ${c.court} برقم أساس (${c.basisNumber}). مع خالص التقدير."
            val url = "https://api.whatsapp.com/send?phone=$phone&text=${Uri.encode(msg)}"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "تعذر فتح واتساب: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun getItemCount() = items.size
}
