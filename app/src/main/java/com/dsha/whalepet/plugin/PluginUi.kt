package com.dsha.whalepet.plugin

import android.app.Activity
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.dsha.whalepet.R

/**
 * 插件管理界面（属于插件宿主，可自由改动）。
 *
 * App 本体只通过 [PluginEntry.showManager] 打开它，不关心界面实现。
 * 功能：插件列表（状态 / 已授权能力）、启用停用、能力授权、撤销授权、删除、
 * 粘贴 JSON 安装、一键安装内置示例。
 */
object PluginUi {

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
                "${p.name}（$state）\n$capsText"
            }.toTypedArray()
            builder.setItems(items) { _, which -> showPluginActions(activity, plugins[which]) }
        }
        builder.show()
    }

    private fun showPluginActions(activity: Activity, p: Plugin) {
        val actions = listOf(
            activity.getString(if (p.enabled) R.string.plugin_disable else R.string.plugin_enable),
            activity.getString(R.string.plugin_authorize),
            activity.getString(R.string.plugin_revoke),
            activity.getString(R.string.confirm_delete)
        ).toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(p.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        PluginManager.setEnabled(activity, p.id, !p.enabled)
                        Toast.makeText(
                            activity,
                            if (p.enabled) R.string.plugin_state_off else R.string.plugin_state_on,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    1 -> authorizePlugin(activity, p)
                    2 -> {
                        PluginManager.revokeAll(activity, p.id)
                        Toast.makeText(activity, R.string.plugin_revoke, Toast.LENGTH_SHORT).show()
                    }
                    3 -> confirmDeletePlugin(activity, p)
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

    /** 安装插件 JSON：成功后立刻进入能力授权。 */
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
        authorizePlugin(activity, p)
    }
}
