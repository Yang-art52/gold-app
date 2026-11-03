package com.goldhourly.app

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 本地存储：当天逐小时价格、汇率缓存、底价、告警武装位 */
object PriceStore {

    private const val PREFS = "gold"
    private const val K_POINTS = "points"
    private const val K_FX = "fx"
    private const val K_FX_TS = "fx_ts"
    private const val K_TH = "threshold"
    private const val K_ARMED = "armed"

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** 结构：{"d":"2026-11-03","v":{"8":906.4},"t":{"8":1699...}}，跨天自动重置 */
    private fun raw(c: Context): JSONObject {
        val s = sp(c).getString(K_POINTS, null)
        if (s != null) {
            try {
                val o = JSONObject(s)
                if (o.optString("d") == today()) return o
            } catch (_: Exception) {
            }
        }
        return JSONObject().put("d", today()).put("v", JSONObject()).put("t", JSONObject())
    }

    fun put(c: Context, hour: Int, price: Double) {
        val o = raw(c)
        o.getJSONObject("v").put(hour.toString(), price)
        o.getJSONObject("t").put(hour.toString(), System.currentTimeMillis())
        sp(c).edit().putString(K_POINTS, o.toString()).apply()
    }

    /** 升序 (小时, 价格) */
    fun hours(c: Context): List<Pair<Int, Double>> {
        val v = raw(c).optJSONObject("v") ?: return emptyList()
        val list = ArrayList<Pair<Int, Double>>()
        for (k in v.keys()) {
            val h = k.toIntOrNull() ?: continue
            list.add(h to v.optDouble(k, 0.0))
        }
        list.sortBy { it.first }
        return list
    }

    fun lastTs(c: Context): Long {
        val t = raw(c).optJSONObject("t") ?: return 0L
        var max = 0L
        for (k in t.keys()) max = maxOf(max, t.optLong(k, 0L))
        return max
    }

    fun threshold(c: Context): Double = sp(c).getFloat(K_TH, 0f).toDouble()

    fun setThreshold(c: Context, v: Double) {
        sp(c).edit().putFloat(K_TH, v.toFloat()).apply()
    }

    fun armed(c: Context): Boolean = sp(c).getBoolean(K_ARMED, true)

    fun setArmed(c: Context, v: Boolean) {
        sp(c).edit().putBoolean(K_ARMED, v).apply()
    }

    /** 12 小时内的美元兑人民币汇率，没有则 0 */
    fun fx(c: Context): Double {
        val ts = sp(c).getLong(K_FX_TS, 0L)
        if (System.currentTimeMillis() - ts > 12 * 3600_000L) return 0.0
        return sp(c).getFloat(K_FX, 0f).toDouble()
    }

    fun setFx(c: Context, v: Double) {
        sp(c).edit().putFloat(K_FX, v.toFloat())
            .putLong(K_FX_TS, System.currentTimeMillis()).apply()
    }
}
