package com.luaforge.studio.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 电视/遥控器(D-pad)场景的焦点高亮修饰符。
 *
 * 触摸屏设备上焦点边框不会出现(除非通过键盘/遥控器聚焦),因此可无条件挂载,
 * 仅在获得焦点时绘制高亮描边,便于用户看清当前选中项。
 */
fun Modifier.tvFocusHighlight(
    color: Color,
    cornerRadius: Int = 12
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.isFocused }
        .then(
            if (focused) {
                Modifier.border(
                    width = 2.dp,
                    color = color,
                    shape = RoundedCornerShape(cornerRadius.dp)
                )
            } else {
                Modifier
            }
        )
}

/** 一个供 TV 焦点/点击复用的稳定 InteractionSource。 */
@Composable
fun rememberTvInteractionSource(): MutableInteractionSource = remember { MutableInteractionSource() }
