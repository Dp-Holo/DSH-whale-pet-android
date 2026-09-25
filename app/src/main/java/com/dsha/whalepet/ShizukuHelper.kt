package com.dsha.whalepet

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku 免 root 授权助手（官方 UserService 方案）。
 *
 * 通过 Shizuku（以 adb/shell 身份运行）自动授予本应用所需权限：
 *  - SYSTEM_ALERT_WINDOW（悬浮窗）：appops set <pkg> SYSTEM_ALERT_WINDOW allow
 *  - POST_NOTIFICATIONS（通知）：pm grant <pkg> android.permission.POST_NOTIFICATIONS
 *
 * 关键设计（此前版本失效的原因）：
 *  1. Shizuku 的 binder **异步到达**，必须用 addBinderReceivedListener 等待就绪，
 *     不能只在 Activity.onCreate 里 pingBinder 一次就放弃；
 *  2. 授权结果必须用 addRequestPermissionResultListener 接收，
 *     Shizuku 不走 Activity.onRequestPermissionsResult；
 *  3. 成功判据以系统真实状态（Settings.canDrawOverlays）为准，而非仅看命令退出码；
 *  4. 失败时把命令的 exit/stdout/stderr 回传界面，便于定位（不再静默失败）；
 *  5. UserService 绑定在后台线程等待回调，绝不阻塞主线程（否则死锁）。
 */
object ShizukuHelper {

    private const val TAG = "WhalePet/Shizuku"

    /** Shizuku 授权请求码（结果经 OnRequestPermissionResultListener 返回）。 */
    const val REQ_SHIZUKU = 1001

    /** UserService 版本号：变更服务实现时递增，Shizuku 会重建旧进程。 */
    private const val SERVICE_VERSION = 1

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 运行时真实包名（debug 版是 *.debug，不能硬编码）。 */
    private var appPackage: String? = null

    /** binder 就绪处理去重（binder 已就绪时注册监听会立即回调）。 */
    @Volatile
    private var binderHandled = false

    private var binderReceivedListener: Shizuku.OnBinderReceivedListener? = null
    private var binderDeadListener: Shizuku.OnBinderDeadListener? = null
    private var permissionResultListener: Shizuku.OnRequestPermissionResultListener? = null

    // ── 状态查询 ──────────────────────────────────────────────

    /** Shizuku 服务是否在运行。 */
    fun isShizukuRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    /** 本应用是否已被 Shizuku 授权。 */
    fun isGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    /** Shizuku 可用（服务运行 + 已授权给本应用）。 */
    fun isAvailable(): Boolean = isShizukuRunning() && isGranted()

    /** 悬浮窗权限的系统真实状态。 */
    fun canDrawOverlay(ctx: Context): Boolean = try {
        Settings.canDrawOverlays(ctx)
    } catch (t: Throwable) {
        false
    }

    // ── 初始化：注册监听（幂等）────────────────────────────────

