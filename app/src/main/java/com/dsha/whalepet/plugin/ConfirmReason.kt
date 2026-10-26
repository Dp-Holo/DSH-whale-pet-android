package com.dsha.whalepet.plugin

/**
 * 敏感确认的**结束原因**。
 *
 * 为什么需要区分：此前只有"允许/拒绝"两态，超时、被后来的确认顶掉、服务停止
 * 这三种情况全都被记成 `用户拒绝`。用户根本没看到那条确认条，审计却写着"他拒绝了"——
 * 一旦有争议，日志不能作为追责依据。
 */
enum class ConfirmReason {

    /** 用户点了「允许」或「始终允许」。 */
    ALLOWED,

    /** 用户点了「拒绝」。 */
    USER_DENIED,

    /** 15 秒内没有任何操作。 */
    TIMEOUT,

    /** 新的确认请求到来，这一条被顶替（用户从未看到它）。 */
    SUPERSEDED,

    /** 桌宠服务停止，确认条被收起。 */
    SERVICE_STOPPED,

    /** 悬浮窗不可用，弹不出确认条。 */
    UNAVAILABLE;

    /** 写进审计"详情"列的中文说明（与结果列 `denied` 配合使用）。 */
    fun auditText(): String = when (this) {
        ALLOWED -> "用户允许"
        USER_DENIED -> "用户拒绝"
        TIMEOUT -> "15 秒内未选择，视为拒绝"
        SUPERSEDED -> "被后来的确认请求顶替，未执行"
        SERVICE_STOPPED -> "桌宠服务已停止，未执行"
        UNAVAILABLE -> "悬浮窗不可用，无法确认"
    }

    /**
     * 审计"详情"列的最终文本。
     *
     * [UNAVAILABLE] 会附带[具体失败原因][failure]（权限没开？布局解析失败？加窗失败？）——
     * 只写"悬浮窗不可用"时，真机上完全无法判断卡在哪一步，排查只能靠猜。
     */
    fun detailWith(failure: String?): String =
        if (this == UNAVAILABLE && !failure.isNullOrBlank()) "${auditText()}（$failure）" else auditText()
}
