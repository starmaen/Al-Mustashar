package com.maen.almustashar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Date

class OwnerPanelActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_owner_panel)

        val etCustomerName = findViewById<EditText>(R.id.etCustomerName)
        val etCustomerDeviceId = findViewById<EditText>(R.id.etCustomerDeviceId)
        val rbMonth = findViewById<RadioButton>(R.id.rbMonth)
        val rbYear = findViewById<RadioButton>(R.id.rbYear)
        val rbLifetime = findViewById<RadioButton>(R.id.rbLifetime)
        val btnGenerate = findViewById<Button>(R.id.btnGenerate)
        val layoutResult = findViewById<LinearLayout>(R.id.layoutResult)
        val tvGeneratedCode = findViewById<TextView>(R.id.tvGeneratedCode)
        val btnCopyCode = findViewById<Button>(R.id.btnCopyCode)
        val btnShareCode = findViewById<Button>(R.id.btnShareCode)

        btnGenerate.setOnClickListener {
            val name = etCustomerName.text.toString().trim()
            val deviceId = etCustomerDeviceId.text.toString().trim()

            if (deviceId.isEmpty()) {
                Toast.makeText(this, "يرجى إدخال معرّف الجهاز", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val durationDays = when {
                rbMonth.isChecked -> 30
                rbYear.isChecked -> 365
                rbLifetime.isChecked -> -1
                else -> 30
            }

            val durationLabel = when (durationDays) {
                30 -> "شهر"
                365 -> "سنة"
                else -> "دائم"
            }

            val code = LicenseManager.generateCode(deviceId, durationDays)
            tvGeneratedCode.text = code
            layoutResult.visibility = View.VISIBLE

            val record = hashMapOf(
                "customerName" to (if (name.isEmpty()) "غير محدد" else name),
                "deviceId" to deviceId.uppercase(),
                "duration" to durationLabel,
                "activationCode" to code,
                "createdAt" to Date()
            )

            FirebaseFirestore.getInstance().collection("licenses")
                .add(record)
                .addOnSuccessListener {
                    Toast.makeText(this, "تم توليد الكود وأرشفته في السجل بنجاح", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener {
                    Toast.makeText(this, "تم توليد الكود محلياً (تعذر الحفظ بالسحاب حالياً)", Toast.LENGTH_SHORT).show()
                }
        }

        btnCopyCode.setOnClickListener {
            val code = tvGeneratedCode.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("License Code", code))
            Toast.makeText(this, "تم نسخ كود التفعيل", Toast.LENGTH_SHORT).show()
        }

        btnShareCode.setOnClickListener {
            val code = tvGeneratedCode.text.toString()
            val shareMsg = "كود تفعيل تطبيق المستشار القانوني الخاص بك هو:\n$code"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareMsg)
            }
            startActivity(Intent.createChooser(intent, "مشاركة كود التفعيل"))
        }
    }
}
