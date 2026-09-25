package com.dsha.whalepet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

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

    private lateinit var wm: WindowManager
    private lateinit var rootView: View
    private lateinit var overlayParams: WindowManager.LayoutParams
    private lateinit var whaleImg: ImageView

    private val handler = Handler(Looper.getMainLooper())
    private var wanderRunnable: Runnable? = null
    private var wanderRunning = false
    private var balanceRunnable: Runnable? = null
    private var badgeTimer: Runnable? = null
    private var bubbleTimer: Runnable? = null

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
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundCompat()
        buildOverlay()
        startWander()
        scheduleBalance()
        registerSystemReceivers()
        scheduleBatteryWatch()
    }

    override fun onDestroy() {
        stopWander()
        balanceRunnable?.let(handler::removeCallbacks)
        batteryWatchRunnable?.let(handler::removeCallbacks)
        badgeTimer?.let(handler::removeCallbacks)
        bubbleTimer?.let(handler::removeCallbacks)
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
        super.onDestroy()
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
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                // 息屏时停止游动省电，亮屏恢复
                Intent.ACTION_SCREEN_OFF -> stopWander()
                Intent.ACTION_SCREEN_ON -> startWander()
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
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.notif_channel_desc)
                }
            )
        }
        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
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
    private var bubbleVisible = false

    private fun buildOverlay() {
        val win = windowPx
        rootView = View.inflate(this, R.layout.overlay_whale, null)
        whaleImg = rootView.findViewById(R.id.whale_img)
        // 本体在窗口内居中绘制，四周留出点击缩放余量
        val pad = imgPad
        whaleImg.setPadding(pad, pad, pad, pad)

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
        wm.addView(rootView, overlayParams)

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
    }

    // ── 触摸：拖动 + 单击/双击 ─────────────────────────────────
    private var downX = 0f
    private var downY = 0f
    private var lastTapAt = 0L
    private var singleTapRunnable: Runnable? = null
    private var moved = false
    // 当前朝向：1=朝右，-1=朝左（点击缩放动画必须保留，不能硬编码覆盖）
    private var facing = 1f
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
                wm.updateViewLayout(rootView, overlayParams)
                if (bubbleVisible) positionBubbleWindow()
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
            showBalanceAsync()
        } else {
            lastTapAt = now
            singleTapRunnable?.let(handler::removeCallbacks)
            singleTapRunnable = Runnable {
                sayLine()
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
    private fun showBubbleWindow(text: String, clearBadge: Boolean) {
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
        handler.postDelayed(bubbleTimer!!, 2200)
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
            wm.updateViewLayout(bubbleWin, bubbleParams)
        } catch (_: Exception) {
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
        wanderRunning = true
        wanderRunnable = object : Runnable {
            override fun run() {
                stepWander()
                handler.postDelayed(this, 16L)
            }
        }
        handler.postDelayed(wanderRunnable!!, 16L)
    }

    /** 暂停游动（息屏 / 服务销毁），避免无谓的动画与重绘。 */
    private fun stopWander() {
        wanderRunning = false
        wanderRunnable?.let(handler::removeCallbacks)
        wanderRunnable = null
    }

    private fun stepWander() {
        if (dragging) return
        val speed = speedPx
        // 随机偏转幅度收敛（0.06 → 0.02）：低速时最容易看出抖动，进一步平滑
        angle += (Random.nextFloat() - 0.5f) * 0.02f
        if (Random.nextFloat() < 0.0025f) {
            angle += (Random.nextFloat() - 0.5f) * (Math.PI / 2).toFloat()
        }
        curVx = cos(angle.toDouble()).toFloat() * speed
        curVy = sin(angle.toDouble()).toFloat() * speed
        x += curVx
        y += curVy
        clamp()
        // 四舍五入取整：避免亚像素累加被截断造成逐帧 ±1px 跳动
        overlayParams.x = x.roundToInt()
        overlayParams.y = y.roundToInt()
        wm.updateViewLayout(rootView, overlayParams)
        if (bubbleVisible) positionBubbleWindow()
        // 临时诊断：每约 30 秒记录一行（避免文件增长过快）
        if (++debugTick >= 1800) {
            debugTick = 0
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
    private fun calibrateBounds() {
        appendDebugLine(
            "=== whale debug start v${packageManager.getPackageInfo(packageName, 0).versionName} ===\n",
            truncate = false
        )
        overlayParams.alpha = 0f
        val probeX = 200
        val probeY = 600
        overlayParams.x = probeX
        overlayParams.y = probeY
        try {
            wm.updateViewLayout(rootView, overlayParams)
        } catch (_: Exception) {
            computeSafeBounds()
            boundsReady = true
            return
        }
        rootView.postOnAnimation {
            val loc = IntArray(2)
            rootView.getLocationOnScreen(loc)
            offsetX = loc[0] - probeX
            offsetY = loc[1] - probeY
            // 偏移量不应大于窗口尺寸，异常则视为无偏移
            val limit = windowPx * 2
            if (abs(offsetX) > limit || abs(offsetY) > limit) {
                offsetX = 0
                offsetY = 0
            }
            computeSafeBounds()
            boundsReady = boundMaxX > boundMinX && boundMaxY > boundMinY
            // 初始位置：右下角（与旧版观感一致）
            val dm = resources.displayMetrics
            x = (boundMaxX - (24 * dm.density).toInt()).toFloat()
            y = (boundMaxY - (60 * dm.density).toInt()).toFloat()
            overlayParams.x = x.roundToInt()
            overlayParams.y = y.roundToInt()
            overlayParams.alpha = 1f
            try {
                wm.updateViewLayout(rootView, overlayParams)
            } catch (_: Exception) {
            }
            dumpDebug("calibrated")
        }
    }

    /**
     * 边界处理：越界时吸附到边界，并按镜面反射调整方向角。
     *
     * 出射角限制为距切线至少 30°（即反射角落在 ±60° 带内）：
     * 入射角很平时（接近水平游动触底）如果只做等角反射，出射角也很平，
     * 鲸鱼会贴着底边横向滑行、看起来逐渐静止，直到随机扰动累积够大才猛地弹起。
     * 限制出射角后每次触边都会以自然但不拖沓的角度明确弹开。
     */
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
    // 自清除：文件接近 1MB 时自动清空重写，保证长期运行也不会无限膨胀。
    private var debugUri: Uri? = null
    private var debugTick = 0
    private val DEBUG_MAX_BYTES = 1 * 1024 * 1024
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
                "angle=${"%.3f".format(angle)} drag=$dragging\n"
            // 超过 1MB 上限则清空重写（自清除历史数据）
            val truncate = currentDebugSize() + line.length > DEBUG_MAX_BYTES
            appendDebugLine(line, truncate = truncate)
        } catch (_: Throwable) {
        }
    }

    /** 诊断文件当前字节数（拿不到按 0 处理）。 */
    private fun currentDebugSize(): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0L
        return try {
            val uri = ensureDebugUri() ?: return 0L
            contentResolver.query(uri, arrayOf(MediaStore.Downloads.SIZE), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    /** 取回或创建诊断文件对应的 MediaStore uri。 */
    private fun ensureDebugUri(): Uri? {
        debugUri?.let { return it }
        return try {
            val cr = contentResolver
            cr.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf(DEBUG_FILE_NAME),
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    debugUri = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)
                    )
                }
            }
            if (debugUri == null) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, DEBUG_FILE_NAME)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                debugUri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            }
            debugUri
        } catch (_: Throwable) {
            null
        }
    }

    /** 追加写入（MediaStore Downloads，Android 10+ 免权限）；truncate=true 时清空重写。 */
    private fun appendDebugLine(line: String, truncate: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val uri = ensureDebugUri() ?: return
            contentResolver.openOutputStream(uri, if (truncate) "wt" else "wa")?.use { os ->
                if (truncate) {
                    os.write(
                        "=== rolled over, history cleared (max 1MB) v${
                            packageManager.getPackageInfo(packageName, 0).versionName
                        } ===\n".toByteArray()
                    )
                }
                os.write(line.toByteArray())
            }
        } catch (_: Throwable) {
        }
    }

    // ── 余额：5 分钟轮询 + 头顶上浮渐隐 0.8s ──────────────────
    private fun scheduleBalance() {
        balanceRunnable = object : Runnable {
            override fun run() {
                showBalanceAsync()
                handler.postDelayed(this, 5 * 60 * 1000L)
            }
        }
        handler.postDelayed(balanceRunnable!!, 3000L)
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
