package com.maen.almustashar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

class AccountActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_account)

        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser

        findViewById<TextView>(R.id.tvName).text = user?.displayName ?: "مستخدم"
        findViewById<TextView>(R.id.tvEmail).text = user?.email ?: "غير مسجل"

        // 📧 نسخ البريد
        findViewById<LinearLayout>(R.id.rowEmail).setOnClickListener {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("email", "starsyia2500@gmail.com"))
            Toast.makeText(this, "تم نسخ البريد", Toast.LENGTH_SHORT).show()
        }

        // 💬 واتساب
        findViewById<LinearLayout>(R.id.rowWhatsApp).setOnClickListener {
            try {
                val url = "https://wa.me/963938466549"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(this, "+963 938 466 549", Toast.LENGTH_LONG).show()
            }
        }

        // 📢 تلغرام
        findViewById<LinearLayout>(R.id.rowTelegram).setOnClickListener {
            try {
                val url = "https://t.me/maenstar"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(this, "@maenstar", Toast.LENGTH_LONG).show()
            }
        }

        // تسجيل الخروج
        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            auth.signOut()
            Toast.makeText(this, "تم تسجيل الخروج", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private var ownerTapsPanel = 0
    private var lastTapPanel = 0L
        findViewById<android.view.View>(R.id.ivAccountLogo)?.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTapPanel > 2000) ownerTapsPanel = 0
            lastTapPanel = now
            ownerTapsPanel++
            if (ownerTapsPanel >= 7) {
                ownerTapsPanel = 0
            }
        }
    }

        val emailInput = android.widget.EditText(this)
        emailInput.hint = "البريد الإلكتروني"
        val passInput = android.widget.EditText(this)
        passInput.hint = "الرقم السري"
        passInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.setPadding(40, 20, 40, 20)
        layout.addView(emailInput)
        layout.addView(passInput)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("دخول المالك - حجرة التفعيل")
            .setView(layout)
            .setPositiveButton("دخول") { _, _ ->
                val email = emailInput.text.toString().trim()
                val pass = passInput.text.toString().trim()
                    runOnUiThread {
                        if (ok) {
                        } else {
                            android.widget.Toast.makeText(this, "بيانات غير صحيحة", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

}
