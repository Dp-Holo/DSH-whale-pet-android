package com.dsha.whalepet

import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku 免 root 授权助手（官方 UserService 方案）。
 *
 * 通过 Shizuku（以 adb/shell 身份运行）自动授予本应用所需权限：
 *  - SYSTEM_ALERT_WINDOW（悬浮窗）：appops set <pkg> SYSTEM_ALERT_WINDOW allow
 *  - POST_NOTIFICATIONS（通知）：pm grant <pkg> android.permission.POST_NOTIFICATIONS
 */
object ShizukuHelper {

    private const val TAG = "WhalePet/Shizuku"

    /** Shizuku 服务是否在运行。 */
    fun isShizukuRunning(): Boolean = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    /** 本应用是否已被 Shizuku 授权。 */
    fun isGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }

    /** Shizuku 可用（服务运行 + 已授权给本应用）。 */
    fun isAvailable(): Boolean = isShizukuRunning() && isGranted()

    /** 服务运行但本应用还没授权。 */
    fun needsPermissionRequest(): Boolean = isShizukuRunning() && !isGranted()

    /** 请求 Shizuku 授权（弹系统框，用户点允许一次）。 */
    fun requestPermission(requestCode: Int) {
        try { Shizuku.requestPermission(requestCode) } catch (e: Throwable) {
            Log.w(TAG, "requestPermission failed", e)
        }
    }

    /** 一键授权：若 Shizuku 可用，自动授予悬浮窗 + 通知权限。 */
    fun autoGrant(ctx: android.content.Context): Boolean {
        if (!isAvailable()) return false
        var ok = false
        try {
            if (!android.provider.Settings.canDrawOverlays(ctx)) {
                ok = exec("appops", "set", ctx.packageName, "SYSTEM_ALERT_WINDOW", "allow") || ok
            }
        } catch (_: Throwable) { }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                ok = exec("pm", "grant", ctx.packageName,
                    "android.permission.POST_NOTIFICATIONS") || ok
            }
        } catch (_: Throwable) { }
        return ok
    }

    /** 通过 Shizuku UserService 执行命令，返回退出码。 */
    private fun exec(vararg args: String): Boolean {
        if (!isAvailable()) return false
        return try {
            val binder = getServiceBinder() ?: return false
            val svc = IShizukuCommand.Stub.asInterface(binder)
            svc.exec(args.toTypedArray()) == 0
        } catch (e: Throwable) {
            Log.w(TAG, "exec failed", e)
            false
        }
    }

    /** 绑定并获取 ShizukuCommandService 的 binder（缓存）。 */
    private var cachedBinder: IBinder? = null

    private fun getServiceBinder(): IBinder? {
        cachedBinder?.let { if (it.isBinderAlive) return it }
        // Shizuku 13.x：UserServiceArgs 由 service 类名构造，
        // bindUserService 第二参数是标准 ServiceConnection，binder 经回调返回
        val args = Shizuku.UserServiceArgs(ShizukuCommandService::class.java.name)
        try {
            Shizuku.bindUserService(
                args,
                object : android.content.ServiceConnection {
                    override fun onServiceConnected(name: android.content.ComponentName, service: IBinder) {
                        cachedBinder = service
                    }

                    override fun onServiceDisconnected(name: android.content.ComponentName) {
                        cachedBinder = null
                    }
                }
            )
        } catch (e: RemoteException) {
            Log.w(TAG, "bindUserService failed", e)
        }
        // 绑定是异步的，稍后通过回调填充；这里给回调一点时间
        return cachedBinder
    }
}
