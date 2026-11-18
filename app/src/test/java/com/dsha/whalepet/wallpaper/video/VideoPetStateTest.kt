package com.dsha.whalepet.wallpaper.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 视频壁纸表现状态的纯 JVM 测试：
 * 音频脉动是否收敛、摇一摇是否衰减、点击弹跳是否回落。
 */
class VideoPetStateTest {

    @Test
    fun audioPulseRaisesScaleAndConverges() {
        val state = VideoPetState()
        state.setAudioLevel(1f)
        repeat(120) { state.step(1f) }
        // 2.5% 的脉动上限附近，既不能没有反馈，也不能夸张
        assertTrue("scale=${state.scale}", state.scale in 1.015f..1.03f)
        assertEquals(0f, state.rotation, 0.0001f)
    }

    @Test
    fun audioPulseFallsBackAfterSilence() {
        val state = VideoPetState()
        state.setAudioLevel(1f)
        repeat(60) { state.step(1f) }
        state.setAudioLevel(0f)
        repeat(240) { state.step(1f) }
        assertTrue("scale=${state.scale}", state.scale < 1.005f)
    }

    @Test
    fun shakeWobblesThenDecays() {
        val state = VideoPetState()
        state.onShake()
        state.step(1f)
        assertTrue("rotation=${state.rotation}", abs(state.rotation) > 0.001f)

        repeat(600) { state.step(1f) }
        assertTrue("rotation=${state.rotation}", abs(state.rotation) < 0.001f)
    }

    @Test
    fun tapPopDecaysBack() {
        val state = VideoPetState()
        state.onTap()
        state.step(1f)
        assertTrue("scale=${state.scale}", state.scale > 1.02f)

        repeat(200) { state.step(1f) }
        assertTrue("scale=${state.scale}", state.scale < 1.005f)
    }
}
