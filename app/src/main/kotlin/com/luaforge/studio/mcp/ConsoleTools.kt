package com.luaforge.studio.mcp

import android.content.Context
import com.luaforge.studio.console.core.ConsoleSettings
import com.luaforge.studio.console.core.EventTracker
import com.luaforge.studio.console.core.FileStateTracker
import com.luaforge.studio.console.core.SessionManager
import com.luaforge.studio.console.core.StateMachine
import com.luaforge.studio.console.env.DexLibraries
import com.luaforge.studio.console.env.LuaEnvironment
import com.luaforge.studio.console.env.ModuleTracker
import com.luaforge.studio.console.logcat.LogcatManager
import com.luaforge.studio.console.output.OutputEntry
import com.luaforge.studio.console.output.OutputManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 调试控制台 → MCP 适配层(只读 + 清空)。
 *
 * 把控制台运行期捕获的数据(print/错误/Toast/Snackbar 输出、事件、require/bindClass 模块、
 * 本会话 logcat、会话与面板状态)暴露为 MCP 工具,使外部 AI 读到的是**控制台看到的同一份现场**,
 * 而不必只依赖 `luaforge.log` 的文本尾部(后者不含逐参 Lua 类型、事件、模块清单)。
 *
 * 设计要点:
 * - 全部数据源复用控制台既有单例(OutputManager/EventTracker/ModuleTracker/...),不新增采集路径,
 *   保证 MCP 结果与浮窗所展示内容**同源**,不会随实现漂移。
 * - 只读 + 清空;不改控制台设置、不驱动面板 UI(面板仍由使用者手动操作)。
 * - 控制台未集成 / 无调试会话时返回明确空态,不抛错。
 *
 * 与既有工具的分工:`get_logs`/`get_runtime_errors` 读 `luaforge.log` 文本;
 * 本层读控制台的结构化缓冲与 logcat 捕获,二者互补。
 */
object ConsoleTools {

    private const val MAX_LIMIT = 2000

    /** 本适配层负责的工具名(与 McpTools 调度解耦)。 */
    private val TOOL_NAMES = setOf(
        "console_status",
        "console_outputs",
        "console_events",
        "console_modules",
        "console_logcat",
        "console_clear"
    )

    // ------------------------------------------------------------------
    // 工具清单(由 McpTools.listTools 追加)
    // ------------------------------------------------------------------

    fun appendToolList(tools: JSONArray) {
        tools.put(
            tool(
                "console_status",
                "调试控制台状态:会话(项目/文件/调试模式/已运行时长)、面板(浮球/浮窗/状态机)、当前文件与布局、" +
                    "捕获开关、缓冲与错误计数。用于确认控制台是否在采集、运行到了哪一步",
                obj()
            )
        )

        tools.put(
            tool(
                "console_outputs",
                "读取控制台「输出」缓冲:print / Lua 报错 / Toast / Snackbar 条目(含逐参 Lua 类型与真实类型)。" +
                    "仅记录调试运行(debugmode)期间的内容;按文件缓冲分组,比 get_logs 更结构化",
                obj(
                    "file" to strProp("仅返回该文件缓冲(键为绝对路径);缺省返回全部缓冲"),
                    "label" to strProp("按级别过滤:print/error/toast/snackbar;数组或逗号分隔可多选"),
                    "limit" to intProp("每个文件缓冲返回的最大条数(取最新),默认 100"),
                    "includeTypes" to boolProp("是否附带 Lua 类型与真实类型,默认 true")
                )
            )
        )

        tools.put(
            tool(
                "console_events",
                "读取控制台事件条目:Lua 侧显式定义且被实际调用的函数(生命周期/事件回调),含时间、参数摘要、所在文件",
                obj(
                    "limit" to intProp("返回的最大条数(取最新),默认 100"),
                    "func" to strProp("仅返回该函数名的条目")
                )
            )
        )

        tools.put(
            tool(
                "console_modules",
                "读取控制台「环境」信息:Lua 版本/是否 JIT、按项目文件跟踪的 require 模块(Lua/原生库,含函数签名)、" +
                    "bindClass 的 Java 类,以及 libs/*.dex 的类与反射方法签名",
                obj("file" to strProp("按该 lua 文件的跟踪记录过滤;缺省用当前会话游标文件"))
            )
        )

        tools.put(
            tool(
                "console_logcat",
                "读取本调试会话的 logcat(与控制台「Logcat」页同源:--pid 限定本进程,threadtime 格式)," +
                    "比 get_logs 更贴近运行现场",
                obj(
                    "lines" to intProp("返回末尾行数,默认 200"),
                    "level" to strProp("按级别过滤:V/D/I/W/E/F/S;数组或逗号分隔可多选;缺省全部")
                )
            )
        )

        tools.put(
            tool(
                "console_clear",
                "清空控制台输出缓冲(不动 luaforge.log,后者用 clear_logs),便于每轮调试前取得干净现场",
                obj("scope" to strProp("current=仅当前文件缓冲,all=全部,默认 all"))
            )
        )
    }

