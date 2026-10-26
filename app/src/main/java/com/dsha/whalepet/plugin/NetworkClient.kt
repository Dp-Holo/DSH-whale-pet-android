package com.dsha.whalepet.plugin

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** 一次联网请求的结果，供调用方写审计（脚本拿到的是 [envelope]）。 */
data class NetResult(
    val ok: Boolean,
    val status: Int?,
    val bytes: Int,
    val error: String?,
    val envelope: String
) {
    /** 审计"结果"列。 */
    val auditResult: String get() = if (ok) "ok" else "error"

    /** 审计"详情"列。 */
    fun auditDetail(): String = when {
        status != null -> "status=$status ${bytes}B"
        else -> error ?: "请求失败"
    }
}

/**
 * 真正发 HTTP 请求的薄封装。
 *
 * **阻塞式**：调用方必须已经在后台线程上（脚本线程 / 插件管理 worker）。
 * 超时与大小上限由 [NetworkPolicy] 定，这里只负责照做。
 */
object NetworkClient {

    /**
     * @param body 仅 POST 使用；GET 传 null
     * @return 结果（含给脚本的 JSON 信封）
     */
    fun request(method: String, url: String, body: String?): NetResult {
        val verb = method.uppercase()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = verb
                connectTimeout = NetworkPolicy.CONNECT_TIMEOUT_MS
                readTimeout = NetworkPolicy.READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "WhalePet-Plugin/1")
                if (verb == "POST") {
                    doOutput = true
                    setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                }
            }
            if (verb == "POST") {
                val payload = (body ?: "").toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(payload.size)
                conn.outputStream.use { it.write(payload) }
            }
            val status = conn.responseCode
            // 4xx/5xx 的正文在 errorStream 里，一并读出来给插件判断
            val stream: InputStream? = if (status in 200..299) conn.inputStream else conn.errorStream
            val (text, truncated) = readCapped(stream)
            val envelope = NetworkPolicy.envelope(status, text, truncated)
            NetResult(
                ok = status in 200..299,
                status = status,
                bytes = text.length,
                error = if (status in 200..299) null else "HTTP $status",
                envelope = envelope
            )
        } catch (t: Throwable) {
            val reason = t.message ?: t.javaClass.simpleName
            NetResult(
                ok = false,
                status = null,
                bytes = 0,
                error = reason,
                envelope = NetworkPolicy.errorEnvelope(reason)
            )
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 只读上限字节：先多读 1 字节用来判断"是否还有更多"，
     * 这样不会因为一个超大响应把内存吃光。
     */
    private fun readCapped(stream: InputStream?): Pair<String, Boolean> {
        if (stream == null) return "" to false
        return stream.use { input ->
            val limit = NetworkPolicy.MAX_RESPONSE_BYTES
            val buf = ByteArray(limit + 1)
            var total = 0
            while (total < buf.size) {
                val n = input.read(buf, total, buf.size - total)
                if (n <= 0) break
                total += n
            }
            if (total > limit) {
                String(buf, 0, limit, Charsets.UTF_8) to true
            } else {
                String(buf, 0, total, Charsets.UTF_8) to false
            }
        }
    }
}
