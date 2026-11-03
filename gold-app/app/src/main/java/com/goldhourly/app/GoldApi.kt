package com.goldhourly.app

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** 网络：现货金价（美元/盎司）+ 美元兑人民币汇率 */
object GoldApi {

    const val OZ_GRAMS = 31.1034768

    private fun get(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "GoldHourly/1.0")
                setRequestProperty("Cache-Control", "no-cache")
            }
            if (conn.responseCode != 200) {
                null
            } else {
                BufferedReader(conn.inputStream.bufferedReader()).use { it.readText() }
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 失败返回 0.0 */
    fun priceUsd(): Double {
        repeat(2) { i ->
            val body = get("https://api.gold-api.com/price/XAU?x=" + System.currentTimeMillis())
            if (!body.isNullOrBlank()) {
                try {
                    val v = JSONObject(body).optDouble("price", 0.0)
                    if (v > 100) return v
                } catch (_: Exception) {
                }
            }
            if (i < 1) {
                try {
                    Thread.sleep(1000)
                } catch (_: Exception) {
                }
            }
        }
        return 0.0
    }

    /** 失败返回 0.0 */
    fun usdCny(): Double {
        val body = get("https://open.er-api.com/v6/latest/USD") ?: return 0.0
        return try {
            val v = JSONObject(body).optJSONObject("rates")?.optDouble("CNY", 0.0) ?: 0.0
            if (v > 1) v else 0.0
        } catch (_: Exception) {
            0.0
        }
    }
}
