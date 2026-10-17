package com.luaforge.studio.collab

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 局域网协同「连接端」客户端。
 *
 * 通过 HTTP 访问共享端 [CollabServer] 暴露的接口,把远端项目同步到本地目录,
 * 并把本地改动推回共享端(双向)。
 *
 * ## 传输格式
 *
 * 文件内容一律 **Base64** 承载并附 **MD5**,原因:
 * - JSON 字符串承载任意字节会在非 UTF-8 内容(图片/加密后的 lua/dex)上损毁内容;
 * - MD5 让两端能在"不比较全文"的前提下判定内容是否真的变了,
 *   也是冲突检测(基线比对)的唯一依据。
 */
class CollabClient(
    private val host: String,
    private val port: Int,
    private val token: String = ""
) {

    /** 远端项目信息。 */
    data class Info(val name: String, val rev: Long, val head: String?)

    /** 单条文件变更。 */
    data class RemoteChange(val rev: Long, val path: String, val deleted: Boolean)

    /** 远端文件内容(二进制安全)。 */
    class RemoteFile(val md5: String, val bytes: ByteArray)

    /** 远端文件清单条目。 */
    data class ManifestEntry(val path: String, val md5: String)

    /** 推送结果。[conflict] 表示共享端上该文件的 MD5 与调用方给出的基线不符。 */
    data class PushResult(val ok: Boolean, val conflict: Boolean, val remoteMd5: String?, val message: String)

    private fun baseUrl(): String = "http://$host:$port"

    private fun open(path: String, method: String = "GET"): HttpURLConnection {
        val conn = URL(baseUrl() + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 8000
        conn.readTimeout = 20000
        if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
        return conn
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        if (stream == null) return ""
        return BufferedInputStream(stream).use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } > 0) out.write(buf, 0, n)
            out.toString("UTF-8")
        }
    }

    private fun postJson(path: String, payload: JSONObject): Pair<Int, String> {
        val conn = open(path, "POST")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        val bytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
        conn.setFixedLengthStreamingMode(bytes.size)
        try {
            BufferedOutputStream(conn.outputStream).use { it.write(bytes); it.flush() }
            val code = conn.responseCode
            val body = readBody(conn)
            conn.disconnect()
            return code to body
        } catch (e: Exception) {
            try { conn.disconnect() } catch (_: Exception) {}
            throw e
        }
    }

    /** 拉取远端项目信息。 */
    fun info(): Info? = try {
        val conn = open("/collab/info")
        val body = readBody(conn)
        val ok = conn.responseCode in 200..299
        conn.disconnect()
        if (!ok) null else {
            val o = JSONObject(body)
            Info(
                name = o.optString("name", "project"),
                rev = o.optLong("rev", 0L),
                head = if (o.isNull("head")) null else o.optString("head")
            )
        }
    } catch (_: Exception) {
        null
    }

    /** 拉取自 since 起的增量变更。 */
    fun changesSince(since: Long): List<RemoteChange>? = try {
        val conn = open("/collab/changes?since=$since")
        val body = readBody(conn)
        val ok = conn.responseCode in 200..299
        conn.disconnect()
        if (!ok) null else {
            val o = JSONObject(body)
            val arr = o.optJSONArray("changes") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                RemoteChange(c.optLong("rev", 0L), c.optString("path"), c.optBoolean("deleted", false))
            }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 拉取远端**全量**文件清单(含每个文件的 MD5)。
     *
     * 旧实现拿 `changedFiles()`(与 HEAD 的 diff)当清单,导致共享端做过快照且工作区
     * 干净时清单为空、首次连接一个文件都下不到。现在由共享端返回工作区全量文件。
     */
    fun manifest(): List<ManifestEntry> = try {
        val conn = open("/collab/files")
        val body = readBody(conn)
        val ok = conn.responseCode in 200..299
        conn.disconnect()
        if (!ok) emptyList() else {
            val arr = JSONObject(body).optJSONArray("files") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val path = o.optString("path")
                if (path.isBlank()) null else ManifestEntry(path, o.optString("md5"))
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** 拉取单个文件内容(Base64 + MD5)。 */
    fun file(path: String): RemoteFile? = try {
        val enc = URLEncoder.encode(path, "UTF-8")
        val conn = open("/collab/file?path=$enc")
        val body = readBody(conn)
        val ok = conn.responseCode in 200..299
        conn.disconnect()
        if (!ok) null else {
            val o = JSONObject(body)
            val b64 = o.optString("contentBase64", "")
            if (b64.isEmpty()) null else RemoteFile(o.optString("md5"), Base64Codec.decode(b64))
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 把本地文件内容推给共享端(双向同步)。
     *
     * @param baseMd5 调用方上次同步时记录的内容指纹;共享端会比对,不一致即报冲突,
     *                避免"本地旧内容覆盖远端新内容"。
     * @param force   冲突时是否强制覆盖(用户明确选择"以我为准")
     */
    fun put(path: String, bytes: ByteArray, baseMd5: String?, force: Boolean = false): PushResult = try {
        val payload = JSONObject()
            .put("path", path)
            .put("contentBase64", Base64Codec.encode(bytes))
            .put("md5", com.luaforge.studio.utils.ProjectFileIO.md5Of(bytes))
        if (baseMd5 != null) payload.put("baseMd5", baseMd5)
        payload.put("force", force)
        val (code, body) = postJson("/collab/put", payload)
        val o = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        when {
            code in 200..299 -> PushResult(true, false, o.optString("md5").ifBlank { null }, "ok")
            code == 409 -> PushResult(false, true, o.optString("remoteMd5").ifBlank { null }, "远端已变更")
            else -> PushResult(false, false, null, o.optString("error").ifBlank { "HTTP $code" })
        }
    } catch (e: Exception) {
        PushResult(false, false, null, e.message ?: "推送失败")
    }

    /** 请求共享端删除文件(同样带基线校验)。 */
    fun delete(path: String, baseMd5: String?, force: Boolean = false): PushResult = try {
        val payload = JSONObject().put("path", path).put("force", force)
        if (baseMd5 != null) payload.put("baseMd5", baseMd5)
        val (code, body) = postJson("/collab/delete", payload)
        val o = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        when {
            code in 200..299 -> PushResult(true, false, null, "ok")
            code == 409 -> PushResult(false, true, o.optString("remoteMd5").ifBlank { null }, "远端已变更")
            else -> PushResult(false, false, null, o.optString("error").ifBlank { "HTTP $code" })
        }
    } catch (e: Exception) {
        PushResult(false, false, null, e.message ?: "删除请求失败")
    }

    /** 拉取远端提交历史。 */
    fun history(): List<JSONObject> = try {
        val conn = open("/collab/history")
        val body = readBody(conn)
        val ok = conn.responseCode in 200..299
        conn.disconnect()
        if (!ok) emptyList() else {
            val arr = JSONObject(body).optJSONArray("commits") ?: return emptyList()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** 在共享端提交一次快照。 */
    fun snapshot(message: String, author: String): Boolean = try {
        val payload = JSONObject().put("message", message).put("author", author)
        val (code, _) = postJson("/collab/snapshot", payload)
        code in 200..299
    } catch (_: Exception) {
        false
    }
}

/**
 * Base64 编解码。
 *
 * 用 `android.util.Base64` 而不是 `java.util.Base64`:`minSdk 23` 上后者要到
 * API 26 才有(除非开脱糖),而这里必须保证在最低版本设备上可用。
 * 一律用 NO_WRAP,避免换行符进入 JSON 字符串。
 */
internal object Base64Codec {
    fun encode(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    fun decode(text: String): ByteArray = try {
        android.util.Base64.decode(text, android.util.Base64.NO_WRAP)
    } catch (_: Exception) {
        ByteArray(0)
    }
}
