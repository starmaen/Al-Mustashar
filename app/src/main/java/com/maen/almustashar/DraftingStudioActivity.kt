package com.maen.almustashar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

// استوديو الصياغة العامة (نافذة مستقلة): مذكرات وطعون واستشارات وطلبات.
// لا علاقة له بشاشة صياغة الدعوى الخاصة (LegalDraftingActivity) — تعمل كما هي.
class DraftingStudioActivity : AppCompatActivity() {

    private lateinit var etTitle: EditText
    private lateinit var spinnerType: Spinner
    private lateinit var etHeader: EditText
    private lateinit var etFacts: EditText
    private lateinit var etFooter: EditText
    private lateinit var btnAttachImage: Button
    private lateinit var btnAttachText: Button
    private lateinit var tvAttachStatus: TextView
    private lateinit var btnImportCase: Button
    private lateinit var btnGroundLaws: Button
    private lateinit var tvGroundStatus: TextView
    private lateinit var etTargetLines: EditText
    private lateinit var etResult: EditText
    private lateinit var btnGenerate: Button
    private lateinit var progress: ProgressBar
    private lateinit var layoutResult: LinearLayout

    private val docTypes = listOf(
        "📝 مذكرة جوابية",
        "📨 استدعاء رسمي",
        "⚖️ طعن بالاستئناف",
        "⚖️ طعن بالنقض",
        "💡 استشارة قانونية",
        "📋 طلب (مهلة/إبراز/خبرة)",
        "⚠️ إنذار عدلي",
        "📄 عقد / اتفاق"
    )

    private val typePrompts = mapOf(
        0 to "مذكرة جوابية ترد على دفوع الخصم وتفندها واحداً واحداً بسند قانوني سوري، وتنتهي بطلب رد الدعوى شكلاً وموضوعاً.",
        1 to "استدعاء رسمي موجه للجهة المختصة بصيغة رسمية مختصرة: الموضوع ثم الوقائع ثم المطلوب.",
        2 to "طعن بالاستئناف: أسباب الاستئناف مرتبة (خطأ في تطبيق القانون، فساد الاستدلال، بطلان الإجراءات) مع طلب فسخ الحكم.",
        3 to "طعن بالنقض: أسباب النقض الشكلية والموضوعية بدقة (مخالفة القانون، الخطأ في تطبيقه، بطلان الحكم) مع طلب النقض.",
        4 to "استشارة قانونية مسببة: الرأي مدعوماً بالنصوص والاجتهادات، مع التنبيه للمخاطر والبدائل.",
        5 to "طلب إجرائي (مهلة/إبراز/خبرة): السبب الموجب ثم المطلوب بصيغة مهذبة مختصرة.",
        6 to "إنذار عدلي عن طريق الكاتب بالعدل: الوقائع ثم الإنذار بالتصرف خلال مهلة محددة مع التحفظ باللجوء للقضاء.",
        7 to "عقد/اتفاق: ديباجة ثم بنود مرقمة (الأطراف، المحل، المدة، البدل، الالتزامات، فسخ، اختصاص قضائي) بلغة محكمة."
    )

