package com.dsha.whalepet.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 壁纸运动状态的纯 JVM 测试：不依赖 Android，CI 的 ./gradlew test 会先跑它。
 * 重点覆盖"越界"和"气泡过期"这两个最容易在真机上表现为卡死/残留的分支。
 */
class WallpaperPetStateTest {

    @Test
    fun staysInsideSurfaceAfterManySteps() {
        val state = WallpaperPetState(speedPx = 2f, petSizePx = 100f)
        state.reset(1000, 2000)
        var now = 0L
        repeat(50_000) {
            now += 16
            state.step(1000, 2000, 1f, now)
        }
        val half = 50f
        assertTrue("x=${state.x}", state.x in half..(1000f - half))
        assertTrue("y=${state.y}", state.y in half..(2000f - half))
    }

    @Test
    fun clampToKeepsPositionAfterSurfaceShrink() {
        val state = WallpaperPetState(speedPx = 1f, petSizePx = 100f)
        state.reset(1440, 3200)
        // 直接推到右下角附近（多次 step 保证位置非中心）
        var now = 0L
        repeat(2000) {
            now += 16
            state.step(1440, 3200, 1f, now)
        }
        state.clampTo(720, 1000)
        assertTrue(state.x in 50f..670f)
        assertTrue(state.y in 50f..950f)
    }

    @Test
    fun bubbleExpires() {
        val state = WallpaperPetState(speedPx = 1f, petSizePx = 100f)
        state.reset(1000, 1000)
        state.say("你好", nowMs = 0L, durationMs = 1000L)
        assertEquals("你好", state.bubble)
        state.step(1000, 1000, 1f, 500L)
        assertEquals("你好", state.bubble)
        state.step(1000, 1000, 1f, 1001L)
        assertNull(state.bubble)
    }

    @Test
    fun facingOnlyHasTwoValidValues() {
        val state = WallpaperPetState(speedPx = 2f, petSizePx = 100f)
        state.reset(1000, 1000)
        var now = 0L
        repeat(2000) {
            now += 16
            state.step(1000, 1000, 1f, now)
            assertTrue(state.facing == 1 || state.facing == -1)
        }
    }
}
