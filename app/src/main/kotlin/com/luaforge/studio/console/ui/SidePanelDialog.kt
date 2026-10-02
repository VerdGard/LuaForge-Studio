package com.luaforge.studio.console.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.ViewGroup
import com.luaforge.studio.R

/**
 * 平板/宽屏横屏专用右侧调试面板(右滑出全高侧栏)。
 * 内容为 [ConsolePanelView](头部 + 左侧竖排导航 + 右侧内容),与竖屏 [ConsoleSheet] 共用,
 * 两形态视觉与页签行为一致。
 */
class SidePanelDialog(
    activity: Activity,
    private val onFullyClosed: () -> Unit,
    private val onDismissed: () -> Unit,
    private val targetWidthPx: Int
) : Dialog(activity) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = context
        val r = ConsoleTheme.cornerRadiusPx

        val panel = ConsolePanelView(
            ctx,
            listOf(
                R.drawable.ic_console_minimize to { dismiss() },
                R.drawable.ic_console_close to { onFullyClosed() }
            )
        ).apply {
            // 侧栏贴右缘:圆角仅在邻近屏幕内容一侧(左上/左下),右上/右下贴边为直角
            background = GradientDrawable().apply {
                setColor(ConsoleTheme.surface)
                cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            }
            // child(头部/导航/内容)为矩形全宽背景,会盖掉圆角。按背景轮廓裁剪子 view。
            clipToOutline = true
        }
        setContentView(panel)

        // 右侧定位、固定宽度、全高、划入动画
        window?.setLayout(targetWidthPx, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.setGravity(Gravity.RIGHT or Gravity.TOP)
        window?.setWindowAnimations(R.style.SidePanelDialogAnim)
        window?.setDimAmount(0.3f)
        // 普通 Dialog 主题默认背景带圆角+描边+系统 inset 会在右/上/下留缝,
        // 改为透明背景、不避让系统窗口(全高贴缘),surface+圆角完全交给面板自绘。
        window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        window?.decorView?.setPadding(0, 0, 0, 0)
        @SuppressLint("ObsoleteSdkInt")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window?.setDecorFitsSystemWindows(false)
        }

        // 恢复上次页签(与竖屏共享 persistedTab;退出调试时由 onSessionEnd 重置为 0)
        panel.restore()

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
}
