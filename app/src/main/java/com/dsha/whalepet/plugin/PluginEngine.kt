package com.dsha.whalepet.plugin

import android.content.Context as AppContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * ② 脚本型插件引擎：负责**调度与记账**；沙箱本身在 [ScriptSandbox]（不依赖 Android，可单测）。
 *
 * 职责划分：
 *  - [ScriptSandbox]：建作用域、ClassShutter 白名单、指令上限、执行脚本
 *  - 本类：后台单线程串行调度、脚本新鲜度判断、把加载/执行结果写进审计
 *
 * 插件可用 API（宿主胶水注入，见 [ScriptSandbox]）：
 *  say(t) · notify(t,m) · vibrate(ms) · openUrl(u) · openApp(pkg) · shizuku(...args) · log(t)
 *  on(event, handler)  —— 事件订阅，handler 收到事件参数对象
 */
object PluginEngine {

    private class Loaded(val script: ScriptSandbox.Script)

    /**
     * 已加载插件：id → 沙箱脚本。
     *
     * 用 [ConcurrentHashMap] 而不是普通 Map：写入发生在 `whale-plugin` 线程，
     * 而 [isLoaded] / [isLoadedFresh] 会在事件分发时被另一条线程调用，
     * 普通 HashMap 在并发读写下可能返回错值或抛异常。
     */
    private val loaded = ConcurrentHashMap<String, Loaded>()

    /**
     * 已尝试加载的 token：id → 当次脚本来源的指纹（**成功与失败都记**）。
     *
     * 为什么要记失败：脚本文件没选/失效时加载会失败。若只按"是否加载成功"判断，
     * 每个事件（点击、每 3 秒的余额检测）都会重试一次并写一条审计，日志被刷屏。
     */
    private val attempted = ConcurrentHashMap<String, String>()