    // ------------------------------------------------------------------
    // 调度
    // ------------------------------------------------------------------

    /** 命中本层工具名则执行,否则返回 null(交由 McpTools 继续判定)。 */
    suspend fun call(context: Context, name: String, args: JSONObject): JSONObject? {
        if (name !in TOOL_NAMES) return null
        return try {
            when (name) {
                "console_status" -> consoleStatus(context)
                "console_outputs" -> consoleOutputs(args)
                "console_events" -> consoleEvents(args)
                "console_modules" -> consoleModules(args)
                "console_logcat" -> consoleLogcat(args)
                "console_clear" -> consoleClear(args)
                else -> null
            }
        } catch (e: Exception) {
            errorResult("控制台工具 $name 执行失败: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // console_status
    // ------------------------------------------------------------------

    private fun consoleStatus(context: Context): JSONObject {
        val settings = ConsoleSettings(context)
        val session = SessionManager.current
        val state = StateMachine.state

        val sessionJson = if (session == null) {
            JSONObject().put("active", false)
        } else {
            JSONObject()
                .put("active", true)
                .put("debugMode", session.debugMode)
                .put("luaPath", session.luaPath ?: JSONObject.NULL)
                .put("luaName", session.luaPath?.let { File(it).name } ?: JSONObject.NULL)
                .put("luaDir", session.luaDir ?: JSONObject.NULL)
                .put("startedAt", session.startTimeMs)
                .put("elapsedMs", System.currentTimeMillis() - session.startTimeMs)
        }

        val buffers = OutputManager.buffers()
        var entries = 0
        var errors = 0
        for (b in buffers) {
            entries += b.size()
            errors += b.all().count { it.label == OutputEntry.LABEL_ERROR }
        }

        val bridge = com.luaforge.studio.console.core.ConsoleRegistry.get()
        val json = JSONObject()
            .put("session", sessionJson)
            .put(
                "ui",
                JSONObject()
                    .put("state", state.name)
                    .put("ballShowing", bridge?.isBallShowing ?: false)
                    .put("panelShowing", bridge?.isPanelShowing ?: false)
            )
            .put("currentFile", OutputManager.currentFile)
            .put(
                "file",
                JSONObject()
                    .put("relative", FileStateTracker.relativePath)
                    .put("layout", FileStateTracker.layout.label)
                    .put("alyPath", FileStateTracker.alyRelativePath)
            )
            .put(
                "environment",
                JSONObject()
                    .put("luaVersion", LuaEnvironment.versionLabel())
                    .put("jit", LuaEnvironment.jit)
            )
            .put(
                "capture",
                JSONObject()
                    .put("parseDepth", settings.parseDepth)
                    .put("print", settings.capturePrint)
                    .put("toast", settings.captureToast)
                    .put("snackbar", settings.captureSnackbar)
            )
            .put(
                "outputs",
                JSONObject()
                    .put("buffers", buffers.size)
                    .put("entries", entries)
                    .put("errors", errors)
            )
            .put("events", EventTracker.snapshot().size)
            .put("logcat", LogcatManager.store?.file()?.name ?: JSONObject.NULL)

        return textResult(json.toString(2))
    }

    // ------------------------------------------------------------------
    // console_outputs
    // ------------------------------------------------------------------

    private fun consoleOutputs(args: JSONObject): JSONObject {
        val fileFilter = args.optString("file", "").ifBlank { null }
        val labels = parseList(args.opt("label"))
        val limit = args.optInt("limit", 100).coerceIn(1, MAX_LIMIT)
        val includeTypes = args.optBoolean("includeTypes", true)

        val buffersArr = JSONArray()
        var total = 0
        var returned = 0
        for (b in OutputManager.buffers()) {
            if (fileFilter != null && b.fileKey != fileFilter) continue
            val all = b.all().filter { labels.isEmpty() || it.label in labels }
            total += all.size
            val tail = if (all.size > limit) all.subList(all.size - limit, all.size) else all
            returned += tail.size
            val entries = JSONArray()
            for (e in tail) entries.put(entryJson(e, includeTypes))
            buffersArr.put(
                JSONObject()
                    .put("file", b.fileKey)
                    .put("returned", tail.size)
                    .put("total", all.size)
                    .put("entries", entries)
            )
        }

        return textResult(
            JSONObject()
                .put("currentFile", OutputManager.currentFile)
                .put("buffers", buffersArr)
                .put("returned", returned)
                .put("total", total)
                .toString(2)
        )
    }

    private fun entryJson(e: OutputEntry, includeTypes: Boolean): JSONObject {
        val o = JSONObject()
            .put("id", e.id)
            .put("time", e.fullTime)
            .put("label", e.label)
            .put("relFile", e.relFile.ifBlank { e.file })
            .put("mainThread", e.isMainThread)
            .put("content", e.primary)
        e.eventFunc?.let { o.put("eventFunc", it) }
        val full = e.fullText
        if (full != null && full != e.primary) o.put("full", full)
        if (includeTypes) {
            if (e.luaTypes.isNotEmpty()) o.put("luaTypes", JSONArray(e.luaTypes))
            if (e.typeDetails.isNotEmpty()) o.put("types", JSONArray(e.typeDetails))
        }
        return o
    }

    // ------------------------------------------------------------------
    // console_events
    // ------------------------------------------------------------------

    private fun consoleEvents(args: JSONObject): JSONObject {
        val limit = args.optInt("limit", 100).coerceIn(1, MAX_LIMIT)
        val func = args.optString("func", "").trim()
        val matched = EventTracker.snapshot().filter { func.isEmpty() || it.funcName == func }
        // snapshot 为新→旧:取前 limit 条即最新
        val tail = if (matched.size > limit) matched.subList(0, limit) else matched
        val arr = JSONArray()
        for (e in tail) {
            arr.put(
                JSONObject()
                    .put("time", e.timeLabel())
                    .put("func", e.funcName)
                    .put("args", e.argsSummary)
                    .put("file", e.fileLabel)
                    .put("mainThread", e.isMainThread)
            )
        }
        return textResult(
            JSONObject()
                .put("count", matched.size)
                .put("returned", tail.size)
                .put("events", arr)
                .toString(2)
        )
    }

    // ------------------------------------------------------------------
    // console_modules
    // ------------------------------------------------------------------

    private fun consoleModules(args: JSONObject): JSONObject {
        val file = args.optString("file", "").ifBlank { OutputManager.currentFile }

        val libs = ModuleTracker.luaLibs(file)
        val luaArr = JSONArray()
        val nativeArr = JSONArray()
        for (l in libs) {
            val funcs = JSONObject()
            for ((fn, np) in l.funcs) funcs.put(fn, np)
            val o = JSONObject().put("module", l.module).put("funcs", funcs)
            if (l.native) nativeArr.put(o) else luaArr.put(o)
        }

        val javaArr = JSONArray()
        for (j in ModuleTracker.javaLibs(file)) {
            javaArr.put(JSONObject().put("class", j.className).put("methods", JSONArray(j.methods)))
        }

        val dexArr = JSONArray()
        for (d in DexLibraries.scan(SessionManager.current?.luaDir)) {
            val classes = JSONArray()
            for (c in d.classes) {
                classes.put(JSONObject().put("class", c.className).put("methods", JSONArray(c.methods)))
            }
            dexArr.put(JSONObject().put("dex", d.dexFile).put("classes", classes))
        }

        return textResult(
            JSONObject()
                .put("file", file)
                .put("luaVersion", LuaEnvironment.versionLabel())
                .put("jit", LuaEnvironment.jit)
                .put("luaModules", luaArr)
                .put("nativeModules", nativeArr)
                .put("javaLibs", javaArr)
                .put("dexLibs", dexArr)
                .toString(2)
        )
    }

    // ------------------------------------------------------------------
    // console_logcat
    // ------------------------------------------------------------------

    private suspend fun consoleLogcat(args: JSONObject): JSONObject {
        val lines = args.optInt("lines", 200).coerceIn(1, 5000)
        val levels = parseList(args.opt("level")).map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
        val store = LogcatManager.store
            ?: return errorResult("当前没有进行中的调试会话 logcat(需先 run_project 运行 debugmode 项目)")
        val file = store.file()
        if (!file.exists()) return errorResult("logcat 文件不存在: ${file.absolutePath}")

        val tail = withContext(Dispatchers.IO) { readTail(file, 256 * 1024L) }
        // 与 Logcat 页过滤一致:无法解析级别的行(非标准行)恒保留
        val filtered = if (levels.isEmpty()) {
            tail
        } else {
            tail.filter { line ->
                val lv = lineLevel(line) ?: return@filter true
                lv in levels
            }
        }
        val out = if (filtered.size > lines) filtered.subList(filtered.size - lines, filtered.size) else filtered

        return textResult(
            JSONObject()
                .put("file", file.name)
                .put("path", file.absolutePath)
                .put("matched", filtered.size)
                .put("returned", out.size)
                .put("content", out.joinToString("\n"))
                .toString()
        )
    }

    private fun readTail(file: File, maxBytes: Long): List<String> {
        val len = file.length()
        if (len <= maxBytes) return file.readText(Charsets.UTF_8).lines()
        return java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(len - maxBytes)
            val buf = ByteArray(maxBytes.toInt())
            val n = raf.read(buf)
            // 首个可能为半行 / 半字符,丢弃
            String(buf, 0, n, Charsets.UTF_8).lines().drop(1)
        }
    }

    // ------------------------------------------------------------------
    // console_clear
    // ------------------------------------------------------------------

    private fun consoleClear(args: JSONObject): JSONObject {
        val scope = args.optString("scope", "all").trim().lowercase()
        return if (scope == "current") {
            val file = OutputManager.currentFile
            OutputManager.clearCurrentFile()
            textResult(JSONObject().put("cleared", "current").put("file", file).toString(2))
        } else {
            OutputManager.clearAll()
            textResult(JSONObject().put("cleared", "all").toString(2))
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 解析过滤参数:JSONArray / 逗号或换行分隔字符串 均可。 */
    private fun parseList(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is JSONArray -> (0 until value.length())
            .map { value.optString(it, "").trim() }
            .filter { it.isNotEmpty() }
        is String -> value.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        else -> listOf(value.toString())
    }

    /** threadtime 行级别字母(V/D/I/W/E/F/S);非标准行返回 null。 */
    private fun lineLevel(line: String): String? =
        LEVEL_REGEX.find(line)?.groupValues?.getOrNull(1)

    private val LEVEL_REGEX = Regex("\\s+\\d+\\s+\\d+\\s+([VDIWEFS])\\s")

    // 与 McpTools 同款的极简 JSON 构造helper(保持本层自包含,避免反向依赖 McpTools)

    private fun textResult(text: String): JSONObject =
        JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            .put("isError", false)

    private fun errorResult(message: String): JSONObject =
        JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message)))
            .put("isError", true)

    private fun tool(
        name: String,
        description: String,
        properties: JSONObject,
        required: Array<String> = emptyArray()
    ): JSONObject =
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put(
                "inputSchema",
                JSONObject()
                    .put("type", "object")
                    .put("properties", properties)
                    .put("required", JSONArray(required.toList()))
            )

    private fun obj(vararg pairs: Pair<String, JSONObject>): JSONObject {
        val result = JSONObject()
        pairs.forEach { (key, value) -> result.put(key, value) }
        return result
    }

    private fun strProp(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)

    private fun intProp(description: String): JSONObject =
        JSONObject().put("type", "integer").put("description", description)

    private fun boolProp(description: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", description)
}
