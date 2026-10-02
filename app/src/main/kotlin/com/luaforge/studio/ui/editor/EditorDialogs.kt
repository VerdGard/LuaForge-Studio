package com.luaforge.studio.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.luaforge.studio.build.BuildType
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.luaforge.studio.R

@Composable
fun InstallApkDialog(
    showInstallDialog: Boolean,
    apkFilePath: String?,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (showInstallDialog) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.code_editor_build_success)) },
            text = {
                Column {
                    Text(stringResource(R.string.code_editor_apk_built), style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = apkFilePath ?: stringResource(R.string.editor_unknown_path),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.code_editor_install_prompt),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onInstall) {
                    Text(stringResource(R.string.code_editor_install), color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * 构建类型选择对话框。
 *
 * "未加密版"决定是否加密 Lua 源码,"Debug / Release 版"决定打包内的
 * `debugmode`(运行时调试模式的来源)。二者相互独立,因此这里一次性选择。
 */
@Composable
fun BuildTypeDialog(
    showBuildTypeDialog: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (BuildType) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!showBuildTypeDialog) return

    // 默认跟随项目属性中的加密/调试设置
    var selected by remember { mutableStateOf(BuildType.PROJECT_DEFAULT) }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.code_editor_build_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.code_editor_build_type),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                BuildType.selectable.forEach { type ->
                    BuildTypeOption(
                        type = type,
                        selected = selected == type,
                        onSelect = { selected = type }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text(stringResource(R.string.code_editor_build_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/** 构建类型单选项。 */
@Composable
private fun BuildTypeOption(
    type: BuildType,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        },
        border = if (selected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            RadioButton(selected = selected, onClick = onSelect)

            Icon(
                imageVector = buildTypeIcon(type),
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp)
            )

            Column(
                modifier = Modifier.padding(start = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = buildTypeLabel(type),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = buildTypeDescription(type),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 构建类型标题。 */
@Composable
private fun buildTypeLabel(type: BuildType): String = when (type) {
    BuildType.UNENCRYPTED -> stringResource(R.string.code_editor_build_type_unencrypted)
    BuildType.DEBUG -> stringResource(R.string.code_editor_build_type_debug)
    BuildType.RELEASE -> stringResource(R.string.code_editor_build_type_release)
    BuildType.PROJECT_DEFAULT -> stringResource(R.string.code_editor_build_type)
}

/** 构建类型说明。 */
@Composable
private fun buildTypeDescription(type: BuildType): String = when (type) {
    BuildType.UNENCRYPTED -> stringResource(R.string.code_editor_build_type_unencrypted_desc)
    BuildType.DEBUG -> stringResource(R.string.code_editor_build_type_debug_desc)
    BuildType.RELEASE -> stringResource(R.string.code_editor_build_type_release_desc)
    BuildType.PROJECT_DEFAULT -> stringResource(R.string.code_editor_build_type_default_desc)
}

/** 构建类型图标。 */
private fun buildTypeIcon(type: BuildType): ImageVector = when (type) {
    BuildType.UNENCRYPTED -> Icons.Filled.Code
    BuildType.DEBUG -> Icons.Filled.Build
    BuildType.RELEASE -> Icons.Filled.CheckCircle
    BuildType.PROJECT_DEFAULT -> Icons.Filled.Build
}
