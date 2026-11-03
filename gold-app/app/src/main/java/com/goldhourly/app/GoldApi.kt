package com.goldhourly.app

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

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
}
