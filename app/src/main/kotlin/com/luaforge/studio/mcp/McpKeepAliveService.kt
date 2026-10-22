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

    /**
     * 最后一次展示过的地址清单。
     *
     * `START_STICKY` 让系统在进程被回收后重建服务,此时 `onStartCommand` 拿到的
     * intent 是 **null**。旧实现在这种情况下会把地址算成空清单,通知退化成
     * "MCP 服务后台保活中",用户看不到任何可用地址 —— 看起来就像服务挂了。
     * 这里把清单缓存下来,重建时继续沿用。
     */
    private var lastAddresses: List<String> = emptyList()
    private var lastShowNotification: Boolean = true

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fromIntent = intent?.getStringArrayListExtra(EXTRA_ADDRESSES)
            ?: arrayListOf<kotlin.String>().also {
                // 兼容旧调用方:单个地址仍可通过 EXTRA_ADDRESS 传入
                intent?.getStringExtra(EXTRA_ADDRESS)?.takeIf { a -> a.isNotBlank() }?.let(it::add)
            }
        if (fromIntent.isNotEmpty()) lastAddresses = fromIntent
        val showNotification =
            intent?.getBooleanExtra(EXTRA_SHOW_NOTIFICATION, true) ?: lastShowNotification
        lastShowNotification = showNotification

        val addresses = if (fromIntent.isNotEmpty()) fromIntent else lastAddresses
        val notification =
            if (showNotification) buildNotification(addresses) else buildSilentNotification()
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

    /**
     * 构建常驻通知:**每个地址各占一行**,每行配一个独立的复制按钮。
     *
     * 旧实现只有一个地址和一个"复制地址"按钮,多网卡(回环 + Wi-Fi)时另外的
     * 地址只能去设置页翻。现在通知本身就是完整清单。
     *
     * 文案不再写"点击按钮可复制"这类说明 —— 按钮上的文字已经说明了动作,
     * 标题行只留地址本身。
     */
    private fun buildNotification(addresses: List<String>): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        // 点击通知正文:回到应用主界面;拿不到启动 Intent 时退化为打开保活服务本身
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, McpKeepAliveService::class.java)
        val contentIntent = PendingIntent.getActivity(this, 0, launchIntent, flags)

        val list = addresses.filter { it.isNotBlank() }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(com.luaforge.studio.R.string.mcp_notification_title))
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (list.isEmpty()) {
            builder.setContentText(getString(com.luaforge.studio.R.string.mcp_notification_text_unknown))
        } else {
            // 折叠态显示第一条(回环地址),展开态显示全部 —— 一行一个
            builder.setContentText(list.first())
            val style = NotificationCompat.BigTextStyle().bigText(list.joinToString("\n"))
            builder.setStyle(style)
            // 每个地址一个复制按钮。通知动作栏放不下太多,取前 MAX_ACTIONS 条;
            // 回环地址在首位,因此总是可见。
            list.take(MAX_COPY_ACTIONS).forEachIndexed { index, address ->
                builder.addAction(
                    android.R.drawable.ic_menu_share,
                    getString(com.luaforge.studio.R.string.mcp_notification_copy_one, address),
                    copyPendingIntent(index, address, flags)
                )
            }
        }
        return builder.build()
    }

    /** 为某个地址生成"复制这一条"的广播 PendingIntent。 */
    private fun copyPendingIntent(index: Int, address: String, flags: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            COPY_REQUEST_BASE + index,
            Intent(ACTION_COPY_MCP_ADDRESS).setPackage(packageName)
                .putExtra(EXTRA_ADDRESS, address),
            flags
        )

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
        const val EXTRA_ADDRESSES = "mcp_addresses"
        const val EXTRA_SHOW_NOTIFICATION = "mcp_show_notification"
        const val ACTION_COPY_MCP_ADDRESS = "com.luaforge.studio.action.COPY_MCP_ADDRESS"

        /** 通知动作栏最多放几个"复制"按钮(系统限制约 3 个)。 */
        private const val MAX_COPY_ACTIONS = 3

        /** 复制动作的 PendingIntent requestCode 起点,逐地址递增。 */
        private const val COPY_REQUEST_BASE = 100

        /** 启动/更新前台保活服务。 */
        fun start(context: Context, addresses: List<String>, showNotification: Boolean) {
            val intent = Intent(context, McpKeepAliveService::class.java).apply {
                putStringArrayListExtra(EXTRA_ADDRESSES, ArrayList(addresses))
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
