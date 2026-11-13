package com.luaforge.studio.utils

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

/**
 * 本进程 logcat 采集器 —— 让「日志」页看到**本程序的全部日志**。
 *
 * ## 为什么需要它
 *
 * 只靠 [LogCatcher] / `RuntimeLog` 两个埋点是"被动收集":凡是没被显式埋点的
 * 代码路径(第三方库、AndroidX、core 里的原生回调、`System.out.println`、
 * 未捕获异常在框架层的打印…)**都不会出现在日志里**。用户因此看到"日志页
 * 一直是空的",而问题恰恰出在那些没有埋点的地方。
 *
 * 这里改为**主动抓取 logcat**:以 `--pid=<本进程>` 过滤,把系统日志缓冲区里
 * 属于本进程的每一行都读进来。这是唯一能覆盖"全部"的手段 —— 所有
 * `android.util.Log.*` 输出最终都汇入同一条缓冲区。
 *
 * ## 权限与降级
 *
 * Android 4.1 起普通应用默认**无权**读取不属于自己的日志,但读**自己进程**的
 * 日志在多数 ROM 上是允许的(`logcat --pid` 只返回本 pid 的行)。仍可能遇到:
 *
 * - 厂商 ROM 收紧 SELinux,`exec` 直接抛异常;
 * - 进程被 deny 后 `readLine` 立刻返回 null。
 *
 * 因此分三级降级,任一成功即停止下降:
 *
 * 1. `logcat --pid=<pid> -v threadtime`  (最完整)
 * 2. `logcat -d --pid=<pid>` 轮询(单次 dump,兼容不支持流式的实现)
 * 3. 仅靠 [LogCatcher] / `RuntimeLog` 埋点(功能退回改造前的水平)
 *
 * 降级原因会作为一条 WARN 写进 [LogBuffer],避免"为什么只有几条日志"无从得知。
 *
 * ## 为什么不用 `System.setOut`
 *
 * 重定向 `System.out` 只能拿到 `println`,拿不到 `Log.d/w/e`,也无法回溯已经
 * 输出过的行;而且会把 IDE 自己 stdout 的语义改掉(某些库会探测它是否为
 * tty)。抓 logcat 是正交的,不改任何现有行为。
 */
object LogcatCollector {

    private const val TAG = "LogcatCollector"

    /** 采集线程。 */
    private var thread: Thread? = null

    /** 采集用的子进程,停止时销毁。 */
    @Volatile
    private var process: java.lang.Process? = null

    /** 是否已启动(幂等保护)。 */
    @Volatile
    private var running = false

    /**
     * 本进程 pid。
     *
     * 用 [Process.myPid] 而不是解析 `/proc/self`:后者在某些沙箱里不可读,
     * 而 myPid 是框架直接给的。
     */
    private val pid: Int get() = Process.myPid()

    /**
     * 启动采集(幂等)。
     *
     * @param context 仅用于日志/诊断,不做权限申请 —— 见类注释的权限说明
     */
    @JvmStatic
    @Synchronized
    fun start(context: Context) {
        if (running) return
        running = true

        val t = Thread({ runLoop() }, "LuaForgeLogcat")
        t.isDaemon = true
        thread = t
        t.start()
    }

    /** 停止采集(退出前调用,或测试用)。 */
    @JvmStatic
    @Synchronized
    fun stop() {
        running = false
        try {
            process?.destroy()
        } catch (_: Throwable) {
        }
        process = null
        thread?.interrupt()
        thread = null
    }

    /** 采集是否在运行。 */
    @JvmStatic
    fun isRunning(): Boolean = running

    // ------------------------------------------------------------------
    // 采集主循环
    // ------------------------------------------------------------------

    private fun runLoop() {
        if (streamMode()) return
        // 流式失败:尝试轮询模式。若也失败,保留既有埋点行为并如实记录原因。
        if (pollMode()) return
        LogBuffer.add(
            LogBuffer.Level.WARN,
            TAG,
            "无法读取本进程 logcat(ROM 限制),日志页仅显示已埋点的日志"
        )
    }

