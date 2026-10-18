package com.dsha.whalepet.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则型动作节流的测试。
 *
 * App 侧的插件通知渠道是 `IMPORTANCE_DEFAULT`（有声），而 `cooldownMs` 默认为 0——
 * 没有下限的话，把 `notify` 绑到 `tap` 上连点就能持续轰炸通知栏。
 */
class RuleThrottleTest {

    @Test
    fun cooldownHasOneSecondFloor() {
        assertEquals(1_000L, RuleThrottle.effectiveCooldown(0L))
        assertEquals(1_000L, RuleThrottle.effectiveCooldown(999L))
        assertEquals(1_000L, RuleThrottle.effectiveCooldown(-5L))
    }

    @Test
    fun userCooldownAboveFloorIsRespected() {
        assertEquals(5_000L, RuleThrottle.effectiveCooldown(5_000L))
    }

    @Test
    fun firstFireIsAlwaysAllowed() {
        assertTrue(RuleThrottle.shouldFire(null, 1_000L, 0L))
        assertTrue(RuleThrottle.shouldFire(null, 1_000L, 60_000L))
    }

    @Test
    fun burstWithinOneSecondIsSuppressed() {
        // 连点场景：300ms 内第二次点击必须被压住
        assertFalse(RuleThrottle.shouldFire(1_000L, 1_300L, 0L))
        assertFalse(RuleThrottle.shouldFire(1_000L, 1_999L, 0L))
    }

    @Test
    fun exactlyAtFloorIsAllowed() {
        assertTrue(RuleThrottle.shouldFire(1_000L, 2_000L, 0L))
    }

    @Test
    fun userCooldownWinsWhenLarger() {
        assertFalse(RuleThrottle.shouldFire(0L, 4_999L, 5_000L))
        assertTrue(RuleThrottle.shouldFire(0L, 5_000L, 5_000L))
    }

    @Test
    fun clockRollbackDoesNotBlockForever() {
        // 系统时间被回调时，若仍按差值判断，这条规则会永久卡死
        assertTrue(RuleThrottle.shouldFire(10_000L, 5_000L, 5_000L))
    }
}
