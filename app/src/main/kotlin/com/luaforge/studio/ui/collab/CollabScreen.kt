package com.luaforge.studio.ui.collab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.luaforge.studio.R
import com.luaforge.studio.collab.CollabManager
import com.luaforge.studio.collab.GitStore
import com.luaforge.studio.ui.components.FilePickerDialog
import com.luaforge.studio.ui.components.SelectionMode
import com.luaforge.studio.ui.settings.SettingsData
import com.luaforge.studio.ui.settings.SettingsManager
import com.luaforge.studio.utils.NonBlockingToastState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 局域网协同开发界面。
 *
 * 支持两种角色切换:
 * - 共享端:选择要共享的项目目录,启动服务,其他设备即可连接同步;
 * - 连接端:填写共享端地址,把项目同步到本地目录,并自动实时刷新。
 *
 * 同时提供 git 快照(提交)与回档能力。
 */
@Composable
fun CollabScreen(
    onBack: () -> Unit,
    currentSettings: SettingsData,
    onSettingsChanged: (SettingsData) -> Unit,
    toast: NonBlockingToastState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = SettingsManager.currentSettings

    var portText by remember { mutableStateOf(state.collabPort.toString()) }
    var tokenText by remember { mutableStateOf(state.collabToken) }
    var remoteHostText by remember { mutableStateOf(state.collabRemoteHost) }
    var remotePortText by remember { mutableStateOf(state.collabRemotePort.toString()) }

    var showProjectPicker by remember { mutableStateOf(false) }
    var showLocalDirPicker by remember { mutableStateOf(false) }

    var snapshotMessage by remember { mutableStateOf("") }
    var showSnapshotDialog by remember { mutableStateOf(false) }
    var pendingRollback by remember { mutableStateOf<String?>(null) }

    var history by remember { mutableStateOf<List<GitStore.CommitInfo>>(emptyList()) }
    var changesCount by remember { mutableStateOf(0) }

    val collabStatus = CollabManager.status

    fun persist(new: SettingsData) {
        SettingsManager.updateSettings(new)
        SettingsManager.saveSettings(context)
        onSettingsChanged(new)
    }

    fun refreshHistory() {
        scope.launch {
            val repo = CollabManager.currentRepo() ?: return@launch
            val log = withContext(Dispatchers.IO) { repo.log(50) }
            val changes = withContext(Dispatchers.IO) { repo.status().size }
            history = log
            changesCount = changes
        }
    }

    LaunchedEffect(collabStatus.running, collabStatus.rev) {
        refreshHistory()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        // ---- 状态 ----
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.collab_status_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (collabStatus.running)
                            stringResource(R.string.collab_running) + " · " +
                                stringResource(R.string.collab_rev, collabStatus.rev)
                        else stringResource(R.string.collab_stopped),
                        color = if (collabStatus.running) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (collabStatus.addresses.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.collab_endpoints),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        collabStatus.addresses.forEach { addr ->
                            Text(
                                text = addr,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    if (!collabStatus.error.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.collab_error, collabStatus.error!!),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // ---- 角色选择 ----
        item {
            Text(
                text = stringResource(R.string.collab_role_select),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.collabRole == "HOST",
                    onClick = { persist(state.copy(collabRole = "HOST")) },
                    label = { Text(stringResource(R.string.collab_role_host)) }
                )
                FilterChip(
                    selected = state.collabRole == "CLIENT",
                    onClick = { persist(state.copy(collabRole = "CLIENT")) },
                    label = { Text(stringResource(R.string.collab_role_client)) }
                )
            }
        }

        if (state.collabRole == "HOST") {
            // ---- 共享端配置 ----
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.collab_shared_project),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.collab_shared_project_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = state.collabSharedProject,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.collab_shared_project)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = { showProjectPicker = true }) {
                            Text(stringResource(R.string.collab_pick_project))
                        }
                    }

                    OutlinedTextField(
                        value = portText,
                        onValueChange = { input ->
                            portText = input.filter { it.isDigit() }.take(5)
                            portText.toIntOrNull()?.let { p ->
                                if (p in 1024..65535) persist(state.copy(collabPort = p))
                            }
                        },
                        label = { Text(stringResource(R.string.collab_port)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = tokenText,
                        onValueChange = { tokenText = it; persist(state.copy(collabToken = it)) },
                        label = { Text(stringResource(R.string.collab_token)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val dir = state.collabSharedProject
                                if (dir.isBlank()) {
                                    toast.showToast(context.getString(R.string.collab_need_project))
                                    return@Button
                                }
                                val ok = CollabManager.startHost(
                                    File(dir), File(dir).name, state.collabPort, state.collabToken
                                )
                                toast.showToast(
                                    context.getString(
                                        if (ok) R.string.collab_started else R.string.collab_start_failed
                                    )
                                )
                                refreshHistory()
                            }
                        ) { Text(stringResource(R.string.collab_start)) }

                        OutlinedButton(onClick = { CollabManager.stop() }) {
                            Text(stringResource(R.string.collab_stop))
                        }
                    }
                }
            }
        } else {
            // ---- 连接端配置 ----
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.collab_remote_host),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    OutlinedTextField(
                        value = remoteHostText,
                        onValueChange = { remoteHostText = it; persist(state.copy(collabRemoteHost = it)) },
                        label = { Text(stringResource(R.string.collab_remote_host)) },
                        placeholder = { Text(stringResource(R.string.collab_remote_host_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = remotePortText,
                        onValueChange = { input ->
                            remotePortText = input.filter { it.isDigit() }.take(5)
                            remotePortText.toIntOrNull()?.let { p ->
                                if (p in 1024..65535) persist(state.copy(collabRemotePort = p))
                            }
                        },
                        label = { Text(stringResource(R.string.collab_remote_port)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tokenText,
                        onValueChange = { tokenText = it; persist(state.copy(collabToken = it)) },
                        label = { Text(stringResource(R.string.collab_token)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(R.string.collab_local_project),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = state.collabLocalProject,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.collab_local_project)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = { showLocalDirPicker = true }) {
                            Text(stringResource(R.string.collab_pick_dir))
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (remoteHostText.isBlank()) {
                                    toast.showToast(context.getString(R.string.collab_need_host))
                                    return@Button
                                }
                                if (state.collabLocalProject.isBlank()) {
                                    toast.showToast(context.getString(R.string.collab_need_project))
                                    return@Button
                                }
                                val ok = CollabManager.startClient(
                                    remoteHostText,
                                    state.collabRemotePort,
                                    state.collabToken,
                                    File(state.collabLocalProject)
                                )
                                toast.showToast(
                                    context.getString(
                                        if (ok) R.string.collab_started else R.string.collab_start_failed
                                    )
                                )
                                refreshHistory()
                            }
                        ) { Text(stringResource(R.string.collab_connect)) }

                        OutlinedButton(onClick = { CollabManager.stop() }) {
                            Text(stringResource(R.string.collab_stop))
                        }
                    }
                }
            }
        }

        // ---- 快照 / 回档 ----
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.collab_history),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.collab_changes, changesCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            snapshotMessage = ""
                            showSnapshotDialog = true
                        }
                    ) { Text(stringResource(R.string.collab_snapshot)) }
                    OutlinedButton(onClick = { refreshHistory() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.height(0.dp))
                        Text(" " + stringResource(R.string.collab_refresh))
                    }
                }
            }
        }

        if (history.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.collab_no_history),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(history) { commit ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = commit.message,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = commit.sha.take(8) + " · " +
                                    stringResource(R.string.collab_author) + ": " + commit.author,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = GitStore.formatTime(commit.timeMillis),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { pendingRollback = commit.sha }) {
                            Icon(
                                Icons.Filled.History,
                                contentDescription = stringResource(R.string.collab_rollback)
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- 项目目录选择 ----
    if (showProjectPicker) {
        FilePickerDialog(
            selectionMode = SelectionMode.DIRECTORY,
            title = stringResource(R.string.collab_pick_project),
            onDismiss = { showProjectPicker = false },
            onDirectorySelected = { path ->
                persist(SettingsManager.currentSettings.copy(collabSharedProject = path))
                showProjectPicker = false
            }
        )
    }
    if (showLocalDirPicker) {
        FilePickerDialog(
            selectionMode = SelectionMode.DIRECTORY,
            title = stringResource(R.string.collab_pick_dir),
            onDismiss = { showLocalDirPicker = false },
            onDirectorySelected = { path ->
                persist(SettingsManager.currentSettings.copy(collabLocalProject = path))
                showLocalDirPicker = false
            }
        )
    }

    // ---- 快照信息输入 ----
    if (showSnapshotDialog) {
        AlertDialog(
            onDismissRequest = { showSnapshotDialog = false },
            title = { Text(stringResource(R.string.collab_snapshot)) },
            text = {
                OutlinedTextField(
                    value = snapshotMessage,
                    onValueChange = { snapshotMessage = it },
                    label = { Text(stringResource(R.string.collab_snapshot_message)) },
                    placeholder = { Text(stringResource(R.string.collab_snapshot_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val repo = CollabManager.currentRepo()
                    showSnapshotDialog = false
                    if (repo == null) return@TextButton
                    scope.launch {
                        val author = com.luaforge.studio.utils.AppInfoUtil.getVersionName(context)
                        withContext(Dispatchers.IO) {
                            repo.snapshot(snapshotMessage.ifBlank { "快照" }, author)
                        }
                        refreshHistory()
                    }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showSnapshotDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // ---- 回档确认 ----
    pendingRollback?.let { sha ->
        AlertDialog(
            onDismissRequest = { pendingRollback = null },
            title = { Text(stringResource(R.string.collab_rollback)) },
            text = { Text(stringResource(R.string.collab_rollback_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    val repo = CollabManager.currentRepo()
                    pendingRollback = null
                    if (repo == null) return@TextButton
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { repo.checkout(sha) }
                        if (ok) {
                            toast.showToast(
                                context.getString(R.string.collab_rolled_back, sha.take(8))
                            )
                        }
                        refreshHistory()
                    }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRollback = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
