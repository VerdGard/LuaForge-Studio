package com.luaforge.studio.mcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * 接收 MCP 常驻通知的动作广播。
 *
 * 目前仅处理「复制 MCP 地址」——点击通知上的复制按钮即把当前服务地址写入剪贴板。
 */
class McpNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != McpKeepAliveService.ACTION_COPY_MCP_ADDRESS) return
        val address = intent.getStringExtra(McpKeepAliveService.EXTRA_ADDRESS).orEmpty()
        if (address.isBlank()) return
        McpKeepAliveService.copyAddress(context, address)
        Toast.makeText(
            context,
            context.getString(com.luaforge.studio.R.string.settings_mcp_address_copied),
            Toast.LENGTH_SHORT
        ).show()
    }
}
