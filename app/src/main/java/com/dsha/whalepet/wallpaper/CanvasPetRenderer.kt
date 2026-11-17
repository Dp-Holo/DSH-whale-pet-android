package com.dsha.whalepet.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.SurfaceHolder
import com.dsha.whalepet.R
import kotlin.math.max

/**
 * 壁纸端渲染后端：把 PetFrame 画到 WallpaperService 的 Surface 上。
 *
 * 选型说明（对应交付文档 Q2）：单张 2D 立绘 + 位移/缩放/水平翻转，
 * 用 lockHardwareCanvas（GPU 加速的 Canvas）足够跑满 60fps，不需要 GL。
 * GL 只在多图层/实时滤镜/大量粒子时才划算；这里刻意保持简单，降低耗电与崩溃面。
 *
 * 线程：所有方法都在 Choreographer 回调线程（壁纸服务主线程）调用，无锁。
 */
class CanvasPetRenderer(
    private val context: Context,
    private val holder: SurfaceHolder,
    /** 本体目标边长（px）；素材按这个尺寸等比缩放，避免每帧重算 */
    private val petSizePx: Float
) : PetRenderer {

    private var bitmap: Bitmap? = null
    private var baseScale = 1f
    private var width = 0
    private var height = 0

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private var bgShader: Shader? = null

    /** 背景画笔复用一个实例：每帧 new Paint 会造成 GC 抖动（60fps 下尤其明显） */
    private val bgPaint = Paint()
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bubbleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.argb(40, 0, 0, 0)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(24, 32, 44)
        textSize = 14f * context.resources.displayMetrics.scaledDensity
    }
    private val density = context.resources.displayMetrics.density
    private val bubbleRect = RectF()

    override fun attach(width: Int, height: Int) {
        this.width = width
        this.height = height
        // 背景必须自己画：动态壁纸的 Surface 不会自动叠在系统静态壁纸之上，
        // 留透明会显示为黑色（多数 launcher 的行为），因此给一层深色渐变作为桌面底色。
        bgShader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(Color.rgb(14, 26, 43), Color.rgb(22, 40, 63)),
            null, Shader.TileMode.CLAMP
        )
        if (bitmap == null) {
            bitmap = decodeBitmap()
        }
        bitmap?.let { baseScale = petSizePx / max(1, it.width).toFloat() }
    }

    private fun decodeBitmap(): Bitmap? {
        // 优先用小体积 webp；解码失败再退回 png，避免素材替换时直接黑屏
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return runCatching { BitmapFactory.decodeResource(context.resources, R.drawable.home_whale, options) }
            .getOrNull()
            ?: runCatching { BitmapFactory.decodeResource(context.resources, R.drawable.whale, options) }.getOrNull()
    }

    override fun render(frame: PetFrame, nowMs: Long) {
        if (width <= 0 || height <= 0) return
        val canvas: Canvas = try {
            holder.lockHardwareCanvas()
        } catch (_: Throwable) {
            // Surface 正在销毁/重建时 lock 会抛异常；丢一帧即可，不能让它冒泡炸掉壁纸服务
            return
        }
        try {
            bgShader?.let {
                bgPaint.shader = it
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
            }
            drawPet(canvas, frame)
            frame.bubble?.let { drawBubble(canvas, frame, it) }
        } finally {
            runCatching { holder.unlockCanvasAndPost(canvas) }
        }
    }

    private fun drawPet(canvas: Canvas, frame: PetFrame) {
        val bmp = bitmap ?: return
        val sx = baseScale * frame.scale * frame.facing
        val sy = baseScale * frame.scale
        matrix.reset()
        // 以图片中心为锚点缩放，负的 sx 就是水平镜像
        matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        matrix.postScale(sx, sy)
        matrix.postTranslate(frame.x, frame.y)
        bitmapPaint.alpha = (frame.alpha.coerceIn(0f, 1f) * 255).toInt()
        canvas.drawBitmap(bmp, matrix, bitmapPaint)
    }

    private fun drawBubble(canvas: Canvas, frame: PetFrame, text: String) {
        val maxWidth = (width * 0.72f).toInt().coerceAtLeast(120)
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(3)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()

        val padH = 10f * density
        val padV = 7f * density
        val bw = layout.width + padH * 2
        val bh = layout.height + padV * 2
        val petHalf = petSizePx * frame.scale * 0.5f
        val cx = frame.x.coerceIn(bw / 2f + 4f, width - bw / 2f - 4f)
        val top = (frame.y - petHalf - bh - 8f * density).coerceAtLeast(4f)
        bubbleRect.set(cx - bw / 2f, top, cx + bw / 2f, top + bh)

        canvas.drawRoundRect(bubbleRect, 12f * density, 12f * density, bubblePaint)
        canvas.drawRoundRect(bubbleRect, 12f * density, 12f * density, bubbleStroke)
        canvas.save()
        canvas.translate(bubbleRect.left + padH, bubbleRect.top + padV)
        layout.draw(canvas)
        canvas.restore()
    }

    override fun detach() {
        // 只回收本渲染器自己解码的位图；Surface 本身由系统管理
        bitmap?.recycle()
        bitmap = null
        bgShader = null
    }
}
