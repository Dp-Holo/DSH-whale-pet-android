package com.dsha.whalepet

import android.content.Context
import org.json.JSONArray

/**
 * 鲸鱼娘台词池：默认台词 + 用户自定义台词（本机存储，支持增删改）。
 *
 * 在主界面以条目形式展示、编辑；服务端每次说话时重新读取，
 * 因此编辑结果无需重启桌宠即时生效。
 */
object WhaleLines {

    private const val NAME = "whale_pet_prefs"
    private const val KEY = "whale_lines"

    /** 默认台词（首次使用 / 用户清空全部时的回退池）。 */
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

    /** 读取台词池；未自定义或读取失败时回退默认台词。 */
    fun load(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return DEFAULT
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotEmpty()) list.add(s)
            }
            if (list.isEmpty()) DEFAULT else list
        } catch (t: Throwable) {
            DEFAULT
        }
    }

    /** 保存台词池（仅本机）。 */
    fun save(ctx: Context, lines: List<String>) {
        val arr = JSONArray()
        lines.forEach { arr.put(it) }
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
