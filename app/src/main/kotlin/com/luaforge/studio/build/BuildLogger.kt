package com.luaforge.studio.build

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单次构建的过程日志。
 *
 * ## 为什么单独写一份
 *
 * 构建失败时最需要的信息是**哪一步失败、从哪条代码路径进来的**,而全局
 * `luaforge.log` 有两类问题:
 *
 * 1. 它是所有功能共用的流水,构建的几百行会和 MCP 请求、Lua 运行输出交织,
 *    事发后很难还原"这一次构建"的完整时间线;
 * 2. 它不含调用栈 —— `ApkBuilder` 的 130 多处日志只写消息,而构建失败常常
 *    是**深层调用**抛出的(ECJ 编译、D8 转换、R8 重写、zip 打包),
 *    没有 stack trace 就只能靠猜。
 *
 * 因此每次构建开一个独立文件:`/storage/emulated/0/LuaForge-Studio/buildlogs/`
 * 下按**到秒**的时间戳命名。内容包含:
 *
 * - 构建入参与环境(内存、ABI、模式、开关);
 * - 每一步的开始 / 结束 / 耗时,失败时标记失败步骤;
 * - 出错处的完整堆栈(含 Caused by 链);
 * - 结尾的汇总(成功路径或失败原因)。
 *
 * ## 写入策略
 *
 * 直接追加写 + 立即 flush。构建日志量小(几百行),而"崩溃/被杀时日志必须已经
 * 在磁盘上"比少量 IO 开销重要得多 —— 异步缓冲写入会在进程被系统杀掉时丢掉
 * 最后也是最关键的那几行。同时所有写入都包在 try/catch 里:日志本身绝不能
 * 影响构建。
 */
object BuildLogger {

    private const val TAG = "BuildLogger"

    /** 构建日志目录(与 luaforge.log 同级,便于一起收集)。 */
    private const val LOG_DIR = "/storage/emulated/0/LuaForge-Studio/buildlogs"

    /** 进程内当前活跃的构建日志。 */
    private var current: Session? = null

    /** 一次构建的日志会话。 */
    class Session internal constructor(val file: File) {
        private val lock = Any()
        private var closed = false

        internal fun write(line: String) {
            synchronized(lock) {
                if (closed) return
                try {
                    FileOutputStream(file, true).use { out ->
                        out.write(line.toByteArray(Charsets.UTF_8))
                        out.write('\n'.code)
                        out.flush()
                        // fsync:进程被杀也不丢已写内容
                        out.fd.sync()
                    }
                } catch (_: Throwable) {
                    // 日志失败不影响构建
                }
            }
        }

        internal fun close() {
            synchronized(lock) { closed = true }
        }
    }

    /**
     * 开始一次构建日志。
     *
     * 目录不可写(未授予文件权限)时静默降级到应用私有目录,保证仍有据可查。
     *
     * @param context 仅用于私有目录回退
     * @param title   构建标题(项目名 / 输出路径),写进文件头
     */
    @JvmStatic
    fun start(context: Context, title: String): Session {
        val dir = resolveDir(context)
        dir.mkdirs()

        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        // 同一秒内重复构建时加序号,避免互相覆盖
        var file = File(dir, "build_$stamp.log")
        var index = 2
        while (file.exists()) {
            file = File(dir, "build_${stamp}_$index.log")
            index++
        }

        val session = Session(file)
        current = session

        session.write("=".repeat(72))
        session.write("构建开始: $stamp")
        session.write("标题: $title")
        session.write("日志: ${file.absolutePath}")
        session.write("线程: ${Thread.currentThread().name} (id=${Thread.currentThread().id})")
        session.write("=".repeat(72))
        return session
    }

    /** 结束当前会话(幂等)。 */
    @JvmStatic
    fun finish(session: Session?) {
        session?.let {
            it.write("=".repeat(72))
            it.close()
        }
        if (current === session) current = null
    }

    /** 当前是否有活跃会话。 */
    @JvmStatic
    fun active(): Session? = current

