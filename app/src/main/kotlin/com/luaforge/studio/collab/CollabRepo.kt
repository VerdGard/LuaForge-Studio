package com.luaforge.studio.collab

import com.luaforge.studio.utils.ProjectFileIO
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 协同仓库:在项目目录内维护「git 历史 + 实时变更版本号」。
 *
 * - git 历史由 [GitStore] 提供(快照 / 回档 / 差异);
 * - 变更版本号用于局域网实时同步:每次文件写入都会递增一个单调版本号并记录变更,
 *   连接端通过 [changesSince] 拉取自该版本以来的全部变更,实现增量同步。
 *
 * 变更日志持久化在 `.luaforge/collab_changes.json`,进程重启后仍可续传。
 */
class CollabRepo(val projectDir: File) {

    val git = GitStore(projectDir)

    private val metaDir = File(projectDir, ".luaforge")
    private val revFile = File(metaDir, "collab_rev")
    private val changesFile = File(metaDir, "collab_changes.json")

    /** 单条变更记录。 */
    data class Change(val rev: Long, val path: String, val deleted: Boolean)

    private val lock = Any()

    /**
     * 变更日志里的最大 rev(启动时从磁盘恢复)。
     *
     * 存在的意义是**崩溃补偿**:旧实现先写 `collab_rev` 再写日志,若进程在两步之间
     * 被杀,重启后 rev 已增但日志里没有对应条目 —— 连接端 `since` 已经跳到该 rev,
     * 那条变更就永久丢失(表现为"同步少了一个文件")。
     *
     * 现在反过来**先写日志、后写 rev**,并用本字段兜住"日志已写、rev 没写"的情况:
     * [currentRev] 取两者较大值,重复记录也能被识别。
     */
    @Volatile
    private var logMaxRev: Long = -1L

