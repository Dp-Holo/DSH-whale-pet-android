package com.dsha.whalepet.plugin

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * 【兼容层 ②③】App 本体与插件宿主之间的**唯一入口**。
 *
 * App 侧只调用这里的四个方法（这就是两个维护方之间的全部接口面）：
 *  - [onServiceStart] 桌宠服务启动
 *  - [onServiceStop]  桌宠服务停止
 *  - [onEvent]        事件源触发（单击 / 双击 / 余额减少 / 低电 / 息屏·亮屏 …）
 *  - [showManager]    设置页打开插件管理界面
 *
 * 插件宿主内部的一切（引擎、权限闸门、确认、审计、管理界面）都可自由改动，
 * 只要保持这四个方法的签名不变，就不会影响 App 本体。
 */
object PluginEntry {

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var host: PluginHostImpl? = null

    /** 桌宠服务启动：预加载脚本，并在稍后广播 appStart 事件。 */
    fun onServiceStart(context: Context, bridge: PluginAppBridge) {
        val app = context.applicationContext
        val h = PluginHostImpl(app, bridge)
        host = h
        PluginManager.preloadScripts(app, h)
        handler.postDelayed({
            // 服务可能在 1.5 秒内就被停掉（或重启成新的 host）。
            // 只对"仍然是启动时那个 host"分发 appStart，否则会把事件投给已销毁的
            // host —— 它的 bridge 指向已经停止的服务。
            if (host === h) {
                PluginManager.dispatch(app, h, "appStart", "{}")
            }
        }, 1500L)
    }

    /** 桌宠服务停止：卸载脚本、关闭引擎、收起确认条。 */
    fun onServiceStop() {
        host = null
        PluginEngine.shutdown()
        // 确认条是被服务停止收起的，不是用户拒绝
        PluginConfirmOverlay.dismiss(ConfirmReason.SERVICE_STOPPED)
    }

    /** 事件源触发（未启动时静默忽略）。 */
    fun onEvent(context: Context, event: String, payloadJson: String = "{}") {
        val h = host ?: return
        PluginManager.dispatch(context.applicationContext, h, event, payloadJson)
    }

    /** 设置页入口：打开插件管理界面。 */
    fun showManager(activity: Activity) {
        PluginUi.show(activity)
    }
}

/**
 * 插件宿主 ↔ App 的适配层：
 * 把宿主侧的能力请求转给 App 提供的基础能力，敏感操作则弹出宿主自己的悬浮确认条。
 */
internal class PluginHostImpl(
    private val appContext: Context,
    private val bridge: PluginAppBridge
) : PluginHost {

    override fun pluginSay(text: String) = bridge.say(text)

    override fun pluginNotify(title: String, text: String) = bridge.notify(title, text)

    override fun pluginVibrate(ms: Long) = bridge.vibrate(ms)

    override fun pluginOpenUrl(url: String): Boolean = bridge.openUrl(url)

    override fun pluginOpenApp(pkg: String): Boolean = bridge.openApp(pkg)

    /**
     * 走 App 侧的**结构化**入口（1.1.47 起可用），不再由宿主自己解析返回文案。
     * 归类规则由 App 侧维护，文案改动不会让审计退化。
     */
    override fun pluginShizuku(args: List<String>): ShizukuResult =
        ShizukuResult.from(bridge.shizukuStructured(args))

    override fun pluginConfirm(
        pluginName: String,
        actionDesc: String,
        onResult: (allowed: Boolean, remember: Boolean, reason: ConfirmReason) -> Unit
    ) {
        PluginConfirmOverlay.show(appContext, pluginName, actionDesc, onResult)
    }
}
