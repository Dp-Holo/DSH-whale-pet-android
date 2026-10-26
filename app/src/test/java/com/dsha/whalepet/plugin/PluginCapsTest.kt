package com.dsha.whalepet.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件能力与敏感判定的测试。
 *
 * "哪些能力算敏感"直接决定用户会不会被弹确认条，属于安全语义，改动必须有测试兜底。
 */
class PluginCapsTest {

    @Test
    fun sensitiveSetIsExactlyTheOutboundCapabilities() {
        // 这四项都会"把东西送出去"（打开网页/应用、执行系统命令、联网），必须每次确认
        assertEquals(
            setOf(PluginCaps.OPEN_URL, PluginCaps.OPEN_APP, PluginCaps.SHIZUKU, PluginCaps.NETWORK),
            PluginCaps.SENSITIVE
        )
    }

    @Test
    fun harmlessCapabilitiesAreNotSensitive() {
        assertFalse(PluginCaps.SAY in PluginCaps.SENSITIVE)
        assertFalse(PluginCaps.NOTIFY in PluginCaps.SENSITIVE)
        assertFalse(PluginCaps.VIBRATE in PluginCaps.SENSITIVE)
    }

    @Test
    fun allCapabilitiesCoverTheSensitiveOnes() {
        assertEquals(7, PluginCaps.ALL.size)
        assertTrue(PluginCaps.ALL.containsAll(PluginCaps.SENSITIVE))
    }

    @Test
    fun everyCapabilityHasReadableLabel() {
        PluginCaps.ALL.forEach { cap ->
            val label = PluginCaps.label(cap)
            assertTrue("能力 $cap 缺少可读标签", label.isNotBlank() && label != cap)
        }
    }

    @Test
    fun actionTypeMapsToExpectedCapability() {
        assertEquals(PluginCaps.SAY, PluginAction(type = "say").capability)
        assertEquals(PluginCaps.NOTIFY, PluginAction(type = "notify").capability)
        assertEquals(PluginCaps.VIBRATE, PluginAction(type = "vibrate").capability)
        assertEquals(PluginCaps.OPEN_URL, PluginAction(type = "openUrl").capability)
        assertEquals(PluginCaps.OPEN_APP, PluginAction(type = "openApp").capability)
        assertEquals(PluginCaps.SHIZUKU, PluginAction(type = "shizuku").capability)
        // 联网两个动作共用 network 能力
        assertEquals(PluginCaps.NETWORK, PluginAction(type = "httpGet").capability)
        assertEquals(PluginCaps.NETWORK, PluginAction(type = "httpPost").capability)
        // 纯记录与未知动作不需要能力
        assertEquals("", PluginAction(type = "log").capability)
        assertEquals("", PluginAction(type = "unknown").capability)
    }

    @Test
    fun actionDescriptionIsNotBlank() {
        PluginCaps.ALL.forEach { cap ->
            val desc = when (cap) {
                PluginCaps.SAY -> PluginAction(type = "say", text = "hi").describe()
                PluginCaps.NOTIFY -> PluginAction(type = "notify", title = "t", text = "m").describe()
                PluginCaps.VIBRATE -> PluginAction(type = "vibrate", ms = 200L).describe()
                PluginCaps.OPEN_URL -> PluginAction(type = "openUrl", url = "https://x.com").describe()
                PluginCaps.OPEN_APP -> PluginAction(type = "openApp", pkg = "com.x").describe()
                PluginCaps.NETWORK -> PluginAction(type = "httpGet", url = "https://x.com/a").describe()
                else -> PluginAction(type = "shizuku", args = listOf("pm", "grant")).describe()
            }
            assertTrue("能力 $cap 的动作描述为空", desc.isNotBlank())
        }
    }

    // ── 动作↔能力映射（唯一来源，漏登记会被这里抓住）─────────────

    @Test
    fun everyCapabilityIsReachableFromSomeAction() {
        val actions = listOf(
            "say", "notify", "vibrate", "openUrl", "openApp", "shizuku", "httpGet", "httpPost"
        )
        assertEquals(
            "PluginCaps.ALL 与 ofAction 的映射不一致：新增能力后忘了登记动作",
            PluginCaps.ALL,
            actions.map { PluginCaps.ofAction(it) }.toSet()
        )
    }

    @Test
    fun unknownOrLogActionNeedsNoCapability() {
        assertEquals("", PluginCaps.ofAction("log"))
        assertEquals("", PluginCaps.ofAction("teleport"))
        assertEquals("", PluginCaps.ofAction(""))
    }

    @Test
    fun pluginActionCapabilityUsesTheSameMapping() {
        // 两处若各写一份 when，容易出现"声明了能力却永远被判未授权"
        listOf(
            "say", "notify", "vibrate", "openUrl", "openApp", "shizuku",
            "httpGet", "httpPost", "log", "unknown"
        )
            .forEach { type ->
                assertEquals(
                    "动作 $type 的能力映射不一致",
                    PluginCaps.ofAction(type),
                    PluginAction(type = type).capability
                )
            }
    }
}
