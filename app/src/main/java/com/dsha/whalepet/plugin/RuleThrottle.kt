package com.dsha.whalepet.plugin

/**
 * 规则型插件动作的节流（纯逻辑，可单测）。
 *
 * 为什么要设下限：App 侧的插件通知渠道是 `IMPORTANCE_DEFAULT`（**有声**），
 * 而规则型的 `cooldownMs` 默认是 0 —— 已授权插件只要把 `notify` 绑到 `tap` 上，
 * 连点就能持续轰炸通知栏。这里给所有规则动作一个最小间隔兜底，
 * 用户自己配置的 `cooldownMs` 更大时以用户为准。
 */
object RuleThrottle {

    /** 同一规则的最小触发间隔：1 秒。 */
    const val MIN_INTERVAL_MS = 1_000L

    /** `lastFire` 表超过这个规模就开始清理，避免无限增长。 */
    const val MAX_TRACKED_RULES = 256

    /** 清理时保留多久内的记录（更早的视为过期，可直接丢弃）。 */
    const val RETENTION_MS = 10 * 60_000L

    /** 实际生效的冷却 = 用户配置值与下限取大。 */
    fun effectiveCooldown(ruleCooldownMs: Long): Long =
        ruleCooldownMs.coerceAtLeast(MIN_INTERVAL_MS)

    /**
     * 该规则此刻是否允许触发。
     *
     * `lastFiredAt == null` 表示从未触发过。系统时间被回拨（`now < lastFiredAt`）时
     * 按"允许触发"处理，否则时钟一改这条规则就永久卡死。
     */
    fun shouldFire(lastFiredAt: Long?, now: Long, cooldownMs: Long): Boolean {
        if (lastFiredAt == null) return true
        if (now < lastFiredAt) return true
        return now - lastFiredAt >= effectiveCooldown(cooldownMs)
    }
}
