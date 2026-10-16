package com.luaforge.studio.collab

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

    init {
        git.init()
        if (!metaDir.exists()) metaDir.mkdirs()
    }

    /** 当前变更版本号(单调递增)。 */
    fun currentRev(): Long = synchronized(lock) {
        if (!revFile.exists()) 0L else revFile.readText(Charsets.UTF_8).trim().toLongOrNull() ?: 0L
    }

    private fun writeRev(rev: Long) {
        if (!metaDir.exists()) metaDir.mkdirs()
        revFile.writeText(rev.toString(), Charsets.UTF_8)
    }

    /**
     * 记录一次文件变更并递增版本号。
     * @return 新的版本号
     */
    fun recordChange(path: String, deleted: Boolean): Long = synchronized(lock) {
        val rev = (currentRev() + 1)
        writeRev(rev)
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
        changesFile.writeText(trimmed.toString(), Charsets.UTF_8)
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

    /** 写入文本文件并记录变更(用于协同写入)。 */
    fun writeFile(relPath: String, content: String): Long {
        val target = File(projectDir, normalize(relPath))
        target.parentFile?.mkdirs()
        target.writeText(content, Charsets.UTF_8)
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
            target.readText(Charsets.UTF_8)
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
