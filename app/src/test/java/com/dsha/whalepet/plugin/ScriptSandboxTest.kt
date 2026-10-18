package com.dsha.whalepet.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 沙箱的 JVM 回归测试（对应硬规则 7：安全边界必须有自动化用例）。
 *
 * 这里跑的是真实 Rhino（`rhino-runtime` 是纯 Java 依赖），只有 bridge 是假的——
 * 因此 `ClassShutter`、全局清理、指令上限都是**真的在执行**，不是 mock。
 *
 * 覆盖三件一旦重构就可能悄悄洞开的事：
 *  1. 危险全局确实不存在
 *  2. `__bridge.getClass()` 之类反射逃逸拿不到东西
 *  3. 死循环被指令上限中止，且不会毒化后续执行
 */
class ScriptSandboxTest {

    @Test
    fun bridgeIsCallable() {
        // 正向对照：白名单不能严到连 __bridge 自己都调不通
        val bridge = FakeBridge()
        ScriptSandbox.compile(bridge, "say('hi');", "t")
        assertEquals(listOf("say:hi"), bridge.calls)
    }

    /**
     * 回归：能力返回值必须是**普通 JS 字符串**，而不是包裹 Java String 的 NativeJavaObject。
     *
     * 1.1.29 ~ 1.1.47 的 bug：白名单只放行 bridge，Rhino 包装 String 返回值时为
     * `java.lang.String` 建 JavaMembers 被拒 → 副作用已经发生、脚本随即抛错并被
     * `__fire` 的 catch 吞掉。表现是"能力生效了但后续脚本不执行"，日志里毫无痕迹。
     */
    @Test
    fun bridgeReturnValuesArePlainJsStrings() {
        val bridge = FakeBridge()
        ScriptSandbox.compile(
            bridge,
            "var r = say('x'); say(typeof r + '/' + String(r === 'ok'));",
            "t"
        )
        assertEquals(listOf("say:x", "say:string/true"), bridge.calls)
    }

    @Test
    fun bridgeCallAtLoadTimeDoesNotBreak() {
        // 加载阶段（compile 内）调用能力同样不能触发 String 包装被拒
        val bridge = FakeBridge()
        ScriptSandbox.compile(bridge, "say('at-load');", "t")
        assertEquals(listOf("say:at-load"), bridge.calls)
    }

    @Test
    fun dangerousGlobalsAreGone() {
        val bridge = FakeBridge()
        ScriptSandbox.compile(
            bridge,
            "say(typeof Packages + '/' + typeof java + '/' + typeof javax + '/' + typeof getClass);",
            "t"
        )
        assertEquals(listOf("say:undefined/undefined/undefined/undefined"), bridge.calls)
    }

    @Test
    fun getClassEscapeIsBlocked() {
        val bridge = FakeBridge()
        // 无论 ClassShutter 的拒绝是以"JS 可捕获异常"还是"直接抛出"的形式出现，
        // 判定标准都一样：Class 对象不得泄漏到脚本手里
        runCatching {
            ScriptSandbox.compile(
                bridge,
                "try { say('LEAK:' + __bridge.getClass()); } catch (e) { say('blocked'); }",
                "t"
            )
        }
        assertTrue("getClass 结果不得泄漏：${bridge.calls}", bridge.calls.none { it.contains("LEAK") })
    }

    @Test
    fun classLoaderEscapeIsBlocked() {
        val bridge = FakeBridge()
        runCatching {
            ScriptSandbox.compile(
                bridge,
                "try { say('LEAK:' + __bridge.getClass().getClassLoader()); } catch (e) { say('blocked'); }",
                "t"
            )
        }
        assertTrue("ClassLoader 不得泄漏：${bridge.calls}", bridge.calls.none { it.contains("LEAK") })
    }

    @Test
    fun normalHandlerRunsAndReportsNoError() {
        val bridge = FakeBridge()
        val script = ScriptSandbox.compile(bridge, "on('tap', function (c) { say('tapped'); });", "t")
        val err = ScriptSandbox.fire(script, "tap", "{}")
        assertNull("正常执行不应报错", err)
        assertFalse(script.aborted)
        assertEquals(listOf("say:tapped"), bridge.calls)
    }

    @Test
    fun payloadIsPassedToHandler() {
        val bridge = FakeBridge()
        val script = ScriptSandbox.compile(
            bridge,
            "on('balanceDecreased', function (c) { say(String(c.prev - c.value)); });",
            "t"
        )
        ScriptSandbox.fire(script, "balanceDecreased", """{"value":8.5,"prev":10.0}""")
        assertEquals(listOf("say:1.5"), bridge.calls)
    }

    @Test
    fun handlerErrorIsReportedNotSwallowed() {
        // 此前 __fire 就地 catch，插件报错在日志里完全看不到，用户只看到"双击没反应"
        val bridge = FakeBridge()
        val script = ScriptSandbox.compile(
            bridge,
            "on('tap', function (c) { throw new Error('boom'); });",
            "t"
        )
        val err = ScriptSandbox.fire(script, "tap", "{}")
        assertNotNull("处理器异常必须被带出来", err)
        assertTrue("应包含原始信息：$err", err!!.contains("boom"))
    }

    @Test(timeout = 60_000L)
    fun infiniteLoopIsAbortedAndFlagged() {
        val bridge = FakeBridge()
        val script = ScriptSandbox.compile(
            bridge,
            "on('tap', function () { while (true) { } });",
            "t"
        )
        runCatching { ScriptSandbox.fire(script, "tap", "{}") }
        // 中止由观察器就地留痕：不依赖 TimeoutError 是否一路抛到调用方
        assertTrue("死循环必须被指令上限中止", script.aborted)
    }

    @Test(timeout = 120_000L)
    fun abortDoesNotPoisonLaterRuns() {
        val bridge = FakeBridge()
        val script = ScriptSandbox.compile(
            bridge,
            "on('tap', function () { while (true) { } });" +
                "on('doubleTap', function () { say('alive'); });",
            "t"
        )
        runCatching { ScriptSandbox.fire(script, "tap", "{}") }
        assertTrue(script.aborted)

        // 每次执行都新建 Context，指令计数不应跨次累积
        val err = ScriptSandbox.fire(script, "doubleTap", "{}")
        assertNull("中止之后的事件仍应正常执行：$err", err)
        assertFalse("上一次的中止标记不应残留", script.aborted)
        assertEquals(listOf("say:alive"), bridge.calls)
    }
}

/**
 * 假的 bridge：记录脚本调用了哪些能力，不需要 Android `Context`。
 *
 * 必须是**公开类**：Rhino 通过反射调用它的方法，而它的类名同时也是
 * ClassShutter 的唯一白名单项。
 */
class FakeBridge {
    val calls = mutableListOf<String>()

    fun say(text: String): String {
        calls += "say:$text"
        return "ok"
    }

    fun log(text: String): String {
        calls += "log:$text"
        return "ok"
    }
}
