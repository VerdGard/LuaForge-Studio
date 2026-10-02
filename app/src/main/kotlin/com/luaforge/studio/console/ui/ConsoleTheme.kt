package com.luaforge.studio.console.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import com.luaforge.studio.ui.settings.DarkMode
import com.luaforge.studio.ui.settings.SettingsManager
import com.luaforge.studio.ui.theme.ThemeType
import com.luaforge.studio.ui.theme.onSurfaceDarkBlue
import com.luaforge.studio.ui.theme.onSurfaceDarkGreen
import com.luaforge.studio.ui.theme.onSurfaceDarkPink
import com.luaforge.studio.ui.theme.onSurfaceLightBlue
import com.luaforge.studio.ui.theme.onSurfaceLightGreen
import com.luaforge.studio.ui.theme.onSurfaceLightPink
import com.luaforge.studio.ui.theme.onSurfaceVariantDarkBlue
import com.luaforge.studio.ui.theme.onSurfaceVariantDarkGreen
import com.luaforge.studio.ui.theme.onSurfaceVariantDarkPink
import com.luaforge.studio.ui.theme.onSurfaceVariantLightBlue
import com.luaforge.studio.ui.theme.onSurfaceVariantLightGreen
import com.luaforge.studio.ui.theme.onSurfaceVariantLightPink
import com.luaforge.studio.ui.theme.primaryContainerDarkBlue
import com.luaforge.studio.ui.theme.primaryContainerDarkGreen
import com.luaforge.studio.ui.theme.primaryContainerDarkPink
import com.luaforge.studio.ui.theme.primaryContainerLightBlue
import com.luaforge.studio.ui.theme.primaryContainerLightGreen
import com.luaforge.studio.ui.theme.primaryContainerLightPink
import com.luaforge.studio.ui.theme.primaryDarkBlue
import com.luaforge.studio.ui.theme.primaryDarkGreen
import com.luaforge.studio.ui.theme.primaryDarkPink
import com.luaforge.studio.ui.theme.primaryLightBlue
import com.luaforge.studio.ui.theme.primaryLightGreen
import com.luaforge.studio.ui.theme.primaryLightPink
import com.luaforge.studio.ui.theme.surfaceContainerDarkBlue
import com.luaforge.studio.ui.theme.surfaceContainerDarkGreen
import com.luaforge.studio.ui.theme.surfaceContainerDarkPink
import com.luaforge.studio.ui.theme.surfaceContainerLightBlue
import com.luaforge.studio.ui.theme.surfaceContainerLightGreen
import com.luaforge.studio.ui.theme.surfaceContainerLightPink
import com.luaforge.studio.ui.theme.surfaceDarkBlue
import com.luaforge.studio.ui.theme.surfaceDarkGreen
import com.luaforge.studio.ui.theme.surfaceDarkPink
import com.luaforge.studio.ui.theme.surfaceLightBlue
import com.luaforge.studio.ui.theme.surfaceLightGreen
import com.luaforge.studio.ui.theme.surfaceLightPink

/**
 * 控制台主题:浮球/浮窗及全部控件颜色遵循 LuaForge-Studio「主题与外观」配置
 * (themeType GREEN/PINK/BLUE + darkMode + dynamicColor)。
 * 非 Compose 环境取色:动态取色走 material3 dynamic scheme,静态走 Color.kt 色板。
 * 使用前须 refresh()(OverlayController.showBall/openSheet 已调用)。
 */
object ConsoleTheme {

    var primary: Int = 0xFF3D5AFE.toInt()
        private set
    var onPrimary: Int = Color.WHITE
        private set
    var surface: Int = Color.WHITE
        private set
    var onSurface: Int = 0xFF222222.toInt()
        private set
    var onSurfaceVariant: Int = 0xFF777777.toInt()
        private set
    /** 强调浅底:选中行 / 操作按钮背景。 */
    var accentContainer: Int = 0xFFE3EDFF.toInt()
        private set
    /** 头部/页签区域背景(与内容区 surface 区分)。 */
    var surfaceContainer: Int = 0xFFF2F3FA.toInt()
        private set
    var isDark: Boolean = false
        private set
    /** 主题圆角基准(px):shapeSizeIndex → 4/8/12/16dp,随「主题与外观」设置。 */
    var cornerRadiusPx: Float = 12f
        private set

