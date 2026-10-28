package com.dsha.whalepet

import android.app.Activity
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.dsha.whalepet.plugin.Plugin
import com.dsha.whalepet.plugin.PluginCaps
import com.dsha.whalepet.plugin.PluginManager
import com.dsha.whalepet.plugin.PluginUi

/**
 * 插件页（独立 tab，位于主页左边）。
 *
 * 说明：本页只负责**界面**，所有数据与管理操作都调用插件宿主（`plugin/PluginManager`）的公开 API，
 * 不修改 `plugin/` 目录 —— 插件宿主自己的对话框界面（`PluginUi`）仍然保留，互不影响。
 */
object PluginPage {

    /** 内置示例插件（脚本型，只需 say 能力）。 */
    private val SAMPLE_PLUGIN_JSON = """
        {
          "id": "sample-starter",
          "name": "示例：基础互动",
          "version": 1,
          "enabled": true,
          "capabilities": ["say"],
          "script": "on('balanceDecreased', function (c) { say('正在吃掉用户token...'); }); on('batteryLow', function (c) { say('电量告急…先待机一下下'); }); on('doubleTap', function (c) { say('别点那么快嘛～'); });"
        }
    """.trimIndent()

    /** 渲染插件列表。 */
    fun render(activity: Activity, container: LinearLayout) {
        container.removeAllViews()
        val plugins = PluginManager.loadAll(activity)
        if (plugins.isEmpty()) {
            val tv = TextView(activity).apply {
                text = activity.getString(R.string.plugin_empty)
                textSize = 13f
                setTextColor(0xFF888888.toInt())
                setPadding(0, (12 * activity.resources.displayMetrics.density).toInt(), 0, 0)
            }
            container.addView(tv)
            return
        }
        val dm = activity.resources.displayMetrics
        plugins.forEach { plugin ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (10 * dm.density).toInt(), 0, (10 * dm.density).toInt())
            }
            val title = TextView(activity).apply {
                text = buildString {
                    append(plugin.name)
                    append("（")
                    append(
                        activity.getString(
                            if (plugin.enabled) R.string.plugin_state_on else R.string.plugin_state_off
                        )
                    )
                    append("）")
                }
                textSize = 14f
                setTextColor(0xFF333333.toInt())
            }
            val caps = PluginManager.granted(activity, plugin.id)
            val capsText = TextView(activity).apply {
                text = if (caps.isEmpty()) {
                    activity.getString(R.string.plugin_caps_none)
                } else {
                    caps.joinToString("、") { PluginCaps.label(it) }
                }
                textSize = 11f
                setTextColor(0xFF888888.toInt())
                setPadding(0, (2 * dm.density).toInt(), 0, 0)
            }
            row.addView(title)
            row.addView(capsText)
            // 外部脚本的绑定状态：不显示的话，用户不知道 scriptFile 插件还差一步
            PluginUi.scriptFileSummary(activity, plugin)?.let { summary ->
                row.addView(TextView(activity).apply {
                    text = summary
                    textSize = 11f
                    setTextColor(0xFF888888.toInt())
                    setPadding(0, (2 * dm.density).toInt(), 0, 0)
                })
            }
            row.setOnClickListener { showActions(activity, plugin, container) }
            container.addView(row)
        }
    }

    /** 单个插件的操作菜单。 */
    private fun showActions(activity: Activity, plugin: Plugin, container: LinearLayout) {
        // 用 (标签, 动作) 列表而不是按下标 switch：外部脚本那一项是**条件出现**的，
        // 用下标容易在增减项时错位（点"删除"却执行了别的操作）。
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions.add(
            activity.getString(
                if (plugin.enabled) R.string.plugin_disable else R.string.plugin_enable
            ) to {
                PluginManager.setEnabled(activity, plugin.id, !plugin.enabled)
                render(activity, container)
            }
        )
        actions.add(activity.getString(R.string.plugin_authorize) to {
            authorize(activity, plugin, container)
        })
        actions.add(activity.getString(R.string.plugin_revoke) to {
            PluginManager.revokeAll(activity, plugin.id)
            render(activity, container)
        })
        // 只在"涉及外部脚本"时出现：声明了 scriptFile，或已经绑过文件
        if (plugin.scriptFile != null || PluginManager.scriptUriOf(activity, plugin.id) != null) {
            actions.add(activity.getString(R.string.plugin_script_pick) to {
                PluginUi.pickScriptFile(activity, plugin) { render(activity, container) }
            })
        }
        actions.add(activity.getString(R.string.confirm_delete) to {
            confirmDelete(activity, plugin, container)
        })

        AlertDialog.Builder(activity)
            .setTitle(plugin.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.second?.invoke()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 能力授权（多选；空选择不写入授权）。 */
    private fun authorize(activity: Activity, plugin: Plugin, container: LinearLayout) {
        val caps = plugin.capabilities.toList()
        if (caps.isEmpty()) {
            Toast.makeText(activity, R.string.plugin_caps_none, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = caps.map { cap ->
            val sensitive = if (cap in PluginCaps.SENSITIVE) "（敏感：每次执行需确认）" else ""
            PluginCaps.label(cap) + sensitive
        }.toTypedArray()
        val granted = PluginManager.granted(activity, plugin.id)
        val checked = BooleanArray(caps.size) { caps[it] in granted }
        AlertDialog.Builder(activity)
            .setTitle(R.string.plugin_authorize)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.save) { _, _ ->
                val toGrant = caps.filterIndexed { i, _ -> checked[i] }
                if (toGrant.isEmpty()) {
                    Toast.makeText(activity, R.string.plugin_none_selected, Toast.LENGTH_SHORT).show()
                } else {
                    PluginManager.grant(activity, plugin.id, toGrant)
                    Toast.makeText(
                        activity,
                        activity.getString(
                            R.string.plugin_authorized,
                            toGrant.joinToString("、") { PluginCaps.label(it) }
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                    render(activity, container)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(activity: Activity, plugin: Plugin, container: LinearLayout) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.plugin_delete_title)
            .setMessage(activity.getString(R.string.plugin_delete_msg, plugin.name))
            .setPositiveButton(R.string.confirm_delete) { _, _ ->
                PluginManager.delete(activity, plugin.id)
                render(activity, container)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 粘贴 JSON 安装。 */
    fun showInstallDialog(activity: Activity, container: LinearLayout) {
        val edit = EditText(activity).apply {
            hint = activity.getString(R.string.plugin_install_hint)
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
        }
        val wrapper = FrameLayout(activity).apply {
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(edit)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.tab_plugins)
            .setView(wrapper)
            .setPositiveButton(R.string.plugin_install) { _, _ ->
                installJson(activity, edit.text.toString(), container)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun installSample(activity: Activity, container: LinearLayout) {
        installJson(activity, SAMPLE_PLUGIN_JSON, container, "内置示例")
    }

    /**
     * 从系统文件选择器导入插件。
     *
     * SAF、读取与安装都在插件宿主里（`PluginUi.installFromFile`），这里只负责
     * 刷新列表与接着走授权 —— 与粘贴安装完全同一条流程。
     */
    fun importFromFile(activity: Activity, container: LinearLayout) {
        PluginUi.installFromFile(activity) { p ->
            if (p != null) {
                render(activity, container)
                authorize(activity, p, container)
            }
        }
    }

    private fun installJson(
        activity: Activity,
        json: String,
        container: LinearLayout,
        source: String = "粘贴"
    ) {
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
        render(activity, container)
        authorize(activity, p, container)
    }
}
