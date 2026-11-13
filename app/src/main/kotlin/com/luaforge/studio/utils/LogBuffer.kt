package com.luaforge.studio.utils

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 编辑器自身日志的结构化缓冲(日志页 / MCP 共用单一来源)。
 *
 * ## 为什么要单独做一层
 *
 * 已有两个落盘通道:
 * - app 模块的 [LogCatcher](构建、MCP、设置等 IDE 侧代码使用)
 * - core 模块的 [com.luaforge.studio.utils.RuntimeLog](LuaActivity 使用)
 *
 * 两者都只把**格式化后的文本行**追加进 `luaforge.log`,读取方(MCP 的
 * `get_logs`、设置页的「查看错误日志」)拿到的是一整块字符串,只能自己做
 * 行切分与 `[ERROR]` 子串匹配 —— 既无法按级别精确过滤(子串 `[ERROR]`
 * 会误命中正文里提到 ERROR 的 INFO 行),也没法按 tag 过滤或做增量刷新。
 *
 * 这里在**写入口**就保留结构化字段(时间 / 级别 / tag / 正文 / 异常栈),
 * 内存中维护定长环形缓冲,供 UI 与 MCP 直接消费;磁盘文本仍由原有通道负责,
 * 不改变 `luaforge.log` 的格式与外部契约。
 *
 * ## 边界
 *
 * - **只记录本编辑器自己的日志**:不读 logcat,不采集用户 APK 的运行日志
 *   (那属于调试控制台的职责,见 `console_logcat`);这正是"只记录本编辑器
 *   的日志"的要求。
 * - 缓冲有上限([MAX_ENTRIES]),超出后丢弃最旧的;UI 侧要长期留存请用导出。
 * - 全部方法线程安全:写入来自任意线程(含 native 回调后的 UI 线程),
 *   读取来自 Compose 重组与 MCP 的 IO 线程。
 */
object LogBuffer {

    /** 日志级别。`ordinal` 越大越严重,过滤时按 >= 阈值筛选。 */
    enum class Level(val label: String) {
        VERBOSE("V"),
        DEBUG("D"),
        INFO("I"),
        WARN("W"),
        ERROR("E");

        companion object {
            /** 从落盘文本里的 `[LEVEL]` 还原级别;认不出按 INFO。 */
            fun fromLabel(raw: String): Level = when (raw.trim().uppercase(Locale.ROOT)) {
                "V", "VERBOSE" -> VERBOSE
                "D", "DEBUG" -> DEBUG
                "I", "INFO", "PERMISSION", "FILE" -> INFO
                "W", "WARN", "WARNING" -> WARN
                "E", "ERROR", "FATAL" -> ERROR
                else -> INFO
            }
        }
    }

    /** 一条日志。 */
    data class Entry(
        /** 单调递增序号,用于 UI 稳定 key 与增量判重。 */
        val id: Long,
        /** 记录时间(毫秒)。 */
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String,
        /** 异常栈(无则 null)。 */
        val throwable: String? = null,
        /** 来源通道:`ide`(LogCatcher) / `runtime`(RuntimeLog)。 */
        val source: String = "ide"
    ) {
        /** `HH:mm:ss.SSS` 形式的短时间,UI 逐行展示用。 */
        val timeText: String
            get() = TIME_FORMAT.get()!!.format(Date(timestamp))
    }

    private const val MAX_ENTRIES = 5000

    private val lock = Any()
    private val entries = ArrayDeque<Entry>(MAX_ENTRIES)
    private var nextId = 1L

