package com.maen.almustashar

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView

class MainActivity : AppCompatActivity() {

    private var logoClickCount = 0
    private var lastLogoClickTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupSevenClicksOwnerAccess()
        setupNavigationCards()
    }

    private fun setupSevenClicksOwnerAccess() {
        val logo = findViewById<View>(R.id.ivLogo) ?: return
        logo.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastLogoClickTime > 2000L) {
                logoClickCount = 0
            }
            lastLogoClickTime = now
            logoClickCount++

            if (logoClickCount == 7) {
                logoClickCount = 0
                showOwnerAccessDialogProgrammatic()
            } else if (logoClickCount in 4..6) {
                val remaining = 7 - logoClickCount
                Toast.makeText(this, "تبقى $remaining نقرات للوصول لخيارات المالك", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showOwnerAccessDialogProgrammatic() {
        val ctx = this
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }

        val etEmail = EditText(ctx).apply {
            hint = "البريد الإلكتروني للمالك"
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val etPass = EditText(ctx).apply {
            hint = "كلمة المرور السرية"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        layout.addView(etEmail)
        layout.addView(etPass)

        AlertDialog.Builder(ctx)
            .setTitle("🔐 الدخول لحجرة المالك")
            .setMessage("أدخل بيانات الاعتماد للمتابعة وتوليد الأكواد:")
            .setView(layout)
            .setPositiveButton("دخول") { dialog, _ ->
                val email = etEmail.text.toString().trim()
                val pass = etPass.text.toString().trim()
                if (LicenseManager.verifyOwner(email, pass)) {
                    val deviceId = DeviceUtils.getDeviceId(ctx)
                    LicenseManager.grantOwnerDeviceLicense(ctx, deviceId)
                    dialog.dismiss()
                    startActivity(Intent(ctx, OwnerPanelActivity::class.java))
                } else {
                    Toast.makeText(ctx, "بيانات الاعتماد غير صحيحة", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("إلغاء") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun setupNavigationCards() {
        findViewById<View?>(R.id.cardSearch)?.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        findViewById<View?>(R.id.cardConsultation)?.setOnClickListener {
            startActivity(Intent(this, ConsultationActivity::class.java))
        }
        findViewById<View?>(R.id.cardDrafting)?.setOnClickListener {
            startActivity(Intent(this, DraftingActivity::class.java))
        }
        findViewById<View?>(R.id.cardArchive)?.setOnClickListener {
            startActivity(Intent(this, ArchiveActivity::class.java))
        }
        findViewById<View?>(R.id.cardSettings)?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
}
