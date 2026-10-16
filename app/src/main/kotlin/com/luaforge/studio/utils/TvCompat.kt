package com.luaforge.studio.utils

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.core.content.getSystemService

/**
 * TV / 大屏设备兼容性判定。
 *
 * Android TV、Google TV 与部分投影/车机以「无触摸屏 + 遥控器方向键导航」为交互方式,
 * 布局与焦点行为需要区别对待。此处集中判定,避免各处散落 `UiModeManager` 判断。
 */
object TvCompat {

    /** 当前是否为电视类设备(Android TV / Google TV)。 */
    fun isTelevision(context: Context): Boolean {
        val uiModeManager = context.getSystemService<UiModeManager>()
        if (uiModeManager != null &&
            uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        ) {
            return true
        }
        // 部分设备 uiMode 不可靠,回退到 leanback 特性声明
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    /**
     * 设备是否缺少触摸屏(典型遥控器/手柄操作)。
     * 用于决定是否需要强制可见焦点与更大的点击热区。
     */
    fun isNonTouchDevice(context: Context): Boolean =
        !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

    /** 需要以「遥控器导航」为主交互:电视或非触摸大屏。 */
    fun prefersDpadNavigation(context: Context): Boolean =
        isTelevision(context) || isNonTouchDevice(context)

    /** Android TV 是否可用(用于决定是否声明/展示 TV 相关入口)。 */
    fun supportsLeanback(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    @Suppress("unused")
    fun sdkIsAtLeastTvApi(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
}
