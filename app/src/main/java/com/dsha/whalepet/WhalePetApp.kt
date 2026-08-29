package com.dsha.whalepet

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * 应用入口：启用 Material 3 动态取色（Dynamic Colors）。
 * Android 12+ 自动从壁纸提取主题色；低版本回退默认主题色。
 */
class WhalePetApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 对所有 Activity 应用壁纸动态取色（Monet）
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
