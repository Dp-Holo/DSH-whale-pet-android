package com.dsha.whalepet.wallpaper.video

import org.json.JSONObject

/**
 * 预计算的音频包络（RMS，0..1）。
 *
 * 为什么不用 Visualizer/AudioRecord 实时采集：
 * 1) 实时采集需要 RECORD_AUDIO 权限，壁纸为一个"轻微缩放"去申请录音权限不体面；
 * 2) 壁纸自己播放的音频，幅度在内容制作阶段就能算出来，运行时只需按播放位置查表，
 *    零权限、零额外解码线程、且与视频天然同步（用 MediaPlayer.currentPosition）。
 *
 * 数据格式（与视频同名的 _env.json）：
 *   { "stepMs": 50, "rms": [0.0, 0.12, ...] }
 */
class AudioEnvelope(
    private val rms: FloatArray,
    /** 每个采样点覆盖的毫秒数（例如 50ms = 20Hz） */
    val stepMs: Int
) {
    fun sample(positionMs: Long): Float {
        if (rms.isEmpty() || stepMs <= 0) return 0f
        val pos = positionMs.coerceAtLeast(0L)
        val index = (pos / stepMs).toInt()
        if (index >= rms.lastIndex) return rms.last().coerceIn(0f, 1f)
        val fraction = (pos % stepMs).toFloat() / stepMs
        val value = rms[index] * (1f - fraction) + rms[index + 1] * fraction
        return value.coerceIn(0f, 1f)
    }

    companion object {
        fun parse(json: String): AudioEnvelope? = runCatching {
            val root = JSONObject(json)
            val array = root.getJSONArray("rms")
            val values = FloatArray(array.length()) { array.getDouble(it).toFloat() }
            AudioEnvelope(values, root.optInt("stepMs", 50).coerceAtLeast(1))
        }.getOrNull()
    }
}
