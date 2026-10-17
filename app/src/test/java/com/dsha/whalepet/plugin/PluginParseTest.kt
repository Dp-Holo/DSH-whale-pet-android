package com.dsha.whalepet.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件 JSON 解析的边界测试。
 *
 * 解析器是插件系统的入口，格式判错会导致"装了但没反应"或"静默失效"，
 * 因此这里覆盖：正常脚本型、规则型、缺字段、坏 JSON、能力自动补齐、无效规则跳过。
 */
class PluginParseTest {

    private val scriptJson = """
        {
          "id": "demo-script",
          "name": "演示脚本插件",
          "version": 2,
          "enabled": true,
          "capabilities": ["say"],
          "script": "on('tap', function (c) { say('hi'); });"
        }
    """.trimIndent()

    private val ruleJson = """
        {
          "id": "demo-rule",
          "name": "演示规则插件",
          "enabled": false,
          "rules": [
            {
              "when": { "event": "doubleTap" },
              "then": [
                { "action": "say", "text": "你好" },
                { "action": "openApp", "pkg": "com.tencent.mm" }
              ],
              "cooldownMs": 5000
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesScriptPlugin() {
        val p = Plugin.parse(scriptJson)
        assertNotNull(p)
        p!!
        assertEquals("demo-script", p.id)
        assertEquals("演示脚本插件", p.name)
        assertTrue(p.enabled)
        assertTrue(p.isScript)
        assertEquals("on('tap', function (c) { say('hi'); });", p.script)
        assertTrue(p.capabilities.contains(PluginCaps.SAY))
    }

    @Test
    fun parsesRulePluginAndInfersCapabilitiesFromActions() {
        val p = Plugin.parse(ruleJson)
        assertNotNull(p)
        p!!
        assertFalse(p.enabled)
        assertFalse(p.isScript)
        assertEquals(1, p.rules.size)
        assertEquals("doubleTap", p.rules[0].event)
        assertEquals(5000L, p.rules[0].cooldownMs)
        assertEquals(2, p.rules[0].actions.size)
        // 声明里没写能力，但动作用到了 say / openApp，应自动补齐
        assertTrue(p.capabilities.contains(PluginCaps.SAY))
        assertTrue(p.capabilities.contains(PluginCaps.OPEN_APP))
    }

    @Test
    fun rejectsPluginWithoutId() {
        assertNull(Plugin.parse("""{ "name": "没有 id" }"""))
        assertNull(Plugin.parse("""{ "id": "   ", "name": "空 id" }"""))
    }

    @Test
    fun rejectsBrokenJson() {
        assertNull(Plugin.parse(""))
        assertNull(Plugin.parse("not json at all"))
        assertNull(Plugin.parse("""{ "id": "x", "script": }"""))
    }

    @Test
    fun skipsRulesWithoutEventOrActions() {
        val json = """
            {
              "id": "demo-mixed",
              "rules": [
                { "when": { "event": "" }, "then": [ { "action": "say", "text": "无事件" } ] },
                { "when": { "event": "tap" }, "then": [] },
                { "when": { "event": "tap" }, "then": [ { "action": "", "text": "无动作" } ] },
                { "when": { "event": "doubleTap" }, "then": [ { "action": "say", "text": "有效" } ] }
              ]
            }
        """.trimIndent()
        val p = Plugin.parse(json)
        assertNotNull(p)
        // 只有最后一条规则是有效的
        assertEquals(1, p!!.rules.size)
        assertEquals("doubleTap", p.rules[0].event)
    }

    @Test
    fun unknownActionTypeDoesNotAddCapability() {
        val json = """
            {
              "id": "demo-unknown",
              "rules": [
                { "when": { "event": "tap" }, "then": [ { "action": "teleport" } ] }
              ]
            }
        """.trimIndent()
        val p = Plugin.parse(json)
        assertNotNull(p)
        assertTrue(p!!.capabilities.isEmpty())
    }

    @Test
    fun pluginWithoutScriptIsNotScriptType() {
        val p = Plugin.parse("""{ "id": "no-script", "name": "无脚本" }""")
        assertNotNull(p)
        assertFalse(p!!.isScript)
        assertTrue(p.rules.isEmpty())
    }

    // ── id 字符集（安全边界：id 会被拼成文件名落盘）────────────────

    @Test
    fun acceptsReasonableIds() {
        listOf("balance-guard", "a", "MyPlugin", "a.b_c-1", "0abc", "a".repeat(64)).forEach {
            assertTrue("应接受 id=$it", Plugin.isValidId(it))
        }
    }

    /**
     * 这些 id 会把 `<id>.json` 写到插件目录之外，或覆盖别的插件文件。
     * 插件 JSON 是用户从外部粘贴的不可信输入，必须在这里挡住。
     */
    @Test
    fun rejectsIdsThatCouldEscapePluginDir() {
        listOf("../whale_lines", "..", ".", "a/b", "/abs", "a\\b").forEach {
            assertFalse("应拒绝 id=$it", Plugin.isValidId(it))
            // 解析层也要一并拒绝（不只是 isValidId 的工具函数层面）
            assertNull("应拒绝解析 id=$it", Plugin.parse("""{ "id": "$it", "name": "x" }"""))
        }
    }

    @Test
    fun rejectsIdsWithControlOrIllegalChars() {
        // 换行会伪造审计日志行；其余字符不在约定字符集内
        listOf("a\nb", "a\rb", "a|b", "含中文", "a b", "-lead", "_lead", ".lead", "").forEach {
            assertFalse("应拒绝 id=${it.replace("\n", "\\n").replace("\r", "\\r")}", Plugin.isValidId(it))
        }
    }

    @Test
    fun rejectsOverlongId() {
        assertTrue(Plugin.isValidId("a".repeat(64)))
        assertFalse(Plugin.isValidId("a".repeat(65)))
    }
}
