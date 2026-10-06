package com.maen.almustashar

import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LicenseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_license)
        val tvId = findViewById<android.widget.TextView>(R.id.tvDeviceId)
        findViewById<android.widget.Button>(R.id.btnCopyDeviceId).setOnClickListener {
            val fullText = tvId.text.toString()
            val idOnly = if (fullText.contains(":")) fullText.substringAfterLast(":").trim() else fullText.trim()
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("device_id", idOnly))
            android.widget.Toast.makeText(this, "تم نسخ معرف الجهاز", android.widget.Toast.LENGTH_SHORT).show()
        }

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        findViewById<TextView>(R.id.tvDeviceId).text = "معرّف جهازك: " + deviceId

        var ownerTapsLogo = 0
        var lastTapLogo = 0L
        findViewById<android.widget.ImageView>(R.id.ivLicenseLogo).setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTapLogo > 2000) ownerTapsLogo = 0
            lastTapLogo = now
            ownerTapsLogo++
            if (ownerTapsLogo >= 7) {
                ownerTapsLogo = 0
                showOwnerBypassDialog()
            }
        }


        findViewById<Button>(R.id.btnActivate).setOnClickListener {
            val code = findViewById<EditText>(R.id.etActivationCode).text.toString().trim()
            if (code.isEmpty()) {
                Toast.makeText(this, "الرجاء إدخال كود التفعيل", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "سيتم ربط التفعيل الفعلي بالخطوة القادمة", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showOwnerBypassDialog() {
        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.setPadding(50, 40, 50, 10)
        val emailInput = android.widget.EditText(this)
        emailInput.hint = "البريد الإلكتروني"
        layout.addView(emailInput)
        val passInput = android.widget.EditText(this)
        passInput.hint = "الرقم السري"
        passInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        layout.addView(passInput)
        android.app.AlertDialog.Builder(this)
            .setTitle("دخول المالك")
            .setView(layout)
            .setPositiveButton("دخول") { _, _ ->
                val email = emailInput.text.toString().trim()
                val pass = passInput.text.toString().trim()
                LicenseManager.verifyOwnerPassword(email, pass) { success ->
                    runOnUiThread {
                        if (success) {
                            android.app.AlertDialog.Builder(this)
                                .setTitle("اختر الإجراء")
                                .setItems(arrayOf("دخول كمستخدم عادي", "حجرة المالك (توليد الأكواد)")) { _, which ->
                                    if (which == 0) {
                                        getSharedPreferences("owner_prefs", MODE_PRIVATE).edit().putBoolean("ownerVerified", true).apply()
                                        val intent = android.content.Intent(this, MainActivity::class.java)
                                        intent.putExtra("ownerBypass", true)
                                        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                                        startActivity(intent)
                                        finish()
                                    } else {
                                        startActivity(android.content.Intent(this, OwnerPanelActivity::class.java))
                                    }
                                }
                                .setCancelable(false)
                                .show()
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
