package com.maen.almustashar

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore

class QueryDetailActivity : AppCompatActivity() {

    private var queryId = ""
    private var question = ""
    private var answer = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_query_detail)

        queryId = intent.getStringExtra("query_id") ?: ""
        val tvQ = findViewById<TextView>(R.id.tvQueryDetailQuestion)
        val tvA = findViewById<TextView>(R.id.tvQueryDetailAnswer)

        if (queryId.isNotEmpty()) {
            FirebaseFirestore.getInstance().collection("queries").document(queryId).get()
                .addOnSuccessListener { doc ->
                    question = doc.getString("question") ?: ""
                    answer = doc.getString("answer") ?: ""
                    tvQ.text = question
                    tvA.text = answer
                }
        }

        // 1. نسخ النص
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("Legal Consult", "السؤال:\n$question\n\nالإجابة:\n$answer"))
            Toast.makeText(this, "تم نسخ الاستشارة بنجاح", Toast.LENGTH_SHORT).show()
        }

        // 2. مشاركة
        findViewById<Button>(R.id.btnShare).setOnClickListener {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "⚖️️ استشارة قانونية — تطبيق المستشار:\n\nالسؤال:\n$question\n\nالإجابة:\n$answer")
            }
            startActivity(Intent.createChooser(intent, "مشاركة عبر"))
        }

        // 3. طباعة
        findViewById<Button>(R.id.btnPrint).setOnClickListener { printText() }

        // 4. متابعة الاستشارة في نفس الموضوع
        findViewById<Button>(R.id.btnContinueConsult).setOnClickListener {
            val intent = Intent(this, ConsultActivity::class.java).apply {
                putExtra("previous_query_id", queryId)
                putExtra("previous_question", question)
                putExtra("previous_answer", answer)
            }
            startActivity(intent)
        }

        // 5. حذف مع رسالة تأكيد لحماية بيانات المحامي/المستخدم
        findViewById<Button>(R.id.btnDelete).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("تأكيد الحذف")
                .setMessage("هل تريد بالتأكيد حذف هذه الاستشارة من الأرشيف؟")
                .setPositiveButton("حذف") { _, _ ->
                    FirebaseFirestore.getInstance().collection("queries").document(queryId).delete()
                        .addOnSuccessListener {
                            Toast.makeText(this, "تم حذف الاستشارة بنجاح", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }

    private fun printText() {
        val webView = WebView(this)
        val html = """
            <html dir="rtl"><head><meta charset="utf-8"></head>
            <body style="font-family:sans-serif;padding:20px;">
            <h2>استشارة قانونية</h2>
            <h3>السؤال:</h3><p>$question</p><hr>
            <h3>الإجابة:</h3><p>$answer</p><hr>
            <p style="color:#666;font-size:12px;">تطبيق المستشار القانوني الذكي</p>
            </body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(null, html, "text/HTML", "UTF-8", null)
        val printManager = getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
        printManager.print("استشارة", webView.createPrintDocumentAdapter("استشارة"), null)
    }
}
