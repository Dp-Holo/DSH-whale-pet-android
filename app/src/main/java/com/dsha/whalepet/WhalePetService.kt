package com.dsha.whalepet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.dsha.whalepet.common.MediaStoreLog
import com.dsha.whalepet.plugin.PluginAppBridge
import com.dsha.whalepet.plugin.PluginEntry
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import java.util.Locale

/**
 * 悬浮窗鲸鱼娘桌宠服务。
 *
 * 行为（与 Web 版插件一致）：
 *  - 自主游动：方向角平滑偏转 + 碰边反弹，浮点亚像素定位，角度制防抖；
 *  - 按下瞬间弹跳缩放（scale 反馈），单击冒台词，双击显示余额；
 *  - 可拖动：按住拖到任意位置，松手后继续游；
 *  - 每 5 分钟自动查一次 DeepSeek 余额，头顶 #A2B4DD 上浮渐隐 0.8s。
 */
class WhalePetService : Service() {

    companion object {
        /** 启动时的"落点"：主页鲸鱼娘图片的屏幕中心（由 Activity 传入） */
        const val EXTRA_START_CENTER_X = "start_center_x"
        const val EXTRA_START_CENTER_Y = "start_center_y"
        /** 服务是否正在运行（供界面启停按钮显示对应文案）。 */
        @Volatile
        var isRunning: Boolean = false

        /** 余额静默检测间隔：3 秒（熄屏期间整体暂停，亮屏恢复） */
        /** 余额变化探测间隔：无变化时每 5 秒一次 */
        private const val TOKEN_WATCH_INTERVAL_MS = 5_000L

        /** 检测到余额变化后的冷却：1 分钟内不再探测 */
        private const val TOKEN_COOLDOWN_AFTER_CHANGE_MS = 60_000L

        /** 余额变动气泡时长（2.5 秒）+ 少量余量，用于「先弹提示、后显示余额」的串行安排 */
        private const val TOKEN_BUBBLE_DURATION_MS = 2_600L

        /** 金额变化提示颜色：扣款红、增加绿 */
        private const val DELTA_COLOR_LOSS = 0xFFE53935.toInt()
        private const val DELTA_COLOR_GAIN = 0xFF43A047.toInt()

        /** 余额查询失败的日志节流：断网时避免每 3 秒写一行把日志刷爆 */
        private const val TOKEN_FAIL_LOG_INTERVAL_MS = 60_000L

        /** 插件通知渠道与通知 id */
        /**
         * 插件通知渠道。
         *
         * 注意：渠道重要性**一旦创建就不能再下调**（此后由用户在系统设置里控制）。
         * 1.1.43 起把它从"有声(DEFAULT)"降为"无声(LOW)"，所以必须换一个新 id，
         * 并在创建后删除旧渠道，否则老用户感知不到变化。
         */
        private const val PLUGIN_CHANNEL_ID = "whale_plugin_channel_v2"
        private const val PLUGIN_NOTIF_ID = 2

    }

    private lateinit var wm: WindowManager
    private lateinit var rootView: View
    private lateinit var overlayParams: WindowManager.LayoutParams
    private lateinit var whaleImg: ImageView

    private val handler = Handler(Looper.getMainLooper())
    private var wanderRunning = false
    /** 漫游帧回调（Choreographer，对齐 vsync；比固定 16ms Handler 更省电、帧率更稳） */
    private var frameCallback: Choreographer.FrameCallback? = null
    private var lastFrameNanos = 0L
    /** 诊断 tick 的毫秒累计（改用时间而非帧数，避免高刷屏上日志变频繁） */
    private var debugTickMs = 0f
    /** 布局更新失败日志的节流时间戳（失败可能每帧发生，避免刷屏） */
    private var layoutFailLoggedAt = 0L
    /** 布局失败后的游动恢复任务（窗口恢复后自动继续，避免一直静止） */
    private var wanderResumeRunnable: Runnable? = null
    /** 待应用的启动落点（屏幕坐标，-1 表示不指定） */
    private var pendingStartX = -1
    private var pendingStartY = -1
    private var balanceRunnable: Runnable? = null
    // 余额消耗检测（每 3 秒静默查询，结果不显示）
    private var tokenWatchRunnable: Runnable? = null
    private var lastBalanceValue: Double? = null
    private var lastBalanceCurrency: String? = null
    private var debugTokenNoKey = false
    /** 上一次余额请求还没回来（超时 8 秒 > 间隔 3 秒，需要防重叠） */
    private var tokenWatchInFlight = false
    /** 请求序号：丢弃过期响应，避免乱序覆盖基准值导致误报"余额减少" */
    private var tokenWatchSeq = 0L
    private var lastTokenFailLogAt = 0L
    private var badgeTimer: Runnable? = null
    private var bubbleTimer: Runnable? = null

    /** 低电量边沿触发用：上一次的电量状态 */
    private var wasBatteryLow = false

    // 漫游状态
    private var x = 0f
    private var y = 0f
    private var angle = -0.4f          // 初始方向（向左上）
    private var dragging = false

    // 鲸鱼本体尺寸（0.8×：120dp → 96dp）
    private val sizePx: Int
        get() = (120 * 0.8f * resources.displayMetrics.density).toInt()
    // 悬浮窗尺寸：比本体大 25%，给点击缩放（1.12×）留余量，避免放大时四周被窗口裁剪
    private val windowPx: Int
        get() = (sizePx * 1.25f).toInt()
    // 窗口内边距：本体居中绘制，四周留出缩放余量
    private val imgPad: Int
        get() = (windowPx - sizePx) / 2
    /** 每帧基础速度（按密度换算，≈0.55 px/frame @1x） */
    private val speedPx: Float
        get() = 0.55f * resources.displayMetrics.density * 0.55f
    // 气泡/余额悬浮窗尺寸（宽度固定，高度内容自适应）
    private val bubbleW: Int
        get() = (240 * resources.displayMetrics.density).toInt()

