package com.luaforge.studio.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luaforge.studio.R
import com.luaforge.studio.utils.LogBuffer
import com.luaforge.studio.utils.LogFileAccess
import com.luaforge.studio.utils.NonBlockingToastState

/**
 * 编辑器自身日志查看页(侧边栏「日志」,仿 [LogFox](https://github.com/F0x1d/LogFox))。
 *
 * ## 外壳与设置页一致
 *
 * 与「设置」「关于」一样**不带自己的 Scaffold / TopAppBar**:标题栏由
 * `MainActivity` 的 `LargeTopAppBar` 按 `MainContentType` 提供。早期版本这里
 * 叠了 Scaffold,顶部会出现两条标题栏。根布局统一为 `Box` + `LazyColumn`,
 * 内容内边距 16dp。
 *
 * ## 交互(对齐 LogFox)
 *
 * 1. **级别过滤** —— 按 `>= 阈值` 筛选,而不是子串匹配;
 * 2. **关键字 / tag 过滤** —— 两路独立,可组合;
 * 3. **跟随最新** —— 停在底部自动滚动,手动上滑则暂停跟随;
 * 4. **暂停采集** —— 冻结视图便于阅读正在变化的日志;
 * 5. **单条展开** —— 异常栈默认折叠;
 * 6. **复制 / 分享 / 清空** —— 作用于当前过滤结果。
 *
 * ## 边界
 *
 * 只展示**本编辑器进程**的日志([LogBuffer]),不读 logcat,也不展示用户 APK
 * 的运行日志 —— 后者属于调试控制台的职责(`console_logcat`)。
 */
@Composable
fun LogsScreen(
    onBack: () -> Unit,
    toast: NonBlockingToastState? = null
) {
    val context = LocalContext.current

    var minLevel by remember { mutableStateOf(LogBuffer.Level.VERBOSE) }
    var tagFilter by remember { mutableStateOf("") }
    var keyword by remember { mutableStateOf("") }
    var paused by remember { mutableStateOf(false) }
    var followTail by remember { mutableStateOf(true) }
    var showFilters by remember { mutableStateOf(false) }

    // 每次缓冲变化递增,作为重组触发器。
    //
    // 这里刻意不在回调里读 compose 状态(`paused`):观察者会在稍后的主线程
    // 消息里执行,闭包捕获到的 `paused` 可能是旧值,导致"暂停后仍持续刷新"
    // 或"恢复后不再刷新"。暂停与否改由下面的 LaunchedEffect 统一处理。
    var revision by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val unregister = LogBuffer.observe { revision++ }
        onDispose { unregister() }
    }

    // 首次进入时若缓冲为空,从磁盘回填历史。
    // 直接进入日志页(未经过 SplashWelcome 的 install)也要能看到内容。
    LaunchedEffect(Unit) {
        if (LogBuffer.size() == 0) {
            LogBuffer.backfillFromFile()
            revision++
        }
    }

    /**
     * 暂停 = 冻结视图内容。
     *
     * 用「冻结时刻的 revision」参与 remember key:暂停期间 [revision] 仍在
     * 变化(缓冲继续接收,恢复时立刻可见),但显示用的 key 不动,列表因此静止。
     * 这与"暂停采集"不同 —— 采集永不停止,否则解冻后就丢了这段时间的日志。
     */
    var frozenRevision by remember { mutableStateOf(0) }
    LaunchedEffect(paused) {
        if (paused) frozenRevision = revision
    }
    val displayRevision = if (paused) frozenRevision else revision

    val entries = remember(displayRevision, minLevel, tagFilter, keyword) {
        LogBuffer.query(minLevel = minLevel, tagFilter = tagFilter, query = keyword)
    }
    val levelCounts = remember(displayRevision) { LogBuffer.countByLevel() }
    val listState = rememberLazyListState()

    // 跟随最新:有新条目且处于跟随态时滚到底部
    LaunchedEffect(entries.size, followTail) {
        if (followTail && entries.isNotEmpty()) {
            listState.scrollToItem(entries.size - 1)
        }
    }
    // 用户上滑离开底部即停止跟随,回到底部则恢复。
    // 一直无条件滚动会让用户无法向上翻阅。
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }
            .collect { canScrollForward -> followTail = !canScrollForward }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---------- 工具栏:与设置页的卡片区同宽同留白 ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.logs_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${entries.size} / ${LogBuffer.size()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { paused = !paused }) {
                    Icon(
                        if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (paused) stringResource(R.string.logs_resume)
                        else stringResource(R.string.logs_pause),
                        tint = if (paused) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { followTail = true }) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.logs_jump_latest),
                        tint = if (followTail) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showFilters = !showFilters }) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = stringResource(R.string.logs_filter),
                        tint = if (showFilters) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (showFilters) {
                FiltersBar(
                    minLevel = minLevel,
                    onMinLevelChange = { minLevel = it },
                    tagFilter = tagFilter,
                    onTagChange = { tagFilter = it },
                    keyword = keyword,
                    onKeywordChange = { keyword = it },
                    levelCounts = levelCounts,
                    onCopy = {
                        copyToClipboard(context, LogBuffer.exportText(minLevel))
                        toast?.showToast(context.getString(R.string.logs_copied))
                    },
                    onShare = {
                        shareText(context, LogBuffer.exportText(minLevel))
                    },
                    onClear = {
                        LogBuffer.clear()
                        LogFileAccess.clear()
                        revision++
                        toast?.showToast(context.getString(R.string.logs_cleared))
                    }
                )
            }

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // ---------- 日志列表 ----------
            if (entries.isEmpty()) {
                EmptyLogs(
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(items = entries, key = { it.id }) { entry ->
                        LogRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun FiltersBar(
    minLevel: LogBuffer.Level,
    onMinLevelChange: (LogBuffer.Level) -> Unit,
    tagFilter: String,
    onTagChange: (String) -> Unit,
    keyword: String,
    onKeywordChange: (String) -> Unit,
    levelCounts: Map<LogBuffer.Level, Int>,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LogBuffer.Level.entries.forEach { level ->
                FilterChip(
                    selected = minLevel == level,
                    onClick = { onMinLevelChange(level) },
                    label = {
                        Text("${level.name} ${levelCounts[level] ?: 0}", fontSize = 11.sp)
                    },
                    leadingIcon = {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(levelColor(level), RoundedCornerShape(50))
                        )
                    }
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = keyword,
                onValueChange = onKeywordChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.logs_search_hint), fontSize = 13.sp) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = tagFilter,
                onValueChange = onTagChange,
                modifier = Modifier.width(120.dp),
                placeholder = { Text(stringResource(R.string.logs_tag_hint), fontSize = 13.sp) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AssistChip(
                onClick = onCopy,
                label = { Text(stringResource(R.string.logs_copy), fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp)) },
                colors = AssistChipDefaults.assistChipColors()
            )
            AssistChip(
                onClick = onShare,
                label = { Text(stringResource(R.string.logs_share), fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Filled.Share, null, Modifier.size(16.dp)) },
                colors = AssistChipDefaults.assistChipColors()
            )
            AssistChip(
                onClick = onClear,
                label = { Text(stringResource(R.string.logs_clear), fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Filled.ClearAll, null, Modifier.size(16.dp)) },
                colors = AssistChipDefaults.assistChipColors()
            )
        }
    }
}

