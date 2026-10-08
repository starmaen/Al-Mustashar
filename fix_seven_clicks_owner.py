import re

# استعادة MainActivity.kt إلى أصلها من Git أولاً للتخلص من أي أخطاء أو تكرار
import subprocess
subprocess.run(["git", "checkout", "app/src/main/java/com/maen/almustashar/MainActivity.kt"])

path = "app/src/main/java/com/maen/almustashar/MainActivity.kt"
with open(path, "r", encoding="utf-8") as f:
    code = f.read()

# إضافة الاستيرادات اللازمة بدون تكرار
needed_imports = """import android.content.Intent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.maen.almustashar.LicenseManager
import com.maen.almustashar.OwnerPanelActivity
"""

# إدراج الاستيرادات بعد حزمة المشروع مباشرة
code = re.sub(r'(package\s+com\.maen\.almustashar\s*\n)', r'\1\n' + needed_imports, code, count=1)

# كود منطق الـ 7 نقرات وحوار المالك البرمجي
secret_owner_logic = """
    private var logoClickCount = 0
    private var lastLogoClickTime = 0L

    private fun setupSevenClicksOwnerAccess() {
        val logo = findViewById<android.view.View>(R.id.ivLogo) ?: return
        logo.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastLogoClickTime > 2000) {
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
                    val deviceId = LicenseManager.getDeviceId(ctx)
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

# استدعاء دالة الـ 7 نقرات في نهاية onCreate
if "setupSevenClicksOwnerAccess()" not in code:
    code = re.sub(
        r'(override\s+fun\s+onCreate\s*\([^)]*\)\s*\{[\s\S]*?super\.onCreate\([^)]*\))',
        r'\1\n        setupSevenClicksOwnerAccess()',
        code,
        count=1
    )
    # إضافة الدوال في نهاية الكلاس قبل القوس الأخير
    code = code.rstrip()
    if code.endswith("}"):
        code = code[:-1] + secret_owner_logic + "\n}\n"

with open(path, "w", encoding="utf-8") as f:
    f.write(code)

print("✅ تم ضبط ميزة النقر 7 مرات على ivLogo بنجاح تام.")
