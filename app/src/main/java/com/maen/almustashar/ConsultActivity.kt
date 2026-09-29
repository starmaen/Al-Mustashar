package com.maen.almustashar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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

class ConsultActivity : AppCompatActivity() {

    private lateinit var etQuestion: EditText
    private lateinit var btnSubmit: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvResult: TextView
    private lateinit var resultActions: LinearLayout

    private var previousQueryId: String? = null
    private var previousQuestion: String? = null
    private var previousAnswer: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_consult)

        etQuestion = findViewById(R.id.etQuestion)
        btnSubmit = findViewById(R.id.btnSubmit)
        progressBar = findViewById(R.id.progressBar)
        tvResult = findViewById(R.id.tvResult)
        resultActions = findViewById(R.id.resultActions)

        previousQueryId = intent.getStringExtra("previous_query_id")
        previousQuestion = intent.getStringExtra("previous_question")
        previousAnswer = intent.getStringExtra("previous_answer")

        if (!previousQuestion.isNullOrEmpty()) {
            etQuestion.hint = "اكتب استفسارك التكميلي حول هذا الموضوع..."
            tvResult.text = "📜 الاستشارة السابقة:\n$previousQuestion\n\n$previousAnswer"
            tvResult.visibility = View.VISIBLE
        }

        findViewById<TextView>(R.id.btnArchive).setOnClickListener {
            startActivity(Intent(this, QueriesActivity::class.java))
        }

        btnSubmit.setOnClickListener { onConsultClick() }

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

    private fun onConsultClick() {
        val question = etQuestion.text?.toString()?.trim() ?: ""
        if (question.isEmpty()) {
            Toast.makeText(this, "يرجى كتابة نص الاستشارة أولاً", Toast.LENGTH_SHORT).show()
            return
        }

        btnSubmit.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvResult.visibility = View.GONE
        resultActions.visibility = View.GONE

        val user = FirebaseAuth.getInstance().currentUser

        lifecycleScope.launch {
            // دمج السياق السابق إذا كان استفساراً تكميلياً
            val fullPrompt = if (!previousQuestion.isNullOrEmpty()) {
                "سياق الاستشارة السابقة:\nالسؤال السابق: $previousQuestion\nالإجابة السابقة: $previousAnswer\n\nسؤال المستخدم التكميلي الجديد:\n$question"
            } else {
                question
            }

            val lawsContext = LawsRepository.searchRelevantLaws(question)
            val combinedPrompt = if (lawsContext.isNotBlank()) {
                "النصوص القانونية ذات الصلة من قاعدة البيانات المعتمدة:\n$lawsContext\n\n$fullPrompt"
            } else {
                fullPrompt
            }

            val answer = AIClient.askLegalQuestion(combinedPrompt)

            val isRealAnswer = answer.isNotEmpty() &&
                    !answer.startsWith("❌") &&
                    !answer.contains("فشل جميع المزودين") &&
                    !answer.contains("الخدمة تواجه ضغطاً") &&
                    answer.length > 50

            tvResult.text = answer
            tvResult.visibility = View.VISIBLE
            progressBar.visibility = View.GONE
            btnSubmit.isEnabled = true

            if (isRealAnswer) {
                resultActions.visibility = View.VISIBLE
                val db = FirebaseFirestore.getInstance().collection("queries")

                // إذا كانت متابعة لنفس الاستشارة، نحدث الوثيقة نفسها بدلاً من مضاعفة السجلات
                if (!previousQueryId.isNullOrEmpty()) {
                    db.document(previousQueryId!!).update(
                        mapOf(
                            "question" to "$previousQuestion\n\n[استفسار تكميلي]: $question",
                            "answer" to "$previousAnswer\n\n━━━━━━━━━━━━━━━━━━━━\n[متابعة الاستشارة]:\n$answer",
                            "timestamp" to System.currentTimeMillis()
                        )
                    )
                } else {
                    db.add(hashMapOf(
                        "question" to question,
                        "answer" to answer,
                        "userId" to (user?.uid ?: "anonymous"),
                        "email" to (user?.email ?: ""),
                        "timestamp" to System.currentTimeMillis()
                    ))
                }
            } else {
                Toast.makeText(this@ConsultActivity, "⚠️ لم يتم الحفظ — الجواب غير مكتمل", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun printText(q: String, a: String) {
        if (a.isEmpty()) {
            Toast.makeText(this, "لا يوجد جواب للطباعة", Toast.LENGTH_SHORT).show()
            return
        }
        val webView = WebView(this)
        val html = """
            <html dir="rtl"><head><meta charset="utf-8"></head>
            <body style="font-family:sans-serif;padding:20px;">
            <h2>استشارة قانونية</h2>
            <h3>السؤال:</h3><p>$q</p><hr>
            <h3>الإجابة:</h3><p>$a</p><hr>
            <p style="color:#666;font-size:12px;">تطبيق المستشار القانوني الذكي</p>
            </body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(null, html, "text/HTML", "UTF-8", null)
        val printManager = getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
        printManager.print("استشارة", webView.createPrintDocumentAdapter("استشارة"), null)
    }
}
