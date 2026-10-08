import subprocess

# استرجاع MainActivity.kt من Git لإزالة كل التكرارات والتراكمات الخاطئة
subprocess.run(["git", "checkout", "HEAD~1", "--", "app/src/main/java/com/maen/almustashar/MainActivity.kt"])

path = "app/src/main/java/com/maen/almustashar/MainActivity.kt"
with open(path, "r", encoding="utf-8") as f:
    lines = f.readlines()

new_lines = []
imports_inserted = False

for line in lines:
    new_lines.append(line)
    if line.startswith("package com.maen.almustashar") and not imports_inserted:
        new_lines.append("import android.content.Intent\n")
        new_lines.append("import android.widget.EditText\n")
        new_lines.append("import android.widget.LinearLayout\n")
        new_lines.append("import android.widget.Toast\n")
        new_lines.append("import androidx.appcompat.app.AlertDialog\n")
        imports_inserted = True

code = "".join(new_lines)

# كود الـ 7 نقرات
seven_clicks_method = """
    private var logoClickCount = 0
    private var lastLogoClickTime = 0L

    private fun setupSevenClicksOwnerAccess() {
        val logo = findViewById<android.view.View>(R.id.ivLogo) ?: return
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
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val etPass = EditText(ctx).apply {
            hint = "كلمة المرور السرية"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
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
"""

# استدعاء setupSevenClicksOwnerAccess داخل onCreate
if "setupSevenClicksOwnerAccess()" not in code:
    import re
    code = re.sub(
        r'(super\.onCreate\(.*?\))',
        r'\1\n        setupSevenClicksOwnerAccess()',
        code,
        count=1
    )
    code = code.rstrip()
    if code.endswith("}"):
        code = code[:-1] + seven_clicks_method + "\n}\n"

with open(path, "w", encoding="utf-8") as f:
    f.write(code)

print("✅ تم تجهيز MainActivity.kt بنجاح مع DeviceUtils.getDeviceId.")
