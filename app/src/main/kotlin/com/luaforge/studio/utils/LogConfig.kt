package com.luaforge.studio.utils

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        CoroutineScope(Dispatchers.IO).launch {
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

    private fun writeToFile(level: String, tag: String, message: String) {
        // 使用 IO 调度器在后台线程执行
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val logFile = File(logConfig.logFilePath)

                // 确保父目录存在(parentFile 在路径无父级时为 null,例如相对文件名)
                logFile.parentFile?.let { parentDir ->
                    if (!parentDir.exists()) {
                        parentDir.mkdirs()
                    }
                }

                // 确保文件存在
                if (!logFile.exists()) {
                    logFile.createNewFile()
                }

                val timestamp =
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                val logEntry = "[$timestamp] [$level] [$tag] $message\n"

                logFile.appendText(logEntry)
            } catch (e: Exception) {
                android.util.Log.e("LogCatcher", "写入日志文件失败: ${e.message}")
            }
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
