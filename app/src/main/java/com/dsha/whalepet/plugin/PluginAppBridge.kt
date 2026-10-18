package com.dsha.whalepet.plugin

/**
 * 【兼容层 ①】App 本体向插件宿主提供的基础能力。
 *
 * 职责划分：
 *  - **App 侧**（软件本体）只实现"怎么做"：气泡说话、发通知、震动、打开网页/应用、执行系统命令。
 *  - **插件宿主侧**负责"能不能做"：能力授权、敏感操作二次确认、审计日志，全部在宿主内完成。
 *
 * 因此新增插件的安全策略**不需要改动 App 本体**；App 侧只需要照旧提供下面这些基础能力。
 */
interface PluginAppBridge {

    /** 让鲸鱼娘在头顶气泡说一句话（与单击台词同一条显示路径）。 */
    fun say(text: String)

    /** 发送系统通知。 */
    fun notify(title: String, text: String)

    /** 震动指定毫秒（实现方需自行做上限保护）。 */
    fun vibrate(ms: Long)

    /** 打开网页；返回是否已成功发起。 */
    fun openUrl(url: String): Boolean

    /** 打开指定包名的应用；返回是否已成功发起。 */
    fun openApp(pkg: String): Boolean

    /**
     * 执行 Shizuku 白名单系统命令；返回可读的结果描述。
     * 实现方会**在后台线程同步执行**（调用方本身运行在插件线程）。
     */
    fun shizuku(args: List<String>): String

    /**
     * [shizuku] 的结构化版本（可选迁移）。
     *
     * 默认实现按 App 侧已知的返回文案归类，避免插件宿主自己解析文本；
     * 归类规则集中在 [com.dsha.whalepet.common.ShizukuOutcome]（App 侧维护）。
     * 现有实现无需改动即可继续工作。
     */
    fun shizukuStructured(args: List<String>): com.dsha.whalepet.common.ShizukuOutcome =
        com.dsha.whalepet.common.ShizukuOutcome.classify(shizuku(args))
}
