package com.luaforge.studio.collab

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 极简 Git 实现(仅实现协同/回档所需的最小子集)。
 *
 * 目标:在**不依赖外部 git 二进制**的前提下,于项目目录内建立标准的 `.git` 仓库,
 * 产出符合 Git 对象格式的松散对象(loose objects),因此仓库可被标准 git 命令行读取。
 *
 * 支持的能力:
 * - [init] 初始化仓库;
 * - [snapshot] 把工作区当前状态提交为一个 commit(写入 blob/tree/commit 对象并更新 ref);
 * - [log] 读取提交历史;
 * - [checkout] 回档到指定提交(还原工作区);
 * - [changedFiles] 对比 HEAD 与工作区的差异;
 * - [headRevision] 当前 HEAD 指向的提交(不存在时为 null)。
 *
 * 对象格式:
 * - blob   = "blob <len>\0" + data
 * - tree   = "tree <len>\0" + 若干 "<mode> <name>\0<20字节sha>"
 * - commit = "commit <len>\0" + "tree <sha>\n[parent <sha>\n]author ...\ncommitter ...\n\n<msg>"
 *
 * 对象以 zlib 压缩后存放于 `.git/objects/<前2位>/<后38位>`。
 */
class GitStore(private val root: File) {

    private val gitDir = File(root, ".git")
    private val objectsDir = File(gitDir, "objects")
    private val refsDir = File(gitDir, "refs/heads")
    private val headFile = File(gitDir, "HEAD")
    private val defaultBranch = "master"

    /** 参与版本控制时需要忽略的目录/文件名。 */
    private val ignored = setOf(".git", "build", "node_modules", ".gradle", "apk", "out", "dist")

    /** 初始化仓库(幂等)。 */
    fun init() {
        if (!gitDir.exists()) gitDir.mkdirs()
        if (!objectsDir.exists()) objectsDir.mkdirs()
        if (!refsDir.exists()) refsDir.mkdirs()
        if (!headFile.exists()) headFile.writeText("ref: refs/heads/$defaultBranch\n", Charsets.UTF_8)
        val configFile = File(gitDir, "config")
        if (!configFile.exists()) {
            configFile.writeText(
                "[core]\n\trepositoryformatversion = 0\n\tfilemode = false\n\tbare = false\n" +
                    "[branch \"$defaultBranch\"]\n",
                Charsets.UTF_8
            )
        }
    }

    /** 当前 HEAD 指向的提交 sha,仓库尚无提交时返回 null。 */
    fun headRevision(): String? {
        val ref = readHeadRef() ?: return null
        val refFile = File(gitDir, ref)
        if (!refFile.exists()) return null
        val sha = refFile.readText(Charsets.UTF_8).trim()
        return sha.ifBlank { null }
    }

    /** HEAD 所在分支的完整 ref 路径(如 refs/heads/master)。 */
    private fun readHeadRef(): String? {
        if (!headFile.exists()) return null
        val content = headFile.readText(Charsets.UTF_8).trim()
        return if (content.startsWith("ref:")) content.removePrefix("ref:").trim() else null
    }

    /**
     * 把工作区当前状态提交为一次快照。
     *
     * @param message 提交信息
     * @param author  作者名(用于 author/committer 字段)
     * @return 新提交的 sha,或失败原因
     */
    fun snapshot(message: String, author: String = "LuaForge Studio"): String {
        init()
        val treeSha = writeTree(root)
        val parent = headRevision()
        val email = "$author@luaforge.local".replace(' ', '.')
        val now = System.currentTimeMillis()
        val tz = TimeZone.getDefault()
        val offMinutes = tz.getOffset(now) / 60000
        val sign = if (offMinutes >= 0) "+" else "-"
        val abs = Math.abs(offMinutes)
        val tzStr = String.format(Locale.US, "%s%02d%02d", sign, abs / 60, abs % 60)
        val timeStr = "${now / 1000} $tzStr"

        val sb = StringBuilder()
        sb.append("tree ").append(treeSha).append('\n')
        if (parent != null) sb.append("parent ").append(parent).append('\n')
        sb.append("author ").append(author).append(" <").append(email).append("> ").append(timeStr).append('\n')
        sb.append("committer ").append(author).append(" <").append(email).append("> ").append(timeStr).append('\n')
        sb.append('\n')
        sb.append(message.trim().ifBlank { "snapshot" })
        sb.append('\n')

        val commitSha = writeObject("commit", sb.toString().toByteArray(Charsets.UTF_8))
        updateHead(commitSha)
        return commitSha
    }

