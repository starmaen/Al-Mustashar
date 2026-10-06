package com.maen.almustashar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class OwnerPanelActivity : AppCompatActivity() {
    private var lastGeneratedCode: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_owner_panel)

        val spinner = findViewById<Spinner>(R.id.spinnerDuration)
        val options = listOf("شهر", "سنة", "دائم")
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, options) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(Color.BLACK)
                return view
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                view.setTextColor(Color.BLACK)
                view.setBackgroundColor(Color.WHITE)
                return view
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        findViewById<Button>(R.id.btnGenerateCode).setOnClickListener {
            val deviceId = findViewById<EditText>(R.id.etTargetDeviceId).text.toString().trim()
            if (deviceId.isEmpty()) {
                Toast.makeText(this, "أدخل معرّف الجهاز", Toast.LENGTH_SHORT).show()
            } else {
                val durationType = when (spinner.selectedItemPosition) {
                    0 -> "month"; 1 -> "year"; else -> "forever"
                }
                LicenseManager.issueLicense(deviceId, durationType) { success, result ->
                    runOnUiThread {
                        if (success) {
                            lastGeneratedCode = result
                            findViewById<TextView>(R.id.tvResultCode).text = "الكود: " + result
                            findViewById<Button>(R.id.btnCopyCode).visibility = View.VISIBLE
                        } else {
                            findViewById<TextView>(R.id.tvResultCode).text = result
                            findViewById<Button>(R.id.btnCopyCode).visibility = View.GONE
                        }
                    }
                }
            }
        }

        findViewById<Button>(R.id.btnCopyCode).setOnClickListener {
            if (lastGeneratedCode.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("activation_code", lastGeneratedCode))
                Toast.makeText(this, "تم نسخ الكود", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnExitOwnerPanel).setOnClickListener { finish() }
    }
}