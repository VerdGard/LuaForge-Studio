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
        // 让读取路径也能找到私有目录里的日志
        LogFileAccess.attach(context)
        // 日志开关不再由用户设置控制:日志页要"记录本程序所有日志",
        // 而一个可被关掉的开关会让用户在关掉后再也拿不到任何现场
        // (故障往往就发生在那之后)。保留字段只为兼容旧配置。
        updateConfig(
            LogConfigState(
                isLogEnabled = true,
                logFilePath = resolveLogFilePath(context),
                isLoaded = true
            )
        )

        // **不再清空日志文件**。
        //
        // 旧实现在每次启动时 truncate,结果:进程崩溃后重启,崩溃现场的日志
        // 恰好被清掉 —— 最需要它的时刻什么都没有。现在改为追加 + 尾部截断
        // (见 truncateIfNeeded),历史由用户主动清空。
        i("LogCatcher", "日志系统初始化完成(追加模式,不清空历史)")
    }

    /**
     * 选定日志文件路径。
     *
     * 历史写死在 `/storage/emulated/0/LuaForge-Studio/luaforge.log`。该路径在
     * 用户尚未授予「所有文件访问权限」时**不可写**(Android 11+ 分存储),
     * 于是所有日志静默丢失 —— 表现正是"日志页一直空"。
     *
     * 现在优先用应用私有目录(`filesDir/logs`),它一定可写、卸载即清、且不需要
     * 任何权限;仅当外部目录确实可写时才继续沿用旧路径,保证与既有
     * `luaforge.log` 的读取方(MCP `get_logs`、崩溃上报)保持兼容。
     *
     * @return 可写的日志文件绝对路径
     */
    private fun resolveLogFilePath(context: Context): String {
        val legacy = File(LogFileAccess.LEGACY_LOG_FILE_PATH)
        val legacyUsable = try {
            val dir = legacy.parentFile
            if (dir != null && !dir.exists()) dir.mkdirs()
            dir != null && dir.exists() && dir.canWrite()
        } catch (_: Throwable) {
            false
        }
        if (legacyUsable) return legacy.absolutePath

        // 回退到私有目录:一定可用
        val fallback = LogFileAccess.privateLogFile(context)
        fallback.parentFile?.mkdirs()
        return fallback.absolutePath
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

    /**
     * 记录任意 [Throwable](含 `Error`)。
     *
     * 原有 [e] 只接 `Exception`,于是 `OutOfMemoryError` /
     * `ExceptionInInitializerError` / `StackOverflowError` 这类 **Error**
     * 无法落盘 —— 而构建期 ECJ/D8 恰恰会抛这些。加这个重载让它们也能
     * 连同堆栈一起记入日志与结构化缓冲。
     */
    @JvmStatic
    fun eThrowable(tag: String, message: String, throwable: Throwable?) {
        if (throwable == null) {
            e(tag, message, null as Exception?)
            return
        }
        android.util.Log.e(tag, message, throwable)
        if (shouldLog()) {
            writeToFile("ERROR", tag, "$message - ${throwable.message}\n${throwable.stackTraceToString()}")
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

    /**
     * 是否应落盘。
     *
     * 只看是否完成初始化 —— 不再尊重 `isLogEnabled`。理由见 [init]:
     * 日志是排障基础设施,不该提供"关掉"的开关;`isLogEnabled` 仅为兼容
     * 旧配置字段而保留。
     */
    private fun shouldLog(): Boolean = isInitialized

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

    /** 传统日志路径(需要外部存储写权限)。 */
    const val LEGACY_LOG_FILE_PATH = "/storage/emulated/0/LuaForge-Studio/luaforge.log"

    /**
     * 当前生效的日志路径。
     *
     * 保留为 `const` 会让"未授权时日志写不进去"这件事无从降级,所以这里
     * 改成运行期解析,并把传统路径作为只读兼容入口。新代码请用
     * [activeLogFile] / [readLog],不要直接拼路径。
     */
    @Deprecated("改用 activeLogFile(context)", ReplaceWith("activeLogFile(context)"))
    const val LOG_FILE_PATH = LEGACY_LOG_FILE_PATH

    /** 应用私有目录下的日志文件(一定可写,无需任何权限)。 */
    fun privateLogFile(context: android.content.Context): File =
        File(File(context.filesDir, "logs"), "luaforge.log")

    /**
     * 当前实际使用的日志文件。
     *
     * 解析顺序:外部路径可写 -> 用它;否则用私有目录。与 [LogCatcher] 的选择
     * 保持一致,避免"写在一处、读在另一处"。
     */
    fun activeLogFile(context: android.content.Context): File {
        val legacy = File(LEGACY_LOG_FILE_PATH)
        val legacyUsable = try {
            val dir = legacy.parentFile
            dir != null && dir.exists() && dir.canWrite()
        } catch (_: Throwable) {
            false
        }
        return if (legacyUsable) legacy else privateLogFile(context)
    }

    /**
     * 找出当前**存在且有内容**的日志文件。
     *
     * 日志可能落在外部路径(有权限时)或私有目录(回退时),调用方不该假定只有一个。
     * 外部优先 —— 它与用户/工具脚本直接查看的路径一致。
     *
     * @return 命中的文件;都不存在时返回 null
     */
    fun existingLogFile(context: android.content.Context? = null): File? {
        val files = mutableListOf(File(LEGACY_LOG_FILE_PATH))
        try {
            val ctx = context ?: currentAppContext
            if (ctx != null) files.add(privateLogFile(ctx))
        } catch (_: Throwable) {
        }
        return files.firstOrNull { it.exists() && it.length() > 0 }
            ?: files.firstOrNull { it.exists() }
    }

    /**
     * 读取日志文本。
     *
     * @param errorsOnly 为 true 时只返回含 [ERROR] 级别的行(用于「查看错误日志」)。
     * @param maxChars   截取的最大字符数(从尾部保留最新内容)。
     */
    fun readLog(errorsOnly: Boolean = false, maxChars: Int = 200_000): String {
        return try {
            val file = candidateFiles().firstOrNull { it.exists() && it.length() > 0 }
                ?: return ""
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

    /**
     * 可能存放日志的文件列表(外部路径优先)。
     *
     * 读取方不该假定只有一处:用户可能在授权前用私有目录,授权后切到外部目录,
     * 两个文件都可能有内容。读取时取第一个非空的。
     */
    private fun candidateFiles(): List<File> {
        val out = mutableListOf(File(LEGACY_LOG_FILE_PATH))
        try {
            val ctx = currentAppContext
            if (ctx != null) out.add(privateLogFile(ctx))
        } catch (_: Throwable) {
        }
        return out
    }

    /** 由 [LogCatcher.init] 注入的应用 Context,供无参读取路径使用。 */
    @Volatile
    private var currentAppContext: android.content.Context? = null

    /** 记录应用 Context(供读取旧路径之外的私有日志)。 */
    internal fun attach(context: android.content.Context) {
        currentAppContext = context.applicationContext
    }

    /** 清空所有候选日志文件(保留文件本身)。 */
    fun clear(): Boolean {
        var ok = false
        for (file in candidateFiles()) {
            try {
                if (file.exists()) file.writeText("", Charsets.UTF_8) else {
                    file.parentFile?.mkdirs()
                    file.createNewFile()
                }
                ok = true
            } catch (_: Throwable) {
            }
        }
        return ok
    }
}
