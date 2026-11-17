package com.dsha.whalepet

/**
 * 桌宠运行状态快照（只读查询用）。
 *
 * 借鉴「人工桌面」的教训：关键状态应当**可查询**，而不是只依赖一次性事件。
 * UI 在 onResume 时直接读快照即可，不必假设广播已经到达、也不必依赖顺序。
 */
data class PetStatus(
    val state: PetLifecycle.State = PetLifecycle.State.IDLE,
    val wanderRunning: Boolean = false,
    val boundsReady: Boolean = false,
    val lastTransition: String = "INIT:IDLE",
    val lastRejected: String = ""
) {
    /** 用户视角"桌宠在运行"（含启动中与息屏暂停）。 */
    val running: Boolean
        get() = state == PetLifecycle.State.STARTING ||
            state == PetLifecycle.State.RUNNING ||
            state == PetLifecycle.State.PAUSED
}
