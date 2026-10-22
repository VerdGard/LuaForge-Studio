package com.luaforge.studio.utils

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogConfigState(
    val isLogEnabled: Boolean = true,
    val logFilePath: String = "/storage/emulated/0/LuaForge-Studio/luaforge.log",
    val isLoaded: Boolean = false
)

class LogConfigRepository(private val context: Context) {

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("webide_log_config", Context.MODE_PRIVATE)

    private val LOG_ENABLED_KEY = "log_enabled"

    fun getLogConfig(): LogConfigState {
        val isEnabled = sharedPreferences.getBoolean(LOG_ENABLED_KEY, true)
        return LogConfigState(
            isLogEnabled = isEnabled,
            logFilePath = "/storage/emulated/0/LuaForge-Studio/luaforge.log",
            isLoaded = true
        )
    }

    /** 读取当前开关状态(供设置页 Switch 使用)。 */
    fun isLogEnabled(): Boolean = sharedPreferences.getBoolean(LOG_ENABLED_KEY, true)

    fun saveLogConfig(isEnabled: Boolean) {
        sharedPreferences.edit()
            .putBoolean(LOG_ENABLED_KEY, isEnabled)
            .apply()

        // 立即更新
        LogCatcher.updateConfig(
            LogConfigState(
                isLogEnabled = isEnabled,
                logFilePath = "/storage/emulated/0/LuaForge-Studio/luaforge.log",
                isLoaded = true
            )
        )
    }
}

/**
 * 增强的日志捕获器，支持文件输出和开关控制
 */
object LogCatcher {

    private var logConfig: LogConfigState = LogConfigState()

    @Volatile
    private var isInitialized = false

    @JvmStatic
    fun updateConfig(config: LogConfigState) {
        logConfig = config
        isInitialized = true
        android.util.Log.e(
            "LogCatcher",
            "日志系统配置已更新 - 启用: ${config.isLogEnabled}, 路径: ${config.logFilePath}"
        )
    }

    /**
     * 初始化并在启动时写入强制日志
     */
    @JvmStatic
    fun init(context: Context) {
        android.util.Log.e("LogCatcher", "开始初始化日志系统...")
        val repository = LogConfigRepository(context)
        updateConfig(repository.getLogConfig())

        // 清空日志文件内容（每次启动时）
        clearLogFile()

        // 写入初始化日志
        i("LogCatcher", "日志系统初始化完成，日志文件已清空并重新开始记录")
    }

    /**
     * 清空日志文件内容
     */
    @JvmStatic
    fun clearLogFile() {
        // 与追加走同一条串行线程:否则"清空"和"正在追加的一行"会互相穿插,
        // 表现为清空后日志里仍残留半条旧记录
        try {
            logHandler.post { clearLogFileNow() }
        } catch (_: Throwable) {
            clearLogFileNow()
        }
    }

