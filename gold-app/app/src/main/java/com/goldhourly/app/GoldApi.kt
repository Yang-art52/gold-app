package com.goldhourly.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 取价：多个源依次尝试。lastError 记录失败的真实原因，界面会显示出来便于定位。
 */
object GoldApi {

    const val OZ_GRAMS = 31.1034768

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    var lastError: String = ""
        private set

    private fun get(url: String, referer: String? = null): String? {
        var conn: HttpURLConnection? = null
        lastError = ""
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json,text/plain,*/*")
                setRequestProperty("Cache-Control", "no-cache")
                if (referer != null) setRequestProperty("Referer", referer)
            }
            val code = conn.responseCode
            if (code != 200) {
                lastError = "HTTP $code"
                null
            } else {
                BufferedReader(conn.inputStream.bufferedReader()).use { it.readText() }
            }
        } catch (e: Exception) {
            val msg = e.message?.take(50) ?: ""
            lastError = e.javaClass.simpleName + if (msg.isBlank()) "" else "($msg)"
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 美元/盎司；失败返回 0.0，原因写入 lastError */
    fun priceUsd(): Double {
        val errs = ArrayList<String>()

        var body = get("https://api.gold-api.com/price/XAU")
        if (!body.isNullOrBlank()) {
            try {
                val v = JSONObject(body).optDouble("price", 0.0)
                if (v > 100.0) return v
                errs.add("goldapi 数值异常")
            } catch (_: Exception) {
                errs.add("goldapi 解析失败")
            }
        } else {
            errs.add("goldapi " + lastError)
        }

        body = get("https://data-asg.goldprice.org/dbXRates/USD")
        if (!body.isNullOrBlank()) {
            try {
                val v = JSONObject(body).optJSONArray("items")?.optJSONObject(0)?.optDouble("xauPrice", 0.0) ?: 0.0
                if (v > 100.0) return v
                errs.add("goldprice 数值异常")
            } catch (_: Exception) {
                errs.add("goldprice 解析失败")
            }
        } else {
            errs.add("goldprice " + lastError)
        }

        // 新浪财经伦敦金，格式：var hq_str_hf_XAU="现价,买价,卖价,..."
        body = get("https://hq.sinajs.cn/list=hf_XAU", "https://finance.sina.com.cn")
        if (!body.isNullOrBlank()) {
            try {
                val s = body.substringAfter("\"").substringBefore("\"")
                val v = s.split(",")[0].toDoubleOrNull() ?: 0.0
                if (v > 100.0) return v
                errs.add("sina 数值异常")
            } catch (_: Exception) {
                errs.add("sina 解析失败")
            }
        } else {
            errs.add("sina " + lastError)
        }

        lastError = errs.joinToString(" | ").take(140)
        return 0.0
    }

    /** 美元兑人民币；失败返回 0.0 */
    fun usdCny(): Double {
        var body = get("https://open.er-api.com/v6/latest/USD")
        if (!body.isNullOrBlank()) {
            try {
                val v = JSONObject(body).optJSONObject("rates")?.optDouble("CNY", 0.0) ?: 0.0
                if (v > 1.0) return v
            } catch (_: Exception) {
            }
        }
        body = get("https://api.frankfurter.app/latest?from=USD&to=CNY")
        if (!body.isNullOrBlank()) {
            try {
                val v = JSONObject(body).optJSONObject("rates")?.optDouble("CNY", 0.0) ?: 0.0
                if (v > 1.0) return v
            } catch (_: Exception) {
            }
        }
        return 0.0
    }

    /**
     * 今日逐小时收盘价（美元/盎司），用于补齐 App 未运行时的整点。
     * 源：东方财富 现货黄金（secid=122.XAU）60 分钟 / 5 分钟 K 线，境内直连。
     * 失败返回空表，原因写 lastError。
     */
    fun hourlyHistory(): Map<Int, Double> {
        val errs = ArrayList<String>()
        for (klt in intArrayOf(60, 5)) {
            val u = "https://push2his.eastmoney.com/api/qt/stock/kline/get" +
                "?secid=122.XAU&ut=fa5fd1943c7b386f172d6893dbfba10b&klt=" + klt +
                "&fqt=1&lmt=" + (if (klt == 60) 48 else 240) + "&end=20500101" +
                "&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56"
            val body = get(u, "https://quote.eastmoney.com")
            if (body.isNullOrBlank()) {
                errs.add("em" + klt + " " + lastError)
                continue
            }
            if (!body.contains("klines")) {
                errs.add("em" + klt + " 无klines")
                continue
            }
            val m = parseEm(body)
            if (m.isNotEmpty()) {
                lastError = ""
                return m
            }
            errs.add("em" + klt + " 无今日点")
        }
        lastError = if (errs.isEmpty()) "hist 不可用" else errs.joinToString(" | ").take(140)
        return emptyMap()
    }

    /** 东财：{"data":{"klines":["2026-11-04 13:00,开,收,高,低,量", ...]}} */
    private fun parseEm(body: String): Map<Int, Double> {
        val arr = try {
            JSONObject(body).optJSONObject("data")?.optJSONArray("klines")
        } catch (_: Exception) {
            null
        } ?: return emptyMap()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val bars = ArrayList<Pair<Long, Double>>()
        for (i in 0 until arr.length()) {
            val parts = arr.optString(i, "").split(",")
            if (parts.size < 3) continue
            val t = try {
                fmt.parse(parts[0])?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
            if (t <= 0L) continue
            val c = parts[2].toDoubleOrNull() ?: continue
            if (c > 100.0) bars.add(t to c)
        }
        return toHourMap(bars)
    }

    /** (时间戳, 价格) → 今日 hour→close；若时间戳整体滞后约 8 小时（按 UTC 标注）则回退 8 小时 */
    private fun toHourMap(bars: List<Pair<Long, Double>>): Map<Int, Double> {
        if (bars.size < 2) return emptyMap()
        val now = System.currentTimeMillis()
        val lag = now - bars.maxOf { it.first }
        val shift = if (lag in 4 * 3_600_000L..11 * 3_600_000L) -8 * 3_600_000L else 0L
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val hourFmt = SimpleDateFormat("HH", Locale.US)
        val today = dayFmt.format(Date())
        val map = LinkedHashMap<Int, Double>()
        for ((t0, v) in bars) {
            val dt = Date(t0 + shift)
            if (dt.time > now + 120_000L) continue
            if (dayFmt.format(dt) != today) continue
            val h = hourFmt.format(dt).toIntOrNull() ?: continue
            map[h] = v
        }
        return map
    }
}
