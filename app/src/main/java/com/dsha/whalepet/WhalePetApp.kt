package com.dsha.whalepet

import android.app.Application
/**
 * 应用入口。
 *
 * 已**关闭** Material 3 动态取色：界面统一使用主题里定义的鲸鱼蓝色板，
 * 避免主色随壁纸变化导致同一版应用在不同手机上观感不一致（用户要求）。
 */
class WhalePetApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 对所有 Activity 应用壁纸动态取色（Monet）
    }
}
