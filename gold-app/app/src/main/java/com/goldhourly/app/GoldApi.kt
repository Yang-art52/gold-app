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
     * 源：新浪外盘期货分时K线（中国境内可访问）。失败返回空表，原因写 lastError。
     */
    fun hourlyHistory(): Map<Int, Double> {
        val errs = ArrayList<String>()

        // 1) 新浪外盘期货分时K线（伦敦金 XAU / COMEX 金 GC）
        for (sym in listOf("XAU", "GC")) {
            for (period in listOf("60m", "5m")) {
                val u = "https://stock2.finance.sina.com.cn/futures/api/jsonp.php/var%20t=/" +
                    "GlobalFuturesService.getGlobalFuturesMiniKLine" + period + "?symbol=" + sym
                val body = get(u, "https://finance.sina.com.cn")
                if (body.isNullOrBlank()) {
                    errs.add("sina" + period + " " + lastError)
                    continue
                }
                val m = parseSina(body)
                if (m.isNotEmpty()) {
                    lastError = ""
                    return m
                }
                errs.add("sina" + period + " 无今日点")
            }
        }

        // 2) 腾讯外盘期货 60 分钟 K 线
        for (sym in listOf("hf_XAU", "hf_GC")) {
            val u = "https://web.ifzq.gtimg.cn/appstock/app/kline/mkline?param=" + sym + ",m60,,48"
            val body = get(u, "https://gu.qq.com")
            if (body.isNullOrBlank()) {
                errs.add("tx " + lastError)
                continue
            }
            val m = parseTencent(body)
            if (m.isNotEmpty()) {
                lastError = ""
                return m
            }
            errs.add("tx 无今日点")
        }

        lastError = if (errs.isEmpty()) "hist 不可用" else errs.joinToString(" | ").take(140)
        return emptyMap()
    }

    /** 新浪 JSONP：var t=([{"d":"2026-11-04 13:00:00","c":"3960.86",...}]) */
    private fun parseSina(body: String): Map<Int, Double> {
        val a = body.indexOf('[')
        val b = body.lastIndexOf(']')
        if (a < 0 || b <= a) return emptyMap()
        val arr = try {
            JSONArray(body.substring(a, b + 1))
        } catch (_: Exception) {
            return emptyMap()
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val bars = ArrayList<Pair<Long, Double>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val d = o.optString("d", "")
            if (d.length < 19) continue
            val t = try {
                fmt.parse(d)?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
            if (t <= 0L) continue
            val c = o.optString("c", "").toDoubleOrNull() ?: o.optDouble("c", 0.0)
            if (c > 100.0) bars.add(t to c)
        }
        return toHourMap(bars)
    }

    /** 腾讯：{"data":{"hf_XAU":{"m60":[["202611041300","开","收","高","低",...]]}}} */
    private fun parseTencent(body: String): Map<Int, Double> {
        val root = try {
            JSONObject(body)
        } catch (_: Exception) {
            return emptyMap()
        }
        val data = root.optJSONObject("data") ?: return emptyMap()
        val f12 = SimpleDateFormat("yyyyMMddHHmm", Locale.US)
        val bars = ArrayList<Pair<Long, Double>>()
        val nk = data.keys()
        while (nk.hasNext()) {
            val node = data.optJSONObject(nk.next()) ?: continue
            val ik = node.keys()
            while (ik.hasNext()) {
                val name = ik.next()
                if (!name.startsWith("m")) continue
                val arr = node.optJSONArray(name) ?: continue
                for (i in 0 until arr.length()) {
                    val it = arr.optJSONArray(i) ?: continue
                    if (it.length() < 3) continue
                    val s = it.optString(0, "")
                    if (s.length != 12) continue
                    val v = it.optString(2, "").toDoubleOrNull() ?: continue
                    if (v <= 100.0) continue
                    val t = try {
                        f12.parse(s)?.time ?: 0L
                    } catch (_: Exception) {
                        0L
                    }
                    if (t > 0L) bars.add(t to v)
                }
            }
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
