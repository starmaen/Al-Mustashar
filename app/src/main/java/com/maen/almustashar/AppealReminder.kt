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

// تنبيه مواعيد الطعن: قبل المهلة بـ3 أيام (9 صباحاً) + يوم المهلة — بدون صلاحيات خاصة.
// يستخدم مستقبل الجلسات نفسه (SessionReminderReceiver) بنوع مميز ومعرف مستقل
// حتى لا يتعارض مع منبه الجلسة لذات الدعوى.
object AppealReminder {
    const val CHANNEL_ID = "case_appeals"
    const val EXTRA_KIND = "kind"
    const val KIND_APPEAL = "appeal"

    fun requestCode(caseId: String) = ("appeal:$caseId").hashCode()

    // يعيد true إذا ضُبط منبه فعلياً
    fun schedule(context: Context, caseId: String, title: String, deadline: String): Boolean {
        cancel(context, caseId)
        if (caseId.isBlank() || deadline.isBlank()) return false
        val day = try {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(deadline) ?: return false
        } catch (_: Exception) {
            return false
        }
        val now = System.currentTimeMillis()
        if (day.time <= now) return false // مهلة ماضية — لا تذكير

        ensureChannel(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        var any = false

        // التنبيه المبكر: قبل 3 أيام
        val early = Calendar.getInstance().apply {
            time = day
            add(Calendar.DAY_OF_YEAR, -3)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }.timeInMillis
        if (early > now) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, early, buildPi(context, caseId, title, deadline, 1))
            any = true
        }
        // تنبيه يوم المهلة نفسه
        val sameDay = Calendar.getInstance().apply {
            time = day
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }.timeInMillis
        val trigger = if (sameDay > now) sameDay else now + 10_000L
        return try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, buildPi(context, caseId, title, deadline, 2))
            true
        } catch (_: Exception) {
            any
        }
    }

    private fun buildPi(context: Context, caseId: String, title: String, deadline: String, slot: Int): PendingIntent {
        val intent = Intent(context, SessionReminderReceiver::class.java).apply {
            putExtra(EXTRA_KIND, KIND_APPEAL)
            putExtra(SessionReminder.EXTRA_CASE_ID, caseId)
            putExtra(SessionReminder.EXTRA_TITLE, title.ifBlank { "دعوى بدون عنوان" })
            putExtra(SessionReminder.EXTRA_DATE, deadline)
            putExtra("slot", slot)
        }
        return PendingIntent.getBroadcast(
            context, requestCode(caseId) + slot, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun cancel(context: Context, caseId: String) {
        if (caseId.isBlank()) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (slot in 1..2) {
            val intent = Intent(context, SessionReminderReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, requestCode(caseId) + slot, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
        }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "تذكير مواعيد الطعن", NotificationManager.IMPORTANCE_HIGH)
                )
            }
        }
    }
}
