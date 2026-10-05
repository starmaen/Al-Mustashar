package com.maen.almustashar

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class OwnerPanelActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_owner_panel)

        val spinner = findViewById<Spinner>(R.id.spinnerDuration)
        val options = listOf("شهر", "سنة", "دائم")
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)

        findViewById<Button>(R.id.btnGenerateCode).setOnClickListener {
            val deviceId = findViewById<EditText>(R.id.etTargetDeviceId).text.toString().trim()
            if (deviceId.isEmpty()) {
                Toast.makeText(this, "أدخل معرّف الجهاز", Toast.LENGTH_SHORT).show()
            } else {
                val durationType = when (spinner.selectedItemPosition) {
                    0 -> "month"
                    1 -> "year"
                    else -> "forever"
                }
                LicenseManager.issueLicense(deviceId, durationType) { success, result ->
                    runOnUiThread {
                        findViewById<TextView>(R.id.tvResultCode).text = if (success) "الكود: " + result else result
                    }
                }
            }
        }

        findViewById<Button>(R.id.btnExitOwnerPanel).setOnClickListener { finish() }
    }
}
