package com.luaforge.studio.ui.mcp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.luaforge.studio.R
import com.luaforge.studio.mcp.McpManager
import com.luaforge.studio.ui.settings.SettingsCardGroup
import com.luaforge.studio.ui.settings.SettingsData
import com.luaforge.studio.ui.settings.SettingsListItem
import com.luaforge.studio.ui.settings.SettingsManager
import com.luaforge.studio.utils.BatteryOptimization
import com.luaforge.studio.utils.NonBlockingToastState

/**
 * MCP 服务管理页(侧边栏「MCP」)。
 *
 * ## 与设置页的一致性
 *
 * 本页刻意**不带自己的 Scaffold / TopAppBar**:标题栏由 `MainActivity` 统一提供
 * (`MainScreen` 的 `LargeTopAppBar` 根据不同 `MainContentType` 显示标题),
 * 与「设置」「关于」两页完全同一套外壳。早期版本这里叠了一层 Scaffold,
 * 表现是顶部出现两条标题栏、且返回按钮与系统返回行为不一致。
 *
 * 根布局同样与设置页对齐:`Box` + `LazyColumn`,
 * `contentPadding` 16dp、卡片间距 16dp。
 *
 * ## 内容
 *
 * 与原先挤在「设置 → MCP 服务」里的那张卡片完全等价(同一批
 * `SettingsCardGroup` / `SettingsListItem` 组件),只是不再需要折叠开关 ——
 * 独立成页后直接展开。
 */
