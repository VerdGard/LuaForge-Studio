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

/**
 * 极简 MCP (Model Context Protocol) HTTP 服务端。
 *
 * - 传输层:HTTP/1.1,POST 承载 JSON-RPC 2.0 请求,GET 返回服务状态。
 * - 协议方法:initialize / ping / tools/list / tools/call / resources/list / prompts/list。
 * - 无第三方依赖,使用 java.net.ServerSocket,每个连接一个守护线程。
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

    private val requestLog = ArrayDeque<String>()
    private val logLock = Any()

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
        acceptThread = null
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
            .put("toolCount", McpTools.listTools().length())
            .put("addresses", JSONArray(localAddresses().map { "http://$it:$port" }))
        return result
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
            Thread({ handleClient(client) }, "mcp-client").apply { isDaemon = true }.start()
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
                val result = JSONObject()
                    .put("protocolVersion", request.optString("protocolVersion", PROTOCOL_VERSION))
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

            "tools/list" -> successJson(id, JSONObject().put("tools", McpTools.listTools()))

            "tools/call" -> {
                val name = params.optString("name", "")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                val result = try {
                    McpTools.call(context, name, args)
                } catch (e: Exception) {
                    LogCatcher.e(TAG, "工具调用失败: $name", e)
                    JSONObject()
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject().put("type", "text")
                                    .put("text", "错误: ${e.message}")
                            )
                        )
                        .put("isError", true)
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

    companion object {
        private const val TAG = "McpServer"
        const val PROTOCOL_VERSION = "2024-11-05"
        const val SERVER_VERSION = "1.0.0"

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
