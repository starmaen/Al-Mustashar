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

    // إعادة تدفق الفقرة للعرض: سطر طويل/قصير من الاستخراج يصبح تدفقاً
    // طبيعياً يلفه TextView، مع إبقاء الفاصل الفارغ بين الفقرات.
    // (لا يمس البيانات — عرض فقط، ويكمل لحم الانشطار في البيانات)
    fun reflowForDisplay(text: String): String {
        if (text.isEmpty()) return text
        var t = text.replace("\r", "")
        t = t.replace(Regex("([^\\s])[-–—]\\n+([^\\s])"), "$1$2")
        t = t.replace(Regex("(?<!\n)\n(?!\n)"), " ")
        t = t.replace(Regex("[ \t]+"), " ")
        return t.trim()
    }
}
