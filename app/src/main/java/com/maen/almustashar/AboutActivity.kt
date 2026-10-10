package com.maen.almustashar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

// شاشة التعريف بالتطبيق وطريقة استخدامه (تُفتح من زر حول التطبيق)
class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_about)
            val tv = findViewById<TextView>(R.id.tvAboutGuide)
            try {
                assets.open("about_guide.txt").bufferedReader().use { tv.text = it.readText() }
            } catch (_: Exception) {
                tv.text = "المستشار القانوني الذكي — الإصدار 1.0.0"
            }
            findViewById<Button>(R.id.btnAboutContact)?.setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:starsyria2500@gmail.com")))
                } catch (_: Exception) {
                    Toast.makeText(this, "تعذر فتح البريد", Toast.LENGTH_SHORT).show()
                }
            }
            findViewById<Button>(R.id.btnAboutAccount)?.setOnClickListener {
                try {
                    startActivity(Intent(this, AccountActivity::class.java))
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر فتح الصفحة", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
