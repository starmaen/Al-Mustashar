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

    private lateinit var spType: Spinner
    private lateinit var etTarget: EditText
    private lateinit var etParties: EditText
    private lateinit var etFacts: EditText
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
        setContentView(R.layout.activity_legal_drafting)

        spType = findViewById(R.id.spDraftType)
        etTarget = findViewById(R.id.etTargetEntity)
        etParties = findViewById(R.id.etParties)
        etFacts = findViewById(R.id.etFactsAndDemands)
        btnAttach = findViewById(R.id.btnAttachImage)
        tvAttachStatus = findViewById(R.id.tvAttachmentStatus)
        btnGenerate = findViewById(R.id.btnGenerateDraft)
        progress = findViewById(R.id.progressDraft)
        layoutResult = findViewById(R.id.layoutResult)
        etResult = findViewById(R.id.etDraftResult)
        btnCopy = findViewById(R.id.btnCopyDraft)
        btnShare = findViewById(R.id.btnShareDraft)

        // أنواع المذكرات والاستدعاءات
        val types = arrayOf(
            "مذكرة جوابية ودفاع أمام المحكمة",
            "استدعاء إداري / بلدي (بيان قيد، شرح تنظيمي)",
            "استدعاء دعوى جديدة (لائحة ادعاء)",
            "مذكرة إبراز مستندات ودفوع تمهيدية",
            "طلب إخلاء سبيل أو استرداد حجز",
            "لائحة طعن بالاستئناف / النقض",
            "إنذار عدلي موجه عبر الكاتب بالعدل"
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)
        spType.adapter = adapter

        // تلقي بيانات الدعوى إن تم الفتح من داخل قضية
        intent.getStringExtra("case_title")?.let { title ->
            val basis = intent.getStringExtra("case_basis") ?: ""
            val court = intent.getStringExtra("case_court") ?: ""
            val parties = intent.getStringExtra("case_parties") ?: ""
            etTarget.setText(court)
            etParties.setText("الدعوى: $title | أساس: $basis | الأطراف: $parties")
        }

        btnAttach.setOnClickListener {
            imagePicker.launch("image/*")
        }

        btnGenerate.setOnClickListener {
            generateDraft()
        }

        btnCopy.setOnClickListener {
            val text = etResult.text.toString()
            if (text.isNotEmpty()) {
                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("LegalDraft", text))
                Toast.makeText(this, "تم نسخ المذكرة للحافظة بنجاح", Toast.LENGTH_SHORT).show()
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
    }

    private fun processImageUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val bmp = BitmapFactory.decodeStream(stream)
                val bos = ByteArrayOutputStream()
                // ضغط خفيف لضمان سرعة الإرسال والاستجابة
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos)
                val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                attachedImagesBase64.clear()
                attachedImagesBase64.add(Pair(b64, "image/jpeg"))
                tvAttachStatus.text = "تم إرفاق صورة المستند بنجاح ✓"
                tvAttachStatus.setTextColor(android.graphics.Color.parseColor("#2E7D32"))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "فشل تجهيز الصورة: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun generateDraft() {
        val selectedType = spType.selectedItem.toString()
        val target = etTarget.text.toString().trim()
        val parties = etParties.text.toString().trim()
        val facts = etFacts.text.toString().trim()

        if (facts.isEmpty() && attachedImagesBase64.isEmpty()) {
            Toast.makeText(this, "يرجى كتابة وقائع الطلب أو إرفاق مستند", Toast.LENGTH_SHORT).show()
            return
        }

        val promptBuilder = StringBuilder()
        promptBuilder.append("نوع الطلب: $selectedType\n")
        if (target.isNotEmpty()) promptBuilder.append("الجهة الموجه إليها: $target\n")
        if (parties.isNotEmpty()) promptBuilder.append("أطراف العلاقة: $parties\n")
        promptBuilder.append("الوقائع والمطالب:\n$facts\n")

        progress.visibility = View.VISIBLE
        btnGenerate.isEnabled = false
        layoutResult.visibility = View.GONE

        lifecycleScope.launch {
            val result = AIClient.draftLegalDocument(promptBuilder.toString(), attachedImagesBase64)
            progress.visibility = View.GONE
            btnGenerate.isEnabled = true
            layoutResult.visibility = View.VISIBLE
            etResult.setText(result)
        }
    }
}
