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

        findViewById<Button>(R.id.btnActivate).setOnClickListener {
            val code = findViewById<EditText>(R.id.etActivationCode).text.toString().trim()
            if (code.isEmpty()) {
                Toast.makeText(this, "الرجاء إدخال كود التفعيل", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "سيتم ربط التفعيل الفعلي بالخطوة القادمة", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
