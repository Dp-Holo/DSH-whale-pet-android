package com.dsha.whalepet.wallpaper.video

import android.content.Context
import java.io.File

/**
 * 壁纸运行时状态落盘。
 *
 * 为什么不用 logcat：MIUI 会过滤第三方应用的 Info/Debug 日志，实测连我们自己的
 * "WhaleWallpaper/WhalePet" 都抓不到；文件是唯一可靠的现场证据（悬浮窗那边也是这么做的）。
 * 只写关键状态（可见性、解码位置、脉动值、摇一摇），2 秒一行，超过 128KB 自动清空。
 */
object WallpaperRuntimeLog {
    private const val FILE_NAME = "wallpaper_runtime.txt"
    private const val MAX_BYTES = 128 * 1024

    @Synchronized
    fun append(context: Context, message: String) {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, FILE_NAME)
            if (file.length() > MAX_BYTES) file.delete()
            file.appendText("${System.currentTimeMillis()} $message\n")
        }
    }
}
