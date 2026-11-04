package com.goldhourly.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/** 前台刷新、常驻服务与后台闹钟共用的同步逻辑：补漏点 → 拉价 → 存小时点 → 判断底价 → 通知 */
object Sync {

    /** 返回 true 表示本次取到了有效价格；backfill=true 时顺带补齐今天漏掉的整点 */
    fun run(c: Context, backfill: Boolean = false): Boolean {
        if (backfill) {
            try {
                fillMissing(c)
            } catch (_: Exception) {
            }
        }

        val usd = GoldApi.priceUsd()
        if (usd <= 0.0) {
            PriceStore.markCheck(c, false, GoldApi.lastError)
            return false
        }

        val rate = ensureRate(c)
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
        PriceStore.markCheck(c, true, "")
        return true
    }

    private fun ensureRate(c: Context): Double {
        var rate = PriceStore.fx(c)
        if (rate <= 0.0) {
            val r = GoldApi.usdCny()
            if (r > 0.0) {
                PriceStore.setFx(c, r)
                rate = r
            }
        }
        return rate
    }

    /** 用历史K线补齐今天"App 没在运行"的那些整点，返回补上的点数 */
    fun fillMissing(c: Context): Int {
        val hist = GoldApi.hourlyHistory()
        if (hist.isEmpty()) {
            PriceStore.setFilled(c, 0)
            PriceStore.setFillMsg(c, GoldApi.lastError.ifBlank { "hist 不可用" })
            return 0
        }
        val nowHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        var n = 0
        for ((h, v) in hist) {
            if (h >= nowHour) continue
            val ts = System.currentTimeMillis() - (nowHour - h) * 3_600_000L
            if (PriceStore.addIfMissing(c, h, v, ts)) n++
        }
        PriceStore.setFilled(c, n)
        if (n > 0) PriceStore.setFillMsg(c, "")
        return n
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
