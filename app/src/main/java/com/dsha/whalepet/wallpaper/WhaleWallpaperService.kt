package com.dsha.whalepet.wallpaper

import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.dsha.whalepet.R
import com.dsha.whalepet.WhaleLines
import com.dsha.whalepet.wallpaper.video.AudioEnvelope
import com.dsha.whalepet.wallpaper.video.ShakeDetector
import com.dsha.whalepet.wallpaper.video.VideoPetRenderer
import com.dsha.whalepet.wallpaper.video.VideoPetState

/**
 * 鲸鱼娘动态壁纸服务。
 *
 * 渲染优先级：视频（MediaPlayer + GL 合成）→ 静态 Canvas（素材缺失/初始化失败时兜底）。
 * 之所以保留 Canvas 兜底：壁纸进程是系统常驻的，任何一次素材或 EGL 初始化失败都不该
 * 让用户看到黑屏——退化成静态图至少还能用。
 *
 * 交互与 ROLE 对齐：
 *  - 音频律动：按播放位置查预计算包络（无需 RECORD_AUDIO），驱动画面轻微缩放；
 *  - 摇一摇：加速度计检测，画面左右晃动（切片段留给后续内容包）；
 *  - 单击/双击：轻微弹跳（台词气泡的 GL 文字纹理待后续迁移）。
 */
class WhaleWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = WhaleEngine()

    inner class WhaleEngine : Engine() {

        private val tag = "WhaleWallpaper"

        /** 视频素材（assets 路径）；换成正式素材时改这里即可 */
        private val videoAsset = "wallpaper/test_idle.mp4"
        private val envelopeAsset = "wallpaper/test_idle_env.json"

        /** 静态兜底时的本体尺寸：与悬浮窗一致的 120dp × 0.8 */
        private val petSizePx: Float
            get() = 120f * 0.8f * this@WhaleWallpaperService.resources.displayMetrics.density

        /** 静态兜底的每帧速度：沿用悬浮窗经验值 */
        private val speedPx: Float
            get() = 0.55f * this@WhaleWallpaperService.resources.displayMetrics.density * 0.55f

        private var videoRenderer: VideoPetRenderer? = null
        private var canvasRenderer: CanvasPetRenderer? = null
        private var spriteState: WallpaperPetState? = null
        private val videoState = VideoPetState()
        private var envelope: AudioEnvelope? = null
        private var shakeDetector: ShakeDetector? = null

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
                // 与帧率解耦：120Hz 不会更快；掉帧/降频时单帧位移最多 3 倍
                val dtMs = if (lastFrameNanos == 0L) 16.67f else (frameTimeNanos - lastFrameNanos) / 1_000_000f
                lastFrameNanos = frameTimeNanos
                val dtScale = (dtMs / 16.67f).coerceIn(0.25f, 3f)
                try {
                    val now = frameTimeNanos / 1_000_000L
                    val video = videoRenderer
                    if (video != null) {
                        // 音频包络按播放位置采样，和视频天然同步
                        videoState.setAudioLevel(envelope?.sample(video.currentPositionMs()) ?: 0f)
                        videoState.step(dtScale)
                        video.render(videoState.frame(surfaceWidth, surfaceHeight), now)
                    } else {
                        val s = spriteState ?: return
                        val r = canvasRenderer ?: return
                        s.step(surfaceWidth, surfaceHeight, dtScale, now)
                        r.render(s.frame(), now)
                    }
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
                    val line = runCatching {
                        WhaleLines.load(this@WhaleWallpaperService.applicationContext).randomOrNull()
                    }.getOrNull() ?: this@WhaleWallpaperService.getString(R.string.wallpaper_default_line)
                    // 视频模式下先只做弹跳反馈；台词气泡需要 GL 文字纹理，放到 M3.5
                    videoRenderer?.let { videoState.onTap() } ?: spriteState?.say(line, System.currentTimeMillis())
                    Log.i(tag, "tap: $line")
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    videoRenderer?.let { videoState.onTap() } ?: spriteState?.jump()
                    return true
                }
            })
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            // 壁纸默认收不到触摸事件，必须显式开启；launcher 手势仍可能优先吃掉纵向滑动
            setTouchEventsEnabled(true)
            shakeDetector = ShakeDetector(this@WhaleWallpaperService.applicationContext) {
                onShakeDetected()
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            Log.i(tag, "surface created")
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width.coerceAtLeast(1)
            surfaceHeight = height.coerceAtLeast(1)

            // 视频优先；失败（素材缺失/EGL 初始化失败）则回退静态 Canvas
            val video = videoRenderer ?: VideoPetRenderer(
                this@WhaleWallpaperService.applicationContext, holder, videoAsset
            ).also { videoRenderer = it }
            video.attach(surfaceWidth, surfaceHeight)
            if (!video.isUsable) {
                video.detach()
                videoRenderer = null
            }

            if (videoRenderer == null) {
                val first = spriteState == null
                val s = spriteState ?: WallpaperPetState(speedPx, petSizePx).also {
                    it.reset(surfaceWidth, surfaceHeight)
                    spriteState = it
                }
                if (!first) s.clampTo(surfaceWidth, surfaceHeight)
                val c = canvasRenderer ?: CanvasPetRenderer(
                    this@WhaleWallpaperService.applicationContext, holder, petSizePx
                ).also { canvasRenderer = it }
                c.attach(surfaceWidth, surfaceHeight)
            } else {
                canvasRenderer?.detach()
                canvasRenderer = null
                spriteState = null
            }

            if (envelope == null) envelope = loadEnvelope()

            // Surface 尺寸变化时，可见性可能没有变化；这里按当前可见性重新驱动循环
            if (visible) startLoop() else postOneFrame()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            this.visible = visible
            Log.i(tag, "visible=$visible")
            if (visible) {
                startLoop()
                // 传感器只在可见时注册，避免常驻耗电
                shakeDetector?.start()
            } else {
                stopLoop()
                shakeDetector?.stop()
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

        override fun onTouchEvent(event: MotionEvent) {
            // WallpaperService.Engine.onTouchEvent 的返回值是 void（不是 Boolean）
            gestureDetector.onTouchEvent(event)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            // 顺序很重要：先停循环再释放，避免回调里拿到已释放的 GL/Surface
            stopLoop()
            shakeDetector?.stop()
            videoRenderer?.detach()
            videoRenderer = null
            canvasRenderer?.detach()
            canvasRenderer = null
            spriteState = null
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            stopLoop()
            shakeDetector?.stop()
            shakeDetector = null
            videoRenderer?.detach()
            videoRenderer = null
            canvasRenderer?.detach()
            canvasRenderer = null
            spriteState = null
            super.onDestroy()
        }

        private fun onShakeDetected() {
            Log.i(tag, "shake")
            videoRenderer?.let { videoState.onShake() } ?: spriteState?.jump()
        }

        private fun loadEnvelope(): AudioEnvelope? = runCatching {
            this@WhaleWallpaperService.assets.open(envelopeAsset).bufferedReader().use {
                AudioEnvelope.parse(it.readText())
            }
        }.getOrNull()

        private fun startLoop() {
            val hasRenderer = videoRenderer != null || canvasRenderer != null
            if (running || !visible || !hasRenderer || surfaceWidth <= 0) return
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
         * 桌面上会先看到一帧黑底，再等下一次可见才出现画面。
         */
        private fun postOneFrame() {
            val now = System.currentTimeMillis()
            videoRenderer?.let {
                videoState.setAudioLevel(0f)
                videoState.step(1f)
                runCatching { it.render(videoState.frame(surfaceWidth, surfaceHeight), now) }
                return
            }
            val s = spriteState ?: return
            val r = canvasRenderer ?: return
            runCatching { r.render(s.frame(), now) }
        }
    }
}
