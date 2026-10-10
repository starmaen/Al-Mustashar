package com.maen.almustashar

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

// يستقبل منبه الجلسة ويعرض إشعاراً بموعدها
class SessionReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val caseId = intent.getStringExtra(SessionReminder.EXTRA_CASE_ID) ?: return
        val title = intent.getStringExtra(SessionReminder.EXTRA_TITLE) ?: "دعوى"
        val date = intent.getStringExtra(SessionReminder.EXTRA_DATE) ?: ""
        SessionReminder.ensureChannel(context)

        val openIntent = Intent(context, CasesActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, caseId.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, SessionReminder.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("⏰ تذكير جلسة: $title")
            .setContentText("موعد الجلسة: $date — راجع ملف الدعوى والمستندات")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(caseId.hashCode(), notif)
        } catch (_: SecurityException) {
            // إذن الإشعارات مرفوض — يُطلب من شاشة الأرشيف
        }
    }
}
