package com.luaforge.studio.console.output

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 控制台时间格式化(线程安全)。
 *
 * SimpleDateFormat 非线程安全:控制台输出/事件同时来自 Lua 线程、logcat 后台线程与 UI 线程,
 * 若共享静态实例会产生格式化错乱甚至抛异常。此处以 ThreadLocal 让每条线程各持一份实例。
 *
 * 不用 ThreadLocal.withInitial:其要求 API 26,而本项目 minSdk 24 且未开启 desugaring。
 */
object TimeFormat {

    private const val PATTERN_SHORT = "MM-dd HH:mm:ss"
    private const val PATTERN_FULL = "MM-dd HH:mm:ss.SSS"

    private val short = formatLocal(PATTERN_SHORT)
    private val full = formatLocal(PATTERN_FULL)

    /** MM-dd HH:mm:ss */
    fun short(ms: Long): String = short.get()!!.format(Date(ms))

    /** MM-dd HH:mm:ss.SSS */
    fun full(ms: Long): String = full.get()!!.format(Date(ms))

    private fun formatLocal(pattern: String): ThreadLocal<SimpleDateFormat> =
        object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat = SimpleDateFormat(pattern, Locale.US)
        }
}
