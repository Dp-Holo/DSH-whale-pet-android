package com.dsha.whalepet.wallpaper

/**
 * 渲染后端接口：悬浮窗与动态壁纸各实现一份。
 *
 * 为什么抽出这个接口：两种形态的差异只在"画到哪里"（View / Surface），
 * 而位置、朝向、缩放的推进逻辑应当只有一份。接口只暴露 attach/render/detach，
 * 不暴露 View、WindowManager、SurfaceHolder，避免后端细节污染状态层。
 *
 * 线程约束：所有方法都在渲染线程调用（壁纸 = Choreographer 回调所在线程；
 * 悬浮窗 = 主线程），实现内部不要再跨线程访问自己的可变字段。
 */
interface PetRenderer {
    /** Surface/窗口尺寸确定或变化时调用；实现方在此重建与尺寸相关的缓存（渐变、矩阵缩放） */
    fun attach(width: Int, height: Int)

    /** 每帧调用；nowMs 用于做与帧率无关的动画（缩放回弹、气泡淡出等） */
    fun render(frame: PetFrame, nowMs: Long)

    /** Surface/窗口销毁时调用；实现方释放与 Surface 绑定的资源（不要在这里 recycle 共享 Bitmap） */
    fun detach()
}
