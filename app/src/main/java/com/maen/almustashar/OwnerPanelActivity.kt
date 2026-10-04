package com.maen.almustashar

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class OwnerPanelActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_owner_panel)

        val auth = FirebaseAuth.getInstance()
        val db = FirebaseFirestore.getInstance()

        val spinner = findViewById<android.widget.Spinner>(R.id.spinnerDuration)
        val options = listOf("شهر", "سنة", "دائم")
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)

        val etDeviceId = findViewById<android.widget.EditText>(R.id.etTargetDeviceId)
        val tvResultCode = findViewById<android.widget.TextView>(R.id.tvResultCode)

        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(this, "الرجاء تسجيل الدخول أولاً", Toast.LENGTH_LONG).show()
            finish()
        } else {
            db.collection("admins").document(user.uid).get()
                .addOnSuccessListener { doc ->
                    if (!doc.exists()) {
                        Toast.makeText(this, "هذا الحساب غير مخوَّل", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(this, "تعذر التحقق من الصلاحية", Toast.LENGTH_LONG).show()
                    finish()
                }
        }

        findViewById<android.widget.Button>(R.id.btnGenerateCode).setOnClickListener {
            val deviceId = etDeviceId.text.toString().trim()
            if (deviceId.isEmpty()) {
                Toast.makeText(this, "أدخل معرّف الجهاز المستهدف", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val durationType = when (spinner.selectedItem.toString()) {
                "شهر" -> "month"
                "سنة" -> "year"
                else -> "permanent"
            }
            LicenseManager.issueLicense(deviceId, durationType) { success, result ->
                if (success) {
                    tvResultCode.text = "كود التفعيل: " + result
                    Toast.makeText(this, "تم إنشاء الكود بنجاح", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
