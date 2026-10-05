package com.maen.almustashar

import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

object OwnerAccessHelper {

    private var tapCount = 0
    private var firstTapTime = 0L
    private const val TAP_WINDOW = 3000L
    private const val REQUIRED_TAPS = 7

    fun registerTap(context: android.app.Activity, view: View) {
        view.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - firstTapTime > TAP_WINDOW) {
                tapCount = 0
                firstTapTime = now
            }
            tapCount++
            if (tapCount >= REQUIRED_TAPS) {
                tapCount = 0
                showOwnerDialog(context)
            }
        }
    }

    private fun showOwnerDialog(context: android.app.Activity) {
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        val padding = (20 * context.resources.displayMetrics.density).toInt()
        layout.setPadding(padding, padding, padding, padding)

        val emailInput = EditText(context)
        emailInput.hint = "البريد الإلكتروني"
        emailInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        layout.addView(emailInput)

        val passInput = EditText(context)
        passInput.hint = "الرقم السري"
        passInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layout.addView(passInput)

        AlertDialog.Builder(context)
            .setTitle("دخول المالك")
            .setView(layout)
            .setPositiveButton("دخول") { _, _ ->
                val email = emailInput.text.toString().trim()
                val pass = passInput.text.toString()
                LicenseManager.verifyOwnerPassword(email, pass) { ok ->
                    if (ok) {
                        context.startActivity(Intent(context, OwnerPanelActivity::class.java))
                    } else {
                        Toast.makeText(context, "بيانات غير صحيحة", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }


    fun showOwnerLoginForBypass(context: android.content.Context) {
        val emailInput = android.widget.EditText(context)
        emailInput.hint = "البريد الإلكتروني"
        val passInput = android.widget.EditText(context)
        passInput.hint = "الرقم السري"
        passInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        val layout = android.widget.LinearLayout(context)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.setPadding(40,20,40,20)
        layout.addView(emailInput)
        layout.addView(passInput)
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("دخول المالك")
            .setView(layout)
            .setPositiveButton("دخول") { _, _ ->
                val email = emailInput.text.toString().trim()
                val pass = passInput.text.toString().trim()
                LicenseManager.verifyOwnerPassword(email, pass) { ok ->
                    if (ok) {
                        context.getSharedPreferences("owner_prefs", android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("ownerVerified", true).apply()
                        val i = android.content.Intent(context, MainActivity::class.java)
                        i.putExtra("ownerBypass", true)
                        i.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                        context.startActivity(i)
                        if (context is android.app.Activity) context.finish()
                    } else {
                        android.widget.Toast.makeText(context, "بيانات غير صحيحة", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}
