package com.luaforge.studio.console.ui

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.luaforge.studio.R

/**
 * 控制台面板(竖屏):BottomSheet 承载 [ConsolePanelView](头部 + 左侧竖排导航 + 右侧内容)。
 *
 * 面板不占满全屏:内容区固定高度,四周留出宿主界面。外观与横屏 [SidePanelDialog] 共用
 * [ConsolePanelView]/[ConsoleChrome],两形态视觉与页签行为一致。
 */
class ConsoleSheet(
    activity: Activity,
    private val onFullyClosed: () -> Unit,
    private val onDismissed: () -> Unit
) : BottomSheetDialog(activity) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = context

        // 内容区高度:屏高约 46%,钳制 260–420dp —— 体积小、不铺满;各页签等高,切换不跳动
        val panelHeight = (ctx.resources.displayMetrics.heightPixels * 0.46f).toInt()
            .coerceIn(ctx.dp(260), ctx.dp(420))

        val panel = ConsolePanelView(
            ctx,
            listOf(
                // 最小化:收起面板回浮球
                R.drawable.ic_console_minimize to { dismiss() },
                // 完全关闭(叉号):直接关闭,无二次确认
                R.drawable.ic_console_close to { onFullyClosed() }
            )
        ).apply {
            // 四角圆角:面板呈悬浮卡片,不再贴边铺满;裁剪子 view 矩形底色以保留圆角
            background = GradientDrawable().apply {
                setColor(ConsoleTheme.surface)
                cornerRadius = ctx.dp(20).toFloat()
            }
            clipToOutline = true
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // 底部留白:内容与手势区之间留呼吸空间
            setPadding(0, 0, 0, ctx.dp(10))
        }
        root.addView(panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, panelHeight))
        setContentView(root)

        // 宽度收到屏宽 92% 并居中:面板成悬浮卡片,左右露出宿主界面(不再铺满全屏)
        // 去掉窗口遮罩:悬浮卡片背后的黑色半透明背景消失,宿主界面保持原亮度
        window?.setDimAmount(0f)
        window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        getBehavior()?.setMaxWidth((ctx.resources.displayMetrics.widthPixels * 0.92f).toInt())
        // 底部留间距 + 去掉 Material 默认 sheet 背景(不透明、仅上圆角),
        // 否则会盖住 root 的四角圆角卡片外观。
        runCatching {
            findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                sheet.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                (sheet.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { lp ->
                    lp.bottomMargin = ctx.dp(12)
                    sheet.layoutParams = lp
                }
            }
        }

        // 禁用 sheet 拖拽手势:页签内滚动(如环境页 ScrollView)与 BottomSheet 下拉关闭冲突,
        // 误触下划会错误收起浮窗;关闭仅通过头部最小化/完全关闭按钮。
        getBehavior().setDraggable(false)

        // 恢复上次页签:面板关闭/重开后保留切换状态(退出调试时由 onSessionEnd 重置为 0)
        panel.restore()

        // 旋转关闭:BottomSheetDialog 无配置回调,用 DisplayListener 监听;本面板仅竖屏创建,旋到横屏即收。
        val displayManager = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val orientationCloser = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                val rotation = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
                    ?: return
                if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) dismiss()
            }
        }
        displayManager.registerDisplayListener(orientationCloser, Handler(Looper.getMainLooper()))
        setOnDismissListener {
            displayManager.unregisterDisplayListener(orientationCloser)
            onDismissed()
        }
    }
}
