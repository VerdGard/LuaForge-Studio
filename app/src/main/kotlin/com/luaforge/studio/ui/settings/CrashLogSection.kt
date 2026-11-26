package com.luaforge.studio.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luaforge.studio.R
import com.luaforge.studio.ui.crash.CrashEntry
import com.luaforge.studio.ui.crash.CrashRecorder
import com.luaforge.studio.ui.crash.CrashStore
import com.luaforge.studio.ui.components.tvFocusHighlight
import com.luaforge.studio.utils.ClipboardUtil
import com.luaforge.studio.utils.NonBlockingToastState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设置页的「崩溃记录」区域。
 *
 * 数据来自 [CrashRecorder] 落盘的 `/storage/emulated/0/LuaForge-Studio/crash/*.log`
 * (外部不可写时为私有 `filesDir/crash/`)。列表**每行一条崩溃**,时间精确到秒;
 * 点开才是完整正文(含异常类型、线程、设备与应用版本、完整堆栈、cause 链)。
 *
 * 列表用 `Column` 而不是嵌套 `LazyColumn`:记录量级是几十条,而设置页本身
 * 已经在可滚动的 `LazyColumn` 里,嵌套同向滚动会抢手势。
 */
@Composable
fun CrashLogCard(toast: NonBlockingToastState) {
    val context = LocalContext.current

    var entries by remember { mutableStateOf<List<CrashEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<CrashEntry?>(null) }
    var detailText by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }
    // 设置页的卡片一律可折叠(与其它分组一致);默认展开,列表本身就是这块的全部内容
    var expanded by remember { mutableStateOf(true) }

    suspend fun reload() {
        val loaded = withContext(Dispatchers.IO) { CrashStore.list(context) }
        entries = loaded
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    // 新的崩溃落盘后即时刷新(用户可能正停在设置页)
    DisposableEffect(Unit) {
        val unregister = CrashRecorder.observe {
            entries = CrashStore.list(context)
        }
        onDispose { unregister() }
    }

    SettingsCardGroup(
        title = stringResource(R.string.settings_crash_title),
        icon = Icons.Filled.BugReport,
        initiallyExpanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_crash_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.settings_crash_count, entries.size),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                if (entries.isNotEmpty()) {
                    TextButton(onClick = { showClearConfirm = true }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_crash_clear_all))
                    }
                }
            }

            when {
                loading -> Text(
                    text = stringResource(R.string.settings_crash_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )

                entries.isEmpty() -> Text(
                    text = stringResource(R.string.settings_crash_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )

                else -> Column(modifier = Modifier.fillMaxWidth()) {
                    entries.forEachIndexed { index, entry ->
                        CrashRow(
                            entry = entry,
                            onClick = {
                                selected = entry
                                detailText = CrashStore.read(entry)
                            },
                            onDelete = {
                                if (CrashStore.delete(entry)) {
                                    entries = entries.filterNot { it.file.name == entry.file.name }
                                    toast.showToast(
                                        context.getString(R.string.settings_crash_deleted)
                                    )
                                }
                            }
                        )
                        if (index < entries.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 4.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- 详情对话框 ----
    selected?.let { entry ->
        var resolvedType by remember(entry) { mutableStateOf("") }
        LaunchedEffect(entry) {
            resolvedType = withContext(Dispatchers.IO) {
                CrashStore.typeOf(entry) ?: context.getString(R.string.unknown)
            }
        }
        AlertDialog(
            onDismissRequest = { selected = null },
            icon = { Icon(Icons.Filled.BugReport, contentDescription = null) },
            title = {
                Column {
                    Text(
                        text = stringResource(R.string.settings_crash_detail_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = entry.timeText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (resolvedType.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = resolvedType,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            text = {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = detailText.ifBlank {
                                stringResource(R.string.settings_crash_detail_unreadable)
                            },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        ClipboardUtil.copy(context, detailText)
                        toast.showToast(context.getString(R.string.settings_crash_copied))
                    }
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_crash_copy))
                }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    // ---- 清空确认 ----
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_crash_clear_all)) },
            text = { Text(stringResource(R.string.settings_crash_clear_confirm, entries.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        val removed = CrashStore.clear(context)
                        entries = emptyList()
                        toast.showToast(
                            context.getString(R.string.settings_crash_cleared, removed)
                        )
                    }
                ) {
                    Text(
                        text = stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * 列表中的一行:时间(到秒)+ 类型/大小摘要 + 删除按钮。
 *
 * 「每行一个崩溃日志」是硬要求,所以两行文本都强制单行省略 —— 堆栈很长,
 * 让它换行会把列表撑成不可用的高度。
 */
@Composable
private fun CrashRow(
    entry: CrashEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusHighlight(MaterialTheme.colorScheme.primary, cornerRadius = 12)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.BugReport,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.timeText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = entry.file.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.settings_crash_delete_one),
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
