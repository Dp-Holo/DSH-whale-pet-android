package com.dsha.whalepet.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 授权集合变更策略的测试。
 *
 * 这是安全边界："取消勾选到底有没有真的收回权限"必须由用例钉死，
 * 否则 UI 与安全语义会悄悄背离。
 */
class GrantPolicyTest {

    @Test
    fun saveReplacesInsteadOfAppending() {
        // 用户本次只勾了 openApp → 结果就是只有 openApp，此前授权的 say 必须消失
        assertEquals(setOf(PluginCaps.OPEN_APP), GrantPolicy.replaceWith(listOf(PluginCaps.OPEN_APP)))
    }

    @Test
    fun emptySelectionMeansNoWrite() {
        // 空选择不代表"撤销全部"（那有独立入口），而是"这次没选"，保持现状
        assertNull(GrantPolicy.replaceWith(emptyList()))
    }

    @Test
    fun duplicatesAreCollapsed() {
        assertEquals(setOf(PluginCaps.SAY), GrantPolicy.replaceWith(listOf(PluginCaps.SAY, PluginCaps.SAY)))
    }

    @Test
    fun pruneAlwaysKeepsOnlyStillGrantedCapabilities() {
        val pruned = GrantPolicy.pruneAlways(
            always = setOf(PluginCaps.SAY, PluginCaps.OPEN_APP),
            granted = setOf(PluginCaps.SAY)
        )
        assertEquals(setOf(PluginCaps.SAY), pruned)
    }

    @Test
    fun pruneAlwaysDropsEverythingWhenAllRevoked() {
        // 撤销 openApp 后，"始终允许 openApp"必须一起消失，
        // 否则用户重新勾上时会被上一轮的免询问决定直接放过
        assertTrue(GrantPolicy.pruneAlways(setOf(PluginCaps.OPEN_APP), emptySet()).isEmpty())
    }

    @Test
    fun pruneAlwaysIsNoOpWhenNothingRevoked() {
        val always = setOf(PluginCaps.OPEN_APP)
        assertEquals(always, GrantPolicy.pruneAlways(always, always))
    }
}
