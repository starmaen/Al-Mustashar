package com.maen.almustashar

import android.content.Intent
import android.os.Bundle
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

        if (!intent.getBooleanExtra("ownerBypass", false) &&
    !getSharedPreferences("owner_prefs", MODE_PRIVATE).getBoolean("ownerVerified", false)) {
    val deviceId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
    LicenseManager.checkRemoteLicense(deviceId) { valid ->
        runOnUiThread {
            if (!valid) {
                startActivity(android.content.Intent(this@MainActivity, LicenseActivity::class.java))
                finish()
            } else {
                proceedAfterLicenseCheck()
            }
        }
    }
} else {
    proceedAfterLicenseCheck()
}
    }

    private fun proceedAfterLicenseCheck() {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        drawer = findViewById(R.id.drawerLayout)
        auth = FirebaseAuth.getInstance()

        // زر القائمة
        findViewById<ImageView>(R.id.ivMenu).setOnClickListener {
            drawer.openDrawer(GravityCompat.END)
        }
        binding.ivLogo.setOnClickListener {
            drawer.openDrawer(GravityCompat.END)
        }

        // حول التطبيق
        binding.btnAbout.setOnClickListener { showAboutDialog() }

        // أزرار الدرج
        findViewById<View>(R.id.navCases).setOnClickListener {
            drawer.closeDrawer(GravityCompat.END)
            requireLogin { startActivity(Intent(this, CasesActivity::class.java)) }
        }
        findViewById<View>(R.id.navGeneral)?.setOnClickListener {
            drawer.closeDrawer(GravityCompat.END)
            startActivity(Intent(this, GeneralSearchActivity::class.java))
        }
        findViewById<View>(R.id.navQueries).setOnClickListener {
            drawer.closeDrawer(GravityCompat.END)
            requireLogin { startActivity(Intent(this, QueriesActivity::class.java)) }
        }
        findViewById<View>(R.id.navSettings).setOnClickListener {
            drawer.closeDrawer(GravityCompat.END)
            startActivity(Intent(this, AccountActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            updateUI()
        }
    }

    private fun updateUI() {
        val user = auth.currentUser

        if (user != null) {
            // مسجل دخول: أظهر كل الأزرار
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
            // غير مسجل: أخفِ الأزرار
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

            للتواصل:
            starsyia2500@gmail.com
            +963 938 466 549
            @maenstar
        """.trimIndent()
        AlertDialog.Builder(this).setTitle("حول التطبيق").setMessage(msg)
            .setPositiveButton("حسناً", null).show()
    }
}
