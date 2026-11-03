package com.goldhourly.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import java.util.Calendar

/** 每小时整点触发一次，Doze 下仍可唤醒（不申请精确闹钟权限） */
object HourlyAlarm {

    private const val REQ = 7001

    private fun pending(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, REQ,
        Intent(c, PriceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(c: Context) {
        val cal = Calendar.getInstance()
        cal.add(Calendar.HOUR_OF_DAY, 1)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 10)
        cal.set(Calendar.MILLISECOND, 0)
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pending(c))
            } else {
                am.set(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pending(c))
            }
        } catch (_: Exception) {
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

/** 开机 / 应用更新后重新排闹钟 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HourlyAlarm.schedule(context)
    }
}
