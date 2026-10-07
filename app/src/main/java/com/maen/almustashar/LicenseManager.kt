package com.maen.almustashar

import android.content.Context
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object LicenseManager {
    private const val PREFS_NAME = "almustashar_license_prefs"
    private const val KEY_ACTIVATION = "license_key"
    private const val KEY_EXPIRY = "license_expiry"

    // مفتاح رياضي داخلي للتوقيع المشفر
    private const val HMAC_KEY = "AlMustashar@SecureSign#2026!Key"

    // تجزئات SHA-256 لبيانات المالك المشفرة
    // starsyria2500@gmail.com
    private const val OWNER_EMAIL_HASH = "815b3c3c13867ea69f912c754d7e828469d7b43a9dc7bc6dcbc148332155c88b"
    // maen@maen@1741965
    private const val OWNER_PASS_HASH = "6f52aa7bc034a7d4ecb91ee7bb25df00122e23908fbbdb5b550dd4171ffc6cf9"

    fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.trim().toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun verifyOwner(email: String, pass: String): Boolean {
        return sha256(email.lowercase()) == OWNER_EMAIL_HASH && sha256(pass) == OWNER_PASS_HASH
    }

    fun generateCode(deviceId: String, expiryDays: Int): String {
        val expiryTime = if (expiryDays == -1) 9999999999L else (System.currentTimeMillis() / 1000L) + (expiryDays * 86400L)
        val payload = "${deviceId.uppercase()}:$expiryTime"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(HMAC_KEY.toByteArray(), "HmacSHA256"))
        val sign = mac.doFinal(payload.toByteArray()).joinToString("") { "%02X".format(it) }.take(8)
        return "MST-$sign-$expiryTime"
    }

    fun verifyAndSaveCode(context: Context, deviceId: String, code: String): Boolean {
        val parts = code.trim().split("-")
        if (parts.size != 3 || parts[0] != "MST") return false
        val sign = parts[1]
        val expiryTime = parts[2].toLongOrNull() ?: return false

        val payload = "${deviceId.uppercase()}:$expiryTime"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(HMAC_KEY.toByteArray(), "HmacSHA256"))
        val expectedSign = mac.doFinal(payload.toByteArray()).joinToString("") { "%02X".format(it) }.take(8)

        if (sign != expectedSign) return false

        val now = System.currentTimeMillis() / 1000L
        if (expiryTime != 9999999999L && now > expiryTime) return false

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ACTIVATION, code).putLong(KEY_EXPIRY, expiryTime).apply()
        return true
    }

    fun isLicensed(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val code = prefs.getString(KEY_ACTIVATION, null) ?: return false
        val devId = DeviceUtils.getDeviceId(context)
        val parts = code.split("-")
        if (parts.size != 3) return false
        val expiryTime = parts[2].toLongOrNull() ?: return false
        val now = System.currentTimeMillis() / 1000L
        if (expiryTime != 9999999999L && now > expiryTime) return false
        return true
    }

    fun clearLicense(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
