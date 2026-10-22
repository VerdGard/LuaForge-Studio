package com.luaforge.studio.mcp

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.luaforge.studio.ui.settings.SettingsData
import com.luaforge.studio.utils.LogCatcher

/**
 * MCP 服务生命周期管理器(进程级单例)。
 *
 * 状态以 Compose State 暴露,设置页可直接观察。服务随应用进程存活;
 * 关闭开关或应用进程结束即停止。
 */
object McpManager {

    private const val TAG = "McpManager"

    private var appContext: Context? = null
    private var server: McpServer? = null

    /** 供 Compose 观察的运行状态。 */
    var status by mutableStateOf(McpStatus())
        private set

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 根据设置同步服务状态:启用则(重新)启动,禁用则停止。
     * 端口或令牌变化时会自动重启。
     */
    fun applySettings(settings: SettingsData) {
        val context = appContext ?: run {
            LogCatcher.w(TAG, "McpManager 未初始化,忽略 applySettings")
            return
        }

        if (!settings.mcpEnabled) {
            stop()
            McpKeepAliveService.stop(context)
            return
        }

        // 仅在需要令牌时才启用鉴权,避免残留令牌导致误拦截
        val effectiveToken = if (settings.mcpRequireToken) settings.mcpToken else ""

        val current = server
        if (current != null &&
            current.isRunning &&
            status.port == settings.mcpPort &&
            status.token == effectiveToken
        ) {
            syncKeepAlive(context, settings)
            return
        }

        start(context, settings.mcpPort, effectiveToken)
        // 启动(或重启)后同步前台保活:重启场景下通知里的地址需要刷新
        syncKeepAlive(context, settings)
    }

    @Synchronized
    fun start(context: Context, port: Int, token: String): Boolean {
        stop()
        return try {
            val instance = McpServer(context, port, token)
            val started = instance.start()
            if (started) {
                server = instance
                status = McpStatus(
                    running = true,
                    port = instance.boundPort,
                    token = token,
                    addresses = McpServer.localAddresses().map { "http://$it:${instance.boundPort}" },
                    error = null
                )
                LogCatcher.i(TAG, "MCP 服务运行中,端口 ${instance.boundPort}")
            } else {
                status = McpStatus(
                    running = false,
                    port = port,
                    token = token,
                    addresses = emptyList(),
                    error = "端口 $port 绑定失败或被占用"
                )
            }
            started
        } catch (e: Exception) {
            LogCatcher.e(TAG, "启动 MCP 服务异常", e)
            status = McpStatus(
                running = false,
                port = port,
                token = token,
                addresses = emptyList(),
                error = e.message ?: "未知错误"
            )
            false
        }
    }

    /**
     * 根据设置同步前台保活服务:开启则(重新)启动并刷新通知中的地址,关闭则停止。
     * 仅在服务器实际运行时保活才有意义。
     */
    private fun syncKeepAlive(context: Context, settings: SettingsData) {
        if (settings.mcpForegroundKeepAlive && isRunning()) {
            McpKeepAliveService.start(context, status.addresses, settings.mcpPersistentNotification)
        } else {
            McpKeepAliveService.stop(context)
        }
    }

    /**
     * 刷新前台保活通知中展示的地址(端口/网卡变化后调用)。
     *
     * @param addresses 非空时用它覆盖;为 null 则用当前服务的地址清单。
     */
    fun refreshKeepAliveNotification(
        context: Context,
        showNotification: Boolean,
        addresses: List<String>? = null
    ) {
        if (!isRunning()) return
        McpKeepAliveService.start(context, addresses ?: status.addresses, showNotification)
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        status = McpStatus()
    }

    fun isRunning(): Boolean = server?.isRunning == true

    fun recentRequests(limit: Int = 20): List<String> = server?.recentRequests(limit) ?: emptyList()

    fun refreshStatus() {
        val instance = server
        status = if (instance != null && instance.isRunning) {
            status.copy(
                running = true,
                port = instance.boundPort,
                addresses = McpServer.localAddresses().map { "http://$it:${instance.boundPort}" }
            )
        } else {
            McpStatus()
        }
    }
}

/** MCP 服务运行状态快照。 */
data class McpStatus(
    val running: Boolean = false,
    val port: Int = 9123,
    val token: String = "",
    val addresses: List<String> = emptyList(),
    val error: String? = null
)
