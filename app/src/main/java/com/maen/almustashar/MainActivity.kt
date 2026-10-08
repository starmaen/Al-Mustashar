package com.maen.almustashar


import android.content.Intent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.maen.almustashar.LicenseManager
import com.maen.almustashar.OwnerPanelActivity
import android.content.Intent
import android.os.Bundle
import android.content.Intent
import android.widget.EditText
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.maen.almustashar.LicenseManager
import com.maen.almustashar.OwnerPanelActivity
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.firebase.auth.FirebaseAuth
import com.maen.almustashar.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var drawer: DrawerLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupSevenClicksOwnerAccess()
        setupOwnerSecretAccess()

        if (!LicenseManager.isLicensed(this)) {
            val intent = Intent(this, LicenseActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        drawer = findViewById(R.id.drawerLayout)
        auth = FirebaseAuth.getInstance()

        findViewById<ImageView>(R.id.ivMenu).setOnClickListener {
            drawer.openDrawer(GravityCompat.END)
        }

        binding.ivLogo.setOnClickListener {
            drawer.openDrawer(GravityCompat.END)
        }

        binding.btnAbout.setOnClickListener {
            showAboutDialog()
        }

        findViewById<View>(R.id.navCases).setOnClickListener {
            drawer.closeDrawer(GravityCompat.END)
            requireLogin {
                startActivity(Intent(this, CasesActivity::class.java))
            }
        }

        findViewById<View>(R.id.navGeneral)?.setOnClickListener {
            drawer.closeDrawer(GravityCompat.END)
            startActivity(Intent(this, GeneralSearchActivity::class.java))
        }

        findViewById<View>(R.id.navQueries).setOnClickListener {
            drawer.closeDrawer(GravityCompat.END)
            requireLogin {
                startActivity(Intent(this, QueriesActivity::class.java))
            }
        }

        findViewById<View>(R.id.navSettings).setOnClickListener {
            drawer.closeDrawer(GravityCompat.END)
            startActivity(Intent(this, AccountActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (!LicenseManager.isLicensed(this)) {
            startActivity(Intent(this, LicenseActivity::class.java))
            finish()
            return
        }
        updateUI()
    }

    private fun updateUI() {
        val user = auth.currentUser
        if (user != null) {
            binding.tvWelcome.text = "مرحباً، ${user.email}"
            binding.btnLogin.text = "حسابي"
            binding.btnConsult.visibility = View.VISIBLE
            binding.btnSearch.visibility = View.VISIBLE
            binding.btnConsult.setOnClickListener {
                startActivity(Intent(this, ConsultActivity::class.java))
            }
            binding.btnGeneralSearch?.setOnClickListener {
                startActivity(Intent(this, GeneralSearchActivity::class.java))
            }
            binding.btnSearch.setOnClickListener {
                startActivity(Intent(this, SearchActivity::class.java))
            }
            binding.btnLogin.setOnClickListener {
                startActivity(Intent(this, AccountActivity::class.java))
            }
        } else {
            binding.tvWelcome.text = "مرحباً بك في المستشار القانوني\nسجّل الدخول للاستفادة من الخدمات"
            binding.btnLogin.text = "تسجيل الدخول"
            binding.btnConsult.visibility = View.GONE
            binding.btnSearch.visibility = View.GONE
            binding.btnLogin.setOnClickListener {
                startActivity(Intent(this, LoginActivity::class.java))
            }
        }
    }

    private fun requireLogin(action: () -> Unit) {
        if (auth.currentUser == null) {
            AlertDialog.Builder(this)
                .setTitle("تسجيل الدخول مطلوب")
                .setMessage("هذه الميزة تحتاج تسجيل الدخول. هل تريد تسجيل الدخول الآن؟")
                .setPositiveButton("تسجيل الدخول") { _, _ ->
                    startActivity(Intent(this, LoginActivity::class.java))
                }
                .setNegativeButton("لاحقاً", null)
                .show()
        } else {
            action()
        }
    }

    override fun onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.END)) {
            drawer.closeDrawer(GravityCompat.END)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun showAboutDialog() {
        val msg = """
            المستشار القانوني الذكي
            الإصدار: 1.0.0
            للتواصل: starsyria2500@gmail.com
            +963 938 466 549
            @maenstar
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle("حول التطبيق")
            .setMessage(msg)
            .setPositiveButton("حسناً", null)
            .show()
    }

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

}
