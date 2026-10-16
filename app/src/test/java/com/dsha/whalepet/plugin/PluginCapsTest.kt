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
    fun sensitiveSetIsExactlyOpenUrlOpenAppAndShizuku() {
        assertEquals(
            setOf(PluginCaps.OPEN_URL, PluginCaps.OPEN_APP, PluginCaps.SHIZUKU),
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
        assertEquals(6, PluginCaps.ALL.size)
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
                else -> PluginAction(type = "shizuku", args = listOf("pm", "grant")).describe()
            }
            assertTrue("能力 $cap 的动作描述为空", desc.isNotBlank())
        }
    }
}