@Composable
private fun LogRow(entry: LogBuffer.Entry) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val hasStack = !entry.throwable.isNullOrBlank()
    val color = levelColor(entry.level)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.level == LogBuffer.Level.ERROR) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                // 级别竖条:比整行染色更克制,长列表滚动时也不刺眼
                Box(
                    Modifier
                        .width(3.dp)
                        .height(16.dp)
                        .background(color, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.timeText,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.tag,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(96.dp)
                )
                Spacer(Modifier.width(8.dp))
                SelectionContainer {
                    Text(
                        text = entry.message,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (hasStack) {
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text(
                        if (expanded) stringResource(R.string.logs_collapse_stack)
                        else stringResource(R.string.logs_expand_stack),
                        fontSize = 11.sp
                    )
                }
                if (expanded) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SelectionContainer {
                            Text(
                                text = entry.throwable ?: "",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLogs(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Android,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.logs_empty_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.logs_empty_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** 级别配色:错误红、警告琥珀、信息蓝、调试紫、详细灰。 */
private fun levelColor(level: LogBuffer.Level): Color = when (level) {
    LogBuffer.Level.ERROR -> Color(0xFFE5484D)
    LogBuffer.Level.WARN -> Color(0xFFF5A623)
    LogBuffer.Level.INFO -> Color(0xFF3B82F6)
    LogBuffer.Level.DEBUG -> Color(0xFF8B5CF6)
    LogBuffer.Level.VERBOSE -> Color(0xFF6B7280)
}

private fun copyToClipboard(context: Context, text: String) {
    try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("LuaForge-Studio logs", text))
    } catch (_: Throwable) {
    }
}

private fun shareText(context: Context, text: String) {
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.logs_share_title))
        )
    } catch (_: Throwable) {
    }
}
