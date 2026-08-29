package com.dsha.whalepet

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * DeepSeek 开放平台余额查询。
 * 接口：GET https://api.deepseek.com/user/balance
 * 鉴权：Authorization: Bearer <API_KEY>
 */
object BalanceFetcher {

    private const val BALANCE_URL = "https://api.deepseek.com/user/balance"
    private const val TIMEOUT_MS = 8000
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 在后台线程查询，结果通过回调回到主线程。 */
    fun fetchAsync(apiKey: String, onResult: (String) -> Unit) {
        Thread {
            val text = fetch(apiKey)
            mainHandler.post { onResult(text) }
        }.start()
    }

    /** 同步查询；返回可直接展示的文案（失败也返回可读的错误文案）。 */
    fun fetch(apiKey: String): String {
        if (apiKey.isBlank()) return "未设置 API Key"
        return try {
            val conn = URL(BALANCE_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            try {
                val code = conn.responseCode
                if (code != 200) return "查询失败 HTTP $code"
                val body = BufferedReader(
                    InputStreamReader(conn.inputStream, Charsets.UTF_8)
                ).use { it.readText() }
                val obj = JSONObject(body)
                val info = obj.optJSONArray("balance_infos")
                    ?.optJSONObject(0)
                val balance = info?.optString("total_balance") ?: return "无余额数据"
                val currency = info.optString("currency", "CNY")
                format(balance, currency)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            "查询失败: ${e.message ?: "网络错误"}"
        }
    }

    /** 如 "4.65" + CNY → "余额 4.65 元" */
    private fun format(balance: String, currency: String): String {
        val num = balance.toDoubleOrNull()
        val text = if (num != null) String.format("%.2f", num) else balance
        val unit = if (currency.equals("CNY", true)) "元" else " $currency"
        return "余额 $text$unit"
    }
}
