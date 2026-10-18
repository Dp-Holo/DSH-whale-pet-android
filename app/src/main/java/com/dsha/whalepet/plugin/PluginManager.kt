package com.dsha.whalepet.plugin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 插件宿主能力接口（由本包 PluginHostImpl 实现；敏感确认走悬浮确认条）。
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

    /** 执行白名单系统命令（Shizuku）；返回值需能区分"被白名单拒绝"。 */
    fun pluginShizuku(args: List<String>): ShizukuResult

    /**
     * 敏感操作二次确认（实现方需在主线程回调结果）。
     *
     * 回调必须带 [ConfirmReason]：审计要记录**真实**结束原因——超时、被后来的确认顶掉、
     * 服务停止都曾被统一写成"用户拒绝"，而用户其实根本没看到确认条。
     */
    fun pluginConfirm(
        pluginName: String,
        actionDesc: String,
        onResult: (allowed: Boolean, remember: Boolean, reason: ConfirmReason) -> Unit
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

    /** 插件目录指纹的缓存有效期。 */
    private const val STAMP_TTL_MS = 3_000L

    /** 规则触发的冷却记录：`pluginId#ruleIndex` → 上次触发时间。 */
    private val lastFire = ConcurrentHashMap<String, Long>()

    /**
     * 规则型动作与敏感确认回调的执行线程。
     *
     * 为什么必须有这条线程：App 侧 `shizuku` 实现内部最长等 5 秒，而规则型动作
     * （事件由主线程分发）与确认回调（按钮点击在主线程）此前都在**主线程**直接调用它，
     * Shizuku 绑定慢时就会阻塞主线程直到 ANR。脚本型插件本就在自己的后台线程执行。
     */
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "whale-plugin-mgmt").apply { isDaemon = true }
    }

    // ── 文件与加载 ────────────────────────────────────────────

    private fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, DIR)
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun files(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toList() ?: emptyList()

    /** 插件列表缓存：事件触发时避免每次都读盘 + 解析 JSON。 */
    private var cache: List<Plugin>? = null
    private var cacheStamp: Long = -1L

    /** 目录指纹缓存：`listFiles()` 是磁盘 IO，不必每次事件都做。 */
    private var stampValue: Long = -1L
    private var stampAt: Long = 0L

    /**
     * 插件目录的"最后修改时间"（用于让缓存随文件改动失效）。
     *
     * 每次事件都 `listFiles()` 在主线程上是白做的 IO，这里缓存 3 秒：
     * 外部改动最多延迟这么久被发现，而安装/启停/删除会走 [invalidateCache] 立刻生效。
     */
    private fun dirStamp(ctx: Context): Long {
        val now = System.currentTimeMillis()
        val age = now - stampAt
        // 用 LongRange：age 是 Long，写成 IntRange 会编译不过
        if (stampValue >= 0 && age in 0L until STAMP_TTL_MS) return stampValue
        val v = dir(ctx).listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L
        stampValue = v
        stampAt = now
        return v
    }

    /** 清空缓存（安装/启用/删除/文件被外部改动后调用）。 */
    @Synchronized
    fun invalidateCache() {
        cache = null
        cacheStamp = -1L
        stampValue = -1L
        stampAt = 0L
    }

    /**
     * 读取全部插件。
     *
     * `@Synchronized`：事件分发在 [worker] 上调用，而设置页的插件列表在主线程调用，
     * 两边都会走到这里读写缓存。
     */
    @Synchronized
    fun loadAll(ctx: Context): List<Plugin> {
        val stamp = dirStamp(ctx)
        cache?.let { if (stamp == cacheStamp) return it }
        val list = files(ctx).mapNotNull { f ->
            val parsed = try {
                Plugin.parse(f.readText())
            } catch (_: Throwable) {
                null
            }
            if (parsed == null) {
                // 不静默丢弃：JSON 写错或 id 不合规时用户只会看到"装了但没反应"。
                // 缓存保证同一份坏文件不会反复刷日志。
                audit(ctx, f.name, "load", "插件文件无法解析，已跳过", "error")
            }
            parsed
        }
        cache = list
        cacheStamp = stamp
        return list
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
            val target = File(dir(ctx), "${p.id}.json")
            // 覆盖安装必须先清空旧授权。授权（含"始终允许"）是按 pluginId 存储的，
            // 若沿用旧记录，新脚本会直接继承上一份脚本的全部能力——用户从外部
            // 粘贴一个同 id 的 JSON，就等于跳过了授权确认这一步。
            if (target.exists()) {
                revokeAll(ctx, p.id)
            }
            target.writeText(json)
            invalidateCache()
            PluginEngine.unload(p.id)          // 覆盖安装后强制重新加载
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
            invalidateCache()
            PluginEngine.unload(id)            // 停用/启用都先卸载，启用时事件到来会自动重新加载
            // 启停是管理操作，审计链不能在这里断掉
            audit(ctx, id, "manage", if (enabled) "启用插件" else "停用插件", "ok")
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun delete(ctx: Context, id: String): Boolean {
        val f = fileOf(ctx, id) ?: return false
        return try {
            PluginEngine.unload(id)
            invalidateCache()
            val deleted = f.delete()         // try 的值：删除是否成功
            if (deleted) {
                // 授权记录与冷却记录也要一起清：否则同 id 重新安装会继承旧授权
                // （install 只在"文件已存在"时 revoke，文件已被删的情况挡不住）
                revokeAll(ctx, id)
                lastFire.keys.removeAll { it.startsWith("$id#") }
            }
            // 删除是最关键的管理操作之一，必须留痕
            audit(ctx, id, "manage", "删除插件", if (deleted) "ok" else "error")
            deleted
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
        // 空集合直接忽略：写入它只会留下一条"授权能力为空"的误导性审计，
        // 语义上也等于没授权（UI 层已挡，这里是公共 API 的兜底）。
        if (caps.isEmpty()) return
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
        // 行格式、时间戳与换行转义统一交给 AuditFormat：
        // 那边用不可变的 DateTimeFormatter（线程安全），并保证"一次调用 = 一行"，
        // 防止不可信的 pluginId / log 正文伪造出额外审计行。
        PluginLog.append(ctx, AuditFormat.line(pluginId, event, action, result, detail))
    }

    /** 审计日志路径（供界面展示）。 */
    fun auditLogPath(): String = PluginLog.filePath()

    // ── 事件分发 ──────────────────────────────────────────────

    /**
     * 分发事件给所有启用插件。
     * 规则型插件按规则执行动作；脚本型插件调用其注册的 handler。
     *
     * 立即切到 [worker] 执行：本方法由主线程（点击 / 电量 / 息屏广播）调用，
     * 而规则型动作里的 `shizuku` 可能阻塞数秒，直接执行有 ANR 风险。
     * 事件按提交顺序在单线程上串行处理，语义与同步版一致。
     */
    fun dispatch(ctx: Context, host: PluginHost, event: String, payloadJson: String = "{}") {
        worker.execute { dispatchInternal(ctx, host, event, payloadJson) }
    }

    private fun dispatchInternal(ctx: Context, host: PluginHost, event: String, payloadJson: String) {
        val now = System.currentTimeMillis()
        loadAll(ctx).filter { it.enabled }.forEach { plugin ->
            if (plugin.isScript) {
                // 脚本内容变了（用户改了文件/覆盖安装）也要重新加载，否则改动不生效
                if (!PluginEngine.isLoadedFresh(plugin.id, plugin.scriptKey)) {
                    PluginEngine.load(ctx, plugin, host)
                }
                PluginEngine.fireEvent(ctx, plugin.id, event, payloadJson)
            } else {
                plugin.rules.forEachIndexed { idx, rule ->
                    if (rule.event != event) return@forEachIndexed
                    val key = "${plugin.id}#$idx"
                    if (!RuleThrottle.shouldFire(lastFire[key], now, rule.cooldownMs)) {
                        return@forEachIndexed
                    }
                    lastFire[key] = now
                    pruneLastFire(now)
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

    /**
     * `lastFire` 只增不减会慢慢堆积（插件/规则删掉后键还留着）。
     * 超过上限时丢掉已经过期的条目——仍在冷却窗口内的记录必须保留，否则节流失效。
     */
    private fun pruneLastFire(now: Long) {
        if (lastFire.size <= RuleThrottle.MAX_TRACKED_RULES) return
        val cutoff = now - RuleThrottle.RETENTION_MS
        lastFire.filterValues { it < cutoff }.keys.forEach { lastFire.remove(it) }
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

    private fun capabilityOf(action: String): String = PluginCaps.ofAction(action)

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
            host.pluginConfirm(plugin.name, desc) { allowed, remember, reason ->
                if (!allowed) {
                    // 按真实原因记账：超时/被顶掉/服务停止都不等于"用户拒绝"
                    audit(ctx, plugin.id, "call", desc, "denied", reason.auditText())
                } else {
                    // 确认条最长挂起 15 秒。这段窗口里用户完全可能在管理页撤销授权、
                    // 停用或删除插件，然后回头点「允许」——不复查就等于让后到的拒绝失效。
                    val blockReason = revalidate(ctx, plugin, cap)
                    if (blockReason != null) {
                        audit(ctx, plugin.id, "call", desc, "denied", blockReason)
                    } else {
                        if (remember) setAlwaysAllowed(ctx, plugin.id, cap)
                        // 切到后台执行：perform 里的 shizuku 可能阻塞数秒，
                        // 而确认回调是在主线程（按钮点击）触发的。
                        worker.execute { perform(ctx, host, plugin, action, args, desc) }
                    }
                }
            }
            return "pending: 等待用户确认"
        }
        return perform(ctx, host, plugin, action, args, desc)
    }

    /**
     * 确认通过后、真正执行前的复查（TOCTOU）。
     *
     * 返回 null 表示放行；否则返回写进审计的拒绝理由。
     */
    private fun revalidate(ctx: Context, plugin: Plugin, cap: String): String? {
        if (cap !in granted(ctx, plugin.id)) return "授权已变更：该能力已被撤销"
        val current = loadAll(ctx).firstOrNull { it.id == plugin.id }
            ?: return "授权已变更：插件已被删除"
        if (!current.enabled) return "授权已变更：插件已被停用"
        return null
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
                // 按真实结果记账：白名单拒绝 ≠ 成功。
                // 此前结果写死 ok，等于把安全拦截记成了"执行成功"，日志里完全看不出来。
                audit(ctx, plugin.id, "call", desc, r.auditResult, r.message)
                r.message
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
