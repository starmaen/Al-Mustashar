package com.maen.almustashar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ConsultActivity : AppCompatActivity() {

    private val conversationHistory = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_consult)

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    LawsLocalCache.syncIfNeeded(applicationContext)
                } catch (_: Exception) {
                }
            }

            val etQuestion = findViewById<EditText>(R.id.etQuestion)
            val btnAsk = findViewById<Button>(R.id.btnAsk)
            val progressBar = findViewById<ProgressBar>(R.id.progressBar)
            val tvAnswer = findViewById<TextView>(R.id.tvAnswer)
            val btnCopy = findViewById<Button>(R.id.btnCopy)
            val btnShare = findViewById<Button>(R.id.btnShare)

            btnAsk?.setOnClickListener {
                val q = etQuestion?.text?.toString()?.trim() ?: ""
                if (q.isNotEmpty()) {
                    progressBar?.visibility = View.VISIBLE
                    btnAsk.isEnabled = false

                    lifecycleScope.launch {
                        try {
                            // تأسيس الاستشارة على مواد القوانين (المحلية أولاً) للاستشهاد الدقيق
                            val pool = try {
                                LawsLocalCache.loadPool(applicationContext)
                            } catch (_: Exception) {
                                null
                            }
                            val localLaws = try {
                                LawsRepository.searchRelevantLaws(q, null, pool?.first, pool?.second).take(2500)
                            } catch (_: Exception) {
                                ""
                            }
                            val grounded = if (localLaws.isNotBlank() && !localLaws.startsWith("⚠️")) {
                                "$q\n\n[مواد مسترجعة من قاعدة قوانين التطبيق — اعتمدها أولاً واستشهد بها حرفياً]:\n$localLaws"
                            } else {
                                q
                            }
                            val prompt = if (conversationHistory.isNotEmpty()) {
                                "$conversationHistory\n\nسؤال جديد:\n$grounded"
                            } else {
                                grounded
                            }
                            val result = AIClient.askLegalQuestion(prompt, this@ConsultActivity)
                            tvAnswer?.text = result
                            if (!result.startsWith("❌")) {
                                conversationHistory.append("\nالاستشارة: ").append(q)
                                    .append("\nالرأي القانوني:\n").append(result).append("\n")
                                etQuestion?.text?.clear()
                                etQuestion?.hint = "تابع نفس الاستشارة بسؤال إضافي..."
                            }
                        } catch (e: Throwable) {
                            tvAnswer?.text = "❌ تعذر إتمام الاستشارة حالياً: ${e.localizedMessage}"
                        } finally {
                            progressBar?.visibility = View.GONE
                            btnAsk.isEnabled = true
                        }
                    }
                }
            }

            btnCopy?.setOnClickListener {
                val txt = tvAnswer?.text?.toString() ?: ""
                if (txt.isNotEmpty()) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Consultation", txt))
                    Toast.makeText(this, "تم نسخ نص الاستشارة والرأي القانوني", Toast.LENGTH_SHORT).show()
                }
            }

            btnShare?.setOnClickListener {
                val txt = tvAnswer?.text?.toString() ?: ""
                if (txt.isNotEmpty()) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, txt)
                    }
                    startActivity(Intent.createChooser(intent, "مشاركة الرأي القانوني"))
                }
            }

            findViewById<Button>(R.id.btnPrint)?.setOnClickListener {
                val txt = tvAnswer?.text?.toString() ?: ""
                if (txt.isNotEmpty()) printConsultation(txt)
            }
        } catch (_: Exception) {
            Toast.makeText(this, "حدث خطأ غير متوقع", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun printConsultation(answer: String) {
        try {
            val wv = android.webkit.WebView(this)
            val esc = answer.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            val html = "<html dir=\"rtl\"><head><meta charset=\"utf-8\"></head>" +
                "<body style=\"font-family:sans-serif;padding:20px;line-height:1.7;\">" +
                "<h2>استشارة قانونية</h2>" +
                "<pre style=\"white-space: pre-wrap; font-family: inherit;\">$esc</pre>" +
                "<hr><p style=\"color:#666;font-size:12px;\">تطبيق المستشار القانوني الذكي</p>" +
                "</body></html>"
            wv.loadDataWithBaseURL(null, html, "text/HTML", "UTF-8", null)
            val pm = getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
            pm.print("استشارة_قانونية", wv.createPrintDocumentAdapter("استشارة_قانونية"), null)
        } catch (_: Exception) {
            Toast.makeText(this, "تعذر الطباعة", Toast.LENGTH_SHORT).show()
        }
    }
}
