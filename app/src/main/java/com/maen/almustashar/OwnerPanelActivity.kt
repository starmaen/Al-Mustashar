package com.maen.almustashar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Date

class OwnerPanelActivity : AppCompatActivity() {

    private lateinit var layoutLicensesList: LinearLayout
    private lateinit var tvLicensesEmpty: TextView
    private lateinit var etSearchLicenses: EditText
    private val allLicenses = mutableListOf<Map<String, Any?>>()
    private val dateFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_owner_panel)

        val etCustomerName = findViewById<EditText>(R.id.etCustomerName)
        val etCustomerDeviceId = findViewById<EditText>(R.id.etCustomerDeviceId)
        val etCustomerPhone = findViewById<EditText>(R.id.etCustomerPhone)
        val cbGifted = findViewById<CheckBox>(R.id.cbGifted)
        val rbMonth = findViewById<RadioButton>(R.id.rbMonth)
        val rbYear = findViewById<RadioButton>(R.id.rbYear)
        val rbLifetime = findViewById<RadioButton>(R.id.rbLifetime)
        val btnGenerate = findViewById<Button>(R.id.btnGenerate)
        val layoutResult = findViewById<LinearLayout>(R.id.layoutResult)
        val tvGeneratedCode = findViewById<TextView>(R.id.tvGeneratedCode)
        val btnCopyCode = findViewById<Button>(R.id.btnCopyCode)
        val btnShareCode = findViewById<Button>(R.id.btnShareCode)
        layoutLicensesList = findViewById(R.id.layoutLicensesList)
        tvLicensesEmpty = findViewById(R.id.tvLicensesEmpty)
        etSearchLicenses = findViewById(R.id.etSearchLicenses)

        etSearchLicenses.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                renderLicenses(s?.toString()?.trim() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        btnGenerate.setOnClickListener {
            val name = etCustomerName.text.toString().trim()
            val deviceId = etCustomerDeviceId.text.toString().trim()
            val phone = etCustomerPhone.text.toString().trim()
            val gifted = cbGifted.isChecked

            if (deviceId.isEmpty()) {
                Toast.makeText(this, "يرجى إدخال معرّف الجهاز", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val durationDays = when {
                rbMonth.isChecked -> 30
                rbYear.isChecked -> 365
                rbLifetime.isChecked -> -1
                else -> 30
            }

            val durationLabel = when (durationDays) {
                30 -> "شهر"
                365 -> "سنة"
                else -> "دائم"
            }

            val code = LicenseManager.generateCode(deviceId, durationDays)
            tvGeneratedCode.text = code
            layoutResult.visibility = View.VISIBLE

            val startDate = dateFmt.format(Date())
            val expiryDate = if (durationDays == -1) "دائم"
            else dateFmt.format(Date(System.currentTimeMillis() + durationDays * 86400L * 1000L))

            val record = hashMapOf(
                "customerName" to (if (name.isEmpty()) "غير محدد" else name),
                "phone" to phone,
                "deviceId" to deviceId.uppercase(),
                "duration" to durationLabel,
                "durationDays" to durationDays,
                "activationCode" to code,
                "startDate" to startDate,
                "expiryDate" to expiryDate,
                "gifted" to gifted,
                "createdAt" to Date()
            )

            FirebaseFirestore.getInstance().collection("licenses")
                .add(record)
                .addOnSuccessListener {
                    Toast.makeText(this, "تم توليد الكود وأرشفته في السجل بنجاح", Toast.LENGTH_SHORT).show()
                    loadLicenses()
                }
                .addOnFailureListener {
                    Toast.makeText(this, "تم توليد الكود محلياً (تعذر الحفظ بالسحاب حالياً)", Toast.LENGTH_SHORT).show()
                }
        }

        btnCopyCode.setOnClickListener {
            val code = tvGeneratedCode.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("License Code", code))
            Toast.makeText(this, "تم نسخ كود التفعيل", Toast.LENGTH_SHORT).show()
        }

        btnShareCode.setOnClickListener {
            val code = tvGeneratedCode.text.toString()
            val shareMsg = "كود تفعيل تطبيق المستشار القانوني الخاص بك هو:\n$code"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareMsg)
            }
            startActivity(Intent.createChooser(intent, "مشاركة كود التفعيل"))
        }

        loadLicenses()
    }

    private fun isExpired(expiry: String): Boolean {
        if (expiry.isBlank() || expiry == "دائم") return false
        return try {
            val exp = dateFmt.parse(expiry) ?: return false
            exp.time < System.currentTimeMillis() - 86400L * 1000L
        } catch (_: Exception) {
            false
        }
    }

    private fun loadLicenses() {
        FirebaseFirestore.getInstance().collection("licenses")
            .get()
            .addOnSuccessListener { qs ->
                allLicenses.clear()
                for (doc in qs.documents) {
                    val m = doc.data ?: continue
                    allLicenses.add(m + ("_docId" to doc.id))
                }
                allLicenses.sortByDescending { (it["createdAt"] as? com.google.firebase.Timestamp)?.toDate()?.time ?: 0L }
                renderLicenses(etSearchLicenses.text?.toString()?.trim() ?: "")
            }
            .addOnFailureListener {
                tvLicensesEmpty.text = "تعذر جلب الأرشيف (تحقق من الإنترنت وقاعدة licenses في Firestore)"
                tvLicensesEmpty.visibility = View.VISIBLE
            }
    }

    private fun renderLicenses(query: String) {
        layoutLicensesList.removeAllViews()
        val q = query.trim()
        val shown = allLicenses.filter { m ->
            q.isBlank() || listOf("customerName", "phone", "activationCode", "deviceId")
                .any { (m[it] as? String ?: "").contains(q, ignoreCase = true) }
        }
        tvLicensesEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        if (allLicenses.isNotEmpty() && shown.isEmpty()) tvLicensesEmpty.text = "لا نتائج مطابقة في الأرشيف."
        for (m in shown) {
            val name = m["customerName"] as? String ?: "-"
            val phone = m["phone"] as? String ?: ""
            val code = m["activationCode"] as? String ?: "-"
            val duration = m["duration"] as? String ?: "-"
            val start = m["startDate"] as? String ?: "-"
            val expiry = m["expiryDate"] as? String ?: "-"
            val gifted = (m["gifted"] as? Boolean) ?: false
            val expired = isExpired(expiry)
            val docId = m["_docId"] as? String ?: ""

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14, 12, 14, 12)
                setBackgroundColor(android.graphics.Color.WHITE)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(0, 0, 0, 10)
                layoutParams = params
            }
            val head = TextView(this).apply {
                text = (if (gifted) "🎁 " else "") + name + if (expired) "  ⛔ منتهي" else ""
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(
                    if (expired) android.graphics.Color.parseColor("#C62828")
                    else android.graphics.Color.parseColor("#0F2042")
                )
                gravity = Gravity.RIGHT
            }
            val info = TextView(this).apply {
                text = "📱 ${phone.ifBlank { "-" }}\n" +
                    "🔑 $code\n" +
                    "⏳ $duration | من $start إلى $expiry"
                textSize = 12.5f
                setTextColor(android.graphics.Color.parseColor("#37474F"))
                gravity = Gravity.RIGHT
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            fun smallBtn(t: String): Button = Button(this@OwnerPanelActivity).apply {
                text = t
                textSize = 11f
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
            val btnCall = smallBtn("📞").apply {
                setOnClickListener {
                    if (phone.isBlank()) {
                        Toast.makeText(this@OwnerPanelActivity, "لا رقم مسجل", Toast.LENGTH_SHORT).show()
                    } else try {
                        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                    } catch (_: Exception) {
                    }
                }
            }
            val btnWa = smallBtn("💬").apply {
                setTextColor(android.graphics.Color.parseColor("#2E7D32"))
                setOnClickListener {
                    if (phone.isBlank()) {
                        Toast.makeText(this@OwnerPanelActivity, "لا رقم مسجل", Toast.LENGTH_SHORT).show()
                    } else try {
                        var p = phone.replace(" ", "").replace("-", "")
                        if (p.startsWith("0")) p = p.substring(1)
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://api.whatsapp.com/send?phone=$p&text=${Uri.encode("كود تفعيلك: $code")}")
                            )
                        )
                    } catch (_: Exception) {
                    }
                }
            }
            val btnCopy = smallBtn("📋 نسخ").apply {
                setOnClickListener {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("License", code))
                    Toast.makeText(this@OwnerPanelActivity, "نُسخ الكود", Toast.LENGTH_SHORT).show()
                }
            }
            val btnDel = smallBtn("🗑️").apply {
                setTextColor(android.graphics.Color.parseColor("#C62828"))
                setOnClickListener {
                    if (docId.isBlank()) return@setOnClickListener
                    androidx.appcompat.app.AlertDialog.Builder(this@OwnerPanelActivity)
                        .setTitle("حذف الترخيص من الأرشيف؟")
                        .setMessage("سيُحذف سجل $name نهائياً (لا يؤثر على جهاز المرخص).")
                        .setPositiveButton("حذف") { _, _ ->
                            FirebaseFirestore.getInstance().collection("licenses").document(docId)
                                .delete().addOnSuccessListener { loadLicenses() }
                        }
                        .setNegativeButton("إلغاء", null)
                        .show()
                }
            }
            row.addView(btnDel)
            row.addView(btnCopy)
            row.addView(btnWa)
            row.addView(btnCall)
            card.addView(head)
            card.addView(info)
            card.addView(row)
            layoutLicensesList.addView(card)
        }
    }
}
