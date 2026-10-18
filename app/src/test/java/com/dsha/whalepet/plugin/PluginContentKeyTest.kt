package com.dsha.whalepet.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脚本内容指纹的测试。
 *
 * 用途：判断"已加载的脚本是否还是当前这份"。用 32 位 `hashCode()` 时碰撞会导致
 * 用户改了脚本却不重载（表现为"改了没生效"），所以换成「长度 + SHA-256 前缀」。
 */
class PluginContentKeyTest {

    @Test
    fun sameContentProducesSameKey() {
        assertEquals(PluginContentKey.of("say('hi');"), PluginContentKey.of("say('hi');"))
    }

    @Test
    fun differentContentProducesDifferentKey() {
        assertNotEquals(PluginContentKey.of("say('a');"), PluginContentKey.of("say('b');"))
        // 同长度且字符相同、顺序不同——旧的 hashCode 实现下也容易撞
        assertNotEquals(PluginContentKey.of("ab"), PluginContentKey.of("ba"))
    }

    @Test
    fun keyCarriesLength() {
        assertTrue("长度要进指纹，进一步降低碰撞", PluginContentKey.of("abc").startsWith("3:"))
        assertEquals("3:".length + 16, PluginContentKey.of("abc").length)
    }

    @Test
    fun emptyScriptIsHandled() {
        assertEquals("0:", PluginContentKey.of(""))
    }

    @Test
    fun unicodeContentIsStable() {
        // 中文按 UTF-8 编码参与哈希，同一内容两次必须一致
        assertEquals(PluginContentKey.of("say('你好🐳');"), PluginContentKey.of("say('你好🐳');"))
        assertNotEquals(PluginContentKey.of("say('你好');"), PluginContentKey.of("say('你好 ');"))
    }
}
