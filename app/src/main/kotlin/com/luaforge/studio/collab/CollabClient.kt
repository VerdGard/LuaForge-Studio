package com.luaforge.studio.collab

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
 * 并轮询增量变更以实现实时刷新。
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

    private fun baseUrl(): String = "http://$host:$port"

    private fun open(path: String, method: String = "GET"): HttpURLConnection {
        val conn = URL(baseUrl() + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 8000
        conn.readTimeout = 15000
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

    /** 拉取远端项目文件清单。 */
    fun manifest(): List<String> = try {
        val conn = open("/collab/manifest")
        val body = readBody(conn)
        val ok = conn.responseCode in 200..299
        conn.disconnect()
        if (!ok) emptyList() else {
            val arr = JSONObject(body).optJSONArray("files") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("path")
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** 拉取单个文件内容。 */
    fun file(path: String): String? = try {
        val enc = URLEncoder.encode(path, "UTF-8")
        val conn = open("/collab/file?path=$enc")
        val body = readBody(conn)
        val ok = conn.responseCode in 200..299
        conn.disconnect()
        if (!ok) null else JSONObject(body).optString("content")
    } catch (_: Exception) {
        null
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
        val conn = open("/collab/snapshot", "POST")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val payload = JSONObject().put("message", message).put("author", author).toString()
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        conn.setFixedLengthStreamingMode(bytes.size)
        BufferedOutputStream(conn.outputStream).use { it.write(bytes); it.flush() }
        val code = conn.responseCode
        readBody(conn)
        conn.disconnect()
        code in 200..299
    } catch (_: Exception) {
        false
    }
}
