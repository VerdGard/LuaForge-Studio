package com.luaforge.studio.console.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
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
            // 面板即悬浮卡片:直接作为 sheet 内容,高度由自身 LayoutParams 决定
            // (原先外套一层 LinearLayout 只为垫底部留白,留白已由 sheet 的 bottomMargin 承担)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, panelHeight)
        }
        setContentView(panel)

        // 宽度收到屏宽 92% 并居中:面板成悬浮卡片,左右露出宿主界面(不再铺满全屏)
        // 去掉窗口遮罩:悬浮卡片背后的黑色半透明背景消失,宿主界面保持原亮度
        window?.setDimAmount(0f)
        window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        getBehavior()?.setMaxWidth((ctx.resources.displayMetrics.widthPixels * 0.92f).toInt())
        // 底部留间距 + 去掉 Material 默认 sheet 容器底色(不透明、仅上圆角),
        // 否则会盖住面板的四角圆角卡片外观。此处不再包 runCatching:取不到容器属异常,
        // 静默失败会让整块矩形底色悄悄露出来(排查困难),应显式暴露。
        findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            sheet.setBackgroundColor(Color.TRANSPARENT)
            (sheet.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                // 12dp 悬浮边距 + 原 root 层承担的 10dp 呼吸空间(去中间层后并入此处,视觉不变)
                lp.bottomMargin = ctx.dp(22)
                sheet.layoutParams = lp
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
