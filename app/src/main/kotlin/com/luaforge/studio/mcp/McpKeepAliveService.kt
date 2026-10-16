package com.luaforge.studio.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService

/**
 * MCP 前台保活服务。
 *
 * Android 8+ 对后台进程有严格的回收策略,端口常监听的 MCP 服务在退到后台后
 * 可能被系统杀死。开启“前台保活”后,本服务以前台服务形式常驻,并显示一条常驻
 * 通知;点击通知即可复制 MCP 访问地址。
 *
 * 服务本身不持有服务器实例——服务器仍由 [McpManager] 管理,本服务只负责
 * 提升进程优先级与展示通知。
 */
class McpKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = intent?.getStringExtra(EXTRA_ADDRESS).orEmpty()
        val showNotification = intent?.getBooleanExtra(EXTRA_SHOW_NOTIFICATION, true) ?: true
        val notification = if (showNotification) buildNotification(address) else buildSilentNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            // 部分 ROM 在前台服务启动有竞态,忽略即可,不影响服务器运行
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun buildNotification(address: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        // 点击通知正文:回到应用主界面;拿不到启动 Intent 时退化为打开保活服务本身
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, McpKeepAliveService::class.java)
        val contentIntent = PendingIntent.getActivity(this, 0, launchIntent, flags)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(com.luaforge.studio.R.string.mcp_notification_title))
            .setContentText(
                if (address.isNotBlank()) {
                    getString(com.luaforge.studio.R.string.mcp_notification_text, address)
                } else {
                    getString(com.luaforge.studio.R.string.mcp_notification_text_unknown)
                }
            )
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // 点击动作:复制 MCP 地址
        if (address.isNotBlank()) {
            val copyIntent = PendingIntent.getBroadcast(
                this,
                1,
                Intent(ACTION_COPY_MCP_ADDRESS).setPackage(packageName)
                    .putExtra(EXTRA_ADDRESS, address),
                flags
            )
            builder.addAction(
                android.R.drawable.ic_menu_share,
                getString(com.luaforge.studio.R.string.mcp_notification_copy),
                copyIntent
            )
        }
        return builder.build()
    }

    private fun buildSilentNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(com.luaforge.studio.R.string.mcp_notification_title))
            .setContentText(getString(com.luaforge.studio.R.string.mcp_notification_text_unknown))
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService<NotificationManager>() ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(com.luaforge.studio.R.string.mcp_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(com.luaforge.studio.R.string.mcp_notification_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "mcp_keepalive"
        const val NOTIFICATION_ID = 9123
        const val EXTRA_ADDRESS = "mcp_address"
        const val EXTRA_SHOW_NOTIFICATION = "mcp_show_notification"
        const val ACTION_COPY_MCP_ADDRESS = "com.luaforge.studio.action.COPY_MCP_ADDRESS"

        /** 启动/更新前台保活服务。 */
        fun start(context: Context, address: String, showNotification: Boolean) {
            val intent = Intent(context, McpKeepAliveService::class.java).apply {
                putExtra(EXTRA_ADDRESS, address)
                putExtra(EXTRA_SHOW_NOTIFICATION, showNotification)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // 无通知权限或后台启动限制时静默失败
            }
        }

        /** 停止前台保活服务。 */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, McpKeepAliveService::class.java))
            } catch (_: Exception) {
            }
        }

        /** 把地址复制到剪贴板(供广播接收器调用)。 */
        fun copyAddress(context: Context, address: String) {
            val clipboard = context.getSystemService<ClipboardManager>() ?: return
            clipboard.setPrimaryClip(ClipData.newPlainText("MCP Endpoint", address))
        }
    }
}
