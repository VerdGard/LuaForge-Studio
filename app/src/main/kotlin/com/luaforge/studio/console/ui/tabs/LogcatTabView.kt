package com.luaforge.studio.console.ui.tabs

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.luaforge.studio.console.logcat.LogcatManager
import com.luaforge.studio.console.ui.ConsoleTheme
import com.luaforge.studio.console.ui.dp

/**
 * Logcat 页:日志等级(V/D/I/W/E/F/S)改用 Tab 标签,选中即过滤;RecyclerView 虚拟化渲染。
 *
 * 手机适配:「日志输出」标题独占一行,等级 Tab 置于下一行并放进隐藏滑条的横向滚动容器,
 * 窄屏可横滑看全等级;日志区隐藏纵横滑条。日志文件分块懒加载读取移至后台线程,
 * 读到增量后主线程只 append 可见行,避免整表 notifyDataSetChanged 造成卡顿。
 */
class LogcatTabView(context: Context) : LinearLayout(context) {

    private val lines = ArrayList<String>()
    private val visible = ArrayList<String>()
    /** Android Log 等级按序 V D I W E F S,index 对应;true=显示(默认全开)。 */
    private val levelEnabled = BooleanArray(LEVELS.size) { true }

    private val tabLabels = ArrayList<TextView>(LEVELS.size)
    private val tabBars = ArrayList<View>(LEVELS.size)

