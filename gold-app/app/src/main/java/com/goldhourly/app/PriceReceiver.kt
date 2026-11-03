package com.goldhourly.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/** 前台刷新与后台闹钟共用的同步逻辑：拉价 → 存小时点 → 判断底价 → 通知 */
object Sync {

    /** 返回 true 表示本次取到了有效价格 */
    fun run(c: Context): Boolean {
        val usd = GoldApi.priceUsd()
        if (usd <= 0.0) return false

        var rate = PriceStore.fx(c)
        if (rate <= 0.0) {
            val r = GoldApi.usdCny()
            if (r > 0.0) {
                PriceStore.setFx(c, r)
                rate = r
            }
        }

        PriceStore.put(c, Calendar.getInstance().get(Calendar.HOUR_OF_DAY), usd)

        val threshold = PriceStore.threshold(c)
        if (threshold > 0.0 && rate > 0.0) {
            val cny = usd * rate / GoldApi.OZ_GRAMS
            if (cny <= threshold) {
                if (PriceStore.armed(c)) {
                    Notifier.alert(c, cny, threshold, usd)
                    PriceStore.setArmed(c, false)
                }
            } else if (cny > threshold * 1.002) {
                PriceStore.setArmed(c, true)
            }
        }
        return true
    }
}

class PriceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                Sync.run(app)
            } catch (_: Exception) {
            }
            HourlyAlarm.schedule(app)
            pending.finish()
        }.start()
    }
}
