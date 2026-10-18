package com.dsha.whalepet.common

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/**
 * Download 目录下的纯文本日志写入器（走 MediaStore，Android 10+ 免存储权限）。
 *
 * **为什么单独放 common 包**：它同时服务两个使用方——
 *  - 运行时诊断日志（`WhalePetService`，文件名 `whale-debug.txt`）
 *  - 插件审计日志（`plugin/PluginLog`，文件名 `whale-plugins.log`）
 *
 * 两方原本各写了一套几乎相同的逻辑（查同名文件、重名归并、1MB 自清除、只删自己的文件），
 * 属于重复实现；集中到这里后，规则只需改一处。改这个文件请同时考虑两个使用方。
 *
 * 行为约定：
 *  1. **同名变体归并**：系统媒体库遇到重名会自动加 `(n)`，这里按前缀列出全部变体，
 *     只保留一个（优先标准名，其次最新），其余删除，并尝试把保留项改回标准名。
 *  2. **只处理本应用创建的文件**：查询带 `OWNER_PACKAGE_NAME = <本应用>`，
 *     避免误删用户手工做的备份（如 `xxx.log.bak` 也会命中前缀）。
 *  3. **1MB 上限自清除**：写入前估算，超过上限则清空重写，并写一行翻页标记。
 */
object MediaStoreLog {

    private const val MAX_BYTES = 1L * 1024 * 1024

    /** 文件名 → 已解析的 uri（进程内缓存，避免每次写入都查库）。 */
    private val uriCache = mutableMapOf<String, Uri>()

    /**
     * 追加文本。
     *
     * @param fileName 文件显示名（如 `whale-debug.txt`）
     * @param text 要写入的完整文本（调用方自行带换行）
     * @param rolloverMark 超过上限清空重写时写入的首行标记
     * @return 是否写入成功（失败不抛异常，调用方一般忽略）
     */
    @Synchronized
    fun append(ctx: Context, fileName: String, text: String, rolloverMark: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        // 按 UTF-8 **字节数**判断，而不是字符数：中文一个字符占 3 字节，
        // 用 text.length 会把 1MB 上限估算成实际可写约 3MB。
        val bytes = text.toByteArray()
        if (writeOnce(ctx, fileName, bytes, rolloverMark)) return true
        // 首次失败后清缓存再试一次：用户可能在文件管理器里删掉了日志文件，
        // 缓存里的 uri 已失效；不清缓存会导致日志静默丢失直到进程重启。
        uriCache.remove(fileName)
        return writeOnce(ctx, fileName, bytes, rolloverMark)
    }

    private fun writeOnce(
        ctx: Context,
        fileName: String,
        bytes: ByteArray,
        rolloverMark: String
    ): Boolean {
        return try {
            val uri = ensureUri(ctx, fileName) ?: return false
            val truncate = size(ctx, fileName) + bytes.size > MAX_BYTES
            ctx.contentResolver.openOutputStream(uri, if (truncate) "wt" else "wa")?.use { os ->
                if (truncate) {
                    os.write((rolloverMark + "\n").toByteArray())
                }
                os.write(bytes)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 当前字节数；文件不存在或读取失败按 0 处理。 */
    fun size(ctx: Context, fileName: String): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0L
        return try {
            val uri = ensureUri(ctx, fileName) ?: return 0L
            ctx.contentResolver
                .query(uri, arrayOf(MediaStore.Downloads.SIZE), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    /** 供界面提示：文件在 Download 下的路径。 */
    fun pathOf(fileName: String): String = "${Environment.DIRECTORY_DOWNLOADS}/$fileName"

    private fun ensureUri(ctx: Context, fileName: String): Uri? {
        uriCache[fileName]?.let { return it }
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
                // 只处理本应用创建的同名条目（避免误删用户手工备份）
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND " +
                    "${MediaStore.Downloads.OWNER_PACKAGE_NAME} = ?",
                arrayOf("$fileName%", ctx.packageName),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: ""
                    if (name.startsWith(fileName)) {
                        found.add(Triple(c.getLong(0), name, c.getLong(2)))
                    }
                }
            }
            val uri = if (found.isEmpty()) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                cr.insert(collection, values)
            } else {
                val keep = found.firstOrNull { it.second == fileName }
                    ?: found.maxByOrNull { it.third }!!
                // 删除其余重复变体（非本应用创建的系统会拒绝，忽略即可）
                found.filter { it.first != keep.first }.forEach { (id, _, _) ->
                    runCatching {
                        cr.delete(ContentUris.withAppendedId(collection, id), null, null)
                    }
                }
                val kept = ContentUris.withAppendedId(collection, keep.first)
                if (keep.second != fileName) {
                    // 尝试改回标准名，保证外部按固定路径能读到
                    runCatching {
                        cr.update(
                            kept,
                            ContentValues().apply {
                                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                            },
                            null,
                            null
                        )
                    }
                }
                kept
            }
            // insert 可能返回 null（系统拒绝等），此时不缓存、也不视为成功
            val resolved = uri ?: return null
            uriCache[fileName] = resolved
            resolved
        } catch (_: Throwable) {
            null
        }
    }
}
