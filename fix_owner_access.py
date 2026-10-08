import re

path = "app/src/main/java/com/maen/almustashar/MainActivity.kt"
with open(path, "r", encoding="utf-8") as f:
    code = f.read()

if "OwnerPanelActivity" not in code:
    code = code.replace("import android.os.Bundle", "import android.os.Bundle\nimport android.content.Intent\nimport android.widget.EditText\nimport android.widget.Button\nimport android.widget.Toast\nimport androidx.appcompat.app.AlertDialog\nimport com.maen.almustashar.LicenseManager\nimport com.maen.almustashar.OwnerPanelActivity")

owner_dialog_code = """
    private fun setupOwnerSecretAccess() {
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar) 
            ?: findViewById<android.view.View>(R.id.topAppBar) 
            ?: findViewById<android.view.View>(android.R.id.content)
            
        toolbar.setOnLongClickListener {
            showOwnerLoginDialog()
            true
        }
    }

    private fun showOwnerLoginDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_owner_access, null)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        val etEmail = view.findViewById<EditText>(R.id.dialogOwnerEmail)
        val etPass = view.findViewById<EditText>(R.id.dialogOwnerPass)
        val btnStudio = view.findViewById<Button>(R.id.btnDialogOwnerStudio)
        val btnOwner = view.findViewById<Button>(R.id.btnDialogOwnerPanel)

        btnOwner?.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val pass = etPass.text.toString().trim()
            if (LicenseManager.verifyOwner(email, pass)) {
                dialog.dismiss()
                startActivity(Intent(this, OwnerPanelActivity::class.java))
            } else {
                Toast.makeText(this, "بيانات المالك غير صحيحة", Toast.LENGTH_SHORT).show()
            }
        }

        btnStudio?.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val pass = etPass.text.toString().trim()
            if (LicenseManager.verifyOwner(email, pass)) {
                dialog.dismiss()
                startActivity(Intent(this, OwnerPanelActivity::class.java))
            } else {
                Toast.makeText(this, "بيانات المالك غير صحيحة", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }
"""

if "setupOwnerSecretAccess()" not in code:
    code = re.sub(r'(super\.onCreate\(.*?\))', r'\1\n        setupOwnerSecretAccess()', code)
    code = code.rstrip()
    if code.endswith("}"):
        code = code[:-1] + owner_dialog_code + "\n}\n"

with open(path, "w", encoding="utf-8") as f:
    f.write(code)

print("✅ تم تفعيل مدخل حجرة المالك بالضغط المطول.")