    private fun updateHead(sha: String) {
        val ref = readHeadRef() ?: "refs/heads/$defaultBranch"
        val refFile = File(gitDir, ref)
        refFile.parentFile?.mkdirs()
        refFile.writeText("$sha\n", Charsets.UTF_8)
    }

    /** 递归写入目录,返回 tree sha。 */
    private fun writeTree(dir: File): String {
        val entries = ArrayList<TreeEntry>()
        val children = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
        for (child in children) {
            if (child.name in ignored) continue
            if (child.isDirectory) {
                val sub = writeTree(child)
                entries.add(TreeEntry("40000", child.name, sub, isDir = true))
            } else if (child.isFile) {
                val bytes = child.readBytes()
                val blobSha = writeObject("blob", bytes)
                val mode = if (child.canExecute()) "100755" else "100644"
                entries.add(TreeEntry(mode, child.name, blobSha, isDir = false))
            }
        }
        // Git 排序:目录名按附加 '/' 参与比较
        entries.sortWith { a, b ->
            val an = if (a.isDir) a.name + "/" else a.name
            val bn = if (b.isDir) b.name + "/" else b.name
            an.compareTo(bn)
        }
        val out = ByteArrayOutputStream()
        for (e in entries) {
            out.write(e.mode.toByteArray(Charsets.US_ASCII))
            out.write(' '.code)
            out.write(e.name.toByteArray(Charsets.UTF_8))
            out.write(0)
            out.write(hexToBytes(e.sha))
        }
        return writeObject("tree", out.toByteArray())
    }

    private data class TreeEntry(val mode: String, val name: String, val sha: String, val isDir: Boolean)

    /** 写入一个对象并返回其 sha。 */
    fun writeObject(type: String, content: ByteArray): String {
        val header = "$type ${content.size}".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        val full = header + content
        val sha = sha1Hex(full)
        val path = objectPath(sha)
        if (!path.exists()) {
            path.parentFile?.mkdirs()
            path.writeBytes(zlibCompress(full))
        }
        return sha
    }

    /** 读取对象,返回 (type, content)。 */
    fun readObject(sha: String): Pair<String, ByteArray>? {
        val path = objectPath(sha)
        if (!path.exists()) return null
        val inflated = try {
            zlibDecompress(path.readBytes())
        } catch (_: Exception) {
            return null
        }
        val nul = indexOfByte(inflated, 0, 0)
        if (nul < 0) return null
        val header = String(inflated, 0, nul, Charsets.US_ASCII)
        val sp = header.indexOf(' ')
        if (sp < 0) return null
        val type = header.substring(0, sp)
        val body = inflated.copyOfRange(nul + 1, inflated.size)
        return type to body
    }

    private fun objectPath(sha: String): File =
        File(objectsDir, sha.substring(0, 2) + "/" + sha.substring(2))

    /** 数据类:一次提交的元信息。 */
    data class CommitInfo(
        val sha: String,
        val parent: String?,
        val author: String,
        val timeMillis: Long,
        val message: String
    )

    /** 读取从 HEAD 开始的提交历史(最新在前)。 */
    fun log(limit: Int = 50): List<CommitInfo> {
        val result = ArrayList<CommitInfo>()
        var sha = headRevision()
        var count = 0
        while (sha != null && count < limit) {
            val (type, body) = readObject(sha) ?: break
            if (type != "commit") break
            val text = String(body, Charsets.UTF_8)
            val lines = text.split("\n")
            var parent: String? = null
            var author = ""
            var timeMillis = 0L
            var idx = 0
            for ((i, line) in lines.withIndex()) {
                if (line.startsWith("parent ")) parent = line.removePrefix("parent ").trim()
                else if (line.startsWith("author ")) {
                    val rest = line.removePrefix("author ").trim()
                    author = rest.substringBefore(" <")
                    val timePart = rest.substringAfterLast("> ").trim()
                    val secs = timePart.substringBefore(" ").toLongOrNull() ?: 0L
                    timeMillis = secs * 1000
                } else if (line.isBlank()) {
                    idx = i + 1
                    break
                }
            }
            val message = lines.drop(idx).joinToString("\n").trim()
            result.add(CommitInfo(sha, parent, author, timeMillis, message))
            sha = parent
            count++
        }
        return result
    }

