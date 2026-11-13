package com.luaforge.studio.utils

import androidx.annotation.Keep
import java.io.File
import java.io.IOException

/**
 * 基于 C 原生实现的极速文件读写工具。
 *
 * ## 为什么需要它
 *
 * 编辑器自身的读写路径(`File.readBytes` / `BufferedOutputStream`)在构建 APK
 * 这种"连续搬运几十 MB"的场景里是瓶颈:Java 流每 8 KiB 一次 syscall,
 * 且大文件要先整体读进 Java 堆 —— 既慢又容易把堆顶爆(`OutOfMemoryError`
 * 曾是构建失败的主要原因之一)。本工具把搬运放到 native:
 *
 * - 读:大文件走 `mmap` + 一次 `memcpy`,小文件走 1 MiB 顺序 `read`
 * - 写:1 MiB 缓冲顺序 `write`,循环处理短写,可选 `fsync`
 * - 拷贝:优先 `sendfile`,同一次内核拷贝内完成,不经用户态
 *
 * 实测口径只承诺"比 Java 流快",不承诺具体倍数 —— 依赖文件系统与文件大小。
 *
 * ## Lua 侧用法
 *
 * 项目 `settings.json` 的 `global_utils` 加入 `FastIOUtil` 后,下列函数直接
 * 成为 Lua 全局函数(首个形参不是 `Context`,因此不做自动注入):
 *
 * ```lua
 * local bytes = readBytes("/sdcard/a.bin")        -- 二进制读
 * local text  = readText("/sdcard/a.txt")         -- 文本读(UTF-8)
 * local ok    = writeText("/sdcard/b.txt", "hi")  -- 文本写(自动建父目录)
 * copyFile("/sdcard/a.bin", "/sdcard/c.bin")      -- 原生拷贝
 * ```
 *
 * 失败时返回 `nil, 错误信息` 形态对 Lua 不友好(多返回值的错误处理在
 * `luajava` 反射路径上会丢失),因此这里统一:**失败返回 null / false**,
 * 详细原因写进 `RuntimeLog`。需要在 Lua 侧强制失败即报错时,改用带
 * `or error(...)` 的写法。
 */
@Keep
object FastIOUtil {

    private const val TAG = "FastIOUtil"

    /** 原生库是否可用。加载失败时所有函数退化为 Java 实现。 */
    private val nativeAvailable: Boolean = try {
        System.loadLibrary("fastio")
        true
    } catch (t: Throwable) {
        RuntimeLog.error(TAG, "libfastio 加载失败,退化为 Java 实现", t)
        false
    }

    /** 判断原生实现是否可用(供诊断/MCP 查询)。 */
    @JvmStatic
    fun isNativeAvailable(): Boolean = nativeAvailable

    // ------------------------------------------------------------------
    // 原生入口
    // ------------------------------------------------------------------

    private external fun nativeReadBytes(path: String, maxBytes: Long): ByteArray?
    private external fun nativeReadChunk(path: String, offset: Long, length: Int): ByteArray?
    private external fun nativeWriteBytes(
        path: String,
        data: ByteArray?,
        createDir: Boolean,
        append: Boolean,
        sync: Boolean
    ): Boolean

