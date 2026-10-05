package com.maen.almustashar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LicenseActivity : AppCompatActivity() {

    private lateinit var deviceId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupOwnerGateTaps()
        setContentView(R.layout.activity_license)

        deviceId = DeviceUtils.getDeviceId(this)

        val tvDeviceId = findViewById<android.widget.TextView>(R.id.tvDeviceId)
        tvDeviceId.text = deviceId

        findViewById<android.widget.ImageView>(R.id.ivLogoLicense)?.let {
            OwnerAccessHelper.registerTap(this, it)
        }

        findViewById<android.widget.Button>(R.id.btnCopyDeviceId).setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("deviceId", deviceId))
            Toast.makeText(this, "تم نسخ معرّف الجهاز", Toast.LENGTH_SHORT).show()
        }

        findViewById<android.widget.Button>(R.id.btnWhatsapp).setOnClickListener {
            val msg = Uri.encode("مرحباً، أرغب بتفعيل تطبيق المستشار. معرّف جهازي:\n" + deviceId)
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/963938466549?text=" + msg)))
            } catch (e: Exception) {
                Toast.makeText(this, "تعذر فتح واتساب", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<android.widget.Button>(R.id.btnTelegram).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/starsyria")))
            } catch (e: Exception) {
                Toast.makeText(this, "تعذر فتح تلغرام", Toast.LENGTH_SHORT).show()
            }
        }

        val etCode = findViewById<EditText>(R.id.etActivationCode)
        findViewById<android.widget.Button>(R.id.btnActivate).setOnClickListener {
            val code = etCode.text.toString().trim()
            if (code.isEmpty()) {
                Toast.makeText(this, "أدخل كود التفعيل", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            LicenseManager.activateWithCode(this, deviceId, code) { success, message ->
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                if (success) {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            }
        }
    }


    private var ownerTapsGate = 0
    private var lastTapGate = 0L
    private fun setupOwnerGateTaps(){
        findViewById<android.view.View>(R.id.ivLogoLicense)?.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTapGate > 2000) ownerTapsGate = 0
            lastTapGate = now
            ownerTapsGate++
            if (ownerTapsGate >= 7) {
                ownerTapsGate = 0
                OwnerAccessHelper.showOwnerLoginForBypass(this)
            }
        }
    }
}