    /** 列出 HEAD tree 中的全部文件路径 -> sha。 */
    private fun listTreeFiles(commitSha: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val (type, body) = readObject(commitSha) ?: return out
        if (type != "commit") return out
        val treeSha = String(body, Charsets.UTF_8).lineSequence()
            .firstOrNull { it.startsWith("tree ") }?.removePrefix("tree ")?.trim() ?: return out
        readTreeInto(treeSha, "", out)
        return out
    }

    private fun readTreeInto(treeSha: String, prefix: String, out: MutableMap<String, String>) {
        val (type, body) = readObject(treeSha) ?: return
        if (type != "tree") return
        var pos = 0
        while (pos < body.size) {
            val sp = indexOfByte(body, ' '.code.toByte(), pos)
            if (sp < 0) break
            val mode = String(body, pos, sp - pos, Charsets.US_ASCII)
            val nul = indexOfByte(body, 0, sp + 1)
            if (nul < 0) break
            val name = String(body, sp + 1, nul - (sp + 1), Charsets.UTF_8)
            val shaStart = nul + 1
            if (shaStart + 20 > body.size) break
            val sha = bytesToHex(body, shaStart, 20)
            pos = shaStart + 20
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            if (mode == "40000") {
                readTreeInto(sha, path, out)
            } else {
                out[path] = sha
            }
        }
    }

    /** 对比 HEAD 与工作区,返回相对路径列表及状态。 */
    fun changedFiles(): List<Pair<String, String>> {
        val head = headRevision() ?: return collectWorkingFiles().keys.map { it to "added" }
        val tracked = listTreeFiles(head)
        val working = collectWorkingFiles()
        val result = ArrayList<Pair<String, String>>()
        for ((path, sha) in working) {
            val old = tracked[path]
            if (old == null) result.add(path to "added")
            else if (old != sha) result.add(path to "modified")
        }
        for (path in tracked.keys) {
            if (!working.containsKey(path)) result.add(path to "deleted")
        }
        return result
    }

    /** 收集工作区文件(相对路径 -> blob sha)。 */
    private fun collectWorkingFiles(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        collectInto(root, "", map)
        return map
    }

    private fun collectInto(dir: File, prefix: String, out: MutableMap<String, String>) {
        val children = dir.listFiles()?.sortedBy { it.name } ?: return
        for (child in children) {
            if (child.name in ignored) continue
            val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            if (child.isDirectory) {
                collectInto(child, rel, out)
            } else if (child.isFile) {
                out[rel] = sha1Hex(
                    ("blob ${child.length()}".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + child.readBytes())
                )
            }
        }
    }

    /** 回档到指定提交:还原工作区文件,并删除提交中不存在的受控文件。 */
    fun checkout(sha: String): Boolean {
        val files = listTreeFiles(sha)
        if (files.isEmpty()) {
            // 允许回档到「空提交」以外的情况,但空结果通常意味着 sha 非法
            val exists = readObject(sha)
            if (exists == null || exists.first != "commit") return false
        }
        // 删除当前工作区中不在目标提交里的受控文件
        val current = collectWorkingFiles().keys
        for (path in current) {
            if (!files.containsKey(path)) File(root, path).delete()
        }
        // 还原目标提交中的文件
        for ((path, blobSha) in files) {
            val (type, body) = readObject(blobSha) ?: continue
            if (type != "blob") continue
            val target = File(root, path)
            target.parentFile?.mkdirs()
            target.writeBytes(body)
        }
        updateHead(sha)
        return true
    }

    // ------------------------------------------------------------------
    // 底层工具
    // ------------------------------------------------------------------

    private fun sha1Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        return bytesToHex(md.digest(bytes), 0, 20)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    private fun bytesToHex(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): String {
        val sb = StringBuilder(length * 2)
        for (i in offset until offset + length) {
            val v = bytes[i].toInt() and 0xFF
            sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    private fun zlibCompress(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 2 + 64)
        val buf = ByteArray(8192)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun zlibDecompress(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 3 + 64)
        val buf = ByteArray(8192)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                out.write(buf, 0, n)
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    private fun indexOfByte(data: ByteArray, value: Byte, from: Int): Int {
        var i = from
        while (i < data.size) {
            if (data[i] == value) return i
            i++
        }
        return -1
    }

    companion object {
        private val HEX_DIGITS = "0123456789abcdef".toCharArray()

        /** 格式化时间戳(供 UI 展示)。 */
        fun formatTime(millis: Long): String {
            if (millis <= 0) return "-"
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            return fmt.format(Date(millis))
        }
    }
}