    private fun clearLogFileNow() {
        synchronized(fileLock) {
            try {
                val logFile = File(logConfig.logFilePath)

                // 确保父目录存在(parentFile 在路径无父级时为 null,例如相对文件名)
                logFile.parentFile?.let { parentDir ->
                    if (!parentDir.exists()) {
                        parentDir.mkdirs()
                    }
                }

                // 如果文件存在，清空内容；如果不存在，创建空文件
                if (logFile.exists()) {
                    logFile.writeText("")
                    android.util.Log.i("LogCatcher", "已清空日志文件: ${logConfig.logFilePath}")
                } else {
                    logFile.createNewFile()
                    android.util.Log.i(
                        "LogCatcher",
                        "已创建新的空日志文件: ${logConfig.logFilePath}"
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("LogCatcher", "清空日志文件失败: ${e.message}")
            }
        }
    }

    @JvmStatic
    fun d(tag: String, message: String) {
        android.util.Log.d(tag, message)
        if (shouldLog()) {
            writeToFile("DEBUG", tag, message)
        }
    }

    @JvmStatic
    fun i(tag: String, message: String) {
        android.util.Log.i(tag, message)
        if (shouldLog()) {
            writeToFile("INFO", tag, message)
        }
    }

    @JvmStatic
    fun w(tag: String, message: String) {
        android.util.Log.w(tag, message)
        if (shouldLog()) {
            writeToFile("WARN", tag, message)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun e(tag: String, message: String, exception: Exception? = null) {
        android.util.Log.e(tag, message, exception)
        if (shouldLog()) {
            val exceptionInfo =
                exception?.let { " - ${it.message}\n${it.stackTraceToString()}" } ?: ""
            writeToFile("ERROR", tag, "$message$exceptionInfo")
        }
    }

    @JvmStatic
    fun permission(tag: String, action: String, result: String) {
        val message = "权限操作: $action - $result"
        android.util.Log.i("$tag-Permission", message)
        if (shouldLog()) {
            writeToFile("PERMISSION", tag, message)
        }
    }

    @JvmStatic
    fun fileOperation(tag: String, operation: String, filePath: String, result: String) {
        val message = "文件操作: $operation - $filePath - $result"
        android.util.Log.i("$tag-File", message)
        if (shouldLog()) {
            writeToFile("FILE", tag, message)
        }
    }

    private fun shouldLog(): Boolean {
        val ready = isInitialized && logConfig.isLogEnabled
        if (!ready) {
            android.util.Log.w(
                "LogCatcher",
                "日志未就绪 - isInitialized: $isInitialized, isEnabled: ${logConfig.isLogEnabled}"
            )
        }
        return ready
    }

    /**
     * 后台串行落盘线程。
     *
     * 旧实现是「每条日志起一个独立协程(IO 调度),里面
     * `File.appendText`」:每条都要重新 open/write/close 一次文件,还各自持有
     * 独立协程。构建一个项目会打上百条日志(见 ApkBuilder 的 133 处调用),
     * 于是同时挂起上百个协程抢同一个文件,IO 排队与协程调度开销会明显拖慢
     * 主线程(`shouldLog`/格式化都在调用线程上)。
     *
     * 现在改为**单线程串行写**:调用方只做一次 `Handler.post`,文件句柄的
     * 开关、截断判定全部集中在一条后台线程,顺序稳定且不再抖动。
     */
    private val logWorker: android.os.HandlerThread by lazy {
        android.os.HandlerThread("LuaForgeLogCatcher").apply { start() }
    }
    private val logHandler: android.os.Handler by lazy {
        android.os.Handler(logWorker.looper)
    }

    /** 串行化文件访问,防止 clearLogFile 与追加并发。 */
    private val fileLock = Any()

    /** 时间格式非线程安全:在锁内格式化。 */
    private val timeLock = Any()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    /** 超过该大小则截断,避免长时间使用后日志无限增长。 */
    private const val MAX_LOG_BYTES = 4L * 1024 * 1024
    private const val TRUNCATE_KEEP = 1024 * 1024

    private fun writeToFile(level: String, tag: String, message: String) {
        val line = synchronized(timeLock) {
            "[${timeFormat.format(Date())}] [$level] [$tag] $message\n"
        }
        try {
            logHandler.post { appendLine(line) }
        } catch (_: Throwable) {
            // HandlerThread 起不来等极端场景:退化为同步写,保证不丢日志
            appendLine(line)
        }
    }

    /** 仅在后台落盘线程执行(退化路径除外)。 */
    private fun appendLine(line: String) {
        synchronized(fileLock) {
            try {
                val logFile = File(logConfig.logFilePath)
                // 确保父目录存在(parentFile 在路径无父级时为 null,例如相对文件名)
                logFile.parentFile?.let { parentDir ->
                    if (!parentDir.exists()) parentDir.mkdirs()
                }
                truncateIfNeeded(logFile)
                java.io.FileOutputStream(logFile, true).use {
                    it.write(line.toByteArray(Charsets.UTF_8))
                }
            } catch (e: Exception) {
                android.util.Log.e("LogCatcher", "写入日志文件失败: ${e.message}")
            }
        }
    }

    /** 超过上限时保留尾部内容,防止日志文件无限增长。 */
    private fun truncateIfNeeded(logFile: File) {
        if (!logFile.exists() || logFile.length() <= MAX_LOG_BYTES) return
        try {
            val keep = logFile.readText(Charsets.UTF_8).takeLast(TRUNCATE_KEEP)
            logFile.writeText("... (较早日志已截断)\n$keep", Charsets.UTF_8)
        } catch (_: Throwable) {
            // 截断失败继续追加,不影响使用
        }
    }
}

/**
 * 日志文件的读取/清空/导出辅助(与 [LogCatcher]、core 的 RuntimeLog 共用同一文件)。
 *
 * 只读取不写入;所有异常都吞掉并返回可判定的结果,避免设置页因日志问题崩溃。
 */
object LogFileAccess {

    /** 与 LogCatcher / RuntimeLog 保持一致的日志路径。 */
    const val LOG_FILE_PATH = "/storage/emulated/0/LuaForge-Studio/luaforge.log"

    /**
     * 读取日志文本。
     *
     * @param errorsOnly 为 true 时只返回含 [ERROR] 级别的行(用于「查看错误日志」)。
     * @param maxChars   截取的最大字符数(从尾部保留最新内容)。
     */
    fun readLog(errorsOnly: Boolean = false, maxChars: Int = 200_000): String {
        return try {
            val file = File(LOG_FILE_PATH)
            if (!file.exists()) return ""
            var text = file.readText(Charsets.UTF_8)
            if (errorsOnly) {
                text = text.lineSequence()
                    .filter { it.contains("[ERROR]") }
                    .joinToString("\n")
            }
            if (text.length > maxChars) {
                text = "...(较早内容已省略)\n" + text.takeLast(maxChars)
            }
            text
        } catch (_: Throwable) {
            ""
        }
    }

    /** 清空日志文件内容(保留文件本身)。 */
    fun clear(): Boolean {
        return try {
            val file = File(LOG_FILE_PATH)
            if (file.exists()) file.writeText("", Charsets.UTF_8) else file.createNewFile()
            true
        } catch (_: Throwable) {
            false
        }
    }
}
