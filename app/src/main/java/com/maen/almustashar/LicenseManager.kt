package com.maen.almustashar

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object LicenseManager {

    private const val PREFS = "license_prefs"
    private const val KEY_LICENSED = "is_licensed"
    private const val KEY_EXPIRES = "expires_at"
    private const val KEY_LAST_CHECK = "last_check"
    private const val RECHECK_INTERVAL = 24 * 60 * 60 * 1000L

    private val db by lazy { FirebaseFirestore.getInstance() }

    fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isLicensedCached(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val licensed = prefs.getBoolean(KEY_LICENSED, false)
        if (!licensed) return false
        val expires = prefs.getLong(KEY_EXPIRES, -1L)
        if (expires == -1L) return true
        return System.currentTimeMillis() < expires
    }

    fun needsRemoteRecheck(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last > RECHECK_INTERVAL
    }

    fun saveLocalLicense(context: Context, active: Boolean, expiresAt: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_LICENSED, active)
            .putLong(KEY_EXPIRES, expiresAt)
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .apply()
    }

    fun checkRemoteLicense(context: Context, deviceId: String, onResult: (Boolean) -> Unit) {
        db.collection("deviceLicenses").document(deviceId).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    saveLocalLicense(context, false, 0L)
                    onResult(false)
                    return@addOnSuccessListener
                }
                val active = doc.getBoolean("active") ?: false
                val expiresAt = doc.getLong("expiresAt") ?: 0L
                val valid = active && (expiresAt == -1L || System.currentTimeMillis() < expiresAt)
                saveLocalLicense(context, valid, expiresAt)
                onResult(valid)
            }
            .addOnFailureListener {
                onResult(isLicensedCached(context))
            }
    }

    fun activateWithCode(context: Context, deviceId: String, code: String, onResult: (Boolean, String) -> Unit) {
        db.collection("deviceLicenses").document(deviceId).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    onResult(false, "لا يوجد ترخيص لهذا الجهاز")
                    return@addOnSuccessListener
                }
                val savedCode = doc.getString("code") ?: ""
                val active = doc.getBoolean("active") ?: false
                val expiresAt = doc.getLong("expiresAt") ?: 0L
                if (!active) {
                    onResult(false, "تم إلغاء تفعيل هذا الترخيص")
                    return@addOnSuccessListener
                }
                if (savedCode.equals(code.trim(), ignoreCase = true)) {
                    val valid = expiresAt == -1L || System.currentTimeMillis() < expiresAt
                    if (valid) {
                        saveLocalLicense(context, true, expiresAt)
                        onResult(true, "تم التفعيل بنجاح")
                    } else {
                        onResult(false, "انتهت صلاحية هذا الكود")
                    }
                } else {
                    onResult(false, "كود التفعيل غير صحيح")
                }
            }
            .addOnFailureListener {
                onResult(false, "تعذر الاتصال بالخادم، تحقق من الإنترنت")
            }
    }

    fun verifyOwnerPassword(email: String, password: String, onResult: (Boolean) -> Unit) {
        db.collection("config").document("ownerAuth").get()
            .addOnSuccessListener { doc ->
                val savedEmail = doc.getString("email") ?: ""
                val savedHash = doc.getString("passwordHash") ?: ""
                val inputHash = sha256(password)
                onResult(savedEmail.equals(email.trim(), ignoreCase = true) && savedHash == inputHash)
            }
            .addOnFailureListener { onResult(false) }
    }

    fun generateCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..8).map { chars.random() }.joinToString("")
    }

    fun issueLicense(deviceId: String, durationType: String, onResult: (Boolean, String) -> Unit) {
        val code = generateCode()
        val now = System.currentTimeMillis()
        val expiresAt = when (durationType) {
            "month" -> now + TimeUnit.DAYS.toMillis(30)
            "year" -> now + TimeUnit.DAYS.toMillis(365)
            else -> -1L
        }
        val data = hashMapOf(
            "deviceId" to deviceId,
            "code" to code,
            "activatedAt" to now,
            "expiresAt" to expiresAt,
            "durationType" to durationType,
            "active" to true
        )
        db.collection("deviceLicenses").document(deviceId).set(data)
            .addOnSuccessListener { onResult(true, code) }
            .addOnFailureListener { onResult(false, "فشل إنشاء الترخيص: " + it.message) }
    }
}
