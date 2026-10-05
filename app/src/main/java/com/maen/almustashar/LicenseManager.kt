package com.maen.almustashar

import com.google.firebase.firestore.FirebaseFirestore
import java.security.MessageDigest
import kotlin.random.Random

object LicenseManager {
    private val db = FirebaseFirestore.getInstance()

    fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun checkRemoteLicense(deviceId: String, onResult: (Boolean) -> Unit) {
        db.collection("deviceLicenses").document(deviceId).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) { onResult(false); return@addOnSuccessListener }
                val active = doc.getBoolean("active") ?: false
                val expiresAt = doc.getLong("expiresAt") ?: -1L
                val valid = active && (expiresAt == -1L || expiresAt > System.currentTimeMillis())
                onResult(valid)
            }
            .addOnFailureListener { onResult(false) }
    }

    fun verifyOwnerPassword(email: String, password: String, onResult: (Boolean) -> Unit) {
        db.collection("config").document("ownerAuth").get()
            .addOnSuccessListener { doc ->
                val storedEmail = doc.getString("email") ?: ""
                val storedHash = doc.getString("passwordHash") ?: ""
                val inputHash = sha256(password)
                onResult(email.trim().equals(storedEmail.trim(), ignoreCase = true) && inputHash == storedHash)
            }
            .addOnFailureListener { onResult(false) }
    }


    fun generateCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val sb = StringBuilder()
        repeat(8) { sb.append(chars[Random.nextInt(chars.length)]) }
        return sb.toString()
    }

    fun issueLicense(deviceId: String, durationType: String, onResult: (Boolean, String) -> Unit) {
        val code = generateCode()
        val now = System.currentTimeMillis()
        val expiresAt = when (durationType) {
            "month" -> now + 30L*24*60*60*1000
            "year" -> now + 365L*24*60*60*1000
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
            .addOnFailureListener { onResult(false, "فشل الإنشاء: " + (it.message ?: "")) }
    }

}
