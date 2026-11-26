package com.luaforge.studio.ui.crash

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一条崩溃记录的元信息(列表页只需要这些,不必读正文)。
 *
 * 列表按时间倒序列出,每行展示到**秒**;点击才加载完整正文。
 */
data class CrashEntry(
    /** 记录文件。 */
    val file: File,
    /** 崩溃时刻(毫秒)。文件名即时间,解析不出来时退回文件 mtime。 */
    val timestamp: Long,
    /** `yyyy-MM-dd HH:mm:ss`(秒级,列表展示用)。 */
    val timeText: String,
    /** 异常类型全名(元数据行取不到时为 "Unknown")。 */
    val typeName: String,
    /** 文件大小(字节),用于在列表里给出体量提示。 */
    val size: Long
)

/**
 * 崩溃记录的读取 / 删除。
 *
 * ## 只有一个数据源
 *
 * 记录由 [CrashRecorder] 写入,这里只读不写 —— 单一写入者,避免并发写同一文件。
 *
 * ## 为什么按文件名解析时间而不是读文件头
 *
 * 列表页要给几十上百条记录排序,逐条读首行做 IO 明显更慢,而文件名里已带
 * `yyyyMMdd-HHmmss-SSS`。元数据行仍会在**打开详情**时解析,用来拿异常类型 ——
 * 那时只读一个文件,开销可忽略。
 *
 * ## 两个目录
 *
 * 外部目录(`LuaForge-Studio/crash/`)与私有目录(`filesDir/crash/`)都可能有用。
 * 历史遗留:早期外部不可写时崩在私有目录,授权后新记录落到外部。读取合并两处,
 * 删除也两处一起清,避免"删了还在"。
 */
object CrashStore {

    private val fileNameFormat = SimpleDateFormat("yyyyMMdd-HHmmssSSS", Locale.US)
    private val displayFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** 文件名:`crash_yyyyMMdd-HHmmss-SSS.log`。 */
    private val fileNameRegex = Regex("""^crash_(\d{8}-\d{6})-(\d{3})\.log$""")

    /**
     * 列出全部记录(时间倒序,最新在前)。
     *
     * @param context 用于解析私有目录
     */
    fun list(context: Context): List<CrashEntry> {
        val entries = ArrayList<CrashEntry>()
        for (dir in candidateDirs(context)) {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".log") } ?: continue
            for (f in files) {
                val ts = parseTime(f)
                entries.add(
                    CrashEntry(
                        file = f,
                        timestamp = ts,
                        timeText = displayFormat.format(Date(ts)),
                        typeName = "",           // 列表不读正文,详情页再补
                        size = f.length()
                    )
                )
            }
        }
        // 同一文件可能同时存在于两处(极端迁移场景):按绝对路径去重后保留外部那份
        return entries
            .distinctBy { it.file.name + it.timestamp }
            .sortedByDescending { it.timestamp }
    }

    /**
     * 读取一条记录的完整正文。
     *
     * @return 文件全文;读失败返回空串
     */
    fun read(entry: CrashEntry): String = try {
        entry.file.readText(Charsets.UTF_8)
    } catch (_: Throwable) {
        ""
    }

    /** 从记录正文里取出异常类型(元数据行 `type=` 字段);取不到返回 null。 */
    fun typeOf(entry: CrashEntry): String? = try {
        entry.file.useLines { seq ->
            seq.firstOrNull { it.startsWith(CrashRecorder.META_PREFIX) }
                ?.substringAfter("type=", "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
    } catch (_: Throwable) {
        null
    }

    /** 删除单条记录。 */
    fun delete(entry: CrashEntry): Boolean = try {
        entry.file.delete()
    } catch (_: Throwable) {
        false
    }

    /** 清空全部记录。 */
    fun clear(context: Context): Int {
        var removed = 0
        for (dir in candidateDirs(context)) {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".log") } ?: continue
            for (f in files) {
                if (runCatching { f.delete() }.getOrDefault(false)) removed++
            }
        }
        return removed
    }

    /** 全部候选目录(外部优先,私有兜底);只返回真实存在的。 */
    private fun candidateDirs(context: Context): List<File> {
        val out = ArrayList<File>(2)
        val external = File(CrashRecorder.EXTERNAL_CRASH_DIR)
        if (external.isDirectory) out.add(external)
        val private = CrashRecorder.privateCrashDir(context)
        if (private.isDirectory && private.absolutePath != external.absolutePath) out.add(private)
        return out
    }

    /** 从文件名解析崩溃时刻;失败时退回文件修改时间。 */
    private fun parseTime(file: File): Long {
        val m = fileNameRegex.find(file.name)
        if (m != null) {
            val parsed = runCatching {
                fileNameFormat.parse(m.groupValues[1] + m.groupValues[2])?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return file.lastModified()
    }
}
