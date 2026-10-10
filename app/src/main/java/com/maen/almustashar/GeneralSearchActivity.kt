package com.maen.almustashar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import java.net.URLEncoder

class GeneralSearchActivity : AppCompatActivity() {

    private lateinit var etQuestion: EditText
    private lateinit var btnSubmit: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvResult: TextView
    private lateinit var cardResult: CardView
    private lateinit var resultActions: LinearLayout
    private lateinit var btnModeAI: Button
    private lateinit var btnModeWeb: Button
    private lateinit var tvSearchMode: TextView
    private lateinit var scrollResults: NestedScrollView
    private var isWebMode = false

    private val conversationHistory = StringBuilder()
    private var lastQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_general_search)

            etQuestion = findViewById(R.id.etGeneralQuestion)
            btnSubmit = findViewById(R.id.btnGeneralSubmit)
            progressBar = findViewById(R.id.progressBar)
            tvResult = findViewById(R.id.tvGeneralResult)
            cardResult = findViewById(R.id.cardGeneralResult)
            resultActions = findViewById(R.id.resultActions)
            btnModeAI = findViewById(R.id.btnModeAI)
            btnModeWeb = findViewById(R.id.btnModeWeb)
            tvSearchMode = findViewById(R.id.tvSearchMode)
            scrollResults = findViewById(R.id.scrollGeneralResults)
            updateModeUI()

            findViewById<TextView>(R.id.btnArchive)?.setOnClickListener {
                try {
                    startActivity(Intent(this, QueriesActivity::class.java))
                } catch (_: Exception) {}
            }

            btnSubmit.setOnClickListener { onSearch() }

            btnModeAI.setOnClickListener {
                isWebMode = false
                updateModeUI()
            }
            btnModeWeb.setOnClickListener {
                isWebMode = true
                updateModeUI()
            }

            findViewById<Button>(R.id.btnCopyResult)?.setOnClickListener {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("Answer", tvResult.text))
                Toast.makeText(this, "تم نسخ النص", Toast.LENGTH_SHORT).show()
            }

            findViewById<Button>(R.id.btnShareResult)?.setOnClickListener {
                val text = "السؤال:\n${lastQuery}\n\nالإجابة:\n${tvResult.text}"
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(intent, "مشاركة عبر"))
            }

            findViewById<Button>(R.id.btnPrintResult)?.setOnClickListener {
                printText(lastQuery, tvResult.text.toString())
            }
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر فتح الشاشة", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun updateModeUI() {
        try {
            if (isWebMode) {
                btnModeWeb.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00796B.toInt())
                btnModeAI.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF1E293B.toInt())
                tvSearchMode.text = "🌐 الوضع: بحث ويب داخل التطبيق"
                btnSubmit.text = "بحث في الويب 🌐"
            } else {
                btnModeAI.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00796B.toInt())
                btnModeWeb.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF1E293B.toInt())
                tvSearchMode.text = "🤖 الوضع: إجابة ذكية"
                btnSubmit.text = "متابعة البحث 🔍".takeIf { conversationHistory.isNotEmpty() } ?: "بحث شامل 🔍"
            }
        } catch (_: Exception) {
        }
    }

    private fun cleanMarkdown(raw: String): String {
        var clean = raw
        clean = clean.replace(Regex("""(?m)^#{1,6}\s*"""), "")
        clean = clean.replace(Regex("""\*{2,3}(.*?)\*{2,3}"""), "$1")
        clean = clean.replace(Regex("""\*(.*?)\*"""), "$1")
        clean = clean.replace(Regex("""---|\*\*\*|___"""), "─────────────────────────────")
        return clean.trim()
    }

    private fun openGoogleSearch(query: String) {
        try {
            val q = if (query.isNotBlank()) query else etQuestion.text?.toString()?.trim() ?: ""
            if (q.isBlank()) {
                Toast.makeText(this, "يرجى كتابة نص البحث أولاً", Toast.LENGTH_SHORT).show()
                return
            }
            // بحث الويب داخل التطبيق مباشرة (بدل المتصفح الخارجي)
            val i = Intent(this, WebSearchActivity::class.java)
            i.putExtra("query", q)
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر فتح البحث الداخلي", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onSearch() {
        val question = etQuestion.text?.toString()?.trim() ?: ""
        if (question.isEmpty()) {
            Toast.makeText(this, "اكتب سؤالك أولاً", Toast.LENGTH_SHORT).show()
            return
        }

        lastQuery = question
        if (isWebMode) {
            progressBar.visibility = View.GONE
            btnSubmit.isEnabled = true
            openGoogleSearch(question)
            return
        }
        btnSubmit.isEnabled = false
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val promptToSend = if (conversationHistory.isNotEmpty()) {
                    "سياق البحث السابق:\n$conversationHistory\nالاستفسار الجديد:\n$question"
                } else {
                    question
                }

                val answer = AIClient.askLegalQuestion(promptToSend)

                if (answer.isNotEmpty() && !answer.startsWith("❌")) {
                    val cleaned = cleanMarkdown(answer)
                    if (conversationHistory.isNotEmpty()) {
                        conversationHistory.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
                            .append("سؤال: ").append(question).append("\n\n")
                            .append("الإجابة:\n").append(cleaned).append("\n\n")
                    } else {
                        conversationHistory.append("السؤال: ").append(question).append("\n\n")
                            .append("الإجابة:\n").append(cleaned).append("\n\n")
                    }

                    tvResult.text = conversationHistory.toString()
                    cardResult.visibility = View.VISIBLE

                    scrollResults.post {
                        scrollResults.fullScroll(View.FOCUS_DOWN)
                    }

                    etQuestion.text.clear()
                    etQuestion.hint = "اطرح استفساراً آخر في نفس الموضوع..."
                    btnSubmit.text = "متابعة البحث 🔍"

                    try {
                        val user = FirebaseAuth.getInstance().currentUser
                        FirebaseFirestore.getInstance().collection("general_queries").add(
                            hashMapOf(
                                "question" to question,
                                "answer" to cleaned,
                                "userId" to (user?.uid ?: "anonymous"),
                                "email" to (user?.email ?: ""),
                                "timestamp" to System.currentTimeMillis()
                            )
                        )
                    } catch (_: Exception) {}
                } else {
                    tvResult.text = answer.ifEmpty { "تعذر الحصول على رد حالياً." }
                    cardResult.visibility = View.VISIBLE
                    Toast.makeText(this@GeneralSearchActivity, "فشل الذكاء — جرّب وضع الويب 🌐", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                tvResult.text = "❌ حدث خطأ: ${e.localizedMessage}"
                cardResult.visibility = View.VISIBLE
                Toast.makeText(this@GeneralSearchActivity, "حدث خطأ — جرّب وضع الويب 🌐", Toast.LENGTH_LONG).show()
            } finally {
                progressBar.visibility = View.GONE
                btnSubmit.isEnabled = true
            }
        }
    }

    private fun printText(q: String, a: String) {
        try {
            if (a.isEmpty()) return
            val wv = WebView(this)
            val html = """
                <html dir="rtl"><head><meta charset="utf-8"></head>
                <body style="font-family:sans-serif;padding:20px;line-height:1.6;">
                <h2>بحث قانوني عام</h2>
                <pre style="white-space: pre-wrap; font-family: inherit;">$a</pre>
                <hr>
                <p style="color:#666;font-size:12px;">تطبيق المستشار القانوني الذكي</p>
                </body></html>
            """.trimIndent()
            wv.loadDataWithBaseURL(null, html, "text/HTML", "UTF-8", null)
            val pm = getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
            pm.print("بحث_قانوني", wv.createPrintDocumentAdapter("بحث_قانوني"), null)
        } catch (_: Exception) {}
    }
}