    private val worker = HandlerThread("logcat-poll").apply { start() }
    private val workerHandler = Handler(worker.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var attached = false

    private val levelRegex = Regex("\\s+\\d+\\s+\\d+\\s+([VDIWEFS])\\s")

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!attached) return // 页已离屏:停止轮询
            val store = LogcatManager.store
            if (store == null) {
                mainHandler.post { updateStatus("(未记录)") }
            } else {
                val chunk = try {
                    store.readChunk(CHUNK_LINES)
                } catch (_: Exception) {
                    emptyList()
                }
                mainHandler.post {
                    if (attached) append(chunk)
                }
            }
            if (attached) workerHandler.postDelayed(this, POLL_MS)
        }
    }

    private val status = TextView(context)
    private val emptyHint = TextView(context)

    private val adapter = object : RecyclerView.Adapter<RowHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val tv = TextView(parent.context).apply {
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(ConsoleTheme.onSurfaceVariant)
                setLineSpacing(0f, 1.05f)
                isClickable = true
                // 行波纹:主题 onSurface 低透明 ripple,随主题色
                background = RippleDrawable(
                    ColorStateList.valueOf(
                        ConsoleTheme.onSurface and 0x00FFFFFF or 0x1A000000
                    ),
                    null,
                    null
                )
                setOnClickListener { onRowClick(it as TextView) }
            }
            return RowHolder(tv)
        }

        override fun getItemCount(): Int = visible.size

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            holder.textView.text = visible[position]
        }
    }

    private class RowHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    private val recycler = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        adapter = this@LogcatTabView.adapter
        // 隐藏纵向滑条:内容区更干净,仍可滚动
        isVerticalScrollBarEnabled = false
    }

    init {
        orientation = VERTICAL
        setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(6))
        setBackgroundColor(ConsoleTheme.surface)

        // 「日志输出」标题独占一行(原先与等级开关挤同一行,窄屏布局混乱)
        addView(
            TextView(context).apply {
                text = "日志输出"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ConsoleTheme.onSurfaceVariant)
                setPadding(0, 0, 0, context.dp(6))
            }
        )

        // 等级 Tab:V/D/I/W/E/F/S 横向排布,宽于容器时横滑;滑条隐藏
        val strip = LinearLayout(context).apply { orientation = HORIZONTAL }
        for (i in LEVELS.indices) {
            strip.addView(
                buildTab(i),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        val tabScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                strip,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        addView(tabScroll, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(TAB_HEIGHT_DP)))

        // Tab 区与日志区之间的 1dp 分界
        addView(
            View(context).apply { setBackgroundColor(dividerColor()) },
            LayoutParams(LayoutParams.MATCH_PARENT, context.dp(1))
        )

        status.apply {
            textSize = 11f
            setTextColor(ConsoleTheme.onSurfaceVariant)
            setPadding(0, context.dp(6), 0, context.dp(4))
        }
        addView(status)

        emptyHint.apply {
            text = "(无记录)"
            textSize = 13f
            setTextColor(ConsoleTheme.onSurfaceVariant)
            gravity = Gravity.CENTER
        }
        addView(emptyHint, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(120)))
        addView(recycler, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        updateStatus(null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
    }

    override fun onDetachedFromWindow() {
        attached = false
        workerHandler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    /** 单个等级 Tab:文字(上) + 2dp 指示条(下);点击切换该等级过滤。 */
    private fun buildTab(i: Int): LinearLayout {
        val label = TextView(context).apply {
            text = LEVELS[i].toString()
            textSize = 13f
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(context.dp(18), 0, context.dp(18), 0)
        }
        val bar = View(context)
        val tab = LinearLayout(context).apply {
            orientation = VERTICAL
            isClickable = true
            isFocusable = true
            // 波纹用 foreground:background 留空,避免 styleTab 改背景时冲掉波纹
            foreground = RippleDrawable(
                ColorStateList.valueOf(ConsoleTheme.primary and 0x00FFFFFF or 0x1F000000),
                null,
                null
            )
            setOnClickListener { onToggle(i) }
            addView(label, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(2)))
        }
        tabLabels.add(label)
        tabBars.add(bar)
        styleTab(i)
        return tab
    }

    /** 等级开关点击:翻转 + 重跑过滤。 */
    private fun onToggle(i: Int) {
        levelEnabled[i] = !levelEnabled[i]
        styleTab(i)
        rebuildAll()
        if (!recycler.canScrollVertically(1)) recycler.scrollToPosition(adapter.itemCount - 1)
    }

    /** Tab 外观:选中 primary 加粗 + 指示条显色;未选 onSurfaceVariant + 指示条透明。 */
    private fun styleTab(i: Int) {
        val on = levelEnabled[i]
        tabLabels[i].setTextColor(if (on) ConsoleTheme.primary else ConsoleTheme.onSurfaceVariant)
        tabLabels[i].typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        tabBars[i].setBackgroundColor(if (on) ConsoleTheme.primary else Color.TRANSPARENT)
    }

    /** 页签展示时刷新并启动轮询。 */
    fun refresh() {
        attached = true
        workerHandler.removeCallbacks(pollRunnable)
        workerHandler.post(pollRunnable)
    }

    fun startPolling() {
        workerHandler.removeCallbacks(pollRunnable)
        workerHandler.post(pollRunnable)
    }

    fun stopPolling() {
        workerHandler.removeCallbacks(pollRunnable)
    }

    /**
     * 释放本页:停止轮询并退出后台 HandlerThread。
     *
     * HandlerThread 在构造时启动且不会自行结束;面板每次打开都会新建本视图,
     * 若只 removeCallbacks 而不 quit,每开一次面板即泄漏一条常驻线程。
     * 本视图随面板一同丢弃,因此退出线程是安全的(不可再复用本实例)。
     */
    fun release() {
        attached = false
        workerHandler.removeCallbacksAndMessages(null)
        worker.quitSafely()
    }

    /** 主线程:追加后台读到的新块,仅插入可见行,避免整表刷新。 */
    private fun append(chunk: List<String>) {
        if (chunk.isEmpty()) {
            updateStatus(null)
            return
        }
        lines.addAll(chunk)
        if (lines.size > MAX_LINES) {
            // 超上限:整段重建(低频,仅超限点触发)
            // 一次性清掉头部超量:逐个 removeAt(0) 是 O(n2),日志突发时会卡住主线程
            lines.subList(0, lines.size - MAX_LINES).clear()
            rebuildAll()
        } else {
            val start = visible.size
            for (l in chunk) if (passes(l)) visible.add(l)
            if (visible.size > start) {
                adapter.notifyItemRangeInserted(start, visible.size - start)
            }
        }
        if (!recycler.canScrollVertically(1)) recycler.scrollToPosition(adapter.itemCount - 1)
        updateStatus(null)
    }

    /** 行点击仅触发波纹反馈,暂不承载具体动作。 */
    private fun onRowClick(row: TextView) = Unit

    /** 等级过滤变化:全量重建可见集。 */
    private fun rebuildAll() {
        visible.clear()
        for (l in lines) if (passes(l)) visible.add(l)
        adapter.notifyDataSetChanged()
        emptyHint.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 等级过滤:threadtime 格式 `time pid tid 级 标签: 消息`,取 pid/tid 后的单字母等级。 */
    private fun passes(line: String): Boolean {
        val m = levelRegex.find(line) ?: return true // 非标准行(如二进制流碎片)恒显示
        val idx = LEVELS.indexOf(m.groupValues[1][0])
        return idx < 0 || levelEnabled[idx]
    }

    private fun updateStatus(s: String?) {
        status.text = s ?: LogcatManager.store?.file()?.name ?: "(未记录)"
        emptyHint.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Tab 区/日志区的细分隔线颜色。 */
    private fun dividerColor(): Int =
        ConsoleTheme.onSurface and 0x00FFFFFF or 0x1A000000

    companion object {
        /** Android Log 优先级字母:V=Verbose, D=Debug, I=Info, W=Warn, E=Error, F=Fatal, S=Silent。 */
        private val LEVELS = charArrayOf('V', 'D', 'I', 'W', 'E', 'F', 'S')
        private const val POLL_MS = 1000L
        private const val CHUNK_LINES = 300
        private const val MAX_LINES = 5000
        /** Tab 行高度(dp):文字 + 2dp 指示条。 */
        private const val TAB_HEIGHT_DP = 40
    }
}
