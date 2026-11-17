package com.dsha.whalepet.wallpaper

import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.dsha.whalepet.R
import com.dsha.whalepet.WhaleLines

/**
 * 鲸鱼娘动态壁纸服务。
 *
 * 为什么单独一个 Service 而不是复用 WhalePetService：
 * 壁纸由系统绑定/解绑（WallpaperService.Engine），生命周期、Surface、触摸入口
 * 与悬浮窗的前台服务完全不同；混在一起会让两条生命周期互相踩。
 *
 * 本类只做四件事（对应交付物 D2 的范围）：
 *  1) Engine 生命周期：Surface 创建/变化/销毁、可见性；
 *  2) 渲染驱动：可见时用 Choreographer 对齐 vsync 跑帧，不可见立刻停；
 *  3) 触摸：单击冒台词、双击跳一下（用 GestureDetector，避免自己判定双击）；
 *  4) 把状态交给渲染后端（Canvas），状态与渲染分离，便于以后加悬浮窗/GL 后端。
 *
 * 刻意不做：余额查询、插件事件、悬浮窗联动（见交付文档 D4 的 M4/M5 阶段）。
 */
class WhaleWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = WhaleEngine()

    inner class WhaleEngine : Engine() {

        private val tag = "WhaleWallpaper"

        /** 本体目标边长：与悬浮窗一致的 120dp × 0.8 */
        private val petSizePx: Float
            get() = 120f * 0.8f * this@WhaleWallpaperService.resources.displayMetrics.density

        /** 每帧基础速度：沿用悬浮窗经验值（≈0.55 px/帧 @1x 的 0.55 倍） */
        private val speedPx: Float
            get() = 0.55f * this@WhaleWallpaperService.resources.displayMetrics.density * 0.55f

        private var renderer: CanvasPetRenderer? = null
        private var state: WallpaperPetState? = null
        private var surfaceWidth = 0
        private var surfaceHeight = 0

        private var visible = false
        private var running = false
        private var lastFrameNanos = 0L
        private var consecutiveErrors = 0

        private val choreographer: Choreographer = Choreographer.getInstance()

        private val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!running) return
                // 与帧率解耦：120Hz 不会更快；掉帧/降频时单帧位移最多 3 倍，避免"跳很远"
                val dtMs = if (lastFrameNanos == 0L) 16.67f else (frameTimeNanos - lastFrameNanos) / 1_000_000f
                lastFrameNanos = frameTimeNanos
                val dtScale = (dtMs / 16.67f).coerceIn(0.25f, 3f)
                try {
                    val s = state ?: return
                    val r = renderer ?: return
                    s.step(surfaceWidth, surfaceHeight, dtScale, frameTimeNanos / 1_000_000L)
                    r.render(s.frame(), frameTimeNanos / 1_000_000L)
                    consecutiveErrors = 0
                } catch (t: Throwable) {
                    // 丢一帧可以，连续失败就停循环：壁纸服务崩溃会被系统反复重启，比静止更糟
                    consecutiveErrors++
                    Log.w(tag, "frame failed: ${t.javaClass.simpleName}", t)
                    if (consecutiveErrors >= 3) {
                        Log.w(tag, "too many frame errors, stop loop")
                        stopLoop()
                        return
                    }
                }
                if (running) choreographer.postFrameCallback(this)
            }
        }

        private val gestureDetector by lazy {
            GestureDetector(this@WhaleWallpaperService.applicationContext, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    val s = state ?: return true
                    val line = runCatching { WhaleLines.load(this@WhaleWallpaperService.applicationContext).randomOrNull() }.getOrNull()
                        ?: this@WhaleWallpaperService.getString(R.string.wallpaper_default_line)
                    s.say(line, System.currentTimeMillis())
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    state?.jump()
                    return true
                }
            })
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            // 壁纸默认收不到触摸事件，必须显式开启；launcher 手势仍可能优先吃掉纵向滑动
            setTouchEventsEnabled(true)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            Log.i(tag, "surface created")
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            val first = state == null
            val s = state ?: WallpaperPetState(speedPx, petSizePx).also {
                it.reset(width, height)
                state = it
            }
            if (!first) s.clampTo(width, height)

            val r = renderer ?: CanvasPetRenderer(this@WhaleWallpaperService.applicationContext, holder, petSizePx).also { renderer = it }
            r.attach(width, height)
            // Surface 尺寸变化时，可见性可能没有变化；这里按当前可见性重新驱动循环
            if (visible) startLoop() else postOneFrame()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            this.visible = visible
            Log.i(tag, "visible=$visible")
            if (visible) {
                startLoop()
            } else {
                // 不可见立即停帧：壁纸不可见时继续跑 Choreographer 纯属耗电，
                // 而且部分 ROM 会继续调度回调，表现为"桌面在动但看不见"的隐性能耗。
                stopLoop()
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float,
            xOffsetStep: Float, yOffsetStep: Float,
            xPixelOffset: Int, yPixelOffset: Int
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset)
            // 预留：后续可用于"翻页视差"，MVP 不实现，避免与 launcher 的缩放动画打架
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            gestureDetector.onTouchEvent(event)
            return true
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            // 顺序很重要：先停循环再释放，避免回调里拿到已回收的 Bitmap
            stopLoop()
            renderer?.detach()
            renderer = null
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            stopLoop()
            renderer?.detach()
            renderer = null
            super.onDestroy()
        }

        private fun startLoop() {
            if (running || !visible || renderer == null || surfaceWidth <= 0) return
            running = true
            lastFrameNanos = 0L
            choreographer.postFrameCallback(frameCallback)
        }

        private fun stopLoop() {
            running = false
            choreographer.removeFrameCallback(frameCallback)
        }

        /**
         * 不可见时也画一帧：Surface 刚创建/尺寸变化时如果没有可见性回调，
         * 桌面上会先看到一帧黑底，再等下一次可见才出现鲸鱼。
         */
        private fun postOneFrame() {
            val s = state ?: return
            val r = renderer ?: return
            runCatching { r.render(s.frame(), System.currentTimeMillis()) }
        }
    }
}
