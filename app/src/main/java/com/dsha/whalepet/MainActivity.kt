package com.dsha.whalepet

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 设置页：授予悬浮窗权限、填写 DeepSeek API Key、
 * 启动/停止桌宠服务。
 *
 * Shizuku 集成注意：binder 异步到达，授权结果经 Shizuku 专用监听回调，
 * 因此统一交给 ShizukuHelper.init() 处理；不再自行 pingBinder 一次了事，
 * 也不依赖 Activity.onRequestPermissionsResult（Shizuku 不走该通道）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnOverlay: Button
    private lateinit var etApiKey: EditText
    private lateinit var tvBalance: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        btnOverlay = findViewById(R.id.btn_overlay)
        etApiKey = findViewById(R.id.et_api_key)
        tvBalance = findViewById(R.id.tv_balance)

        // 回填已保存的 key
        etApiKey.setText(Prefs.getApiKey(this))

        btnOverlay.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                toast(R.string.overlay_granted)
            } else {
                openOverlaySettings()
            }
        }

        btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                // 先尝试 Shizuku 自动授权，失败再跳手动设置
                if (ShizukuHelper.isAvailable()) {
                    toast(R.string.granting_overlay)
                    ShizukuHelper.autoGrant(this) { ok, detail ->
                        if (ok) {
                            refreshOverlayState()
                            tryStartService()
                        } else {
                            showGrantFailure(detail)
                            openOverlaySettings()
                        }
                    }
                } else {
                    toast(R.string.grant_overlay)
                    openOverlaySettings()
                }
            } else {
                tryStartService()
            }
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, WhalePetService::class.java))
        }

        // 立即查一次余额（复用服务里的查询逻辑）
        findViewById<Button>(R.id.btn_check_balance).setOnClickListener {
            val key = etApiKey.text.toString().trim()
            if (key.isBlank()) {
                toast(R.string.no_api_key)
                return@setOnClickListener
            }
            Prefs.saveApiKey(this, key)
            tvBalance.text = getString(R.string.balance_hint)
            BalanceFetcher.fetchAsync(key) { text ->
                runOnUiThread { tvBalance.text = text }
            }
        }

        // Shizuku：注册 binder 就绪/授权结果监听。binder 就绪后自动请求授权，
        // 已授权则直接自动授予悬浮窗 + 通知权限（免手动跳设置页）。
        ShizukuHelper.init(this) { ok, detail -> onShizukuResult(ok, detail) }
    }

    /** Shizuku 自动授权结果：成功刷新界面；失败显示真实原因（便于排查）。 */
    private fun onShizukuResult(ok: Boolean, detail: String) {
        runOnUiThread {
            if (ok) {
                toast(R.string.auto_granted)
                refreshOverlayState()
            } else if (ShizukuHelper.isShizukuRunning() && ShizukuHelper.isGranted()) {
                // 已授权给本应用但自动授予仍未生效 → 命令层面失败，把 exit/err 显示出来
                showGrantFailure(detail)
            }
        }
    }

    private fun showGrantFailure(detail: String) {
        Toast.makeText(this, getString(R.string.auto_grant_failed, detail), Toast.LENGTH_LONG).show()
    }

    /** 保存 key 并启动桌宠服务。 */
    private fun tryStartService() {
        val key = etApiKey.text.toString().trim()
        if (key.isNotBlank()) Prefs.saveApiKey(this, key)
        startServiceCompat()
    }

    override fun onResume() {
        super.onResume()
        // 从 Shizuku 授权 / 系统设置页返回后重试一次
        if (ShizukuHelper.isAvailable() && !Settings.canDrawOverlays(this)) {
            ShizukuHelper.autoGrant(this) { ok, detail -> onShizukuResult(ok, detail) }
        }
        refreshOverlayState()
    }

    override fun onDestroy() {
        ShizukuHelper.release()
        super.onDestroy()
    }

    private fun refreshOverlayState() {
        if (Settings.canDrawOverlays(this)) {
            btnOverlay.setText(R.string.overlay_granted)
            btnOverlay.isEnabled = false
        } else {
            btnOverlay.setText(R.string.grant_overlay)
            btnOverlay.isEnabled = true
        }
    }

    private fun startServiceCompat() {
        val intent = Intent(this, WhalePetService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        toast(R.string.start_service)
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    /** 轻量键值存储（API key 只存本机） */
    object Prefs {
        private const val NAME = "whale_pet_prefs"
        private const val KEY_API = "deepseek_api_key"

        fun getApiKey(ctx: Context): String =
            ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getString(KEY_API, "") ?: ""

        fun saveApiKey(ctx: Context, key: String) {
            ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_API, key).apply()
        }
    }
}
