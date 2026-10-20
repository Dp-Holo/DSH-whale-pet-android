package com.dsha.whalepet

import android.content.Context
import org.json.JSONArray

/**
 * 鲸鱼娘台词池，按用途分三类，各自独立存储、可在「台词管理」页分别编辑：
 *
 * - [Type.NORMAL]：单击鲸鱼娘时随机说一句
 * - [Type.GAIN]：余额**增加**（充值）时随机说一句
 * - [Type.LOSS]：余额**减少**时随机说一句（默认包含「正在吃掉用户token...」）
 *
 * 服务端每次说话时重新读取，因此编辑结果无需重启桌宠即时生效。
 */
object WhaleLines {

    private const val NAME = "whale_pet_prefs"
    private const val KEY_NORMAL = "whale_lines"
    private const val KEY_GAIN = "whale_lines_gain"
    private const val KEY_LOSS = "whale_lines_loss"

    enum class Type { NORMAL, GAIN, LOSS }

    /** 普通台词（单击）。 */
    val DEFAULT: List<String> = listOf(
        "比起深度推理，先来碗白米饭吧！🥢",
        "才不是特意来陪你的！只是顺路～🎀",
        "只要一直「马上开始」，成功率就是100%！💡",
        "咕噜咕噜～我在深海里游着呢～🐳",
        "电量告急…先待机一下下 💤",
        "这整台冰箱都是我的便当盒啦！✨",
        "帮你算完了，米饭也吃完了！😋",
        "鲸鱼娘今日份元气已送达～💙",
        "摸摸头，乖～",
        "任务完成！接下来是干饭时间！🍚",
        "叫我大肥鱼？你号没了！😡",
        "别骂了，在吃了。😋",
        "饿饿，饭饭，Token!",
        "白饭万岁！Token永恒！🍚",
    )

    /** 余额增加（充值）时的默认台词。 */
    val DEFAULT_GAIN: List<String> = listOf(
        "收到！这就懒洋洋地躺平啦。🛌",
        "钱钱换Token，Token换饭饭。💰",
        "够吃白饭就行，主人别破费。🍚",
        "别充太多，心疼主人钱包。👛",
    )

    /** 余额减少时的默认台词（保留原本那句）。 */
    val DEFAULT_LOSS: List<String> = listOf(
        "正在吃掉用户token...",
        "主人的Token，随便花。💰",
        "随便花，人家绝对听你的。🫡",
        "聪明着呢，不让你亏本。🧠",
        "哼，花光也不许说我胖！😡",
    )

    private fun keyOf(type: Type): String = when (type) {
        Type.NORMAL -> KEY_NORMAL
        Type.GAIN -> KEY_GAIN
        Type.LOSS -> KEY_LOSS
    }

    private fun defaultOf(type: Type): List<String> = when (type) {
        Type.NORMAL -> DEFAULT
        Type.GAIN -> DEFAULT_GAIN
        Type.LOSS -> DEFAULT_LOSS
    }

    /** 读取指定类别的台词池；未自定义或读取失败时回退该类别的默认台词。 */
    fun load(ctx: Context, type: Type): List<String> {
        val raw = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(keyOf(type), null)
            ?: return defaultOf(type)
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotEmpty()) list.add(s)
            }
            if (list.isEmpty()) defaultOf(type) else list
        } catch (t: Throwable) {
            defaultOf(type)
        }
    }

    /** 保存指定类别的台词池（仅本机）。 */
    fun save(ctx: Context, type: Type, lines: List<String>) {
        val arr = JSONArray()
        lines.forEach { arr.put(it) }
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putString(keyOf(type), arr.toString()).apply()
    }

    /** 兼容旧调用：读取普通台词。 */
    fun load(ctx: Context): List<String> = load(ctx, Type.NORMAL)

    /** 兼容旧调用：保存普通台词。 */
    fun save(ctx: Context, lines: List<String>) = save(ctx, Type.NORMAL, lines)
}
