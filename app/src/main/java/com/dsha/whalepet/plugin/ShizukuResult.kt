package com.dsha.whalepet.plugin

/**
 * Shizuku 命令的执行结果分类（纯逻辑，可单测）。
 *
 * 为什么需要分类：跨边界接口 `PluginAppBridge.shizuku()` 只返回一段人可读文本
 * （改签名需要双方确认，本次不动）。而审计此前把结果写死成 `ok`，
 * 于是**白名单拒绝也记成了成功**——安全拦截在日志里完全看不出来。
 *
 * 这里按 App 侧已知返回文案的前缀归类，让审计能区分"真执行了"和"被拒了"。
 * 前缀集合与 `ShizukuHelper.execWhitelistedSync()` 的返回文案一一对应；
 * 若 App 侧改了文案，最坏退化成 OK（只是审计不够精确，不会误放行）。
 */
data class ShizukuResult(val outcome: Outcome, val message: String) {

    enum class Outcome { OK, DENIED, ERROR }

    /** 审计"结果"列的取值。 */
    val auditResult: String
        get() = when (outcome) {
            Outcome.OK -> "ok"
            Outcome.DENIED -> "denied"
            Outcome.ERROR -> "error"
        }

    companion object {

        /** 被白名单/策略拒绝时 App 侧的说法。 */
        private val DENIED_PREFIXES = listOf("拒绝执行", "命令不在白名单")

        /** 通道不可用或执行异常。 */
        private val ERROR_PREFIXES = listOf(
            "执行异常", "调用失败", "Shizuku 不可用", "无法绑定"
        )

        /** 把 App 侧返回的可读文本归类。 */
        fun classify(message: String): ShizukuResult {
            val text = message.trim()
            val outcome = when {
                // 先判拒绝：白名单拒绝的文案里也含"白名单"字样，
                // 且它属安全拦截，优先级高于其它
                DENIED_PREFIXES.any { text.startsWith(it) } -> Outcome.DENIED
                ERROR_PREFIXES.any { text.startsWith(it) } -> Outcome.ERROR
                text.isEmpty() -> Outcome.ERROR
                else -> Outcome.OK
            }
            return ShizukuResult(outcome, text)
        }

        /**
         * 由 App 侧的结构化结果映射（1.1.47 起 `PluginAppBridge.shizukuStructured()` 提供）。
         *
         * 迁移到这个入口后，宿主不必再自己解析文案——归类规则由**真正知道文案含义**的
         * App 侧维护（`common/ShizukuOutcome`）。[classify] 只作为没有结构化通道时的兜底。
         */
        fun from(appOutcome: com.dsha.whalepet.common.ShizukuOutcome): ShizukuResult =
            ShizukuResult(
                outcome = when {
                    appOutcome.success -> Outcome.OK
                    appOutcome.denied -> Outcome.DENIED
                    else -> Outcome.ERROR
                },
                message = appOutcome.message
            )
    }
}
