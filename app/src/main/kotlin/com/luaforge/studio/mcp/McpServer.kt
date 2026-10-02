package com.luaforge.studio.mcp

import android.content.Context
import com.luaforge.studio.utils.LogCatcher
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 极简 MCP (Model Context Protocol) HTTP 服务端。
 *
 * - 传输层:HTTP/1.1,POST 承载 JSON-RPC 2.0 请求,GET 返回服务状态。
 * - 协议方法:initialize / ping / tools/list / tools/call / resources/list / prompts/list。
 * - 无第三方依赖,使用 java.net.ServerSocket。
 *
 * 每连接交给**有界线程池**:此前每连接裸起一条线程,端口暴露在局域网时会被并发连接拖垮。
 * 队列与线程数均有上限,超限连接直接以 503 拒绝,不无限堆积。
 *
 * 该服务运行在应用进程内,退出应用即随之关闭。
 */
class McpServer(
    private val context: Context,
    private val port: Int,
    private val token: String
) {

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    private var acceptThread: Thread? = null

    /** 客户端线程池:核心 0 + 有界队列,空闲线程 30s 回收;队列满即拒绝。 */
    private val clients = ThreadPoolExecutor(
        0,
        MAX_CLIENT_THREADS,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(MAX_QUEUED_CLIENTS),
        ThreadFactory { r ->
            Thread(r, "mcp-client").apply { isDaemon = true }
        }
    )

    private val requestLog = ArrayDeque<String>()
    private val logLock = Any()

    /** 工具清单缓存:listTools 会重建全部工具 schema,不必每次 GET/initialize 都算一遍。 */
    @Volatile
    private var toolsCache: JSONArray? = null

    val boundPort: Int get() = serverSocket?.localPort ?: port

    fun start(): Boolean {
        if (isRunning) return true
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            serverSocket = socket
            isRunning = true
            acceptThread = Thread({ acceptLoop(socket) }, "mcp-accept").apply {
                isDaemon = true
                start()
            }
            LogCatcher.i(TAG, "MCP 服务已启动,监听端口 $port")
            true
        } catch (e: Exception) {
            LogCatcher.e(TAG, "MCP 服务启动失败: ${e.message}", e)
            isRunning = false
            try {
                serverSocket?.close()
            } catch (_: Exception) {
            }
            serverSocket = null
            false
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        // 打断阻塞在 accept 上的线程,并回收仍在处理请求的客户端线程
        acceptThread?.interrupt()
        acceptThread = null
        clients.shutdownNow()
        LogCatcher.i(TAG, "MCP 服务已停止")
    }

    fun recentRequests(limit: Int = 20): List<String> = synchronized(logLock) {
        requestLog.toList().takeLast(limit)
    }

    fun statusJson(): JSONObject {
        val result = JSONObject()
            .put("running", isRunning)
            .put("port", port)
            .put("tokenRequired", token.isNotBlank())
            .put("toolCount", toolList().length())
            .put("addresses", JSONArray(localAddresses().map { "http://$it:$port" }))
        return result
    }

    /** 工具清单(带缓存);调用方不得修改返回值。 */
    private fun toolList(): JSONArray {
        toolsCache?.let { return it }
        synchronized(this) {
            toolsCache?.let { return it }
            val built = McpTools.listTools()
            toolsCache = built
            return built
        }
    }

    // ------------------------------------------------------------------
    // 网络循环
    // ------------------------------------------------------------------

    private fun acceptLoop(socket: ServerSocket) {
        while (isRunning) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                break
            }
            try {
                clients.execute { handleClient(client) }
            } catch (_: RejectedExecutionException) {
                // 过载:明确拒绝并断开,不排队堆积
                rejectClient(client)
            }
        }
    }

    /** 过载拒绝:尽力回一个 503,随即断开。 */
    private fun rejectClient(client: Socket) {
        try {
            client.soTimeout = 3000
            writeResponse(
                client.getOutputStream(),
                503,
                errorJson(null, -32000, "Server busy: too many concurrent connections"),
                "application/json"
            )
        } catch (_: Exception) {
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 15000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"

            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }

            if (method.equals("OPTIONS", ignoreCase = true)) {
                writeResponse(output, 204, "", "text/plain")
                return
            }

            if (token.isNotBlank()) {
                val raw = headers["authorization"] ?: headers["x-mcp-token"] ?: ""
                val provided = raw.removePrefix("Bearer ").trim()
                if (provided != token) {
                    writeResponse(output, 401, errorJson(null, -32001, "Unauthorized"), "application/json")
                    return
                }
            }

            if (method.equals("GET", ignoreCase = true)) {
                writeResponse(output, 200, statusJson().toString(), "application/json")
                return
            }

            if (!method.equals("POST", ignoreCase = true)) {
                writeResponse(output, 405, errorJson(null, -32600, "Method Not Allowed"), "application/json")
                return
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength > MAX_BODY_BYTES) {
                // 声明长度即超限:直接拒绝,避免按声明值分配出巨型字节数组
                writeResponse(output, 413, errorJson(null, -32600, "Payload Too Large"), "application/json")
                return
            }
            val body = if (contentLength > 0) readBody(input, contentLength) else ""

            val response = runBlocking { handleRpc(body) }
            if (response == null) {
                // JSON-RPC 通知:无响应体
                writeResponse(output, 202, "", "application/json")
            } else {
                writeResponse(output, 200, response, "application/json")
            }
        } catch (e: Exception) {
            LogCatcher.e(TAG, "处理 MCP 请求失败: ${e.message}", e)
            // 之前此处只在服务端日志留痕,客户端只能等到连接超时;尽力回 500 并断开
            try {
                writeResponse(
                    client.getOutputStream(),
                    500,
                    errorJson(null, -32603, "Internal error: ${e.message}"),
                    "application/json"
                )
            } catch (_: Exception) {
            }
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun handleRpc(body: String): String? {
        if (body.isBlank()) return errorJson(null, -32700, "Parse error: empty body")

        val request = try {
            JSONObject(body)
        } catch (e: Exception) {
            return errorJson(null, -32700, "Parse error: ${e.message}")
        }

        val hasId = request.has("id")
        val id: Any? = if (hasId) request.opt("id") else null
        val method = request.optString("method", "")
        val params = request.optJSONObject("params") ?: JSONObject()

        synchronized(logLock) {
            requestLog.addLast("${System.currentTimeMillis()}\t$method")
            while (requestLog.size > 100) requestLog.removeFirst()
        }

        // JSON-RPC 通知(无 id)不返回响应体
        if (method.startsWith("notifications/") || !hasId) return null

        return when (method) {
            "initialize" -> {
                // 按 MCP 协商语义回**本服务支持**的版本:回显客户端值会谎称支持任意版本
                val result = JSONObject()
                    .put("protocolVersion", PROTOCOL_VERSION)
                    .put(
                        "capabilities",
                        JSONObject().put("tools", JSONObject().put("listChanged", false))
                    )
                    .put(
                        "serverInfo",
                        JSONObject().put("name", "luaforge-studio-mcp").put("version", SERVER_VERSION)
                    )
                successJson(id, result)
            }

            "ping" -> successJson(id, JSONObject())

            "tools/list" -> successJson(id, JSONObject().put("tools", toolList()))

            "tools/call" -> {
                val name = params.optString("name", "")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                val result = try {
                    McpTools.call(context, name, args)
                } catch (e: Exception) {
                    LogCatcher.e(TAG, "工具调用失败: $name", e)
                    errorResultJson("错误: ${e.message}")
                }
                successJson(id, result)
            }

            "resources/list" -> successJson(id, JSONObject().put("resources", JSONArray()))
            "prompts/list" -> successJson(id, JSONObject().put("prompts", JSONArray()))

            else -> errorJson(id, -32601, "Method not found: $method")
        }
    }

    // ------------------------------------------------------------------
    // HTTP 解析与写出
    // ------------------------------------------------------------------

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var read: Int
        while (true) {
            read = input.read()
            if (read == -1) {
                return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            }
            if (read == '\n'.code) break
            if (read != '\r'.code) buffer.write(read)
        }
        return buffer.toString("UTF-8")
    }

    private fun readBody(input: InputStream, length: Int): String {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(data, offset, length - offset)
            if (read == -1) break
            offset += read
        }
        return String(data, 0, offset, StandardCharsets.UTF_8)
    }

    private fun writeResponse(output: java.io.OutputStream, status: Int, body: String, contentType: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            202 -> "Accepted"
            204 -> "No Content"
            401 -> "Unauthorized"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "OK"
        }
        val header = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: *\r\n")
            append("Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        if (bytes.isNotEmpty()) output.write(bytes)
        output.flush()
    }

    private fun successJson(id: Any?, result: JSONObject): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("result", result)
            .toString()

    private fun errorJson(id: Any?, code: Int, message: String): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))
            .toString()

    /** tools/call 失败时的标准结果体(isError=true),与 McpTools 的结果结构一致。 */
    private fun errorResultJson(message: String): JSONObject =
        JSONObject()
            .put(
                "content",
                JSONArray().put(JSONObject().put("type", "text").put("text", message))
            )
            .put("isError", true)

    companion object {
        private const val TAG = "McpServer"
        const val PROTOCOL_VERSION = "2024-11-05"
        const val SERVER_VERSION = "1.0.0"

        /** 请求体上限:防止畸形/超长 Content-Length 触发巨型分配。 */
        private const val MAX_BODY_BYTES = 8 * 1024 * 1024

        /** 并发客户端线程上限与排队上限,超限直接 503。 */
        private const val MAX_CLIENT_THREADS = 8
        private const val MAX_QUEUED_CLIENTS = 32

        /** 枚举本机可用于访问服务的 IPv4 地址。 */
        fun localAddresses(): List<String> {
            val result = ArrayList<String>()
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
                for (ni in interfaces) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (address in ni.inetAddresses) {
                        val host = address.hostAddress ?: continue
                        if (address is java.net.Inet4Address && !address.isLoopbackAddress) {
                            result.add(host)
                        }
                    }
                }
            } catch (e: Exception) {
                LogCatcher.e(TAG, "枚举网络地址失败", e)
            }
            if (result.isEmpty()) result.add("127.0.0.1")
            return result
        }
    }
}
