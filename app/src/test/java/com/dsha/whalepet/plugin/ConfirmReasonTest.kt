package com.dsha.whalepet.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 确认结束原因的测试。
 *
 * 审计是追责依据：超时、被后来的确认顶掉、服务停止这三种情况都曾被写成"用户拒绝"，
 * 而用户根本没看到确认条。这里保证每种原因都有**互不相同**的说明文案。
 */
class ConfirmReasonTest {

    @Test
    fun everyReasonHasNonBlankText() {
        ConfirmReason.values().forEach {
            assertTrue("原因 $it 缺少审计文案", it.auditText().isNotBlank())
        }
    }

    @Test
    fun textsAreDistinctSoAuditIsUnambiguous() {
        val texts = ConfirmReason.values().map { it.auditText() }
        assertEquals("不同原因不能共用同一句文案", texts.size, texts.toSet().size)
    }

    @Test
    fun nonUserReasonsAreNotBlamedOnTheUser() {
        listOf(
            ConfirmReason.TIMEOUT,
            ConfirmReason.SUPERSEDED,
            ConfirmReason.SERVICE_STOPPED,
            ConfirmReason.UNAVAILABLE
        ).forEach {
            assertNotEquals(
                "「$it」不应写成用户拒绝",
                ConfirmReason.USER_DENIED.auditText(),
                it.auditText()
            )
        }
    }

    @Test
    fun unavailableCarriesTheConcreteFailureReason() {
        // 1.1.51 实机只看到"悬浮窗不可用，无法确认"，无法判断是权限、布局还是加窗失败
        val detail = ConfirmReason.UNAVAILABLE.detailWith("加窗失败：BadTokenException: x")
        assertTrue("应保留原说明", detail.contains(ConfirmReason.UNAVAILABLE.auditText()))
        assertTrue("应附带具体原因", detail.contains("BadTokenException"))
    }

    @Test
    fun otherReasonsIgnoreTheFailureDetail() {
        // 用户拒绝/超时与"弹不出来"无关，不该被拼上异常文本
        assertEquals(
            ConfirmReason.USER_DENIED.auditText(),
            ConfirmReason.USER_DENIED.detailWith("不该出现的异常")
        )
        assertEquals(
            ConfirmReason.TIMEOUT.auditText(),
            ConfirmReason.TIMEOUT.detailWith("不该出现的异常")
        )
    }

    @Test
    fun unavailableWithoutFailureFallsBackToPlainText() {
        assertEquals(ConfirmReason.UNAVAILABLE.auditText(), ConfirmReason.UNAVAILABLE.detailWith(null))
        assertEquals(ConfirmReason.UNAVAILABLE.auditText(), ConfirmReason.UNAVAILABLE.detailWith("  "))
    }

    @Test
    fun onlyAllowedRepresentsConsent() {
        // 除 ALLOWED 外都表示"没执行"，审计结果列应为 denied
        assertEquals(ConfirmReason.ALLOWED, ConfirmReason.valueOf("ALLOWED"))
        assertTrue(ConfirmReason.valueOf("ALLOWED").auditText().contains("允许"))
    }
}
