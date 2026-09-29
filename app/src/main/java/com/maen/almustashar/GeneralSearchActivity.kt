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
    private lateinit var resultActions: LinearLayout

    private var lastQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_general_search)

        etQuestion = findViewById(R.id.etGeneralQuestion)
        btnSubmit = findViewById(R.id.btnGeneralSubmit)
        progressBar = findViewById(R.id.progressBar)
        tvResult = findViewById(R.id.tvGeneralResult)
        resultActions = findViewById(R.id.resultActions)

        btnSubmit.setOnClickListener { onSearch() }

        findViewById<Button>(R.id.btnCopyResult).setOnClickListener {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("Answer", tvResult.text))
            Toast.makeText(this, "تم النسخ", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnShareResult).setOnClickListener {
            val text = "السؤال:\n${etQuestion.text}\n\nالإجابة:\n${tvResult.text}"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(intent, "مشاركة عبر"))
        }

        findViewById<Button>(R.id.btnPrintResult).setOnClickListener {
            printText(etQuestion.text.toString(), tvResult.text.toString())
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
        tvResult.visibility = View.GONE
        resultActions.visibility = View.GONE

        val user = FirebaseAuth.getInstance().currentUser

        lifecycleScope.launch {
            val answer = AIClient.askGeneralQuestion(question)
            val isReal = answer.isNotEmpty() &&
                    !answer.startsWith("❌") &&
                    !answer.contains("فشل جميع المزودين") &&
                    answer.length > 30

            val cleaned = cleanMarkdown(answer)
            tvResult.text = cleaned
            tvResult.visibility = View.VISIBLE
            progressBar.visibility = View.GONE
            btnSubmit.isEnabled = true

            if (isReal) {
                resultActions.visibility = View.VISIBLE
                FirebaseFirestore.getInstance().collection("general_queries").add(
                    hashMapOf(
                        "question" to question,
                        "answer" to cleaned,
                        "userId" to (user?.uid ?: "anonymous"),
                        "email" to (user?.email ?: ""),
                        "timestamp" to System.currentTimeMillis()
                    )
                )
            } else {
                Toast.makeText(this@GeneralSearchActivity, "⚠️ لم يتم الحصول على جواب كامل — يمكنك البحث في Google", Toast.LENGTH_LONG).show()
                openGoogleSearch(question)
            }
        }
    }

    private fun printText(q: String, a: String) {
        if (a.isEmpty()) return
        val wv = WebView(this)
        val html = """
            <html dir="rtl"><head><meta charset="utf-8"></head>
            <body style="font-family:sans-serif;padding:20px;">
            <h2>بحث قانوني عام</h2>
            <h3>السؤال:</h3><p>$q</p><hr>
            <h3>الإجابة:</h3><p>$a</p><hr>
            <p style="color:#666;font-size:12px;">تطبيق المستشار القانوني الذكي</p>
            </body></html>
        """.trimIndent()
        wv.loadDataWithBaseURL(null, html, "text/HTML", "UTF-8", null)
        val pm = getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
        pm.print("بحث قانوني", wv.createPrintDocumentAdapter("بحث قانوني"), null)
    }
}
