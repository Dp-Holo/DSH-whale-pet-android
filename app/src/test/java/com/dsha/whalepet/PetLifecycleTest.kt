package com.dsha.whalepet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生命周期状态机的边界测试。
 *
 * 这台状态机的价值在于"非法迁移被拒绝且可观测"——因此测试重点不是正常路径，
 * 而是：重复启动、未就绪就息屏、停止后再请求恢复、启动失败回退等非法顺序。
 */
class PetLifecycleTest {

    private fun running(): PetLifecycle {
        val l = PetLifecycle()
        assertTrue(l.start())
        assertTrue(l.ready())
        return l
    }

    @Test
    fun `正常顺序：启动-就绪-息屏-亮屏-停止`() {
        val l = PetLifecycle()
        assertEquals(PetLifecycle.State.IDLE, l.state)

        assertTrue(l.start("test"))
        assertEquals(PetLifecycle.State.STARTING, l.state)

        assertTrue(l.ready("test"))
        assertEquals(PetLifecycle.State.RUNNING, l.state)
        assertTrue(l.isActive)
        assertTrue(l.isAlive)

        assertTrue(l.screenOff("test"))
        assertEquals(PetLifecycle.State.PAUSED, l.state)
        assertFalse(l.isActive)
        assertTrue(l.isAlive)

        assertTrue(l.screenOn("test"))
        assertEquals(PetLifecycle.State.RUNNING, l.state)

        assertTrue(l.stop("test"))
        assertEquals(PetLifecycle.State.STOPPING, l.state)
        assertTrue(l.stopped("test"))
        assertEquals(PetLifecycle.State.IDLE, l.state)
    }

    @Test
    fun `重复启动被拒绝`() {
        val l = running()
        assertFalse(l.start())
        assertEquals(PetLifecycle.State.RUNNING, l.state)
        assertTrue(l.lastRejected.startsWith("RUNNING->STARTING"))
    }

    @Test
    fun `未就绪时息屏被拒绝`() {
        val l = PetLifecycle()
        assertTrue(l.start())
        // STARTING 阶段屏幕广播可能先到，但此时还没开始渲染，不应进入 PAUSED
        assertFalse(l.screenOff())
        assertEquals(PetLifecycle.State.STARTING, l.state)
    }

    @Test
    fun `停止后不能再亮屏或就绪`() {
        val l = running()
        assertTrue(l.stop())
        assertFalse(l.screenOn())
        assertFalse(l.ready())
        assertEquals(PetLifecycle.State.STOPPING, l.state)
    }

    @Test
    fun `启动失败回退到 IDLE`() {
        val l = PetLifecycle()
        assertTrue(l.start())
        assertTrue(l.abort("permissionDenied"))
        assertEquals(PetLifecycle.State.IDLE, l.state)
        assertEquals("STARTING->IDLE(permissionDenied)", l.lastTransition)
    }

    @Test
    fun `重复停止被拒绝（幂等）`() {
        val l = running()
        assertTrue(l.stop())
        assertFalse(l.stop())
        assertEquals(PetLifecycle.State.STOPPING, l.state)
    }

    @Test
    fun `快照 running 覆盖启动中-运行中-暂停中`() {
        val l = PetLifecycle()
        assertFalse(PetStatus(state = l.state).running)

        l.start()
        assertTrue(PetStatus(state = l.state).running)
        l.ready()
        assertTrue(PetStatus(state = l.state).running)
        l.screenOff()
        assertTrue(PetStatus(state = l.state).running)
        l.stop()
        assertFalse(PetStatus(state = l.state).running)
    }
}
