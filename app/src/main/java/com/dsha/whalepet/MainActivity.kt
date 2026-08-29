package com.dsha.whalepet

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 设置页：授予悬浮窗权限、填写 DeepSeek API Key、
 * 启动/停止桌宠服务。
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val REQ_SHIZUKU = 1001
    }

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
                    ShizukuHelper.autoGrant(this) { ok ->
                        if (ok) {
                            refreshOverlayState()
                            tryStartService()
                        } else {
                            toast(R.string.grant_overlay)
                            openOverlaySettings()
                        }
                    }
                } else {
                    toast(R.string.grant_overlay)
                    openOverlaySettings()
                    return@setOnClickListener
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

        // Shizuku：若可用则自动授予悬浮窗+通知权限（免手动跳设置页）
        if (ShizukuHelper.needsPermissionRequest()) {
            ShizukuHelper.requestPermission(REQ_SHIZUKU)
        } else if (ShizukuHelper.isAvailable()) {
            tryAutoGrant()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SHIZUKU && ShizukuHelper.isAvailable()) {
            tryAutoGrant()
        }
    }

    /** 通过 Shizuku 自动授权并刷新按钮状态。 */
    private fun tryAutoGrant() {
        ShizukuHelper.autoGrant(this) { ok ->
            if (ok) {
                toast(R.string.auto_granted)
                refreshOverlayState()
            }
        }
    }

    /** 保存 key 并启动桌宠服务。 */
    private fun tryStartService() {
        val key = etApiKey.text.toString().trim()
        if (key.isNotBlank()) Prefs.saveApiKey(this, key)
        startServiceCompat()
    }

    override fun onResume() {
        super.onResume()
        // 从 Shizuku 授权/设置页返回后再次尝试自动授权
        if (ShizukuHelper.isAvailable() && !Settings.canDrawOverlays(this)) {
            tryAutoGrant()
        }
        refreshOverlayState()
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
