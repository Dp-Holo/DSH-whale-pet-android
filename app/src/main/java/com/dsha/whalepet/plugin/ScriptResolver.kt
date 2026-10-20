package com.dsha.whalepet.plugin

/**
 * 脚本来源的解析结果。
 *
 * 插件有三种形态，优先级固定为：**已绑定的脚本文件 > 内联 `script` > `rules`**。
 * 这里只做"该用哪一份文本"的判定（纯逻辑，可单测）；真正的文件读取在
 * [PluginManager][com.dsha.whalepet.plugin.PluginManager] 里（需要 Android Context）。
 */
sealed class ScriptSource {

    /** 内联在插件 JSON 的 `script` 字段里。 */
    data class Inline(val text: String) : ScriptSource()

    /** 来自用户通过系统文件选择器绑定的 `.js` 文件。 */
    data class File(val text: String) : ScriptSource()

    /**
     * 没有可用脚本。[reason] 会写进审计并提示用户，
     * 所以要写成用户能看懂的话（而不是异常名）。
     */
    data class Missing(val reason: String) : ScriptSource()
}

/**
 * 脚本来源的纯逻辑判定。
 *
 * 单独抽出来的原因：优先级与大小上限是"行为契约"，必须有 JVM 用例兜底；
 * 而读写文件依赖 Android 的 `ContentResolver`，放在一起就没法测。
 */
object ScriptResolver {

    /** 外部脚本文件的大小上限：256KB。插件脚本只有几百行，超出基本是选错文件。 */
    const val MAX_SCRIPT_BYTES = 256 * 1024

    /**
     * 判定用哪一份脚本。
     *
     * @param boundScriptUri 已绑定的脚本文件 URI（没绑定为 null）
     * @param fileText 该 URI 读出的文本；**读取失败/文件已失效时传 null**
     * @param inlineScript 插件 JSON 里的内联 `script`
     *
     * 规则：
     *  - 绑定了文件且读取成功 → [ScriptSource.File]（优先级最高）
     *  - 否则若内联脚本非空 → [ScriptSource.Inline]（文件失效时优雅回退）
     *  - 都没有 → [ScriptSource.Missing]，并给出可读原因
     */
    fun sourceOf(boundScriptUri: String?, fileText: String?, inlineScript: String): ScriptSource = when {
        boundScriptUri != null && fileText != null -> ScriptSource.File(fileText)
        inlineScript.isNotBlank() -> ScriptSource.Inline(inlineScript)
        boundScriptUri != null -> ScriptSource.Missing("脚本文件不可用（读取失败或已失效），请重新选择")
        else -> ScriptSource.Missing("未选择脚本文件")
    }

    /** 读到的字节数是否超出上限（读取方应多读 1 字节以便判断）。 */
    fun isOversize(byteCount: Int): Boolean = byteCount > MAX_SCRIPT_BYTES

    /** 给用户看的上限说明。 */
    fun sizeLimitText(): String = "${MAX_SCRIPT_BYTES / 1024}KB"
}
