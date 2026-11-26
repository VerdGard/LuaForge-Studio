package com.luaforge.studio.ui.crash

import android.content.Context
import android.os.Build
import com.luaforge.studio.utils.LogCatcher
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 崩溃记录:把未捕获异常连同完整堆栈落盘,供「设置 → 崩溃记录」列出。
 *
 * ## 与 CrashManager / CrashLogActivity 的分工
 *
 * - [CrashManager] 负责**当下这一屏**:拼 Intent、启 [CrashLogActivity] 展示、杀进程。
 *   它的 Intent 数据随进程死亡一起消失,历史崩溃因此无从回看。
 * - 本对象只负责**持久化**:把同样的现场写成文件,退出后仍能在设置页翻到。
 *
 * 因职责不同,安装顺序有硬要求:必须先 `CrashManager.install`,再 `install(this)`。
 * 因为 CrashManager 内部会 `Thread.setDefaultUncaughtExceptionHandler`,后装者才能
 * 把上一个处理器作为 delegate 串起来 —— 反过来装会被直接覆盖,现场就丢了。
 *
 * ## 为什么不复用 CrashManager 的 Intent
 *
 * [CrashManager] 的 Intent 里已经有异常类型/线程/上下文,但那是**给 UI 用的**;
 * 落盘需要的额外信息(时间戳精确到秒的文件名、版本、设备、诊断)在这里一次采全,
 * 并且**不依赖 UI 是否成功启动** —— 崩溃页起不来时文件照样在。
 *
 * ## 落盘位置
 *
 * `/storage/emulated/0/LuaForge-Studio/crash/`,与 `luaforge.log` 同根,便于
 * 文件管理器或 adb 直接取。外部目录不可写(未授予「所有文件访问权限」)时
 * 自动回退到应用私有目录 `filesDir/crash/`,不丢记录。读取侧 [CrashStore]
 * 同时扫描两处,与日志的 `LogFileAccess` 保持同一套降级思路。
 *
 * ## 文件格式
 *
 * 纯文本,首行是唯一的元数据行(便于列表页免解析正文):
 *
 * ```
 * #LUAForgeCrash v1 time=<毫秒> type=<异常类名>
 * [Time] 2026-11-26 22:30:01
 * [App] LuaForge-Studio 1.7.0 (10)
 * [Device] Pixel 7 / Android 14 (SDK 34)
 * [Thread] main (ID:1)
 * ...
 * [Stack Trace]
 * java.lang.RuntimeException: ...
 * ```
 */
object CrashRecorder {

    private const val TAG = "CrashRecorder"

    /** 目录名(位于 `LuaForge-Studio/` 与私有 `filesDir/` 之下同名)。 */
    const val DIR_NAME = "crash"

    /** 元数据行前缀与版本,读取侧据此判断格式。 */
    const val META_PREFIX = "#LUAForgeCrash v1"

    /** 单条记录最大字符数,防止 insane 场景写出几百 MB 的文件。 */
    private const val MAX_RECORD_CHARS = 512 * 1024

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** 应用 Context(install 时注入,供落盘时解析私有目录)。 */
    @Volatile
    private var appContext: Context? = null

    /** 已安装保护。 */
    @Volatile
    private var installed = false

    /** 记录落盘后的回调(设置页用它即时刷新列表)。 */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * 安装全局未捕获异常处理器。
     *
     * 幂等;重复调用不会叠加多层包装。
     */
    @JvmStatic
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        appContext = context.applicationContext

        val delegate = Thread.getDefaultUncaughtExceptionHandler()
        if (delegate is RecordingHandler) return

