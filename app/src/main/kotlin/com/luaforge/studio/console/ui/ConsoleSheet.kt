package com.luaforge.studio.console.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.luaforge.studio.R
import com.luaforge.studio.console.core.FileStateTracker
import com.luaforge.studio.console.ui.tabs.DebugTabView
import com.luaforge.studio.console.ui.tabs.EnvTabView
import com.luaforge.studio.console.ui.tabs.LogcatTabView
import com.luaforge.studio.console.ui.tabs.OutputTabView
import com.luaforge.studio.console.ui.tabs.SettingsTabView
import com.luaforge.studio.console.ui.tabs.StructTabView

/**
 * 控制台面板(竖屏):BottomSheet + 页签(输出/结构/环境/Logcat/调试/设置)+ 关闭/最小化。
 * 面板不占满全屏:内容区固定高度,四周留出宿主界面。
 * 外观与横屏 [SidePanelDialog] 共用 [ConsoleChrome],两形态视觉一致。
 */
class ConsoleSheet(
    activity: Activity,
    private val onFullyClosed: () -> Unit,
    private val onDismissed: () -> Unit
) : BottomSheetDialog(activity) {

    companion object {
        /** 上次选中的页签位:面板关闭/重开后保留,退出本次调试(onSessionEnd)时重置为 0。 */
        @Volatile
        var persistedTab = 0
    }

    private val cachedTabs = HashMap<Int, View>()
    private var lastShownPos = -1
    private val container by lazy { FrameLayout(context).apply { id = View.generateViewId() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // 底部留白:内容与系统手势区之间留出呼吸空间
            setPadding(0, 0, 0, ctx.dp(16))
            setBackgroundColor(ConsoleTheme.surface)
        }

        val header = ConsoleChrome.header(
            ctx = ctx,
            title = "调试控制台",
            subtitle = currentFileLabel(),
            actions = listOf(
                // 最小化:收起面板回浮球
                R.drawable.ic_console_minimize to { dismiss() },
                // 完全关闭(叉号):直接关闭,无二次确认
                R.drawable.ic_console_close to { onFullyClosed() }
            )
        )
        val tabs = ConsoleChrome.tabs(ctx, ConsoleChrome.TAB_TITLES)

        // 内容区固定高度:面板非全屏,且各页签高度一致,切换不跳动
        container.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            ctx.dp(460)
        )

        root.addView(header)
        root.addView(tabs)
        root.addView(
            ConsoleChrome.divider(ctx),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(1))
        )
        root.addView(container)
        setContentView(root)

        // Material ≥1.14 对宽屏/横屏 BottomSheet 施加 android:maxWidth(默认 640dp),
        // 使浮窗呈居中窄条、左右留边无法覆盖。放开为容器宽,控制台即全宽铺满;maxWidth 单位为 px。
        getBehavior()?.setMaxWidth(ctx.resources.displayMetrics.widthPixels)

        // 禁用 sheet 拖拽手势:页签内滚动(如环境页 ScrollView)与 BottomSheet 下拉关闭冲突,
        // 误触下划会错误收起浮窗;关闭仅通过头部最小化/完全关闭按钮。
        getBehavior().setDraggable(false)

        tabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                persistedTab = tab.position
                showTab(tab.position)
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })
        // 恢复上次页签:面板关闭/重开后保留切换状态(退出调试时由 onSessionEnd 重置为 0)
        val restore = persistedTab.coerceIn(0, tabs.tabCount - 1)
        tabs.getTabAt(restore)?.select()
        if (lastShownPos != restore) showTab(restore)

        // 旋转关闭:BottomSheetDialog 无配置回调,用 DisplayListener 监听;本面板仅竖屏创建,旋到横屏即收。
        val displayManager = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val orientationCloser = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                val rotation = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
                    ?: return
                val nowLandscape =
                    rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
                if (nowLandscape) dismiss()
            }
        }
        displayManager.registerDisplayListener(orientationCloser, Handler(Looper.getMainLooper()))
        setOnDismissListener {
            displayManager.unregisterDisplayListener(orientationCloser)
            onDismissed()
        }
    }

    /** 副标题:当前文件项目相对路径(未知则空,头部只显标题)。 */
    private fun currentFileLabel(): String? {
        val p = FileStateTracker.relativePath
        return p.ifBlank { null }
    }

    @SuppressLint("Recycle")
    private fun showTab(pos: Int) {
        (cachedTabs[lastShownPos] as? LogcatTabView)?.stopPolling()
        lastShownPos = pos
        val view = cachedTabs.getOrPut(pos) { buildTab(pos) }
        container.removeAllViews()
        container.addView(
            view,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        (view as? StructTabView)?.refresh()
        (view as? EnvTabView)?.refresh()
        (view as? DebugTabView)?.refresh()
        (view as? SettingsTabView)?.refresh()
        (view as? LogcatTabView)?.apply {
            refresh()
            startPolling()
        }
    }

    private fun buildTab(pos: Int): View = when (pos) {
        0 -> OutputTabView(context)
        1 -> StructTabView(context)
        2 -> EnvTabView(context)
        3 -> LogcatTabView(context)
        4 -> DebugTabView(context)
        else -> SettingsTabView(context)
    }
}
