package com.luaforge.studio.ui.components

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.luaforge.studio.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MarkdownDialog(
    mdFileName: String,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current

    // 状态：Markdown内容
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // 检查文件是否存在
    LaunchedEffect(mdFileName) {
        isLoading = true
        error = null

        try {
            withContext(Dispatchers.IO) {
                // 检查assets中是否存在该文件
                val filePath = "doc/$mdFileName"
                try {
                    context.assets.open(filePath).use {
                        // 文件存在，继续
                    }
                } catch (_: Exception) {
                    error = context.getString(R.string.error_doc_not_found, mdFileName)
                }
            }
        } catch (e: Exception) {
            error = context.getString(R.string.error_check_failed, e.message)
            e.printStackTrace()
        } finally {
            isLoading = false
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.85f)
                .shadow(0.dp, MaterialTheme.shapes.extraLarge),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                when {
                    isLoading -> {
                        // 加载中
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = stringResource(R.string.loading_doc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 16.dp)
                                )
                            }
                        }
                    }

                    error != null -> {
                        // 错误状态
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Error,
                                    contentDescription = stringResource(R.string.error),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(64.dp)
                                )
                                Text(
                                    text = error ?: stringResource(R.string.unknown_error),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 16.dp)
                                )
                                Text(
                                    text = stringResource(R.string.file_label, mdFileName),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                    }

                    else -> {
                        // 显示MarkdownView
                        MarkdownViewContent(
                            mdFileName = mdFileName,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownViewContent(
    mdFileName: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 预览页的 markdown/hljs 主题是硬编码浅色的上游产物,必须把当前
    // MaterialTheme 的配色喂进去,否则深色模式下会是一张白色文档盖在
    // 深色对话框上。这里直接取 colorScheme,连 Android 12 动态取色也能跟随。
    val colorScheme = MaterialTheme.colorScheme
    val palette = remember(colorScheme) {
        MarkdownView.DocPalette(
            background = colorScheme.surfaceContainer.toArgb(),
            foreground = colorScheme.onSurface.toArgb(),
            muted = colorScheme.onSurfaceVariant.toArgb(),
            border = colorScheme.outlineVariant.toArgb(),
            hr = colorScheme.outlineVariant.copy(alpha = 0.6f).toArgb(),
            inlineCodeBg = colorScheme.secondaryContainer.toArgb(),
            inlineCodeFg = colorScheme.onSecondaryContainer.toArgb(),
            preBorder = colorScheme.outlineVariant.toArgb(),
            tableHeadBorder = colorScheme.outline.toArgb()
        )
    }
    val isDark = colorScheme.surface.luminance() < 0.5f

    AndroidView(
        factory = { ctx -> createMarkdownView(ctx, mdFileName, isDark, palette) },
        modifier = modifier,
        update = { markdownView ->
            // 主题或文件名变化时都要同步:主题切换后 WebView 不会自动重绘
            markdownView.applyTheme(isDark, palette)
            markdownView.loadFromAssets("doc/$mdFileName")
        }
    )
}

@SuppressLint("SetJavaScriptEnabled")
private fun createMarkdownView(
    context: android.content.Context,
    mdFileName: String,
    isDark: Boolean,
    palette: MarkdownView.DocPalette
): MarkdownView {
    return MarkdownView(context).apply {
        // 设置布局参数
        layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )

        // 设置点击链接在外部浏览器中打开
        isOpenUrlInBrowser = true

        // 透明背景:底色由页面自身的 CSS 变量控制,与对话框容器色一致
        setBackgroundColor(0x00000000)

        // 启用硬件加速
        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

        // 先给主题,再加载内容;onPageFinished 里还会补一次,避免竞态
        applyTheme(isDark, palette)

        // 加载Markdown文件
        try {
            loadFromAssets("doc/$mdFileName")
        } catch (e: Exception) {
            println("MarkdownView加载失败: ${e.message}")
            e.printStackTrace()
        }
    }
}