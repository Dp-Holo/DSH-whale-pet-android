package com.dsha.whalepet

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/**
 * 插件审计日志：记录插件的每一次动作执行（谁、什么事件、什么动作、结果）。
 *
 * 独立于运行时诊断文件，写入 Download/whale-plugins.log；
 * 超过 1MB 自动清空重写（保留最新一段历史），所有重复同名变体会收敛为一个文件。
 */
object PluginLog {

    private const val FILE_NAME = "whale-plugins.log"
    private const val MAX_BYTES = 1 * 1024 * 1024

    @Volatile
    private var cachedUri: Uri? = null

    /** 追加一条审计记录（线程安全：内部加锁）。 */
    @Synchronized
    fun append(ctx: Context, line: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val cr = ctx.contentResolver
            val uri = ensureUri(ctx) ?: return
            val truncated = currentSize(ctx) + line.length > MAX_BYTES
            cr.openOutputStream(uri, if (truncated) "wt" else "wa")?.use { os ->
                if (truncated) {
                    os.write("=== audit log rolled over (max 1MB) ===\n".toByteArray())
                }
                os.write((line + "\n").toByteArray())
            }
        } catch (_: Throwable) {
        }
    }

    /** 供界面展示/排查：日志文件在 Download 下的固定路径。 */
    fun filePath(): String = "${Environment.DIRECTORY_DOWNLOADS}/$FILE_NAME"

    private fun ensureUri(ctx: Context): Uri? {
        cachedUri?.let { return it }
        return try {
            val cr = ctx.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val found = mutableListOf<Triple<Long, String, Long>>()   // (id, name, dateAdded)
            cr.query(
                collection,
                arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.DATE_ADDED
                ),
                // 只处理**本应用自己创建**的同名条目：避免误删用户手工做的备份
                // （如 whale-plugins.log.bak 也会命中前缀）
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND " +
                    "${MediaStore.Downloads.OWNER_PACKAGE_NAME} = ?",
                arrayOf("$FILE_NAME%", ctx.packageName),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: ""
                    if (name.startsWith(FILE_NAME)) {
                        found.add(Triple(c.getLong(0), name, c.getLong(2)))
                    }
                }
            }
            if (found.isEmpty()) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                cachedUri = cr.insert(collection, values)
            } else {
                val keep = found.firstOrNull { it.second == FILE_NAME }
                    ?: found.maxByOrNull { it.third }!!
                found.filter { it.first != keep.first }.forEach { (id, _, _) ->
                    runCatching {
                        cr.delete(ContentUris.withAppendedId(collection, id), null, null)
                    }
                }
                cachedUri = ContentUris.withAppendedId(collection, keep.first)
            }
            cachedUri
        } catch (_: Throwable) {
            null
        }
    }

    private fun currentSize(ctx: Context): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0L
        return try {
            val uri = ensureUri(ctx) ?: return 0L
            ctx.contentResolver
                .query(uri, arrayOf(MediaStore.Downloads.SIZE), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }
}
