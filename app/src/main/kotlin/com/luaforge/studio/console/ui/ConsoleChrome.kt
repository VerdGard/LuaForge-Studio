package com.luaforge.studio.console.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 控制台浮窗公共外观:头部(标题 + 当前文件副标题 + 图标按钮)。
 * 竖屏 BottomSheet 与横屏侧栏共用,保证两形态视觉一致。
 *
 * 原顶部页签栏已移除:页签改由左侧竖排导航 [ConsoleNavColumn] 承担。
 */
object ConsoleChrome {

    /**
     * 头部:主色竖条 + 标题(+ 副标题)+ 右侧图标按钮组。
     * @param subtitle 副标题(当前文件相对路径),空则只显标题。
     */
    fun header(
        ctx: Context,
        title: String,
        subtitle: String?,
        actions: List<Pair<Int, () -> Unit>>
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(10), ctx.dp(10))
            setBackgroundColor(ConsoleTheme.surface)
        }
        // 主色竖条:左侧视觉锚点
        row.addView(
            android.view.View(ctx).apply {
                background = GradientDrawable().apply {
                    setColor(ConsoleTheme.primary)
                    cornerRadius = ctx.dp(2).toFloat()
                }
            },
            LinearLayout.LayoutParams(ctx.dp(3), ctx.dp(20)).apply { marginEnd = ctx.dp(10) }
        )

        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        texts.addView(
            TextView(ctx).apply {
                text = title
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ConsoleTheme.onSurface)
                maxLines = 1
            }
        )
        if (!subtitle.isNullOrBlank()) {
            texts.addView(
                TextView(ctx).apply {
                    text = subtitle
                    textSize = 11f
                    setTextColor(ConsoleTheme.onSurfaceVariant)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = ctx.dp(2) }
            )
        }
        row.addView(
            texts,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        for ((iconRes, action) in actions) {
            row.addView(
                iconButton(ctx, iconRes, action),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = ctx.dp(8) }
            )
        }
        return row
    }

    /** 头部/导航与内容之间的细分隔线:低调分界,提升层次感。 */
    fun divider(ctx: Context): android.view.View =
        android.view.View(ctx).apply {
            setBackgroundColor(
                Color.argb(
                    0x1A,
                    Color.red(ConsoleTheme.onSurface),
                    Color.green(ConsoleTheme.onSurface),
                    Color.blue(ConsoleTheme.onSurface)
                )
            )
        }

    /** 头部图标按钮:圆角容器色底 + 主色图标 + 圆角波纹。 */
    fun iconButton(ctx: Context, iconRes: Int, onClick: () -> Unit): ImageView =
        ImageView(ctx).apply {
            setImageResource(iconRes)
            setColorFilter(ConsoleTheme.primary)
            val radius = ctx.dp(11).toFloat()
            background = RippleDrawable(
                ColorStateList.valueOf(ConsoleTheme.primary and 0x00FFFFFF or 0x24000000),
                GradientDrawable().apply {
                    setColor(ConsoleTheme.accentContainer)
                    cornerRadius = radius
                },
                GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = radius
                }
            )
            val p = ctx.dp(8)
            setPadding(p, p, p, p)
            setOnClickListener { onClick() }
        }
}
