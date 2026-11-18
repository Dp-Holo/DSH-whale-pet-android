package com.dsha.whalepet.wallpaper.video

import com.dsha.whalepet.wallpaper.PetFrame
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * 视频壁纸的"表现状态"（纯 Kotlin，可 JVM 单测）。
 *
 * 为什么和 WallpaperPetState 分开：
 * 那个类管的是"小精灵在屏幕上漫游"（位置/速度/边界），视频壁纸是整屏画面，
 * 没有位移诉求，只有三类叠加效果——音频律动（缩放脉动）、摇一摇（左右晃）、
 * 点击（弹一下）。混在一起会让两套语义互相污染。
 *
 * 所有效果都只改渲染参数（scale/rotation），不改视频内容：
 * 这样即使换素材、换分辨率，效果也不用重新渲染。
 */
class VideoPetState {

    /** 音频包络的当前值（0..1），由 AudioEnvelope 按播放位置采样后喂入 */
    private var targetPulse = 0f
    private var pulse = 0f

    /** 摇一摇的剩余振幅（弧度），按指数衰减 */
    private var shakeAmp = 0f
    private var shakePhase = 0f

    /** 点击的弹跳量（额外缩放） */
    private var pop = 0f

    var scale = 1f
        private set
    var rotation = 0f
        private set

    /** 当前音频脉动值（0..1），仅用于诊断落盘 */
    val currentPulse: Float get() = pulse

    /** 每帧推进；dtScale 相对 16.67ms，保证高刷/掉帧下观感一致 */
    fun step(dtScale: Float) {
        // 音频脉动：向目标值靠近，避免包络抖动导致画面"抽搐"
        pulse += (targetPulse - pulse) * min(1f, 0.28f * dtScale)

        if (shakeAmp > 0.0002f) {
            shakePhase += 0.16f * dtScale
            shakeAmp *= (1f - 0.055f * dtScale).coerceIn(0.9f, 1f)
        } else {
            shakeAmp = 0f
        }

        pop = (pop - pop * 0.18f * dtScale).coerceAtLeast(0f)

        rotation = if (shakeAmp > 0f) {
            // 3Hz 左右摆动，幅度随 shakeAmp 衰减；上限 2.5° 左右，避免晕
            (sin(shakePhase * 2.0 * PI) * shakeAmp).toFloat().coerceIn(-0.045f, 0.045f)
        } else {
            0f
        }
        // 音频只做 2.5% 的缩放：太大像"呼吸机"，太小看不出来
        scale = 1f + pulse * 0.025f + pop
    }

    /** 喂入当前音频包络（0..1） */
    fun setAudioLevel(level: Float) {
        targetPulse = level.coerceIn(0f, 1f)
    }

    /** 摇一摇：左右晃一下（不切片段，切片段留给后续 clip 状态机） */
    fun onShake() {
        shakeAmp = 0.035f
        shakePhase = 0f
    }

    /** 单击：弹一下 */
    fun onTap() {
        pop = 0.05f
    }

    /** 组装渲染快照；视频铺满屏幕，位置固定在中心 */
    fun frame(width: Int, height: Int, bubble: String? = null): PetFrame = PetFrame(
        x = width * 0.5f,
        y = height * 0.5f,
        scale = scale,
        rotation = rotation,
        facing = 1,
        alpha = 1f,
        bubble = bubble
    )
}