    fun refresh(context: Context) {
        val s = SettingsManager.currentSettings
        // 主题圆角基准(dp):shapeSizeIndex 0/1/2/3 → 小/中小/中/大
        val baseDp = when (s.shapeSizeIndex) {
            0 -> 4f
            1 -> 8f
            2 -> 12f
            3 -> 16f
            else -> 12f
        }
        cornerRadiusPx = baseDp * context.resources.displayMetrics.density
        val dark = when (s.darkMode) {
            DarkMode.FOLLOW_SYSTEM -> isSystemDark(context)
            DarkMode.LIGHT -> false
            DarkMode.DARK -> true
            else -> isSystemDark(context)
        }
        isDark = dark

        if (s.dynamicColor && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            try {
                val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                primary = scheme.primary.toArgb()
                onPrimary = scheme.onPrimary.toArgb()
                surface = scheme.surface.toArgb()
                onSurface = scheme.onSurface.toArgb()
                onSurfaceVariant = scheme.onSurfaceVariant.toArgb()
                accentContainer = scheme.primaryContainer.toArgb()
                surfaceContainer = scheme.surfaceContainer.toArgb()
                return
            } catch (_: Exception) {
                // 动态取色失败 → 回退静态色板
            }
        }

        when (s.themeType) {
            ThemeType.GREEN -> if (dark) {
                primary = primaryDarkGreen.toArgb(); surface = surfaceDarkGreen.toArgb()
                onSurface = onSurfaceDarkGreen.toArgb(); onSurfaceVariant = onSurfaceVariantDarkGreen.toArgb()
                accentContainer = primaryContainerDarkGreen.toArgb()
                surfaceContainer = surfaceContainerDarkGreen.toArgb()
            } else {
                primary = primaryLightGreen.toArgb(); surface = surfaceLightGreen.toArgb()
                onSurface = onSurfaceLightGreen.toArgb(); onSurfaceVariant = onSurfaceVariantLightGreen.toArgb()
                accentContainer = primaryContainerLightGreen.toArgb()
                surfaceContainer = surfaceContainerLightGreen.toArgb()
            }
            ThemeType.PINK -> if (dark) {
                primary = primaryDarkPink.toArgb(); surface = surfaceDarkPink.toArgb()
                onSurface = onSurfaceDarkPink.toArgb(); onSurfaceVariant = onSurfaceVariantDarkPink.toArgb()
                accentContainer = primaryContainerDarkPink.toArgb()
                surfaceContainer = surfaceContainerDarkPink.toArgb()
            } else {
                primary = primaryLightPink.toArgb(); surface = surfaceLightPink.toArgb()
                onSurface = onSurfaceLightPink.toArgb(); onSurfaceVariant = onSurfaceVariantLightPink.toArgb()
                accentContainer = primaryContainerLightPink.toArgb()
                surfaceContainer = surfaceContainerLightPink.toArgb()
            }
            ThemeType.BLUE -> if (dark) {
                primary = primaryDarkBlue.toArgb(); surface = surfaceDarkBlue.toArgb()
                onSurface = onSurfaceDarkBlue.toArgb(); onSurfaceVariant = onSurfaceVariantDarkBlue.toArgb()
                accentContainer = primaryContainerDarkBlue.toArgb()
                surfaceContainer = surfaceContainerDarkBlue.toArgb()
            } else {
                primary = primaryLightBlue.toArgb(); surface = surfaceLightBlue.toArgb()
                onSurface = onSurfaceLightBlue.toArgb(); onSurfaceVariant = onSurfaceVariantLightBlue.toArgb()
                accentContainer = primaryContainerLightBlue.toArgb()
                surfaceContainer = surfaceContainerLightBlue.toArgb()
            }
        }
    }

    private fun isSystemDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}

/** 控制台 MaterialSwitch 主题化:轨道/拇指颜色跟随 ConsoleTheme(需已 refresh())。 */
fun android.widget.CompoundButton.themeSwitch() {
    (this as? com.google.android.material.materialswitch.MaterialSwitch)?.let { sw ->
        val thumbUnchecked = if (ConsoleTheme.isDark) 0xFFB6B6C6.toInt() else 0xFF8B93A7.toInt()
        val trackUnchecked = android.graphics.Color.argb(
            0x40, android.graphics.Color.red(ConsoleTheme.onSurface),
            android.graphics.Color.green(ConsoleTheme.onSurface),
            android.graphics.Color.blue(ConsoleTheme.onSurface)
        )
        sw.thumbTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ConsoleTheme.onPrimary, thumbUnchecked)
        )
        sw.trackTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ConsoleTheme.primary, trackUnchecked)
        )
    }
}
