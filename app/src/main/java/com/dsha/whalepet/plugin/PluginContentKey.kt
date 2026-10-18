package com.dsha.whalepet.plugin

import java.security.MessageDigest

/**
 * 脚本内容指纹：`长度:SHA-256 前 16 位十六进制`。
 *
 * 为什么不用 `String.hashCode()`：它是 32 位哈希，碰撞时用户改了脚本却**不触发重载**
 * （表现为"改了没生效，重启桌宠才好"）。加上长度并把哈希换成 SHA-256 后，
 * 碰撞概率可以忽略，而脚本只有几百字节，计算成本可忽略。
 *
 * 指纹在 [Plugin.parse] 时算一次并随模型缓存，不会每次事件都重算。
 */
object PluginContentKey {

    /** 取 SHA-256 十六进制的前多少位——128 位足够，且日志/调试时更短。 */
    private const val HEX_PREFIX = 16

    fun of(script: String): String {
        if (script.isEmpty()) return "0:"
        val digest = MessageDigest.getInstance("SHA-256").digest(script.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "${script.length}:${hex.take(HEX_PREFIX)}"
    }
}
