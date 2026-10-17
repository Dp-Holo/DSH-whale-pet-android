package com.dsha.whalepet.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审计日志格式的测试。
 *
 * 审计是安全追责依据，而 `pluginId`、动作描述、`log()` 正文都来自**不可信的插件 JSON**。
 * 所以这里重点验证两件事：
 *  1. 一次调用只产生**一行**（换行被转义，插件无法伪造出额外记录）
 *  2. 时间戳格式与历史保持一致（外部按固定格式解析，改了会断）
 */
class AuditFormatTest {

    @Test
    fun singleCallProducesSingleLineEvenWithNewlinesInInput() {
        val line = AuditFormat.line(
            pluginId = "a\nb",
            event = "tap",
            action = "记录：x\r\ny",
            result = "ok"
        )
        assertFalse("审计行不得含裸换行：插件会借此伪造额外记录", line.contains('\n'))
        assertFalse("审计行不得含裸回车", line.contains('\r'))
    }

    @Test
    fun newlinesAreEscapedRatherThanDropped() {
        // 保留可见痕迹：既不能多出一行，也不该把内容悄悄吃掉（否则伪造行为不可追）
        val line = AuditFormat.line("a\nb", "tap", "x\r\ny", "ok")
        assertTrue(line.contains("a\\nb"))
        assertTrue(line.contains("x\\r\\ny"))
    }

    @Test
    fun fieldsKeepOrderAndDetailIsOptional() {
        val withoutDetail = AuditFormat.line("p", "tap", "act", "ok")
        assertEquals("时间|id|事件|动作|结果 共 5 段", 5, withoutDetail.split(" | ").size)
        assertTrue(withoutDetail.endsWith("| ok"))

        val withDetail = AuditFormat.line("p", "tap", "act", "ok", "detail")
        assertEquals(6, withDetail.split(" | ").size)
        assertTrue(withDetail.endsWith("| ok | detail"))

        // 空白 detail 不应产生多余的尾部分段
        assertEquals(withoutDetail.length, AuditFormat.line("p", "tap", "act", "ok", "  ").length)
    }

    @Test
    fun timestampMatchesHistoricalFormat() {
        // 形如 10-12 09:03:11
        assertTrue(
            "时间戳格式变了会破坏外部对日志的解析",
            Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}$""").matches(AuditFormat.now())
        )
    }
}