    private val attachedImagesBase64 = mutableListOf<Pair<String, String>>()
    private val attachedTexts = mutableListOf<Pair<String, String>>()
    private var groundText: String = ""
    private var fontSize = 14f
    private var fontColor = Color.parseColor("#0F2042")

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { processImageUri(it) } }

    private val textPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { processTextUri(it) } }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.openOutputStream(it)?.use { os ->
                    os.write(etResult.text.toString().toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this, "صُدّر النص", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "فشل التصدير: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_drafting_studio)

            etTitle = findViewById(R.id.etDraftTitle)
            spinnerType = findViewById(R.id.spinnerDocType)
            etHeader = findViewById(R.id.etCustomHeader)
            etFacts = findViewById(R.id.etFactsAndDemands)
            etFooter = findViewById(R.id.etCustomFooter)
            btnAttachImage = findViewById(R.id.btnAttachImage)
            btnAttachText = findViewById(R.id.btnAttachText)
            tvAttachStatus = findViewById(R.id.tvAttachmentStatus)
            btnImportCase = findViewById(R.id.btnImportCase)
            btnGroundLaws = findViewById(R.id.btnGroundLaws)
            tvGroundStatus = findViewById(R.id.tvGroundStatus)
            etTargetLines = findViewById(R.id.etTargetLines)
            etResult = findViewById(R.id.etDraftResult)
            btnGenerate = findViewById(R.id.btnGenerateDraft)
            progress = findViewById(R.id.progressDraft)
            layoutResult = findViewById(R.id.layoutResult)

            spinnerType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, docTypes)
            etFooter.setText("بكل تحفظ واحترام\nالوكيل المحامي")
            applyResultStyle()

            btnAttachImage.setOnClickListener { imagePicker.launch("image/*") }
            btnAttachText.setOnClickListener { textPicker.launch(arrayOf("text/*", "application/json", "*/*")) }
            btnImportCase.setOnClickListener { showImportCaseDialog() }
            btnGroundLaws.setOnClickListener { toggleGroundLaws() }

            findViewById<Button>(R.id.btnTemplates)?.setOnClickListener { showTemplates() }
            findViewById<Button>(R.id.btnFontSmaller)?.setOnClickListener {
                fontSize = (fontSize - 1f).coerceAtLeast(11f)
                applyResultStyle()
            }
            findViewById<Button>(R.id.btnFontBigger)?.setOnClickListener {
                fontSize = (fontSize + 1f).coerceAtMost(22f)
                applyResultStyle()
            }
            findViewById<Button>(R.id.btnFontColor)?.setOnClickListener { showColorPicker() }

            btnGenerate.setOnClickListener { generate() }
            findViewById<Button>(R.id.btnCopyDraft).setOnClickListener { copyResult() }
            findViewById<Button>(R.id.btnShareDraft).setOnClickListener { shareResult() }
            findViewById<Button>(R.id.btnSaveDraft).setOnClickListener { saveDraft() }
            findViewById<Button>(R.id.btnExportDraft).setOnClickListener {
                if (etResult.text.isNullOrBlank()) {
                    Toast.makeText(this, "ولّد النص أولاً", Toast.LENGTH_SHORT).show()
                } else {
                    val safe = etTitle.text.toString().trim().ifBlank { "مذكرة" }
                        .replace(Regex("[\\\\/:*?\"<>|]"), "-").take(40)
                    exportLauncher.launch("$safe.txt")
                }
            }
            findViewById<Button>(R.id.btnPrintDraft).setOnClickListener { printDraft() }
            findViewById<Button>(R.id.btnMyDrafts).setOnClickListener { showDrafts() }
        } catch (e: Exception) {
            Toast.makeText(this, "خطأ في تهيئة الشاشة: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun applyResultStyle() {
        try {
            etResult.textSize = fontSize
            etResult.setTextColor(fontColor)
        } catch (_: Exception) {
        }
    }

    private fun showColorPicker() {
        val names = arrayOf("كحلي", "أسود", "أخضر داكن", "عنابي", "أزرق", "رمادي داكن")
        val values = intArrayOf(
            Color.parseColor("#0F2042"), Color.BLACK, Color.parseColor("#1B5E20"),
            Color.parseColor("#6A1B1B"), Color.parseColor("#0D47A1"), Color.parseColor("#37474F")
        )
        AlertDialog.Builder(this)
            .setTitle("🎨 لون الخط")
            .setItems(names) { _, which ->
                fontColor = values[which]
                applyResultStyle()
            }
            .show()
    }

    private fun processImageUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val bmp: Bitmap = BitmapFactory.decodeStream(stream) ?: return
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                attachedImagesBase64.clear()
                attachedImagesBase64.add(Pair(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP), "image/jpeg"))
                updateAttachStatus()
                Toast.makeText(this, "تم إرفاق صورة المستند ✓", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "فشل تجهيز الصورة: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processTextUri(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            if (bytes.size > 300 * 1024) {
                Toast.makeText(this, "الملف كبير (أكثر من 300KB) — الصق مقتطفاً منه", Toast.LENGTH_LONG).show()
                return
            }
            var text = ""
            for (enc in listOf("UTF-8", "windows-1256", "ISO-8859-6")) {
                try {
                    text = String(bytes, charset(enc)).trim()
                    if (text.isNotBlank()) break
                } catch (_: Exception) {
                }
            }
            if (text.isBlank()) {
                Toast.makeText(this, "تعذر قراءة الملف كنص (جرّب صورة للمستندات المصورة)", Toast.LENGTH_LONG).show()
                return
            }
            attachedTexts.add(Pair("مرفق نصي", text.take(6000)))
            updateAttachStatus()
            Toast.makeText(this, "تم إرفاق النص ✓", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "فشل قراءة الملف: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateAttachStatus() {
        val parts = mutableListOf<String>()
        if (attachedImagesBase64.isNotEmpty()) parts.add("صورة مستند (${attachedImagesBase64.size})")
        if (attachedTexts.isNotEmpty()) parts.add("ملفات نصية (${attachedTexts.size})")
        if (groundText.isNotBlank()) parts.add("تأريض بالمواد")
        tvAttachStatus.text = if (parts.isEmpty()) "لا توجد مرفقات" else "المرفقات: " + parts.joinToString(" + ")
        tvAttachStatus.setTextColor(
            if (parts.isEmpty()) Color.parseColor("#757575") else Color.parseColor("#2E7D32")
        )
        tvGroundStatus.text =
            if (groundText.isBlank()) "التأريض: يدوي" else "التأريض: ${groundText.length} حرفاً من القوانين ✓"
    }

    private fun showImportCaseDialog() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            Toast.makeText(this, "سجل الدخول أولاً لاستيراد دعوى", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "جارٍ جلب دعاواك...", Toast.LENGTH_SHORT).show()
        FirebaseFirestore.getInstance().collection("cases")
            .whereEqualTo("userId", user.uid).get()
            .addOnSuccessListener { qs ->
                val docs = qs.documents
                if (docs.isEmpty()) {
                    Toast.makeText(this, "لا دعاوى في أرشيفك", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                val names = docs.map {
                    val c = it.toObject(Case::class.java)
                    "${c?.title ?: "بدون عنوان"} (أساس ${c?.basisNumber ?: "-"})"
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("⚖️ اختر دعوى للاستيراد منها")
                    .setItems(names) { _, which ->
                        val c = docs[which].toObject(Case::class.java) ?: return@setItems
                        val sb = StringBuilder()
                        sb.append("من ملف الدعوى: ${c.title} — أساس ${c.basisNumber}/${c.caseYear}\n")
                        sb.append("المحكمة: ${c.court} (${c.chamber}) — الموكل: ${c.clientName} (${c.clientRole}) ضد ${c.opponentName}\n")
                        if (c.summary.isNotBlank()) sb.append("الوقائع: ${c.summary}\n")
                        if (c.status.isNotBlank()) sb.append("الحالة: ${c.status}\n")
                        val cur = etFacts.text.toString()
                        etFacts.setText(if (cur.isBlank()) sb.toString() else cur + "\n\n" + sb.toString())
                        if (etTitle.text.isNullOrBlank()) etTitle.setText("مذكرة في الدعوى: ${c.title}")
                        Toast.makeText(this, "استُوردت بيانات الدعوى", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "تعذر الجلب — تحقق من الإنترنت", Toast.LENGTH_SHORT).show()
            }
    }

    private fun toggleGroundLaws() {
        if (groundText.isNotBlank()) {
            groundText = ""
            updateAttachStatus()
            return
        }
        val q = (etTitle.text.toString() + " " + etFacts.text.toString()).trim()
        if (q.isBlank()) {
            Toast.makeText(this, "اكتب العنوان والمطلوب أولاً للتأريض", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "جارٍ استرجاع المواد المؤيدة...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val pool = try {
                    LawsLocalCache.loadPool(applicationContext)
                } catch (_: Exception) {
                    null
                }
                val laws = try {
                    LawsRepository.searchRelevantLaws(q, null, pool?.first, pool?.second).take(2500)
                } catch (_: Exception) {
                    ""
                }
                if (laws.isNotBlank() && !laws.startsWith("⚠️")) {
                    groundText = laws
                    updateAttachStatus()
                } else {
                    Toast.makeText(this@DraftingStudioActivity, "لا مواد مطابقة — سيُصاغ بدون تأريض", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(this@DraftingStudioActivity, "تعذر التأريض", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showTemplates() {
        val type = try {
            spinnerType.selectedItemPosition
        } catch (_: Exception) {
            0
        }
        val extra = when (type) {
            2 -> "\nأسباب الاستئناف: [1-... 2-...].\nالمطلوب: فسخ الحكم المستأنف."
            3 -> "\nأسباب النقض: [مخالفة القانون / بطلان...].\nالمطلوب: نقض الحكم."
            4 -> "\nالوقائع المعروضة: [...].\nالمطلوب: الرأي القانوني المسبب مع المخاطر والبدائل."
            6 -> "\nالمنذَر: [...].\nالوقائع: [...].\nالمطلوب: إنذاره خلال [...] تحت طائلة اللجوء للقضاء."
            7 -> "\nالفريقان: [...].\nالبنود: [المحل/المدة/البدل/الالتزامات/الفسخ/الاختصاص]."
            else -> null
        }
        if (extra != null) {
            val cur = etFacts.text.toString()
            etFacts.setText(if (cur.isBlank()) "نوع المستند: ${docTypes[type]}$extra" else cur + "\n\nنوع المستند: ${docTypes[type]}$extra")
            Toast.makeText(this, "أُدرج قالب ${docTypes[type]}", Toast.LENGTH_SHORT).show()
            return
        }
        val names = arrayOf("📝 مذكرة جوابية", "📨 استدعاء رسمي", "⏳ طلب مهلة وإبراز")
        val bodies = arrayOf(
            "الغرض: الرد على مذكرة الخصم ودحض دفوعه.\nالوقائع: [اكتبها].\nالمطلوب: تفنيد كل دفع بسنده ثم طلب الرد شكلاً وموضوعاً.",
            "الغرض: استدعاء رسمي إلى [الجهة].\nالموضوع: [اكتبه].\nالمطلوب: اتخاذ الإجراء اللازم أصولاً.",
            "الغرض: طلب مهلة.\nالسبب: [...].\nالمطلوب: منح مهلة مناسبة."
        )
        AlertDialog.Builder(this)
            .setTitle("اختر قالباً")
            .setItems(names) { _, which ->
                val cur = etFacts.text.toString()
                etFacts.setText(if (cur.isBlank()) bodies[which] else cur + "\n\n" + bodies[which])
            }
            .show()
    }

    private fun generate() {
        val title = etTitle.text.toString().trim()
        val facts = etFacts.text.toString().trim()
        if (title.isEmpty() && facts.isEmpty() && attachedImagesBase64.isEmpty() && attachedTexts.isEmpty()) {
            Toast.makeText(this, "اكتب العنوان والمطلوب أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        val type = try {
            spinnerType.selectedItemPosition
        } catch (_: Exception) {
            0
        }
        progress.visibility = View.VISIBLE
        findViewById<Button>(R.id.btnGenerateDraft).isEnabled = false
        layoutResult.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val sb = StringBuilder()
                sb.append("أنت محامٍ سوري خبير. اكتب بلغة عربية فصيحة سليمة وأسلوب قانوني رصين بلا أخطاء إملائية.\n")
                sb.append("نوع المستند: ${docTypes.getOrElse(type) { docTypes[0] }}\n${typePrompts[type] ?: ""}\n")
                if (title.isNotBlank()) sb.append("العنوان: $title\n")
                val lines = etTargetLines.text.toString().trim().toIntOrNull()
                if (lines != null && lines in 5..500) sb.append("الطول المستهدف: نحو $lines سطراً.\n")
                if (groundText.isNotBlank()) sb.append("مواد قانونية مؤيدة (اعتمدها واستشهد بأرقامها حرفياً):\n${groundText.take(2500)}\n")
                for ((i, t) in attachedTexts.withIndex()) {
                    sb.append("مرفق نصي ${i + 1} (${t.first}):\n${t.second.take(4000)}\n")
                }
                sb.append("الوقائع والمطلوب: $facts\nالتزم الصياغة القانونية السورية حصراً.")
                val aiBody = AIClient.draftLegalDocument(sb.toString(), attachedImagesBase64)
                val full = StringBuilder()
                if (title.isNotBlank()) full.append(title).append("\n\n-------------------------\n\n")
                val header = etHeader.text.toString().trim()
                if (header.isNotBlank()) full.append(header).append("\n\n-------------------------\n\n")
                full.append(aiBody)
                val footer = etFooter.text.toString().trim()
                if (footer.isNotBlank()) full.append("\n\n-------------------------\n\n").append(footer)
                progress.visibility = View.GONE
                findViewById<Button>(R.id.btnGenerateDraft).isEnabled = true
                layoutResult.visibility = View.VISIBLE
                etResult.setText(full.toString())
                applyResultStyle()
            } catch (e: Exception) {
                progress.visibility = View.GONE
                try {
                    findViewById<Button>(R.id.btnGenerateDraft).isEnabled = true
                } catch (_: Exception) {
                }
                Toast.makeText(this@DraftingStudioActivity, "خطأ: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun applyResultStyle() {
        try {
            etResult.textSize = fontSize
            etResult.setTextColor(fontColor)
        } catch (_: Exception) {
        }
    }

    private fun showColorPicker() {
        val names = arrayOf("كحلي", "أسود", "أخضر داكن", "عنابي", "أزرق", "رمادي داكن")
        val values = intArrayOf(
            Color.parseColor("#0F2042"), Color.BLACK, Color.parseColor("#1B5E20"),
            Color.parseColor("#6A1B1B"), Color.parseColor("#0D47A1"), Color.parseColor("#37474F")
        )
        AlertDialog.Builder(this)
            .setTitle("🎨 لون الخط")
            .setItems(names) { _, which ->
                fontColor = values[which]
                applyResultStyle()
            }
            .show()
    }

    private fun copyResult() {
        val text = etResult.text.toString()
        if (text.isNotEmpty()) {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Draft", text))
            Toast.makeText(this, "تم النسخ", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareResult() {
        val text = etResult.text.toString()
        if (text.isNotEmpty()) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }, "مشاركة المسودة"))
        }
    }

    private fun draftsFile() = File(filesDir, "general_drafts.json")

    private fun loadDrafts(): JSONArray {
        return try {
            val f = draftsFile()
            if (!f.exists()) JSONArray() else JSONArray(f.readText())
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun saveDraft() {
        val text = etResult.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "ولّد النص أولاً ثم احفظ", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val arr = loadDrafts()
            arr.put(JSONObject().apply {
                put("id", System.currentTimeMillis())
                put("title", etTitle.text.toString().trim().ifBlank { "بدون عنوان" })
                put("type", docTypes.getOrElse(spinnerType.selectedItemPosition) { docTypes[0] })
                put("task", etFacts.text.toString().trim().take(500))
                put("result", text)
                put("ts", System.currentTimeMillis())
            })
            draftsFile().writeText(arr.toString())
            Toast.makeText(this, "حُفظت المسودة (${arr.length()})", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "فشل الحفظ: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDrafts() {
        val arr = loadDrafts()
        if (arr.length() == 0) {
            Toast.makeText(this, "لا مسودات محفوظة بعد", Toast.LENGTH_SHORT).show()
            return
        }
        val names = Array(arr.length()) { i ->
            try {
                val o = arr.getJSONObject(i)
                "${o.optString("title")} — ${o.optString("type")}"
            } catch (_: Exception) {
                "مسودة"
            }
        }
        AlertDialog.Builder(this)
            .setTitle("📚 مسوداتي المحفوظة")
            .setItems(names) { _, which ->
                try {
                    val o = arr.getJSONObject(which)
                    etTitle.setText(o.optString("title"))
                    etResult.setText(o.optString("result"))
                    layoutResult.visibility = View.VISIBLE
                    applyResultStyle()
                } catch (_: Exception) {
                }
            }
            .setNeutralButton("حذف واحدة...") { _, _ -> showDeleteDraft(arr) }
            .show()
    }

    private fun showDeleteDraft(arr: JSONArray) {
        val names = Array(arr.length()) { i ->
            try {
                arr.getJSONObject(i).optString("title")
            } catch (_: Exception) {
                "مسودة"
            }
        }
        AlertDialog.Builder(this)
            .setTitle("حذف مسودة")
            .setItems(names) { _, w2 ->
                try {
                    val next = JSONArray()
                    for (i in 0 until arr.length()) if (i != w2) next.put(arr.get(i))
                    draftsFile().writeText(next.toString())
                    Toast.makeText(this, "حُذفت", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                }
            }
            .show()
    }

    private fun printDraft() {
        val text = etResult.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "ولّد النص أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val colorHex = String.format("#%06X", 0xFFFFFF and fontColor)
            val esc = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
            val title = etTitle.text.toString().trim()
            val wv = android.webkit.WebView(this)
            val html = "<html dir=\"rtl\"><head><meta charset=\"utf-8\"></head>" +
                "<body style=\"font-family:sans-serif;padding:24px;line-height:1.8;color:$colorHex;font-size:${fontSize.toInt()}px;\">" +
                (if (title.isNotBlank()) "<h2>" + title.replace("&", "&amp;").replace("<", "&lt;") + "</h2>" else "") +
                "<p>$esc</p><hr><p style=\"color:#666;font-size:12px;\">تطبيق المستشار القانوني</p></body></html>"
            wv.loadDataWithBaseURL(null, html, "text/HTML", "UTF-8", null)
            val pm = getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
            pm.print("مسودة_قانونية", wv.createPrintDocumentAdapter("مسودة_قانونية"), null)
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر الطباعة: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
