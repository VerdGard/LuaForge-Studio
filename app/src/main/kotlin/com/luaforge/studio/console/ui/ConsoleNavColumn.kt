package com.luaforge.studio.console.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 控制台左侧竖排导航栏:每项 = 左侧主色竖条 + 页签名,选中态 accentContainer 底 + 主色加粗文字。
 *
 * 取代原顶部 TabLayout:横竖屏共用同一形态(左导航 / 右内容),避免竖屏顶部页签挤压内容高度。
 * 背景/行底 drawable 一次性构建,选中切换只改 isSelected 与文字色,不重复分配对象。
 */
class ConsoleNavColumn(
    context: Context,
    private val titles: List<String>,
    private val onSelect: (Int) -> Unit
) : LinearLayout(context) {

    private val bars = ArrayList<View>(titles.size)
    private val labels = ArrayList<TextView>(titles.size)
    private val rows = ArrayList<LinearLayout>(titles.size)

    /** 当前高亮项;-1 = 尚未选择。 */
    var selectedIndex: Int = -1
        private set

    init {
        orientation = VERTICAL
        setBackgroundColor(ConsoleTheme.surfaceContainer)
        setPadding(0, context.dp(6), 0, context.dp(6))
        titles.forEachIndexed { index, title ->
            val bar = View(context)
            val label = TextView(context).apply {
                text = title
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
            }
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                background = rowBackground()
                setOnClickListener { select(index) }
            }
            row.addView(bar, LayoutParams(context.dp(3), LayoutParams.MATCH_PARENT))
            row.addView(
                label,
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(9) }
            )
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(46)))
            bars.add(bar)
            labels.add(label)
            rows.add(row)
        }
    }

    /** 选中并高亮第 [index] 项;越界忽略。会回调 [onSelect]。 */
    fun select(index: Int) {
        if (index !in titles.indices) return
        selectedIndex = index
        for (i in titles.indices) {
            val on = i == index
            // 竖条用底色直接开关(选中显主色);行选中态交给 StateListDrawable
            bars[i].setBackgroundColor(if (on) ConsoleTheme.primary else Color.TRANSPARENT)
            labels[i].setTextColor(if (on) ConsoleTheme.primary else ConsoleTheme.onSurfaceVariant)
            labels[i].typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            rows[i].isSelected = on
        }
        onSelect(index)
    }

    /** 行底:波纹叠在 StateList 上——选中 accentContainer,未选透明。 */
    private fun rowBackground(): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(ConsoleTheme.primary and 0x00FFFFFF or 0x1F000000),
            StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_selected),
                    GradientDrawable().apply { setColor(ConsoleTheme.accentContainer) }
                )
                addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            },
            null
        )

    companion object {
        /** 左列固定宽度(dp):容纳 2–6 字页签名,给右侧内容让出最大宽度。 */
        const val WIDTH_DP = 78
    }
}
