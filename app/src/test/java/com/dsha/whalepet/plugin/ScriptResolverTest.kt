package com.dsha.whalepet.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脚本来源判定的测试（对应任务单「六、测试要求」：优先级与大小上限）。
 *
 * 优先级是行为契约：**已绑定的文件 > 内联 script > 无**。
 * 文件失效时要有可读原因，而不是让插件静默不工作。
 */
class ScriptResolverTest {

    private val uri = "content://com.android.providers.downloads.documents/document/123"

    @Test
    fun boundFileWinsOverInlineScript() {
        val s = ScriptResolver.sourceOf(uri, "say('file');", "say('inline');")
        assertEquals(ScriptSource.File("say('file');"), s)
    }

    @Test
    fun inlineScriptUsedWhenNoFileBound() {
        val s = ScriptResolver.sourceOf(null, null, "say('inline');")
        assertEquals(ScriptSource.Inline("say('inline');"), s)
    }

    @Test
    fun unreadableFileFallsBackToInlineScript() {
        // 文件被删 / URI 失效 / 超限：不能让整个插件静默失效，有内联脚本就回退
        val s = ScriptResolver.sourceOf(uri, null, "say('inline');")
        assertEquals(ScriptSource.Inline("say('inline');"), s)
    }

    @Test
    fun unreadableFileWithoutInlineExplainsWhy() {
        val s = ScriptResolver.sourceOf(uri, null, "")
        assertTrue(s is ScriptSource.Missing)
        assertTrue(
            "原因要提示用户重新选择：${(s as ScriptSource.Missing).reason}",
            s.reason.contains("重新选择")
        )
    }

    @Test
    fun nothingAvailableSaysNotSelected() {
        val s = ScriptResolver.sourceOf(null, null, "")
        assertTrue(s is ScriptSource.Missing)
        assertTrue(
            "原因要说清是「没选」而不是「读失败」：${(s as ScriptSource.Missing).reason}",
            s.reason.contains("未选择")
        )
    }

    @Test
    fun emptyFileContentIsStillAFile() {
        // 空脚本文件也是"有效绑定"，不该被当成读取失败
        val s = ScriptResolver.sourceOf(uri, "", "")
        assertEquals(ScriptSource.File(""), s)
    }

    @Test
    fun sizeLimitIs256Kb() {
        assertEquals(256 * 1024, ScriptResolver.MAX_SCRIPT_BYTES)
        assertFalse("正好 256KB 允许", ScriptResolver.isOversize(256 * 1024))
        assertTrue("超出 1 字节就拒绝", ScriptResolver.isOversize(256 * 1024 + 1))
    }

    @Test
    fun sizeLimitTextIsHumanReadable() {
        assertEquals("256KB", ScriptResolver.sizeLimitText())
    }
}
