package com.luaforge.studio.collab

import com.luaforge.studio.utils.LogCatcher
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 局域网项目协同「共享端」服务。
 *
 * 复用与 [com.luaforge.studio.mcp.McpServer] 相同的极简 HTTP/1.1 传输(无第三方依赖),
 * 暴露以下接口:
 * - `GET  /collab/info`            项目元信息(名称、当前版本号、HEAD)
 * - `GET  /collab/manifest?rev=`   受控文件清单(路径 -> sha)
 * - `GET  /collab/file?path=`      读取单个文件内容
 * - `GET  /collab/changes?since=`  自版本 since 起的增量变更
 * - `POST /collab/history`         提交一次 git 快照(共享端记录)
 *
 * 服务运行在应用进程内,关闭开关或退出应用即停止。
 * 仅面向局域网使用,可选携带令牌。
 */
class CollabServer(
    private val repo: CollabRepo,
    private val port: Int,
    private val token: String,
    private val projectName: String
) {

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    private val clients = ThreadPoolExecutor(
        0, 16, 30L, TimeUnit.SECONDS,
        LinkedBlockingQueue(64)
    ) { r -> Thread(r, "collab-client").apply { isDaemon = true } }

    val boundPort: Int get() = serverSocket?.localPort ?: port

    fun start(): Boolean {
        if (isRunning) return true
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            serverSocket = socket
            isRunning = true
            acceptThread = Thread({ acceptLoop(socket) }, "collab-accept").apply {
                isDaemon = true
                start()
            }
            LogCatcher.i(TAG, "协同共享端已启动,端口 $port")
            true
        } catch (e: Exception) {
            LogCatcher.e(TAG, "协同共享端启动失败: ${e.message}", e)
            isRunning = false
            serverSocket = null
            false
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        clients.shutdownNow()
        LogCatcher.i(TAG, "协同共享端已停止")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (isRunning) {
            val client = try { socket.accept() } catch (_: Exception) { break }
            try {
                clients.execute { handle(client) }
            } catch (_: Exception) {
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 15000
            val input = BufferedInputStream(client.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val rawTarget = parts[1]

            // 读取并忽略请求头
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                    line.substring(idx + 1).trim()
            }

            // 令牌校验
            if (token.isNotBlank()) {
                val auth = headers["authorization"].orEmpty()
                if (auth != "Bearer $token") {
                    writeResponse(client, 401, "application/json", "{\"error\":\"unauthorized\"}")
                    return
                }
            }

            val qIdx = rawTarget.indexOf('?')
            val path = if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget
            val query = if (qIdx >= 0) parseQuery(rawTarget.substring(qIdx + 1)) else emptyMap()

            when {
                method == "GET" && path == "/collab/info" -> handleInfo(client)
                method == "GET" && path == "/collab/manifest" -> handleManifest(client)
                method == "GET" && path == "/collab/file" -> handleFile(client, query["path"].orEmpty())
                method == "GET" && path == "/collab/changes" -> handleChanges(client, query["since"]?.toLongOrNull() ?: 0L)
                method == "GET" && path == "/collab/history" -> handleHistory(client)
                method == "POST" && path == "/collab/snapshot" -> handleSnapshot(client, input, headers)
                else -> writeResponse(client, 404, "application/json", "{\"error\":\"not found\"}")
            }
        } catch (e: Exception) {
            LogCatcher.e(TAG, "处理协同请求异常", e)
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun handleInfo(client: Socket) {
        val body = JSONObject()
            .put("name", projectName)
            .put("rev", repo.currentRev())
            .put("head", repo.headRevision() ?: JSONObject.NULL)
            .put("fileCount", repo.git.changedFiles().size)
            .toString()
        writeResponse(client, 200, "application/json", body)
    }

    private fun handleManifest(client: Socket) {
        val files = repo.git.changedFiles()
        val arr = JSONArray()
        files.forEach { (path, status) ->
            arr.put(JSONObject().put("path", path).put("status", status))
        }
        val body = JSONObject()
            .put("rev", repo.currentRev())
            .put("files", arr)
            .toString()
        writeResponse(client, 200, "application/json", body)
    }

    private fun handleFile(client: Socket, path: String) {
        if (path.isBlank()) {
            writeResponse(client, 400, "application/json", "{\"error\":\"missing path\"}")
            return
        }
        val content = repo.readFile(path)
        if (content == null) {
            writeResponse(client, 404, "application/json", "{\"error\":\"file not found\"}")
            return
        }
        val body = JSONObject()
            .put("path", path)
            .put("rev", repo.currentRev())
            .put("content", content)
            .toString()
        writeResponse(client, 200, "application/json", body)
    }

    private fun handleChanges(client: Socket, since: Long) {
        val changes = repo.changesSince(since)
        val arr = JSONArray()
        changes.forEach { c ->
            arr.put(JSONObject().put("rev", c.rev).put("path", c.path).put("deleted", c.deleted))
        }
        val body = JSONObject()
            .put("rev", repo.currentRev())
            .put("changes", arr)
            .toString()
        writeResponse(client, 200, "application/json", body)
    }

    private fun handleHistory(client: Socket) {
        val log = repo.log(50)
        val arr = JSONArray()
        log.forEach { c ->
            arr.put(
                JSONObject()
                    .put("sha", c.sha)
                    .put("parent", c.parent ?: JSONObject.NULL)
                    .put("author", c.author)
                    .put("time", c.timeMillis)
                    .put("message", c.message)
            )
        }
        writeResponse(client, 200, "application/json", JSONObject().put("commits", arr).toString())
    }

    private fun handleSnapshot(client: Socket, input: InputStream, headers: Map<String, String>) {
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n <= 0) break
            read += n
        }
        val req = try { JSONObject(String(buf, 0, read, StandardCharsets.UTF_8)) } catch (_: Exception) { JSONObject() }
        val message = req.optString("message", "远程快照")
        val author = req.optString("author", "remote")
        val sha = repo.snapshot(message, author)
        writeResponse(
            client, 200, "application/json",
            JSONObject().put("sha", sha).put("rev", repo.currentRev()).toString()
        )
    }

    // ------------------------------------------------------------------
    // HTTP 工具
    // ------------------------------------------------------------------

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("ISO-8859-1")
            if (b == '\n'.code) {
                val bytes = out.toByteArray()
                val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, end, StandardCharsets.ISO_8859_1)
            }
            out.write(b)
        }
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isBlank()) return emptyMap()
        val map = HashMap<String, String>()
        q.split("&").forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                map[urlDecode(pair.substring(0, idx))] = urlDecode(pair.substring(idx + 1))
            }
        }
        return map
    }

    private fun urlDecode(s: String): String =
        try { java.net.URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }

    private fun writeResponse(client: Socket, status: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; else -> "Error"
        }
        val header = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $contentType; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        val out = BufferedOutputStream(client.getOutputStream())
        out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    companion object {
        private const val TAG = "CollabServer"

        /** 枚举本机所有非回环 IPv4 地址。 */
        fun localAddresses(): List<String> {
            val out = ArrayList<String>()
            try {
                val ifaces = NetworkInterface.getNetworkInterfaces()
                for (iface in ifaces) {
                    if (!iface.isUp || iface.isLoopback) continue
                    for (addr in iface.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr.hostAddress?.contains(':') == false) {
                            out.add(addr.hostAddress!!)
                        }
                    }
                }
            } catch (_: Exception) {
            }
            return out
        }
    }
}
