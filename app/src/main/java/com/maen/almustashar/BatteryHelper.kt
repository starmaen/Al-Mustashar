package com.maen.almustashar

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

// يطلب داخلياً (برمجياً) استثناء التطبيق من تحسين البطارية،
// حتى لا يقتله النظام عند الخروج منه. يُستدعى مرة واحدة فقط.
object BatteryHelper {

    private const val PREFS = "battery_helper"
    private const val KEY_ASKED = "asked_unrestricted"

    fun isUnrestricted(ctx: Context): Boolean {
        return try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pm.isIgnoringBatteryOptimizations(ctx.packageName)
            } else true
        } catch (_: Exception) {
            true
        }
    }

    // يعيد true إذا فتحنا شاشة الطلب للمستخدم
    @SuppressLint("BatteryLife")
    fun requestUnrestrictedOnce(ctx: Context): Boolean {
        return try {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_ASKED, false)) return false
            if (isUnrestricted(ctx)) {
                prefs.edit().putBoolean(KEY_ASKED, true).apply()
                return false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${ctx.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(intent)
                } catch (_: Exception) {
                    // fallback: شاشة الإعدادات العامة
                    try {
                        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        ctx.startActivity(fallback)
                    } catch (_: Exception) {
                    }
                }
            }
            prefs.edit().putBoolean(KEY_ASKED, true).apply()
            true
        } catch (_: Exception) {
            false
        }
    }
}
