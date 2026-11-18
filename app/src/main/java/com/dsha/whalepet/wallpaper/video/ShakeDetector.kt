package com.dsha.whalepet.wallpaper.video

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 摇一摇检测（加速度计）。
 *
 * 用"合加速度与重力的偏差"判定：静止时合加速度≈9.8，摇动时会出现明显偏离。
 * 比逐轴阈值稳（不同握持角度下不会误判），也不需要额外权限。
 *
 * 只在壁纸可见时注册（由 Engine 控制），不可见时注销，避免常驻传感器耗电。
 */
class ShakeDetector(
    context: Context,
    private val onShake: () -> Unit
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var started = false
    private var lastShakeAt = 0L

    /** 偏差阈值（m/s²）：约 7 相当于"明显甩一下"，轻碰不会触发 */
    private val threshold = 7.0f
    /** 冷却：一次摇动会连续产生多个高点，避免连环触发 */
    private val cooldownMs = 700L

    fun start() {
        if (started) return
        val sm = sensorManager ?: return
        val sensor = accelerometer ?: return
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        started = true
    }

    fun stop() {
        if (!started) return
        sensorManager?.unregisterListener(this)
        started = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)
        val deviation = abs(magnitude - SensorManager.GRAVITY_EARTH)
        if (deviation < threshold) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastShakeAt < cooldownMs) return
        lastShakeAt = now
        onShake()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
