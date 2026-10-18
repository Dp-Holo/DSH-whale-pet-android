package com.dsha.whalepet.plugin

/**
 * 授权集合的变更策略（纯逻辑，无 Android 依赖 → 可被 JVM 单测覆盖）。
 *
 * 为什么单独抽出来：授权是安全边界，"取消勾选到底有没有真的收回权限"这类语义
 * 必须被测试钉死。UI 上的多选框只是输入，真正的语义在这里。
 */
object GrantPolicy {

    /**
     * 保存授权时的目标集合。
     *
     * **全量替换**而不是追加：授权框会把已授权项预勾选，用户取消勾选某项后点保存，
     * 若只做 `addAll`，被取消的能力仍然有效——用户以为收回了权限，实际没有。
     *
     * 返回 `null` 表示"什么都不写"：空选择不代表"撤销全部"（撤销全部有独立入口），
     * 而是"这次没选"，应保持现状。
     */
    fun replaceWith(selected: Collection<String>): Set<String>? =
        if (selected.isEmpty()) null else selected.toSet()

    /**
     * 授权收缩后需要保留的"始终允许"记录 = 仍然被授权的那些。
     *
     * 不清掉的话：用户取消勾选 `openApp` 再重新勾上时，"始终允许"会复活，
     * 敏感操作直接跳过确认条——上一轮的一次性决定被强行延续到新一轮授权里。
     */
    fun pruneAlways(always: Set<String>, granted: Set<String>): Set<String> =
        always.intersect(granted)
}
