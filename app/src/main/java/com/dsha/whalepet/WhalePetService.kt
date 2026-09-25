package com.dsha.whalepet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
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
        scheduleWander()
        scheduleBalance()
    }

    override fun onDestroy() {
        wanderRunnable?.let(handler::removeCallbacks)
        balanceRunnable?.let(handler::removeCallbacks)
        badgeTimer?.let(handler::removeCallbacks)
        bubbleTimer?.let(handler::removeCallbacks)
        try {
            wm.removeView(rootView)
        } catch (_: Exception) {
        }
        hideBubbleWindow()
        super.onDestroy()
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
                    // 拖动结束：不触发点击
                } else {
                    handleTap()
                }
                v.performClick()
                true
            }
            // 系统手势/来电等打断触摸时必须复位拖动状态，否则鲸鱼会卡死不再游动
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                moved = false
                v.animate().scaleX(facing).scaleY(1f).setDuration(120).start()
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
    private fun scheduleWander() {
        wanderRunnable = object : Runnable {
            override fun run() {
                stepWander()
                handler.postDelayed(this, 16L)
            }
        }
        handler.postDelayed(wanderRunnable!!, 16L)
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
        // 朝向：滞回阈值提高（0.2 → 0.35 倍速）+ 动画过渡，
        // 杜绝速度在阈值附近抖动时朝向反复翻转造成的闪烁
        val wantFacing = if (curVx < 0) 1f else -1f
        if (abs(curVx) > speed * 0.35f && wantFacing != facing) {
            facing = wantFacing
            whaleImg.animate().scaleX(facing).setDuration(150).start()
        }
    }

    // ── 窗口可移动范围（窗口 attach 后自校准）────────────────
    // 各系统版本对 displayMetrics 与 overlay 窗口坐标的语义不一致：
    // 直接读屏幕尺寸常常把底部边界算大，窗口实际已被系统夹住、
    // 而位置变量仍在推进 —— 视觉上就是"贴着底边滑行、不触发反弹"。
    // 这里改为把窗口先推到极值位置，再用 getLocationOnScreen 读回系统夹取后的真实坐标。
    private var boundsReady = false
    private var boundMinX = 0
    private var boundMinY = 0
    private var boundMaxX = 0
    private var boundMaxY = 0

    /** 自校准窗口可移动范围（校准期间窗口透明，避免闪烁）。 */
    private fun calibrateBounds() {
        overlayParams.alpha = 0f
        overlayParams.x = -100000
        overlayParams.y = -100000
        try {
            wm.updateViewLayout(rootView, overlayParams)
        } catch (_: Exception) {
            return
        }
        rootView.postOnAnimation {
            val loc = IntArray(2)
            rootView.getLocationOnScreen(loc)
            boundMinX = loc[0]
            boundMinY = loc[1]
            overlayParams.x = 100000
            overlayParams.y = 100000
            try {
                wm.updateViewLayout(rootView, overlayParams)
            } catch (_: Exception) {
            }
            rootView.postOnAnimation {
                rootView.getLocationOnScreen(loc)
                boundMaxX = loc[0]
                boundMaxY = loc[1]
                boundsReady = boundMaxX > boundMinX && boundMaxY > boundMinY
                if (!boundsReady) {
                    // 校准失败：回退为屏幕尺寸估算，仍保证边界限制生效
                    val dm = resources.displayMetrics
                    boundMinX = 0
                    boundMinY = 0
                    boundMaxX = dm.widthPixels - windowPx
                    boundMaxY = dm.heightPixels - windowPx
                    boundsReady = boundMaxX > 0 && boundMaxY > 0
                }
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
            }
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

        if (bounced) applyAngle()

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
        val band = windowPx * 0.6f                        // 感知带宽度
        if (band <= 0f) return
        val halfPi = (Math.PI / 2).toFloat()
        val k = 0.06f                                     // 每帧最大偏转比例
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
