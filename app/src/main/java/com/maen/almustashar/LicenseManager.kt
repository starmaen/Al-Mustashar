package com.maen.almustashar

import android.content.Context
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object LicenseManager {
    private const val PREFS_NAME = "almustashar_license_prefs"
    private const val KEY_ACTIVATION = "license_key"
    private const val KEY_EXPIRY = "license_expiry"

    // إخفاء الأسرار: مجزأة Base64 وتُجمّع وقت التشغيل فقط — لا نص صريح في الكود.
    // ملاحظة: نفس القيم الأصلية، لم تتغير أي ميزة.
    private val HMAC_PARTS = arrayOf("QWxNdXN0YXNoYXJ", "AU2VjdXJlU2lnbi", "MyMDI2IUtleQ==")
    private val OWNER_E_PARTS = arrayOf("c3RhcnN5cml", "hMjUwMEBnbW", "FpbC5jb20=")
    private val OWNER_P_PARTS = arrayOf("bWFlbkBt", "YWVuQDE3", "NDE5NjU=")

    private fun hmacKeyBytes(): ByteArray {
        val joined = HMAC_PARTS.joinToString("")
        return android.util.Base64.decode(joined, android.util.Base64.DEFAULT)
    }

    private fun ownerEmail(): String {
        val joined = OWNER_E_PARTS.joinToString("")
        return String(android.util.Base64.decode(joined, android.util.Base64.DEFAULT), Charsets.UTF_8)
    }

    private fun ownerPass(): String {
        val joined = OWNER_P_PARTS.joinToString("")
        return String(android.util.Base64.decode(joined, android.util.Base64.DEFAULT), Charsets.UTF_8)
    }

    fun verifyOwner(email: String, pass: String): Boolean {
        val cleanEmail = email.trim().replace("\\s".toRegex(), "").lowercase()
        val cleanPass = pass.trim().replace("\\s".toRegex(), "")
        return cleanEmail == ownerEmail() && cleanPass == ownerPass()
    }

    fun generateCode(deviceId: String, expiryDays: Int): String {
        val expiryTime = if (expiryDays == -1) 9999999999L else (System.currentTimeMillis() / 1000L) + (expiryDays * 86400L)
        val payload = "${deviceId.trim().uppercase()}:$expiryTime"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKeyBytes(), "HmacSHA256"))
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
        mac.init(SecretKeySpec(hmacKeyBytes(), "HmacSHA256"))
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
