package com.dsha.whalepet.plugin

import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * Rhino 沙箱：建作用域、挂类访问白名单、执行脚本。
 *
 * **本文件刻意不依赖 Android**（不 import `android.*`、不碰 `PluginManager`），
 * 因此可以在 JVM 单测里直接跑——沙箱与指令上限是安全边界，
 * 只靠真机探针验证等于没有回归保护（重构一次就可能洞开而 CI 全绿）。
 *
 * 安全设计：
 *  1. 脚本全局作用域只注入调用方给的那一个 Java 对象（`__bridge`），
 *     它把每次能力调用转交宿主统一校验。
 *  2. 删除 `Packages`/`java`/`javax` 等危险全局；
 *     再用 [ClassShutter] 把可访问的 Java 类限制为 `__bridge` 所属的那一个类，
 *     堵死 `__bridge.getClass()` → ClassLoader → Runtime 的反射逃逸
 *     （只删全局变量拦不住这条：删全局不影响实例方法）。
 *  3. 指令数观察器 + 单次上限，死循环会被 [TimeoutError] 中止。
 *     该错误继承 `java.lang.Error`：Rhino 只对 Error 走 `EX_NO_JS_STATE`，
 *     脚本里的 `try/catch` 拦不住，否则死循环会照常卡住执行线程。
 */
object ScriptSandbox {

    /** 单次执行的指令上限，超过即中止本次执行（防死循环）。 */
    const val MAX_INSTRUCTIONS_PER_RUN = 5_000_000

    /** 超时被中止时写进审计的固定说明。 */
    const val ABORT_DETAIL = "执行被中止：超出指令上限（疑似死循环）"

    /** 脚本超时错误（必须是 [java.lang.Error]，原因见类注释第 3 条）。 */
    class TimeoutError : java.lang.Error("脚本执行超过指令上限（疑似死循环），已中止")

    /**
     * 一次执行的共享状态。
     *
     * 观察器在这里留痕，调用方据此记账——**不依赖 "Error 一定会抛到调用方"**：
     * Rhino 内部对 Error 的分派（`Interpreter` 的 `EX_NO_JS_STATE`）是它自己的实现细节，
     * 把审计建立在这个假设上，一旦它变了日志就会静默缺失。
     */
    internal class RunState {
        @Volatile
        var aborted: Boolean = false
    }

    /** 编译好的脚本：作用域 + 它自己的沙箱工厂（每个插件独立白名单）。 */
    class Script internal constructor(
        internal val factory: ContextFactory,
        internal val scope: Scriptable,
        // 必须 internal 而非 private：Kotlin 的嵌套类私有成员对外层 object 也不可见，
        // 而 [compile] / [fire] 要读写它
        internal val state: RunState
    ) {
        /** 最近一次 [compile] / [fire] 是否因超出指令上限被中止。 */
        val aborted: Boolean get() = state.aborted
    }

    private val DANGEROUS_GLOBALS = arrayOf(
        "Packages", "java", "javax", "org", "com", "net", "edu",
        "JavaAdapter", "getClass", "importClass", "importPackage",
        "loadClass", "JavaImporter", "Continuation", "XML", "XMLList"
    )

    /**
     * 除 bridge 之外**必须**放行的 JDK 类型。
     *
     * `java.lang.String` 是必需的：bridge 的每个能力都返回 String，Rhino 在包装返回值时
     * 会为 `java.lang.String` 建 JavaMembers。只放行 bridge 一个类，就会在
     * **副作用已经发生之后**抛
     * `Access to Java class "java.lang.String" is prohibited`——
     * 表现为"能力调用了、桌面反应了，但脚本随即被中断"，而且错误被脚本层的
     * `try/catch` 吞掉、日志里什么都看不到（1.1.29 ~ 1.1.47 一直踩这个坑）。
     *
     * 放行 String **不构成逃逸**：反射逃逸的必经节点是 `java.lang.Class` / `ClassLoader`，
     * 它们仍不在白名单里（`String.getClass()` 会拿到 Class → 被拦）。
     */
    private val ALLOWED_JDK_TYPES = setOf("java.lang.String")

