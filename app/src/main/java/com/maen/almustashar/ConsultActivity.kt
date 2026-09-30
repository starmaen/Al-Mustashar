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

    private lateinit var etQuestion: EditText
    private lateinit var btnAsk: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvAnswer: TextView
    private lateinit var btnCopy: Button
    private lateinit var btnShare: Button

    private val conversationHistory = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_consult)

        etQuestion = findViewById(R.id.etQuestion)
        btnAsk = findViewById(R.id.btnAsk)
        progressBar = findViewById(R.id.progressBar)
        tvAnswer = findViewById(R.id.tvAnswer)
        btnCopy = findViewById(R.id.btnCopy)
        btnShare = findViewById(R.id.btnShare)

        btnAsk.setOnClickListener {
            val q = etQuestion.text.toString().trim()
            if (q.isNotEmpty()) {
                askConsultation(q)
            }
        }

        btnCopy.setOnClickListener {
            val txt = tvAnswer.text.toString()
            if (txt.isNotEmpty()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Consultation", txt))
                Toast.makeText(this, "تم نسخ نص الاستشارة بنجاح", Toast.LENGTH_SHORT).show()
            }
        }

        btnShare.setOnClickListener {
            val txt = tvAnswer.text.toString()
            if (txt.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, txt)
                }
                startActivity(Intent.createChooser(intent, "مشاركة الاستشارة"))
            }
        }
    }

    private fun askConsultation(question: String) {
        progressBar.visibility = View.VISIBLE
        btnAsk.isEnabled = false

        if (conversationHistory.isNotEmpty()) {
            conversationHistory.append("\n\nسؤال استيضاحي ومتابعة:\n").append(question)
        } else {
            conversationHistory.append(question)
        }

        val promptToSend = conversationHistory.toString()

        lifecycleScope.launch {
            val result = AIClient.askLegalQuestion(promptToSend)
            progressBar.visibility = View.GONE
            btnAsk.isEnabled = true

            if (result.isNotEmpty() && !result.startsWith("❌")) {
                tvAnswer.text = result
                conversationHistory.append("\n\nإجابة المستشار:\n").append(result)
                etQuestion.text.clear()
                etQuestion.hint = "اسأل سؤالاً تالياً لمتابعة نفس الاستشارة..."
            } else {
                tvAnswer.text = result
            }
        }
    }
}