        Thread.setDefaultUncaughtExceptionHandler(RecordingHandler(delegate))
        LogCatcher.i(TAG, "崩溃记录已就绪,目录: ${crashDir(context).absolutePath}")
    }

    /** 注册记录落盘回调;返回注销函数。 */
    @JvmStatic
    fun observe(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    /**
     * 主动记录一次异常(供非未捕获场景复用,例如 Lua 侧 `onError`)。
     *
     * @return 落盘文件;写失败返回 null
     */
    @JvmStatic
    fun record(throwable: Throwable, thread: Thread = Thread.currentThread()): File? {
        val context = appContext ?: return null
        return try {
            val text = buildRecord(context, throwable, thread)
            val file = File(crashDir(context), fileName(System.currentTimeMillis()))
            file.writeText(text, Charsets.UTF_8)
            notifyListeners()
            LogCatcher.i(TAG, "崩溃记录已写入: ${file.absolutePath}")
            file
        } catch (t: Throwable) {
            // 记录崩溃的过程本身不能再抛异常
            runCatching { LogCatcher.eThrowable(TAG, "写入崩溃记录失败", t) }
            null
        }
    }

    // ------------------------------------------------------------------
    // 落盘内容
    // ------------------------------------------------------------------

    /** 拼装一条完整记录。 */
    private fun buildRecord(context: Context, throwable: Throwable, thread: Thread): String {
        val now = System.currentTimeMillis()
        val typeName = throwable.javaClass.name

        val stack = StringWriter().also { sw ->
            throwable.printStackTrace(PrintWriter(sw))
        }.toString()

        val sb = StringBuilder(4096)
        sb.append(META_PREFIX)
            .append(" time=").append(now)
            .append(" type=").append(typeName)
            .append('\n')

        sb.append("[Time] ").append(timeFormat.format(Date(now))).append('\n')

        sb.append("[App] ").append(appLabel(context)).append('\n')
        sb.append("[Device] ")
            .append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" / Android ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")

        // 用弃用的 thread.id 而不是 thread.threadId():后者是 API 36 才有的方法,
        // 在 minSdk 24 的老系统上会 NoSuchMethodError —— 崩溃记录器自己先崩就白写了。
        @Suppress("DEPRECATION")
        val threadId = thread.id
        sb.append("[Thread] ").append(thread.name)
            .append(" (ID:").append(threadId).append(")\n")

        sb.append("[Exception Type]\n").append(typeName).append("\n\n")

        // cause 链单独展开:printStackTrace 只会打印 "Caused by",顶层类型仍不显眼
        val chain = generateSequence(throwable) { it.cause }.drop(1).toList()
        if (chain.isNotEmpty()) {
            sb.append("[Cause Chain]\n")
            chain.forEach { c -> sb.append("  <- ").append(c.javaClass.name).append(": ").append(c.message ?: "").append('\n') }
            sb.append('\n')
        }

        sb.append("[Stack Trace]\n").append(stack)

        if (sb.length > MAX_RECORD_CHARS) {
            sb.setLength(MAX_RECORD_CHARS)
            sb.append("\n... (记录超过 ").append(MAX_RECORD_CHARS).append(" 字符,已截断)\n")
        }
        return sb.toString()
    }

    /** 应用名 + 版本名/版本号;取不到时降级为包名。 */
    private fun appLabel(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${context.getString(com.luaforge.studio.R.string.app_name)} ${info.versionName} ($code)"
    } catch (_: Throwable) {
        context.packageName
    }

    /**
     * 文件名:精确到秒的本地时间 + 毫秒尾数。
     *
     * 秒级时间戳是列表页要展示的粒度;追加 3 位毫秒是为了**同一秒内连续崩溃**
     * 不互相覆盖(崩溃后立刻重启再崩是常见现象,尤其原生库场景)。
     */
    private fun fileName(now: Long): String {
        val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "crash_${fmt.format(Date(now))}-${(now % 1000).toString().padStart(3, '0')}.log"
    }

    // ------------------------------------------------------------------
    // 目录
    // ------------------------------------------------------------------

    /** 记录目录(外部优先,不可写时回退私有目录)。 */
    @JvmStatic
    fun crashDir(context: Context): File {
        val external = File(EXTERNAL_CRASH_DIR)
        val parent = external.parentFile
        if (parent != null && !parent.exists()) runCatching { parent.mkdirs() }
        if (parent != null && parent.exists() && parent.canWrite()) {
            if (!external.exists()) runCatching { external.mkdirs() }
            if (external.exists()) return external
        }
        val fallback = File(context.filesDir, DIR_NAME)
        if (!fallback.exists()) runCatching { fallback.mkdirs() }
        return fallback
    }

    /** 外部记录目录(与 `luaforge.log` 同根)。 */
    const val EXTERNAL_CRASH_DIR = "/storage/emulated/0/LuaForge-Studio/$DIR_NAME"

    /** 私有记录目录(外部不可写时的回退位置)。 */
    @JvmStatic
    fun privateCrashDir(context: Context): File = File(context.filesDir, DIR_NAME)

    private fun notifyListeners() {
        listeners.forEach {
            try {
                it()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 转发器:先落盘,再交回原处理器。
     *
     * 落盘必须在前面 —— 原处理器([CrashManager])最后会 `killProcess`,
     * 之后再写就来不及了。异常一律吞掉,不能让记录环节改变崩溃行为。
     */
    private class RecordingHandler(
        private val delegate: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            try {
                record(throwable, thread)
            } catch (_: Throwable) {
            }
            delegate?.uncaughtException(thread, throwable)
        }
    }
}