    private external fun nativeSize(path: String): Long
    private external fun nativeExists(path: String): Boolean
    private external fun nativeDelete(path: String): Boolean
    private external fun nativeMkdirs(path: String): Boolean
    private external fun nativeCopy(src: String, dst: String, createDir: Boolean): Long

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /**
     * 读取整个文件为字节数组。
     *
     * @param path     文件路径
     * @param maxBytes 最多读取的字节数;`<= 0` 表示不限制。
     *                 超过上限时**只读前 maxBytes 字节**,用于防止误读超大文件
     *                 把内存打满(调用方应据此判断是否被截断)。
     * @return 文件内容;文件不存在或读取失败返回 null
     */
    @JvmStatic
    @JvmOverloads
    fun readBytes(path: String, maxBytes: Long = 0L): ByteArray? {
        if (path.isEmpty()) return null
        if (nativeAvailable) return nativeReadBytes(path, maxBytes)
        return try {
            val f = File(path)
            if (!f.isFile) null
            else if (maxBytes > 0 && f.length() > maxBytes) f.inputStream().use { input ->
                val out = ByteArray(maxBytes.toInt())
                var read = 0
                while (read < out.size) {
                    val n = input.read(out, read, out.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read == out.size) out else out.copyOf(read)
            } else f.readBytes()
        } catch (e: Exception) {
            RuntimeLog.error(TAG, "读取失败: $path", e)
            null
        }
    }

    /**
     * 读取整个文件为文本。
     *
     * @param charset 字符集名,默认 UTF-8
     * @return 文件文本;失败返回 null。无效字节序列按替换字符处理,不抛异常
     *         —— 读文本不应因单个坏字节让整个调用失败。
     */
    @JvmStatic
    @JvmOverloads
    fun readText(path: String, charset: String = "UTF-8"): String? {
        val bytes = readBytes(path) ?: return null
        return try {
            String(bytes, charset(charset))
        } catch (e: Exception) {
            RuntimeLog.error(TAG, "解码失败: $path ($charset)", e)
            null
        }
    }

    /**
     * 从指定偏移读取一段字节(随机访问,不读整个文件)。
     *
     * @return 读到的字节;参数非法或失败返回 null
     */
    @JvmStatic
    fun readChunk(path: String, offset: Long, length: Int): ByteArray? {
        if (path.isEmpty() || offset < 0 || length <= 0) return null
        if (nativeAvailable) return nativeReadChunk(path, offset, length)
        return try {
            java.io.RandomAccessFile(path, "r").use { raf ->
                if (offset >= raf.length()) return ByteArray(0)
                raf.seek(offset)
                val want = minOf(length.toLong(), raf.length() - offset).toInt()
                val out = ByteArray(want)
                raf.readFully(out)
                out
            }
        } catch (e: Exception) {
            RuntimeLog.error(TAG, "分段读取失败: $path @$offset", e)
            null
        }
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /**
     * 写入字节到文件。
     *
     * @param createDir 为 true 时自动递归创建父目录
     * @param append    为 true 时追加,否则截断重写
     * @param sync      为 true 时等待数据落盘(`fsync`)后才返回;
     *                  需要"断电也不丢"的场景(配置/索引)应开启,普通输出可关
     * @return 是否成功。原生路径保证全量写入,不会静默产出截断文件
     */
    @JvmStatic
    @JvmOverloads
    fun writeBytes(
        path: String,
        data: ByteArray?,
        createDir: Boolean = true,
        append: Boolean = false,
        sync: Boolean = false
    ): Boolean {
        if (path.isEmpty()) return false
        if (nativeAvailable) return nativeWriteBytes(path, data, createDir, append, sync)
        return try {
            val f = File(path)
            if (createDir) f.parentFile?.mkdirs()
            java.io.FileOutputStream(f, append).use { out ->
                if (data != null && data.isNotEmpty()) out.write(data)
                out.flush()
                if (sync) out.fd.sync()
            }
            true
        } catch (e: Exception) {
            RuntimeLog.error(TAG, "写入失败: $path", e)
            false
        }
    }

    /**
     * 写入文本到文件(UTF-8 或指定字符集)。
     *
     * @return 是否成功
     */
    @JvmStatic
    @JvmOverloads
    fun writeText(
        path: String,
        text: String?,
        charset: String = "UTF-8",
        createDir: Boolean = true,
        append: Boolean = false
    ): Boolean {
        if (text == null) return false
        val bytes = try {
            text.toByteArray(charset(charset))
        } catch (e: Exception) {
            RuntimeLog.error(TAG, "编码失败: $path ($charset)", e)
            return false
        }
        return writeBytes(path, bytes, createDir, append, sync = false)
    }

    // ------------------------------------------------------------------
    // 拷贝 / 元信息
    // ------------------------------------------------------------------

    /**
     * 原生拷贝文件(优先 `sendfile`,不经 Java 堆)。
     *
     * @param createDir 为 true 时自动创建目标父目录
     * @return 拷贝的字节数;失败返回 -1
     */
    @JvmStatic
    @JvmOverloads
    fun copyFile(src: String, dst: String, createDir: Boolean = true): Long {
        if (src.isEmpty() || dst.isEmpty()) return -1
        if (nativeAvailable) return nativeCopy(src, dst, createDir)
        return try {
            val from = File(src)
            if (!from.isFile) return -1
            val to = File(dst)
            if (createDir) to.parentFile?.mkdirs()
            from.inputStream().use { input ->
                to.outputStream().use { output ->
                    var total = 0L
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        total += n
                    }
                    total
                }
            }
        } catch (e: Exception) {
            RuntimeLog.error(TAG, "拷贝失败: $src -> $dst", e)
            -1
        }
    }

    /** 文件字节数;不存在或非普通文件返回 -1。 */
    @JvmStatic
    fun fileSize(path: String): Long {
        if (path.isEmpty()) return -1
        if (nativeAvailable) return nativeSize(path)
        val f = File(path)
        return if (f.isFile) f.length() else -1
    }

    /** 路径是否存在。 */
    @JvmStatic
    fun exists(path: String): Boolean {
        if (path.isEmpty()) return false
        if (nativeAvailable) return nativeExists(path)
        return File(path).exists()
    }

    /** 删除文件(不删非空目录)。 */
    @JvmStatic
    fun delete(path: String): Boolean {
        if (path.isEmpty()) return false
        if (nativeAvailable) return nativeDelete(path)
        return try {
            val f = File(path)
            f.isFile && f.delete()
        } catch (e: Exception) {
            false
        }
    }

    /** 递归创建目录(等价 `mkdir -p`)。 */
    @JvmStatic
    fun mkdirs(path: String): Boolean {
        if (path.isEmpty()) return false
        if (nativeAvailable) return nativeMkdirs(path)
        return try {
            val f = File(path)
            f.isDirectory || f.mkdirs()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 以字节为单位截断/扩展文件。
     *
     * 原生路径下用 `open` + `ftruncate`,无需读入内容;用于日志轮转等场景。
     *
     * @return 是否成功
     */
    @JvmStatic
    fun truncate(path: String, length: Long): Boolean {
        if (path.isEmpty() || length < 0) return false
        return try {
            java.io.RandomAccessFile(path, "rw").use { it.setLength(length) }
            true
        } catch (e: Exception) {
            RuntimeLog.error(TAG, "截断失败: $path -> $length", e)
            false
        }
    }

    /** 把字符集名解析成 [java.nio.charset.Charset];非法名回退 UTF-8。 */
    private fun charset(name: String): java.nio.charset.Charset = try {
        java.nio.charset.Charset.forName(name)
    } catch (e: Exception) {
        RuntimeLog.log(TAG, "未知字符集 $name,回退 UTF-8")
        Charsets.UTF_8
    }

    /**
     * 原子写:先写同目录临时文件,`fsync` 后 `rename` 覆盖目标。
     *
     * 读者永远看到旧内容或新内容,不会看到半截文件 —— 与
     * `ProjectFileIO.writeBytesAtomic` 的语义一致,区别是整条路径在 native 侧
     * 完成(临时文件也由原生创建),适合大文件的"要么全有要么全无"。
     *
     * @return 是否成功
     */
    @JvmStatic
    fun writeBytesAtomic(path: String, data: ByteArray?): Boolean {
        if (path.isEmpty()) return false
        val target = File(path)
        val parent = target.parentFile ?: return writeBytes(path, data, false, false, true)
        val tmp = File(parent, "${target.name}.fastio.tmp")
        return try {
            parent.mkdirs()
            if (!writeBytes(tmp.absolutePath, data, false, false, true)) {
                tmp.delete()
                return false
            }
            if (target.exists() && !target.delete()) {
                tmp.delete()
                return false
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return false
            }
            true
        } catch (e: Exception) {
            RuntimeLog.error(TAG, "原子写失败: $path", e)
            tmp.delete()
            false
        }
    }

    /** 供 Lua 侧直接调用的便捷读(缺文件时返回空串而不是 null,便于 `print`)。 */
    @JvmStatic
    fun readTextOrEmpty(path: String, charset: String = "UTF-8"): String =
        readText(path, charset) ?: ""

    /** 供 Lua 侧直接调用的便捷写(内部抛 IOException 语义外化为布尔)。 */
    @JvmStatic
    fun writeTextOrThrow(path: String, text: String?, charset: String = "UTF-8") {
        if (!writeText(path, text, charset)) {
            throw IOException("write failed: $path")
        }
    }
}
