package com.dsha.whalepet.plugin

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.dsha.whalepet.R

/**
 * 插件敏感操作确认条（悬浮窗）：**允许 / 始终允许 / 拒绝**，15 秒未选择视为拒绝。
 * 用于 openUrl / openApp / shizuku / network 这类"把东西送出去"的能力。
 *
 * 属于插件宿主的一部分（可自由改动）：App 本体只提供悬浮窗权限与 Context，
 * 不参与确认逻辑。需要 App 已授予 SYSTEM_ALERT_WINDOW。
 */
object PluginConfirmOverlay {

    private const val TIMEOUT_MS = 15_000L

    private val handler = Handler(Looper.getMainLooper())
    private var win: View? = null
    private var callback: ((Boolean, Boolean, ConfirmReason) -> Unit)? = null
    private var timeout: Runnable? = null

    /**
     * 上次无法弹出确认条的具体原因。
     *
     * 一开始这里只有一句"悬浮窗不可用"，而 `catch` 把异常整个吞掉了——
     * 真机上只看到这句话，完全无法判断是**权限**、**布局解析**还是**加窗**失败
     * （1.1.51 实机就卡在这里：四条审计同一秒出现，连确认条都没显示过）。
     * 现在把异常抄进审计，下次一眼能定位。
     */
    @Volatile
    var lastFailure: String? = null
        private set

    /** 展示确认条；可在任意线程调用（内部切主线程）。 */
    fun show(
        context: Context,
        pluginName: String,
        actionDesc: String,
        onResult: (allowed: Boolean, remember: Boolean, reason: ConfirmReason) -> Unit
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { show(context, pluginName, actionDesc, onResult) }
            return
        }
        // 同一时刻只保留一个确认条；旧的那条是被顶掉的，不能记成"用户拒绝"
        dismiss(ConfirmReason.SUPERSEDED)
        lastFailure = null

        // 没有悬浮窗权限时 addView 只会抛 BadTokenException，那对用户毫无指导意义，
        // 这里提前给出「该去开什么」的说法
        if (!Settings.canDrawOverlays(context)) {
            lastFailure = "未授予「显示在其他应用上层」权限"
            onResult(false, false, ConfirmReason.UNAVAILABLE)
            return
        }

        // 布局里用了 Material3 主题属性（?attr/materialButtonOutlinedStyle、
        // ?attr/colorSurfaceVariant 等），**必须用带主题的 context 解析**。
        // 传入的若是不带 Theme.WhalePet 的 context，这里会抛 Resources.NotFoundException，
        // 表现为"确认条永远弹不出来"。
        val themed = ContextThemeWrapper(context, R.style.Theme_WhalePet)
        val view = try {
            View.inflate(themed, R.layout.overlay_plugin_confirm, null)
        } catch (t: Throwable) {
            lastFailure = "布局解析失败：${t.javaClass.simpleName}: ${t.message}"
            onResult(false, false, ConfirmReason.UNAVAILABLE)
            return
        }

        callback = onResult
        try {
            view.findViewById<TextView>(R.id.confirm_text).text =
                themed.getString(R.string.plugin_confirm_format, pluginName, actionDesc)
            view.findViewById<Button>(R.id.confirm_allow).setOnClickListener {
                finish(true, false, ConfirmReason.ALLOWED)
            }
            view.findViewById<Button>(R.id.confirm_always).setOnClickListener {
                finish(true, true, ConfirmReason.ALLOWED)
            }
            view.findViewById<Button>(R.id.confirm_deny).setOnClickListener {
                finish(false, false, ConfirmReason.USER_DENIED)
            }

            val dm = context.resources.displayMetrics
            val params = WindowManager.LayoutParams(
                dm.widthPixels - (24 * dm.density).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                x = (12 * dm.density).toInt()
                y = (96 * dm.density).toInt()
            }
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.addView(view, params)
            win = view
            timeout = Runnable { finish(false, false, ConfirmReason.TIMEOUT) }
            handler.postDelayed(timeout!!, TIMEOUT_MS)
        } catch (t: Throwable) {
            // 加窗失败（权限被撤销/系统限制）：按"无法确认"结束，不冒充用户拒绝
            callback = null
            lastFailure = "加窗失败：${t.javaClass.simpleName}: ${t.message}"
            onResult(false, false, ConfirmReason.UNAVAILABLE)
        }
    }

    /**
     * 收起确认条（若正在等待，按 [reason] 结束）。
     *
     * 调用方必须给出**真实原因**：服务停止用 [ConfirmReason.SERVICE_STOPPED]，
     * 被新确认顶替用 [ConfirmReason.SUPERSEDED]——不要一律当成"用户拒绝"。
     */
    fun dismiss(reason: ConfirmReason) {
        if (callback != null || win != null) finish(false, false, reason)
    }

    private fun finish(allowed: Boolean, remember: Boolean, reason: ConfirmReason) {
        val cb = callback
        callback = null
        timeout?.let(handler::removeCallbacks)
        timeout = null
        val view = win
        win = null
        if (view != null) {
            try {
                val wm = view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(view)
            } catch (_: Throwable) {
            }
        }
        cb?.invoke(allowed, remember, reason)
    }
}
