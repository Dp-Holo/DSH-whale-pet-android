package com.dsha.whalepet.wallpaper

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 壁纸端的宠物运动状态（纯 Kotlin，不依赖 Android，便于 JVM 单测）。
 *
 * 为什么先独立实现、而不是直接抽悬浮窗的漫游逻辑：
 * 悬浮窗那套与 WindowManager/窗口内边距/实测偏移强耦合，直接共用会把壁纸拖进
 * 那些历史坑里。先按同样的行为参数（角度游走、随机偏转、朝向滞回）独立实现，
 * 等壁纸形态验证稳定后，再把它提升为两边共用的 PetMotion（见交付文档 D4）。
 *
 * 坐标：Surface 像素坐标，单位 px；边界按"本体半径"内缩，保证整只鲸鱼都在屏内。
 */
class WallpaperPetState(
    /** 每帧基础速度（px/帧@60Hz），由调用方按屏幕密度换算 */
    private val speedPx: Float,
    /** 本体边长（px），用于边界内缩与绘制中心 */
    private val petSizePx: Float
) {
    var x: Float = 0f
        private set
    var y: Float = 0f
        private set

    /** 1 = 原图，-1 = 镜像；朝向只在横向速度足够大时翻转，避免阈值附近抖动 */
    var facing: Int = -1
        private set

    /** 点击时短暂放大，随后指数回落到 1 */
    var scale: Float = 1f
        private set

    var bubble: String? = null
        private set

    private var bubbleUntilMs: Long = 0L
    private var angle: Float = -0.4f
    private var lastVx: Float = 0f

    /** 屏幕尺寸变化（旋转/分屏/折叠）后调用：夹回边界，保留当前位置 */
    fun clampTo(width: Int, height: Int) {
        val half = petSizePx * 0.5f
        x = x.coerceIn(half, (width - half).coerceAtLeast(half))
        y = y.coerceIn(half, (height - half).coerceAtLeast(half))
    }

    /** 首次 attach 或 Surface 重建时重置到屏幕中心，避免沿用旧分辨率下的坐标 */
    fun reset(width: Int, height: Int) {
        x = width * 0.5f
        y = height * 0.5f
        angle = -0.4f
        scale = 1f
        bubble = null
        bubbleUntilMs = 0L
    }

    /**
     * 推进一步。
     * @param dtScale 相对 16.67ms 的帧间隔倍率（高刷屏不会变快，掉帧单帧上限由调用方夹住）
     */
    fun step(width: Int, height: Int, dtScale: Float, nowMs: Long) {
        // 与悬浮窗一致：小幅随机偏转 + 偶发大转弯，游动看起来更自然
        angle += (Random.nextFloat() - 0.5f) * 0.02f
        if (Random.nextFloat() < 0.0025f) {
            angle += (Random.nextFloat() - 0.5f) * (PI / 2).toFloat()
        }

        var vx = cos(angle) * speedPx
        var vy = sin(angle) * speedPx
        x += vx * dtScale
        y += vy * dtScale

        val half = petSizePx * 0.5f
        val minX = half
        val maxX = (width - half).coerceAtLeast(half)
        val minY = half
        val maxY = (height - half).coerceAtLeast(half)

        // 边界反弹：只翻转对应分量，不清零，避免贴着边"蹭"或卡死
        if (x < minX) {
            x = minX
            angle = (PI - angle).toFloat()
            vx = -vx
        } else if (x > maxX) {
            x = maxX
            angle = (PI - angle).toFloat()
            vx = -vx
        }
        if (y < minY) {
            y = minY
            angle = -angle
            vy = -vy
        } else if (y > maxY) {
            y = maxY
            angle = -angle
            vy = -vy
        }
        lastVx = vx

        // 朝向滞回：横向速度超过 35% 基础速度才允许翻转（与悬浮窗同一经验值）
        if (abs(lastVx) > speedPx * 0.35f) {
            facing = if (lastVx < 0f) 1 else -1
        }

        // 点击缩放的指数回落：与帧率解耦，120Hz 与 60Hz 观感一致
        if (scale > 1f) {
            scale = (scale - (scale - 1f) * 0.12f * dtScale).coerceAtLeast(1f)
        }

        if (bubble != null && nowMs >= bubbleUntilMs) {
            bubble = null
        }
    }

    /** 单击：冒一句台词（durationMs 后可被下一句覆盖） */
    fun say(text: String, nowMs: Long, durationMs: Long = 2600L) {
        bubble = text
        bubbleUntilMs = nowMs + durationMs
    }

    /** 双击：轻轻跳一下（缩放从 1.12 回弹） */
    fun jump() {
        scale = 1.12f
    }

    /** 组装给渲染后端的快照；气泡随状态一起传，避免渲染端反向读状态 */
    fun frame(): PetFrame = PetFrame(
        x = x,
        y = y,
        scale = scale,
        rotation = 0f,
        facing = facing,
        alpha = 1f,
        bubble = bubble
    )
}
