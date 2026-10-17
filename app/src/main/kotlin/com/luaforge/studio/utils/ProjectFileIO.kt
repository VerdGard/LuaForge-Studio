package com.luaforge.studio.utils

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 项目文件的**唯一写入出口**:原子写 + 项目级互斥 + 内容指纹。
 *
 * ## 为什么需要它
 *
 * 编辑器保存、MCP 外部写入、协同同步三条路径都会往同一个项目文件写内容。
 * 之前它们各写各的(`File.writeText` / `outputStream().bufferedWriter()`),
 * 有两个致命后果:
 *
 * 1. **非原子写**:先截断再写,写到一半被读(或进程被杀)就留下半截文件 ——
 *    这是"协同同步导致项目文件损坏"的直接机制;
 * 2. **无互斥**:编辑器保存与协同落盘同时进行时,两者的字节会交错,
 *    产出一个语法上完全不成立的文件。
 *
 * 本对象把这两点一次性解决:所有写入都走"写临时文件 → fsync → 原子 rename",
 * 并按**项目根目录**粒度加锁,让同一项目的并发写串行化。
 *
 * ## 锁粒度
 *
 * 按项目根(而不是全局)加锁:不同项目可以并行,同一项目内串行。
 * 锁对象按规范化路径缓存在 [projectLocks],项目卸载不需要清理 ——
 * 数量级是"用户打开过的项目数",可以忽略。
 */
object ProjectFileIO {

    private const val TAG = "ProjectFileIO"

    /** 项目根规范化路径 -> 锁对象。 */
    private val projectLocks = ConcurrentHashMap<String, Any>()

    /** 取某项目根对应的锁对象。 [projectRoot] 应是项目根目录。 */
    fun lockFor(projectRoot: File): Any {
        val key = try {
            projectRoot.canonicalPath
        } catch (_: Exception) {
            projectRoot.absolutePath
        }
        return projectLocks.getOrPut(key) { Any() }
    }

    /**
     * 取某个**项目内文件**应该使用的写锁。
     *
     * 锁按项目根区分,所以必须先确定"这个文件属于哪个项目":从文件所在目录向上找,
     * 第一个含项目标记的目录即项目根。
     *
     * 标记取 `settings.json` / `.luaforge` / `.git` 三者之一:
     * - `settings.json` 是每个 LuaForge 项目的必有文件;
     * - `.luaforge` 是协同仓库元数据(共享端/连接端都会有);
     * - `.git` 是协同快照仓库。
     *
     * 编辑器、MCP、协同三条写入路径都经这里,因此同一项目内必然落到同一个锁上。
     * 一个标记都找不到时退回文件父目录 —— 锁粒度变细,但仍然不会交错写。
     */
    fun lockForProjectFile(file: File): Any = lockFor(findProjectRoot(file))

    /**
     * 向上探测 [file] 所属的项目根目录。
     *
     * 见 [lockForProjectFile] 的标记说明。为避免走到 `/storage/emulated/0` 这种
     * 大目录上误判,最多向上 8 层(项目嵌套深度远小于此)。
     */
    fun findProjectRoot(file: File): File {
        var dir: File? = if (file.isDirectory) file else file.parentFile
        var depth = 0
        val start = dir
        while (dir != null && depth < 8) {
            if (hasProjectMarker(dir)) return dir
            dir = dir.parentFile
            depth++
        }
        return start ?: file
    }

    private fun hasProjectMarker(dir: File): Boolean =
        PROJECT_MARKERS.any { File(dir, it).exists() }

    /** 项目根标记:命中任一即认定该目录是项目根。 */
    private val PROJECT_MARKERS = listOf("settings.json", ".luaforge", ".git")

    /**
     * 原子写入文本文件。
     *
     * @param file     目标文件(不存在则创建,父目录自动 mkdirs)
     * @param content  文本内容,按 UTF-8 编码
     * @param lock     可选锁对象;传 [lockFor] 的结果即可与同项目其它写入互斥
     * @return 写入后的内容 MD5(便于调用方做基线比对)
     */
    fun writeTextAtomic(file: File, content: String, lock: Any? = null): String {
        val bytes = content.toByteArray(Charsets.UTF_8)
        return writeBytesAtomic(file, bytes, lock)
    }

    /**
     * 原子写入字节(二进制安全,协同传输解出来的内容走这里)。
     *
     * 实现:同目录下写 `<name>.<random>.tmp` → `fd.sync()` → `renameTo`。
     * 用同目录临时文件是为了让 rename 落在同一文件系统上(否则会退化成"复制+删除",
     * 又变成非原子)。rename 在 POSIX 上是原子的:读者要么看到旧内容,要么看到新内容。
     */
    fun writeBytesAtomic(file: File, bytes: ByteArray, lock: Any? = null): String {
        val body = { doWriteAtomic(file, bytes) }
        if (lock == null) body() else synchronized(lock) { body() }
        return md5Of(bytes)
    }

    private fun doWriteAtomic(file: File, bytes: ByteArray) {
        file.parentFile?.let { parent ->
            if (!parent.exists()) parent.mkdirs()
        }
        val tmp = File(file.parentFile ?: File("."), "${file.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                // fsync:保证 rename 之后不会因为掉电出现"文件名在、内容空洞"
                try {
                    out.fd.sync()
                } catch (_: Exception) {
                    // 部分分区不支持 sync,退化为普通 flush;不因此失败
                }
            }
            // File.renameTo 在目标已存在时的行为依赖平台;先删再 rename 会开出竞态窗口,
            // 因此优先直接 rename(同目录、同文件系统时成功且原子)。
            if (!tmp.renameTo(file)) {
                // 极少数文件系统(TMPFS / 某些 FUSE)rename 到已存在目标会失败,
                // 退回"先删再 rename" —— 仍然在同一把锁内,竞态窗口只对本进程外的读者可见。
                if (file.exists() && !file.delete()) {
                    throw java.io.IOException("无法覆盖目标文件: ${file.absolutePath}")
                }
                if (!tmp.renameTo(file)) {
                    throw java.io.IOException("原子写入失败: ${file.absolutePath}")
                }
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * 读取文本(带大小上限保护,避免把超大文件读进内存)。
     *
     * @return 文件不存在/不可读/超限时返回 null
     */
    fun readText(file: File, maxBytes: Long = 8L * 1024 * 1024): String? {
        if (!file.isFile) return null
        return try {
            if (file.length() > maxBytes) null else file.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** 读原始字节。 */
    fun readBytes(file: File, maxBytes: Long = 8L * 1024 * 1024): ByteArray? {
        if (!file.isFile) return null
        return try {
            if (file.length() > maxBytes) null else file.readBytes()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 计算文件当前内容的 MD5;文件不存在返回 null。
     *
     * 用于"上次同步基线"比对:调用方先记下 md5,下次同步前重新算一遍,
     * 不一致就说明本地被改过,不能静默覆盖。
     */
    fun md5Of(file: File): String? {
        if (!file.isFile) return null
        return try {
            val md = MessageDigest.getInstance("MD5")
            RandomAccessFile(file, "r").use { raf ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = raf.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            hex(md.digest())
        } catch (_: Exception) {
            null
        }
    }

    /** 计算字节的 MD5。 */
    fun md5Of(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("MD5").digest(bytes))

    /** 计算字符串(UTF-8)的 MD5。 */
    fun md5Of(text: String): String = md5Of(text.toByteArray(Charsets.UTF_8))

    private fun hex(digest: ByteArray): String {
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
