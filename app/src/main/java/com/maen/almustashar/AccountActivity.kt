package com.maen.almustashar

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
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

        val tvEmail = findViewById<TextView>(R.id.tvAccountEmail)
        val btnLogout = findViewById<Button>(R.id.btnLogout)
        val btnResetPass = findViewById<Button>(R.id.btnResetPassword)

        if (user != null) {
            tvEmail?.text = user.email
        } else {
            tvEmail?.text = "غير مسجل الدخول"
        }

        btnResetPass?.setOnClickListener {
            val email = user?.email
            if (!email.isNullOrEmpty()) {
                auth.sendPasswordResetEmail(email)
                    .addOnSuccessListener {
                        Toast.makeText(this, "تم إرسال رابط إعادة تعيين كلمة المرور إلى بريدك", Toast.LENGTH_LONG).show()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "فشل الإرسال: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
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
