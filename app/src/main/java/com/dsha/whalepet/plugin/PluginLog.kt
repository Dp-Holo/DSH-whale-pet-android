package com.dsha.whalepet.plugin

import android.content.Context
import com.dsha.whalepet.common.MediaStoreLog

/**
 * 插件审计日志：记录插件的每一次动作执行（谁、什么事件、什么动作、结果）。
 *
 * 写入实现已集中到 [MediaStoreLog]（与运行时诊断日志共用同一套规则：
 * 同名变体归并、1MB 自清除、只处理本应用创建的文件）。本类只保留插件侧的文件名与入口。
 */
object PluginLog {

    private const val FILE_NAME = "whale-plugins.log"

    /** 追加一条审计记录（线程安全由 [MediaStoreLog] 保证）。 */
    fun append(ctx: Context, line: String) {
        MediaStoreLog.append(
            ctx,
            FILE_NAME,
            line + "\n",
            "=== audit log rolled over (max 1MB) ==="
        )
    }

    /** 供界面展示/排查：日志文件在 Download 下的固定路径。 */
    fun filePath(): String = MediaStoreLog.pathOf(FILE_NAME)
}
