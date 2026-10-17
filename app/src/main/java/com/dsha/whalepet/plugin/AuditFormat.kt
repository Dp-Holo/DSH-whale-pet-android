package com.dsha.whalepet.plugin

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 审计日志的行格式（**纯逻辑，无 Android 依赖**，因此可被 JVM 单元测试覆盖）。
 *
 * 为什么单独抽出来：
 *  1. `SimpleDateFormat` 是非线程安全的，而 `audit()` 会被主线程（规则型插件、确认回调）
 *     与插件线程（脚本能力调用）同时调用 —— 共用实例会让时间戳错乱。
 *     这里换成不可变的 `DateTimeFormatter`，天然线程安全。
 *  2. 审计日志是**安全追责依据**，而 `pluginId`、动作描述、`log()` 正文都来自不可信的插件 JSON。
 *     若原样写入，插件可以塞入 `\n` 伪造出额外的日志行（例如假装别的插件被拒绝）。
 *     这里统一把 CR/LF 转义成可见字符，保证"一次调用 = 一行"。
 */
object AuditFormat {

    /** 与历史格式保持一致（MM-dd HH:mm:ss），避免已有的日志解析失效。 */
    private val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.US)

    /** 当前时间戳，形如 `10-12 09:03:11`。 */
    fun now(): String = LocalDateTime.now().format(TS)

    /**
     * 拼一行审计记录。
     *
     * 字段顺序：时间 | 插件id | 事件或动作类别 | 动作描述 | 结果 [| 详情]
     */
    fun line(
        pluginId: String,
        event: String,
        action: String,
        result: String,
        detail: String = ""
    ): String = buildString {
        append(now()).append(" | ")
        append(escape(pluginId)).append(" | ")
        append(escape(event)).append(" | ")
        append(escape(action)).append(" | ")
        append(escape(result))
        if (detail.isNotBlank()) {
            append(" | ").append(escape(detail))
        }
    }

    /**
     * 把换行转义为字面量 `\n` / `\r`。
     *
     * 不能用空白替换（会丢失信息、也让伪造痕迹不可见）：保留原文可读，同时杜绝多出一行。
     */
    fun escape(text: String): String =
        text.replace("\r", "\\r").replace("\n", "\\n")
}