    /** 单线程执行器：所有脚本（加载与事件）都在这条线程上串行跑。 */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "whale-plugin").apply { isDaemon = true }
    }

    fun isLoaded(pluginId: String): Boolean = loaded.containsKey(pluginId)

    /**
     * 这次脚本来源是否已经尝试过（无论成败）。
     *
     * token 由 `PluginManager.scriptToken()` 给出：内联内容指纹 + 已绑定的脚本 URI。
     * 换文件或改 JSON 都会让 token 变化，从而触发重新加载。
     *
     * 不用 32 位 `hashCode()`：碰撞时会漏掉重载，用户表现为"改了没生效"。
     */
    fun isLoadedFresh(pluginId: String, token: String): Boolean = attempted[pluginId] == token

    /** 异步加载（或重载）脚本插件；语法错误会写入审计。 */
    fun load(ctx: AppContext, plugin: Plugin, host: PluginHost) {
        if (!plugin.isScript) return
        executor.execute { loadInternal(ctx, plugin, host) }
    }

    fun unload(pluginId: String) {
        executor.execute {
            loaded.remove(pluginId)
            attempted.remove(pluginId)
        }
    }

    /** 异步触发某插件的某事件。 */
    fun fireEvent(ctx: AppContext, pluginId: String, event: String, payloadJson: String) {
        executor.execute { fireInternal(ctx, pluginId, event, payloadJson) }
    }

    /** 异步卸载全部（服务停止时调用）。 */
    fun shutdown() {
        executor.execute {
            loaded.clear()
            attempted.clear()
        }
    }

    // ── 以下均在插件线程上执行 ────────────────────────────────

    /**
     * 加载脚本：先确定来源（已绑定文件 > 内联 `script`），再编译进沙箱。
     *
     * 文件读取放在这条线程上——它已经是后台线程，不会卡 UI。
     * 无论成功失败都会记下 token（见 [attempted]），避免每个事件重试刷审计。
     */
    private fun loadInternal(ctx: AppContext, plugin: Plugin, host: PluginHost) {
        loaded.remove(plugin.id)
        val token = PluginManager.scriptToken(ctx, plugin)
        try {
            when (val source = PluginManager.loadScriptText(ctx, plugin)) {
                is ScriptSource.Missing ->
                    PluginManager.audit(ctx, plugin.id, "load", "脚本未加载", "error", source.reason)

                is ScriptSource.Inline ->
                    compileAndStore(ctx, plugin, host, source.text, "内联脚本")

                is ScriptSource.File ->
                    compileAndStore(ctx, plugin, host, source.text, "脚本文件")
            }
        } catch (t: Throwable) {
            PluginManager.audit(
                ctx, plugin.id, "load", "脚本加载失败", "error",
                t.message ?: t.javaClass.simpleName
            )
        } finally {
            attempted[plugin.id] = token
        }
    }

    private fun compileAndStore(
        ctx: AppContext,
        plugin: Plugin,
        host: PluginHost,
        scriptText: String,
        sourceDesc: String
    ) {
        val bridge = PluginBridge(ctx, plugin, host)
        val script = ScriptSandbox.compile(bridge, scriptText, "${plugin.id}($sourceDesc)")
        loaded[plugin.id] = Loaded(script)
        if (script.aborted) {
            // 加载阶段就死循环：同样要留痕，否则只表现为"插件没反应"
            PluginManager.audit(
                ctx, plugin.id, "load", "脚本加载失败", "error", ScriptSandbox.ABORT_DETAIL
            )
        } else {
            PluginManager.audit(ctx, plugin.id, "load", "脚本已加载", "ok", "来源：$sourceDesc")
        }
    }

    /**
     * 执行事件并记账。
     *
     * 三个来源都要能被审计区分出来：
     *  1. **超出指令上限**——以观察器留下的 [ScriptSandbox.Script.aborted] 为准。
     *     不依赖 `TimeoutError` 能否抛到这里：Rhino 对 Error 的分派是它自己的实现细节，
     *     若它被就地吞掉（例如脚本层 `catch`），只靠 catch 就会"日志里什么都没有"。
     *  2. **处理器自己抛异常**——`__fire` 会收集并返回摘要，不就地吞掉。
     *  3. 其它逃出来的异常（例如作用域丢失）。
     */
    private fun fireInternal(ctx: AppContext, pluginId: String, event: String, payloadJson: String) {
        val item = loaded[pluginId] ?: return
        var handlerError: String? = null
        var thrown: Throwable? = null
        try {
            handlerError = ScriptSandbox.fire(item.script, event, payloadJson)
        } catch (t: Throwable) {
            thrown = t
        }
        when {
            // 超时优先：此时 handlerError/thrown 可能是它带出来的副作用，不重复记两条
            item.script.aborted ->
                PluginManager.audit(
                    ctx, pluginId, event, "事件处理", "error", ScriptSandbox.ABORT_DETAIL
                )
            handlerError != null ->
                PluginManager.audit(
                    ctx, pluginId, event, "事件处理", "error", "处理器抛出异常：$handlerError"
                )
            thrown != null ->
                PluginManager.audit(
                    ctx, pluginId, event, "事件处理", "error",
                    thrown.message ?: thrown.javaClass.simpleName
                )
        }
    }

    /**
     * 脚本可见的唯一 Java 对象：所有能力调用统一进入 PluginManager.handleCall。
     *
     * 声明为 public（而非 private）是必要的：Rhino 通过反射调用它的方法，
     * 私有嵌套类在 JVM 上会因访问检查而调用失败。它的类名同时是 ClassShutter 的唯一白名单项。
     */
    class PluginBridge(
        private val appCtx: AppContext,
        private val plugin: Plugin,
        private val host: PluginHost
    ) {
        fun say(text: String): String = call("say", listOf(text))
        fun notify(title: String, text: String): String = call("notify", listOf(title, text))
        fun vibrate(ms: String): String = call("vibrate", listOf(ms))
        fun openUrl(url: String): String = call("openUrl", listOf(url))
        fun openApp(pkg: String): String = call("openApp", listOf(pkg))
        fun httpGet(url: String): String = call("httpGet", listOf(url))
        fun httpPost(url: String, body: String): String = call("httpPost", listOf(url, body))
        fun shizuku(joined: String): String = call("shizuku", joined.split('\u0001'))
        fun log(text: String): String = call("log", listOf(text))

        private fun call(action: String, args: List<String>): String =
            PluginManager.handleCall(appCtx, host, plugin, action, args)
    }
}
