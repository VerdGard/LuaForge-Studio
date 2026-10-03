package com.luaforge.studio.console.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
        // 窗口背景显式置透明:卡片之外不应有任何底色(主题已设,此处兜底防被系统主题改写)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // 底部留间距 + 清除 Material 给 sheet 容器装的底色与投影。
        // 关键:仅在此处清一次不够 —— BottomSheetBehavior 会在首次 layout 时执行
        // view.setBackground(materialShapeDrawable)(填充 colorSurfaceContainerLow),
        // 把这里的透明覆盖掉,暗色主题下表现为卡片背后一块浅色矩形。故再挂一次性
        // layout 监听,在布局完成后再清一次(清完即摘监听,不产生持续回调)。
        // 不再包 runCatching:取不到容器属异常,静默失败会让矩形底色悄悄露出。
        findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            fun clearSheetChrome(v: View) {
                v.background = null
                v.elevation = 0f
            }
            clearSheetChrome(sheet)
            (sheet.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                // 12dp 悬浮边距 + 原 root 层承担的 10dp 呼吸空间(去中间层后并入此处,视觉不变)
                lp.bottomMargin = ctx.dp(22)
                sheet.layoutParams = lp
            }
            val clearOnce = object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                ) {
                    clearSheetChrome(v)
                    v.removeOnLayoutChangeListener(this)
                }
            }
            sheet.addOnLayoutChangeListener(clearOnce)
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
