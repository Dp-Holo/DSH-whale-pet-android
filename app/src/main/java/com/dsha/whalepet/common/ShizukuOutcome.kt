package com.dsha.whalepet.common

/**
 * Shizuku 命令执行结果的结构化表示（**跨边界类型**，放在 common/）。
 *
 * 为什么需要：跨边界接口 `PluginAppBridge.shizuku()` 返回的是一段人可读文本，
 * 插件宿主只能靠"文案前缀"猜测是成功、被拒还是异常——文案一改就退化。
 * 这里把归类逻辑集中到 App 侧（真正知道文案含义的一方），插件宿主按需改用即可。
 *
 * 兼容策略：不修改 `shizuku()` 的签名，而是由 [PluginAppBridge.shizukuStructured]
 * 提供默认实现（按本文案归类）。插件宿主可以渐进迁移，旧实现不受影响。
 */
data class ShizukuOutcome(
    /** 命令是否真的执行成功（被拒绝或异常均为 false）。 */
    val success: Boolean,
    /** 是否被白名单/策略拒绝（与"执行失败"区分，便于审计与提示）。 */
    val denied: Boolean,
    /** 原始返回文本（诊断用）。 */
    val message: String
) {

    companion object {

        /** 被白名单/策略拒绝时 App 侧的返回前缀。 */
        private val DENIED_PREFIXES = listOf("拒绝执行", "命令不在白名单")

        /** 通道不可用或执行异常时的返回前缀。 */
        private val ERROR_PREFIXES = listOf(
            "执行异常", "调用失败", "Shizuku 不可用", "无法绑定"
        )

        /** 把 App 侧返回的可读文本归类为结构化结果。 */
        fun classify(message: String): ShizukuOutcome {
            val text = message.trim()
            val denied = DENIED_PREFIXES.any { text.startsWith(it) }
            if (denied) return ShizukuOutcome(success = false, denied = true, message = text)
            val failed = ERROR_PREFIXES.any { text.startsWith(it) }
            if (failed) return ShizukuOutcome(success = false, denied = false, message = text)
            // 其余视为成功（包括空的 exit=0 输出）
            return ShizukuOutcome(success = true, denied = false, message = text)
        }
    }
}