    /**
     * 初始化 Shizuku 集成。注册 binder 就绪/死亡监听与授权结果监听；
     * binder 就绪后自动请求授权或执行一键授权。
     *
     * @param onResult 结果回调（主线程）：ok=权限是否真的已生效，detail=失败原因（便于排查）
     */
    fun init(ctx: Context, onResult: (ok: Boolean, detail: String) -> Unit) {
        val app = ctx.applicationContext
        appPackage = app.packageName

        // 1) 授权结果监听（Shizuku 专用通道）
        if (permissionResultListener == null) {
            val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                if (requestCode == REQ_SHIZUKU) {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        autoGrant(app, onResult)
                    } else {
                        Log.w(TAG, "Shizuku permission denied by user")
                        mainHandler.post { onResult(false, "你拒绝了 Shizuku 授权") }
                    }
                }
            }
            permissionResultListener = listener
            try {
                Shizuku.addRequestPermissionResultListener(listener)
            } catch (t: Throwable) {
                Log.w(TAG, "addRequestPermissionResultListener failed", t)
            }
        }

        // 2) binder 就绪 / 死亡监听
        if (binderReceivedListener == null) {
            val received = Shizuku.OnBinderReceivedListener {
                onBinderReady(app, onResult)
            }
            binderReceivedListener = received
            val dead = Shizuku.OnBinderDeadListener {
                Log.i(TAG, "Shizuku binder dead")
                binderHandled = false
            }
            binderDeadListener = dead
            try {
                Shizuku.addBinderReceivedListener(received)
                Shizuku.addBinderDeadListener(dead)
            } catch (t: Throwable) {
                Log.w(TAG, "addBinderReceivedListener failed", t)
            }
        }

        // 3) binder 可能已经就绪（应用启动晚于 Shizuku）：直接尝试一次
        if (isShizukuRunning()) {
            onBinderReady(app, onResult)
        } else {
            Log.i(TAG, "Shizuku not running (will retry when binder arrives)")
            mainHandler.post { onResult(false, "Shizuku 未运行") }
        }
    }

    /** 注销监听，避免 Activity 泄漏。 */
    fun release() {
        permissionResultListener?.let { runCatching { Shizuku.removeRequestPermissionResultListener(it) } }
        binderReceivedListener?.let { runCatching { Shizuku.removeBinderReceivedListener(it) } }
        binderDeadListener?.let { runCatching { Shizuku.removeBinderDeadListener(it) } }
        permissionResultListener = null
        binderReceivedListener = null
        binderDeadListener = null
    }

    /** binder 就绪：需要授权就请求，已授权就直接执行自动授权。 */
    private fun onBinderReady(app: Context, onResult: (Boolean, String) -> Unit) {
        if (binderHandled) return
        binderHandled = true
        if (!isGranted()) {
            Log.i(TAG, "binder ready, requesting Shizuku permission")
            try {
                Shizuku.requestPermission(REQ_SHIZUKU)
            } catch (t: Throwable) {
                Log.w(TAG, "requestPermission failed", t)
                mainHandler.post { onResult(false, "请求 Shizuku 授权失败：${t.message ?: "未知错误"}") }
            }
            return // 结果由 OnRequestPermissionResultListener 回调
        }
        autoGrant(app, onResult)
    }

    // ── 一键自动授权 ───────────────────────────────────────────

    /**
     * 一键授权：若 Shizuku 可用，在后台线程自动授予悬浮窗 + 通知权限。
     * 结果以系统真实状态为准，回调到主线程。
     */
    fun autoGrant(ctx: Context, onResult: (ok: Boolean, detail: String) -> Unit) {
        val app = ctx.applicationContext
        appPackage = app.packageName
        if (!isAvailable()) {
            mainHandler.post { onResult(false, "Shizuku 不可用（未运行或未授权）") }
            return
        }
        Thread {
            val log = StringBuilder()
            try {
                if (!canDrawOverlay(app)) {
                    val r = runCommand("appops", "set", app.packageName, "SYSTEM_ALERT_WINDOW", "allow")
                    log.append("appops set: ").append(r).append('\n')
                } else {
                    log.append("悬浮窗权限此前已授予\n")
                }
            } catch (t: Throwable) {
                log.append("appops 异常：").append(t.message ?: t.javaClass.simpleName).append('\n')
            }
            try {
                if (Build.VERSION.SDK_INT >= 33 &&
                    app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    val r = runCommand("pm", "grant", app.packageName, "android.permission.POST_NOTIFICATIONS")
                    log.append("pm grant: ").append(r).append('\n')
                }
            } catch (t: Throwable) {
                log.append("pm grant 异常：").append(t.message ?: t.javaClass.simpleName).append('\n')
            }
            // 以系统真实状态作为成功判据（命令退出码可能为 0 但 appop 实际未生效）
            val ok = canDrawOverlay(app)
            val detail = buildString {
                append(if (ok) "已自动授予悬浮窗权限" else "自动授权未生效")
                val text = log.toString().trim()
                if (text.isNotEmpty()) append('\n').append(text)
            }
            Log.i(TAG, "autoGrant ok=$ok detail=$detail")
            mainHandler.post { onResult(ok, detail) }
        }.start()
    }

    // ── 电池优化白名单 ──────────────────────────────────────

    /**
     * 把本应用加入电池优化白名单（`dumpsys deviceidle whitelist +<pkg>`），
     * 降低被系统后台清理的概率（桌宠长期常驻更稳）。以系统真实状态回报结果。
     */
    fun addToBatteryWhitelist(ctx: Context, onResult: (ok: Boolean, detail: String) -> Unit) {
        val app = ctx.applicationContext
        appPackage = app.packageName
        if (isIgnoringBatteryOptimizations(app)) {
            mainHandler.post { onResult(true, "已在电池优化白名单中") }
            return
        }
        if (!isAvailable()) {
            mainHandler.post { onResult(false, "Shizuku 不可用") }
            return
        }
        Thread {
            val detail = try {
                runCommand("dumpsys", "deviceidle", "whitelist", "+" + app.packageName)
            } catch (t: Throwable) {
                "执行异常：${t.message ?: t.javaClass.simpleName}"
            }
            val ok = isIgnoringBatteryOptimizations(app)
            Log.i(TAG, "battery whitelist ok=$ok detail=$detail")
            mainHandler.post { onResult(ok, detail) }
        }.start()
    }

    /** 是否已忽略电池优化（系统真实状态）。 */
    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (t: Throwable) {
        false
    }

    // ── 后台线程命令执行 ─────────────────────────────────────

    private var cachedBinder: IBinder? = null

    /** 通过 Shizuku UserService 执行命令并取回诊断文本（须在后台线程调用）。 */
    private fun runCommand(vararg args: String): String {
        if (!isAvailable()) return "Shizuku 不可用"
        val binder = getServiceBinder() ?: return "无法绑定 UserService（Shizuku 未响应）"
        return try {
            IShizukuCommand.Stub.asInterface(binder).run(args)
        } catch (t: Throwable) {
            Log.w(TAG, "runCommand failed", t)
            "调用 UserService 失败：${t.message ?: t.javaClass.simpleName}"
        }
    }

    /** 绑定 UserService（在后台线程同步等待回调，最长 5 秒）。 */
    private fun getServiceBinder(): IBinder? {
        cachedBinder?.let { if (it.isBinderAlive) return it }
        val pkg = appPackage ?: return null
        val svcArgs = Shizuku.UserServiceArgs(
            ComponentName(pkg, ShizukuCommandService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shizuku")
            .debuggable(false)
            .version(SERVICE_VERSION)

        val latch = CountDownLatch(1)
        try {
            Shizuku.bindUserService(
                svcArgs,
                object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {
                        cachedBinder = service
                        latch.countDown()
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        cachedBinder = null
                    }
                }
            )
        } catch (t: Throwable) {
            Log.w(TAG, "bindUserService failed", t)
            return null
        }
        // 后台线程等待回调（主线程不被阻塞，回调可正常派发）
        try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        return cachedBinder
    }
}
