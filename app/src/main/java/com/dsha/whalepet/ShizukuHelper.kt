package com.dsha.whalepet

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku 免 root 授权助手（官方 UserService 方案，异步执行）。
 *
 * 通过 Shizuku（以 adb/shell 身份运行）自动授予本应用所需权限：
 *  - SYSTEM_ALERT_WINDOW（悬浮窗）：appops set <pkg> SYSTEM_ALERT_WINDOW allow
 *  - POST_NOTIFICATIONS（通知）：pm grant <pkg> android.permission.POST_NOTIFICATIONS
 *
 * 关键设计：UserService 绑定是异步的，回调在主线程派发；
 * 因此命令执行放在后台线程等待绑定完成，绝不阻塞主线程（否则死锁）。
 */
object ShizukuHelper {

    private const val TAG = "WhalePet/Shizuku"
    private val mainHandler = Handler(Looper.getMainLooper())
    /** 运行时真实包名（debug 版是 *.debug，不能硬编码）。 */
    private var appPackage: String? = null

    /** Shizuku 服务是否在运行。 */
    fun isShizukuRunning(): Boolean = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    /** 本应用是否已被 Shizuku 授权。 */
    fun isGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
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

    /**
     * 一键授权（异步）：若 Shizuku 可用，在后台线程自动授予悬浮窗 + 通知权限。
     * 结果通过回调回到主线程。
     */
    fun autoGrant(ctx: Context, onDone: (Boolean) -> Unit) {
        if (!isAvailable()) {
            mainHandler.post { onDone(false) }
            return
        }
        appPackage = ctx.packageName
        Thread {
            var ok = false
            try {
                if (!android.provider.Settings.canDrawOverlays(ctx)) {
                    ok = exec("appops", "set", ctx.packageName,
                        "SYSTEM_ALERT_WINDOW", "allow") || ok
                }
            } catch (_: Throwable) { }
            try {
                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                    ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    ok = exec("pm", "grant", ctx.packageName,
                        "android.permission.POST_NOTIFICATIONS") || ok
                }
            } catch (_: Throwable) { }
            val result = ok
            mainHandler.post { onDone(result) }
        }.start()
    }

    // ── 后台线程命令执行 ─────────────────────────────────────

    private var cachedBinder: IBinder? = null

    /** 通过 Shizuku UserService 执行命令（须在后台线程调用）。 */
    private fun exec(vararg args: String): Boolean {
        if (!isAvailable()) return false
        val binder = getServiceBinder() ?: return false
        return try {
            val svc = IShizukuCommand.Stub.asInterface(binder)
            svc.exec(args.toTypedArray()) == 0
        } catch (e: Throwable) {
            Log.w(TAG, "exec failed", e)
            false
        }
    }

    /** 绑定 UserService（在后台线程同步等待回调，最长 5 秒）。 */
    private fun getServiceBinder(): IBinder? {
        cachedBinder?.let { if (it.isBinderAlive) return it }
        val svcArgs = Shizuku.UserServiceArgs(
            android.content.ComponentName(
                appPackage ?: "com.dsha.whalepet",
                ShizukuCommandService::class.java.name
            )
        )
        val latch = java.util.concurrent.CountDownLatch(1)
        try {
            Shizuku.bindUserService(
                svcArgs,
                object : android.content.ServiceConnection {
                    override fun onServiceConnected(name: android.content.ComponentName, service: IBinder) {
                        cachedBinder = service
                        latch.countDown()
                    }

                    override fun onServiceDisconnected(name: android.content.ComponentName) {
                        cachedBinder = null
                    }
                }
            )
        } catch (e: RemoteException) {
            Log.w(TAG, "bindUserService failed", e)
        }
        // 后台线程等待回调（主线程不被阻塞，回调可正常派发）
        try {
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        return cachedBinder
    }
}
