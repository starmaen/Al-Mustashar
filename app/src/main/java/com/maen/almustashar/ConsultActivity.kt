package com.maen.almustashar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.maen.almustashar.databinding.ActivityConsultBinding
import kotlinx.coroutines.launch

class ConsultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConsultBinding
    private val conversationHistory = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConsultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnAsk.setOnClickListener {
            val q = binding.etQuestion.text.toString().trim()
            if (q.isNotEmpty()) {
                binding.progressBar.visibility = View.VISIBLE
                binding.btnAsk.isEnabled = false

                if (conversationHistory.isNotEmpty()) {
                    conversationHistory.append("\n\nمتابعة واستيضاح:\n").append(q)
                } else {
                    conversationHistory.append(q)
                }

                lifecycleScope.launch {
                    val result = AIClient.askLegalQuestion(conversationHistory.toString())
                    binding.progressBar.visibility = View.GONE
                    binding.btnAsk.isEnabled = true

                    binding.tvAnswer.text = result
                    if (!result.startsWith("❌")) {
                        conversationHistory.append("\n\nالرد:\n").append(result)
                        binding.etQuestion.text.clear()
                        binding.etQuestion.hint = "تابع نفس الاستشارة بسؤال إضافي..."
                    }
                }
            }
        }

        binding.btnCopy.setOnClickListener {
            val txt = binding.tvAnswer.text.toString()
            if (txt.isNotEmpty()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Consultation", txt))
                Toast.makeText(this, "تم نسخ النص", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnShare.setOnClickListener {
            val txt = binding.tvAnswer.text.toString()
            if (txt.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, txt)
                }
                startActivity(Intent.createChooser(intent, "مشاركة الاستشارة"))
            }
        }
    }
}
