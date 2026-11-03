package com.goldhourly.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Locale

object Notifier {

    private const val CHANNEL = "gold_alert"

    fun ensureChannel(c: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "金价提醒", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "金价触及你设定的底价时提醒"
        nm.createNotificationChannel(ch)
    }

    fun alert(c: Context, cny: Double, threshold: Double, usd: Double) {
        ensureChannel(c)
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        val intent = Intent(c, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            c, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = "金价已达底价 " + fmt(cny) + " 元/克"
        val text = "底价 " + fmt(threshold) + " 元/克 · 国际金价 " + fmt(usd) + " 美元/盎司"
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText("当前 " + fmt(cny) + " 元/克，不高于你设定的底价 " + fmt(threshold) + " 元/克（国际金价 " + fmt(usd) + " 美元/盎司）"))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .build()
        try {
            nm.notify(1001, n)
        } catch (_: Exception) {
        }
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)
}
