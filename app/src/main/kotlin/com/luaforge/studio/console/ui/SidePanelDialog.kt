package com.luaforge.studio.console.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.luaforge.studio.R
import com.luaforge.studio.console.core.FileStateTracker
import com.luaforge.studio.console.ui.tabs.DebugTabView
import com.luaforge.studio.console.ui.tabs.EnvTabView
import com.luaforge.studio.console.ui.tabs.LogcatTabView
import com.luaforge.studio.console.ui.tabs.OutputTabView
import com.luaforge.studio.console.ui.tabs.SettingsTabView
import com.luaforge.studio.console.ui.tabs.StructTabView

/**
 * 平板/宽屏横屏专用右侧调试面板(右滑出全高侧栏)。
 * 外观与竖屏 [ConsoleSheet] 共用 [ConsoleChrome](头部 + 页签),两形态视觉一致。
 * persistedTab 与 ConsoleSheet 共享,退出调试时的重置逻辑对两者同时生效。
 */
class SidePanelDialog(
    activity: Activity,
    private val onFullyClosed: () -> Unit,
    private val onDismissed: () -> Unit,
    private val targetWidthPx: Int
) : Dialog(activity) {

    private val cachedTabs = HashMap<Int, View>()
    private var lastShownPos = -1
    private val container by lazy { FrameLayout(context).apply { id = View.generateViewId() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = context
        val r = ConsoleTheme.cornerRadiusPx
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // 侧栏贴右缘:圆角仅在邻近屏幕内容一侧(左上/左下),右上/右下贴边为直角
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ConsoleTheme.surface)
                cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            }
            // child(header/tabs/内容)为矩形全宽背景,会盖掉圆角。按背景轮廓裁剪子 view。
            clipToOutline = true
        }

        val header = ConsoleChrome.header(
            ctx = ctx,
            title = "调试控制台",
            subtitle = FileStateTracker.relativePath.ifBlank { null },
            actions = listOf(
                R.drawable.ic_console_minimize to { dismiss() },
                R.drawable.ic_console_close to { onFullyClosed() }
            )
        )
        val tabs = ConsoleChrome.tabs(ctx, ConsoleChrome.TAB_TITLES)

        // 容器撑满剩余竖向空间(全高侧栏)
        container.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        )

        root.addView(header)
        root.addView(tabs)
        root.addView(
            ConsoleChrome.divider(ctx),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(1))
        )
        root.addView(container)
        setContentView(root)

        // 右侧定位、固定宽度、全高、划入动画
        window?.setLayout(targetWidthPx, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.setGravity(Gravity.RIGHT or Gravity.TOP)
        window?.setWindowAnimations(R.style.SidePanelDialogAnim)
        window?.setDimAmount(0.3f)
        // 普通 Dialog 主题默认背景带圆角+描边+系统 inset 会在右/上/下留缝,
        // 改为透明背景、不避让系统窗口(全高贴缘),surface+圆角完全交给 root 自绘。
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        window?.decorView?.setPadding(0, 0, 0, 0)
        @SuppressLint("ObsoleteSdkInt")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window?.setDecorFitsSystemWindows(false)
        }

        tabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                ConsoleSheet.persistedTab = tab.position
                showTab(tab.position)
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })
        val restore = ConsoleSheet.persistedTab.coerceIn(0, tabs.tabCount - 1)
        tabs.getTabAt(restore)?.select()
        if (lastShownPos != restore) showTab(restore)

        // 旋转关闭:Dialog 无配置回调,用 DisplayListener 监听;本面板仅横屏创建,旋到竖屏即收。
        val displayManager = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val orientationCloser = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                val rotation = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
                    ?: return
                val nowLandscape =
                    rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
                if (!nowLandscape) dismiss()
            }
        }
        displayManager.registerDisplayListener(orientationCloser, Handler(Looper.getMainLooper()))
        setOnDismissListener {
            displayManager.unregisterDisplayListener(orientationCloser)
            onDismissed()
        }
    }

    @SuppressLint("Recycle") // 缓存视图重复 addView,Recycler 唤醒由各 TabView 内部自管
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