    /**
     * 宿主胶水：脚本可见的唯一入口函数集合。
     *
     * `__fire` 会把每个处理函数的异常**收集起来返回**，而不是就地吞掉——
     * 吞掉的话"插件报错但日志里什么都没有"，用户只看到"双击没反应"。
     */
    private val GLUE_JS = """
        var __handlers = {};
        function on(event, handler) {
          if (typeof handler === 'function') {
            (__handlers[event] = __handlers[event] || []).push(handler);
          }
        }
        function say(t) { return String(__bridge.say(String(t))); }
        function notify(t, m) { return String(__bridge.notify(String(t), String(m == null ? '' : m))); }
        function vibrate(ms) { return String(__bridge.vibrate(String(ms))); }
        function openUrl(u) { return String(__bridge.openUrl(String(u))); }
        function openApp(p) { return String(__bridge.openApp(String(p))); }
        function httpGet(u) { return String(__bridge.httpGet(String(u))); }
        function httpPost(u, b) { return String(__bridge.httpPost(String(u), String(b == null ? '' : b))); }
        function shizuku() {
          var a = [];
          for (var i = 0; i < arguments.length; i++) { a.push(String(arguments[i])); }
          return String(__bridge.shizuku(a.join('\u0001')));
        }
        function log(t) { return String(__bridge.log(String(t))); }
        function __fire(event, payloadJson) {
          var list = __handlers[event] || [];
          var data = {};
          try { data = JSON.parse(payloadJson); } catch (e) { data = {}; }
          var errors = [];
          for (var i = 0; i < list.length; i++) {
            try { list[i](data); } catch (e) { errors.push(String(e)); }
          }
          return errors.join(' | ');
        }
    """.trimIndent()

    /**
     * 建作用域并求值脚本。
     *
     * @param bridge 注入给脚本的唯一 Java 对象；它的类名会成为 ClassShutter 的唯一白名单项
     * @throws TimeoutError 脚本在加载阶段就超出指令上限时
     */
    fun compile(bridge: Any, script: String, sourceName: String): Script {
        val state = RunState()
        val factory = newFactory(bridge.javaClass.name, state)
        val cx = factory.enterContext()
        try {
            state.aborted = false
            val scope = cx.initStandardObjects()
            DANGEROUS_GLOBALS.forEach { name -> runCatching { scope.delete(name) } }
            ScriptableObject.putProperty(scope, "__bridge", Context.javaToJS(bridge, scope))
            cx.evaluateString(scope, GLUE_JS, "whale-glue", 1, null)
            cx.evaluateString(scope, script, sourceName, 1, null)
            return Script(factory, scope, state)
        } finally {
            Context.exit()
        }
    }

    /**
     * 触发事件。
     *
     * @return 处理器抛出的异常摘要（正常为 null）；多个处理器出错时以 ` | ` 连接
     */
    fun fire(s: Script, event: String, payloadJson: String): String? {
        s.state.aborted = false
        val cx = s.factory.enterContext()
        try {
            val fn = ScriptableObject.getProperty(s.scope, "__fire")
            if (fn !is Function) return null
            val result = fn.call(cx, s.scope, s.scope, arrayOf<Any>(event, payloadJson))
            return (result as? String)?.takeIf { it.isNotBlank() }
        } finally {
            Context.exit()
        }
    }

    /**
     * 每个脚本一个工厂：白名单绑定到它自己的 bridge 类（外加 [ALLOWED_JDK_TYPES]），互不影响。
     *
     * 用 `enterContext()`/`Context.exit()` 成对包裹，每次执行都会新建 Context，
     * 因此指令计数不会跨次累积（否则一次超时会把后续执行全部毒化）。
     */
    private fun newFactory(bridgeClass: String, state: RunState): ContextFactory {
        val allowed: Set<String> = ALLOWED_JDK_TYPES + bridgeClass
        return object : ContextFactory() {
            override fun makeContext(): Context {
                val cx = super.makeContext()
                cx.optimizationLevel = -1
                cx.setClassShutter(ClassShutter { className -> allowed.contains(className) })
                cx.setInstructionObserverThreshold(MAX_INSTRUCTIONS_PER_RUN)
                return cx
            }

            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                state.aborted = true
                throw TimeoutError()
            }
        }
    }
}
