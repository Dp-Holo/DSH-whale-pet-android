package com.dsha.whalepet.plugin

import org.json.JSONObject
import java.net.URI

/**
 * 联网能力的策略（**纯逻辑，无 Android 依赖 → 可被 JVM 单测覆盖**）。
 *
 * 判断与限制集中在这里，真正发请求的是 [NetworkClient]。分开的原因：
 * URL 白名单、方法白名单、大小上限这些是"契约"，必须有用例兜底；
 * 而 socket 读写没法在单测里跑。
 */
object NetworkPolicy {

    /** 响应体读取上限：插件脚本里再大的响应也没法用，64KB 够放 JSON/HTML 片段。 */
    const val MAX_RESPONSE_BYTES = 64 * 1024

    /** 连接/读取超时。取 8 秒：比 Shizuku 的 5 秒宽松，但不至于让插件线程卡太久。 */
    const val CONNECT_TIMEOUT_MS = 8_000
    const val READ_TIMEOUT_MS = 8_000

    /** 同一插件两次联网的最小间隔，防止被当成攻击流量 / 把自己电量刷光。 */
    const val MIN_INTERVAL_MS = 1_000L

    /** URL 长度上限（超长的 URL 基本是异常或攻击）。 */
    const val MAX_URL_LENGTH = 2_048

    /** 只允许 http/https。file:// 之类会被明确拒绝。 */
    private val ALLOWED_SCHEMES = setOf("http", "https")

    /**
     * 允许的方法：GET 与 POST。
     *
     * 刻意不支持 PUT/DELETE/PATCH —— 插件只需要"读数据"和"提交表单"，
     * 放开破坏性动词只会扩大误操作面。
     */
    val ALLOWED_METHODS: Set<String> = setOf("GET", "POST")

    /** 校验结果。 */
    sealed class Check {
        object Ok : Check()
        data class Reject(val reason: String) : Check()
    }

    /** 校验方法 + URL；不通过时给出可直接展示给用户的原因。 */
    fun check(method: String, url: String): Check {
        val m = method.uppercase()
        if (m !in ALLOWED_METHODS) {
            return Check.Reject("不支持的请求方法：$method（只允许 GET / POST）")
        }
        val raw = url.trim()
        if (raw.isEmpty()) return Check.Reject("URL 为空")
        if (raw.length > MAX_URL_LENGTH) {
            return Check.Reject("URL 过长（上限 $MAX_URL_LENGTH 字符）")
        }
        val uri = try {
            URI(raw)
        } catch (_: Throwable) {
            return Check.Reject("URL 格式不正确")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme !in ALLOWED_SCHEMES) {
            return Check.Reject("只允许 http/https（收到：${uri.scheme ?: "无协议"}）")
        }
        if (uri.host.isNullOrBlank()) return Check.Reject("URL 缺少主机名")
        return Check.Ok
    }

    /**
     * 审计用的 URL：**丢掉 query 与 userinfo**。
     *
     * 插件很可能把 API key 放在 query 里，而审计日志是给用户看、还会被导出的——
     * 把密钥写进日志等于换个地方泄露。
     */
    fun auditUrl(url: String): String = try {
        val u = URI(url.trim())
        buildString {
            u.scheme?.let { append(it).append("://") }
            u.host?.let { append(it) }
            if (u.port > 0) append(":").append(u.port)
            u.rawPath?.let { append(it) }
        }.ifEmpty { "(无法解析的 URL)" }
    } catch (_: Throwable) {
        "(无法解析的 URL)"
    }

    /**
     * 成功拿到响应时给脚本的信封（JSON 字符串，脚本可 `JSON.parse`）。
     *
     * 用 [JSONObject] 拼而不是手写字符串：响应体里的引号/换行必须被正确转义，
     * 否则插件拿到的 JSON 直接坏掉。
     */
    fun envelope(status: Int, body: String, truncated: Boolean): String =
        JSONObject().apply {
            put("ok", status in 200..299)
            put("status", status)
            put("truncated", truncated)
            put("body", body)
        }.toString()

    /** 请求根本没发出去（DNS/超时/证书等）时的信封。 */
    fun errorEnvelope(reason: String): String =
        JSONObject().apply {
            put("ok", false)
            put("error", reason)
        }.toString()
}
