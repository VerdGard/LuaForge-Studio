package com.luaforge.studio.console.ui.adapters

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.luaforge.studio.console.core.ConsoleSettings
import com.luaforge.studio.console.output.OutputEntry
import com.luaforge.studio.console.ui.ConsoleTheme
import com.luaforge.studio.console.ui.dp

/**
 * 输出条目适配器。
 *
 * 每条输出恒显三段：内容 / 元数据行（左=项目相对路径，右=真实类型 typeDetails）/ 标签药丸 chips（打印/Toast/Snackbar/错误/事件）
 * · 一级 lua 类型 · 线程，最右侧独占完整时间。
 *
 * 条目间以左右不碰壁的细分割线分隔。多选复选框位于整条输出左侧垂直居中，Material 样式跟随主题，
 * 选中/取消底色带过渡动画。点击条目有波纹反馈。
 */
class OutputAdapter(
    private val settings: ConsoleSettings,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<OutputAdapter.VH>() {

    private val items = ArrayList<OutputEntry>()
    private val selected = LinkedHashSet<Long>()

    var selectionMode: Boolean = false
        private set

    val selectionCount: Int get() = selected.size

    val selectedEntries: List<OutputEntry>
        get() = items.filter { it.id in selected }

    /** label 标签 → chip 文案映射。 */
    private fun labelText(label: String): String = when (label) {
        "print" -> "打印"
        "toast" -> "Toast"
        "snackbar" -> "Snackbar"
        "error" -> "错误"
        "event" -> "事件"
        else -> label
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<OutputEntry>) {
        // 列表以旧列表为前缀(仅追加)时只通知插入区间:此前每来一条输出都整表重绑
        val oldSize = items.size
        var common = 0
        val limit = minOf(oldSize, list.size)
        while (common < limit && items[common].id == list[common].id) common++

        items.clear()
        items.addAll(list)
        val live = HashSet<Long>(items.size)
        for (item in items) live.add(item.id)
        selected.retainAll(live)

        when {
            common != oldSize -> notifyDataSetChanged()
            list.size > oldSize -> notifyItemRangeInserted(oldSize, list.size - oldSize)
            else -> Unit // 前缀全同且数量未增:无需刷新
        }
    }

    fun setSelectionMode(on: Boolean) {
        if (selectionMode == on) return
        selectionMode = on
        if (!on) selected.clear()
        notifyDataSetChanged()
        onSelectionChanged()
    }

    /** 全选当前缓冲全部条目。 */
    fun selectAll() {
        selected.clear()
        selected.addAll(items.map { it.id })
        notifyDataSetChanged()
        onSelectionChanged()
    }

    /** 反选：已选取消，未选选中。 */
    fun invertSelection() {
        val ids = selected.toHashSet()
        selected.clear()
        selected.addAll(items.filter { it.id !in ids }.map { it.id })
        notifyDataSetChanged()
        onSelectionChanged()
    }

    /** 取消选择：清空已选，保持选择模式。 */
    fun clearSelection() {
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(12), ctx.dp(5), ctx.dp(12), ctx.dp(1))
        }
        val check = MaterialCheckBox(ctx).apply {
            visibility = View.GONE
            isClickable = false
            isFocusable = false
            buttonTintList = android.content.res.ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(ConsoleTheme.primary, ConsoleTheme.onSurfaceVariant)
            )
            scaleX = 1.1f
            scaleY = 1.1f
        }
        // 事件函数名药丸 chip（(onPause) 样式，同条目药丸：primary 淡底全圆角）
        val pill = TextView(ctx).apply {
            textSize = 10f
            visibility = View.GONE
            setTextColor(ConsoleTheme.primary)
            setPadding(ctx.dp(6), ctx.dp(1), ctx.dp(6), ctx.dp(1))
            background = GradientDrawable().apply {
                setColor((ConsoleTheme.primary and 0x00FFFFFF) or 0x14000000)
                cornerRadius = 999f // 药丸形
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = ctx.dp(6) }
        }
        val content = TextView(ctx).apply {
            textSize = 14f
            setTextColor(ConsoleTheme.onSurface)
        }
        val contentHost = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        contentHost.addView(pill)
        contentHost.addView(content, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // 元数据行（原「真实类型+相对文件」合并单行）：左 = 项目相对路径，右 = 真实类型
        val metaRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val leftPath = TextView(ctx).apply {
            textSize = 11f
            setTextColor(ConsoleTheme.onSurfaceVariant)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        val typeRight = TextView(ctx).apply {
            textSize = 11f
            setTextColor(ConsoleTheme.onSurfaceVariant)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.END
        }
        leftPath.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        typeRight.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        metaRow.addView(leftPath)
        metaRow.addView(typeRight)
        val chipRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val spacer = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ctx.dp(4), 1f)
        }
        val time = TextView(ctx).apply {
            textSize = 11f
            setTextColor(ConsoleTheme.onSurfaceVariant)
        }
        val divider = View(ctx).apply {
            setBackgroundColor((ConsoleTheme.onSurface and 0x00FFFFFF) or 0x14000000.toInt())
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ctx.dp(1)
            ).apply { setMargins(ctx.dp(12), ctx.dp(4), ctx.dp(12), 0) }
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
        check.layoutParams = LinearLayout.LayoutParams(ctx.dp(28), ctx.dp(28)).apply {
            rightMargin = ctx.dp(4)
        }
        row.addView(check)
        column.addView(contentHost, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        column.addView(metaRow)
        chipRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        chipRow.addView(spacer)
        chipRow.addView(time)
        column.addView(chipRow)
        row.addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        root.addView(divider)
        // 点击波纹（跟随系统 selectableItemBackground）
        val rippleOut = android.util.TypedValue()
        var ripple: android.graphics.drawable.Drawable? = null
        if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, rippleOut, true)) {
            ripple = ContextCompat.getDrawable(ctx, rippleOut.resourceId)
        }
        root.foreground = ripple
        root.background = back
        root.layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            RecyclerView.LayoutParams.WRAP_CONTENT
        )
        return VH(root, check, contentHost, pill, content, metaRow, leftPath, typeRight, chipRow, time, divider, back, ripple)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        holder.content.text = e.primary

        // 事件条目：函数名独立药丸 chip（(funcName)）+ 正文
        if (e.eventFunc != null) {
            holder.pill.visibility = View.VISIBLE
            holder.pill.text = "(${e.eventFunc})"
        } else {
            holder.pill.visibility = View.GONE
        }

        val typeLine = e.typeDetails.joinToString(" · ")
        val hasMeta = e.relFile.isNotEmpty() || typeLine.isNotEmpty()
        holder.metaRow.visibility = if (hasMeta) View.VISIBLE else View.GONE
        holder.leftPath.text = e.relFile
        holder.typeRight.text = typeLine

        // chips:标签 · 一级 lua 类型 · 线程(尾部 spacer + 时间固定不拆)
        // 复用池只改文本:此前每次 bind 都新建 TextView + GradientDrawable,滚动时持续分配
        val pool = holder.chipPool
        holder.chipRow.removeViews(0, holder.chipRow.childCount - 2)
        var slot = 0
        fun chip(text: String) {
            while (pool.size <= slot) pool.add(newChip(holder.chipRow))
            val v = pool[slot]
            v.text = text
            holder.chipRow.addView(v, slot)
            slot++
        }
        chip(labelText(e.label))
        if (e.luaTypes.isNotEmpty()) chip(e.luaTypes.joinToString(" "))
        chip(if (e.isMainThread) "主线程" else "子线程")
        holder.time.text = e.fullTime

        val isSel = e.id in selected
        holder.check.isChecked = isSel
        holder.check.visibility = if (selectionMode) View.VISIBLE else View.GONE
        holder.itemView.foreground = if (selectionMode) null else holder.rippleForeground
        // 选中底色：淡入/淡出动画（accentContainer ⇄ 透明）
        val target = if (isSel) ConsoleTheme.accentContainer else Color.TRANSPARENT
        val current = (holder.back.color ?: android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)).defaultColor
        if (current != target) {
            ObjectAnimator.ofArgb(holder.back, "color", current, target).setDuration(160L).start()
        }
        holder.divider.visibility = if (position == items.lastIndex) View.GONE else View.VISIBLE

        holder.itemView.setOnClickListener {
            if (selectionMode) toggleSelect(e.id)
        }
        holder.itemView.setOnLongClickListener {
            if (!selectionMode) setSelectionMode(true)
            toggleSelect(e.id)
            true
        }
    }

    /**
     * 造一个药丸形小 chip(primary 淡底全圆角,仿 Material Chip 但尺寸收敛)。
     * 仅创建实例、不挂载:由 VH.chipPool 复用,绑定期只改文本,避免滚动时重复分配。
     */
    private fun newChip(row: LinearLayout): TextView {
        val ctx = row.context
        val chip = TextView(ctx).apply {
            textSize = 10f
            setTextColor(ConsoleTheme.primary)
            setPadding(ctx.dp(6), ctx.dp(1), ctx.dp(6), ctx.dp(1))
            background = GradientDrawable().apply {
                setColor((ConsoleTheme.primary and 0x00FFFFFF) or 0x14000000.toInt())
                cornerRadius = 999f // 药丸形
            }
        }
        chip.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = ctx.dp(6) }
        return chip
    }

    private fun toggleSelect(id: Long) {
        if (!selectionMode) setSelectionMode(true)
        if (selected.contains(id)) selected.remove(id) else selected.add(id)
        notifyDataSetChanged()
        onSelectionChanged()
    }

    class VH(
        itemView: View,
        val check: MaterialCheckBox,
        val contentHost: LinearLayout,
        val pill: TextView,
        val content: TextView,
        val metaRow: LinearLayout,
        val leftPath: TextView,
        val typeRight: TextView,
        val chipRow: LinearLayout,
        val time: TextView,
        val divider: View,
        val back: GradientDrawable,
        val rippleForeground: android.graphics.drawable.Drawable?
    ) : RecyclerView.ViewHolder(itemView) {
        /** chip 复用池:绑定期只改文本,避免滚动时重复分配。 */
        val chipPool = ArrayList<TextView>(3)
    }
}