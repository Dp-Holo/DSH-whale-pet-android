package com.dsha.whalepet

/**
 * 桌宠服务的显式生命周期状态机。
 *
 * 为什么要有它：此前"是否在跑 / 是否暂停"散落在 `isRunning`、`wanderRunning` 和
 * 屏幕广播回调里。一旦某个广播丢失或乱序，就没人知道当前到底处于哪个状态，
 * 表现就是"卡住 / 半死"，而且无法从日志判断。
 *
 * 这借鉴了「人工桌面」的教训：它把暂停/恢复的决定权交给一次性事件（JS），
 * 丢一个 `SCREEN_UNLOCK` 就永久停在最后一帧。正确做法是**由服务自己持有生命周期**，
 * 外部事件只做"请求"，非法请求被拒绝并留下记录。
 *
 * 三条原则：
 * 1. 状态只在这里迁移，其它地方只发请求；
 * 2. 非法迁移**不静默**——返回 false，由调用方写日志；
 * 3. 状态可查询（[state]），UI/诊断直接读，不依赖事件顺序。
 */
class PetLifecycle {

    enum class State { IDLE, STARTING, RUNNING, PAUSED, STOPPING }

    var state: State = State.IDLE
        private set

    /** 最近一次成功迁移，格式 `FROM->TO(原因)`，供诊断日志使用。 */
    var lastTransition: String = "INIT:IDLE"
        private set

    /** 最近一次被拒绝的迁移，便于排查"为什么没动"。 */
    var lastRejected: String = ""
        private set

    /** 服务启动（创建悬浮窗之前）。 */
    fun start(reason: String = "user"): Boolean =
        move(State.STARTING, reason) { state == State.IDLE }

    /** 悬浮窗就绪、帧循环已启动。 */
    fun ready(reason: String = "overlay"): Boolean =
        move(State.RUNNING, reason) { state == State.STARTING }

    /** 息屏：请求暂停游动与网络轮询。 */
    fun screenOff(reason: String = "screenOff"): Boolean =
        move(State.PAUSED, reason) { state == State.RUNNING }

    /** 亮屏：请求恢复。 */
    fun screenOn(reason: String = "screenOn"): Boolean =
        move(State.RUNNING, reason) { state == State.PAUSED }

    /** 开始停止（销毁窗口/注销广播之前）。 */
    fun stop(reason: String = "stop"): Boolean =
        move(State.STOPPING, reason) { state != State.IDLE && state != State.STOPPING }

    /** 停止完成。 */
    fun stopped(reason: String = "destroyed"): Boolean =
        move(State.IDLE, reason) { state == State.STOPPING }

    /** 启动阶段失败（例如权限缺失、窗口添加失败）直接回到 IDLE。 */
    fun abort(reason: String): Boolean =
        move(State.IDLE, reason) { state == State.STARTING }

    /** 是否处于"用户认为在跑"的状态（启动中/运行中/暂停中）。 */
    val isAlive: Boolean
        get() = state == State.STARTING || state == State.RUNNING || state == State.PAUSED

    /** 是否应当继续渲染/轮询。 */
    val isActive: Boolean
        get() = state == State.RUNNING

    private fun move(to: State, reason: String, guard: () -> Boolean): Boolean {
        val from = state
        if (!guard()) {
            lastRejected = "$from->$to($reason)"
            return false
        }
        state = to
        lastTransition = "$from->$to($reason)"
        return true
    }
}
