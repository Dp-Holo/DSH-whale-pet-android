package com.dsha.whalepet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 插件宿主能力接口（由 WhalePetService 实现，全部在主线程调用）。
 *
 * 敏感动作的流程：插件调用 → handleCall 校验能力 → 未"始终允许"则调用
 * pluginConfirm 弹二次确认 → 用户允许后才执行 → 全程写入审计日志。
 */
interface PluginHost {
    fun pluginSay(text: String)
    fun pluginNotify(title: String, text: String)
    fun pluginVibrate(ms: Long)
    fun pluginOpenUrl(url: String): Boolean
    fun pluginOpenApp(pkg: String): Boolean

    /** 执行白名单系统命令（Shizuku），返回结果描述。 */
    fun pluginShizuku(args: List<String>): String

    /** 敏感操作二次确认（实现方需在主线程回调结果）。 */
    fun pluginConfirm(
        pluginName: String,
        actionDesc: String,
        onResult: (allowed: Boolean, remember: Boolean) -> Unit
    )
}

/**
 * 插件管理：文件读写、能力授权、审计、事件分发、脚本引擎调度。
 *
 * 安全模型（用户要求）：
 *  - 插件必须在 JSON 里声明 capabilities，用户授权后才能调用；
 *  - openUrl / openApp / shizuku 属敏感能力，每次执行前二次确认
 *    （用户可选"始终允许"以免重复询问）；
 *  - 每次调用（含被拒/失败）都写入 Download/whale-plugins.log 审计日志。
 */
object PluginManager {

    private const val DIR = "plugins"
    private const val PREFS = "whale_pet_prefs"
    private const val KEY_GRANTS = "plugin_grants"
    private const val KEY_ALWAYS = "plugin_always"

    /** 规则触发的冷却记录：pluginId#ruleIndex → 上次触发时间。 */
    private val lastFire = mutableMapOf<String, Long>()

    private val tsFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    // ── 文件与加载 ────────────────────────────────────────────

