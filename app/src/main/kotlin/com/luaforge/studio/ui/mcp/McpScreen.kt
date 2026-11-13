package com.luaforge.studio.ui.mcp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.luaforge.studio.R
import com.luaforge.studio.mcp.McpManager
import com.luaforge.studio.ui.settings.EndpointRow
import com.luaforge.studio.ui.settings.SettingsCardGroup
import com.luaforge.studio.ui.settings.SettingsData
import com.luaforge.studio.ui.settings.SettingsListItem
import com.luaforge.studio.ui.settings.SettingsManager
import com.luaforge.studio.utils.NonBlockingToastState

/**
 * MCP 服务管理页。
 *
 * 这些配置原先挤在「设置」里,与主题、字体、构建参数混在一张长列表上 ——
 * MCP 是**高频操作**("改端口 / 复制地址 / 看是否在跑"),而设置是低频的,
 * 两者放一起导致每次调 MCP 都要在大列表里翻找。现在移到侧边栏独立成页。
 *
 * 内容与原先设置页里的 MCP 卡片完全一致(同一个 SettingsCardGroup /
 * SettingsListItem 组件),只是去掉了折叠(独立页无需再折叠)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpScreen(
    onBack: () -> Unit,
    currentSettings: SettingsData,
    onSettingsChanged: (SettingsData) -> Unit,
    toast: NonBlockingToastState
) {
    val context = LocalContext.current
    val currentSettingsState = SettingsManager.currentSettings

    // 折叠态:独立页默认展开,但保留折叠能力(便于快速收起长表单)
    var expanded by remember { mutableStateOf(true) }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_mcp)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        SettingsCardGroup(
            title = stringResource(R.string.settings_mcp),
            icon = Icons.Filled.Hub,
            initiallyExpanded = true,
            onExpandedChange = { expanded = it }
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
                                    val clipboard = context.getSystemService(
                                        android.content.ClipboardManager::class.java
                                    )
                                    clipboard?.setPrimaryClip(
                                        android.content.ClipData.newPlainText(
                                            "MCP Token",
                                            currentSettingsState.mcpToken
                                        )
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
                            val exempt = com.luaforge.studio.utils.BatteryOptimization
                                .isIgnoringBatteryOptimizations(context)
                            Icon(
                                if (exempt) Icons.Filled.CheckCircle else Icons.Filled.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        onClick = {
                            val ran = com.luaforge.studio.utils.BatteryOptimization
                                .requestExemption(context)
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

/** 复制到剪贴板;失败静默(与设置页同一行为)。 */
private fun copyToClipboard(context: Context, label: String, text: String) {
    try {
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    } catch (_: Exception) {
        // 剪贴板不可用时不阻塞界面
    }
}