    /**
     * 流式模式:`logcat --pid=N` 持续输出。
     *
     * @return true 表示成功接管(直到进程结束)
     */
    private fun streamMode(): Boolean {
        val proc = try {
            ProcessBuilder(
                "/system/bin/logcat",
                "--pid=$pid",
                "-v", "threadtime",
                "-T", "1"
            ).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            LogBuffer.add(
                LogBuffer.Level.DEBUG,
                TAG,
                "logcat 流式启动失败: ${t.javaClass.simpleName}"
            )
            return false
        }
        process = proc

        var lines = 0
        try {
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                while (running) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    if (parseThreadtime(line)) lines++
                    // 一行都没能解析出来说明格式/权限与预期不符,提前退出走降级
                    if (lines == 0 && looksLikeDenied(line)) break
                }
            }
        } catch (_: Throwable) {
            // 中断等:交给下面统一判断
        } finally {
            try {
                proc.destroy()
            } catch (_: Throwable) {
            }
            process = null
        }

        if (lines == 0) {
            LogBuffer.add(LogBuffer.Level.DEBUG, TAG, "logcat 流式未产出可解析行,尝试轮询")
            return false
        }
        return true
    }

    /**
     * 轮询模式:周期性 `logcat -d --pid=N`,按已见行数做增量。
     *
     * 用于"能读但流式不工作"的 ROM。间隔取 1.5s —— 足够及时,又不会因为
     * 频繁 fork 进程拖累设备。
     */
    private fun pollMode(): Boolean {
        var lastCount = 0
        var failures = 0
        var first = true

        while (running) {
            val proc = try {
                ProcessBuilder(
                    "/system/bin/logcat",
                    "-d",
                    "--pid=$pid",
                    "-v", "threadtime"
                ).redirectErrorStream(true).start()
            } catch (t: Throwable) {
                return false
            }

            var parsed = 0
            var total = 0
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    // 跳过已处理的前 lastCount 行,只解析新增部分
                    var skipped = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        total++
                        if (skipped < lastCount) {
                            skipped++
                            continue
                        }
                        if (parseThreadtime(line)) parsed++
                    }
                }
            } catch (_: Throwable) {
                failures++
            } finally {
                try {
                    proc.destroy()
                } catch (_: Throwable) {
                }
            }

            if (first) {
                first = false
                // 首轮必须真的读到东西,否则说明这条路也不通
                if (total == 0) {
                    failures++
                    if (failures >= 1) return false
                }
            }

            lastCount = total
            if (parsed == 0) failures++ else failures = 0
            if (failures >= 3) return false

            try {
                Thread.sleep(1500)
            } catch (_: InterruptedException) {
                return true
            }
        }
        return true
    }

    // ------------------------------------------------------------------
    // 行解析
    // ------------------------------------------------------------------

    /**
     * 解析 `-v threadtime` 格式的一行并写入 [LogBuffer]。
     *
     * 格式:`MM-DD HH:MM:SS.mmm  PID  TID L TAG     : message`
     * 例:`11-13 18:52:01.123  4321  4321 I ActivityManager: Start proc`
     *
     * @return 是否解析成功
     */
    private fun parseThreadtime(line: String): Boolean {
        // 前 18 个字符是 "MM-DD HH:MM:SS.mmm",第 19 起是 pid/tid/级别/tag
        if (line.length < 32) return false

        // 级别:threadtime 里是单个字符,位置在 pid/tid 之后的一个独立列
        // 为避免对列宽做脆弱假设,这里定位 : 之前的最后一个单字母列
        val sep = line.indexOf(':')
        if (sep <= 0) return false

        val head = line.substring(0, sep)
        val message = line.substring(sep + 1).trimStart()
        if (message.isEmpty()) return false

        // head 末尾形如 "... I SomeTag"
        val tokens = head.split(' ').filter { it.isNotEmpty() }
        if (tokens.size < 3) return false

        val tag = tokens.last()
        val levelToken = tokens[tokens.size - 2]
        val level = when (levelToken.firstOrNull()?.uppercaseChar()) {
            'V' -> LogBuffer.Level.VERBOSE
            'D' -> LogBuffer.Level.DEBUG
            'I' -> LogBuffer.Level.INFO
            'W' -> LogBuffer.Level.WARN
            'E', 'F' -> LogBuffer.Level.ERROR
            else -> return false
        }

        // 本采集器自己的日志也要收(它记录的是启动/降级等诊断信息),
        // 但避免把"读取失败"这类自身告警刷屏:同 tag 同级别去重交给 LogBuffer 容量控制即可。
        LogBuffer.add(level, tag, message)
        return true
    }

    /** 判断一行是否是权限拒绝类错误(用于提前放弃该模式)。 */
    private fun looksLikeDenied(line: String): Boolean {
        val lower = line.lowercase(Locale.ROOT)
        return lower.contains("permission denied") ||
            lower.contains("not allowed") ||
            lower.contains("operation not permitted")
    }
}
