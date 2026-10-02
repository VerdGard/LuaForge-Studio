package com.luaforge.studio.console.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 悬浮按钮:自适应长方形胶囊——高度固定,宽度随文字与未读计数芯片伸缩(WRAP_CONTENT 自测量)。
 * 颜色遵循 LuaForge-Studio 主题(ConsoleTheme.primary);崩溃转红,未读 Lua 错误以内嵌计数芯片呈现。
 * 支持拖动与点击(位移阈值内视为点击)。
 */
@SuppressLint("ViewConstructor")
class ConsoleBallView(context: Context, private val onTap: () -> Unit) : View(context) {

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity

    private val label = "控制台"

    private val capsule = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = scaled * 12.5f
        typeface = Typeface.DEFAULT_BOLD
        isFakeBoldText = true
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        isFakeBoldText = true
        textSize = scaled * 9f
    }
    private val bounds = RectF()
    private val borderRect = RectF()
    private val chipRect = RectF()

    /** 固定高度;宽度由 [desiredWidth] 自适应。 */
    private val capsuleHeight = (density * 34f).toInt()
    private val padH = density * 13f
    private val dotR = density * 3.2f
    private val dotGap = density * 6.5f
    private val chipGap = density * 6.5f
    private val chipPadH = density * 5f
    private val chipPadV = density * 1.4f

    private var crashed = false
    private var errorCount = 0
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    var onMove: ((dx: Float, dy: Float) -> Unit)? = null

    init {
        contentDescription = "调试控制台"
        applyColors()
    }

    /** 崩溃提示:浮球转红。 */
    fun setRed(red: Boolean) {
        if (crashed == red) return
        crashed = red
        applyColors()
        invalidate()
    }

    /** 未读 Lua 错误角标计数(胶囊内计数芯片);0 隐藏。宽度随之变化,需重新测量。 */
    fun setErrorCount(n: Int) {
        if (errorCount == n) return
        errorCount = n
        requestLayout()
        invalidate()
    }

    /** 主题/状态取色:胶囊竖向渐变 + 文字/圆点/芯片配色。 */
    private fun applyColors() {
        val base = if (crashed) COLOR_CRASH else ConsoleTheme.primary
        val h = (if (height > 0) height else capsuleHeight).toFloat()
        capsule.shader = LinearGradient(
            0f, 0f, 0f, h,
            lighten(base, 0.16f), darken(base, 0.10f), Shader.TileMode.CLAMP
        )
        textPaint.color = Color.WHITE
        border.color = withAlpha(Color.WHITE, if (crashed) 0.26f else 0.20f)
        dotPaint.color = withAlpha(Color.WHITE, 0.62f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyColors()
    }

    // ---------- 自适应测量 ----------

    private fun chipLabel(): String? =
        if (errorCount <= 0) null else if (errorCount > 99) "99+" else errorCount.toString()

    private fun chipWidth(): Float {
        val l = chipLabel() ?: return 0f
        return chipPadH * 2f + chipText.measureText(l)
    }

    private fun chipHeight(): Float = chipText.textSize + chipPadV * 2f

    /** 期望宽度 = 左右内边距 + 状态点 + 文字 +(有错误时)计数芯片。 */
    private fun desiredWidth(): Float {
        var w = padH * 2f + dotR * 2f + dotGap + textPaint.measureText(label)
        if (errorCount > 0) w += chipGap + chipWidth()
        return w
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(desiredWidth().toInt(), widthMeasureSpec),
            resolveSize(capsuleHeight, heightMeasureSpec)
        )
    }

    // ---------- 绘制 ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = h / 2f

        bounds.set(0f, 0f, w, h)
        canvas.drawRoundRect(bounds, radius, radius, capsule)
        // 内描边提亮上缘,弱化纯色扁平感
        val inset = border.strokeWidth / 2f
        borderRect.set(inset, inset, w - inset, h - inset)
        canvas.drawRoundRect(borderRect, radius, radius, border)

        val cy = h / 2f
        var x = padH
        canvas.drawCircle(x + dotR, cy, dotR, dotPaint)
        x += dotR * 2f + dotGap

        val ty = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, x, ty, textPaint)
        x += textPaint.measureText(label)

        val cLabel = chipLabel()
        if (cLabel != null) {
            val cw = chipWidth()
            val ch = chipHeight()
            val left = x + chipGap
            val top = cy - ch / 2f
            chipRect.set(left, top, left + cw, top + ch)
            chipPaint.color = if (crashed) Color.WHITE else COLOR_CRASH
            canvas.drawRoundRect(chipRect, ch / 2f, ch / 2f, chipPaint)
            chipText.color = if (crashed) COLOR_CRASH else Color.WHITE
            val cty = cy - (chipText.descent() + chipText.ascent()) / 2f
            canvas.drawText(cLabel, left + cw / 2f, cty, chipText)
        }
    }

    // ---------- 交互 ----------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (abs(dx) > 8 || abs(dy) > 8) {
                    moved = true
                    onMove?.invoke(dx, dy)
                    downX = event.rawX
                    downY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) onTap()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    companion object {
        private val COLOR_CRASH = 0xFFE53935.toInt()

        private fun withAlpha(c: Int, a: Float): Int =
            Color.argb((255f * a).toInt(), Color.red(c), Color.green(c), Color.blue(c))

        private fun lighten(c: Int, f: Float): Int = Color.rgb(
            (Color.red(c) + (255 - Color.red(c)) * f).toInt().coerceIn(0, 255),
            (Color.green(c) + (255 - Color.green(c)) * f).toInt().coerceIn(0, 255),
            (Color.blue(c) + (255 - Color.blue(c)) * f).toInt().coerceIn(0, 255)
        )

        private fun darken(c: Int, f: Float): Int = Color.rgb(
            (Color.red(c) * (1f - f)).toInt().coerceIn(0, 255),
            (Color.green(c) * (1f - f)).toInt().coerceIn(0, 255),
            (Color.blue(c) * (1f - f)).toInt().coerceIn(0, 255)
        )
    }
}
