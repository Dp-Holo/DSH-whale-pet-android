package com.dsha.whalepet

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.MaterialColors

/**
 * 主界面：底部导航栏分两页。
 *  - 第一页（设置）：悬浮窗权限、DeepSeek API Key、启停桌宠
 *  - 第二页（台词管理）：条目列表，🖊 编辑 / ❌ 删除（二次确认）/ ➕ 添加
 *
 * Shizuku 集成注意：binder 异步到达，授权结果经 Shizuku 专用监听回调，
 * 因此统一交给 ShizukuHelper.init() 处理；不再自行 pingBinder 一次了事，
 * 也不依赖 Activity.onRequestPermissionsResult（Shizuku 不走该通道）。
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        /** 通知权限请求码（Android 13+ 运行时权限） */
        const val REQ_NOTIF = 1002
    }

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnBattery: Button
    private lateinit var etApiKey: EditText
    private lateinit var tvBalance: TextView

    /** 底栏分页 */
    private lateinit var tabHome: TextView
    private lateinit var tabLines: TextView

    /** 台词管理：当前编辑中的台词池（与本地存储同步）。 */
    private lateinit var llLines: LinearLayout
    private val lines = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 适配 edge-to-edge：内容整体避开顶部状态栏与底部手势小白条
        val rootLayout = findViewById<View>(R.id.root_layout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        btnOverlay = findViewById(R.id.btn_overlay)
        btnBattery = findViewById(R.id.btn_battery)
        etApiKey = findViewById(R.id.et_api_key)
        tvBalance = findViewById(R.id.tv_balance)
        llLines = findViewById(R.id.ll_lines)

        // 回填已保存的 key
        etApiKey.setText(Prefs.getApiKey(this))

        btnOverlay.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                toast(R.string.overlay_granted)
            } else {
                openOverlaySettings()
            }
        }

        // 电池优化白名单：优先用 Shizuku 自动加入，否则走系统授权框
        btnBattery.setOnClickListener {
            if (ShizukuHelper.isIgnoringBatteryOptimizations(this)) {
                toast(R.string.battery_whitelisted)
            } else if (ShizukuHelper.isAvailable()) {
                toast(R.string.battery_granting)
                ShizukuHelper.addToBatteryWhitelist(this) { ok, detail ->
                    if (ok) {
                        toast(R.string.battery_whitelisted)
                        refreshBatteryState()
                    } else {
                        Toast.makeText(
                            this,
                            getString(R.string.battery_whitelist_failed, detail),
                            Toast.LENGTH_LONG
                        ).show()
                        openBatterySettings()
                    }
                }
            } else {
                openBatterySettings()
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
            setBalanceText(getString(R.string.balance_hint))
            BalanceFetcher.fetchAsync(key) { text ->
                runOnUiThread { setBalanceText(text) }
            }
        }

        // ── 台词管理（第二页）────────────────────────────────
        lines.clear()
        lines.addAll(WhaleLines.load(this))
        renderLines()
        findViewById<Button>(R.id.btn_add_line).setOnClickListener { showLineDialog(null) }

        // ── 底栏分页：设置 / 台词管理 ────────────────────────
        tabHome = findViewById(R.id.tab_home)
        tabLines = findViewById(R.id.tab_lines)
        tabHome.setOnClickListener { showPage(0) }
        tabLines.setOnClickListener { showPage(1) }
        showPage(0)

        // Shizuku：注册 binder 就绪/授权结果监听。binder 就绪后自动请求授权，
        // 已授权则直接自动授予悬浮窗 + 通知权限（免手动跳设置页）。
        ShizukuHelper.init(this) { ok, detail -> onShizukuResult(ok, detail) }
    }

    // ── 底栏分页 ────────────────────────────────────────────

    /** 切换页面：0=设置，1=台词管理。 */
    private fun showPage(index: Int) {
        findViewById<View>(R.id.page_home).visibility = if (index == 0) View.VISIBLE else View.GONE
        findViewById<View>(R.id.page_lines).visibility = if (index == 1) View.VISIBLE else View.GONE
        styleTab(tabHome, index == 0)
        styleTab(tabLines, index == 1)
    }

    private fun styleTab(tab: TextView, active: Boolean) {
        val attr = if (active) {
            com.google.android.material.R.attr.colorPrimary
        } else {
            com.google.android.material.R.attr.colorOnSurfaceVariant
        }
        tab.setTextColor(MaterialColors.getColor(tab, attr))
        tab.alpha = if (active) 1f else 0.7f
    }

    // ── 台词管理实现 ─────────────────────────────────────────

    /** 以条目形式渲染台词列表（每项右下角：🖊 编辑 / ❌ 删除）。 */
    private fun renderLines() {
        llLines.removeAllViews()
        val inflater = LayoutInflater.from(this)
        lines.forEachIndexed { index, text ->
            val item = inflater.inflate(R.layout.item_line, llLines, false)
            item.findViewById<TextView>(R.id.line_text).text = text
            item.findViewById<Button>(R.id.btn_edit_line).setOnClickListener { showLineDialog(index) }
            item.findViewById<Button>(R.id.btn_del_line).setOnClickListener { confirmDeleteLine(index) }
            llLines.addView(item)
        }
    }

    /** 添加（index=null）或编辑（index=条目标号）台词。 */
    private fun showLineDialog(index: Int?) {
        val isEdit = index != null
        val edit = EditText(this).apply {
            setText(if (isEdit) lines[index!!] else "")
            hint = getString(if (isEdit) R.string.edit_line_hint else R.string.add_line_hint)
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(edit)
        }
        AlertDialog.Builder(this)
            .setTitle(if (isEdit) R.string.edit_line_hint else R.string.add_line_hint)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = edit.text.toString().trim()
                if (text.isEmpty()) {
                    toast(R.string.line_empty_warn)
                } else {
                    if (isEdit) lines[index!!] = text else lines.add(text)
                    persistLines()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 删除台词：二次确认后生效。 */
    private fun confirmDeleteLine(index: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_line_title)
            .setMessage(getString(R.string.delete_line_msg, lines[index]))
            .setPositiveButton(R.string.confirm_delete) { _, _ ->
                lines.removeAt(index)
                persistLines()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 保存到本机并刷新列表（桌宠服务每次说话都重新读取，无需重启即生效）。 */
    private fun persistLines() {
        WhaleLines.save(this, lines)
        renderLines()
    }

    // ── Shizuku / 权限 ──────────────────────────────────────

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
        requestNotificationPermissionIfNeeded()
        startServiceCompat()
    }

    /** Android 13+ 通知权限：无 Shizuku 的用户也要能授权（否则常驻通知不显示）。 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIF
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // 从 Shizuku 授权 / 系统设置页返回后重试一次
        if (ShizukuHelper.isAvailable() && !Settings.canDrawOverlays(this)) {
            ShizukuHelper.autoGrant(this) { ok, detail -> onShizukuResult(ok, detail) }
        }
        refreshOverlayState()
        refreshBatteryState()
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

    /** 电池优化白名单按钮状态（已加入则显示为完成态）。 */
    private fun refreshBatteryState() {
        if (ShizukuHelper.isIgnoringBatteryOptimizations(this)) {
            btnBattery.setText(R.string.battery_whitelisted)
            btnBattery.isEnabled = false
        } else {
            btnBattery.setText(R.string.battery_whitelist)
            btnBattery.isEnabled = true
        }
    }

    /** 无 Shizuku 或自动加入失败时，跳系统电池优化授权框/列表。 */
    private fun openBatterySettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Throwable) {
            }
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

    /** 更新主界面余额文字。 */
    private fun setBalanceText(text: String) {
        tvBalance.text = text
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