    private fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, DIR)
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun files(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toList() ?: emptyList()

    fun loadAll(ctx: Context): List<Plugin> = files(ctx).mapNotNull { f ->
        try {
            Plugin.parse(f.readText())
        } catch (_: Throwable) {
            null
        }
    }

    private fun fileOf(ctx: Context, id: String): File? = files(ctx).firstOrNull { f ->
        try {
            Plugin.parse(f.readText())?.id == id
        } catch (_: Throwable) {
            false
        }
    }

    /** 安装插件（覆盖同名文件）；解析失败返回 null。 */
    fun install(ctx: Context, json: String): Plugin? {
        val p = Plugin.parse(json) ?: return null
        return try {
            File(dir(ctx), "${p.id}.json").writeText(json)
            p
        } catch (_: Throwable) {
            null
        }
    }

    fun setEnabled(ctx: Context, id: String, enabled: Boolean): Boolean {
        val f = fileOf(ctx, id) ?: return false
        return try {
            val o = JSONObject(f.readText())
            o.put("enabled", enabled)
            f.writeText(o.toString(2))
            if (enabled) {
                Plugin.parse(o.toString())?.let { PluginEngine.unload(it.id) }
            } else {
                PluginEngine.unload(id)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun delete(ctx: Context, id: String): Boolean {
        val f = fileOf(ctx, id) ?: return false
        return try {
            PluginEngine.unload(id)
            f.delete()
        } catch (_: Throwable) {
            false
        }
    }

    /** 启动时预加载所有启用的脚本插件（提前暴露语法错误）。 */
    fun preloadScripts(ctx: Context, host: PluginHost) {
        loadAll(ctx).filter { it.enabled && it.isScript }.forEach { p ->
            PluginEngine.load(ctx, p, host)
        }
    }

    // ── 能力授权 ──────────────────────────────────────────────

    private fun readMap(ctx: Context, key: String): MutableMap<String, MutableSet<String>> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)
            ?: return mutableMapOf()
        val out = mutableMapOf<String, MutableSet<String>>()
        try {
            val o = JSONObject(raw)
            o.keys().forEach { pid ->
                val arr = o.optJSONArray(pid) ?: JSONArray()
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) set.add(arr.optString(i))
                out[pid] = set
            }
        } catch (_: Throwable) {
        }
        return out
    }

    private fun writeMap(ctx: Context, key: String, map: Map<String, Set<String>>) {
        val o = JSONObject()
        map.forEach { (k, v) ->
            val arr = JSONArray()
            v.forEach { arr.put(it) }
            o.put(k, arr)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key, o.toString()).apply()
    }

    fun granted(ctx: Context, pluginId: String): Set<String> =
        readMap(ctx, KEY_GRANTS)[pluginId] ?: emptySet()

    fun grant(ctx: Context, pluginId: String, caps: Collection<String>) {
        val map = readMap(ctx, KEY_GRANTS)
        val cur = map[pluginId] ?: mutableSetOf()
        cur.addAll(caps)
        map[pluginId] = cur
        writeMap(ctx, KEY_GRANTS, map)
        audit(ctx, pluginId, "grant", "授权能力：${caps.joinToString(", ")}", "ok")
    }

    /** 撤销该插件的全部授权（含"始终允许"记录）。 */
    fun revokeAll(ctx: Context, pluginId: String) {
        val map = readMap(ctx, KEY_GRANTS)
        map.remove(pluginId)
        writeMap(ctx, KEY_GRANTS, map)
        val always = readMap(ctx, KEY_ALWAYS)
        always.remove(pluginId)
        writeMap(ctx, KEY_ALWAYS, always)
        audit(ctx, pluginId, "revoke", "撤销全部授权", "ok")
    }

    fun isAlwaysAllowed(ctx: Context, pluginId: String, cap: String): Boolean =
        readMap(ctx, KEY_ALWAYS)[pluginId]?.contains(cap) == true

    fun setAlwaysAllowed(ctx: Context, pluginId: String, cap: String) {
        val map = readMap(ctx, KEY_ALWAYS)
        val cur = map[pluginId] ?: mutableSetOf()
        cur.add(cap)
        map[pluginId] = cur
        writeMap(ctx, KEY_ALWAYS, map)
    }

    // ── 审计 ──────────────────────────────────────────────────

    fun audit(
        ctx: Context,
        pluginId: String,
        event: String,
        action: String,
        result: String,
        detail: String = ""
    ) {
        val line = "${tsFormat.format(Date())} | $pluginId | $event | $action | $result" +
            if (detail.isBlank()) "" else " | $detail"
        PluginLog.append(ctx, line)
    }

    /** 审计日志路径（供界面展示）。 */
    fun auditLogPath(): String = PluginLog.filePath()

    // ── 事件分发 ──────────────────────────────────────────────

    /**
     * 分发事件给所有启用插件。
     * 规则型插件按规则执行动作；脚本型插件调用其注册的 handler。
     */
    fun dispatch(ctx: Context, host: PluginHost, event: String, payloadJson: String = "{}") {
        val now = System.currentTimeMillis()
        loadAll(ctx).filter { it.enabled }.forEach { plugin ->
            if (plugin.isScript) {
                if (!PluginEngine.isLoaded(plugin.id)) {
                    PluginEngine.load(ctx, plugin, host)
                }
                PluginEngine.fireEvent(ctx, plugin.id, event, payloadJson)
            } else {
                plugin.rules.forEachIndexed { idx, rule ->
                    if (rule.event != event) return@forEachIndexed
                    val key = "${plugin.id}#$idx"
                    if (rule.cooldownMs > 0 && now - (lastFire[key] ?: 0L) < rule.cooldownMs) {
                        return@forEachIndexed
                    }
                    lastFire[key] = now
                    rule.actions.forEach { action ->
                        handleCall(
                            ctx, host, plugin,
                            action.type,
                            actionArgs(action)
                        )
                    }
                }
            }
        }
    }

    private fun actionArgs(a: PluginAction): List<String> = when (a.type) {
        "say" -> listOf(a.text ?: "")
        "notify" -> listOf(a.title ?: "", a.text ?: "")
        "vibrate" -> listOf((a.ms ?: 300L).toString())
        "openUrl" -> listOf(a.url ?: "")
        "openApp" -> listOf(a.pkg ?: "")
        "shizuku" -> a.args ?: emptyList()
        "log" -> listOf(a.text ?: "")
        else -> emptyList()
    }

    // ── 统一能力入口（脚本与规则共用）──────────────────────────

    private fun capabilityOf(action: String): String = when (action) {
        "say" -> PluginCaps.SAY
        "notify" -> PluginCaps.NOTIFY
        "vibrate" -> PluginCaps.VIBRATE
        "openUrl" -> PluginCaps.OPEN_URL
        "openApp" -> PluginCaps.OPEN_APP
        "shizuku" -> PluginCaps.SHIZUKU
        else -> ""
    }

    private fun describe(action: String, args: List<String>): String = when (action) {
        "say" -> "说话：${args.getOrElse(0) { "" }}"
        "notify" -> "通知：${args.getOrElse(0) { "" }} ${args.getOrElse(1) { "" }}".trim()
        "vibrate" -> "震动 ${args.getOrElse(0) { "300" }}ms"
        "openUrl" -> "打开网页：${args.getOrElse(0) { "" }}"
        "openApp" -> "打开应用：${args.getOrElse(0) { "" }}"
        "shizuku" -> "执行系统命令：${args.joinToString(" ")}"
        "log" -> "记录：${args.getOrElse(0) { "" }}"
        else -> "未知动作：$action"
    }

    /**
     * 所有插件能力调用的唯一入口。
     * 返回可读结果字符串（脚本可拿到），同时写入审计。
     */
    fun handleCall(
        ctx: Context,
        host: PluginHost,
        plugin: Plugin,
        action: String,
        args: List<String>
    ): String {
        val cap = capabilityOf(action)
        val desc = describe(action, args)
        if (action == "log") {
            audit(ctx, plugin.id, "call", desc, "ok")
            return "ok"
        }
        if (cap.isEmpty()) {
            audit(ctx, plugin.id, "call", desc, "error", "未知动作")
            return "error: 未知动作 $action"
        }
        if (cap !in granted(ctx, plugin.id)) {
            audit(ctx, plugin.id, "call", desc, "denied", "未授权能力：${PluginCaps.label(cap)}")
            return "denied: 未授权 ${PluginCaps.label(cap)}"
        }
        if (cap in PluginCaps.SENSITIVE && !isAlwaysAllowed(ctx, plugin.id, cap)) {
            // 敏感操作：二次确认后才执行
            host.pluginConfirm(plugin.name, desc) { allowed, remember ->
                if (!allowed) {
                    audit(ctx, plugin.id, "call", desc, "denied", "用户拒绝")
                } else {
                    if (remember) setAlwaysAllowed(ctx, plugin.id, cap)
                    perform(ctx, host, plugin, action, args, desc)
                }
            }
            return "pending: 等待用户确认"
        }
        return perform(ctx, host, plugin, action, args, desc)
    }

    private fun perform(
        ctx: Context,
        host: PluginHost,
        plugin: Plugin,
        action: String,
        args: List<String>,
        desc: String
    ): String = try {
        when (action) {
            "say" -> {
                host.pluginSay(args.getOrElse(0) { "" })
                audit(ctx, plugin.id, "call", desc, "ok")
                "ok"
            }
            "notify" -> {
                host.pluginNotify(args.getOrElse(0) { "" }, args.getOrElse(1) { "" })
                audit(ctx, plugin.id, "call", desc, "ok")
                "ok"
            }
            "vibrate" -> {
                host.pluginVibrate(args.getOrNull(0)?.toLongOrNull() ?: 300L)
                audit(ctx, plugin.id, "call", desc, "ok")
                "ok"
            }
            "openUrl" -> {
                val ok = host.pluginOpenUrl(args.getOrElse(0) { "" })
                audit(ctx, plugin.id, "call", desc, if (ok) "ok" else "error", if (ok) "" else "无法打开")
                if (ok) "ok" else "error: 无法打开"
            }
            "openApp" -> {
                val ok = host.pluginOpenApp(args.getOrElse(0) { "" })
                audit(ctx, plugin.id, "call", desc, if (ok) "ok" else "error", if (ok) "" else "应用不存在")
                if (ok) "ok" else "error: 应用不存在"
            }
            "shizuku" -> {
                val r = host.pluginShizuku(args)
                audit(ctx, plugin.id, "call", desc, "ok", r)
                r
            }
            else -> {
                audit(ctx, plugin.id, "call", desc, "error", "未知动作")
                "error: 未知动作"
            }
        }
    } catch (t: Throwable) {
        audit(ctx, plugin.id, "call", desc, "error", t.message ?: t.javaClass.simpleName)
        "error: ${t.message ?: t.javaClass.simpleName}"
    }
}
