package com.maen.almustashar

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore

class OwnerPanelActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val credential = GoogleAuthProvider.getCredential(account.idToken, null)
            auth.signInWithCredential(credential)
                .addOnSuccessListener { checkAdminAndShowPanel() }
                .addOnFailureListener {
                    Toast.makeText(this, "فشل تسجيل الدخول: " + it.message, Toast.LENGTH_LONG).show()
                }
        } catch (e: ApiException) {
            Toast.makeText(this, "فشل تسجيل الدخول بجوجل", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.post {
            val root = findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as? android.view.ViewGroup
            val exitBtn = android.widget.Button(this)
            exitBtn.id = android.view.View.generateViewId()
            exitBtn.tag = "btnOwnerExitAuto"
            exitBtn.text = "خروج"
            exitBtn.setOnClickListener { finish() }
            root?.addView(exitBtn)
        }
        setContentView(R.layout.activity_owner_panel)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        val spinner = findViewById<Spinner>(R.id.spinnerDuration)
        val options = listOf("شهر", "سنة", "دائم")
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)

        findViewById<Button>(R.id.btnGoogleSignIn).setOnClickListener {
            startGoogleSignIn()
        }

        findViewById<Button>(R.id.btnGenerateCode).setOnClickListener {
            val etDeviceId = findViewById<EditText>(R.id.etTargetDeviceId)
            val tvResultCode = findViewById<TextView>(R.id.tvResultCode)
            val deviceId = etDeviceId.text.toString().trim()
            if (deviceId.isEmpty()) {
                Toast.makeText(this, "أدخل معرّف الجهاز المستهدف", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val durationType = when (spinner.selectedItem.toString()) {
                "شهر" -> "month"
                "سنة" -> "year"
                else -> "permanent"
            }
            LicenseManager.issueLicense(deviceId, durationType) { success, result ->
                if (success) {
                    tvResultCode.text = "كود التفعيل: " + result
                    Toast.makeText(this, "تم إنشاء الكود بنجاح", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                }
            }
        }

        checkAdminAndShowPanel()
    }

    private fun startGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        val client = GoogleSignIn.getClient(this, gso)
        client.signOut().addOnCompleteListener {
            signInLauncher.launch(client.signInIntent)
        }
    }

    private fun checkAdminAndShowPanel() {
        val llSignIn = findViewById<LinearLayout>(R.id.llSignInSection)
        val llPanel = findViewById<LinearLayout>(R.id.llPanelContent)
        val tvSignInMessage = findViewById<TextView>(R.id.tvSignInMessage)

        val user = auth.currentUser
        if (user == null) {
            llSignIn.visibility = View.VISIBLE
            llPanel.visibility = View.GONE
            return
        }

        db.collection("admins").document(user.uid).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    llSignIn.visibility = View.GONE
                    llPanel.visibility = View.VISIBLE
                } else {
                    llSignIn.visibility = View.VISIBLE
                    llPanel.visibility = View.GONE
                    tvSignInMessage.text = "هذا الحساب (" + user.email + ") غير مخوَّل. سجّل بحساب المالك."
                }
            }
            .addOnFailureListener {
                llSignIn.visibility = View.VISIBLE
                llPanel.visibility = View.GONE
                tvSignInMessage.text = "تعذر التحقق من الصلاحية، أعد المحاولة"
            }
    }
}
