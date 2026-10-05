package com.maen.almustashar

import com.google.firebase.firestore.FirebaseFirestore
import java.security.MessageDigest

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
}
