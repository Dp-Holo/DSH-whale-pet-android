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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.dsha.whalepet.plugin.PluginEntry
import com.google.android.material.color.MaterialColors

/**
 * 主界面：底部导航栏分三页（主页 / 台词管理 / 设置）。
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

        /** 自动授权重试的最小间隔：避免每次回界面都执行系统命令 */
        const val AUTO_GRANT_RETRY_INTERVAL_MS = 5 * 60_000L
    }

    private lateinit var btnToggle: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnBattery: Button
    private lateinit var etApiKey: EditText
    private lateinit var tvBalance: TextView
    private lateinit var ivWhale: ImageView

    /** 底栏分页 */
    private lateinit var drawerLayout: androidx.drawerlayout.widget.DrawerLayout
    private lateinit var fabMenu: com.google.android.material.floatingactionbutton.FloatingActionButton
    private var currentPage = 1
    private var menuIconOpen = false
    private lateinit var llPlugins: LinearLayout
    /** 自动授权尝试的节流时间戳（返回界面时最多每 5 分钟试一次） */
    private var lastAutoGrantAttemptAt = 0L

    /** 台词管理：当前编辑中的台词池（与本地存储同步）。 */
    private lateinit var llLines: LinearLayout
    private val lines = mutableListOf<String>()
    /** 当前正在编辑的台词类别 */
    private var lineType = WhaleLines.Type.NORMAL
    private lateinit var tabLineNormal: Button
    private lateinit var tabLineGain: Button
    private lateinit var tabLineLoss: Button

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

        btnToggle = findViewById(R.id.btn_toggle)
        btnOverlay = findViewById(R.id.btn_overlay)
        btnBattery = findViewById(R.id.btn_battery)
        etApiKey = findViewById(R.id.et_api_key)
        tvBalance = findViewById(R.id.tv_balance)
        ivWhale = findViewById(R.id.iv_whale)
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

        // 通知设置：跳系统通知页，用户可彻底关闭常驻通知（服务不受影响）
        findViewById<Button>(R.id.btn_notif_settings).setOnClickListener {
            openNotificationSettings()
        }

        // 启停合并为一个按钮：未运行则启动（含权限检查），运行中则停止
        btnToggle.setOnClickListener {
            if (WhalePetService.isRunning) {
                stopService(Intent(this, WhalePetService::class.java))
                // 服务销毁是异步的，稍后刷新按钮文案
                btnToggle.postDelayed({ refreshToggleState() }, 400)
            } else {
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
        tabLineNormal = findViewById(R.id.tab_line_normal)
        tabLineGain = findViewById(R.id.tab_line_gain)
        tabLineLoss = findViewById(R.id.tab_line_loss)
        tabLineNormal.setOnClickListener { switchLineType(WhaleLines.Type.NORMAL) }
        tabLineGain.setOnClickListener { switchLineType(WhaleLines.Type.GAIN) }
        tabLineLoss.setOnClickListener { switchLineType(WhaleLines.Type.LOSS) }
        switchLineType(WhaleLines.Type.NORMAL)
        findViewById<View>(R.id.btn_add_line).setOnClickListener { showLineDialog(null) }

        // ── 底栏分页：主页 / 台词管理 / 设置 ──────────────────
        // 右下角菜单 + 右侧抽屉（替代原底部 tab）
        drawerLayout = findViewById(R.id.drawer_layout)
        fabMenu = findViewById(R.id.fab_menu)
        fabMenu.setOnClickListener {
            if (drawerLayout.isDrawerOpen(android.view.Gravity.END)) {
                drawerLayout.closeDrawer(android.view.Gravity.END)
            } else {
                drawerLayout.openDrawer(android.view.Gravity.END)
            }
        }
        drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerSlide(drawerView: android.view.View, slideOffset: Float) {
                // 抽屉滑出时按钮同步切换：三横线蓝底 → 叉号浅底
                val open = slideOffset > 0.5f
                if (open != menuIconOpen) {
                    menuIconOpen = open
                    // 图标旋转 90° 后替换：三横线 ↔ 叉，视觉上有"翻面"过渡
                    fabMenu.animate().rotationBy(90f).setDuration(160).withEndAction {
                        fabMenu.setImageResource(
                            if (open) R.drawable.ic_menu_close else R.drawable.ic_menu_lines
                        )
                        fabMenu.rotation = 0f
                    }.start()
                }
                fabMenu.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(
                        this@MainActivity, if (open) R.color.f2f4f5 else R.color.brand_primary
                    )
                )
                fabMenu.imageTintList = android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(
                        this@MainActivity, if (open) R.color.brand_primary else R.color.f2f4f5
                    )
                )
                // 打开时去掉阴影：与抽屉同色、无分层，视觉上"融进"菜单栏
                fabMenu.elevation = if (open) 0f else resources.displayMetrics.density * 6f
            }
        })
        findViewById<View>(R.id.menu_plugins).setOnClickListener { selectPage(0) }
        findViewById<View>(R.id.menu_home).setOnClickListener { selectPage(1) }
        findViewById<View>(R.id.menu_lines).setOnClickListener { selectPage(2) }
        findViewById<View>(R.id.menu_settings).setOnClickListener { selectPage(3) }
        showPage(1)

        // 关于鲸鱼娘桌宠（技术说明 + GitHub 链接）
        findViewById<Button>(R.id.btn_about).setOnClickListener { showAbout() }

        // 插件页（独立 tab）：只调用插件宿主的公开 API，不改插件目录
        llPlugins = findViewById(R.id.ll_plugins)
        findViewById<Button>(R.id.btn_plugin_install).setOnClickListener {
            PluginPage.showInstallDialog(this, llPlugins)
        }
        findViewById<Button>(R.id.btn_plugin_sample).setOnClickListener {
            PluginPage.installSample(this, llPlugins)
        }
        findViewById<Button>(R.id.btn_plugin_import).setOnClickListener {
            PluginPage.importFromFile(this, llPlugins)
        }
        PluginPage.render(this, llPlugins)

        // Shizuku：注册 binder 就绪/授权结果监听。binder 就绪后自动请求授权，
        // 已授权则直接自动授予悬浮窗 + 通知权限（免手动跳设置页）。
        ShizukuHelper.init(this) { ok, detail -> onShizukuResult(ok, detail) }
    }

    // ── 底栏分页 ────────────────────────────────────────────

    /** 切换页面：0=插件，1=主页，2=台词管理，3=设置。 */
    private fun showPage(index: Int) {
        findViewById<View>(R.id.page_plugins).visibility = if (index == 0) View.VISIBLE else View.GONE
        findViewById<View>(R.id.page_home).visibility = if (index == 1) View.VISIBLE else View.GONE
        findViewById<View>(R.id.page_lines).visibility = if (index == 2) View.VISIBLE else View.GONE
        findViewById<View>(R.id.page_settings).visibility = if (index == 3) View.VISIBLE else View.GONE
        currentPage = index
        updateMenuSelection(index)
    }

    /** 点击菜单项：切页并收起抽屉。 */
    private fun selectPage(index: Int) {
        showPage(index)
        drawerLayout.closeDrawer(android.view.Gravity.END)
    }

    /** 菜单选中态：左侧蓝色细竖条 + 该选项条浅蓝背景。 */
    private fun updateMenuSelection(index: Int) {
        val items = intArrayOf(
            R.id.menu_plugins, R.id.menu_home, R.id.menu_lines, R.id.menu_settings
        )
        val indicators = intArrayOf(
            R.id.ind_plugins, R.id.ind_home, R.id.ind_lines, R.id.ind_settings
        )
        val bgSelected = androidx.core.content.ContextCompat.getColor(this, R.color.menu_selected)
        items.forEachIndexed { i, id ->
            val active = i == index
            findViewById<View>(id).setBackgroundColor(
                if (active) bgSelected else android.graphics.Color.TRANSPARENT
            )
            findViewById<View>(indicators[i]).visibility =
                if (active) View.VISIBLE else View.INVISIBLE
        }
    }


    // ── 台词管理实现 ─────────────────────────────────────────

    /** 切换台词类别（普通 / 充值 / 消费），并重新加载该类别列表。 */
    private fun switchLineType(type: WhaleLines.Type) {
        lineType = type
        styleLineTab(tabLineNormal, type == WhaleLines.Type.NORMAL)
        styleLineTab(tabLineGain, type == WhaleLines.Type.GAIN)
        styleLineTab(tabLineLoss, type == WhaleLines.Type.LOSS)
        lines.clear()
        lines.addAll(WhaleLines.load(this, type))
        renderLines()
    }

    private fun styleLineTab(btn: Button, active: Boolean) {
        val attr = if (active) {
            com.google.android.material.R.attr.colorPrimary
        } else {
            com.google.android.material.R.attr.colorOnSurfaceVariant
        }
        btn.setTextColor(MaterialColors.getColor(btn, attr))
        btn.alpha = if (active) 1f else 0.7f
    }

    /** 以条目形式渲染台词列表（每项右下角：🖊 编辑 / ❌ 删除）。 */
    private fun renderLines() {
        llLines.removeAllViews()
        val inflater = LayoutInflater.from(this)
        lines.forEachIndexed { index, text ->
            val item = inflater.inflate(R.layout.item_line, llLines, false)
            item.findViewById<TextView>(R.id.line_text).text = text
            item.findViewById<View>(R.id.btn_edit_line).setOnClickListener { showLineDialog(index) }
            item.findViewById<View>(R.id.btn_del_line).setOnClickListener { confirmDeleteLine(index) }
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
        WhaleLines.save(this, lineType, lines)
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
        // 服务启动是异步的，稍后刷新按钮文案为「停止桌宠」
        btnToggle.postDelayed({ refreshToggleState() }, 400)
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
        // 从 Shizuku 授权 / 系统设置页返回后重试一次；加节流，
        // 避免每次回到界面都去执行一次系统授权命令（过于激进且无意义）
        val now = System.currentTimeMillis()
        if (ShizukuHelper.isAvailable() && !Settings.canDrawOverlays(this) &&
            now - lastAutoGrantAttemptAt >= AUTO_GRANT_RETRY_INTERVAL_MS
        ) {
            lastAutoGrantAttemptAt = now
            ShizukuHelper.autoGrant(this) { ok, detail -> onShizukuResult(ok, detail) }
        }
        refreshOverlayState()
        refreshBatteryState()
        refreshToggleState()
    }

    override fun onDestroy() {
        ShizukuHelper.release()
        super.onDestroy()
    }

    /** 悬浮窗权限按钮：已授予则从主界面隐藏。 */
    private fun refreshOverlayState() {
        if (Settings.canDrawOverlays(this)) {
            btnOverlay.visibility = View.GONE
        } else {
            btnOverlay.visibility = View.VISIBLE
            btnOverlay.setText(R.string.grant_overlay)
            btnOverlay.isEnabled = true
        }
    }

    /** 电池白名单按钮：已加入则从主界面隐藏。 */
    private fun refreshBatteryState() {
        if (ShizukuHelper.isIgnoringBatteryOptimizations(this)) {
            btnBattery.visibility = View.GONE
        } else {
            btnBattery.visibility = View.VISIBLE
            btnBattery.setText(R.string.battery_whitelist)
            btnBattery.isEnabled = true
        }
    }

    /** 启停按钮文案随服务运行状态切换。 */
    private fun refreshToggleState() {
        btnToggle.setText(if (WhalePetService.isRunning) R.string.stop_service else R.string.start_service)
        // 桌宠运行时主页图片让位（启动瞬间已隐藏）；停止后恢复显示
        ivWhale.visibility = if (WhalePetService.isRunning) View.INVISIBLE else View.VISIBLE
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

    /** 跳系统通知设置页：用户可关闭常驻通知（前台服务仍会继续运行）。 */
    private fun openNotificationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (_: Throwable) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Throwable) {
            }
        }
    }

    /** 关于对话框：版本、来源、隐私、许可与仓库入口（分段排版；版本号自动读取，避免手写遗漏）。 */
    private fun showAbout() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Throwable) {
            ""
        }
        val view = layoutInflater.inflate(R.layout.dialog_about, null)
        view.findViewById<TextView>(R.id.tv_about_version).text =
            getString(R.string.about_version_format, version)
        view.findViewById<TextView>(R.id.tv_about_desc).text = getString(R.string.about_desc)
        view.findViewById<TextView>(R.id.tv_about_privacy_title).text =
            getString(R.string.about_privacy_title)
        view.findViewById<TextView>(R.id.tv_about_privacy_body).text =
            getString(R.string.about_privacy_body)
        view.findViewById<TextView>(R.id.tv_about_license_title).text =
            getString(R.string.about_license_title)
        view.findViewById<TextView>(R.id.tv_about_license_body).text =
            getString(R.string.about_license_body)
        view.findViewById<TextView>(R.id.tv_about_repo_title).text =
            getString(R.string.about_repo_title)
        val repo = view.findViewById<TextView>(R.id.tv_about_repo_body)
        repo.text = getString(R.string.about_repo_body)
        repo.setOnClickListener { openGithub() }   // 地址本身可点，省去再找按钮

        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setView(view)
            .setPositiveButton(R.string.about_open_github) { _, _ -> openGithub() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openGithub() {
        try {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/Dp-Holo/DSH-whale-pet-android")
                )
            )
        } catch (_: Throwable) {
        }
    }

    private fun startServiceCompat() {
        val intent = Intent(this, WhalePetService::class.java)
        // 把主页鲸鱼娘图片的屏幕中心位置传过去：悬浮窗先从"图上"出现，再淡出图片
        if (ivWhale.width > 0) {
            val loc = IntArray(2)
            ivWhale.getLocationOnScreen(loc)
            intent.putExtra(WhalePetService.EXTRA_START_CENTER_X, loc[0] + ivWhale.width / 2)
            intent.putExtra(WhalePetService.EXTRA_START_CENTER_Y, loc[1] + ivWhale.height / 2)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        // 0.05 秒后隐藏图片，让悬浮窗的鲸鱼娘"接替"她的位置
        ivWhale.postDelayed({ ivWhale.visibility = View.INVISIBLE }, 50)
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
