package com.maen.almustashar

import android.content.Context
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object LicenseManager {
    private const val PREFS_NAME = "almustashar_license_prefs"
    private const val KEY_ACTIVATION = "license_key"
    private const val KEY_EXPIRY = "license_expiry"

    private const val HMAC_KEY = "AlMustashar@SecureSign#2026!Key"

    private const val OWNER_EMAIL = "starsyria2500@gmail.com"
    private const val OWNER_PASS = "maen@maen@1741965"

    fun verifyOwner(email: String, pass: String): Boolean {
        val cleanEmail = email.trim().replace("\\s".toRegex(), "").lowercase()
        val cleanPass = pass.trim().replace("\\s".toRegex(), "")
        return cleanEmail == OWNER_EMAIL && cleanPass == OWNER_PASS
    }

    fun generateCode(deviceId: String, expiryDays: Int): String {
        val expiryTime = if (expiryDays == -1) 9999999999L else (System.currentTimeMillis() / 1000L) + (expiryDays * 86400L)
        val payload = "${deviceId.trim().uppercase()}:$expiryTime"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(HMAC_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val sign = mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02X".format(it) }.take(8)
        return "MST-$sign-$expiryTime"
    }

    fun grantOwnerDeviceLicense(context: Context, deviceId: String) {
        val permanentCode = generateCode(deviceId, -1)
        verifyAndSaveCode(context, deviceId, permanentCode)
    }

    fun verifyAndSaveCode(context: Context, deviceId: String, code: String): Boolean {
        val cleanCode = code.trim().replace("\\s".toRegex(), "")
        val parts = cleanCode.split("-")
        if (parts.size != 3 || parts[0] != "MST") return false
        val sign = parts[1]
        val expiryTime = parts[2].toLongOrNull() ?: return false

        val payload = "${deviceId.trim().uppercase()}:$expiryTime"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(HMAC_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val expectedSign = mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02X".format(it) }.take(8)

        if (sign != expectedSign) return false

        val now = System.currentTimeMillis() / 1000L
        if (expiryTime != 9999999999L && now > expiryTime) return false

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ACTIVATION, cleanCode).putLong(KEY_EXPIRY, expiryTime).apply()
        return true
    }

    fun isLicensed(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val code = prefs.getString(KEY_ACTIVATION, null) ?: return false
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
