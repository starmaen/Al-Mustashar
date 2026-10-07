package com.maen.almustashar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

class AccountActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account)

        auth = FirebaseAuth.getInstance()
        val user = auth.currentUser

        val tvName = findViewById<TextView>(R.id.tvName)
        val tvEmail = findViewById<TextView>(R.id.tvEmail)
        val btnLogout = findViewById<Button>(R.id.btnLogout)

        if (user != null) {
            tvEmail?.text = user.email
            tvName?.text = user.displayName ?: "مستخدم مسجل"
        } else {
            tvEmail?.text = "غير مسجل الدخول"
            tvName?.text = "زائر"
        }

        findViewById<android.view.View>(R.id.rowEmail)?.setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:starsyria2500@gmail.com")
            }
            try { startActivity(intent) } catch (_: Exception) {}
        }

        findViewById<android.view.View>(R.id.rowWhatsApp)?.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://wa.me/963938466549")
            }
            try { startActivity(intent) } catch (_: Exception) {}
        }

        findViewById<android.view.View>(R.id.rowTelegram)?.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://t.me/maenstar")
            }
            try { startActivity(intent) } catch (_: Exception) {}
        }

        btnLogout?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("تسجيل الخروج")
                .setMessage("هل أنت متأكد من رغبتك في تسجيل الخروج؟")
                .setPositiveButton("نعم") { _, _ ->
                    auth.signOut()
                    val intent = Intent(this, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }
}