    init {
        git.init()
        if (!metaDir.exists()) metaDir.mkdirs()
        logMaxRev = runCatching {
            val arr = readChangesArray()
            var max = -1L
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i)?.optLong("rev", -1L) ?: -1L
                if (r > max) max = r
            }
            max
        }.getOrDefault(-1L)
    }

    /**
     * 当前变更版本号(单调递增)。
     *
     * 取 `collab_rev` 文件与变更日志最大 rev 的**较大值**,保证不会因为崩溃
     * (日志已写、rev 未写)而让 rev 回退、把变更重放或漏掉。
     */
    fun currentRev(): Long = synchronized(lock) { currentRevUnlocked() }

    private fun currentRevUnlocked(): Long {
        val fromFile = if (!revFile.exists()) 0L
        else revFile.readText(Charsets.UTF_8).trim().toLongOrNull() ?: 0L
        return maxOf(fromFile, logMaxRev, 0L)
    }

    private fun writeRev(rev: Long) {
        if (!metaDir.exists()) metaDir.mkdirs()
        // 原子写:进程被杀不会留下半截版本号,否则 currentRev() 会退化成 0,
        // 连接端 `since` 归零后会把全部历史变更重放一遍。
        ProjectFileIO.writeTextAtomic(revFile, rev.toString(), lock)
    }

    /**
     * 记录一次文件变更并递增版本号。
     * @return 新的版本号
     */
    fun recordChange(path: String, deleted: Boolean): Long = synchronized(lock) {
        val rev = (currentRevUnlocked() + 1)
        val arr = readChangesArray()
        arr.put(
            JSONObject()
                .put("rev", rev)
                .put("path", normalize(path))
                .put("deleted", deleted)
        )
        // 仅保留最近 5000 条,避免日志无限增长
        val trimmed = if (arr.length() > 5000) {
            JSONArray().also { out ->
                for (i in arr.length() - 5000 until arr.length()) out.put(arr.get(i))
            }
        } else arr
        // 变更日志同样原子写:这份 JSON 一旦被写坏,readChangesArray() 会静默退化成空数组,
        // 之后 rev 继续递增但日志从 0 开始,增量同步就永久断链。
        ProjectFileIO.writeTextAtomic(changesFile, trimmed.toString(), lock)
        // **先日志、后 rev**:反过来(旧实现)一旦在两步之间被杀,rev 已增而日志缺失,
        // 连接端 since 已越过该 rev,这条变更永久丢失。现在的顺序下最坏情况是
        // 日志多一条、rev 没走 —— currentRevUnlocked() 会把它认回来,不会漏变更。
        logMaxRev = rev
        writeRev(rev)
        rev
    }

    /** 取得版本号大于 [since] 的变更(按 rev 升序)。 */
    fun changesSince(since: Long): List<Change> = synchronized(lock) {
        val arr = readChangesArray()
        val out = ArrayList<Change>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rev = o.optLong("rev", 0L)
            if (rev > since) {
                out.add(Change(rev, o.optString("path"), o.optBoolean("deleted", false)))
            }
        }
        out.sortBy { it.rev }
        out
    }

    private fun readChangesArray(): JSONArray {
        if (!changesFile.exists()) return JSONArray()
        return try {
            JSONArray(changesFile.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            JSONArray()
        }
    }

    /**
     * 写入文本文件并记录变更(用于协同写入)。
     *
     * 走 [ProjectFileIO.writeTextAtomic] + 项目级锁:与编辑器保存、MCP 写入互斥,
     * 且对读者是原子的 —— 这是"同步把项目文件写坏"的根因修复点之一。
     */
    fun writeFile(relPath: String, content: String): Long {
        val target = File(projectDir, normalize(relPath))
        target.parentFile?.mkdirs()
        ProjectFileIO.writeTextAtomic(target, content, ProjectFileIO.lockFor(projectDir))
        return recordChange(relPath, deleted = false)
    }

    /** 按字节写入(二进制安全,协同传输解出来的内容走这里)。 */
    fun writeFileBytes(relPath: String, bytes: ByteArray): Long {
        val target = File(projectDir, normalize(relPath))
        target.parentFile?.mkdirs()
        ProjectFileIO.writeBytesAtomic(target, bytes, ProjectFileIO.lockFor(projectDir))
        return recordChange(relPath, deleted = false)
    }

    /** 删除文件并记录变更。 */
    fun deleteFile(relPath: String): Long {
        val target = File(projectDir, normalize(relPath))
        if (target.exists()) target.delete()
        return recordChange(relPath, deleted = true)
    }

    /** 读取文本文件内容(相对项目根)。 */
    fun readFile(relPath: String): String? {
        val target = File(projectDir, normalize(relPath))
        if (!target.exists() || !target.isFile) return null
        return try {
            ProjectFileIO.readText(target)
        } catch (_: Exception) {
            null
        }
    }

    /** 读取原始字节(相对项目根)。二进制文件走这里,避免 UTF-8 解码损坏内容。 */
    fun readFileBytes(relPath: String): ByteArray? =
        ProjectFileIO.readBytes(File(projectDir, normalize(relPath)))

    /** 该文件当前内容的 MD5(相对项目根);不存在返回 null。 */
    fun md5Of(relPath: String): String? =
        ProjectFileIO.md5Of(File(projectDir, normalize(relPath)))

    /** 工作区全量清单(相对路径 -> 内容 MD5),排除 `.git` / `.luaforge` 等。 */
    fun fileList(): List<Pair<String, String>> = git.workingFileMd5s()

    /**
     * 把远端传来的相对路径解析成项目内的文件;越界或不合法返回 null。
     *
     * 拒收的情况:
     * - 空路径、绝对路径;
     * - 含 `..`(规范化后仍跳出项目根);
     * - 落在 `.git` / `.luaforge` 等被忽略的目录里 —— 否则对端可以通过
     *   `/collab/put` 直接改写本端的协同元数据,把版本号与变更日志写坏。
     */
    fun resolveSafe(relPath: String): File? {
        val rel = normalize(relPath)
        if (rel.isEmpty()) return null
        // 逐段检查:任一段为 ".." 即拒绝,不依赖 canonicalPath(文件可能尚不存在)
        if (rel.split('/').any { it == ".." || it.isEmpty() }) return null
        if (rel.split('/').firstOrNull()?.let { git.isIgnoredName(it) } == true) return null
        val target = File(projectDir, rel)
        return try {
            val base = projectDir.canonicalPath
            val abs = target.canonicalPath
            if (!abs.startsWith(base + File.separator)) null else target
        } catch (_: Exception) {
            null
        }
    }

    /** 提交一次 git 快照。 */
    fun snapshot(message: String, author: String): String {
        val sha = git.snapshot(message, author)
        return sha
    }

    /** 回档到指定提交。 */
    fun checkout(sha: String): Boolean = git.checkout(sha)

    fun headRevision(): String? = git.headRevision()

    fun log(limit: Int): List<GitStore.CommitInfo> = git.log(limit)

    fun status(): List<Pair<String, String>> = git.changedFiles()

    /** 规范化相对路径:统一分隔符,去除前导 ./ 与 /。 */
    private fun normalize(path: String): String =
        path.replace('\\', '/').removePrefix("./").trimStart('/')

    companion object {
        val META_DIR_NAME = ".luaforge"
    }
}
