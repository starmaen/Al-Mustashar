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
    private lateinit var btnGoogleSearch: Button
    private lateinit var btnFallbackGoogle: Button
    private lateinit var scrollResults: NestedScrollView

    private val conversationHistory = StringBuilder()
    private var lastQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_general_search)

        etQuestion = findViewById(R.id.etGeneralQuestion)
        btnSubmit = findViewById(R.id.btnGeneralSubmit)
        progressBar = findViewById(R.id.progressBar)
        tvResult = findViewById(R.id.tvGeneralResult)
        cardResult = findViewById(R.id.cardGeneralResult)
        resultActions = findViewById(R.id.resultActions)
        btnGoogleSearch = findViewById(R.id.btnGoogleSearch)
        btnFallbackGoogle = findViewById(R.id.btnFallbackGoogle)
        scrollResults = findViewById(R.id.scrollGeneralResults)

        findViewById<TextView>(R.id.btnArchive).setOnClickListener {
            startActivity(Intent(this, QueriesActivity::class.java))
        }

        btnSubmit.setOnClickListener { onSearch() }

        btnGoogleSearch.setOnClickListener { openGoogleSearch(lastQuery) }
        btnFallbackGoogle.setOnClickListener { openGoogleSearch(lastQuery) }

        findViewById<Button>(R.id.btnCopyResult).setOnClickListener {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("Answer", tvResult.text))
            Toast.makeText(this, "تم نسخ النص", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnShareResult).setOnClickListener {
            val text = "السؤال:\n${lastQuery}\n\nالإجابة:\n${tvResult.text}"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(intent, "مشاركة عبر"))
        }

        findViewById<Button>(R.id.btnPrintResult).setOnClickListener {
            printText(lastQuery, tvResult.text.toString())
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
        val q = if (query.isNotBlank()) query else etQuestion.text?.toString()?.trim() ?: ""
        if (q.isBlank()) {
            Toast.makeText(this, "يرجى كتابة نص البحث أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        val refinedQuery = "$q القانون السوري"
        val url = "https://www.google.com/search?q=" + URLEncoder.encode(refinedQuery, "UTF-8")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }

    private fun onSearch() {
        val question = etQuestion.text?.toString()?.trim() ?: ""
        if (question.isEmpty()) {
            Toast.makeText(this, "اكتب سؤالك أولاً", Toast.LENGTH_SHORT).show()
            return
        }

        lastQuery = question
        btnSubmit.isEnabled = false
        progressBar.visibility = View.VISIBLE
        btnFallbackGoogle.visibility = View.GONE

        val user = FirebaseAuth.getInstance().currentUser

        lifecycleScope.launch {
            try {
                val promptToSend = if (conversationHistory.isNotEmpty()) {
                    "سياق البحث السابق:\n$conversationHistory\nالاستفسار الجديد:\n$question"
                } else {
                    question
                }

                val answer = AIClient.askGeneralQuestion(promptToSend)

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
                    btnFallbackGoogle.visibility = View.GONE

                    scrollResults.post {
                        scrollResults.fullScroll(View.FOCUS_DOWN)
                    }

                    etQuestion.text.clear()
                    etQuestion.hint = "اطرح استفساراً آخر في نفس الموضوع..."
                    btnSubmit.text = "متابعة البحث 🔍"

                    try {
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
                    btnFallbackGoogle.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                tvResult.text = "❌ حدث خطأ: ${e.localizedMessage}"
                cardResult.visibility = View.VISIBLE
                btnFallbackGoogle.visibility = View.VISIBLE
            } finally {
                progressBar.visibility = View.GONE
                btnSubmit.isEnabled = true
            }
        }
    }

    private fun printText(q: String, a: String) {
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
    }
}
