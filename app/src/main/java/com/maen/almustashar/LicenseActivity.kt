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
        val emailInput = EditText(this)
        emailInput.hint = "البريد الإلكتروني"
        val passInput = EditText(this)
        passInput.hint = "الرقم السري"
        passInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.setPadding(40, 20, 40, 20)
        layout.addView(emailInput)
        layout.addView(passInput)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("دخول المالك")
            .setView(layout)
            .setPositiveButton("دخول") { _, _ ->
                val email = emailInput.text.toString().trim()
                val pass = passInput.text.toString().trim()
                LicenseManager.verifyOwnerPassword(email, pass) { ok ->
                    if (ok) {
                        getSharedPreferences("owner_prefs", MODE_PRIVATE).edit().putBoolean("ownerVerified", true).apply()
                        val i = android.content.Intent(this, MainActivity::class.java)
                        i.putExtra("ownerBypass", true)
                        i.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(i)
                        finish()
                    } else {
                        Toast.makeText(this, "بيانات غير صحيحة", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

}
