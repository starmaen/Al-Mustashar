package com.maen.almustashar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class LegalDraftingActivity : AppCompatActivity() {

    private lateinit var etHeader: EditText
    private lateinit var etFacts: EditText
    private lateinit var etFooter: EditText
    private lateinit var btnAttach: Button
    private lateinit var tvAttachStatus: TextView
    private lateinit var btnGenerate: Button
    private lateinit var progress: ProgressBar
    private lateinit var layoutResult: LinearLayout
    private lateinit var etResult: EditText
    private lateinit var btnCopy: Button
    private lateinit var btnShare: Button

    private val attachedImagesBase64 = mutableListOf<Pair<String, String>>()

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { processImageUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_legal_drafting)

            etHeader = findViewById(R.id.etCustomHeader)
            etFacts = findViewById(R.id.etFactsAndDemands)
            etFooter = findViewById(R.id.etCustomFooter)
            btnAttach = findViewById(R.id.btnAttachImage)
            tvAttachStatus = findViewById(R.id.tvAttachmentStatus)
            btnGenerate = findViewById(R.id.btnGenerateDraft)
            progress = findViewById(R.id.progressDraft)
            layoutResult = findViewById(R.id.layoutResult)
            etResult = findViewById(R.id.etDraftResult)
            btnCopy = findViewById(R.id.btnCopyDraft)
            btnShare = findViewById(R.id.btnShareDraft)

            // توليد الترويسة والخاتمة التلقائية بناءً على ملف القضية
            intent?.let {
                val title = it.getStringExtra("case_title") ?: ""
                val basis = it.getStringExtra("case_basis") ?: ""
                val court = it.getStringExtra("case_court") ?: ""
                val cName = it.getStringExtra("client_name") ?: ""
                val cRole = it.getStringExtra("client_role") ?: "مدعٍ"
                val opp = it.getStringExtra("opponent_name") ?: ""

                val courtHeader = if (court.isNotBlank()) "إلى $court الموقرة\n" else "إلى المحكمة الموقرة\n"
                val partiesHeader = StringBuilder(courtHeader)
                if (cName.isNotBlank()) partiesHeader.append("المستدعي / الجهة ال${cRole}: $cName (يمثلها الوكيل المحامي)\n")
                if (opp.isNotBlank()) partiesHeader.append("الجهة المدعى عليها: $opp\n")
                if (basis.isNotBlank()) partiesHeader.append("الدعوى: $title | أساس: $basis\n")
                partiesHeader.append("الموضوع: مذكرة في الدعوى الماثلة")

                etHeader.setText(partiesHeader.toString())

                // خاتمة افتراضية رصينة
                val defaultFooter = "بكل تحفظ واحترام\nالوكيل المحامي عن الجهة ال${cRole}"
                etFooter.setText(defaultFooter)
            }

            btnAttach.setOnClickListener {
                imagePicker.launch("image/*")
            }

            findViewById<Button>(R.id.btnTemplates)?.setOnClickListener { showTemplates() }

            btnGenerate.setOnClickListener {
                generateAndMergeDraft()
            }

            btnCopy.setOnClickListener {
                val text = etResult.text.toString()
                if (text.isNotEmpty()) {
                    val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.setPrimaryClip(ClipData.newPlainText("LegalDraft", text))
                    Toast.makeText(this, "تم نسخ المذكرة بالكامل للحافظة", Toast.LENGTH_SHORT).show()
                }
            }

            btnShare.setOnClickListener {
                val text = etResult.text.toString()
                if (text.isNotEmpty()) {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    startActivity(Intent.createChooser(share, "مشاركة المذكرة القضائية عبر:"))
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "خطأ في تهيئة الشاشة: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showTemplates() {
        val names = arrayOf("📝 مذكرة جوابية (رد على الخصم)", "📨 استدعاء رسمي", "⏳ طلب مهلة وإبراز مستندات")
        val bodies = arrayOf(
            "الغرض: الرد على مذكرة الخصم ودحض دفوعه.\nالوقائع: [اكتب وقائع الدعوى باختصار].\nدفوع الخصم المطلوب الرد عليها: [انسخها هنا].\nالمطلوب: تفنيد كل دفع بسنده القانوني السوري، ثم طلب رد الدعوى شكلاً وموضوعاً.",
            "الغرض: استدعاء رسمي إلى [المحكمة/البلدية/الدائرة].\nالموضوع: [اكتبه].\nالوقائع والمستندات المرفقة: [اذكرها].\nالمطلوب: اتخاذ الإجراء اللازم أصولاً.",
            "الغرض: طلب مهلة.\nالسبب: [الاطلاع على الملف / إبراز حجة حصر إرث / سند تمليك / ...].\nالمطلوب: منح مهلة مناسبة مع قبول الطلب شكلاً."
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("اختر قالباً (يُدرج في خانة الوقائع)")
            .setItems(names) { _, which ->
                val cur = etFacts.text.toString()
                etFacts.setText(if (cur.isBlank()) bodies[which] else cur + "\n\n" + bodies[which])
                Toast.makeText(this, "أُدرج القالب — خصصه ثم اضغط الصياغة", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun processImageUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val bmp = BitmapFactory.decodeStream(stream)
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                attachedImagesBase64.clear()
                attachedImagesBase64.add(Pair(b64, "image/jpeg"))
                tvAttachStatus.text = "تم إرفاق صورة المستند بنجاح ✓"
                tvAttachStatus.setTextColor(android.graphics.Color.parseColor("#2E7D32"))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "فشل تجهيز المرفق: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun generateAndMergeDraft() {
        val header = etHeader.text.toString().trim()
        val facts = etFacts.text.toString().trim()
        val footer = etFooter.text.toString().trim()

        if (facts.isEmpty() && attachedImagesBase64.isEmpty()) {
            Toast.makeText(this, "يرجى كتابة موضوع ووقائع المذكرة للذكاء الاصطناعي", Toast.LENGTH_SHORT).show()
            return
        }

        progress.visibility = View.VISIBLE
        btnGenerate.isEnabled = false
        layoutResult.visibility = View.GONE

        // توجيه الذكاء الاصطناعي لصياغة متن المذكرة فقط
        val prompt = "صغ صلب ومتن المذكرة القضائية/الاستدعاء حصراً (من حيث الوقائع، الأسانيد القانونية السورية، والطلبات) دون كتابة ترويسة أو خاتمة:\n$facts"

        lifecycleScope.launch {
            try {
                val aiBody = AIClient.draftLegalDocument(prompt, attachedImagesBase64)
                
                // دمج الأجزاء الثلاثة بنص واحد متناسق
                val fullDocument = StringBuilder()
                if (header.isNotEmpty()) {
                    fullDocument.append(header).append("\n\n-------------------------\n\n")
                }
                fullDocument.append(aiBody)
                if (footer.isNotEmpty()) {
                    fullDocument.append("\n\n-------------------------\n\n").append(footer)
                }

                progress.visibility = View.GONE
                btnGenerate.isEnabled = true
                layoutResult.visibility = View.VISIBLE
                etResult.setText(fullDocument.toString())
            } catch (e: Exception) {
                progress.visibility = View.GONE
                btnGenerate.isEnabled = true
                Toast.makeText(this@LegalDraftingActivity, "خطأ: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
