package com.dsha.whalepet.plugin

import android.app.Activity
import android.net.Uri
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.dsha.whalepet.R

/**
 * 插件管理界面（属于插件宿主，可自由改动）。
 *
 * App 本体只通过 [PluginEntry.showManager] 打开它，不关心界面实现。
 * 功能：插件列表（状态 / 已授权能力 / 脚本来源）、启用停用、能力授权、撤销授权、
 * 选择脚本文件、删除、粘贴 JSON 安装、一键安装内置示例。
 */
object PluginUi {

    /**
     * 选脚本文件用的注册 key。
     *
     * 用 [androidx.activity.result.ActivityResultRegistry.register] 的**不带 LifecycleOwner**
     * 的重载：插件管理是在 `RESUMED` 状态下才打开的，常规 `registerForActivityResult`
     * 会抛 "must call register before STARTED"。这个重载可以在任意时机注册。
     */
    private const val SCRIPT_PICK_KEY = "whale-plugin-script-pick"

    /** 导入插件 JSON 用的 key：与选脚本文件分开，结果不会串到对方回调里。 */
    private const val IMPORT_PICK_KEY = "whale-plugin-import-json"

    /** launcher 与 Activity 绑定：换 Activity 后旧 launcher 的回调会走空，必须重注册。 */
    private var pickerOwner: Activity? = null
    private val pickers = mutableMapOf<String, ActivityResultLauncher<Array<String>>>()
    private val pendingPicks = mutableMapOf<String, (Uri?) -> Unit>()

    /** 内置示例插件：只依赖 say，作为"链路是否通"的最小验证。 */
    private val SAMPLE_PLUGIN_JSON = """
        {
          "id": "sample-starter",
          "name": "示例：基础互动",
          "version": 1,
          "enabled": true,
          "capabilities": ["say"],
          "script": "on('balanceDecreased', function (c) { say('正在偷吃用户token...'); }); on('batteryLow', function (c) { say('电量告急…先待机一下下'); }); on('doubleTap', function (c) { say('别点那么快嘛～'); });"
        }
    """.trimIndent()