    /** 每条新日志的监听器(日志页用它触发增量刷新)。 */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    private val TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    }

    /** 追加一条日志。 */
    @JvmStatic
    fun add(level: Level, tag: String, message: String, throwable: Throwable? = null, source: String = "ide") {
        val entry = synchronized(lock) {
            val e = Entry(
                id = nextId++,
                timestamp = System.currentTimeMillis(),
                level = level,
                tag = tag,
                message = message,
                throwable = throwable?.let { it.stackTraceToString() },
                source = source
            )
            if (entries.size >= MAX_ENTRIES) entries.removeFirst()
            entries.addLast(e)
            e
        }
        // 监听器异常不能影响日志写入
        listeners.forEach {
            try {
                it()
            } catch (_: Throwable) {
            }
        }
        entry
    }

    /** 追加一条异常日志。 */
    @JvmStatic
    fun addError(tag: String, message: String, throwable: Throwable?, source: String = "ide") {
        add(Level.ERROR, tag, message, throwable, source)
    }

    /**
     * 供 core 模块 `RuntimeLog` 经反射调用的入口。
     *
     * 签名刻意保持"全基本类型 + String",避免反射侧还要拼装 Level 枚举 ——
     * core 里没有也不该有 `LogBuffer.Level` 的可见性。
     *
     * @param levelText `INFO` / `ERROR` 等落盘级别文本
     * @param timestamp 产生日志的时刻(由调用方在调用线程取,保证与磁盘行一致)
     */
    @JvmStatic
    fun addFromRuntime(levelText: String, tag: String, message: String, timestamp: Long) {
        val level = Level.fromLabel(levelText)
        val entry = synchronized(lock) {
            val e = Entry(
                id = nextId++,
                timestamp = timestamp,
                level = level,
                tag = tag,
                message = message,
                source = "runtime"
            )
            if (entries.size >= MAX_ENTRIES) entries.removeFirst()
            entries.addLast(e)
            e
        }
        listeners.forEach {
            try {
                it()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 取出满足条件的日志(按时间正序)。
     *
     * @param minLevel  最低级别(含);默认 VERBOSE 即全部
     * @param tagFilter tag 子串匹配,空表示不过滤
     * @param query     正文/tag 关键词子串匹配(忽略大小写),空表示不过滤
     * @param afterId   只取 id 大于它的条目(增量刷新用)
     * @param limit     最多返回条数,从最新的往回取
     */
    @JvmStatic
    @JvmOverloads
    fun query(
        minLevel: Level = Level.VERBOSE,
        tagFilter: String = "",
        query: String = "",
        afterId: Long = 0L,
        limit: Int = MAX_ENTRIES
    ): List<Entry> {
        val tagNeedle = tagFilter.trim()
        val textNeedle = query.trim()
        val snapshot = synchronized(lock) { entries.toList() }

        val filtered = snapshot.asSequence()
            .filter { it.id > afterId }
            .filter { it.level.ordinal >= minLevel.ordinal }
            .filter { tagNeedle.isEmpty() || it.tag.contains(tagNeedle, ignoreCase = true) }
            .filter {
                textNeedle.isEmpty() ||
                    it.message.contains(textNeedle, ignoreCase = true) ||
                    it.tag.contains(textNeedle, ignoreCase = true)
            }
            .toList()

        return if (filtered.size > limit) filtered.subList(filtered.size - limit, filtered.size)
        else filtered
    }

    /** 当前缓冲内的条数。 */
    @JvmStatic
    fun size(): Int = synchronized(lock) { entries.size }

    /** 各级别计数(UI 用于展示筛选徽标)。 */
    @JvmStatic
    fun countByLevel(): Map<Level, Int> {
        val snapshot = synchronized(lock) { entries.toList() }
        return Level.entries.associateWith { level -> snapshot.count { it.level == level } }
    }

    /** 已知的全部 tag(按出现次数降序,供 tag 下拉/自动补全)。 */
    @JvmStatic
    fun tags(): List<String> {
        val snapshot = synchronized(lock) { entries.toList() }
        return snapshot.groupingBy { it.tag }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
    }

    /** 清空内存缓冲(不影响磁盘日志)。 */
    @JvmStatic
    fun clear() {
        synchronized(lock) { entries.clear() }
        listeners.forEach {
            try {
                it()
            } catch (_: Throwable) {
            }
        }
    }

    /** 注册刷新回调;返回注销函数。 */
    @JvmStatic
    fun observe(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    /**
     * 把内存缓冲导出为文本(日志页「导出」用)。
     *
     * 与 `luaforge.log` 的行格式保持一致,便于与既有分析流程对接。
     */
    @JvmStatic
    fun exportText(minLevel: Level = Level.VERBOSE): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        val snapshot = synchronized(lock) { entries.toList() }
        return snapshot.asSequence()
            .filter { it.level.ordinal >= minLevel.ordinal }
            .joinToString("\n") { e ->
                buildString {
                    append("[${fmt.format(Date(e.timestamp))}]")
                    append(" [${e.level.name}]")
                    append(" [${e.tag}]")
                    append(" ${e.message}")
                    e.throwable?.let { append("\n").append(it) }
                }
            }
    }

    /**
     * 从 `luaforge.log` 末尾回填内存缓冲。
     *
     * 进程重启后内存缓冲是空的,而磁盘日志仍有历史。日志页打开时回填一次,
     * 用户能看到"这次启动前后"的连续性;只解析符合
     * `[时间] [级别] [tag] 正文` 的行,其余(异常栈续行)并入上一条。
     *
     * @param maxLines 最多回填的行数(从尾部取)
     */
    @JvmStatic
    @JvmOverloads
    fun backfillFromFile(maxLines: Int = 2000) {
        try {
            val file = File(LogFileAccess.LOG_FILE_PATH)
            if (!file.exists() || file.length() == 0L) return

            val text = LogFileAccess.readLog(maxChars = 512 * 1024)
            if (text.isBlank()) return

            val lines = text.lines().takeLast(maxLines)
            val parsed = ArrayList<Entry>(lines.size)
            val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            // 与既有行格式对应:[2026-01-01 10:00:00.000] [INFO] [Tag] 正文
            val head = Regex("^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})] \\[(\\w+)] \\[([^]]*)] (.*)$")

            var lastIndex = -1
            for (line in lines) {
                if (line.isBlank()) continue
                val m = head.find(line)
                if (m != null) {
                    val ts = try {
                        dateFmt.parse(m.groupValues[1])?.time ?: System.currentTimeMillis()
                    } catch (_: Exception) {
                        System.currentTimeMillis()
                    }
                    parsed.add(
                        Entry(
                            id = 0,
                            timestamp = ts,
                            level = Level.fromLabel(m.groupValues[2]),
                            tag = m.groupValues[3],
                            message = m.groupValues[4],
                            source = "runtime"
                        )
                    )
                    lastIndex = parsed.size - 1
                } else if (lastIndex >= 0) {
                    // 异常栈等续行:并入上一条
                    val prev = parsed[lastIndex]
                    parsed[lastIndex] = prev.copy(
                        throwable = (prev.throwable?.plus("\n") ?: "") + line
                    )
                }
            }

            if (parsed.isEmpty()) return
            synchronized(lock) {
                // 回填只补历史:已有同秒同 tag 同正文的条目就跳过,避免重复打开叠加
                val existing = entries.map { "${it.timestamp}/${it.tag}/${it.message}" }.toHashSet()
                for (e in parsed) {
                    val key = "${e.timestamp}/${e.tag}/${e.message}"
                    if (existing.contains(key)) continue
                    if (entries.size >= MAX_ENTRIES) entries.removeFirst()
                    entries.addLast(e.copy(id = nextId++))
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("LogBuffer", "回填磁盘日志失败: ${t.message}")
        }
    }

    /**
     * 安装日志桥:让既有两个落盘通道在写盘的同时也写入本缓冲。
     *
     * 只做"旁路采集",不改变任何通道原有的落盘行为与格式。
     */
    @JvmStatic
    fun install(context: Context) {
        // 引导一次磁盘历史回填,让启动即可查看上次会话
        backfillFromFile()
        android.util.Log.i("LogBuffer", "日志缓冲已就绪(${size()} 条历史)")
    }
}
