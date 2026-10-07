package com.maen.almustashar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class LicenseActivity : AppCompatActivity() {

    private var clickCount = 0
    private var lastClickTime: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (LicenseManager.isLicensed(this)) {
            goToMain()
            return
        }

        setContentView(R.layout.activity_license)

        val ivLogo = findViewById<ImageView>(R.id.ivLicenseLogo)
        val tvDeviceId = findViewById<TextView>(R.id.tvDeviceId)
        val btnCopy = findViewById<Button>(R.id.btnCopyDeviceId)
        val btnShare = findViewById<Button>(R.id.btnShareDeviceId)
        val etCode = findViewById<EditText>(R.id.etLicenseCode)
        val btnActivate = findViewById<Button>(R.id.btnActivate)

        val deviceId = DeviceUtils.getDeviceId(this)
        tvDeviceId.text = deviceId

        btnCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Device ID", deviceId))
            Toast.makeText(this, "تم نسخ معرّف الجهاز بنجاح", Toast.LENGTH_SHORT).show()
        }

        btnShare.setOnClickListener {
            val shareMsg = "معرّف جهازي لتفعيل تطبيق المستشار هو:\n$deviceId"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareMsg)
            }
            startActivity(Intent.createChooser(intent, "مشاركة معرّف الجهاز"))
        }

        btnActivate.setOnClickListener {
            val inputCode = etCode.text.toString().trim()
            if (inputCode.isEmpty()) {
                Toast.makeText(this, "يرجى كتابة كود التفعيل", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (LicenseManager.verifyAndSaveCode(this, deviceId, inputCode)) {
                Toast.makeText(this, "تم تفعيل الترخيص بنجاح!", Toast.LENGTH_LONG).show()
                goToMain()
            } else {
                Toast.makeText(this, "كود التفعيل غير صالح أو منتهي الصلاحية", Toast.LENGTH_LONG).show()
            }
        }

        ivLogo.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastClickTime < 1500) {
                clickCount++
            } else {
                clickCount = 1
            }
            lastClickTime = currentTime

            if (clickCount == 7) {
                clickCount = 0
                showOwnerAccessDialog()
            }
        }
    }

    private fun showOwnerAccessDialog() {
        val dialogView = LayoutInflater.from(this).inflate(android.R.layout.activity_list_item, null)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }

        val etEmail = EditText(this).apply {
            hint = "البريد الإلكتروني للمالك"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val etPass = EditText(this).apply {
            hint = "كلمة المرور"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        layout.addView(etEmail)
        layout.addView(etPass)

        AlertDialog.Builder(this)
            .setTitle("بوابة المالك")
            .setView(layout)
            .setPositiveButton("دخول لغرفة المالك") { _, _ ->
                verifyAndProceed(etEmail.text.toString(), etPass.text.toString(), targetOwner = true)
            }
            .setNeutralButton("دخول كمستخدم") { _, _ ->
                verifyAndProceed(etEmail.text.toString(), etPass.text.toString(), targetOwner = false)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun verifyAndProceed(email: String, pass: String, targetOwner: Boolean) {
        if (LicenseManager.verifyOwner(email, pass)) {
            if (targetOwner) {
                startActivity(Intent(this, OwnerPanelActivity::class.java))
            } else {
                goToMain()
            }
        } else {
            Toast.makeText(this, "بيانات الاعتماد غير صحيحة", Toast.LENGTH_SHORT).show()
        }
    }

    private fun goToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
