package com.dsha.whalepet

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

/**
 * Shizuku 免 root 授权助手。
 *
 * 通过 Shizuku（以 adb/shell 身份运行）自动授予本应用所需权限：
 *  - SYSTEM_ALERT_WINDOW（悬浮窗）：appops set <pkg> SYSTEM_ALERT_WINDOW allow
 *  - POST_NOTIFICATIONS（通知）：pm grant <pkg> android.permission.POST_NOTIFICATIONS
 *
 * 用户需要：手机已运行 Shizuku（ADB 或 root 方式启动），
 * 并在首次使用时在 Shizuku 弹窗里允许本应用。
 */
object ShizukuHelper {

    private const val TAG = "WhalePet/Shizuku"

    /** Shizuku 是否可用（服务在运行且已授权给本应用）。 */
    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    /** Shizuku 服务在运行但本应用还没被授权。 */
    fun needsPermissionRequest(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    /** 请求 Shizuku 授权（会弹系统授权框，用户点允许一次）。 */
    fun requestPermission(requestCode: Int) {
        try {
            Shizuku.requestPermission(requestCode)
        } catch (e: Throwable) {
            Log.w(TAG, "requestPermission failed", e)
        }
    }

    /** 通过 Shizuku 授予悬浮窗权限。 */
    fun grantOverlay(ctx: Context): Boolean {
        return runShizuku(
            "appops", "set", ctx.packageName, "SYSTEM_ALERT_WINDOW", "allow"
        )
    }

    /** 通过 Shizuku 授予通知权限（Android 13+ 需要）。 */
    fun grantNotification(ctx: Context): Boolean {
        return runShizuku(
            "pm", "grant", ctx.packageName, "android.permission.POST_NOTIFICATIONS"
        )
    }

    /** 通过 Shizuku 执行系统命令。 */
    private fun runShizuku(vararg args: String): Boolean {
        if (!isAvailable()) return false
        return try {
            // Shizuku.newProcess 以 shell 身份执行命令
            val process = Shizuku.newProcess(args, null)
            val exit = process.waitFor()
            if (exit != 0) {
                Log.w(TAG, "shizuku cmd ${args.joinToString(" ")} exit=$exit")
            }
            exit == 0
        } catch (e: Throwable) {
            Log.w(TAG, "shizuku exec failed", e)
            false
        }
    }

    /**
     * 一键授权：若 Shizuku 可用，自动授予悬浮窗 + 通知权限。
     * @return true 表示已通过 Shizuku 完成授权
     */
    fun autoGrant(ctx: Context): Boolean {
        if (!isAvailable()) return false
        var ok = false
        try {
            if (!android.provider.Settings.canDrawOverlays(ctx)) {
                ok = grantOverlay(ctx) || ok
            }
        } catch (_: Throwable) {
        }
        // 通知权限（Android 13+）
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ok = grantNotification(ctx) || ok
            }
        } catch (_: Throwable) {
        }
        return ok
    }

    /** 确保 ShizukuProvider 已注册（编译期引用，防混淆移除）。 */
    @Suppress("unused")
    private val providerClass = ShizukuProvider::class.java
}
