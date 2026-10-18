package com.dsha.whalepet.plugin

import com.dsha.whalepet.common.ShizukuOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shizuku 结果归类的测试。
 *
 * 这条链路的安全含义：白名单拒绝**必须**在审计里留下 `denied`。
 * 此前结果写死成 `ok`，等于把安全拦截记成"执行成功"，出事时日志完全看不出拦截发生过。
 */
class ShizukuResultTest {

    @Test
    fun whitelistRejectionIsDenied() {
        val r = ShizukuResult.classify("拒绝执行：命令不在白名单内")
        assertEquals(ShizukuResult.Outcome.DENIED, r.outcome)
        assertEquals("denied", r.auditResult)
    }

    @Test
    fun bareWhitelistRejectionIsAlsoDenied() {
        val r = ShizukuResult.classify("命令不在白名单内")
        assertEquals(ShizukuResult.Outcome.DENIED, r.outcome)
    }

    @Test
    fun channelUnavailableOrExceptionIsError() {
        listOf(
            "Shizuku 不可用",
            "无法绑定 UserService（Shizuku 未响应）",
            "调用失败：RemoteException",
            "执行异常：SecurityException"
        ).forEach {
            val r = ShizukuResult.classify(it)
            assertEquals("应判为 error：$it", ShizukuResult.Outcome.ERROR, r.outcome)
            assertEquals("error", r.auditResult)
        }
    }

    @Test
    fun successfulCommandOutputIsOk() {
        listOf(
            "exit=0 | out:  | err: ",
            "exit=0",
            "exit=1 | out: Error: not allowed | err: "
        ).forEach {
            val r = ShizukuResult.classify(it)
            assertEquals("应判为 ok：$it", ShizukuResult.Outcome.OK, r.outcome)
            assertEquals("ok", r.auditResult)
        }
    }

    @Test
    fun blankMessageIsErrorNotEmptyOk() {
        // 空串说明没拿到任何结果，不能当作成功处理
        assertEquals(ShizukuResult.Outcome.ERROR, ShizukuResult.classify("").outcome)
        assertEquals(ShizukuResult.Outcome.ERROR, ShizukuResult.classify("   ").outcome)
    }

    @Test
    fun originalMessageIsPreservedForAudit() {
        val r = ShizukuResult.classify("  拒绝执行：命令不在白名单内  ")
        assertEquals("拒绝执行：命令不在白名单内", r.message)
        assertTrue("原文要保留，便于排查", r.message.isNotBlank())
    }

    // ── 结构化入口（App 侧 1.1.47 起提供，宿主不再自己猜文案）────────

    @Test
    fun structuredSuccessMapsToOk() {
        val r = ShizukuResult.from(ShizukuOutcome(success = true, denied = false, message = "exit=0"))
        assertEquals(ShizukuResult.Outcome.OK, r.outcome)
        assertEquals("ok", r.auditResult)
    }

    @Test
    fun structuredDeniedMapsToDenied() {
        val r = ShizukuResult.from(
            ShizukuOutcome(success = false, denied = true, message = "拒绝执行：命令不在白名单内")
        )
        assertEquals(ShizukuResult.Outcome.DENIED, r.outcome)
        assertEquals("denied", r.auditResult)
    }

    @Test
    fun structuredFailureMapsToError() {
        val r = ShizukuResult.from(
            ShizukuOutcome(success = false, denied = false, message = "执行异常：SecurityException")
        )
        assertEquals(ShizukuResult.Outcome.ERROR, r.outcome)
        assertEquals("error", r.auditResult)
    }

    @Test
    fun structuredPathKeepsMessageVerbatim() {
        // 归类交给 App 侧，宿主只负责把原文带进审计
        assertEquals("原始文本", ShizukuResult.from(ShizukuOutcome(true, false, "原始文本")).message)
    }
}
