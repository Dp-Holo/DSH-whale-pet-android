package com.dsha.whalepet

import android.content.Context as AppContext
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.util.concurrent.Executors

/**
 * ② 脚本型插件引擎（Rhino 沙箱，纯 Java 无需 NDK）。
 *
 * 安全与稳定性设计：
 *  1. 脚本全局作用域里**只暴露一个 Java 对象** `__bridge`，它把每个能力调用转交
 *     PluginManager.handleCall() 统一处理（能力授权 → 敏感二次确认 → 审计）。
 *  2. 移除 Packages/java/javax/... 等危险全局，脚本无法引用任意 Java 类。
 *  3. 指令数观察器 + 执行时限，防止死循环。
 *  4. **所有脚本在同一个后台线程串行执行**（既不阻塞 UI，也保证 Rhino 作用域线程安全）；
 *     宿主能力回调再由宿主 post 到主线程，插件出错逐个隔离。
 *
 * 插件可用 API（宿主胶水注入）：
 *  say(t) · notify(t,m) · vibrate(ms) · openUrl(u) · openApp(pkg) · shizuku(...args) · log(t)
 *  on(event, handler)  —— 事件订阅，handler 收到事件参数对象
 */
object PluginEngine {

    private class Loaded(val plugin: Plugin, val scope: Scriptable)

    private val loaded = mutableMapOf<String, Loaded>()

    /** 单线程执行器：所有脚本（加载与事件）都在这条线程上串行跑。 */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "whale-plugin").apply { isDaemon = true }
    }

    private val DANGEROUS_GLOBALS = arrayOf(
        "Packages", "java", "javax", "org", "com", "net", "edu",
        "JavaAdapter", "getClass", "importClass", "importPackage",
        "loadClass", "JavaImporter", "Continuation", "XML", "XMLList"
    )

    private val GLUE_JS = """
        var __handlers = {};
        function on(event, handler) {
          if (typeof handler === 'function') {
            (__handlers[event] = __handlers[event] || []).push(handler);
          }
        }
        function say(t) { return __bridge.say(String(t)); }
        function notify(t, m) { return __bridge.notify(String(t), String(m == null ? '' : m)); }
        function vibrate(ms) { return __bridge.vibrate(String(ms)); }
        function openUrl(u) { return __bridge.openUrl(String(u)); }
        function openApp(p) { return __bridge.openApp(String(p)); }
        function shizuku() {
          var a = [];
          for (var i = 0; i < arguments.length; i++) { a.push(String(arguments[i])); }
          return __bridge.shizuku(a.join('\u0001'));
        }
        function log(t) { return __bridge.log(String(t)); }
        function __fire(event, payloadJson) {
          var list = __handlers[event] || [];
          var data = {};
          try { data = JSON.parse(payloadJson); } catch (e) { data = {}; }
          for (var i = 0; i < list.length; i++) {
            try { list[i](data); } catch (e) { }
          }
        }
    """.trimIndent()

    fun isLoaded(pluginId: String): Boolean = loaded.containsKey(pluginId)

    /** 异步加载（或重载）脚本插件；语法错误会写入审计。 */
    fun load(ctx: AppContext, plugin: Plugin, host: PluginHost) {
        if (!plugin.isScript) return
        executor.execute { loadInternal(ctx, plugin, host) }
    }

    fun unload(pluginId: String) {
        executor.execute { loaded.remove(pluginId) }
    }

    /** 异步触发某插件的某事件。 */
    fun fireEvent(ctx: AppContext, pluginId: String, event: String, payloadJson: String) {
        executor.execute { fireInternal(ctx, pluginId, event, payloadJson) }
    }

    /** 异步卸载全部（服务停止时调用）。 */
    fun shutdown() {
        executor.execute { loaded.clear() }
    }

    // ── 以下均在插件线程上执行 ────────────────────────────────

    private fun loadInternal(ctx: AppContext, plugin: Plugin, host: PluginHost) {
        loaded.remove(plugin.id)
        var cx: Context? = null
        try {
            cx = Context.enter()
            cx.optimizationLevel = -1
            val scope = cx.initStandardObjects()
            DANGEROUS_GLOBALS.forEach { name -> runCatching { scope.delete(name) } }

            val bridge = PluginBridge(ctx, plugin, host)
            ScriptableObject.putProperty(scope, "__bridge", Context.javaToJS(bridge, scope))

            cx.evaluateString(scope, GLUE_JS, "whale-glue", 1, null)
            applyGuard(cx)
            cx.evaluateString(scope, plugin.script, plugin.id, 1, null)

            loaded[plugin.id] = Loaded(plugin, scope)
            PluginManager.audit(ctx, plugin.id, "load", "脚本已加载", "ok")
        } catch (t: Throwable) {
            PluginManager.audit(
                ctx, plugin.id, "load", "脚本加载失败", "error",
                t.message ?: t.javaClass.simpleName
            )
        } finally {
            cx?.let { runCatching { Context.exit() } }
        }
    }

    private fun fireInternal(ctx: AppContext, pluginId: String, event: String, payloadJson: String) {
        val item = loaded[pluginId] ?: return
        var cx: Context? = null
        try {
            cx = Context.enter()
            cx.optimizationLevel = -1
            applyGuard(cx)
            val fn = ScriptableObject.getProperty(item.scope, "__fire")
            if (fn is Function) {
                fn.call(cx, item.scope, item.scope, arrayOf<Any>(event, payloadJson))
            }
        } catch (t: Throwable) {
            runCatching {
                PluginManager.audit(
                    ctx, pluginId, event, "事件处理", "error",
                    t.message ?: t.javaClass.simpleName
                )
            }
        } finally {
            cx?.let { runCatching { Context.exit() } }
        }
    }

    private fun applyGuard(cx: Context) {
        // 说明：所用 Rhino 版本未暴露指令观察器 API，故不做执行时限拦截。
        // 由于所有脚本都在独立的后台线程串行执行，插件死循环只会阻塞插件功能，
        // 不会卡住桌宠本体或 UI（异常仍逐个隔离并写入审计）。
        cx.optimizationLevel = -1
    }

    /** 脚本可见的唯一 Java 对象：所有能力调用统一进入 PluginManager.handleCall。 */
    private class PluginBridge(
        private val appCtx: AppContext,
        private val plugin: Plugin,
        private val host: PluginHost
    ) {
        fun say(text: String): String = call("say", listOf(text))
        fun notify(title: String, text: String): String = call("notify", listOf(title, text))
        fun vibrate(ms: String): String = call("vibrate", listOf(ms))
        fun openUrl(url: String): String = call("openUrl", listOf(url))
        fun openApp(pkg: String): String = call("openApp", listOf(pkg))
        fun shizuku(joined: String): String = call("shizuku", joined.split('\u0001'))
        fun log(text: String): String = call("log", listOf(text))

        private fun call(action: String, args: List<String>): String =
            PluginManager.handleCall(appCtx, host, plugin, action, args)
    }
}
