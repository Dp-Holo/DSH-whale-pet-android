package com.dsha.whalepet

import android.content.Context as AppContext
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
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
 *  2. 移除 Packages/java/javax/... 等危险全局；再用 ClassShutter 把脚本能访问的
 *     Java 类限制为 `__bridge` 一个，堵死 `__bridge.getClass()` → ClassLoader → Runtime
 *     的反射逃逸（只删全局变量拦不住这条）。
 *  3. 指令数观察器 + 单次执行上限，死循环会被 java.lang.Error 中止（脚本 catch 不住）。
 *  4. **所有脚本在同一个后台线程串行执行**（既不阻塞 UI，也保证 Rhino 作用域线程安全）；
 *     宿主能力回调再由宿主 post 到主线程，插件出错逐个隔离。
 *
 * 插件可用 API（宿主胶水注入）：
 *  say(t) · notify(t,m) · vibrate(ms) · openUrl(u) · openApp(pkg) · shizuku(...args) · log(t)
 *  on(event, handler)  —— 事件订阅，handler 收到事件参数对象
 */
object PluginEngine {

    private class Loaded(val plugin: Plugin, val scope: Scriptable, val scriptHash: Int)

    private val loaded = mutableMapOf<String, Loaded>()

    /** 单线程执行器：所有脚本（加载与事件）都在这条线程上串行跑。 */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "whale-plugin").apply { isDaemon = true }
    }

    /** 单次执行的指令上限，超过即中止本次执行（防死循环）。 */
    private const val MAX_INSTRUCTIONS_PER_RUN = 5_000_000

    /**
     * 脚本超时错误。
     *
     * 必须是 [java.lang.Error]：Rhino 解释器只对 Error 走 EX_NO_JS_STATE
     * （彻底终止脚本执行），脚本里的 try/catch 吞不掉它；若抛的是 Exception，
     * 会被 GLUE_JS 中 `__fire` 的 try/catch 静默忽略，死循环照样卡住插件线程。
     */
    private class ScriptTimeoutError : java.lang.Error("脚本执行超过指令上限（疑似死循环），已中止")

    /**
     * 类访问白名单：脚本只能看见能力桥 [PluginBridge] 这一个 Java 类。
     *
     * Rhino 在首次访问某个 Java 对象时会构造 JavaMembers，其构造函数会查
     * ClassShutter 并拒绝白名单外的类（见 JavaMembers(scope, cl) 的
     * `shutter.visibleToScripts(cl.getName())` 判断）。因此脚本即使用
     * `__bridge.getClass()` 拿到了 Class 对象，只要一使用它（建成员表）就会失败，
     * 无法顺着 ClassLoader 反射出 Runtime 进而绕过能力授权。
     */
    private val classShutter = ClassShutter { className ->
        className == PluginBridge::class.java.name
    }

    /**
     * 插件专用 ContextFactory：统一挂上类访问白名单、指令上限，并固定解释模式。
     *
     * 所有脚本都跑在专用的 `whale-plugin` 线程上，首次 enterContext() 必定由这里
     * makeContext()，因此白名单一定先于任何 Java 对象被包装而生效。
     */
    private val factory: ContextFactory = object : ContextFactory() {
        override fun makeContext(): Context {
            val cx = super.makeContext()
            cx.optimizationLevel = -1
            cx.setClassShutter(classShutter)
            cx.setInstructionObserverThreshold(MAX_INSTRUCTIONS_PER_RUN)
            return cx
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            throw ScriptTimeoutError()
        }
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

    /**
     * 已加载且脚本内容与当前一致？
     *
     * 只判断"是否加载过"会导致用户改了插件文件却不生效（必须停/开桌宠才行）。
     * 这里比对脚本内容 hash：内容变了就返回 false，调用方会重新加载。
     */
    fun isLoadedFresh(pluginId: String, scriptHash: Int): Boolean =
        loaded.containsKey(pluginId) && loaded[pluginId]?.scriptHash == scriptHash

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
        try {
            val cx = factory.enterContext()
            try {
                val scope = cx.initStandardObjects()
                DANGEROUS_GLOBALS.forEach { name -> runCatching { scope.delete(name) } }

                val bridge = PluginBridge(ctx, plugin, host)
                ScriptableObject.putProperty(scope, "__bridge", Context.javaToJS(bridge, scope))

                cx.evaluateString(scope, GLUE_JS, "whale-glue", 1, null)
                cx.evaluateString(scope, plugin.script, plugin.id, 1, null)

                loaded[plugin.id] = Loaded(plugin, scope, plugin.script.hashCode())
                PluginManager.audit(ctx, plugin.id, "load", "脚本已加载", "ok")
            } finally {
                Context.exit()
            }
        } catch (t: Throwable) {
            PluginManager.audit(
                ctx, plugin.id, "load", "脚本加载失败", "error",
                t.message ?: t.javaClass.simpleName
            )
        }
    }

    private fun fireInternal(ctx: AppContext, pluginId: String, event: String, payloadJson: String) {
        val item = loaded[pluginId] ?: return
        try {
            val cx = factory.enterContext()
            try {
                val fn = ScriptableObject.getProperty(item.scope, "__fire")
                if (fn is Function) {
                    fn.call(cx, item.scope, item.scope, arrayOf<Any>(event, payloadJson))
                }
            } finally {
                Context.exit()
            }
        } catch (t: Throwable) {
            runCatching {
                PluginManager.audit(
                    ctx, pluginId, event, "事件处理", "error",
                    t.message ?: t.javaClass.simpleName
                )
            }
        }
    }

    /**
     * 脚本可见的唯一 Java 对象：所有能力调用统一进入 PluginManager.handleCall。
     *
     * 声明为 public（而非 private）是必要的：Rhino 通过反射调用它的方法，
     * 私有嵌套类在 JVM 上会因访问检查而调用失败。
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
        fun shizuku(joined: String): String = call("shizuku", joined.split('\u0001'))
        fun log(text: String): String = call("log", listOf(text))

        private fun call(action: String, args: List<String>): String =
            PluginManager.handleCall(appCtx, host, plugin, action, args)
    }
}
