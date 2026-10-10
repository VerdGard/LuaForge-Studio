package com.luaforge.studio.widget.glass

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.annotation.Keep
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy

/**
 * 液态玻璃控件(基于 Kyant0/AndroidLiquidGlass 的 backdrop 效果)。
 *
 * 为什么是 Android View 而不是 Compose 组件:
 * 布局助手与运行时 `loadlayout` 都通过 luajava 绑定 **Android View 子类**
 * (且要求 `View.isAssignableFrom`),而 backdrop 库只提供 Compose 侧的
 * `Modifier.drawBackdrop(...)`。因此这里用 [ComposeView] 把 Compose 效果
 * 包成一个 `FrameLayout`,使其能像普通控件一样写成:
 *
 * ```
 * { LiquidGlassView, layout_width = "match_parent", layout_height = "120dp", cornerRadius = 28 }
 * ```
 *
 * 可经 luajava 调用的 setter(属性名 = 去掉 set 前缀、首字母小写):
 * - `cornerRadius` 圆角半径(px)
 * - `blurRadius`   模糊半径(px)
 * - `glassColor`   玻璃底色(ARGB,如 `0x40FFFFFF` 或 `"#40FFFFFF"`)
 *
 * 效果语义(重要):玻璃层的内容由本控件**自绘**(底色 + 彩色斑块),再经
 * `vibrancy()` + `blur()` 处理得到磨砂质感。因此它是"半透明磨砂面板",而
 * **不是**对控件身后真实画面的实时采样/模糊(那需要整屏 Compose 才能实现)。
 * `glassColor` 带 alpha 时,身后内容会透过面板显现,但不会被模糊。
 *
 * 兼容性:效果依赖 RenderEffect / RuntimeShader。低端或不支持的设备上,库内部
 * 会自行降级(跳过对应效果),不会抛错崩溃。
 */
@Keep
class LiquidGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var cornerRadiusPx by mutableFloatStateOf(28f)
    private var blurRadiusPx by mutableFloatStateOf(24f)
    // 属性名不能叫 glassColor:它生成的 JVM setter setGlassColor(I)V 会与
    // 下方手写的 fun setGlassColor(argb: Int) 签名冲突(Platform declaration clash)。
    private var glassColorArgb: Int by mutableIntStateOf(0x40FFFFFF)

    private val composeView = ComposeView(context)

    init {
        addView(
            composeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        composeView.setContent { LiquidGlassSurface() }
    }

    /** 圆角半径:布局中写 `cornerRadius = 28`(px)。 */
    fun setCornerRadius(radiusPx: Float) {
        cornerRadiusPx = radiusPx
    }

    /** 背景模糊半径:布局中写 `blurRadius = 24`(px)。 */
    fun setBlurRadius(radiusPx: Float) {
        blurRadiusPx = radiusPx
    }

    /** 玻璃底色:布局中写 `glassColor = 0x40FFFFFF`。 */
    fun setGlassColor(argb: Int) {
        glassColorArgb = argb
    }

    @Composable
    private fun LiquidGlassSurface() {
        val radiusPx = cornerRadiusPx
        val blurPx = blurRadiusPx
        val base = Color(glassColorArgb)
        val density = LocalDensity.current

        // CanvasBackdrop:玻璃层内容由本控件自绘(底色 + 彩色斑块),再施加
        // 鲜艳度 + 模糊得到磨砂质感。斑块用于让模糊效果可见。
        // 注意:这是"自绘磨砂面板",不采样控件身后的真实画面。
        val backdrop = rememberCanvasBackdrop {
            drawRect(color = base)
            val r = size.minDimension * 0.5f
            drawCircle(
                color = Color(0x66FF5A8A),
                radius = r,
                center = Offset(size.width * 0.25f, size.height * 0.3f)
            )
            drawCircle(
                color = Color(0x664A8CFF),
                radius = r,
                center = Offset(size.width * 0.75f, size.height * 0.7f)
            )
        }

        // 圆角用 Dp 重载,px -> dp 需按当前 density 换算(不可直接用 px 当 dp)
        val corner = with(density) { radiusPx.toDp() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawPlainBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(corner) },
                    effects = {
                        vibrancy()
                        blur(blurPx)
                    }
                )
        )
    }
}
