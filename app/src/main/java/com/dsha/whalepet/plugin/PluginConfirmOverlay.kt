package com.dsha.whalepet.plugin

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.dsha.whalepet.R

/**
 * 插件敏感操作确认条（悬浮窗）：**允许 / 始终允许 / 拒绝**，15 秒未选择视为拒绝。
 *
 * 属于插件宿主的一部分（可自由改动）：App 本体只提供悬浮窗权限与 Context，
 * 不参与确认逻辑。需要 App 已授予 SYSTEM_ALERT_WINDOW。
 */
object PluginConfirmOverlay {

    private const val TIMEOUT_MS = 15_000L

    private val handler = Handler(Looper.getMainLooper())
    private var win: View? = null
    private var callback: ((Boolean, Boolean) -> Unit)? = null
    private var timeout: Runnable? = null

    /** 展示确认条；可在任意线程调用（内部切主线程）。 */
    fun show(
        context: Context,
        pluginName: String,
        actionDesc: String,
        onResult: (allowed: Boolean, remember: Boolean) -> Unit
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { show(context, pluginName, actionDesc, onResult) }
            return
        }
        dismiss()                                    // 同一时刻只保留一个确认条
        callback = onResult
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = View.inflate(context, R.layout.overlay_plugin_confirm, null)
            view.findViewById<TextView>(R.id.confirm_text).text =
                context.getString(R.string.plugin_confirm_format, pluginName, actionDesc)
            view.findViewById<Button>(R.id.confirm_allow).setOnClickListener { finish(true, false) }
            view.findViewById<Button>(R.id.confirm_always).setOnClickListener { finish(true, true) }
            view.findViewById<Button>(R.id.confirm_deny).setOnClickListener { finish(false, false) }

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
            wm.addView(view, params)
            win = view
            timeout = Runnable { finish(false, false) }
            handler.postDelayed(timeout!!, TIMEOUT_MS)
        } catch (_: Throwable) {
            // 悬浮窗不可用（未授权等）：视为拒绝
            callback = null
            onResult(false, false)
        }
    }

    /** 收起确认条（若正在等待，按"拒绝"结束）。 */
    fun dismiss() {
        if (callback != null || win != null) finish(false, false)
    }

    private fun finish(allowed: Boolean, remember: Boolean) {
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
        cb?.invoke(allowed, remember)
    }
}
