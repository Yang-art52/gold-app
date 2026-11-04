package com.goldhourly.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.util.Calendar
import java.util.Locale

/** 整点触发：优先精确闹钟（不受 Doze/省电策略延迟），拿不到权限时退回普通闹钟 */
object HourlyAlarm {

    private const val REQ = 7001

    private fun pending(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, REQ,
        Intent(c, PriceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Android 12+ 精确闹钟需用户单独授权 */
    fun canExact(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = c.getSystemService(AlarmManager::class.java) ?: return false
        return try {
            am.canScheduleExactAlarms()
        } catch (_: Exception) {
            false
        }
    }

    fun schedule(c: Context) {
        val cal = Calendar.getInstance()
        cal.add(Calendar.HOUR_OF_DAY, 1)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 10)
        cal.set(Calendar.MILLISECOND, 0)
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val at = cal.timeInMillis
        val pi = pending(c)
        try {
            if (canExact(c)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: Exception) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (_: Exception) {
            }
        }
    }

    fun cancel(c: Context) {
        c.getSystemService(AlarmManager::class.java)?.cancel(pending(c))
    }

    fun ignoringBattery(c: Context): Boolean {
        val pm = c.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(c.packageName)
    }
}

/**
 * 常驻前台服务：目的有二
 * 1) 让进程不被系统冻结，闹钟广播能正常投递；
 * 2) 屏幕亮着时每分钟兜底检查一次，整点闹钟万一被吞也能补上。
 */
class MonitorService : Service() {

    companion object {
        private const val CH = "gold_service"
        private const val NID = 2001

        fun start(c: Context) {
            try {
                val i = Intent(c, MonitorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    c.startForegroundService(i)
                } else {
                    c.startService(i)
                }
            } catch (_: Exception) {
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var busy = false
    private var lastText = ""
    private var lastHour = -1

    private val loop = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (_: Exception) {
            }
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(NID, build())
        } catch (_: Exception) {
        }
        handler.postDelayed(loop, 2_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(loop)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        HourlyAlarm.schedule(this)
        super.onTaskRemoved(rootIntent)
    }

    private fun tick() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (PriceStore.hours(this).any { it.first == hour }) {
            if (hour != lastHour) {
                lastHour = hour
                push()
            }
            return
        }
        if (busy) return
        busy = true
        Thread {
            try {
                Sync.run(this, true)
            } catch (_: Exception) {
            }
            handler.post {
                busy = false
                lastHour = hour
                push()
            }
        }.start()
    }

    /** 常驻通知文字变化时才刷新，避免无谓唤醒 */
    private fun push() {
        val text = summary()
        if (text == lastText) return
        lastText = text
        try {
            getSystemService(NotificationManager::class.java)?.notify(NID, build())
        } catch (_: Exception) {
        }
    }

    private fun summary(): String {
        val rate = PriceStore.fx(this)
        val points = PriceStore.hours(this)
        val th = PriceStore.threshold(this)
        val sb = StringBuilder()
        if (points.isEmpty()) {
            sb.append("等待数据")
        } else {
            val usd = points.last().second
            if (rate > 0.0) {
                sb.append(String.format(Locale.US, "%.1f 元/克", usd * rate / GoldApi.OZ_GRAMS))
            } else {
                sb.append(String.format(Locale.US, "%.1f 美元/盎司", usd))
            }
            sb.append(" · 今日 ").append(points.size).append(" 点")
        }
        sb.append(if (th > 0.0) String.format(Locale.US, " · 底价 %.1f", th) else " · 未设底价")
        return sb.toString()
    }

    private fun build(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm != null && nm.getNotificationChannel(CH) == null) {
            val ch = NotificationChannel(CH, "常驻监控", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            ch.description = "保持整点自动检查金价"
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("金价小时监控")
            .setContentText(summary())
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(Notification.PRIORITY_MIN)
            .build()
    }
}

/** 开机 / 应用更新后重新排闹钟并拉起常驻服务 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HourlyAlarm.schedule(context)
        MonitorService.start(context)
    }
}
