package com.dsha.whalepet.plugin

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

    /**
     * 动作类型 → 所需能力；纯记录（`log`）与未知动作返回空串。
     *
     * 这是"动作"与"能力"之间**唯一**的映射来源：[PluginAction.capability] 与
     * `PluginManager` 的能力校验都走这里。此前两处各写一份 `when`，
     * 新增能力时容易只改一处，导致"声明了却永远判为未授权"。
     */
    fun ofAction(type: String): String = when (type) {
        SAY -> SAY
        NOTIFY -> NOTIFY
        VIBRATE -> VIBRATE
        OPEN_URL -> OPEN_URL
        OPEN_APP -> OPEN_APP
        SHIZUKU -> SHIZUKU
        else -> ""
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
        get() = PluginCaps.ofAction(type)

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

/**
 * 规则：某事件发生（可选冷却）时执行一组动作。
 *
 * 注意：**暂不支持定时事件**。旧版本解析过一个 `intervalMs` 字段但从未读取，
 * 留在模型里只会让人误以为"定时可用"，已移除（见 PLUGIN_SPEC §2）。
 */
data class PluginRule(
    val event: String,
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
    val script: String = "",
    /**
     * 脚本内容指纹（见 [PluginContentKey]）。
     *
     * 用于判断"是否需要重新加载"：此前用 `script.hashCode()`（32 位），
     * 碰撞时会漏掉重载，表现为"改了脚本不生效"。在解析时算一次并随模型缓存。
     */
    val scriptKey: String = PluginContentKey.of(script)
) {
    /** 是否脚本型插件。 */
    val isScript: Boolean
        get() = script.isNotBlank()

    companion object {
        /**
         * 插件 id 的允许字符集：字母或数字开头，其后只允许字母、数字、`.`、`_`、`-`，总长 ≤ 64。
         *
         * **为什么必须校验**：`id` 会直接拼成文件名（`<id>.json`）写进插件目录，而插件 JSON
         * 是用户从外部粘贴的**不可信输入**。不校验时 `"id": "../whale_lines"` 会穿过目录边界
         * 去写别的文件，`"id": "other-plugin"` 会覆盖另一个插件的文件。
         * 由于字符集里不含 `/`，路径穿越与目录逃逸在源头上就不可能发生。
         *
         * 大小写都允许，是为了不把用户已有的 `MyPlugin` 之类旧插件判为非法。
         */
        private val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

        /** id 是否合法（见 [ID_PATTERN]）。 */
        fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)

        /** 解析插件 JSON；格式不合法返回 null。 */
        fun parse(json: String): Plugin? = try {
            val o = JSONObject(json)
            val id = o.optString("id").trim()
            if (!isValidId(id)) {
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
