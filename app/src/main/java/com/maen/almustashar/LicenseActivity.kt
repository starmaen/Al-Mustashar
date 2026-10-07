package com.maen.almustashar

import android.content.Intent
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

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val tvId = findViewById<TextView>(R.id.tvDeviceId)
        tvId.text = "معرّف جهازك: " + deviceId

        findViewById<Button>(R.id.btnCopyDeviceId).setOnClickListener {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("device_id", deviceId))
            Toast.makeText(this, "تم نسخ معرف الجهاز", Toast.LENGTH_SHORT).show()
        }

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
                LicenseManager.activateWithCode(deviceId, code) { success, message ->
                    runOnUiThread {
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                        if (success) {
                            startActivity(Intent(this, LoginActivity::class.java))
                            finish()
                        }
                    }
                }
            }
        }
    }

    private fun showOwnerBypassDialog() {
        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.setPadding(50, 40, 50, 10)
        val emailInput = EditText(this)
        emailInput.hint = "البريد الإلكتروني"
        layout.addView(emailInput)
        val passInput = EditText(this)
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
                                .setItems(arrayOf("الدخول كمستخدم", "الدخول كمالك")) { _, which ->
                                    if (which == 0) {
                                        startActivity(Intent(this, LoginActivity::class.java))
                                        finish()
                                    } else {
                                        startActivity(Intent(this, OwnerPanelActivity::class.java))
                                    }
                                }
                                .setCancelable(false)
                                .show()
                        } else {
                            Toast.makeText(this, "بيانات غير صحيحة", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}
