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
import androidx.cardview.widget.CardView
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

class ConsultActivity : AppCompatActivity() {

    private lateinit var etQuestion: EditText
    private lateinit var btnSubmit: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvResult: TextView
    private lateinit var cardResult: CardView
    private lateinit var resultActions: LinearLayout
    private lateinit var scrollAnswers: NestedScrollView

    // إدارة سلسلة الاستشارات المتتابعة
    private var currentSessionQueryId: String? = null
    private val conversationHistory = StringBuilder()
    private var lastQuestion = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_consult)

        etQuestion = findViewById(R.id.etQuestion)
        btnSubmit = findViewById(R.id.btnSubmit)
        progressBar = findViewById(R.id.progressBar)
        tvResult = findViewById(R.id.tvResult)
        cardResult = findViewById(R.id.cardResult)
        resultActions = findViewById(R.id.resultActions)
        scrollAnswers = findViewById(R.id.scrollAnswers)

        // التحقق إن كانت الشاشة فُتحت لمتابعة استشارة سابقة من الأرشيف
        val prevId = intent.getStringExtra("previous_query_id")
        val prevQ = intent.getStringExtra("previous_question")
        val prevA = intent.getStringExtra("previous_answer")

        if (!prevQ.isNullOrEmpty() && !prevA.isNullOrEmpty()) {
            currentSessionQueryId = prevId
            conversationHistory.append("السؤال: ").append(prevQ).append("\n\nالإجابة:\n").append(prevA).append("\n\n")
            tvResult.text = cleanMarkdown(conversationHistory.toString())
            cardResult.visibility = View.VISIBLE
            resultActions.visibility = View.VISIBLE
            etQuestion.hint = "اكتب استفسارك التكميلي حول هذه الاستشارة..."
            btnSubmit.text = "إرسال استفسار تعقيبي 💬"
        }

        findViewById<TextView>(R.id.btnArchive).setOnClickListener {
            startActivity(Intent(this, QueriesActivity::class.java))
        }

        btnSubmit.setOnClickListener { onConsultClick() }

        findViewById<Button>(R.id.btnCopyResult).setOnClickListener {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("Consultation", tvResult.text))
            Toast.makeText(this, "تم نسخ نص الاستشارة بالكامل", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnShareResult).setOnClickListener {
            val text = tvResult.text.toString()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(intent, "مشاركة الاستشارة عبر"))
        }

        findViewById<Button>(R.id.btnPrintResult).setOnClickListener {
            printText(tvResult.text.toString())
        }
    }

    private fun cleanMarkdown(raw: String): String {
        var clean = raw
        // إزالة وسوم الترويسات الماركداون
        clean = clean.replace(Regex("""(?m)^#{1,6}\s*"""), "")
        // إزالة النجوم المزدوجة والثلاثية للتغميق
        clean = clean.replace(Regex("""\*{2,3}(.*?)\*{2,3}"""), "$1")
        clean = clean.replace(Regex("""\*(.*?)\*"""), "$1")
        // تنسيق الخطوط الأفقية
        clean = clean.replace(Regex("""---|\*\*\*|___"""), "─────────────────────────────")
        return clean.trim()
    }

    private fun onConsultClick() {
        val question = etQuestion.text?.toString()?.trim() ?: ""
        if (question.isEmpty()) {
            Toast.makeText(this, "يرجى كتابة نص السؤال أو الاستفسار التعقيبي", Toast.LENGTH_SHORT).show()
            return
        }

        lastQuestion = question
        btnSubmit.isEnabled = false
        progressBar.visibility = View.VISIBLE

        val user = FirebaseAuth.getInstance().currentUser

        lifecycleScope.launch {
            // سياق المحادثة الممتدة
            val fullPrompt = if (conversationHistory.isNotEmpty()) {
                "سياق الاستشارة والمناقشة السابقة:\n$conversationHistory\nسؤال المستخدم التعقيبي الجديد:\n$question"
            } else {
                question
            }

            // البحث عن النصوص ذات الصلة
            val lawsContext = LawsRepository.searchRelevantLaws(question)
            val combinedPrompt = if (lawsContext.isNotBlank()) {
                "النصوص القانونية ذات الصلة من التشريعات المعتمدة:\n$lawsContext\n\n$fullPrompt"
            } else {
                fullPrompt
            }

            val answer = AIClient.askLegalQuestion(combinedPrompt)
            val isRealAnswer = answer.isNotEmpty() &&
                    !answer.startsWith("❌") &&
                    !answer.contains("فشل جميع المزودين") &&
                    !answer.contains("الخدمة تواجه ضغطاً") &&
                    answer.length > 50

            progressBar.visibility = View.GONE
            btnSubmit.isEnabled = true

            if (isRealAnswer) {
                // تنظيف التنسيق فوراً
                val cleanedAnswer = cleanMarkdown(answer)

                if (conversationHistory.isNotEmpty()) {
                    conversationHistory.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
                        .append("سؤال تعقيبي: ").append(question).append("\n\n")
                        .append("الرأي والمتابعة:\n").append(cleanedAnswer).append("\n\n")
                } else {
                    conversationHistory.append("السؤال: ").append(question).append("\n\n")
                        .append("الرأي القانوني:\n").append(cleanedAnswer).append("\n\n")
                }

                tvResult.text = conversationHistory.toString()
                cardResult.visibility = View.VISIBLE
                resultActions.visibility = View.VISIBLE

                // التمرير تلقائياً لأسفل لمشاهدة الرد الجديد
                scrollAnswers.post {
                    scrollAnswers.fullScroll(View.FOCUS_DOWN)
                }

                // إفراغ حقل الكتابة وتحضيره للسؤال القادم في نفس الموضوع
                etQuestion.text.clear()
                etQuestion.hint = "اطرح سؤالاً تعقيبياً أو استفساراً آخر في نفس الموضوع..."
                btnSubmit.text = "إرسال استفسار تعقيبي 💬"

                // الحفظ في الأرشيف
                val db = FirebaseFirestore.getInstance().collection("queries")
                if (currentSessionQueryId != null) {
                    db.document(currentSessionQueryId!!).update(
                        mapOf(
                            "question" to conversationHistory.toString().split("\n\nالرأي القانوني:")[0],
                            "answer" to tvResult.text.toString(),
                            "timestamp" to System.currentTimeMillis()
                        )
                    )
                } else {
                    db.add(hashMapOf(
                        "question" to question,
                        "answer" to cleanedAnswer,
                        "userId" to (user?.uid ?: "anonymous"),
                        "email" to (user?.email ?: ""),
                        "timestamp" to System.currentTimeMillis()
                    )).addOnSuccessListener { docRef ->
                        currentSessionQueryId = docRef.id
                    }
                }
            } else {
                Toast.makeText(this@ConsultActivity, "⚠️ تعذر جلب إجابة مكتملة، يرجى المحاولة ثانية", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun printText(content: String) {
        if (content.isEmpty()) {
            Toast.makeText(this, "لا يوجد جواب للطباعة", Toast.LENGTH_SHORT).show()
            return
        }
        val webView = WebView(this)
        val html = """
            <html dir="rtl"><head><meta charset="utf-8"></head>
            <body style="font-family:sans-serif;padding:20px;line-height:1.6;">
            <h2>استشارة قانونية</h2>
            <pre style="white-space: pre-wrap; font-family: inherit;">$content</pre>
            <hr>
            <p style="color:#666;font-size:12px;">تطبيق المستشار القانوني السوري الذكي</p>
            </body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(null, html, "text/HTML", "UTF-8", null)
        val printManager = getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
        printManager.print("استشارة_قانونية", webView.createPrintDocumentAdapter("استشارة_قانونية"), null)
    }
}
