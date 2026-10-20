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

    private var pickerOwner: Activity? = null
    private var picker: ActivityResultLauncher<Array<String>>? = null
    private var onPicked: ((Uri?) -> Unit)? = null

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
                installPluginJson(activity, SAMPLE_PLUGIN_JSON)
            }
            .setNeutralButton(R.string.plugin_install) { _, _ -> showPasteInstallDialog(activity) }
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
                val declared = p.scriptFile
                val scriptText = if (declared == null) {
                    null
                } else {
                    val bound = PluginManager.scriptFileNameOf(activity, p.id)
                    if (bound != null) {
                        activity.getString(R.string.plugin_script_file_bound, bound)
                    } else {
                        activity.getString(R.string.plugin_script_file_unbound, declared)
                    }
                }
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
    private fun obtainPicker(activity: Activity): ActivityResultLauncher<Array<String>>? {
        picker?.let { if (pickerOwner === activity) return it }
        val component = activity as? ComponentActivity ?: return null
        return try {
            val launcher = component.activityResultRegistry.register(
                SCRIPT_PICK_KEY,
                ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                val callback = onPicked
                onPicked = null
                callback?.invoke(uri)
            }
            picker = launcher
            pickerOwner = activity
            launcher
        } catch (_: Throwable) {
            // 注册失败（极端情况）：退化为"不支持选择"，不要让界面崩掉
            null
        }
    }

    /**
     * 启动系统文件选择器让用户选 `.js`。
     *
     * @param then 选完（或取消）后要接着做的事，安装后的"先选文件再授权"用它
     */
    private fun pickScriptFile(activity: Activity, p: Plugin, then: (() -> Unit)? = null) {
        val launcher = obtainPicker(activity)
        if (launcher == null) {
            Toast.makeText(activity, R.string.plugin_script_pick_unsupported, Toast.LENGTH_LONG).show()
            then?.invoke()
            return
        }
        onPicked = { uri: Uri? ->
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
        try {
            // 很多文件管理器把 .js 标成 text/plain 或 application/octet-stream，用 */* 最稳
            launcher.launch(arrayOf("*/*"))
        } catch (t: Throwable) {
            onPicked = null
            Toast.makeText(activity, R.string.plugin_script_pick_unsupported, Toast.LENGTH_LONG).show()
            then?.invoke()
        }
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
    private fun installPluginJson(activity: Activity, json: String) {
        val p = PluginManager.install(activity, json)
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