    /**
     * 记录一步的开始。
     *
     * @return 该步骤的时间戳(nanos),配合 [stepEnd] 计算耗时
     */
    @JvmStatic
    fun stepBegin(session: Session?, name: String, detail: String = ""): Long {
        session ?: return System.nanoTime()
        val sb = StringBuilder()
        sb.append("\n>>> [STEP] ").append(name)
        if (detail.isNotEmpty()) sb.append(" — ").append(detail)
        session.write(sb.toString())
        return System.nanoTime()
    }

    /** 记录一步成功结束。 */
    @JvmStatic
    fun stepEnd(session: Session?, name: String, beginNanos: Long, detail: String = "") {
        session ?: return
        val ms = (System.nanoTime() - beginNanos) / 1_000_000
        val sb = StringBuilder()
        sb.append("<<< [ OK ] ").append(name).append("  耗时 ").append(ms).append("ms")
        if (detail.isNotEmpty()) sb.append("  ").append(detail)
        session.write(sb.toString())
    }

    /**
     * 记录一步失败,并附带**完整堆栈**。
     *
     * @param throwable 失败原因;为 null 时也写调用位置(便于定位是哪条路径返回了失败)
     */
    @JvmStatic
    fun stepFail(session: Session?, name: String, throwable: Throwable? = null, reason: String = "") {
        session ?: return
        val sb = StringBuilder()
        sb.append("!!! [FAIL] ").append(name)
        if (reason.isNotEmpty()) sb.append(" — ").append(reason)
        session.write(sb.toString())
        sb.clear()
        // 记录**调用位置**:跳过 BuildLogger 自身帧,让第一帧就是构建代码。
        // 这样"某步返回 false 而非抛异常"的情况也能定位到具体调用点。
        Throwable().stackTrace
            .dropWhile { it.className.contains("BuildLogger") }
            .take(24)
            .forEach { session.write("        at $it") }
        throwable?.let { session.write(formatThrowable(it)) }
    }

    /** 普通信息行。 */
    @JvmStatic
    fun info(session: Session?, message: String) {
        session?.write("      $message")
    }

    /** 警告行。 */
    @JvmStatic
    fun warn(session: Session?, message: String) {
        session?.write("  [WARN] $message")
    }

    /**
     * 失败行 + 堆栈(不改变步骤状态)。
     *
     * 供 `catch` 块里记录真实异常用。
     */
    @JvmStatic
    fun error(session: Session?, message: String, throwable: Throwable? = null) {
        session ?: return
        session.write("  [ERROR] $message")
        throwable?.let { session.write(formatThrowable(it)) }
    }

    /**
     * 把 [Throwable] 连同 `Caused by` 链格式化成文本。
     *
     * 直接 `stackTraceToString()` 在部分 JDK 实现下只给主异常的栈,
     * `Caused by` 需要自己串 —— 而构建失败的真实原因几乎总在 Caused by 里
     * (例如 `RuntimeException: 构建失败` ← `IOException: No space left`)。
     */
    private fun formatThrowable(t: Throwable): String {
        val sb = StringBuilder()
        var cur: Throwable? = t
        var depth = 0
        val seen = HashSet<Throwable>()
        while (cur != null && depth < 8 && seen.add(cur)) {
            val head = if (depth == 0) "" else "Caused by: "
            sb.append("      ").append(head)
                .append(cur.javaClass.name)
            cur.message?.let { sb.append(": ").append(it) }
            sb.append('\n')
            cur.stackTrace.take(60).forEach { e ->
                sb.append("        at ").append(e.toString()).append('\n')
            }
            cur = cur.cause
            depth++
        }
        return sb.toString().trimEnd()
    }

    /**
     * 选定日志目录。
     *
     * 外部目录要 `MANAGE_EXTERNAL_STORAGE` 才可写;拿不到权限时退到
     * `filesDir/buildlogs` —— 与日志主文件同样的取舍:宁可换地方,
     * 也不能"构建失败但没有任何记录"。
     */
    private fun resolveDir(context: Context): File {
        val external = File(LOG_DIR)
        val ok = try {
            if (!external.exists()) external.mkdirs()
            external.isDirectory && external.canWrite()
        } catch (_: Throwable) {
            false
        }
        return if (ok) external else File(context.filesDir, "buildlogs")
    }

    /** 构建日志目录(供界面/MCP 展示)。 */
    @JvmStatic
    fun logDir(context: Context): File = resolveDir(context)
}
