package com.dsha.whalepet.wallpaper

/**
 * 渲染无关的宠物状态快照。
 *
 * 坐标系：壁纸 Surface 的像素坐标（原点 = Surface 左上角，不是状态栏下方，
 * 这一点与悬浮窗的 WindowManager 坐标不同，壁纸端不需要做状态栏偏移校准）。
 * 悬浮窗后端与壁纸后端都应能由同一份 PetFrame 驱动，UI 细节不进入这里。
 */
data class PetFrame(
    val x: Float,
    val y: Float,
    val scale: Float,
    /** 弧度；当前 2D 立绘未使用，预留给后续倾斜/摆动动画 */
    val rotation: Float,
    /** 1 = 原图，-1 = 水平镜像 */
    val facing: Int,
    val alpha: Float,
    /** 头顶气泡文字；null 表示不显示。MVP 阶段由壁纸端自己产出台词 */
    val bubble: String? = null
)