    fun show(activity: Activity) {
        val plugins = PluginManager.loadAll(activity)
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.plugins_title)
            .setPositiveButton(R.string.plugin_install_sample) { _, _ ->
                installPluginJson(activity, SAMPLE_PLUGIN_JSON, "内置示例")
            }
            .setNeutralButton(R.string.plugin_install) { _, _ -> showInstallChooser(activity) }
            .setNegativeButton(R.string.cancel, null)
        if (plugins.isEmpty()) {
            builder.setMessage(R.string.plugin_empty)
        } else {
            val items = plugins.map { p ->
                val state = activity.getString(
                    if (p.enabled) R.string.plugin_state_on else R.string.plugin_state_off
                )
                val caps = PluginManager.granted(activity, p.id)
                val capsText = if (caps.isEmpty()) {
                    activity.getString(R.string.plugin_caps_none)
                } else {
                    caps.joinToString("、") { PluginCaps.label(it) }
                }
                // 外部脚本插件额外显示绑定情况：未绑定要显式提示，否则用户不知道少做了什么
                val scriptText = scriptFileSummary(activity, p)
                if (scriptText == null) {
                    "${p.name}（$state）\n$capsText"
                } else {
                    "${p.name}（$state）\n$capsText\n$scriptText"
                }
            }.toTypedArray()
            builder.setItems(items) { _, which -> showPluginActions(activity, plugins[which]) }
        }
        builder.show()
    }

    /** 插件操作菜单的一项。用列表而不是下标 switch：脚本文件那项是条件出现的。 */
    private class Action(val label: String, val perform: () -> Unit)

    private fun showPluginActions(activity: Activity, p: Plugin) {
        // 用 (标签, 动作) 列表而不是按下标 switch：脚本文件那一项是**条件出现**的，
        // 用下标很容易在增减项时错位（点"删除"结果执行了别的操作）。
        val actions = mutableListOf<Action>()
        actions += Action(
            activity.getString(if (p.enabled) R.string.plugin_disable else R.string.plugin_enable)
        ) {
            PluginManager.setEnabled(activity, p.id, !p.enabled)
            Toast.makeText(
                activity,
                if (p.enabled) R.string.plugin_state_off else R.string.plugin_state_on,
                Toast.LENGTH_SHORT
            ).show()
        }
        actions += Action(activity.getString(R.string.plugin_authorize)) {
            authorizePlugin(activity, p)
        }
        actions += Action(activity.getString(R.string.plugin_revoke)) {
            PluginManager.revokeAll(activity, p.id)
            Toast.makeText(activity, R.string.plugin_revoke, Toast.LENGTH_SHORT).show()
        }
        // 只在"涉及外部脚本"时出现：声明了 scriptFile，或已经绑过文件
        if (p.scriptFile != null || PluginManager.scriptUriOf(activity, p.id) != null) {
            actions += Action(activity.getString(R.string.plugin_script_pick)) {
                pickScriptFile(activity, p)
            }
        }
        actions += Action(activity.getString(R.string.confirm_delete)) {
            confirmDeletePlugin(activity, p)
        }

        AlertDialog.Builder(activity)
            .setTitle(p.name)
            .setItems(actions.map { it.label }.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.perform?.invoke()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ── 选择外部脚本文件（SAF）────────────────────────────────

    /**
     * 拿到（必要时先注册）文件选择器。
     *
     * 必须在 Activity 变更时重新注册：`activityResultRegistry` 是 Activity 私有的，
     * 旧 launcher 绑定的是旧 Activity 的 registry，回调会走空。
     */
    private fun obtainPicker(activity: Activity, key: String): ActivityResultLauncher<Array<String>>? {
        if (pickerOwner !== activity) {
            // 换了 Activity：旧 launcher 绑的是旧 registry，回调走空，全部重来
            pickers.clear()
            pendingPicks.clear()
            pickerOwner = activity
        }
        pickers[key]?.let { return it }
        val component = activity as? ComponentActivity ?: return null
        return try {
            val launcher = component.activityResultRegistry.register(
                key,
                ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                val callback = pendingPicks.remove(key)
                callback?.invoke(uri)
            }
            pickers[key] = launcher
            launcher
        } catch (_: Throwable) {
            // 注册失败（极端情况）：退化为"不支持选择"，不要让界面崩掉
            null
        }
    }

    /**
     * 打开系统文件选择器。
     *
     * @param key 注册 key，不同用途用不同 key，避免结果落到对方的回调里
     * @param onResult 选中（或取消）后回调；取消时 uri 为 null
     */
    private fun pickFile(
        activity: Activity,
        key: String,
        onResult: (Uri?) -> Unit
    ) {
        val launcher = obtainPicker(activity, key)
        if (launcher == null) {
            Toast.makeText(activity, R.string.plugin_script_pick_unsupported, Toast.LENGTH_LONG).show()
            onResult(null)
            return
        }
        pendingPicks[key] = onResult
        try {
            // 文件管理器对 .js/.json 的 MIME 判断普遍不可靠，统一放开 */*
            launcher.launch(arrayOf("*/*"))
        } catch (t: Throwable) {
            pendingPicks.remove(key)
            Toast.makeText(activity, R.string.plugin_script_pick_unsupported, Toast.LENGTH_LONG).show()
            onResult(null)
        }
    }

    /**
     * 让用户选外部脚本文件（`.js`）。
     *
     * **对外公开**：插件管理搬到独立页（`PluginPage`，App 侧）后，那一页也要能绑定脚本文件，
     * 否则 `scriptFile` 插件装上去就永远绑不到文件、等于装了不能用。
     * SAF 与读取逻辑留在这里，页面侧只调这一个方法。
     *
     * @param then 选完（或取消）后要接着做的事，安装后的"先选文件再授权"用它
     */
    fun pickScriptFile(activity: Activity, p: Plugin, then: (() -> Unit)? = null) {
        pickFile(activity, SCRIPT_PICK_KEY) { uri: Uri? ->
            if (uri == null) {
                then?.invoke()
            } else {
                // 绑定会**试读一次**文件（上限 256KB）。SAF 的 provider 可能是网盘，
                // 在主线程读会阻塞到 ANR —— 所以整段放后台线程，结果再回主线程提示。
                Thread {
                    val ok = PluginManager.bindScriptUri(activity, p.id, uri)
                    activity.runOnUiThread {
                        Toast.makeText(
                            activity,
                            if (ok) R.string.plugin_script_pick_ok else R.string.plugin_script_pick_failed,
                            Toast.LENGTH_LONG
                        ).show()
                        then?.invoke()
                    }
                }.start()
            }
        }
    }

    /**
     * 从系统文件选择器导入插件 JSON（省去手打/粘贴一长串）。
     *
     * 读到内容后走**与粘贴完全相同**的安装流程，所以能力授权、`scriptFile` 选文件、
     * id 校验、覆盖安装清授权这些都自动继承，不会出现"导入的插件少了一道关"。
     */
    private fun importPluginFromFile(activity: Activity) {
        pickFile(activity, IMPORT_PICK_KEY) { uri: Uri? ->
            if (uri != null) {
                // 读文件放后台：SAF 的 provider 可能是网盘，主线程读会 ANR
                Thread {
                    val (text, error) = PluginManager.readPickedText(activity, uri)
                    activity.runOnUiThread {
                        if (text == null) {
                            Toast.makeText(
                                activity,
                                activity.getString(
                                    R.string.plugin_import_read_failed, error ?: "未知原因"
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            installPluginJson(activity, text, "文件导入")
                        }
                    }
                }.start()
            }
        }
    }

    // ── 给 App 侧插件页（PluginPage）用的最小入口 ──────────────
    //
    // 插件管理搬到独立页之后，原先挂在本对话框里的"从文件导入"与"绑定脚本文件"
    // 都失去了入口：`scriptFile` 插件在那一页装上去永远绑不到文件，装了不能用。
    // 这里只暴露入口，SAF/读取/安装逻辑仍留在插件宿主内，页面侧一行调用即可。

    /**
     * 插件行上"外部脚本"的摘要文案；不涉及外部脚本时返回 null。
     *
     * 两处界面（旧对话框 / 新插件页）共用同一份文案，避免说法不一致。
     */
    fun scriptFileSummary(activity: Activity, plugin: Plugin): String? {
        val declared = plugin.scriptFile ?: return null
        val bound = PluginManager.scriptFileNameOf(activity, plugin.id)
        return if (bound != null) {
            activity.getString(R.string.plugin_script_file_bound, bound)
        } else {
            activity.getString(R.string.plugin_script_file_unbound, declared)
        }
    }

    /**
     * 从系统文件选择器导入插件。
     *
     * 与粘贴走**同一套安装流程**；若插件声明了 `scriptFile`，导入后紧接着要求绑定脚本文件
     * ——否则它装是装上了，但没有任何可执行内容。
     *
     * @param onDone 回调带回安装好的插件；取消/读取失败/解析失败为 null
     */
    fun installFromFile(activity: Activity, onDone: (Plugin?) -> Unit) {
        pickFile(activity, IMPORT_PICK_KEY) { uri: Uri? ->
            if (uri == null) {
                onDone(null)
                return@pickFile
            }
            // 读文件放后台：SAF 的 provider 可能是网盘，主线程读会 ANR
            Thread {
                val (text, error) = PluginManager.readPickedText(activity, uri)
                activity.runOnUiThread {
                    if (text == null) {
                        Toast.makeText(
                            activity,
                            activity.getString(
                                R.string.plugin_import_read_failed, error ?: "未知原因"
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                        onDone(null)
                        return@runOnUiThread
                    }
                    val p = PluginManager.install(activity, text, "文件导入")
                    if (p == null) {
                        Toast.makeText(activity, R.string.plugin_install_failed, Toast.LENGTH_LONG).show()
                        onDone(null)
                        return@runOnUiThread
                    }
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.plugin_installed, p.name),
                        Toast.LENGTH_SHORT
                    ).show()
                    if (p.scriptFile != null) {
                        pickScriptFile(activity, p) { onDone(p) }
                    } else {
                        onDone(p)
                    }
                }
            }.start()
        }
    }

    /** 安装入口的二级选择：粘贴 JSON / 从文件导入。 */
    private fun showInstallChooser(activity: Activity) {
        val labels = arrayOf(
            activity.getString(R.string.plugin_install_hint),
            activity.getString(R.string.plugin_import_from_file)
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.plugin_install)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> showPasteInstallDialog(activity)
                    1 -> importPluginFromFile(activity)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 能力授权：多选；敏感能力标注"每次需确认"；**未选择任何能力时不授权**。 */
    private fun authorizePlugin(activity: Activity, p: Plugin) {
        val caps = p.capabilities.toList()
        if (caps.isEmpty()) {
            Toast.makeText(activity, R.string.plugin_caps_none, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = caps.map { cap ->
            val sensitive = if (cap in PluginCaps.SENSITIVE) "（敏感：每次执行需确认）" else ""
            PluginCaps.label(cap) + sensitive
        }.toTypedArray()
        val granted = PluginManager.granted(activity, p.id)
        val checked = BooleanArray(caps.size) { caps[it] in granted }
        AlertDialog.Builder(activity)
            .setTitle(R.string.plugin_authorize)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.save) { _, _ ->
                val toGrant = caps.filterIndexed { i, _ -> checked[i] }
                if (toGrant.isEmpty()) {
                    // 空选择：不写入授权，明确告知"你没选"
                    // （此前复用 plugin_caps_none「未声明能力」，读起来像在说插件有问题）
                    Toast.makeText(activity, R.string.plugin_none_selected, Toast.LENGTH_SHORT).show()
                } else {
                    PluginManager.grant(activity, p.id, toGrant)
                    val text = toGrant.joinToString("、") { PluginCaps.label(it) }
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.plugin_authorized, text),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeletePlugin(activity: Activity, p: Plugin) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.plugin_delete_title)
            .setMessage(activity.getString(R.string.plugin_delete_msg, p.name))
            .setPositiveButton(R.string.confirm_delete) { _, _ ->
                PluginManager.delete(activity, p.id)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showPasteInstallDialog(activity: Activity) {
        val edit = EditText(activity).apply {
            hint = activity.getString(R.string.plugin_install_hint)
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
        }
        val container = FrameLayout(activity).apply {
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(edit)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.plugins_title)
            .setView(container)
            .setPositiveButton(R.string.plugin_install) { _, _ ->
                installPluginJson(activity, edit.text.toString())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 安装插件 JSON：成功后进入后续流程。
     *
     * 声明了 `scriptFile` 的插件**先选脚本文件再授权**：没有脚本内容时，
     * 授权了也没有可执行的东西（`isScript` 为真但加载会失败）。
     */
    private fun installPluginJson(activity: Activity, json: String, source: String = "粘贴") {
        val p = PluginManager.install(activity, json, source)
        if (p == null) {
            Toast.makeText(activity, R.string.plugin_install_failed, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(
            activity,
            activity.getString(R.string.plugin_installed, p.name),
            Toast.LENGTH_SHORT
        ).show()
        if (p.scriptFile != null) {
            pickScriptFile(activity, p) { authorizePlugin(activity, p) }
        } else {
            authorizePlugin(activity, p)
        }
    }
}
