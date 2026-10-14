package com.maen.almustashar

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

// يستقبل منبه الجلسة أو الطعن ويعرض إشعاراً (النوع من EXTRA_KIND)
class SessionReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val caseId = intent.getStringExtra(SessionReminder.EXTRA_CASE_ID) ?: return
        val title = intent.getStringExtra(SessionReminder.EXTRA_TITLE) ?: "دعوى"
        val date = intent.getStringExtra(SessionReminder.EXTRA_DATE) ?: ""
        val isAppeal = intent.getStringExtra(AppealReminder.EXTRA_KIND) == AppealReminder.KIND_APPEAL
        if (isAppeal) AppealReminder.ensureChannel(context) else SessionReminder.ensureChannel(context)

        val openIntent = Intent(context, CasesActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            context, caseId.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val channel = if (isAppeal) AppealReminder.CHANNEL_ID else SessionReminder.CHANNEL_ID
        val notif = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (isAppeal) "⚖️ مهلة طعن: $title" else "⏰ تذكير جلسة: $title")
            .setContentText(
                if (isAppeal) "آخر مهلة: $date — راجع ملف الدعوى قبل فوات الميعاد"
                else "موعد الجلسة: $date — راجع ملف الدعوى والمستندات"
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .build()
        try {
            val nid = if (isAppeal) AppealReminder.requestCode(caseId) else caseId.hashCode()
            NotificationManagerCompat.from(context).notify(nid, notif)
        } catch (_: SecurityException) {
            // إذن الإشعارات مرفوض — يُطلب من شاشة الأرشيف
        }
    }
}
