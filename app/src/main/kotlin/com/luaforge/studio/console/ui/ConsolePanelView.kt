package com.luaforge.studio.console.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.luaforge.studio.console.core.FileStateTracker
import com.luaforge.studio.console.output.OutputManager
import com.luaforge.studio.console.ui.tabs.DebugTabView
import com.luaforge.studio.console.ui.tabs.EnvTabView
import com.luaforge.studio.console.ui.tabs.LogcatTabView
import com.luaforge.studio.console.ui.tabs.OutputTabView
import com.luaforge.studio.console.ui.tabs.SettingsTabView
import com.luaforge.studio.console.ui.tabs.StructTabView

/**
 * 控制台面板的公共内容:头部(标题 + 图标按钮) + 当前文件行 + 左侧竖排导航 + 右侧内容区。
 *
 * 竖屏 [ConsoleSheet](BottomSheet)与横屏 [SidePanelDialog](右侧栏)共用本视图,
 * 两形态布局与页签生命周期完全一致,不再各自维护一份 cachedTabs/showTab/buildTab。
 * 导航与内容左右分栏:页签从顶部挪到左列,纵向空间全部让给内容。
 */
class ConsolePanelView(
    context: Context,
    actions: List<Pair<Int, () -> Unit>>
) : LinearLayout(context) {

    private val contentHost = FrameLayout(context)
    private val cachedTabs = HashMap<Int, View>()
    private var lastShownPos = -1
    private val fileView = TextView(context)

    private val nav = ConsoleNavColumn(context, TITLES) { showTab(it) }

    init {
        orientation = VERTICAL
        // 底色由外层(竖屏 sheet / 横屏侧栏)的圆角 GradientDrawable 统一提供,
        // 这里不再铺一层不透明色,避免「卡片 + 中间层」两层底色叠出硬边。

        addView(
            ConsoleChrome.header(
                ctx = context,
                title = "调试控制台",
                actions = actions
            )
        )

        // 分隔线:头部与「当前文件」行之间
        addView(
            ConsoleChrome.divider(context),
            LayoutParams(LayoutParams.MATCH_PARENT, context.dp(1))
        )

        // 当前文件:原在面板副标题/输出页顶部,现统一置于标题下方单行展示
        fileView.apply {
            textSize = 12f
            setTextColor(ConsoleTheme.onSurfaceVariant)
            setPadding(context.dp(14), context.dp(5), context.dp(14), context.dp(5))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        addView(fileView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // 分隔线:「当前文件」行与「导航+内容」之间
        addView(
            ConsoleChrome.divider(context),
            LayoutParams(LayoutParams.MATCH_PARENT, context.dp(1))
        )

        refreshCurrentFile()

        val body = LinearLayout(context).apply { orientation = HORIZONTAL }
        body.addView(nav, LayoutParams(context.dp(ConsoleNavColumn.WIDTH_DP), LayoutParams.MATCH_PARENT))
        // 导航与内容之间的竖向分隔线
        body.addView(
            View(context).apply { setBackgroundColor(dividerColor(context)) },
            LayoutParams(context.dp(1), LayoutParams.MATCH_PARENT)
        )
        body.addView(contentHost, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** 刷新「当前文件」行:相对路径优先,缺失回退绝对路径,再缺失显示 (无)。 */
    private fun refreshCurrentFile() {
        val rel = FileStateTracker.relativePath
        val shown = rel.ifBlank { OutputManager.currentFile }
        fileView.text = "当前文件：" + shown.ifBlank { "(无)" }
    }

    /**
     * 面板离屏(对话框 dismiss / 宿主销毁)时释放页签持有的后台资源。
     *
     * 对话框每次打开都会新建本视图,故此处不可复用:清空缓存,并让 Logcat 页退出
     * 其后台 HandlerThread(否则每开一次面板泄漏一条常驻线程)。
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cachedTabs.values.forEach { (it as? LogcatTabView)?.release() }
        cachedTabs.clear()
        lastShownPos = -1
    }

    /** 恢复上次页签(面板关闭/重开后保留;退出调试时由 onSessionEnd 重置)。 */
    fun restore() {
        val pos = persistedTab.coerceIn(0, TITLES.size - 1)
        if (pos == nav.selectedIndex) return
        nav.select(pos)
    }

    @SuppressLint("Recycle") // 缓存视图重复 addView,Recycler 唤醒由各 TabView 内部自管
    private fun showTab(pos: Int) {
        if (pos !in TITLES.indices) return
        persistedTab = pos
        refreshCurrentFile()
        (cachedTabs[lastShownPos] as? LogcatTabView)?.stopPolling()
        val changed = lastShownPos != pos
        lastShownPos = pos
        val view = cachedTabs.getOrPut(pos) { buildTab(pos) }
        if (changed || contentHost.childCount == 0) {
            contentHost.removeAllViews()
            contentHost.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        when (view) {
            is StructTabView -> view.refresh()
            is EnvTabView -> view.refresh()
            is DebugTabView -> view.refresh()
            is SettingsTabView -> view.refresh()
            is LogcatTabView -> {
                view.refresh()
                view.startPolling()
            }
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

    companion object {
        /** 页签标题:输出 / 结构 / 环境 / Logcat / 调试 / 设置。顺序与 buildTab 索引一一对应。 */
        val TITLES = listOf("输出", "结构", "环境", "Logcat", "调试", "设置")

        /** 上次选中的页签位:面板关闭/重开后保留,退出本次调试(onSessionEnd)时重置为 0。 */
        @Volatile
        var persistedTab = 0

        private fun dividerColor(context: Context): Int {
            val c = ConsoleTheme.onSurface
            return Color.argb(0x1A, Color.red(c), Color.green(c), Color.blue(c))
        }
    }
}
