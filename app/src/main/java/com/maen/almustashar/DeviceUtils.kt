package com.maen.almustashar

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

object DeviceUtils {
    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN_DEV"
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(androidId.toByteArray())
        return digest.joinToString("") { "%02X".format(it) }.take(12)
    }
}
