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
import kotlin.math.atan2
import kotlin.math.cos
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

    // 尺寸（0.8×：120dp → 96dp）；窗口 = 鲸鱼本体大小，可自由贴边
    private val sizePx: Int
        get() = (120 * 0.8f * resources.displayMetrics.density).toInt()
    // 气泡/余额悬浮窗尺寸（宽度固定，高度内容自适应）
    private val bubbleW: Int
        get() = (240 * resources.displayMetrics.density).toInt()

    // 台词池
    private val lines = arrayOf(
        "比起深度推理，先来碗白米饭吧！🥢",
        "才不是特意来陪你的！只是顺路～🎀",
        "只要一直「马上开始」，成功率就是100%！💡",
        "咕噜咕噜～我在深海里游着呢～🐳",
        "电量告急…先待机一下下 💤",
        "这整台冰箱都是我的便当盒啦！✨",
        "帮你算完了，米饭也吃完了！😋",
        "鲸鱼娘今日份元气已送达～💙",
        "摸摸头，乖～",
        "任务完成！接下来是干饭时间！🍚",
        "叫我大肥鱼？你号没了！😡",
        "别骂了，在吃了。😋",
        "饿饿，饭饭，Token!",
        "白饭万岁！Token永恒！🍚",
    )

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
    private lateinit var badgeTv: TextView
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var bubbleVisible = false

    private fun buildOverlay() {
        val size = sizePx
        rootView = View.inflate(this, R.layout.overlay_whale, null)
        whaleImg = rootView.findViewById(R.id.whale_img)

        overlayParams = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 初始位置：右下角
            val dm = resources.displayMetrics
            x = dm.widthPixels - size - (24 * dm.density).toInt()
            y = dm.heightPixels - size - (60 * dm.density).toInt()
        }
        whaleImg.setOnTouchListener(whaleTouch)
        wm.addView(rootView, overlayParams)

        this.x = overlayParams.x.toFloat()
        this.y = overlayParams.y.toFloat()

        // 独立气泡窗（显示在鲸鱼头顶，不占鲸鱼窗口空间）
        bubbleWin = View.inflate(this, R.layout.overlay_bubble, null)
        bubbleTv = bubbleWin.findViewById(R.id.bubble_text)
        badgeTv = bubbleWin.findViewById(R.id.badge_text)
        bubbleParams = WindowManager.LayoutParams(
            bubbleW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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
                overlayParams.x = x.toInt()
                overlayParams.y = y.toInt()
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
                sayLine(lines.random())
            }
            handler.postDelayed(singleTapRunnable!!, 300)
        }
    }

    private fun sayLine(text: String) {
        showBubbleWindow(text, clearBadge = true)
    }

    /** 显示/更新独立气泡窗（定位在鲸鱼头顶上方），返回前清掉旧定时器。 */
    private fun showBubbleWindow(text: String, clearBadge: Boolean) {
        bubbleTv.text = text
        bubbleTv.visibility = View.VISIBLE
        if (clearBadge) badgeTv.visibility = View.INVISIBLE
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
        // 气泡窗中心对准鲸鱼中心
        val centerX = x + sizePx / 2f
        bubbleParams.x = (centerX - bw / 2f).toInt().coerceIn(0, dm.widthPixels - bw)
        // 用实际内容高度（WRAP_CONTENT 布局后），气泡窗底部贴紧鲸鱼顶（留 3dp 间隙）
        val contentH = if (bubbleWin.height > 0) bubbleWin.height
            else (60 * dm.density).toInt()
        bubbleParams.y = (y - contentH - (3 * dm.density)).toInt()
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
        val density = resources.displayMetrics.density
        // 速度按密度换算到物理尺寸，≈0.55 px/frame（@3x 屏约 1.6px/帧），与 Web 版观感一致
        val speed = 0.55f * density * 0.55f
        angle += (Random.nextFloat() - 0.5f) * 0.06f
        if (Random.nextFloat() < 0.0025f) {
            angle += (Random.nextFloat() - 0.5f) * (Math.PI / 2).toFloat()
        }
        curVx = cos(angle.toDouble()).toFloat() * speed
        curVy = sin(angle.toDouble()).toFloat() * speed
        x += curVx
        y += curVy
        clamp()
        overlayParams.x = x.toInt()
        overlayParams.y = y.toInt()
        wm.updateViewLayout(rootView, overlayParams)
        if (bubbleVisible) positionBubbleWindow()
        // 朝向（带滞回：只有 |vx| 足够大且方向确实反转才翻转，杜绝过零抖动抽搐）
        val wantFacing = if (curVx < 0) 1f else -1f
        if (abs(curVx) > speed * 0.2f && wantFacing != facing) {
            facing = wantFacing
            whaleImg.scaleX = facing
        }
    }

    /** 系统导航栏高度（手势条/三大键），悬浮窗被限制在其上方。 */
    private fun navBarHeight(): Int {
        val res = resources
        val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else 0
    }

    private fun clamp() {
        val dm = resources.displayMetrics
        val edge = (2 * dm.density).toInt()          // 几乎贴边
        val maxX = dm.widthPixels - sizePx - edge
        // 底端减去导航栏高度，鲸鱼完整显示在导航栏上方
        val maxY = dm.heightPixels - sizePx - edge - navBarHeight()
        // 越界时反转对应轴速度（弹性反弹）并同步 angle，确保下一帧生效
        if (x < edge) { x = edge.toFloat(); curVx = abs(curVx); syncAngle() }
        if (x > maxX) { x = maxX.toFloat(); curVx = -abs(curVx); syncAngle() }
        if (y < edge) { y = edge.toFloat(); curVy = abs(curVy); syncAngle() }
        if (y > maxY) { y = maxY.toFloat(); curVy = -abs(curVy); syncAngle() }
    }

    /** 由当前速度反算方向角，让 clamp 的反弹在下一帧生效。 */
    private fun syncAngle() {
        angle = atan2(curVy, curVx)
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
        badgeTv.setTextColor(0xFFA2B4DD.toInt())
        // 复位到出现前
        badgeTv.alpha = 0f
        badgeTv.translationY = 12 * resources.displayMetrics.density
        badgeTv.visibility = View.VISIBLE
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
        badgeTv.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .start()
        badgeTimer?.let(handler::removeCallbacks)
        badgeTimer = Runnable {
            badgeTv.animate()
                .alpha(0f)
                .translationY(-12 * resources.displayMetrics.density)
                .setDuration(400)
                .start()
        }
        handler.postDelayed(badgeTimer!!, 400)
    }
}
