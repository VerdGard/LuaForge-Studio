package com.luaforge.studio.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.getSystemService

/**
 * 电池优化豁免工具。
 *
 * Android 6.0 起引入 Doze/App Standby,长驻的后台服务(如 MCP 保活)可能被限制。
 * 申请加入电池优化白名单可显著降低被限制的概率;部分 ROM 还需用户手动在系统设置中允许。
 */
object BatteryOptimization {

    /** 当前应用是否已加入电池优化白名单。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService<PowerManager>() ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 请求加入电池优化白名单。
     *
     * 优先使用系统专用的授权弹窗 [Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS];
     * 若设备不支持(或抛异常),回退到电池优化列表页由用户手动选择。
     *
     * @return 是否成功拉起系统界面
     */
    @SuppressLint("BatteryLife")
    fun requestExemption(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        if (isIgnoringBatteryOptimizations(context)) return true
        return try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