@Composable
fun McpScreen(
    onBack: () -> Unit,
    currentSettings: SettingsData,
    onSettingsChanged: (SettingsData) -> Unit,
    toast: NonBlockingToastState
) {
    val context = LocalContext.current
    val currentSettingsState = SettingsManager.currentSettings

    var mcpPortText by remember { mutableStateOf(currentSettingsState.mcpPort.toString()) }
    var mcpTokenText by remember { mutableStateOf(currentSettingsState.mcpToken) }

    fun saveSettings(newSettings: SettingsData) {
        SettingsManager.updateSettings(newSettings)
        SettingsManager.saveSettings(context)
        onSettingsChanged(newSettings)
    }

    // 通知权限(Android 13+ 运行时权限):常驻通知显示的前提
    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    var notificationPermissionGranted by remember { mutableStateOf(hasNotificationPermission()) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionGranted = granted
        if (!granted) {
            toast.showToast(context.getString(R.string.settings_mcp_notification_permission_toast))
        } else if (currentSettingsState.mcpForegroundKeepAlive &&
            currentSettingsState.mcpPersistentNotification
        ) {
            // 授权后立即刷新保活通知,无需等待下次设置变更
            McpManager.refreshKeepAliveNotification(context, true, null)
        }
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationPermissionGranted = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)
        ) {
            item {
                SettingsCardGroup(
                    title = stringResource(R.string.settings_mcp_service),
                    icon = Icons.Filled.Hub,
                    initiallyExpanded = true,
                    onExpandedChange = { }
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SettingsListItem(
                            title = stringResource(R.string.settings_mcp_enable),
                            subtitle = stringResource(R.string.settings_mcp_enable_desc),
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Hub,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = currentSettingsState.mcpEnabled,
                                    onCheckedChange = { enabled ->
                                        saveSettings(
                                            currentSettingsState.copy(mcpEnabled = enabled)
                                        )
                                    }
                                )
                            },
                            onClick = {
                                saveSettings(
                                    currentSettingsState.copy(mcpEnabled = !currentSettingsState.mcpEnabled)
                                )
                            }
                        )

                        if (currentSettingsState.mcpEnabled) {
                            OutlinedTextField(
                                value = mcpPortText,
                                onValueChange = { input ->
                                    mcpPortText = input.filter { it.isDigit() }.take(5)
                                    val parsed = mcpPortText.toIntOrNull()
                                    if (parsed != null && parsed in 1024..65535) {
                                        saveSettings(
                                            currentSettingsState.copy(mcpPort = parsed)
                                        )
                                    }
                                },
                                label = { Text(stringResource(R.string.settings_mcp_port)) },
                                supportingText = { Text(stringResource(R.string.settings_mcp_port_desc)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )

                            SettingsListItem(
                                title = stringResource(R.string.settings_mcp_require_token),
                                subtitle = stringResource(R.string.settings_mcp_require_token_desc),
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = currentSettingsState.mcpRequireToken,
                                        onCheckedChange = { require ->
                                            var newToken = currentSettingsState.mcpToken
                                            if (require && newToken.isBlank()) {
                                                newToken = SettingsManager.generateMcpToken()
                                                mcpTokenText = newToken
                                            }
                                            saveSettings(
                                                currentSettingsState.copy(
                                                    mcpRequireToken = require,
                                                    mcpToken = newToken
                                                )
                                            )
                                        }
                                    )
                                },
                                onClick = {
                                    var newToken = currentSettingsState.mcpToken
                                    val require = !currentSettingsState.mcpRequireToken
                                    if (require && newToken.isBlank()) {
                                        newToken = SettingsManager.generateMcpToken()
                                        mcpTokenText = newToken
                                    }
                                    saveSettings(
                                        currentSettingsState.copy(
                                            mcpRequireToken = require,
                                            mcpToken = newToken
                                        )
                                    )
                                }
                            )

                            if (currentSettingsState.mcpRequireToken) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = mcpTokenText,
                                        onValueChange = { input ->
                                            mcpTokenText = input
                                            saveSettings(
                                                currentSettingsState.copy(mcpToken = input)
                                            )
                                        },
                                        label = { Text(stringResource(R.string.settings_mcp_token)) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            val newToken = SettingsManager.generateMcpToken()
                                            mcpTokenText = newToken
                                            saveSettings(
                                                currentSettingsState.copy(mcpToken = newToken)
                                            )
                                        }
                                    ) {
                                        Icon(
                                            Icons.Filled.RestartAlt,
                                            contentDescription = stringResource(R.string.settings_mcp_generate_token)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            copyToClipboard(
                                                context,
                                                "MCP Token",
                                                currentSettingsState.mcpToken
                                            )
                                            toast.showToast(
                                                context.getString(R.string.settings_mcp_token_copied)
                                            )
                                        }
                                    ) {
                                        Icon(
                                            Icons.Filled.ContentCopy,
                                            contentDescription = stringResource(R.string.settings_mcp_copy_token)
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.05f)
                            )

                            // 前台保活:以前台服务常驻,降低退后台被回收的概率
                            SettingsListItem(
                                title = stringResource(R.string.settings_mcp_foreground_keepalive),
                                subtitle = stringResource(R.string.settings_mcp_foreground_keepalive_desc),
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Security,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = currentSettingsState.mcpForegroundKeepAlive,
                                        onCheckedChange = { enabled ->
                                            saveSettings(
                                                currentSettingsState.copy(mcpForegroundKeepAlive = enabled)
                                            )
                                            if (enabled) requestNotificationPermission()
                                        }
                                    )
                                },
                                onClick = {
                                    val enabled = !currentSettingsState.mcpForegroundKeepAlive
                                    saveSettings(
                                        currentSettingsState.copy(
                                            mcpForegroundKeepAlive = enabled
                                        )
                                    )
                                    if (enabled) requestNotificationPermission()
                                }
                            )

                            // 通知权限(Android 13+ 运行时权限;未授予时保活通知不显示)
                            SettingsListItem(
                                title = stringResource(R.string.settings_mcp_notification_permission),
                                subtitle = stringResource(
                                    if (notificationPermissionGranted)
                                        R.string.settings_mcp_notification_permission_granted
                                    else
                                        R.string.settings_mcp_notification_permission_denied
                                ),
                                leadingIcon = {
                                    Icon(
                                        if (notificationPermissionGranted) Icons.Filled.CheckCircle
                                        else Icons.Filled.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                onClick = { requestNotificationPermission() }
                            )

                            // 通知栏常驻(仅在保活开启时才有意义)
                            if (currentSettingsState.mcpForegroundKeepAlive) {
                                SettingsListItem(
                                    title = stringResource(R.string.settings_mcp_persistent_notification),
                                    subtitle = stringResource(R.string.settings_mcp_persistent_notification_desc),
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.Info,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    trailingContent = {
                                        Switch(
                                            checked = currentSettingsState.mcpPersistentNotification,
                                            onCheckedChange = { enabled ->
                                                saveSettings(
                                                    currentSettingsState.copy(mcpPersistentNotification = enabled)
                                                )
                                                if (enabled) requestNotificationPermission()
                                            }
                                        )
                                    },
                                    onClick = {
                                        val enabled = !currentSettingsState.mcpPersistentNotification
                                        saveSettings(
                                            currentSettingsState.copy(
                                                mcpPersistentNotification = enabled
                                            )
                                        )
                                        if (enabled) requestNotificationPermission()
                                    }
                                )
                            }

                            // 电池优化豁免:进一步保障后台存活
                            SettingsListItem(
                                title = stringResource(R.string.settings_mcp_battery_exempt),
                                subtitle = stringResource(R.string.settings_mcp_battery_exempt_desc),
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Security,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                trailingContent = {
                                    val exempt = BatteryOptimization
                                        .isIgnoringBatteryOptimizations(context)
                                    Icon(
                                        if (exempt) Icons.Filled.CheckCircle else Icons.Filled.Security,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                onClick = {
                                    val ran = BatteryOptimization.requestExemption(context)
                                    if (!ran) {
                                        toast.showToast(
                                            context.getString(R.string.settings_mcp_battery_exempt_unavailable)
                                        )
                                    } else {
                                        saveSettings(
                                            currentSettingsState.copy(mcpBatteryExempt = true)
                                        )
                                    }
                                }
                            )

                            val mcpStatus = McpManager.status
                            Text(
                                text = if (mcpStatus.running) {
                                    stringResource(R.string.settings_mcp_status_running, mcpStatus.port)
                                } else {
                                    stringResource(R.string.settings_mcp_status_stopped)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = if (mcpStatus.running) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (mcpStatus.running && mcpStatus.addresses.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.settings_mcp_endpoints),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    mcpStatus.addresses.forEach { address ->
                                        EndpointRow(
                                            address = address,
                                            onCopy = {
                                                copyToClipboard(context, "MCP Endpoint", address)
                                                toast.showToast(
                                                    context.getString(
                                                        R.string.settings_mcp_address_copied
                                                    )
                                                )
                                            }
                                        )
                                    }
                                }
                            }

                            if (!mcpStatus.error.isNullOrBlank()) {
                                Text(
                                    text = mcpStatus.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            Text(
                                text = stringResource(R.string.settings_mcp_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * MCP 服务地址行:等宽字体展示地址,尾部提供一键复制按钮。
 *
 * 回环地址(127.0.0.1 / ::1)与局域网地址用不同标签区分 —— 前者只能在本机
 * 访问(MCP 客户端跑同一台设备时用它),后者给同一网络下的其他设备用。
 * 不分清楚的话用户常拿局域网地址去配本机客户端,结果连不上。
 */
@Composable
fun EndpointRow(
    address: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val isLoopback = address.contains("127.0.0.1") || address.contains("[::1]")
            Text(
                text = address,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 10.dp)
            )
            Text(
                text = stringResource(
                    if (isLoopback) R.string.settings_mcp_endpoint_local
                    else R.string.settings_mcp_endpoint_lan
                ),
                style = MaterialTheme.typography.labelSmall,
                color = if (isLoopback) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.settings_mcp_copy_address),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** 复制到剪贴板;失败静默(与设置页同一行为)。 */
private fun copyToClipboard(context: Context, label: String, text: String) {
    try {
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    } catch (_: Exception) {
        // 剪贴板不可用时不阻塞界面
    }
}
