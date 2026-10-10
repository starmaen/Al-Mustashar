package com.maen.almustashar

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// تذكير بموعد الجلسة قبلها بيوم (الساعة 9 صباحاً) — بدون صلاحيات خاصة
object SessionReminder {
    const val CHANNEL_ID = "case_sessions"
    const val EXTRA_CASE_ID = "case_id"
    const val EXTRA_TITLE = "case_title"
    const val EXTRA_DATE = "case_date"

    // تعيد true إذا ضُبط منبه فعلياً (لعرض تأكيد للمستخدم)
    fun schedule(context: Context, caseId: String, title: String, sessionDate: String): Boolean {
        cancel(context, caseId)
        if (caseId.isBlank() || sessionDate.isBlank()) return false
        val day = try {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(sessionDate) ?: return false
        } catch (_: Exception) {
            return false
        }
        val now = System.currentTimeMillis()
        if (day.time <= now) return false // موعد ماضٍ — لا تذكير

        val cal = Calendar.getInstance().apply {
            time = day
            add(Calendar.DAY_OF_YEAR, -1)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
        var trigger = cal.timeInMillis
        if (trigger <= now) trigger = now + 10_000L // الجلسة قريبة — ذكّر بعد لحظات

        val intent = Intent(context, SessionReminderReceiver::class.java).apply {
            putExtra(EXTRA_CASE_ID, caseId)
            putExtra(EXTRA_TITLE, title.ifBlank { "دعوى بدون عنوان" })
            putExtra(EXTRA_DATE, sessionDate)
        }
        val pi = PendingIntent.getBroadcast(
            context, caseId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun cancel(context: Context, caseId: String) {
        if (caseId.isBlank()) return
        val intent = Intent(context, SessionReminderReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context, caseId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pi)
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "تذكير الجلسات", NotificationManager.IMPORTANCE_HIGH)
                )
            }
        }
    }
}