    // 台词池由用户在设置页维护（WhaleLines，本机存储），每次说话时读取，改动即时生效

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 悬浮窗权限被撤销时不要再走 addView：否则会抛 BadTokenException，
        // 若服务被系统重启就会形成"崩溃→重启→再崩溃"的循环。直接结束，等用户重新授权。
        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        isRunning = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundCompat()
        buildOverlay()
        startWander()
        scheduleBalance()
        scheduleTokenWatch()
        registerSystemReceivers()
        scheduleBatteryWatch()
        // 插件宿主启动：App 只交出基础能力，授权/确认/审计由宿主侧负责
        PluginEntry.onServiceStart(this, pluginBridge)
    }

    /**
     * 显式声明不粘性：服务异常退出后不由系统自动重启。
     * 这样"权限被撤销 → onCreate 抛异常 → 系统重启"不会再变成崩溃循环。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 主页点「启动桌宠」时带上图片中心坐标：悬浮窗先落在图上，随后主页图片淡出
        intent?.let {
            val cx = it.getIntExtra(EXTRA_START_CENTER_X, -1)
            val cy = it.getIntExtra(EXTRA_START_CENTER_Y, -1)
            if (cx >= 0 && cy >= 0) {
                pendingStartX = cx
                pendingStartY = cy
                if (boundsReady) applyStartPosition()
            }
        }
        return START_NOT_STICKY
    }

    /** 系统状态栏高度（校准失败时作为窗口原点偏移的近似值）。 */
    private fun statusBarHeightPx(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) {
            runCatching { resources.getDimensionPixelSize(resId) }.getOrDefault(0)
        } else {
            0
        }
    }

    /** 把窗口挪到启动落点（服务已在运行时用；首次启动在校准后调用）。 */
    private fun applyStartPosition() {
        if (pendingStartX < 0 || pendingStartY < 0) return
        x = (pendingStartX - windowPx / 2f).coerceIn(boundMinX.toFloat(), boundMaxX.toFloat())
        y = (pendingStartY - offsetY - windowPx / 2f).coerceIn(boundMinY.toFloat(), boundMaxY.toFloat())
        overlayParams.x = x.roundToInt()
        overlayParams.y = y.roundToInt()
        safeUpdateLayout()
        pendingStartX = -1
        pendingStartY = -1
    }

    override fun onDestroy() {
        isRunning = false
        stopWander()
        PluginEntry.onServiceStop()
        balanceRunnable?.let(handler::removeCallbacks)
        tokenWatchRunnable?.let(handler::removeCallbacks)
        batteryWatchRunnable?.let(handler::removeCallbacks)
        badgeTimer?.let(handler::removeCallbacks)
        bubbleTimer?.let(handler::removeCallbacks)
        wanderResumeRunnable?.let(handler::removeCallbacks)
        deltaTimer?.let(handler::removeCallbacks)
        try {
            unregisterReceiver(batteryReceiver)
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        try {
            wm.removeView(rootView)
        } catch (_: Exception) {
        }
        hideBubbleWindow()
        hideDeltaWindow()
        super.onDestroy()
    }

    // ── 插件基础能力（兼容层 ①）：App 只负责"怎么做" ─────────
    // 授权 / 敏感二次确认 / 审计均在插件宿主侧完成，App 侧不参与。
// 脚本运行在后台线程，因此 UI/Activity 操作统一 post 回主线程。

    private val pluginBridge = object : PluginAppBridge {
        override fun say(text: String) {
            if (text.isBlank()) return
            handler.post { showBubbleWindow(text, clearBadge = true) }
        }

        override fun notify(title: String, text: String) {
            handler.post {
                try {
                    val nm = getSystemService(NotificationManager::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        nm.createNotificationChannel(
                            NotificationChannel(
                                PLUGIN_CHANNEL_ID,
                                getString(R.string.plugin_notif_channel),
                                // 无声、不弹横幅：插件通知不该打扰用户
                                // （规则型动作另有 1 秒硬下限兜底，见 RuleThrottle）
                                NotificationManager.IMPORTANCE_LOW
                            )
                        )
                        // 旧渠道已停用，删掉避免系统设置里出现两个同名"插件通知"
                        runCatching { nm.deleteNotificationChannel("whale_plugin_channel") }
                    }
                    val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Notification.Builder(this@WhalePetService, PLUGIN_CHANNEL_ID)
                            .setContentTitle(title.ifBlank { getString(R.string.app_name) })
                            .setContentText(text)
                            .setSmallIcon(R.drawable.ic_launcher_foreground)
                            .build()
                    } else {
                        @Suppress("DEPRECATION")
                        Notification.Builder(this@WhalePetService)
                            .setContentTitle(title.ifBlank { getString(R.string.app_name) })
                            .setContentText(text)
                            .setSmallIcon(R.drawable.ic_launcher_foreground)
                            .build()
                    }
                    nm.notify(PLUGIN_NOTIF_ID, n)
                } catch (_: Throwable) {
                }
            }
        }

        override fun vibrate(ms: Long) {
            handler.post {
                try {
                    val v = getSystemService(Vibrator::class.java)
                    val dur = ms.coerceIn(1L, 5000L)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(VibrationEffect.createOneShot(dur, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        v.vibrate(dur)
                    }
                } catch (_: Throwable) {
                }
            }
        }

        override fun openUrl(url: String): Boolean {
            val uri = try {
                Uri.parse(url)
            } catch (_: Throwable) {
                return false
            }
            // 只允许真正的网页链接：file:// 暴露本地文件、content:// 可读他人数据、
            // intent: 能间接拉起任意组件，都不该借"打开网页"这个名义触发。
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return false
            handler.post {
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Throwable) {
                }
            }
            return true
        }

        override fun openApp(pkg: String): Boolean {
            val intent = try {
                packageManager.getLaunchIntentForPackage(pkg)
            } catch (_: Throwable) {
                null
            } ?: return false
            handler.post {
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (_: Throwable) {
                }
            }
            return true
        }

        override fun shizuku(args: List<String>): String = try {
            // 白名单命令：只有以本应用包名为目标的那几条能通过 UserService 校验
            ShizukuHelper.execWhitelistedSync(this@WhalePetService, args)
        } catch (t: Throwable) {
            "执行异常：${t.message ?: t.javaClass.simpleName}"
        }

    }

    // ── 系统状态联动：低电量提示 / 息屏暂停游动 ────────────────
    private var batteryLow = false
    private var lastLowBatteryNotify = 0L
    private var batteryWatchRunnable: Runnable? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val i = intent ?: return
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val pct = if (scale > 0) level * 100 / scale else -1
            batteryLow = pct in 0..20 && !plugged
            // 插件事件：刚进入低电状态时触发一次
            if (batteryLow && !wasBatteryLow) {
                PluginEntry.onEvent(this@WhalePetService, "batteryLow", "{\"level\":$pct}")
            }
            wasBatteryLow = batteryLow
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                // 息屏时停止游动省电，亮屏恢复
                Intent.ACTION_SCREEN_OFF -> {
                    stopWander()
                    pauseBalanceWatch()
                    PluginEntry.onEvent(this@WhalePetService, "screenOff")
                }
                Intent.ACTION_SCREEN_ON -> {
                    startWander()
                    resumeBalanceWatch()
                    PluginEntry.onEvent(this@WhalePetService, "screenOn")
                }
            }
        }
    }

    private fun registerSystemReceivers() {
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            screenFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** 每 30 秒检查一次电量；低于 20% 且未充电时提示一句（10 分钟内不重复）。 */
    private fun scheduleBatteryWatch() {
        batteryWatchRunnable = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                if (batteryLow && now - lastLowBatteryNotify > 10 * 60 * 1000L) {
                    lastLowBatteryNotify = now
                    showBubbleWindow(getString(R.string.low_battery_line), clearBadge = false)
                }
                handler.postDelayed(this, 30_000L)
            }
        }
        handler.postDelayed(batteryWatchRunnable!!, 30_000L)
    }

    // ── 前台通知 ──────────────────────────────────────────────
    private fun startForegroundCompat() {
        val channelId = "whale_pet_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            // 前台服务必须挂通知（系统硬性要求），这里把打扰降到最低：
            // IMPORTANCE_MIN 不显示状态栏图标、无提示音/震动/角标。
            // 注意：渠道重要性一旦创建只能由用户修改，因此不删除重建（尊重用户设置）。
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = getString(R.string.notif_channel_desc)
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
            )
        }
        // 通知文案写着"点按打开设置"，就必须真的能点开（此前没有 contentIntent，点了没反应）
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .setContentIntent(contentIntent)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .setContentIntent(contentIntent)
                .build()
        }
        // Android 14+ 需要声明 specialUse 前台服务类型（Manifest 已声明）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(1, notification)
        }
    }

    // ── 悬浮窗构建：鲸鱼窗口（纯尺寸，可贴边）+ 独立气泡/余额窗 ──
    private lateinit var bubbleWin: View
    private lateinit var bubbleTv: TextView
    private lateinit var badgeBox: View
    private lateinit var badgeTv: TextView
    private lateinit var badgeShadowTv: TextView
    private lateinit var bubbleParams: WindowManager.LayoutParams

    // 金额变化提示（鲸鱼下方、纯文字、红/绿）
    private lateinit var deltaWin: View
    private lateinit var deltaTv: TextView
    private lateinit var deltaParams: WindowManager.LayoutParams
    private var deltaVisible = false
    private var deltaTimer: Runnable? = null
    private var bubbleVisible = false

    private fun buildOverlay() {
        val win = windowPx
        rootView = View.inflate(this, R.layout.overlay_whale, null)
        whaleImg = rootView.findViewById(R.id.whale_img)
        // 本体在窗口内居中绘制，四周留出点击缩放余量
        val pad = imgPad
        whaleImg.setPadding(pad, pad, pad, pad)
        // 首帧就按初始朝向绘制，避免启动瞬间由原图方向翻到运动方向
        whaleImg.scaleX = facing

        overlayParams = WindowManager.LayoutParams(
            win,
            win,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不加 FLAG_LAYOUT_NO_LIMITS：交给系统把窗口约束在安全区内，
            // 避免窗口被放进状态栏／底部手势条区域（NO_LIMITS 会允许超出屏幕边界）
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 初始位置：右下角
            val dm = resources.displayMetrics
            x = dm.widthPixels - win - (24 * dm.density).toInt()
            y = dm.heightPixels - win - (60 * dm.density).toInt()
        }
        whaleImg.setOnTouchListener(whaleTouch)
        // 权限可能在服务启动后、addView 之前被撤销（例如用户在系统设置里收回），
        // 这里兜底捕获 BadTokenException，避免整个服务崩溃。
        try {
            wm.addView(rootView, overlayParams)
        } catch (t: Throwable) {
            stopSelf()
            return
        }

        this.x = overlayParams.x.toFloat()
        this.y = overlayParams.y.toFloat()

        // 窗口 attach 后自校准真实可移动范围（见 calibrateBounds 注释）
        calibrateBounds()

        // 独立气泡窗（显示在鲸鱼头顶，不占鲸鱼窗口空间）
        bubbleWin = View.inflate(this, R.layout.overlay_bubble, null)
        bubbleTv = bubbleWin.findViewById(R.id.bubble_text)
        badgeBox = bubbleWin.findViewById(R.id.badge_box)
        badgeTv = bubbleWin.findViewById(R.id.badge_text)
        badgeShadowTv = bubbleWin.findViewById(R.id.badge_shadow_text)
        bubbleParams = WindowManager.LayoutParams(
            bubbleW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        // 金额变化提示窗（鲸鱼下方；纯文字无背景，字号与余额一致）
        deltaWin = View.inflate(this, R.layout.overlay_delta, null)
        deltaTv = deltaWin.findViewById(R.id.delta_text)
        deltaParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    // ── 触摸：拖动 + 单击/双击 ─────────────────────────────────
    private var downX = 0f
    private var downY = 0f
    private var lastTapAt = 0L
    private var singleTapRunnable: Runnable? = null
    private var moved = false
    // 当前朝向：1=原图方向，-1=镜像（点击缩放动画必须保留，不能硬编码覆盖）。
    // 初值与初始运动方向对齐（angle=-0.4 → cos 为正 → 向右 → wantFacing=-1），
    // 否则启动第一步会触发一次翻转动画（用户反馈"启动时一定翻转一下"）。
    private var facing = -1f
    // 当前速度（供 clamp 反弹用）
    private var curVx = 0f
    private var curVy = 0f

    private val whaleTouch = View.OnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                moved = false
                downX = event.rawX
                downY = event.rawY
                // 缩放时保留朝向：scaleX = facing * 1.12
                v.animate().scaleX(facing * 1.12f).scaleY(1.12f).setDuration(120).start()
                v.performClick()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return@OnTouchListener true
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (abs(dx) + abs(dy) > 12) moved = true
                downX = event.rawX
                downY = event.rawY
                // 统一走 x/y 再 clamp（避免与漫游逻辑分叉）
                x += dx
                y += dy
                clamp()
                overlayParams.x = x.roundToInt()
                overlayParams.y = y.roundToInt()
                safeUpdateLayout()
                // 只有气泡或余额实际可见时才需要更新气泡窗布局；
                // 窗口常驻（复用）但内容隐藏时，每帧多做一次 binder 调用是浪费
                if (bubbleVisible && (bubbleTv.visibility == View.VISIBLE ||
                        badgeBox.visibility == View.VISIBLE)
                ) {
                    positionBubbleWindow()
                }
                if (deltaVisible) positionDeltaWindow()
                true
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                // 缩放结束恢复原朝向
                v.animate().scaleX(facing).scaleY(1f).setDuration(120).start()
                if (moved) {
                    // 拖动结束：不触发点击；若贴着边界立即朝内弹开，避免停顿后再慢慢转身
                    kickOffBoundary()
                } else {
                    handleTap()
                }
                v.performClick()
                true
            }
            // 系统手势/来电等打断触摸时必须复位拖动状态，否则鲸鱼会卡死不再游动。
            // 底部小白条区域上滑会被系统手势抢走触摸（收到 ACTION_CANCEL 而非 ACTION_UP），
            // 因此这里同样要执行"贴边立即弹开"，否则只有底部会出现松手后停住。
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                moved = false
                v.animate().scaleX(facing).scaleY(1f).setDuration(120).start()
                kickOffBoundary()
                true
            }
            else -> false
        }
    }

    private fun handleTap() {
        val now = System.currentTimeMillis()
        if (now - lastTapAt < 300) {
            // 双击：查余额
            singleTapRunnable?.let(handler::removeCallbacks)
            lastTapAt = 0
            PluginEntry.onEvent(this@WhalePetService, "doubleTap")
            showBalanceAsync()
        } else {
            lastTapAt = now
            singleTapRunnable?.let(handler::removeCallbacks)
            singleTapRunnable = Runnable {
                sayLine()
                PluginEntry.onEvent(this@WhalePetService, "tap")
            }
            handler.postDelayed(singleTapRunnable!!, 300)
        }
    }

    /** 最近说过的台词（最多 2 条）：同一条至少要隔两句才会再次出现。 */
    private val recentLines = ArrayDeque<String>()

    /** 从用户台词池随机说一句（每次读取，设置页改动即时生效）。 */
    private fun sayLine() {
        val pool = WhaleLines.load(this)
        if (pool.isEmpty()) return
        showBubbleWindow(pickLine(pool), clearBadge = true)
    }

    /**
     * 选下一句：在「不在最近 2 条内」的台词中随机；
     * 台词池不足 3 条时无法完全避免，退化为全体随机。
     */
    private fun pickLine(pool: List<String>): String {
        if (pool.size <= 1) return pool.first()
        val blocked = recentLines.toSet()
        val candidates = pool.filter { it !in blocked }
        val chosen = if (candidates.isNotEmpty()) candidates.random() else pool.random()
        recentLines.addLast(chosen)
        while (recentLines.size > 2) recentLines.removeFirst()
        return chosen
    }

    /** 显示/更新独立气泡窗（定位在鲸鱼头顶上方），返回前清掉旧定时器。 */
    private fun showBubbleWindow(text: String, clearBadge: Boolean, durationMs: Long = 2_200L) {
        bubbleTv.text = text
        bubbleTv.visibility = View.VISIBLE
        if (clearBadge) badgeBox.visibility = View.INVISIBLE
        positionBubbleWindow()
        if (!bubbleVisible) {
            try {
                wm.addView(bubbleWin, bubbleParams)
                bubbleVisible = true
                // 主动测量，让 positionBubbleWindow 拿到真实内容高度
                bubbleWin.measure(
                    View.MeasureSpec.makeMeasureSpec(bubbleW, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                positionBubbleWindow()
            } catch (_: Exception) {
            }
        }
        bubbleTimer?.let(handler::removeCallbacks)
        bubbleTimer = Runnable { hideBubbleWindow() }
        handler.postDelayed(bubbleTimer!!, durationMs)
    }

    /** 把气泡窗放到鲸鱼头顶正上方（水平居中）。 */
    private fun positionBubbleWindow() {
        val dm = resources.displayMetrics
        val bw = bubbleW
        // 气泡窗中心对准鲸鱼中心（本体在窗口内居中）
        val centerX = x + windowPx / 2f
        bubbleParams.x = (centerX - bw / 2f).toInt().coerceIn(0, dm.widthPixels - bw)
        // 用实际内容高度（WRAP_CONTENT 布局后），气泡窗底部贴紧鲸鱼本体顶部（留 3dp 间隙）
        val contentH = if (bubbleWin.height > 0) bubbleWin.height
            else (60 * dm.density).toInt()
        bubbleParams.y = (y + imgPad - contentH - (3 * dm.density)).toInt()
            .coerceAtLeast(0)
        try {
            runCatching { wm.updateViewLayout(bubbleWin, bubbleParams) }
        } catch (_: Exception) {
        }
    }

    /**
     * 显示本次余额变化金额（鲸鱼下方、纯文字、无气泡背景）。
     *
     * 显示时长与台词一致（约 2.2 秒），结束前淡出。红色为扣款（带负号），
     * 绿色为增加（带正号）；字号与余额显示相同（12sp 加粗）。
     */
    private fun showDelta(text: String, color: Int) {
        try {
            deltaTv.text = text
            deltaTv.setTextColor(color)
            deltaTv.alpha = 1f
            if (!deltaVisible) {
                wm.addView(deltaWin, deltaParams)
                deltaVisible = true
            }
            positionDeltaWindow()
            deltaTimer?.let(handler::removeCallbacks)
            // 与台词一致：显示约 2.2 秒后直接消失（不做淡出动画）
            deltaTimer = Runnable { hideDeltaWindow() }
            handler.postDelayed(deltaTimer!!, 2_500L)
        } catch (_: Throwable) {
        }
    }

    /** 把金额提示定位到鲸鱼本体正下方（超出屏幕时上移，保证可见）。 */
    private fun positionDeltaWindow() {
        if (!deltaVisible) return
        val dm = resources.displayMetrics
        val bw = deltaWin.width.takeIf { it > 0 } ?: (60 * dm.density).toInt()
        val bh = deltaWin.height.takeIf { it > 0 } ?: (20 * dm.density).toInt()
        val bodyBottom = y + windowPx - imgPad
        val gap = 4 * dm.density
        deltaParams.x = (x + windowPx / 2f - bw / 2f).toInt()
            .coerceIn(0, (dm.widthPixels - bw).coerceAtLeast(0))
        deltaParams.y = (bodyBottom + gap).toInt()
            .coerceAtMost((dm.heightPixels - bh).coerceAtLeast(0))
        runCatching { wm.updateViewLayout(deltaWin, deltaParams) }
    }

    private fun hideDeltaWindow() {
        deltaTimer?.let(handler::removeCallbacks)
        deltaTimer = null
        if (deltaVisible) {
            try {
                wm.removeView(deltaWin)
            } catch (_: Throwable) {
            }
            deltaVisible = false
        }
    }

    private fun hideBubbleWindow() {
        bubbleTimer?.let(handler::removeCallbacks)
        bubbleTimer = null
        if (bubbleVisible) {
            try {
                wm.removeView(bubbleWin)
            } catch (_: Exception) {
            }
            bubbleVisible = false
        }
    }

    // ── 自主游动（角度制，防抖）───────────────────────────────
    private fun startWander() {
        if (wanderRunning) return
        wanderResumeRunnable?.let(handler::removeCallbacks)
        wanderRunning = true
        lastFrameNanos = 0L
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!wanderRunning) return
                // 按真实帧间隔缩放位移：60Hz 时约 1.0；120Hz 屏不会变快；
                // 掉帧或系统降频时单帧位移上限 3 倍，避免"一跳很远"。
                val dtMs = if (lastFrameNanos == 0L) {
                    16.67f
                } else {
                    (frameTimeNanos - lastFrameNanos) / 1_000_000f
                }
                lastFrameNanos = frameTimeNanos
                val scale = (dtMs / 16.67f).coerceIn(0.25f, 3f)
                stepWander(scale)
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        frameCallback = cb
        Choreographer.getInstance().postFrameCallback(cb)
    }

    /** 暂停游动（息屏 / 服务销毁），避免无谓的动画与重绘。 */
    private fun stopWander() {
        wanderRunning = false
        frameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        frameCallback = null
    }

    /**
     * 安全地更新鲸鱼主窗口的布局。
     *
     * 悬浮窗权限被撤销、或窗口已被系统移除后，updateViewLayout 会抛异常；
     * 而它由游动循环每帧调用 → 未捕获会直接把服务崩掉（权限撤销 = 必崩）。
     * 这里兜底：失败即停止游动并结束服务，等用户重新授权后再启动。
     */
    /**
     * 安全地更新鲸鱼主窗口的布局。
     *
     * 权限被撤销或窗口被移除时 updateViewLayout 会抛异常，而它由游动循环每帧调用；
     * 这里兜底：失败即停止游动（避免每帧抛异常），但**不结束服务**——
     * 窗口可能只是暂时不可用，应由用户手动停止/重启，而不是被代码杀掉。
     */
    private fun safeUpdateLayout() {
        try {
            wm.updateViewLayout(rootView, overlayParams)
        } catch (t: Throwable) {
            stopWander()
            val now = System.currentTimeMillis()
            if (now - layoutFailLoggedAt >= 60_000L) {
                layoutFailLoggedAt = now
                dumpDebug("updateLayout failed: ${t.javaClass.simpleName}")
            }
            // 自动恢复：窗口可能只是暂时不可用。5 秒后若服务仍在且亮屏就重启游动，
            // 否则会一直静止到用户息屏再亮屏（表现为「卡死一段时间后恢复」）。
            scheduleWanderResume()
        }
    }

    /** 安排一次游动恢复尝试（布局失败后使用，避免永久静止）。 */
    private fun scheduleWanderResume() {
        wanderResumeRunnable?.let(handler::removeCallbacks)
        wanderResumeRunnable = Runnable {
            if (isRunning && !wanderRunning && isScreenOn()) {
                startWander()
                dumpDebug("wander resumed after layout failure")
            }
        }
        handler.postDelayed(wanderResumeRunnable!!, 5_000L)
    }

    private fun stepWander(scale: Float = 1f) {
        if (dragging) return
        val speed = speedPx
        // 随机偏转幅度收敛（0.06 → 0.02）：低速时最容易看出抖动，进一步平滑
        angle += (Random.nextFloat() - 0.5f) * 0.02f
        if (Random.nextFloat() < 0.0025f) {
            angle += (Random.nextFloat() - 0.5f) * (Math.PI / 2).toFloat()
        }
        curVx = cos(angle.toDouble()).toFloat() * speed
        curVy = sin(angle.toDouble()).toFloat() * speed
        x += curVx * scale
        y += curVy * scale
        clamp()
        // 四舍五入取整：避免亚像素累加被截断造成逐帧 ±1px 跳动
        overlayParams.x = x.roundToInt()
        overlayParams.y = y.roundToInt()
        safeUpdateLayout()
        if (bubbleVisible) positionBubbleWindow()
        // 金额变化提示跟随鲸鱼移动（此前只加在拖动分支，游动时不动）
        if (deltaVisible) positionDeltaWindow()
        // 临时诊断：每约 30 秒记录一行（按时间累计，避免高刷屏上触发过频）
        debugTickMs += scale * 16.67f
        if (debugTickMs >= 30_000f) {
            debugTickMs = 0f
            dumpDebug("tick")
        }
        // 朝向：滞回阈值提高（0.2 → 0.35 倍速）+ 动画过渡，
        // 杜绝速度在阈值附近抖动时朝向反复翻转造成的闪烁
        val wantFacing = if (curVx < 0) 1f else -1f
        if (abs(curVx) > speed * 0.35f && wantFacing != facing) {
            facing = wantFacing
            whaleImg.animate().scaleX(facing).setDuration(150).start()
        }
    }

    // ── 窗口可移动范围（安全区）────────────────────────────
    // 实测发现（whale-debug.txt）：overlay 窗口的坐标原点不在物理屏幕左上角，
    // 而在状态栏下方 —— param.y 与 getLocationOnScreen().y 恒定相差一个状态栏
    // 高度（该机为 162px）。若直接按物理屏幕算边界，底部会多出这一偏移量：
    // 窗口已被系统夹住，位置变量却仍判定"在界内"，于是鲸鱼在底部完全静止
    // 一段时间才恢复（正是用户看到的现象）。
    // 处理：先实测该偏移 offset，再把物理安全区换算到 param 坐标。
    private var boundsReady = false
    private var boundMinX = 0
    private var boundMinY = 0
    private var boundMaxX = 0
    private var boundMaxY = 0
    private var offsetX = 0
    private var offsetY = 0
    private var insetsBottomPx = 0

    /** 用系统 API + 实测偏移，计算 param 坐标下的可移动范围。 */
    private fun computeSafeBounds() {
        val dm = resources.displayMetrics
        var left = 0
        var top = 0
        var right = dm.widthPixels
        var bottom = dm.heightPixels
        insetsBottomPx = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val uiCtx = createWindowContext(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    null as android.os.Bundle?
                )
                val metrics = uiCtx.getSystemService(WindowManager::class.java).currentWindowMetrics
                val b = metrics.bounds
                if (b.width() > 0 && b.height() > 0) {
                    val ins = metrics.windowInsets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                    )
                    left = b.left + ins.left
                    top = b.top + ins.top
                    right = b.right - ins.right
                    bottom = b.bottom - ins.bottom
                    insetsBottomPx = ins.bottom
                }
            } catch (_: Throwable) {
                // 拿不到就沿用 displayMetrics
            }
        }
        // 状态栏：insets 常常取不到；实测的坐标偏移量本身约等于状态栏高度
        if (top <= 0 && offsetY > 0) top = offsetY
        // 底部手势条/导航栏：insets 为 0 时用保守余量兜底，避免鲸鱼被小白条遮住
        if (insetsBottomPx <= 0) bottom -= (24 * dm.density).toInt()
        // 物理坐标 → param 坐标（减去坐标原点偏移）
        boundMinX = left - offsetX
        boundMinY = top - offsetY
        boundMaxX = right - offsetX - windowPx
        boundMaxY = bottom - offsetY - windowPx
    }

    /**
     * 校准：窗口先摆在中间位置，用 getLocationOnScreen 与 param 的差值求出
     * 坐标系偏移，再据此换算可移动范围（校准期间窗口透明，避免闪烁）。
     */
    /**
     * 校准：窗口先摆到探针位置，用 getLocationOnScreen 与 param 的差值求出
     * 坐标系偏移，再据此换算可移动范围（校准期间窗口透明，避免闪烁）。
     *
     * 注意：窗口刚 addView 时 updateViewLayout 可能失败（尚未 attach）。
     * 这里先立即尝试，失败则下一帧重试一次，仍失败**降级**为按屏幕尺寸估算——
     * **绝不能因此结束服务**（1.1.44 曾把失败路径写成 stopSelf，导致桌宠启动即退出）。
     */
    private fun calibrateBounds() {
        appendDebugLine(
            "=== whale debug start v${packageManager.getPackageInfo(packageName, 0).versionName} ===\n"
        )
        overlayParams.alpha = 0f
        val probeX = 200
        val probeY = 600
        if (moveToProbe(probeX, probeY)) {
            measureWithProbe(probeX, probeY)
        } else {
            // 窗口可能尚未 attach：下一帧再试一次
            rootView.post {
                if (moveToProbe(probeX, probeY, logFailure = true)) {
                    measureWithProbe(probeX, probeY)
                } else {
                    computeSafeBounds()
                    boundsReady = true
                    dumpDebug("calibrate: fallback to computed bounds")
                }
            }
        }
    }

    /** 把窗口移到探针位置；成功返回 true。 */
    private fun moveToProbe(x0: Int, y0: Int, logFailure: Boolean = false): Boolean {
        return try {
            overlayParams.x = x0
            overlayParams.y = y0
            wm.updateViewLayout(rootView, overlayParams)
            true
        } catch (t: Throwable) {
            if (logFailure) dumpDebug("calibrate: move failed ${t.javaClass.simpleName}")
            false
        }
    }

    /**
     * 读取实测偏移并换算边界。
     *
     * `updateViewLayout` 有时会"返回成功但窗口并未移动到探针位置"（刚添加的窗口尚未完成
     * 第一次 layout 时尤甚）。此时测出的偏移会异常大，若直接置 0，会少算约一个状态栏的高度，
     * 表现为"桌宠启动位置略低"。因此：偏移异常时**重试移动并再测一帧**，最多 2 次；
     * 仍失败则用系统状态栏高度兜底（窗口原点通常就在状态栏下方）。
     */
    private fun measureWithProbe(probeX: Int, probeY: Int, attempt: Int = 0) {
        rootView.postOnAnimation {
            val loc = IntArray(2)
            rootView.getLocationOnScreen(loc)
            val dx = loc[0] - probeX
            val dy = loc[1] - probeY
            val limit = windowPx * 2
            if ((abs(dx) > limit || abs(dy) > limit) && attempt < 2) {
                // 窗口还没真正移动到位：再试一次
                moveToProbe(probeX, probeY)
                measureWithProbe(probeX, probeY, attempt + 1)
                return@postOnAnimation
            }
            val calibrated = abs(dx) <= limit && abs(dy) <= limit
            offsetX = if (calibrated) dx else 0
            offsetY = if (calibrated) dy else statusBarHeightPx()
            if (!calibrated) {
                dumpDebug("calibrate: offset fallback to statusbar=${offsetY}px")
            }
            computeSafeBounds()
            boundsReady = boundMaxX > boundMinX && boundMaxY > boundMinY
            // 初始位置：有启动落点就落在主页图片位置，否则右下角（与旧版观感一致）
            val dm = resources.displayMetrics
            if (pendingStartX >= 0 && pendingStartY >= 0) {
                x = (pendingStartX - windowPx / 2f).coerceIn(boundMinX.toFloat(), boundMaxX.toFloat())
                y = (pendingStartY - offsetY - windowPx / 2f).coerceIn(boundMinY.toFloat(), boundMaxY.toFloat())
                pendingStartX = -1
                pendingStartY = -1
            } else {
                x = (boundMaxX - (24 * dm.density).toInt()).toFloat()
                y = (boundMaxY - (60 * dm.density).toInt()).toFloat()
            }
            overlayParams.x = x.roundToInt()
            overlayParams.y = y.roundToInt()
            overlayParams.alpha = 1f
            runCatching { wm.updateViewLayout(rootView, overlayParams) }
            dumpDebug("calibrated")
        }
    }

    private fun clamp() {
        if (!boundsReady) return                     // 自校准完成前不做边界限制
        val edge = (2 * resources.displayMetrics.density).toInt()
        val minX = boundMinX + edge
        val minY = boundMinY + edge
        val maxX = boundMaxX - edge
        val maxY = boundMaxY - edge

        val halfPi = (Math.PI / 2).toFloat()
        val limit = (Math.PI / 3).toFloat()          // 60°
        var bounced = false                          // 角部同时越界时只反射一次

        if (x < minX) {                              // 撞左 → 向右
            x = minX.toFloat()
            if (!bounced) {
                angle = normalizeAngle(Math.PI.toFloat() - angle).coerceIn(-limit, limit)
                bounced = true
            }
        } else if (x > maxX) {                       // 撞右 → 向左
            x = maxX.toFloat()
            if (!bounced) {
                val a = normalizeAngle(Math.PI.toFloat() - angle)
                angle = if (a >= 0) {
                    a.coerceIn(Math.PI.toFloat() - limit, Math.PI.toFloat())
                } else {
                    a.coerceIn(-Math.PI.toFloat(), -Math.PI.toFloat() + limit)
                }
                bounced = true
            }
        }

        if (y < minY) {                              // 撞顶 → 向下
            y = minY.toFloat()
            if (!bounced) {
                angle = normalizeAngle(-angle).coerceIn(halfPi - limit, halfPi + limit)
                bounced = true
            }
        } else if (y > maxY) {                       // 撞底 → 向上
            y = maxY.toFloat()
            if (!bounced) {
                angle = normalizeAngle(-angle).coerceIn(-halfPi - limit, -halfPi + limit)
                bounced = true
            }
        }

        if (bounced) {
            applyAngle()
            dumpDebug("bounce")
        }

        // 近边界软排斥：避免以接近水平的角度长期贴着边游动（看起来像滑行）
        softRepel(minX.toFloat(), minY.toFloat(), maxX.toFloat(), maxY.toFloat())
    }

    /**
     * 边界软排斥：靠近边界时把方向逐渐转向内侧。
     *
     * 仅靠硬边界反弹无法避免"贴着底边水平游动"：鲸鱼可以长期保持接近水平的角度
     * 在边界附近移动（y 变化≈0，永远不触发反弹），视觉上就是贴边滑行。
     * 这里在距边界一定范围内持续施加很小的向内偏转，让它自然离开边缘。
     */
    private fun softRepel(minX: Float, minY: Float, maxX: Float, maxY: Float) {
        val band = windowPx * 0.35f                       // 感知带宽度（过大会让鲸鱼不敢靠近边缘）
        if (band <= 0f) return
        val halfPi = (Math.PI / 2).toFloat()
        val k = 0.05f                                     // 每帧最大偏转比例（过大会显得被"吸"住）
        var changed = false

        val tBottom = ((y - (maxY - band)) / band).coerceIn(0f, 1f)
        if (tBottom > 0f) { angle = turnToward(angle, -halfPi, k * tBottom); changed = true }
        val tTop = (((minY + band) - y) / band).coerceIn(0f, 1f)
        if (tTop > 0f) { angle = turnToward(angle, halfPi, k * tTop); changed = true }
        val tRight = ((x - (maxX - band)) / band).coerceIn(0f, 1f)
        if (tRight > 0f) { angle = turnToward(angle, Math.PI.toFloat(), k * tRight); changed = true }
        val tLeft = (((minX + band) - x) / band).coerceIn(0f, 1f)
        if (tLeft > 0f) { angle = turnToward(angle, 0f, k * tLeft); changed = true }

        if (changed) applyAngle()
    }

    /**
     * 松手时若贴着边界，立即把方向设为朝内。
     *
     * 否则拖到底部后只能靠 softRepel 每帧 3% 慢慢转身，从水平转到朝上约需 1.5 秒，
     * 这段时间垂直速度接近 0，看起来就是"停住不动、过一会儿才弹开"。
     */
    private fun kickOffBoundary() {
        if (!boundsReady) return
        val near = windowPx * 0.9f                        // 判定"贴边"的范围（放宽，覆盖拖到边缘附近即松手）
        val halfPi = (Math.PI / 2).toFloat()
        val spread = (Math.PI / 6).toFloat()                  // ±30° 随机，避免每次都是正角
        val jitter = (Random.nextFloat() - 0.5f) * 2f * spread
        val target: Float? = when {
            y > boundMaxY - near -> -halfPi + jitter          // 贴底 → 向上
            y < boundMinY + near -> halfPi + jitter           // 贴顶 → 向下
            x > boundMaxX - near -> Math.PI.toFloat() + jitter // 贴右 → 向左
            x < boundMinX + near -> jitter                    // 贴左 → 向右
            else -> null
        }
        if (target != null) {
            angle = normalizeAngle(target)
            applyAngle()
            dumpDebug("kick")
        }
    }

    /** 把 from 朝 to 方向旋转 factor 比例（走最短角差）。 */
    private fun turnToward(from: Float, to: Float, factor: Float): Float {
        val diff = normalizeAngle(to - from)
        return normalizeAngle(from + diff * factor.coerceIn(0f, 1f))
    }

    /** 由当前方向角重算速度分量（反弹后立即生效）。 */
    private fun applyAngle() {
        curVx = cos(angle.toDouble()).toFloat() * speedPx
        curVy = sin(angle.toDouble()).toFloat() * speedPx
    }

    /** 把角度归一化到 (-π, π]。 */
    private fun normalizeAngle(a: Float): Float {
        val twoPi = (Math.PI * 2).toFloat()
        var r = a
        while (r <= -Math.PI.toFloat()) r += twoPi
        while (r > Math.PI.toFloat()) r -= twoPi
        return r
    }

    // ── 运行时诊断（写入 Download/whale-debug.txt，便于问题追溯）──
    // 文件写入规则（同名变体归并、1MB 自清除、只处理本应用文件）集中在
    // common/MediaStoreLog，与插件审计日志共用同一套实现。
    private val DEBUG_FILE_NAME = "whale-debug.txt"

    /** 记录一行运行时状态到 Download/whale-debug.txt。 */
    private fun dumpDebug(tag: String) {
        try {
            val loc = IntArray(2)
            rootView.getLocationOnScreen(loc)
            val dm = resources.displayMetrics
            val line = "t=${System.currentTimeMillis()} tag=$tag ready=$boundsReady " +
                "dm=${dm.widthPixels}x${dm.heightPixels} " +
                "off=[$offsetX,$offsetY] insB=$insetsBottomPx " +
                "bounds=[$boundMinX,$boundMinY,$boundMaxX,$boundMaxY] " +
                "param=[${overlayParams.x},${overlayParams.y}] loc=[${loc[0]},${loc[1]}] " +
                "xy=[${x.roundToInt()},${y.roundToInt()}] " +
                "angle=${String.format(Locale.ROOT, "%.3f", angle)} drag=$dragging\n"
            appendDebugLine(line)
        } catch (_: Throwable) {
        }
    }

    /** 追加写入诊断文件；超过 1MB 时由 MediaStoreLog 自动清空重写。 */
    private fun appendDebugLine(line: String) {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Throwable) {
            ""
        }
        MediaStoreLog.append(
            this, DEBUG_FILE_NAME, line,
            "=== rolled over, history cleared (max 1MB) v$version ==="
        )
    }

    /** 屏幕是否点亮：熄屏时不查询余额（省电、省流量），亮屏由广播恢复。 */
    private fun isScreenOn(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true

    // ── 余额：5 分钟轮询 + 头顶上浮渐隐 0.8s ──────────────────
    private fun scheduleBalance() {
        // 只在启动（及亮屏恢复）时显示一次余额；不再每 5 分钟重复弹窗（用户要求）。
        // 手动查余额仍可双击鲸鱼娘。
        balanceRunnable = object : Runnable {
            override fun run() {
                showBalanceAsync()
            }
        }
        // 熄屏状态下启动服务时不发请求，等 ACTION_SCREEN_ON 再恢复
        if (isScreenOn()) handler.postDelayed(balanceRunnable!!, 3000L)
    }

    /**
     * 熄屏：暂停余额展示轮询与余额消耗检测。
     * 两者都只是不发起网络请求，间隔本身不变（消耗检测仍是 3 秒）。
     */
    private fun pauseBalanceWatch() {
        balanceRunnable?.let(handler::removeCallbacks)
        tokenWatchRunnable?.let(handler::removeCallbacks)
    }

    /** 亮屏：恢复余额展示轮询与 3 秒消耗检测。 */
    private fun resumeBalanceWatch() {
        balanceRunnable?.let {
            handler.removeCallbacks(it)
            handler.postDelayed(it, 3000L)
        }
        tokenWatchRunnable?.let {
            handler.removeCallbacks(it)
            handler.postDelayed(it, TOKEN_WATCH_INTERVAL_MS)
        }
    }

    // ── 余额消耗检测：每 3 秒静默查询（结果不显示）───────────

    /**
     * 余额变化探测：无变化每 5 秒一次；有变化时冒台词 + 显示红/绿金额，随后冷却 1 分钟。
     * 余额本身不再周期显示（用户要求取消每 5 分钟弹窗），双击可手动查。
     * 熄屏期间暂停发起请求（见 pauseBalanceWatch），亮屏恢复。
     */
    private fun scheduleTokenWatch() {
        tokenWatchRunnable = object : Runnable {
            override fun run() = checkTokenChange()
        }
        // 熄屏状态下启动服务时不发请求，等 ACTION_SCREEN_ON 再恢复
        if (isScreenOn()) handler.postDelayed(tokenWatchRunnable!!, TOKEN_WATCH_INTERVAL_MS)
    }

    /** 安排下一次探测：有变化后冷却 1 分钟，无变化继续 5 秒。 */
    private fun scheduleNextTokenWatch(delayMs: Long) {
        tokenWatchRunnable?.let {
            handler.removeCallbacks(it)
            handler.postDelayed(it, delayMs)
        }
    }

    /**
     * 余额变化探测。
     *
     * 节奏（用户指定）：
     *  - 无变化：每 5 秒探测一次
     *  - 有变化：先弹「正在偷吃用户token...」（仅余额**减少**时），再显示一次余额，
     *    然后冷却 1 分钟再探测
     *  - 余额**增加**（充值）不弹"偷吃"，只把新余额显示一次
     *  - 查询失败按"无变化"处理（继续 5 秒，失败日志另有节流）
     */
    private fun checkTokenChange() {
        val key = MainActivity.Prefs.getApiKey(this)
        if (key.isBlank()) {
            // 诊断：只提示一次，避免刷屏
            if (!debugTokenNoKey) {
                debugTokenNoKey = true
                dumpDebug("tokenWatch: API Key 为空，跳过检测")
            }
            scheduleNextTokenWatch(TOKEN_WATCH_INTERVAL_MS)
            return
        }
        // 上一次请求还没回来就跳过本轮（超时 8s 长于间隔 5s，避免请求叠加）
        if (tokenWatchInFlight) {
            scheduleNextTokenWatch(TOKEN_WATCH_INTERVAL_MS)
            return
        }
        tokenWatchInFlight = true
        val seq = ++tokenWatchSeq
        BalanceFetcher.fetchAmountAsync(key) { amount ->
            // 兜底：丢弃过期响应（seq 不匹配说明已有更新的请求）
            if (seq != tokenWatchSeq) return@fetchAmountAsync
            tokenWatchInFlight = false

            if (amount == null) {
                // 失败：按"无变化"继续 5 秒；日志节流避免断网刷屏
                val now = System.currentTimeMillis()
                if (now - lastTokenFailLogAt >= TOKEN_FAIL_LOG_INTERVAL_MS) {
                    lastTokenFailLogAt = now
                    dumpDebug("tokenWatch: 查询失败（保持基准不变）")
                }
                scheduleNextTokenWatch(TOKEN_WATCH_INTERVAL_MS)
                return@fetchAmountAsync
            }

            val prev = lastBalanceValue
            val prevCurrency = lastBalanceCurrency
            val changed = prev != null && prevCurrency == amount.currency && amount.value != prev
            val decreased = changed && amount.value < prev
            lastBalanceValue = amount.value
            lastBalanceCurrency = amount.currency

            if (!changed) {
                // 无变化：继续 5 秒探测
                scheduleNextTokenWatch(TOKEN_WATCH_INTERVAL_MS)
                return@fetchAmountAsync
            }

            val balanceText = BalanceFetcher.formatAmount(amount)
            // 本次变化金额（减少为负、增加为正），保留两位小数
            val deltaAmount = amount.value - (prev ?: amount.value)
            if (decreased) {
                // 余额减少：从「消费」类台词随机说一句（默认含「正在吃掉用户token...」）
                val line = WhaleLines.load(this, WhaleLines.Type.LOSS).randomOrNull()
                    ?: getString(R.string.token_nibble_line)
                showBubbleWindow(line, clearBadge = true, durationMs = 2_500L)
                handler.postDelayed({ showBadge(balanceText) }, TOKEN_BUBBLE_DURATION_MS)
                // 鲸鱼下方：红色 + 负号显示本次扣款
                showDelta(
                    getString(
                        R.string.delta_loss_format,
                        String.format(Locale.ROOT, "%.2f", -deltaAmount)
                    ),
                    DELTA_COLOR_LOSS
                )
                // 插件事件：余额减少
                PluginEntry.onEvent(
                    this@WhalePetService, "balanceDecreased",
                    "{\"value\":${amount.value},\"prev\":${prev ?: 0.0}}"
                )
            } else {
                // 余额增加：从「充值」类台词随机说一句，并显示新余额与增加金额
                val line = WhaleLines.load(this, WhaleLines.Type.GAIN).randomOrNull()
                    ?: getString(R.string.token_nibble_line)
                showBubbleWindow(line, clearBadge = true, durationMs = 2_500L)
                handler.postDelayed({ showBadge(balanceText) }, TOKEN_BUBBLE_DURATION_MS)
                showDelta(
                    getString(
                        R.string.delta_gain_format,
                        String.format(Locale.ROOT, "%.2f", deltaAmount)
                    ),
                    DELTA_COLOR_GAIN
                )
            }
            dumpDebug("tokenWatch: changed=true decreased=$decreased cur=${amount.currency}")
            // 有变化后冷却 1 分钟
            scheduleNextTokenWatch(TOKEN_COOLDOWN_AFTER_CHANGE_MS)
        }
    }

    private fun showBalanceAsync() {
        val key = MainActivity.Prefs.getApiKey(this)
        if (key.isBlank()) return
        BalanceFetcher.fetchAsync(key) { text ->
            showBadge(text)
        }
    }

    private fun showBadge(text: String) {
        // 双击显示余额时，隐藏残留的单击气泡（避免气泡+余额同时出现）
        bubbleTv.visibility = View.INVISIBLE
        bubbleTv.animate().cancel()
        bubbleTv.alpha = 1f
        badgeTv.text = text
        badgeShadowTv.text = text
        badgeTv.setTextColor(0xFFA2B4DD.toInt())
        // 复位到出现前（整组一起动画，主文字与浅灰副本保持相对偏移）
        badgeBox.alpha = 0f
        badgeBox.translationY = 12 * resources.displayMetrics.density
        badgeBox.visibility = View.VISIBLE
        positionBubbleWindow()
        if (!bubbleVisible) {
            try {
                wm.addView(bubbleWin, bubbleParams)
                bubbleVisible = true
                bubbleWin.measure(
                    View.MeasureSpec.makeMeasureSpec(bubbleW, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                positionBubbleWindow()
            } catch (_: Exception) {
            }
        }
        // 上浮渐隐 0.8s
        badgeBox.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .start()
        badgeTimer?.let(handler::removeCallbacks)
        badgeTimer = Runnable {
            badgeBox.animate()
                .alpha(0f)
                .translationY(-12 * resources.displayMetrics.density)
                .setDuration(400)
                .start()
        }
        handler.postDelayed(badgeTimer!!, 400)
    }
}
