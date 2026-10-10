package com.luaforge.studio.utils

import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.Keep
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lua 运行时日志落盘工具(core 模块)。
 *
 * core 模块的 LuaActivity / LuaService / LuaApplication 无法引用 app 模块的
 * LogCatcher,而它们的 sendMsg / sendError 之前只写 logcat,导致 Lua 运行时
 * 错误从不出现在 luaforge.log 中 —— 排查现场和 MCP 运行时检查都拿不到错误。
 *
 * 这里统一把这些消息追加到与 LogCatcher 相同的日志文件,保持单一来源。
 * 写入失败绝不影响 Lua 运行。
 *
 * 性能:落盘改为「调用线程只入队、后台单线程串行写盘」。
 * 原因:Lua 在宿主线程(通常即 UI 线程)执行,注入调试浮窗后每条 print 都会
 * 走到这里;若在此同步做磁盘 IO,高频输出会直接拖住 UI 线程造成卡顿。
 * 现在调用方只做一次 Handler.post,磁盘读写全部在后台线程完成。
 */
@Keep
object RuntimeLog {

    /** 与 LogCatcher 使用同一个日志文件。 */
    const val LOG_FILE_PATH = "/storage/emulated/0/LuaForge-Studio/luaforge.log"

    /** 超过该大小则截断,避免长时间运行导致日志无限增长。 */
    private const val MAX_BYTES = 4L * 1024 * 1024
    private const val TRUNCATE_KEEP = 1024 * 1024

    /** 串行化后台落盘的文件锁。 */
    private val fileLock = Any()

    /** 时间格式非线程安全:调用方在独立锁内格式化后再入队。 */
    private val timeLock = Any()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    /**
     * 后台单线程串行落盘。
     *
     * 用 HandlerThread 而非线程池:保证日志行按顺序写入、且只有一条
     * 写盘线程互相竞争,避免多线程随机穿插破坏日志可读性。
     */
    private val worker: HandlerThread by lazy {
        HandlerThread("LuaForgeRuntimeLog").apply { start() }
    }
    private val handler: Handler by lazy { Handler(worker.looper) }

    @JvmStatic
    fun log(tag: String, message: String) {
        write("INFO", tag, message)
    }

    /**
     * Lua 层输出统一入口(LuaActivity / LuaService 的 sendMsg 都会走到这里)。
     *
     * 按内容判定级别,使运行时错误在日志里可直接按 ERROR 检索,
     * 而普通的 print 输出仍为 INFO。
     */
    @JvmStatic
    fun logLua(message: String) {
        write(if (looksLikeError(message)) "ERROR" else "INFO", "lua", message)
    }

    private val errorKeywords = arrayOf(
        "error", "exception", "traceback", "failed", "failure",
        "错误", "失败", "异常"
    )

    private fun looksLikeError(message: String): Boolean {
        val lower = message.lowercase(Locale.getDefault())
        return errorKeywords.any { lower.contains(it) }
    }

    @JvmStatic
    fun error(tag: String, message: String) {
        error(tag, message, null)
    }

    @JvmStatic
    fun error(tag: String, message: String, throwable: Throwable?) {
        val detail = if (throwable == null) message else "$message\n${throwable.stackTraceToString()}"
        write("ERROR", tag, detail)
    }

    private fun write(level: String, tag: String, message: String) {
        val line = synchronized(timeLock) {
            "[${timeFormat.format(Date())}] [$level] [$tag] $message\n"
        }
        try {
            handler.post { appendLine(line) }
        } catch (_: Throwable) {
            // HandlerThread 初始化失败等极端场景:退化为同步写,保证不丢日志
            appendLine(line)
        }
    }

    /** 仅在后台落盘线程执行(退化路径除外)。 */
    private fun appendLine(line: String) {
        synchronized(fileLock) {
            try {
                val file = File(LOG_FILE_PATH)
                val parent = file.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }
                truncateIfNeeded(file)
                FileOutputStream(file, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
            } catch (_: Throwable) {
                // 日志写入失败不能影响 Lua 运行
            }
        }
    }

    private fun truncateIfNeeded(file: File) {
        if (!file.exists() || file.length() <= MAX_BYTES) return
        try {
            val keep = file.readText(Charsets.UTF_8).takeLast(TRUNCATE_KEEP)
            file.writeText("... (较早日志已截断)\n$keep", Charsets.UTF_8)
        } catch (_: Throwable) {
            // 截断失败则继续追加
        }
    }
}
