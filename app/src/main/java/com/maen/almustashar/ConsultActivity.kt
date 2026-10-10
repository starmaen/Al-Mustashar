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
import kotlinx.coroutines.launch

class ConsultActivity : AppCompatActivity() {

    private val conversationHistory = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_consult)

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
                            // تأسيس الاستشارة على مواد القوانين المثبتة أولاً (للاستشهاد الدقيق)
                            val localLaws = try {
                                LawsRepository.searchRelevantLaws(q).take(3500)
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
        } catch (_: Exception) {
            Toast.makeText(this, "حدث خطأ غير متوقع", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
