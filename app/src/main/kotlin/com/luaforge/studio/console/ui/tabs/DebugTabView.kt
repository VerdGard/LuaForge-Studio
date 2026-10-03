package com.luaforge.studio.console.ui.tabs

import android.content.Context
import android.content.res.ColorStateList
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.button.MaterialButton
import com.luaforge.studio.console.core.SessionManager
import com.luaforge.studio.console.debug.FileLauncher
import com.luaforge.studio.console.ui.ConsoleTheme
import com.luaforge.studio.console.ui.dp

/**
 * F7 调试页:重启项目 / 重建当前文件。
 *
 * 两操作改 Material 3 按钮并排(原先为竖向堆叠的纯文本行),窄屏下更易点按;
 * 配色显式取自 [ConsoleTheme],不依赖宿主主题的 tonal/outlined 样式。
 */
class DebugTabView(context: Context) : ScrollView(context) {

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
        setBackgroundColor(ConsoleTheme.surface)
    }

    init {
        addView(
            content,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
    }

    /** 页签展示时重建按钮行。 */
    fun refresh() {
        content.removeAllViews()
        if (SessionManager.current == null) return
        val enabled = SessionManager.activity != null

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            md3Button("重启项目", enabled) { FileLauncher.restartProject() },
            LinearLayout.LayoutParams(0, context.dp(BUTTON_HEIGHT_DP), 1f)
        )
        row.addView(
            md3Button("重建当前文件", enabled) { FileLauncher.rebuildCurrentFile() },
            LinearLayout.LayoutParams(0, context.dp(BUTTON_HEIGHT_DP), 1f)
                .apply { marginStart = context.dp(8) }
        )
        content.addView(
            row,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
    }

    /** MD3 按钮:accentContainer 底 + primary 字 + 主题圆角;无宿主时 disabled 自然变灰。 */
    private fun md3Button(label: String, enabled: Boolean, onClick: () -> Unit): MaterialButton =
        MaterialButton(context).apply {
            text = label
            textSize = 14f
            setAllCaps(false) // MD3 按钮文字不转大写(不用 isAllCaps: 无 getAllCaps() getter)
            isEnabled = enabled
            backgroundTintList = ColorStateList.valueOf(ConsoleTheme.accentContainer)
            setTextColor(ConsoleTheme.primary)
            cornerRadius = ConsoleTheme.cornerRadiusPx.toInt()
            insetTop = 0
            insetBottom = 0
            stateListAnimator = null
            setOnClickListener { onClick() }
        }

    private companion object {
        /** 按钮高度(dp):两钮并排,略高于单行文本以适配触控。 */
        const val BUTTON_HEIGHT_DP = 46
    }
}
