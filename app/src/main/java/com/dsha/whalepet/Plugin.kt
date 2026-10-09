package com.dsha.whalepet

import org.json.JSONObject

/**
 * 插件能力（capability）。插件必须在 JSON 中声明或在动作中体现，
 * 用户授权后插件才能调用对应能力；敏感能力另需运行时二次确认。
 */
object PluginCaps {
    const val SAY = "say"
    const val NOTIFY = "notify"
    const val VIBRATE = "vibrate"
    const val OPEN_URL = "openUrl"
    const val OPEN_APP = "openApp"
    const val SHIZUKU = "shizuku"

    /** 敏感能力：执行前需要用户二次确认。 */
    val SENSITIVE: Set<String> = setOf(OPEN_URL, OPEN_APP, SHIZUKU)

    val ALL: Set<String> = setOf(SAY, NOTIFY, VIBRATE, OPEN_URL, OPEN_APP, SHIZUKU)

    fun label(cap: String): String = when (cap) {
        SAY -> "让鲸鱼娘说话"
        NOTIFY -> "发送通知"
        VIBRATE -> "震动"
        OPEN_URL -> "打开网页"
        OPEN_APP -> "打开其他应用"
        SHIZUKU -> "执行系统命令（Shizuku）"
        else -> cap
    }
}

/** 插件动作。 */
data class PluginAction(
    val type: String,
    val text: String? = null,
    val title: String? = null,
    val url: String? = null,
    val pkg: String? = null,
    val ms: Long? = null,
    val args: List<String>? = null
) {
    /** 该动作需要的能力；log 等纯记录动作返回空串。 */
    val capability: String
        get() = when (type) {
            "say" -> PluginCaps.SAY
            "notify" -> PluginCaps.NOTIFY
            "vibrate" -> PluginCaps.VIBRATE
            "openUrl" -> PluginCaps.OPEN_URL
            "openApp" -> PluginCaps.OPEN_APP
            "shizuku" -> PluginCaps.SHIZUKU
            else -> ""
        }

    /** 可读描述（用于二次确认与审计）。 */
    fun describe(): String = when (type) {
        "say" -> "说话：${text ?: ""}"
        "notify" -> "发送通知：${title ?: ""} ${text ?: ""}".trim()
        "vibrate" -> "震动 ${ms ?: 300}ms"
        "openUrl" -> "打开网页：${url ?: ""}"
        "openApp" -> "打开应用：${pkg ?: ""}"
        "shizuku" -> "执行系统命令：${args?.joinToString(" ") ?: ""}"
        "log" -> "记录：${text ?: ""}"
        else -> "未知动作：$type"
    }
}

/** 规则：某事件发生（可选冷却）时执行一组动作。 */
data class PluginRule(
    val event: String,
    val intervalMs: Long = 0L,
    val cooldownMs: Long = 0L,
    val actions: List<PluginAction>
)

/** 插件定义（一个 JSON 文件对应一个插件）。 */
data class Plugin(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val capabilities: Set<String>,
    val rules: List<PluginRule>,
    /** 脚本型插件的 JS 源码；为空表示规则型（JSON 规则）。 */
    val script: String = ""
) {
    /** 是否脚本型插件。 */
    val isScript: Boolean
        get() = script.isNotBlank()

    companion object {
        /** 解析插件 JSON；格式不合法返回 null。 */
        fun parse(json: String): Plugin? = try {
            val o = JSONObject(json)
            val id = o.optString("id").trim()
            if (id.isEmpty()) {
                null
            } else {
                val rules = mutableListOf<PluginRule>()
                o.optJSONArray("rules")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val r = arr.optJSONObject(i) ?: continue
                        val whenObj = r.optJSONObject("when") ?: continue
                        val event = whenObj.optString("event").trim()
                        if (event.isEmpty()) continue
                        val actions = mutableListOf<PluginAction>()
                        r.optJSONArray("then")?.let { ta ->
                            for (j in 0 until ta.length()) {
                                val a = ta.optJSONObject(j) ?: continue
                                val type = a.optString("action").trim()
                                if (type.isEmpty()) continue
                                actions.add(
                                    PluginAction(
                                        type = type,
                                        text = a.optString("text").ifEmpty { null },
                                        title = a.optString("title").ifEmpty { null },
                                        url = a.optString("url").ifEmpty { null },
                                        pkg = a.optString("pkg").ifEmpty { null },
                                        ms = if (a.has("ms")) a.optLong("ms") else null,
                                        args = a.optJSONArray("args")?.let { argsArr ->
                                            (0 until argsArr.length()).map { k2 -> argsArr.optString(k2) }
                                        }
                                    )
                                )
                            }
                        }
                        if (actions.isNotEmpty()) {
                            rules.add(
                                PluginRule(
                                    event = event,
                                    intervalMs = whenObj.optLong("intervalMs", 0L),
                                    cooldownMs = r.optLong("cooldownMs", 0L),
                                    actions = actions
                                )
                            )
                        }
                    }
                }
                val caps = mutableSetOf<String>()
                o.optJSONArray("capabilities")?.let { ca ->
                    for (i in 0 until ca.length()) caps.add(ca.optString(i))
                }
                // 规则里实际用到的能力一并纳入，避免漏声明
                rules.flatMap { it.actions }.forEach { a ->
                    if (a.capability.isNotEmpty()) caps.add(a.capability)
                }
                Plugin(
                    id = id,
                    name = o.optString("name").ifEmpty { id },
                    enabled = o.optBoolean("enabled", true),
                    capabilities = caps,
                    rules = rules,
                    script = o.optString("script").trim()
                )
            }
        } catch (_: Throwable) {
            null
        }
    }
}
