package com.luaforge.studio.mcp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.androlua.LuaActivity
import com.androlua.LuaFunctionRegistrar
import com.androlua.LuaLexer
import com.androlua.LuaTokenTypes
import com.luaforge.studio.langs.lua.tools.CompleteHashmapUtils
import com.luaforge.studio.ui.editor.backupProject
import com.luaforge.studio.ui.editor.viewmodel.EditorViewModel
import com.luaforge.studio.ui.editor.buildProject
import com.luaforge.studio.ui.editor.getAppNameFromSettings
import com.luaforge.studio.ui.project.TemplateItem
import com.luaforge.studio.ui.settings.SettingsManager
import com.luaforge.studio.utils.ConsoleUtil
import com.luaforge.studio.utils.JsonUtil
import com.luaforge.studio.utils.LuaParserUtil
import com.luaforge.studio.utils.FileUtil
import com.luaforge.studio.utils.LogCatcher
import com.luaforge.studio.utils.ProjectUtil
import com.luajava.LuaObject
import com.luajava.LuaState
import com.luajava.LuaStateFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Method

/**
 * MCP 工具集:把编辑器的全部能力(读写代码、调试运行、编译构建、日志等)暴露为 MCP 工具。
 *
 * 所有实现都复用应用内已有逻辑,不重复造轮子:
 * - 打开/切换文件、格式化、保存 → [EditorBridge] 上的 EditorViewModel
 * - 构建 / 备份 → `ui/editor/EditorFunctions.kt`
 * - 导入分析 → LuaLexer + classMap.dat
 */
object McpTools {

    private const val TAG = "McpTools"
    private const val LOG_FILE_PATH = "/storage/emulated/0/LuaForge-Studio/luaforge.log"
    private const val MAX_READ_BYTES = 2 * 1024 * 1024

    /** 等待编辑器控件就绪的重试次数与间隔(open_file / goto_line 共用)。 */
    private const val CURSOR_MOVE_ATTEMPTS = 10
    private const val CURSOR_MOVE_INTERVAL_MS = 50L

    /** call_global_util 的分隔字符串实参类型推断。 */
    private val INTEGER_ARG = Regex("^[+-]?\\d+$")
    private val DECIMAL_ARG = Regex("^[+-]?(?:\\d+\\.\\d*|\\.\\d+|\\d+)(?:[eE][+-]?\\d+)?$")

    /**
     * 能由 JSON / 字面量表达的形参类型;其余(控件、Java 对象、LuaObject 等)无法经 MCP 传参。
     *
     * 注意 KClass.java 给的是原始类型(int/long...),装箱类型要另取 javaObjectType:
     * 两者都列进来,否则形参写成 java.lang.Integer 的方法会被误判为不可调用。
     */
    private val SCALAR_PARAM_TYPES: Set<Class<*>> = setOf(
        String::class.java,
        Any::class.java,
        Number::class.java,
        Int::class.java, Int::class.javaObjectType,
        Long::class.java, Long::class.javaObjectType,
        Double::class.java, Double::class.javaObjectType,
        Float::class.java, Float::class.javaObjectType,
        Short::class.java, Short::class.javaObjectType,
        Byte::class.java, Byte::class.javaObjectType,
        Boolean::class.java, Boolean::class.javaObjectType,
        Char::class.java, Char::class.javaObjectType
    )

    // ------------------------------------------------------------------
    // 工具清单
    // ------------------------------------------------------------------

    fun listTools(): JSONArray {
        val tools = JSONArray()
        tools.put(tool("list_projects", "列出所有项目(名称与路径)", obj()))

        tools.put(
            tool(
                "list_files",
                "列出项目或指定目录下的文件树",
                obj(
                    "path" to strProp("相对项目根目录的路径,缺省为项目根"),
                    "recursive" to boolProp("是否递归,默认 true"),
                    "maxEntries" to intProp("最多返回条目数,默认 500")
                )
            )
        )

        tools.put(
            tool(
                "read_file",
                "读取文本文件内容",
                obj("path" to strProp("文件路径(可相对项目根目录)")),
                required = arrayOf("path")
            )
        )

        tools.put(
            tool(
                "write_file",
                "覆盖写入文本文件(自动创建父目录)",
                obj(
                    "path" to strProp("文件路径(可相对项目根目录)"),
                    "content" to strProp("写入的完整内容")
                ),
                required = arrayOf("path", "content")
            )
        )

        tools.put(
            tool(
                "create_file",
                "新建文件,已存在时默认不覆盖",
                obj(
                    "path" to strProp("文件路径(可相对项目根目录)"),
                    "content" to strProp("初始内容,默认空"),
                    "overwrite" to boolProp("已存在时是否覆盖,默认 false")
                ),
                required = arrayOf("path")
            )
        )

        tools.put(
            tool(
                "delete_file",
                "删除文件或空目录",
                obj("path" to strProp("文件或目录路径")),
                required = arrayOf("path")
            )
        )

        tools.put(
            tool(
                "get_project_info",
                "获取项目信息(settings.json、文件数量、图标等)",
                obj("path" to strProp("项目路径,缺省为当前打开的项目"))
            )
        )

        tools.put(tool("get_editor_state", "获取编辑器状态:已打开标签、活动文件、未保存改动", obj()))

        tools.put(
            tool(
                "open_file",
                "在编辑器中打开文件",
                obj(
                    "path" to strProp("文件路径(可相对项目根目录)"),
                    "line" to intProp("打开后跳转的行号(可选,从 1 开始)"),
                    "column" to intProp("与 line 配合的列号(可选,从 0 开始)")
                ),
                required = arrayOf("path")
            )
        )

        tools.put(
            tool(
                "set_editor_content",
                "替换当前活动文件的全部内容并落盘",
                obj("content" to strProp("新的完整内容")),
                required = arrayOf("content")
            )
        )

        tools.put(
            tool(
                "insert_text",
                "在当前活动文件的光标处插入文本",
                obj("text" to strProp("要插入的文本")),
                required = arrayOf("text")
            )
        )

        tools.put(tool("save_files", "保存所有已修改的文件", obj()))
        tools.put(tool("format_code", "格式化当前活动文件", obj()))

        tools.put(
            tool(
                "compile_file",
                "编译单个 .lua/.aly 文件(等价于编辑器“编译文件”)。默认在验证成功后删除 .luac/.alyc 产物,避免污染项目",
                obj(
                    "path" to strProp("文件路径,缺省为当前活动文件"),
                    "keepOutput" to boolProp("true=保留编译产物(.luac/.alyc),默认 false 即验证后删除")
                )
            )
        )

        tools.put(
            tool(
                "build_apk",
                "构建项目为已签名 APK(与编辑器“构建”一致),返回输出路径",
                obj("path" to strProp("项目路径,缺省为当前项目"))
            )
        )

        tools.put(
            tool(
                "run_project",
                "调试运行项目:启动 LuaActivity 加载 main.lua",
                obj("path" to strProp("项目路径,缺省为当前项目"))
            )
        )

        tools.put(
            tool(
                "install_apk",
                "唤起系统安装器安装指定 APK",
                obj("path" to strProp("APK 文件路径")),
                required = arrayOf("path")
            )
        )

        tools.put(
            tool(
                "backup_project",
                "备份项目到 LuaForge-Studio/backup 目录,返回备份文件路径",
                obj("path" to strProp("项目路径,缺省为当前项目"))
            )
        )

        tools.put(
            tool(
                "analyze_imports",
                "分析代码中用到的类,生成 import 语句列表",
                obj("content" to strProp("要分析的代码;缺省时分析当前活动文件"))
            )
        )

        tools.put(
            tool(
                "get_logs",
                "读取应用调试日志(luaforge.log)",
                obj("lines" to intProp("返回末尾行数,默认 200"))
            )
        )

        tools.put(tool("get_settings", "读取当前应用设置(编辑器/主题等)", obj()))

        tools.put(
            tool(
                "dump_screen",
                "导出当前前台界面的控件树(调试运行后确认屏幕内容)",
                obj(
                    "includeInvisible" to boolProp("是否包含不可见控件,默认 false"),
                    "maxDepth" to intProp("最大递归深度,默认 40")
                )
            )
        )

        tools.put(
            tool(
                "check_screen",
                "按预期校验当前界面(文本/控件数量/错误弹窗),返回是否通过及差异",
                obj(
                    "expectTexts" to strProp("必须出现的文本,JSON 数组或换行/逗号分隔"),
                    "expectAnyOf" to strProp("至少出现一个的文本,JSON 数组或换行/逗号分隔"),
                    "absentTexts" to strProp("必须不出现的文本,JSON 数组或换行/逗号分隔"),
                    "minViews" to intProp("可见控件数量下限"),
                    "includeInvisible" to boolProp("是否包含不可见控件,默认 false")
                )
            )
        )

        tools.put(
            tool(
                "get_runtime_errors",
                "读取日志中的运行时错误(Lua 报错、布局加载失败等)",
                obj("lines" to intProp("返回末尾错误行数,默认 50"))
            )
        )

        tools.put(tool("clear_logs", "清空 luaforge.log,便于每次运行前取得干净的错误现场", obj()))

        tools.put(
            tool(
                "debugger_status",
                "调试浮窗(debugger.lua)状态:当前运行页面 / 项目 / 浮窗缓冲条数。" +
                    "用于确认调试运行是否已注入浮窗、以及是否有 print/报错现场可读",
                obj(
                    "page" to strProp("目标页面名(pageName),存在多个运行页面时用于精确指定"),
                    "path" to strProp("目标项目路径,缺省用当前打开的项目")
                )
            )
        )

        tools.put(
            tool(
                "debugger_outputs",
                "读取调试浮窗缓冲:项目运行期间经全局 print 与 Lua 报错(onError)汇入的全部条目。" +
                    "与 get_logs 读 luaforge.log 文本互补,这里读的是浮窗同源缓冲",
                obj(
                    "page" to strProp("目标页面名(pageName),存在多个运行页面时用于精确指定"),
                    "path" to strProp("目标项目路径,缺省用当前打开的项目"),
                    "limit" to intProp("返回最新条目数,默认 200"),
                    "keyword" to strProp("只返回包含该关键词的条目")
                )
            )
        )

        tools.put(
            tool(
                "debugger_clear",
                "清空调试浮窗缓冲(不动 luaforge.log),便于每次运行前取得干净的现场",
                obj(
                    "page" to strProp("目标页面名(pageName),存在多个运行页面时用于精确指定"),
                    "path" to strProp("目标项目路径,缺省用当前打开的项目")
                )
            )
        )

        tools.put(
            tool(
                "refresh_editor",
                "把磁盘上的最新内容重新载入编辑器(外部修改文件后强制刷新界面);缺省刷新全部已打开文件",
                obj("path" to strProp("文件路径,缺省刷新全部已打开文件"))
            )
        )

        tools.put(
            tool(
                "clean_compiled",
                "清理项目内的编译产物(.luac/.alyc)。compile_file 已默认自动清理,此工具用于清理历史残留",
                obj(
                    "path" to strProp("项目路径,缺省为当前项目"),
                    "dryRun" to boolProp("true=只列出不删除,默认 false")
                )
            )
        )

        tools.put(tool("get_selection", "读取编辑器当前选中的文本与光标位置", obj()))

        tools.put(
            tool(
                "editor_history",
                "撤销/重做当前活动文件的编辑",
                obj("action" to strProp("undo 或 redo,默认 undo")),
                required = arrayOf("action")
            )
        )

        tools.put(
            tool(
                "goto_line",
                "把光标移动到指定行(1 起算)",
                obj(
                    "line" to intProp("目标行号,1 起算"),
                    "column" to intProp("目标列号,0 起算,默认 0")
                ),
                required = arrayOf("line")
            )
        )

        tools.put(
            tool(
                "search_in_files",
                "在项目内按文本或正则搜索,返回命中的文件与行号",
                obj(
                    "path" to strProp("搜索目录,缺省为当前项目"),
                    "query" to strProp("搜索内容"),
                    "regex" to boolProp("true=按正则匹配,默认 false 字面量"),
                    "ignoreCase" to boolProp("是否忽略大小写,默认 true"),
                    "maxResults" to intProp("最多返回条数,默认 200")
                ),
                required = arrayOf("query")
            )
        )

        tools.put(
            tool(
                "read_files",
                "一次读取多个文本文件",
                obj("paths" to strProp("文件路径列表,JSON 数组或换行分隔")),
                required = arrayOf("paths")
            )
        )

        tools.put(
            tool(
                "replace_in_file",
                "在单个文件中替换文本(支持正则与出现次数断言)",
                obj(
                    "path" to strProp("文件路径"),
                    "find" to strProp("被替换内容"),
                    "replace" to strProp("替换为"),
                    "regex" to boolProp("true=按正则,默认 false"),
                    "ignoreCase" to boolProp("是否忽略大小写,默认 false"),
                    "expectCount" to intProp("期望出现次数,不符则不改写")
                ),
                required = arrayOf("path", "find", "replace")
            )
        )

        tools.put(
            tool(
                "replace_in_files",
                "在项目内批量替换文本,支持 dryRun 预览",
                obj(
                    "path" to strProp("项目或目录,缺省为当前项目"),
                    "find" to strProp("被替换内容"),
                    "replace" to strProp("替换为"),
                    "regex" to boolProp("true=按正则,默认 false"),
                    "ignoreCase" to boolProp("是否忽略大小写,默认 false"),
                    "dryRun" to boolProp("true=只统计不写入,默认 false")
                ),
                required = arrayOf("find", "replace")
            )
        )

        tools.put(
            tool(
                "rename_file",
                "重命名/移动文件或目录,并同步编辑器标签",
                obj(
                    "from" to strProp("原路径"),
                    "to" to strProp("新路径"),
                    "overwrite" to boolProp("目标已存在时是否覆盖,默认 false")
                ),
                required = arrayOf("from", "to")
            )
        )

        tools.put(
            tool(
                "make_directory",
                "创建目录(递归)",
                obj("path" to strProp("目录路径")),
                required = arrayOf("path")
            )
        )

        tools.put(
            tool(
                "file_info",
                "获取文件/目录信息(大小、修改时间、行数、MD5)",
                obj("path" to strProp("路径")),
                required = arrayOf("path")
            )
        )

        tools.put(
            tool(
                "check_syntax",
                "对 Lua/ALY 代码做语法检查,返回是否通过及错误行",
                obj(
                    "path" to strProp("文件路径;缺省时使用 content 或当前活动文件"),
                    "content" to strProp("直接检查的代码内容")
                )
            )
        )

        tools.put(
            tool(
                "wait_for_text",
                "轮询等待界面出现(或消失)指定文本,用于运行后确认界面就绪",
                obj(
                    "text" to strProp("等待出现的文本"),
                    "absent" to boolProp("true=等待该文本消失,默认 false"),
                    "timeoutMs" to intProp("超时毫秒,默认 10000,上限 60000"),
                    "intervalMs" to intProp("轮询间隔毫秒,默认 500")
                ),
                required = arrayOf("text")
            )
        )

        tools.put(tool("list_templates", "列出可用于新建项目的模板", obj()))

        tools.put(
            tool(
                "create_project",
                "按模板新建项目",
                obj(
                    "name" to strProp("项目名,缺省自动生成"),
                    "packageName" to strProp("包名,缺省由项目名推导"),
                    "template" to strProp("模板 zip 文件名(见 list_templates),缺省不使用模板"),
                    "debugMode" to boolProp("是否开启调试模式,默认 false"),
                    "globalUtils" to strProp("全局工具类列表,JSON 数组或换行分隔"),
                    "overwrite" to boolProp("同名项目存在时是否覆盖,默认 false")
                )
            )
        )

        tools.put(
            tool(
                "restore_backup",
                "把 backup 目录中的备份 zip 还原为项目",
                obj(
                    "backupPath" to strProp("备份 zip 路径"),
                    "projectName" to strProp("还原后的项目名,缺省由备份文件名推导"),
                    "overwrite" to boolProp("同名项目存在时是否覆盖,默认 false")
                ),
                required = arrayOf("backupPath")
            )
        )

        tools.put(
            tool(
                "list_global_utils",
                "查看项目 global_utils 配置,以及这些工具类在运行时会注册的 Lua 全局函数" +
                    "(参数、返回类型、是否自动注入 context、同名覆盖)",
                obj("path" to strProp("项目路径,缺省为当前打开的项目"))
            )
        )

        tools.put(
            tool(
                "call_global_util",
                "在运行中的项目里调用 global_utils 注册的 Lua 全局函数(需先 run_project)。" +
                    "与项目自身调用走同一条路径,因此需要项目正在运行",
                obj(
                    "name" to strProp("Lua 全局函数名,如 dp2px"),
                    "args" to strProp("实参:JSON 数组,或换行/逗号分隔的字符串(分隔形式会自动推断 number/boolean)"),
                    "page" to strProp("目标页面名(pageName),存在多个运行页面时用于精确指定"),
                    "path" to strProp("目标项目路径,缺省用当前打开的项目"),
                    "timeoutMs" to intProp("等待毫秒,默认 5000,上限 30000")
                ),
                required = arrayOf("name")
            )
        )

        return tools
    }

    // ------------------------------------------------------------------
    // 调度
    // ------------------------------------------------------------------

    suspend fun call(context: Context, name: String, args: JSONObject): JSONObject {
        LogCatcher.i(TAG, "调用工具: $name")
        return try {
            when (name) {
                "list_projects" -> listProjects(context)
                "list_files" -> listFiles(context, args)
                "read_file" -> readFile(context, args)
                "write_file" -> writeFile(context, args)
                "create_file" -> createFile(context, args)
                "delete_file" -> deleteFile(context, args)
                "get_project_info" -> getProjectInfo(context, args)
                "get_editor_state" -> getEditorState()
                "open_file" -> openFile(context, args)
                "set_editor_content" -> setEditorContent(context, args)
                "insert_text" -> insertText(args)
                "save_files" -> saveFiles()
                "format_code" -> formatCode()
                "refresh_editor" -> refreshEditor(context, args)
                "clean_compiled" -> cleanCompiled(context, args)
                "search_in_files" -> searchInFiles(context, args)
                "read_files" -> readFiles(context, args)
                "replace_in_file" -> replaceInFile(context, args)
                "replace_in_files" -> replaceInFiles(context, args)
                "rename_file" -> renameFile(context, args)
                "make_directory" -> makeDirectory(context, args)
                "file_info" -> fileInfo(context, args)
                "check_syntax" -> checkSyntax(context, args)
                "get_selection" -> getSelection()
                "editor_history" -> editorHistory(args)
                "goto_line" -> gotoLine(args)
                "wait_for_text" -> waitForText(args)
                "list_templates" -> listTemplates(context)
                "create_project" -> createProject(context, args)
                "restore_backup" -> restoreBackup(context, args)
                "compile_file" -> compileFile(context, args)
                "build_apk" -> buildApk(context, args)
                "run_project" -> runProject(context, args)
                "install_apk" -> installApk(context, args)
                "backup_project" -> backupProjectTool(context, args)
                "analyze_imports" -> analyzeImports(context, args)
                "get_logs" -> getLogs(args)
                "get_settings" -> getSettings()
                "dump_screen" -> dumpScreen(args)
                "check_screen" -> checkScreen(args)
                "get_runtime_errors" -> getRuntimeErrors(args)
                "clear_logs" -> clearLogs()
                "debugger_status" -> debuggerStatus(context, args)
                "debugger_outputs" -> debuggerOutputs(context, args)
                "debugger_clear" -> debuggerClear(context, args)
                "list_global_utils" -> listGlobalUtils(context, args)
                "call_global_util" -> callGlobalUtil(context, args)
                else -> errorResult("未知工具: $name")
            }
        } catch (e: Exception) {
            LogCatcher.e(TAG, "工具 $name 执行异常", e)
            errorResult("执行失败: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // 项目与文件
    // ------------------------------------------------------------------

    private suspend fun listProjects(context: Context): JSONObject {
        val projectsPath = FileUtil.getProjectsPath(context)
        val deferred = CompletableDeferred<List<com.luaforge.studio.ProjectItem>>()
        ProjectUtil.loadProjectsFromDirectory(projectsPath) { deferred.complete(it) }
        val items = withTimeoutOrNull(8000) { deferred.await() } ?: emptyList()

        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("name", item.name)
                    .put("path", item.path)
                    .put("modified", item.modifiedDate.time)
            )
        }
        return textResult(
            JSONObject()
                .put("projectsRoot", projectsPath)
                .put("count", items.size)
                .put("projects", array)
                .toString(2)
        )
    }

    private suspend fun listFiles(context: Context, args: JSONObject): JSONObject {
        val base = resolveProject(context, args.optString("path", ""))
        if (!base.exists()) return errorResult("路径不存在: ${base.absolutePath}")
        if (!isAllowed(context, base)) return errorResult("路径不在允许范围内: ${base.absolutePath}")

        val recursive = args.optBoolean("recursive", true)
        val maxEntries = args.optInt("maxEntries", 500).coerceIn(1, 5000)

        val entries = JSONArray()
        var truncated = false

        if (base.isFile) {
            entries.put(describe(base, base.parentFile ?: base))
        } else {
            val queue = ArrayDeque<File>()
            queue.add(base)
            var count = 0
            while (queue.isNotEmpty() && count < maxEntries) {
                val dir = queue.removeFirst()
                val children = dir.listFiles()?.sortedBy { it.name } ?: continue
                for (child in children) {
                    if (count >= maxEntries) {
                        truncated = true
                        break
                    }
                    entries.put(describe(child, base))
                    count++
                    if (recursive && child.isDirectory) queue.add(child)
                }
            }
        }

        return textResult(
            JSONObject()
                .put("path", base.absolutePath)
                .put("truncated", truncated)
                .put("entries", entries)
                .toString(2)
        )
    }

    private fun describe(file: File, base: File): JSONObject =
        JSONObject()
            .put("path", file.absolutePath)
            .put("relative", runCatching { file.relativeTo(base).path }.getOrDefault(file.name))
            .put("directory", file.isDirectory)
            .put("size", if (file.isFile) file.length() else 0L)

    private suspend fun readFile(context: Context, args: JSONObject): JSONObject {
        val raw = args.optString("path", "")
        if (raw.isBlank()) return errorResult("缺少参数 path")
        val file = resolveProject(context, raw)
        if (!file.exists() || !file.isFile) return errorResult("文件不存在: ${file.absolutePath}")
        if (!isAllowed(context, file)) return errorResult("路径不在允许范围内: ${file.absolutePath}")
        if (file.length() > MAX_READ_BYTES) {
            return errorResult("文件过大(${file.length()} 字节),超过 2MB 限制")
        }
        val content = withContext(Dispatchers.IO) { file.readText(Charsets.UTF_8) }
        return textResult(
            JSONObject()
                .put("path", file.absolutePath)
                .put("size", file.length())
                .put("content", content)
                .toString()
        )
    }

    private suspend fun writeFile(context: Context, args: JSONObject): JSONObject {
        val raw = args.optString("path", "")
        if (raw.isBlank()) return errorResult("缺少参数 path")
        if (!args.has("content")) return errorResult("缺少参数 content")
        val file = resolveProject(context, raw)
        if (!isAllowed(context, file)) return errorResult("路径不在允许范围内: ${file.absolutePath}")

        val content = args.optString("content", "")
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
        }
        // 该文件若正在编辑器中打开,必须把新内容推回编辑器;
        // 否则编辑器仍持有旧内容,用户之后一保存就会把外部写入覆盖掉。
        val synced = syncEditorIfOpen(file.absolutePath)
        return textResult(
            JSONObject()
                .put("path", file.absolutePath)
                .put("bytes", content.toByteArray(Charsets.UTF_8).size)
                .put("editorSynced", synced)
                .put("message", "写入成功")
                .toString(2)
        )
    }

    private suspend fun createFile(context: Context, args: JSONObject): JSONObject {
        val raw = args.optString("path", "")
        if (raw.isBlank()) return errorResult("缺少参数 path")
        val file = resolveProject(context, raw)
        if (!isAllowed(context, file)) return errorResult("路径不在允许范围内: ${file.absolutePath}")

        val overwrite = args.optBoolean("overwrite", false)
        val existedBefore = file.exists()
        if (existedBefore && !overwrite) {
            return errorResult("文件已存在(overwrite=false): ${file.absolutePath}")
        }

        val content = args.optString("content", "")
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
        }
        // 覆盖已有文件时同样要刷新编辑器,避免旧缓冲区回写覆盖
        val synced = syncEditorIfOpen(file.absolutePath)
        return textResult(
            JSONObject()
                .put("path", file.absolutePath)
                .put("created", true)
                .put("overwritten", existedBefore)
                .put("editorSynced", synced)
                .toString(2)
        )
    }

    private suspend fun deleteFile(context: Context, args: JSONObject): JSONObject {
        val raw = args.optString("path", "")
        if (raw.isBlank()) return errorResult("缺少参数 path")
        val file = resolveProject(context, raw)
        if (!isAllowed(context, file)) return errorResult("路径不在允许范围内: ${file.absolutePath}")
        if (!file.exists()) return errorResult("路径不存在: ${file.absolutePath}")

        val deleted = withContext(Dispatchers.IO) { file.delete() }
        if (!deleted) {
            return errorResult("删除失败(目录非空或无权限): ${file.absolutePath}")
        }
        // 该文件若在编辑器中打开,需关闭标签;否则标签会指向已删除的文件
        val closed = closeEditorIfOpen(file.absolutePath)
        return textResult(
            JSONObject()
                .put("path", file.absolutePath)
                .put("deleted", true)
                .put("editorTabClosed", closed)
                .toString(2)
        )
    }

    private suspend fun getProjectInfo(context: Context, args: JSONObject): JSONObject {
        val project = resolveProjectPath(context, args.optString("path", ""))
            ?: return errorResult("无法确定项目路径,请先在前台打开项目或传入 path")
        if (!isAllowed(context, File(project))) return errorResult("路径不在允许范围内: $project")
        val info = ProjectUtil.getProjectInfo(project)

        // 注意:getProjectInfo 返回的 Map 里含 java.util.Date,JSONObject 无法直接序列化,
        // 这里显式挑出可序列化的字段逐项转换。
        val json = JSONObject()
        json.put("name", info["name"] ?: JSONObject.NULL)
        json.put("path", info["path"] ?: JSONObject.NULL)
        val lastModified = info["lastModified"]
        json.put(
            "lastModified",
            if (lastModified is java.util.Date) lastModified.time else JSONObject.NULL
        )
        json.put("hasIcon", info["hasIcon"] ?: false)
        json.put("fileCount", info["fileCount"] ?: 0)

        json.put("settings", toJsonSafe(info["settings"]))
        json.put("global_utils", toJsonSafe(info["global_utils"] ?: emptyList<Any>()))

        return textResult(json.toString(2))
    }

    // ------------------------------------------------------------------
    // 编辑器操作
    // ------------------------------------------------------------------

    private fun getEditorState(): JSONObject {
        val vm = EditorBridge.currentViewModel()
        if (vm == null) return errorResult("编辑器未打开")

        val openFiles = JSONArray()
        vm.openFiles.forEach { state ->
            openFiles.put(
                JSONObject()
                    .put("path", state.file.absolutePath)
                    .put("name", state.file.name)
                    .put("modified", state.isModified)
            )
        }
        val active = vm.activeFileState
        return textResult(
            JSONObject()
                .put("projectPath", EditorBridge.currentProjectPath() ?: "")
                .put("activeFile", active?.file?.absolutePath ?: JSONObject.NULL)
                .put("hasUnsavedChanges", active?.isModified ?: false)
                .put("openFiles", openFiles)
                .toString(2)
        )
    }

    // EditorViewModel.openFile 标注了 @RequiresApi(VANILLA_ICE_CREAM);编辑器界面本身也仅在
    // API 34+ 可用,这里显式抑制 NewApi 提示(非编译错误)。
    @Suppress("NewApi")
    private suspend fun openFile(context: Context, args: JSONObject): JSONObject {
        val vm = EditorBridge.currentViewModel() ?: return errorResult("编辑器未打开")
        val project = EditorBridge.currentProjectPath()
            ?: resolveProjectPath(context, "") ?: return errorResult("无法确定项目路径")

        val raw = args.optString("path", "")
        if (raw.isBlank()) return errorResult("缺少参数 path")
        val file = resolveProject(context, raw)
        if (!isAllowed(context, file)) return errorResult("路径不在允许范围内: ${file.absolutePath}")
        if (!file.exists() || !file.isFile) return errorResult("文件不存在: ${file.absolutePath}")

        withContext(Dispatchers.Main) {
            vm.openFile(file, project)
        }

        val line = args.optInt("line", 0)
        var jumped = false
        if (line > 0) {
            jumped = withContext(Dispatchers.Main) {
                moveCursorTo(vm, file.absolutePath, line, args.optInt("column", 0))
            }
        }
        return textResult(
            JSONObject()
                .put("opened", file.absolutePath)
                .put("line", line)
                .put("cursorMoved", jumped)
                .toString(2)
        )
    }

    /**
     * 把光标移到指定文件的活动编辑器上(1 起算行号)。
     *
     * openFile 内部是异步的,编辑器控件可能尚未创建或尚未切到目标文件,
     * 因此这里带重试地等待编辑器就绪;必须在主线程调用。
     *
     * @return 是否成功移动光标
     */
    private suspend fun moveCursorTo(
        vm: EditorViewModel,
        filePath: String,
        line: Int,
        column: Int
    ): Boolean {
        repeat(CURSOR_MOVE_ATTEMPTS) { attempt ->
            val active = vm.activeFileState
            if (active != null && active.file.absolutePath == filePath) {
                val editor = vm.getActiveEditor()
                if (editor != null) {
                    try {
                        val lineCount = editor.text.lineCount
                        val targetLine = (line - 1).coerceIn(0, maxOf(0, lineCount - 1))
                        val lineLength = editor.text.getColumnCount(targetLine)
                        val targetColumn = column.coerceAtLeast(0).coerceIn(0, lineLength)
                        editor.setSelection(targetLine, targetColumn)
                        return true
                    } catch (e: Exception) {
                        LogCatcher.e(TAG, "移动光标失败: $filePath", e)
                        return false
                    }
                }
            }
            if (attempt < CURSOR_MOVE_ATTEMPTS - 1) delay(CURSOR_MOVE_INTERVAL_MS)
        }
        return false
    }

    private suspend fun setEditorContent(context: Context, args: JSONObject): JSONObject {
        val vm = EditorBridge.currentViewModel() ?: return errorResult("编辑器未打开")
        if (!args.has("content")) return errorResult("缺少参数 content")
        val active = vm.activeFileState ?: return errorResult("没有活动文件")
        val file = active.file
        if (!isAllowed(context, file)) return errorResult("文件不在允许范围内: ${file.absolutePath}")

        val content = args.optString("content", "")
        withContext(Dispatchers.IO) {
            file.writeText(content, Charsets.UTF_8)
        }
        val reloaded = withContext(Dispatchers.Main) { vm.reloadCurrentFile() }
        return textResult(
            JSONObject()
                .put("path", file.absolutePath)
                .put("reloaded", reloaded)
                .put("bytes", content.toByteArray(Charsets.UTF_8).size)
                .toString(2)
        )
    }

    /**
     * 关闭指定文件在编辑器中的标签(仅当它已打开)。
     *
     * 删除/重命名文件后必须调用,否则标签会指向已不存在的路径。
     */
    private suspend fun closeEditorIfOpen(filePath: String): Boolean {
        val vm = EditorBridge.currentViewModel() ?: return false
        return withContext(Dispatchers.Main) {
            val index = vm.openFiles.indexOfFirst { it.file.absolutePath == filePath }
            if (index < 0) return@withContext false
            runCatching { vm.closeFile(index) }.isSuccess
        }
    }

    private suspend fun insertText(args: JSONObject): JSONObject {
        val vm = EditorBridge.currentViewModel() ?: return errorResult("编辑器未打开")
        if (!args.has("text")) return errorResult("缺少参数 text")
        val text = args.optString("text", "")
        withContext(Dispatchers.Main) {
            vm.insertSymbolToCorrectEditor(text)
        }
        return textResult(JSONObject().put("inserted", text.length).toString(2))
    }

    private suspend fun saveFiles(): JSONObject {
        val vm = EditorBridge.currentViewModel() ?: return errorResult("编辑器未打开")
        val saved = withContext(Dispatchers.Main) { vm.saveAllFilesSilently() }
        return textResult(JSONObject().put("saved", saved).toString(2))
    }

    /**
     * 把磁盘内容推回编辑器(仅当该文件已在编辑器中打开)。
     *
     * write_file / create_file 等外部写入之后必须调用,否则编辑器缓冲区仍是旧内容,
     * 用户随后保存会把外部写入覆盖掉。
     */
    private suspend fun syncEditorIfOpen(filePath: String): Boolean {
        val vm = EditorBridge.currentViewModel() ?: return false
        return withContext(Dispatchers.Main) {
            runCatching { vm.refreshEditorFromDisk(filePath) }.getOrDefault(false)
        }
    }

    private suspend fun refreshEditor(context: Context, args: JSONObject): JSONObject {
        val vm = EditorBridge.currentViewModel() ?: return errorResult("编辑器未打开")
        val raw = args.optString("path", "")

        // 缺省刷新全部已打开文件(统一回到"以磁盘为准")
        if (raw.isBlank()) {
            val refreshed = ArrayList<String>()
            for (state in vm.openFiles) {
                val path = state.file.absolutePath
                val ok = withContext(Dispatchers.Main) {
                    runCatching { vm.refreshEditorFromDisk(path) }.getOrDefault(false)
                }
                if (ok) refreshed.add(path)
            }
            return textResult(
                JSONObject()
                    .put("refreshed", refreshed.size)
                    .put("paths", JSONArray(refreshed))
                    .put("editorSynced", refreshed.isNotEmpty())
                    .toString(2)
            )
        }

        val target = resolveProject(context, raw)
        if (!isAllowed(context, target)) return errorResult("路径不在允许范围内: ${target.absolutePath}")
        val ok = withContext(Dispatchers.Main) {
            runCatching { vm.refreshEditorFromDisk(target.absolutePath) }.getOrDefault(false)
        }
        return if (ok) {
            textResult(
                JSONObject()
                    .put("path", target.absolutePath)
                    .put("refreshed", true)
                    .put("editorSynced", true)
                    .toString(2)
            )
        } else {
            errorResult("刷新失败(文件未在编辑器中打开或不可读): ${target.absolutePath}")
        }
    }

    private suspend fun formatCode(): JSONObject {
        val vm = EditorBridge.currentViewModel() ?: return errorResult("编辑器未打开")
        withContext(Dispatchers.Main) { vm.formatCode() }
        return textResult(JSONObject().put("formatted", true).toString(2))
    }

    // ------------------------------------------------------------------
    // 编译 / 构建 / 运行 / 安装 / 备份
    // ------------------------------------------------------------------

    private suspend fun compileFile(context: Context, args: JSONObject): JSONObject {
        val vm = EditorBridge.currentViewModel()
        val raw = args.optString("path", "")
        val activeFile = vm?.activeFileState
        val file = when {
            raw.isNotBlank() -> resolveProject(context, raw)
            activeFile != null -> activeFile.file
            else -> return errorResult("缺少参数 path 且编辑器没有活动文件")
        }
        if (!isAllowed(context, file)) return errorResult("路径不在允许范围内: ${file.absolutePath}")
        if (!file.exists()) return errorResult("文件不存在: ${file.absolutePath}")

        val extension = file.extension.lowercase()
        if (extension != "lua" && extension != "aly") {
            return errorResult("只支持编译 .lua/.aly 文件,当前: .$extension")
        }

        // 先落盘,保证编译的是最新内容
        vm?.let { viewModel -> withContext(Dispatchers.Main) { viewModel.saveAllFilesSilently() } }

        val result = withContext(Dispatchers.IO) {
            var luaState: LuaState? = null
            try {
                val state = LuaStateFactory.newLuaState()
                luaState = state
                state.openLibs()
                val raw = ConsoleUtil.build(state, file.absolutePath)
                val table = raw as? Map<*, *>
                val path = table?.get("path") as? String
                val error = table?.get("error") as? String
                Pair(path, error)
            } catch (e: Exception) {
                LogCatcher.e(TAG, "编译失败: ${file.absolutePath}", e)
                Pair(null, e.message)
            } finally {
                try {
                    luaState?.let { state ->
                        state.gc(LuaState.LUA_GCCOLLECT, 1)
                        state.top = 0
                    }
                } catch (_: Exception) {
                }
            }
        }

        val (compiledPath, errorMsg) = result
        if (compiledPath == null) {
            return errorResult("编译失败: ${errorMsg ?: "未知错误"}")
        }

        // 编译产物(.luac/.alyc)只是语法验证的副产物,不参与项目构建;
        // 默认在验证成功后删除,避免残留污染项目目录。
        val keepOutput = args.optBoolean("keepOutput", false)
        val outputFile = File(compiledPath)
        val outputExists = outputFile.exists() && outputFile.isFile
        var deleted = false
        if (!keepOutput && outputExists && isCompiledArtifact(outputFile) && isAllowed(context, outputFile)) {
            deleted = withContext(Dispatchers.IO) { outputFile.delete() }
        }

        return textResult(
            JSONObject()
                .put("success", true)
                .put("source", file.absolutePath)
                .put("output", compiledPath)
                .put("outputExisted", outputExists)
                .put("outputDeleted", deleted)
                .put("outputKept", outputExists && !deleted)
                .put("note", if (deleted) "编译成功,产物已自动删除(keepOutput=true 可保留)" else "编译成功")
                .toString(2)
        )
    }

    /** 判断是否是编辑器编译产物(.luac/.alyc)。 */
    private fun isCompiledArtifact(file: File): Boolean {
        val name = file.name.lowercase()
        return name.endsWith(".luac") || name.endsWith(".alyc")
    }

    /**
     * 清理项目内的编译产物(.luac/.alyc)。
     *
     * 编译产物由 compile_file / 编辑器"编译文件"生成,不应留在项目里:
     * 它们既会污染文件树,也可能被后续打包误收。
     */
    private suspend fun cleanCompiled(context: Context, args: JSONObject): JSONObject {
        val project = resolveProjectPath(context, args.optString("path", ""))
            ?: return errorResult("无法确定项目路径,请先在前台打开项目或传入 path")
        if (!isAllowed(context, File(project))) return errorResult("路径不在允许范围内: $project")
        val dryRun = args.optBoolean("dryRun", false)

        val deleted = withContext(Dispatchers.IO) {
            val root = File(project)
            if (!root.exists() || !root.isDirectory) return@withContext null
            val artifacts = root.walk()
                .filter { it.isFile && isCompiledArtifact(it) }
                .toList()
            if (dryRun) {
                artifacts.map { it.absolutePath }
            } else {
                artifacts.filter { it.delete() }.map { it.absolutePath }
            }
        } ?: return errorResult("项目目录不存在: $project")

        return textResult(
            JSONObject()
                .put("projectPath", project)
                .put("dryRun", dryRun)
                .put("count", deleted.size)
                .put("files", JSONArray(deleted))
                .toString(2)
        )
    }

    private suspend fun buildApk(context: Context, args: JSONObject): JSONObject {
        val project = resolveProjectPath(context, args.optString("path", ""))
            ?: return errorResult("无法确定项目路径,请先在前台打开项目或传入 path")
        if (!isAllowed(context, File(project))) return errorResult("路径不在允许范围内: $project")
        if (!File(project, "main.lua").exists()) {
            return errorResult("项目缺少 main.lua: $project")
        }
        // 构建前先保存编辑器内的改动
        EditorBridge.currentViewModel()?.let { viewModel ->
            withContext(Dispatchers.Main) { viewModel.saveAllFilesSilently() }
        }

        val result = withContext(Dispatchers.IO) { buildProject(context, project) }
        return if (result.startsWith("error")) {
            errorResult(result)
        } else {
            textResult(
                JSONObject()
                    .put("success", true)
                    .put("projectPath", project)
                    .put("apkPath", result)
                    .put("appName", getAppNameFromSettings(project) ?: JSONObject.NULL)
                    .toString(2)
            )
        }
    }

    private suspend fun runProject(context: Context, args: JSONObject): JSONObject {
        val project = resolveProjectPath(context, args.optString("path", ""))
            ?: return errorResult("无法确定项目路径,请先在前台打开项目或传入 path")
        if (!isAllowed(context, File(project))) return errorResult("路径不在允许范围内: $project")
        val mainLua = File(project, "main.lua")
        if (!mainLua.exists()) return errorResult("项目缺少 main.lua: ${mainLua.absolutePath}")

        EditorBridge.currentViewModel()?.let { viewModel ->
            withContext(Dispatchers.Main) { viewModel.saveAllFilesSilently() }
        }

        return withContext(Dispatchers.Main) {
            try {
                val intent = Intent(context, com.androlua.LuaActivity::class.java).apply {
                    data = Uri.fromFile(mainLua)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                textResult(
                    JSONObject()
                        .put("started", true)
                        .put("entry", mainLua.absolutePath)
                        .toString(2)
                )
            } catch (e: Exception) {
                LogCatcher.e(TAG, "启动运行失败", e)
                errorResult("启动失败: ${e.message}")
            }
        }
    }

    private suspend fun installApk(context: Context, args: JSONObject): JSONObject {
        val raw = args.optString("path", "")
        if (raw.isBlank()) return errorResult("缺少参数 path")
        val file = resolveProject(context, raw)
        if (!isAllowed(context, file)) return errorResult("路径不在允许范围内: ${file.absolutePath}")
        if (!file.exists() || !file.isFile) return errorResult("APK 不存在: ${file.absolutePath}")

        return withContext(Dispatchers.Main) {
            try {
                val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                } else {
                    Uri.fromFile(file)
                }
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    context.packageManager
                        .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                        .forEach { info ->
                            context.grantUriPermission(
                                info.activityInfo.packageName,
                                apkUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                }
                context.startActivity(intent)
                textResult(JSONObject().put("installerLaunched", true).put("apk", file.absolutePath).toString(2))
            } catch (e: Exception) {
                LogCatcher.e(TAG, "安装 APK 失败", e)
                errorResult("安装失败: ${e.message}")
            }
        }
    }

    private suspend fun backupProjectTool(context: Context, args: JSONObject): JSONObject {
        val project = resolveProjectPath(context, args.optString("path", ""))
            ?: return errorResult("无法确定项目路径,请先在前台打开项目或传入 path")
        if (!isAllowed(context, File(project))) return errorResult("路径不在允许范围内: $project")
        val result = withContext(Dispatchers.IO) { backupProject(context, project) }
        return if (result.startsWith("error")) {
            errorResult(result)
        } else {
            textResult(JSONObject().put("success", true).put("backupPath", result).toString(2))
        }
    }

    // ------------------------------------------------------------------
    // 分析 / 日志 / 设置
    // ------------------------------------------------------------------

    private suspend fun analyzeImports(context: Context, args: JSONObject): JSONObject {
        var code = args.optString("content", "")
        if (code.isBlank()) {
            val file = EditorBridge.currentViewModel()?.activeFileState?.file
                ?: return errorResult("缺少参数 content 且编辑器没有活动文件")
            code = withContext(Dispatchers.IO) { file.readText(Charsets.UTF_8) }
        }

        val imports = withContext(Dispatchers.IO) {
            val classMap = CompleteHashmapUtils.loadHashMapFromFile2(context, "classMap.dat")
                ?: return@withContext emptyList<String>()
            val found = LinkedHashSet<String>()
            try {
                val lexer = LuaLexer(code)
                var lastToken: LuaTokenTypes? = null
                while (true) {
                    val token = lexer.advance() ?: break
                    if (lastToken != LuaTokenTypes.DOT && token == LuaTokenTypes.NAME) {
                        val identifier = lexer.yytext()
                        if (identifier.isNotEmpty() && identifier[0].isUpperCase()) {
                            classMap[identifier]?.let { found.addAll(it) }
                        }
                    }
                    lastToken = token
                }
            } catch (e: Exception) {
                LogCatcher.e(TAG, "分析导入失败", e)
            }
            found.sorted()
        }

        val array = JSONArray()
        imports.forEach { array.put(it) }
        val importStatements = imports.joinToString("\n") { "import \"$it\"" }
        return textResult(
            JSONObject()
                .put("count", imports.size)
                .put("classes", array)
                .put("imports", importStatements)
                .toString(2)
        )
    }

    /**
     * 读取文本文件末尾至多 maxBytes 字节。
     *
     * 用 RandomAccessFile.seek 精确定位:InputStream.skip 允许返回小于请求值(甚至 0),
     * 原实现不校验返回值,大文件下会从错误偏移开始读,导致返回内容错位。
     */
    private fun readTailText(file: File, maxBytes: Int): String {
        val len = file.length()
        if (len <= maxBytes) return file.readText(Charsets.UTF_8)
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(len - maxBytes)
            val buf = ByteArray(maxBytes)
            var offset = 0
            while (offset < maxBytes) {
                val n = raf.read(buf, offset, maxBytes - offset)
                if (n <= 0) break
                offset += n
            }
            return String(buf, 0, offset, Charsets.UTF_8)
        }
    }

    private suspend fun getLogs(args: JSONObject): JSONObject {
        val lines = args.optInt("lines", 200).coerceIn(1, 5000)
        val file = File(LOG_FILE_PATH)
        if (!file.exists()) return errorResult("日志文件不存在: $LOG_FILE_PATH")

        val content = withContext(Dispatchers.IO) {
            // 大文件只读取末尾部分,避免占用内存
            val maxBytes = 256 * 1024
            readTailText(file, maxBytes)
        }
        val all = content.lines()
        val tail = if (all.size > lines) all.subList(all.size - lines, all.size) else all
        return textResult(
            JSONObject()
                .put("path", LOG_FILE_PATH)
                .put("lines", tail.size)
                .put("content", tail.joinToString("\n"))
                .toString()
        )
    }

    // ------------------------------------------------------------------
    // 运行时界面检查
    // ------------------------------------------------------------------

    /** 接受 JSON 数组,或换行/逗号分隔的字符串。 */
    private fun stringList(args: JSONObject, key: String): List<String> {
        val array = args.optJSONArray(key)
        if (array != null) {
            val out = ArrayList<String>(array.length())
            for (i in 0 until array.length()) {
                val value = array.optString(i, "")
                if (value.isNotBlank()) out.add(value)
            }
            return out
        }
        val raw = args.optString(key, "")
        if (raw.isBlank()) return emptyList()
        return raw.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private suspend fun dumpScreen(args: JSONObject): JSONObject {
        val includeInvisible = args.optBoolean("includeInvisible", false)
        val maxDepth = args.optInt("maxDepth", 40).coerceIn(1, 100)
        return withContext(Dispatchers.Main) {
            val activity = ActivityTracker.current()
            if (activity == null) {
                errorResult("没有前台 Activity,请先调用 run_project 启动界面")
            } else {
                textResult(RuntimeInspector.dumpScreen(activity, includeInvisible, maxDepth).toString(2))
            }
        }
    }

    private suspend fun checkScreen(args: JSONObject): JSONObject {
        val expectTexts = stringList(args, "expectTexts")
        val expectAnyOf = stringList(args, "expectAnyOf")
        val absentTexts = stringList(args, "absentTexts")
        val minViews = if (args.has("minViews")) args.optInt("minViews", 0) else null
        val includeInvisible = args.optBoolean("includeInvisible", false)

        if (expectTexts.isEmpty() && expectAnyOf.isEmpty() &&
            absentTexts.isEmpty() && minViews == null
        ) {
            return errorResult("至少需要指定 expectTexts / expectAnyOf / absentTexts / minViews 之一")
        }

        return withContext(Dispatchers.Main) {
            val activity = ActivityTracker.current()
            if (activity == null) {
                errorResult("没有前台 Activity,请先调用 run_project 启动界面")
            } else {
                val json = RuntimeInspector.checkScreen(
                    activity, expectTexts, expectAnyOf, absentTexts, minViews, includeInvisible
                )
                val result = textResult(json.toString(2))
                // 未通过时置 isError,便于客户端直接据此判断
                if (!json.optBoolean("passed", false)) result.put("isError", true)
                result
            }
        }
    }

    private suspend fun getRuntimeErrors(args: JSONObject): JSONObject {
        val lines = args.optInt("lines", 50).coerceIn(1, 2000)
        val file = File(LOG_FILE_PATH)
        if (!file.exists()) return errorResult("日志文件不存在: $LOG_FILE_PATH")

        val content = withContext(Dispatchers.IO) {
            val maxBytes = 512 * 1024
            readTailText(file, maxBytes)
        }
        val matched = content.lines().filter { line ->
            line.contains("[ERROR]") || line.contains("[WARN]") ||
                line.contains("Runtime error") || line.contains("traceback") ||
                line.contains("is not a field")
        }
        val tail = if (matched.size > lines) matched.subList(matched.size - lines, matched.size) else matched
        return textResult(
            JSONObject()
                .put("path", LOG_FILE_PATH)
                .put("matched", matched.size)
                .put("lines", tail.size)
                .put("content", tail.joinToString("\n"))
                .toString(2)
        )
    }

    private suspend fun clearLogs(): JSONObject = withContext(Dispatchers.IO) {
        try {
            val file = File(LOG_FILE_PATH)
            if (file.exists()) {
                file.writeText("", Charsets.UTF_8)
                textResult(JSONObject().put("cleared", true).put("path", LOG_FILE_PATH).toString(2))
            } else {
                textResult(
                    JSONObject().put("cleared", false)
                        .put("reason", "日志文件不存在").toString(2)
                )
            }
        } catch (e: Exception) {
            LogCatcher.e(TAG, "清空日志失败", e)
            errorResult("清空日志失败: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // 调试浮窗(debugger.lua)
    // ------------------------------------------------------------------

    /**
     * 读调试浮窗状态。
     *
     * 注入脚本(assets/debugger.lua)在运行 LuaState 上暴露只读全局 `__lfDebugger`(实例),
     * 其 `prints` 字段为缓冲数组。这里在运行实例上按字段读取,与浮窗展示同源。
     */
    private suspend fun debuggerStatus(context: Context, args: JSONObject): JSONObject {
        val activity = resolveRunningActivity(context, args)
            ?: return debuggerNoSession(context)
        return withContext(Dispatchers.Main) {
            val state = activity.getLuaState()
            if (state == null) return@withContext errorResult("运行实例没有 LuaState")
            val prints = readDebuggerPrints(state)
            textResult(
                JSONObject()
                    .put("page", activity.getPageName() ?: JSONObject.NULL)
                    .put("projectPath", activity.getLuaDir() ?: JSONObject.NULL)
                    .put("debuggerActive", prints != null)
                    .put("bufferCount", prints?.size ?: 0)
                    .toString(2)
            )
        }
    }

    private suspend fun debuggerOutputs(context: Context, args: JSONObject): JSONObject {
        val activity = resolveRunningActivity(context, args)
            ?: return debuggerNoSession(context)
        val limit = args.optInt("limit", 200).coerceIn(1, 5000)
        val keyword = args.optString("keyword", "").trim()
        return withContext(Dispatchers.Main) {
            val state = activity.getLuaState()
            if (state == null) return@withContext errorResult("运行实例没有 LuaState")
            val entries = readDebuggerPrints(state) ?: return@withContext errorResult(
                "当前运行实例未注入调试浮窗。请确认项目 settings.json 的 debugmode 为 true 后重新 run_project。"
            )
            val filtered = if (keyword.isEmpty()) entries else entries.filter { it.contains(keyword) }
            val tail = if (filtered.size > limit) filtered.subList(filtered.size - limit, filtered.size) else filtered
            textResult(
                JSONObject()
                    .put("page", activity.getPageName() ?: JSONObject.NULL)
                    .put("projectPath", activity.getLuaDir() ?: JSONObject.NULL)
                    .put("total", entries.size)
                    .put("matched", filtered.size)
                    .put("returned", tail.size)
                    .put("entries", JSONArray(tail))
                    .toString(2)
            )
        }
    }

    private suspend fun debuggerClear(context: Context, args: JSONObject): JSONObject {
        val activity = resolveRunningActivity(context, args)
            ?: return debuggerNoSession(context)
        return withContext(Dispatchers.Main) {
            val state = activity.getLuaState()
            if (state == null) return@withContext errorResult("运行实例没有 LuaState")
            val outcome = runCatching {
                val dbg = state.getLuaObject("__lfDebugger")
                if (dbg == null || dbg.isNil()) error("调试浮窗未注入")
                val fn = state.getLuaObject("__lfDebuggerClear")
                if (fn == null || fn.isNil()) error("调试浮窗清空入口不存在")
                fn._call_aux(arrayOfNulls<Any>(0), 1)
                true
            }
            outcome.fold(
                onSuccess = {
                    textResult(JSONObject().put("cleared", true).toString(2))
                },
                onFailure = { e ->
                    errorResult("清空调试缓冲失败: ${e.message ?: e}")
                }
            )
        }
    }

    private fun debuggerNoSession(context: Context): JSONObject {
        val running = LuaActivity.getRunningActivities()
        return if (running.isEmpty()) {
            errorResult("没有正在运行的项目。请先 run_project 启动调试运行,再读取调试浮窗。")
        } else {
            errorResult(
                "未匹配到运行中的项目(当前运行页面: ${running.keys.joinToString(", ")})。" +
                    "请用 page 指定 pageName,或用 path 指定项目。"
            )
        }
    }

    /** 读取 `__lfDebugger.prints` 缓冲为字符串列表;浮窗未注入 / 非表时返回 null。 */
    private fun readDebuggerPrints(state: LuaState): List<String>? {
        return try {
            val dbg = state.getLuaObject("__lfDebugger")
            if (dbg == null || dbg.isNil()) return null
            val prints = dbg.getField("prints")
            if (prints == null || prints.isNil() || !prints.isTable()) return null
            prints.asArray().map { it?.toString() ?: "" }
        } catch (e: Exception) {
            LogCatcher.e(TAG, "读取调试浮窗缓冲失败", e)
            null
        }
    }

    private fun getSettings(): JSONObject {
        val s = SettingsManager.currentSettings
        return textResult(
            JSONObject()
                .put("themeType", s.themeType.name)
                .put("darkMode", s.darkMode.name)
                .put("fontSizeScale", s.fontSizeScale.toDouble())
                .put("editorFontType", s.editorFontType.name)
                .put("editorWordWrap", s.editorWordWrap)
                .put("indentGuideEnabled", s.indentGuideEnabled)
                .put("enableTabHistory", s.enableTabHistory)
                .put("enableSwipeGesture", s.enableSwipeGesture)
                .put("hexColorHighlightEnabled", s.hexColorHighlightEnabled)
                .put("completionCaseSensitive", s.completionCaseSensitive)
                .put("languageTag", s.languageTag)
                .put("projectStoragePath", s.projectStoragePath)
                .put("mcpEnabled", s.mcpEnabled)
                .put("mcpPort", s.mcpPort)
                .put("mcpRequireToken", s.mcpRequireToken)
                .toString(2)
        )
    }

    // ------------------------------------------------------------------
    // 路径解析与权限
    // ------------------------------------------------------------------

    /** 项目存储根目录(所有允许访问的项目都在其下)。 */
    private fun projectsRoot(context: Context): File = File(FileUtil.getProjectsPath(context))

    /**
     * 额外允许目录:构建输出、备份、日志均位于 /storage/emulated/0/LuaForge-Studio 下。
     */
    private val extraAllowedDirs: List<String> = listOf(
        "/storage/emulated/0/LuaForge-Studio/build",
        "/storage/emulated/0/LuaForge-Studio/backup",
        "/storage/emulated/0/LuaForge-Studio"
    )

    private fun isAllowed(context: Context, file: File): Boolean {
        val target = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        val roots = ArrayList<String>()
        // 可配置的项目根目录
        roots.add(FileUtil.getProjectsPath(context))
        // 日志目录 + 构建/备份输出
        roots.add(runCatching { File(LOG_FILE_PATH).parentFile?.canonicalPath }.getOrNull() ?: "/storage/emulated/0/LuaForge-Studio")
        roots.addAll(extraAllowedDirs)
        return roots.any { root ->
            val normalized = runCatching { File(root).canonicalPath }.getOrElse { root }.trimEnd('/')
            // 过滤空值与文件系统根,避免误放行
            normalized.isNotEmpty() && normalized != "/" &&
                (target == normalized || target.startsWith("$normalized/"))
        }
    }

    /** 解析相对项目根的路径;绝对路径原样返回。 */
    private fun resolveProject(context: Context, raw: String): File {
        if (raw.isBlank()) {
            return resolveProjectPath(context, "")?.let { File(it) } ?: projectsRoot(context)
        }
        val file = File(raw)
        if (file.isAbsolute) return file
        val root = resolveProjectPath(context, "") ?: return file
        return File(root, raw)
    }

    /** 得到项目绝对路径:优先参数,其次当前打开的项目,最后若根目录只有一个项目则使用它。 */
    private fun resolveProjectPath(context: Context, raw: String): String? {
        if (raw.isNotBlank()) {
            val file = File(raw)
            if (file.isAbsolute) return file.absolutePath
            val root = projectsRoot(context)
            return File(root, raw).absolutePath
        }
        EditorBridge.currentProjectPath()?.takeIf { it.isNotBlank() }?.let { return it }

        val root = projectsRoot(context)
        val children = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
        return if (children.size == 1) children[0].absolutePath else null
    }

    // ------------------------------------------------------------------
    // 搜索 / 批量文件操作
    // ------------------------------------------------------------------

    /** 在目录内收集可搜索的文本文件(跳过二进制与构建目录)。 */
    private fun collectTextFiles(root: File, max: Int): List<File> {
        if (!root.exists()) return emptyList()
        val out = ArrayList<File>()
        val queue = ArrayDeque<File>()
        queue.add(root)
        while (queue.isNotEmpty() && out.size < max) {
            val dir = queue.removeFirst()
            val children = dir.listFiles()?.sortedBy { it.name } ?: continue
            for (child in children) {
                if (out.size >= max) break
                if (child.isDirectory) {
                    // 跳过体积大且与代码无关的目录
                    if (child.name == ".git" || child.name == "build" || child.name == "node_modules") continue
                    queue.add(child)
                } else {
                    val name = child.name.lowercase()
                    val isBinary = name.endsWith(".png") || name.endsWith(".jpg") ||
                        name.endsWith(".jpeg") || name.endsWith(".zip") || name.endsWith(".apk") ||
                        name.endsWith(".dex") || name.endsWith(".so") || name.endsWith(".jks") ||
                        name.endsWith(".keystore") || name.endsWith(".luac") || name.endsWith(".alyc")
                    if (!isBinary && child.length() <= MAX_READ_BYTES) out.add(child)
                }
            }
        }
        return out
    }

    private suspend fun searchInFiles(context: Context, args: JSONObject): JSONObject {
        val query = args.optString("query", "")
        if (query.isBlank()) return errorResult("缺少参数 query")
        val root = resolveProject(context, args.optString("path", ""))
        if (!root.exists()) return errorResult("路径不存在: ${root.absolutePath}")
        if (!isAllowed(context, root)) return errorResult("路径不在允许范围内: ${root.absolutePath}")

        val useRegex = args.optBoolean("regex", false)
        val ignoreCase = args.optBoolean("ignoreCase", true)
        val maxResults = args.optInt("maxResults", 200).coerceIn(1, 5000)

        val regex = if (useRegex) {
            try {
                if (ignoreCase) Regex(query, RegexOption.IGNORE_CASE) else Regex(query)
            } catch (e: Exception) {
                return errorResult("正则表达式无效: ${e.message}")
            }
        } else null

        val started = System.currentTimeMillis()
        val matches = withContext(Dispatchers.IO) {
            val result = JSONArray()
            var truncated = false
            val files = if (root.isFile) listOf(root) else collectTextFiles(root, 3000)
            outer@ for (file in files) {
                val text = try {
                    file.readText(Charsets.UTF_8)
                } catch (_: Exception) {
                    continue
                }
                val lines = text.lines()
                for (index in lines.indices) {
                    if (result.length() >= maxResults) {
                        truncated = true
                        break@outer
                    }
                    val line = lines[index]
                    val hit = if (regex != null) {
                        regex.containsMatchIn(line)
                    } else {
                        line.contains(query, ignoreCase = ignoreCase)
                    }
                    if (hit) {
                        result.put(
                            JSONObject()
                                .put("path", file.absolutePath)
                                .put("line", index + 1)
                                .put("text", line.take(500))
                        )
                    }
                }
            }
            Pair(result, truncated)
        }

        return textResult(
            JSONObject()
                .put("query", query)
                .put("regex", useRegex)
                .put("root", root.absolutePath)
                .put("count", matches.first.length())
                .put("truncated", matches.second)
                .put("elapsedMs", System.currentTimeMillis() - started)
                .put("matches", matches.first)
                .toString(2)
        )
    }

    private suspend fun readFiles(context: Context, args: JSONObject): JSONObject {
        val paths = stringList(args, "paths")
        if (paths.isEmpty()) return errorResult("缺少参数 paths")

        val results = JSONArray()
        for (raw in paths) {
            val file = resolveProject(context, raw)
            val item = JSONObject().put("requested", raw).put("path", file.absolutePath)
            when {
                !isAllowed(context, file) -> item.put("error", "路径不在允许范围内")
                !file.exists() || !file.isFile -> item.put("error", "文件不存在")
                file.length() > MAX_READ_BYTES -> item.put("error", "文件过大(${file.length()} 字节)")
                else -> {
                    val content = withContext(Dispatchers.IO) {
                        runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
                    }
                    if (content == null) {
                        item.put("error", "读取失败(可能是二进制文件)")
                    } else {
                        item.put("size", file.length()).put("content", content)
                    }
                }
            }
            results.put(item)
        }

        val ok = (0 until results.length()).count { !results.getJSONObject(it).has("error") }
        return textResult(
            JSONObject()
                .put("requested", paths.size)
                .put("succeeded", ok)
                .put("files", results)
                .toString(2)
        )
    }

    /** 统计并(可选)替换文本:返回出现次数与替换后内容。 */
    private fun applyReplacement(
        source: String,
        find: String,
        replace: String,
        useRegex: Boolean,
        ignoreCase: Boolean
    ): Pair<String, Int> {
        return if (useRegex) {
            val regex = if (ignoreCase) Regex(find, RegexOption.IGNORE_CASE) else Regex(find)
            val count = regex.findAll(source).count()
            Pair(regex.replace(source, replace), count)
        } else {
            var count = 0
            var index = source.indexOf(find, 0, ignoreCase)
            while (index >= 0) {
                count++
                index = source.indexOf(find, index + find.length, ignoreCase)
            }
            val replaced = if (ignoreCase) {
                // 保大小写的字面量替换
                val regex = Regex(Regex.escape(find), RegexOption.IGNORE_CASE)
                regex.replace(source, Regex.escapeReplacement(replace))
            } else {
                source.replace(find, replace)
            }
            Pair(replaced, count)
        }
    }

    private suspend fun replaceInFile(context: Context, args: JSONObject): JSONObject {
        val raw = args.optString("path", "")
        if (raw.isBlank()) return errorResult("缺少参数 path")
        if (!args.has("find")) return errorResult("缺少参数 find")
        val file = resolveProject(context, raw)
        if (!file.exists() || !file.isFile) return errorResult("文件不存在: ${file.absolutePath}")
        if (!isAllowed(context, file)) return errorResult("路径不在允许范围内: ${file.absolutePath}")

        val find = args.optString("find", "")
        if (find.isEmpty()) return errorResult("find 不能为空")
        val replace = args.optString("replace", "")
        val useRegex = args.optBoolean("regex", false)
        val ignoreCase = args.optBoolean("ignoreCase", false)

        val source = withContext(Dispatchers.IO) {
            runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
        } ?: return errorResult("读取失败: ${file.absolutePath}")

        val outcome = try {
            applyReplacement(source, find, replace, useRegex, ignoreCase)
        } catch (e: Exception) {
            return errorResult("正则表达式无效: ${e.message}")
        }
        val (updated, count) = outcome

        if (args.has("expectCount")) {
            val expected = args.optInt("expectCount", 0)
            if (count != expected) {
                return errorResult("出现次数不符: 期望 $expected,实际 $count(未做任何修改)")
            }
        }

        if (count == 0) {
            return textResult(
                JSONObject()
                    .put("path", file.absolutePath)
                    .put("occurrences", 0)
                    .put("changed", false)
                    .put("message", "未找到匹配内容")
                    .toString(2)
            )
        }

        withContext(Dispatchers.IO) { file.writeText(updated, Charsets.UTF_8) }
        val synced = syncEditorIfOpen(file.absolutePath)
        return textResult(
            JSONObject()
                .put("path", file.absolutePath)
                .put("occurrences", count)
                .put("changed", true)
                .put("editorSynced", synced)
                .toString(2)
        )
    }

    private suspend fun replaceInFiles(context: Context, args: JSONObject): JSONObject {
        if (!args.has("find")) return errorResult("缺少参数 find")
        val find = args.optString("find", "")
        if (find.isEmpty()) return errorResult("find 不能为空")
        val replace = args.optString("replace", "")
        val useRegex = args.optBoolean("regex", false)
        val ignoreCase = args.optBoolean("ignoreCase", false)
        val dryRun = args.optBoolean("dryRun", false)

        val root = resolveProject(context, args.optString("path", ""))
        if (!root.exists()) return errorResult("路径不存在: ${root.absolutePath}")

        // 先校验正则,避免扫到一半才失败
        if (useRegex) {
            try {
                if (ignoreCase) Regex(find, RegexOption.IGNORE_CASE) else Regex(find)
            } catch (e: Exception) {
                return errorResult("正则表达式无效: ${e.message}")
            }
        }

        val summary: List<Any> = withContext(Dispatchers.IO) {
            val details = JSONArray()
            var fileCount = 0
            var totalOccurrences = 0
            var skipped = 0
            val files = if (root.isFile) listOf(root) else collectTextFiles(root, 3000)
            for (file in files) {
                if (!isAllowed(context, file)) {
                    skipped++
                    continue
                }
                val source = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
                if (source == null) {
                    skipped++
                    continue
                }
                val (updated, count) = try {
                    applyReplacement(source, find, replace, useRegex, ignoreCase)
                } catch (_: Exception) {
                    skipped++
                    continue
                }
                if (count == 0) continue
                fileCount++
                totalOccurrences += count
                if (!dryRun) file.writeText(updated, Charsets.UTF_8)
                details.put(
                    JSONObject()
                        .put("path", file.absolutePath)
                        .put("occurrences", count)
                )
            }
            listOf(details, fileCount, totalOccurrences, skipped)
        }

        // 批量写入后统一刷新编辑器,避免缓冲区回写覆盖
        if (!dryRun) {
            val vm = EditorBridge.currentViewModel()
            if (vm != null) {
                withContext(Dispatchers.Main) {
                    vm.openFiles.forEach { runCatching { vm.refreshEditorFromDisk(it.file.absolutePath) } }
                }
            }
        }

        return textResult(
            JSONObject()
                .put("root", root.absolutePath)
                .put("dryRun", dryRun)
                .put("changedFiles", summary[1])
                .put("occurrences", summary[2])
                .put("skipped", summary[3])
                .put("files", summary[0])
                .toString(2)
        )
    }

    private suspend fun renameFile(context: Context, args: JSONObject): JSONObject {
        val fromRaw = args.optString("from", "")
        val toRaw = args.optString("to", "")
        if (fromRaw.isBlank() || toRaw.isBlank()) return errorResult("缺少参数 from / to")
        val from = resolveProject(context, fromRaw)
        if (!from.exists()) return errorResult("源路径不存在: ${from.absolutePath}")
        if (!isAllowed(context, from)) return errorResult("源路径不在允许范围内: ${from.absolutePath}")

        val to = resolveProject(context, toRaw)
        if (!isAllowed(context, to)) return errorResult("目标路径不在允许范围内: ${to.absolutePath}")
        // 禁止把目录移入自身
        if (to.absolutePath.startsWith(from.absolutePath + File.separator)) {
            return errorResult("目标路径不能位于源目录内部: ${to.absolutePath}")
        }

        val overwrite = args.optBoolean("overwrite", false)
        val targetExisting = to.exists()

        if (targetExisting && !overwrite) {
            return errorResult("目标已存在(overwrite=false): ${to.absolutePath}")
        }

        val success = withContext(Dispatchers.IO) {
            runCatching {
                to.parentFile?.mkdirs()
                if (targetExisting && overwrite && to.isDirectory) {
                    // 覆盖目录需先清空,否则 renameTo 会失败
                    to.deleteRecursively()
                } else if (targetExisting && overwrite) {
                    to.delete()
                }
                from.renameTo(to)
            }.getOrDefault(false)
        }

        if (!success) return errorResult("重命名失败: ${from.absolutePath} -> ${to.absolutePath}")

        // 同步编辑器:关闭旧标签(路径已失效)
        val closed = closeEditorIfOpen(from.absolutePath)

        return textResult(
            JSONObject()
                .put("from", from.absolutePath)
                .put("to", to.absolutePath)
                .put("renamed", true)
                .put("overwritten", targetExisting)
                .put("editorTabClosed", closed)
                .toString(2)
        )
    }

    private suspend fun makeDirectory(context: Context, args: JSONObject): JSONObject {
        val raw = args.optString("path", "")
        if (raw.isBlank()) return errorResult("缺少参数 path")
        val dir = resolveProject(context, raw)
        if (!isAllowed(context, dir)) return errorResult("路径不在允许范围内: ${dir.absolutePath}")

        val existed = dir.exists()
        val created = withContext(Dispatchers.IO) { dir.mkdirs() || dir.isDirectory }
        if (!created) return errorResult("创建目录失败: ${dir.absolutePath}")

        return textResult(
            JSONObject()
                .put("path", dir.absolutePath)
                .put("existed", existed)
                .put("created", true)
                .toString(2)
        )
    }

    private suspend fun fileInfo(context: Context, args: JSONObject): JSONObject {
        val raw = args.optString("path", "")
        if (raw.isBlank()) return errorResult("缺少参数 path")
        val file = resolveProject(context, raw)
        if (!file.exists()) return errorResult("路径不存在: ${file.absolutePath}")
        if (!isAllowed(context, file)) return errorResult("路径不在允许范围内: ${file.absolutePath}")

        return withContext(Dispatchers.IO) {
            val json = JSONObject()
                .put("path", file.absolutePath)
                .put("name", file.name)
                .put("directory", file.isDirectory)
                .put("size", file.length())
                .put("lastModified", file.lastModified())
                .put("readable", file.canRead())
                .put("writable", file.canWrite())

            if (file.isDirectory) {
                val children = file.listFiles() ?: emptyArray()
                json.put("childCount", children.size)
                    .put("fileCount", children.count { it.isFile })
                    .put("dirCount", children.count { it.isDirectory })
            } else {
                json.put("extension", file.extension.lowercase())
                // 文本文件补充行数/MD5,便于比对
                if (file.length() <= MAX_READ_BYTES) {
                    val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
                    if (text != null) {
                        json.put("lines", text.lineSequence().count())
                        json.put("md5", md5Of(text))
                    }
                }
            }
            textResult(json.toString(2))
        }
    }

    private fun md5Of(text: String): String = try {
        java.security.MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        ""
    }

    // ------------------------------------------------------------------
    // 语法检查 / 编辑器光标
    // ------------------------------------------------------------------

    private suspend fun checkSyntax(context: Context, args: JSONObject): JSONObject {
        val raw = args.optString("path", "")
        val code = when {
            args.has("content") -> args.optString("content", "")
            raw.isNotBlank() -> {
                val file = resolveProject(context, raw)
                if (!file.exists() || !file.isFile) return errorResult("文件不存在: ${file.absolutePath}")
                if (!isAllowed(context, file)) return errorResult("路径不在允许范围内: ${file.absolutePath}")
                withContext(Dispatchers.IO) { runCatching { file.readText(Charsets.UTF_8) }.getOrNull() }
                    ?: return errorResult("读取失败: ${file.absolutePath}")
            }
            else -> EditorBridge.currentViewModel()?.activeFileState?.content
                ?: return errorResult("缺少参数 path/content,且编辑器没有活动文件")
        }

        val target = raw.ifBlank { EditorBridge.currentViewModel()?.activeFileState?.file?.absolutePath ?: "" }
        val lowerName = target.lowercase()
        if (target.isNotBlank() && !lowerName.endsWith(".lua") && !lowerName.endsWith(".aly")) {
            return errorResult("只支持检查 .lua/.aly,当前: ${target.substringAfterLast('.')}")
        }

        val result = withContext(Dispatchers.IO) {
            runCatching { LuaParserUtil.parse(code) }.getOrNull()
        } ?: return errorResult("语法检查器不可用(原生库未加载)")

        val parsed = runCatching { JsonUtil.parseObject(result) }.getOrNull()
            ?: return errorResult("语法检查结果解析失败")

        val status = parsed["status"] as? Boolean ?: false
        val line = (parsed["line"] as? Number)?.toInt() ?: 0
        val message = parsed["message"] as? String ?: ""

        return textResult(
            JSONObject()
                .put("passed", status)
                .put("status", status)
                .put("line", line)
                .put("message", message)
                .put("path", target)
                .put("codeLength", code.length)
                .toString(2)
        )
    }

    private fun getSelection(): JSONObject {
        val vm = EditorBridge.currentViewModel() ?: return errorResult("编辑器未打开")
        val state = vm.activeFileState ?: return errorResult("没有活动文件")
        val editor = vm.getActiveEditor() ?: return errorResult("活动编辑器尚未创建")
        return textResult(
            JSONObject()
                .put("path", state.file.absolutePath)
                .put("fileName", state.file.name)
                .put("isModified", state.isModified)
                .put("cursorLine", editor.cursor.leftLine)
                .put("cursorColumn", editor.cursor.leftColumn)
                .put("selectionEndLine", editor.cursor.rightLine)
                .put("hasSelection", editor.cursor.isSelected)
                .put("selectedText", runCatching {
                    val left = editor.cursor.left
                    val right = editor.cursor.right
                    if (right > left) editor.text.subSequence(left, right).toString() else ""
                }.getOrDefault(""))
                .toString(2)
        )
    }

    private suspend fun editorHistory(args: JSONObject): JSONObject {
        val vm = EditorBridge.currentViewModel() ?: return errorResult("编辑器未打开")
        if (vm.activeFileState == null) return errorResult("没有活动文件")
        val action = args.optString("action", "undo").lowercase()
        if (action != "undo" && action != "redo") return errorResult("action 只支持 undo / redo")

        withContext(Dispatchers.Main) {
            if (action == "undo") vm.undo() else vm.redo()
        }
        return textResult(JSONObject().put("action", action).put("done", true).toString(2))
    }

    private suspend fun gotoLine(args: JSONObject): JSONObject {
        val vm = EditorBridge.currentViewModel() ?: return errorResult("编辑器未打开")
        val state = vm.activeFileState ?: return errorResult("没有活动文件")
        val line = args.optInt("line", 0)
        if (line <= 0) return errorResult("line 必须大于 0(1 起算)")
        val column = args.optInt("column", 0).coerceAtLeast(0)

        return withContext(Dispatchers.Main) {
            val editor = vm.getActiveEditor()
                ?: return@withContext errorResult("活动编辑器尚未创建,无法跳转")
            try {
                val lineCount = editor.text.lineCount
                val targetLine = (line - 1).coerceIn(0, maxOf(0, lineCount - 1))
                val lineLength = editor.text.getColumnCount(targetLine)
                val targetColumn = column.coerceIn(0, lineLength)
                editor.setSelection(targetLine, targetColumn)
                textResult(
                    JSONObject()
                        .put("path", state.file.absolutePath)
                        .put("line", targetLine + 1)
                        .put("column", targetColumn)
                        .put("moved", true)
                        .toString(2)
                )
            } catch (e: Exception) {
                LogCatcher.e(TAG, "跳转行失败", e)
                errorResult("跳转失败: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------
    // 运行时等待 / 项目创建 / 备份还原
    // ------------------------------------------------------------------

    private suspend fun waitForText(args: JSONObject): JSONObject {
        val text = args.optString("text", "")
        if (text.isBlank()) return errorResult("缺少参数 text")
        val absent = args.optBoolean("absent", false)
        val timeoutMs = args.optInt("timeoutMs", 10000).coerceIn(200, 60000)
        val intervalMs = args.optInt("intervalMs", 500).coerceIn(50, 5000)

        val started = System.currentTimeMillis()
        var lastTexts: List<String> = emptyList()

        while (System.currentTimeMillis() - started < timeoutMs) {
            val texts = withContext(Dispatchers.Main) {
                val activity = ActivityTracker.current() ?: return@withContext null
                RuntimeInspector.collectVisibleTexts(activity, false)
            }
            if (texts == null) {
                delay(intervalMs.toLong())
                continue
            }
            lastTexts = texts
            val present = texts.any { it.contains(text) }
            if (present != absent) {
                return textResult(
                    JSONObject()
                        .put("text", text)
                        .put("absent", absent)
                        .put("matched", true)
                        .put("elapsedMs", System.currentTimeMillis() - started)
                        .put("texts", JSONArray(texts.take(50)))
                        .toString(2)
                )
            }
            delay(intervalMs.toLong())
        }

        val description = if (absent) "在 $timeoutMs ms 内未消失" else "在 $timeoutMs ms 内未出现"
        // 与 check_screen 一致:保留结构化结果,同时置 isError 便于客户端直接判定
        return textResult(
            JSONObject()
                .put("text", text)
                .put("absent", absent)
                .put("matched", false)
                .put("elapsedMs", System.currentTimeMillis() - started)
                .put("texts", JSONArray(lastTexts.take(50)))
                .put("message", "文本$description")
                .toString(2)
        ).put("isError", true)
    }

    private suspend fun listTemplates(context: Context): JSONObject {
        val deferred = CompletableDeferred<List<TemplateItem>>()
        ProjectUtil.loadTemplates(context) { deferred.complete(it) }
        val templates = withTimeoutOrNull(8000) { deferred.await() } ?: emptyList()

        val array = JSONArray()
        templates.forEach { item ->
            array.put(
                JSONObject()
                    .put("name", item.name)
                    .put("zipFileName", item.zipFileName)
                    .put("hasPreview", item.previewUri != null)
            )
        }
        return textResult(
            JSONObject()
                .put("count", templates.size)
                .put("templates", array)
                .toString(2)
        )
    }

    private suspend fun createProject(context: Context, args: JSONObject): JSONObject {
        val root = projectsRoot(context)
        val name = args.optString("name", "").ifBlank {
            ProjectUtil.generateDefaultProjectName(root)
        }
        if (name.contains('/') || name.contains('\\')) {
            return errorResult("项目名不能包含路径分隔符: $name")
        }

        val packageName = args.optString("packageName", "").ifBlank {
            ProjectUtil.generatePackageName(name)
        }
        if (!ProjectUtil.isValidPackageName(packageName)) {
            return errorResult("包名无效: $packageName")
        }

        val projectDir = File(root, name)
        val overwrite = args.optBoolean("overwrite", false)
        if (projectDir.exists()) {
            if (!overwrite) return errorResult("项目已存在(overwrite=false): ${projectDir.absolutePath}")
            if (!isAllowed(context, projectDir)) {
                return errorResult("路径不在允许范围内: ${projectDir.absolutePath}")
            }
            val cleared = withContext(Dispatchers.IO) { projectDir.deleteRecursively() }
            if (!cleared) return errorResult("无法清空已存在的项目目录: ${projectDir.absolutePath}")
        }

        val debugMode = args.optBoolean("debugMode", false)
        val globalUtils = stringList(args, "globalUtils")
        val templateName = args.optString("template", "")

        // 复用 UI 侧同一套模板检索逻辑,保证模板名与新建界面一致
        var template: TemplateItem? = null
        if (templateName.isNotBlank()) {
            val deferred = CompletableDeferred<List<TemplateItem>>()
            ProjectUtil.loadTemplates(context) { deferred.complete(it) }
            val templates = withTimeoutOrNull(8000) { deferred.await() } ?: emptyList()
            template = templates.firstOrNull {
                it.zipFileName.equals(templateName, ignoreCase = true) ||
                    it.name.equals(templateName, ignoreCase = true) ||
                    it.zipFileName.equals("$templateName.zip", ignoreCase = true)
            } ?: return errorResult(
                "模板不存在: $templateName(可用: ${templates.joinToString { it.zipFileName }})"
            )
        }

        val created = withContext(Dispatchers.IO) {
            runCatching {
                projectDir.mkdirs()
                template?.let {
                    ProjectUtil.extractTemplate(context, it, projectDir, name, packageName, debugMode)
                }
                ProjectUtil.saveSettingsFile(projectDir, name, packageName, debugMode, globalUtils)
                if (template == null) {
                    // 无模板时补一个可运行的 main.lua
                    ProjectUtil.createDefaultMainLuaFile(projectDir, name)
                }
                true
            }.getOrElse { e ->
                LogCatcher.e(TAG, "创建项目失败: $name", e as? Exception ?: Exception(e))
                false
            }
        }

        if (!created) return errorResult("创建项目失败: ${projectDir.absolutePath}")

        return textResult(
            JSONObject()
                .put("name", name)
                .put("path", projectDir.absolutePath)
                .put("packageName", packageName)
                .put("template", template?.zipFileName ?: JSONObject.NULL)
                .put("debugMode", debugMode)
                .put("created", true)
                .toString(2)
        )
    }

    private suspend fun restoreBackup(context: Context, args: JSONObject): JSONObject {
        val raw = args.optString("backupPath", "")
        if (raw.isBlank()) return errorResult("缺少参数 backupPath")
        val backup = File(raw)
        if (!backup.exists() || !backup.isFile) return errorResult("备份文件不存在: ${backup.absolutePath}")
        if (!isAllowed(context, backup)) {
            return errorResult("备份文件不在允许范围内: ${backup.absolutePath}")
        }
        if (!backup.name.lowercase().endsWith(".zip")) {
            return errorResult("只支持还原 .zip 备份: ${backup.name}")
        }

        val projectName = args.optString("projectName", "").ifBlank {
            // 备份命名规则: 项目名_yyyyMMdd_HHmmss.zip
            backup.nameWithoutExtension.replace(Regex("_\\d{8}_\\d{6}$"), "")
        }
        if (projectName.isBlank()) return errorResult("无法从备份文件名推导项目名,请显式传入 projectName")

        val targetDir = File(projectsRoot(context), projectName)
        if (!isAllowed(context, targetDir)) {
            return errorResult("目标路径不在允许范围内: ${targetDir.absolutePath}")
        }

        val overwrite = args.optBoolean("overwrite", false)
        if (targetDir.exists()) {
            if (!overwrite) return errorResult("项目已存在(overwrite=false): ${targetDir.absolutePath}")
            val cleared = withContext(Dispatchers.IO) { targetDir.deleteRecursively() }
            if (!cleared) return errorResult("无法清空已存在的项目目录: ${targetDir.absolutePath}")
        }

        val restored = withContext(Dispatchers.IO) {
            targetDir.mkdirs()
            FileUtil.extractZip(backup, targetDir)
        }
        if (!restored) return errorResult("还原失败: ${backup.absolutePath}")

        // 关闭编辑器中可能残留的同名文件标签
        val vm = EditorBridge.currentViewModel()
        if (vm != null) {
            val prefix = targetDir.absolutePath + File.separator
            val stale = vm.openFiles.map { it.file.absolutePath }.filter { it.startsWith(prefix) }
            withContext(Dispatchers.Main) {
                stale.forEach { path ->
                    val index = vm.openFiles.indexOfFirst { it.file.absolutePath == path }
                    if (index >= 0) runCatching { vm.closeFile(index) }
                }
            }
        }

        return textResult(
            JSONObject()
                .put("backupPath", backup.absolutePath)
                .put("projectName", projectName)
                .put("projectPath", targetDir.absolutePath)
                .put("restored", true)
                .toString(2)
        )
    }

    // ------------------------------------------------------------------
    // global_utils(全局工具类)
    // ------------------------------------------------------------------

    /** 读取项目 settings.json 中的 global_utils。 */
    private fun readGlobalUtils(settingsFile: File): List<String> {
        if (!settingsFile.exists() || !settingsFile.isFile) return emptyList()
        return try {
            val map = JsonUtil.parseObject(settingsFile.readText())
            val raw = map["global_utils"] as? List<*>
            raw?.mapNotNull { it?.toString()?.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        } catch (e: Exception) {
            LogCatcher.e(TAG, "读取 global_utils 失败: ${settingsFile.absolutePath}", e)
            emptyList()
        }
    }

    /**
     * 查看项目的 global_utils 以及运行时**实际会注册**的 Lua 全局函数。
     *
     * 注册清单来自 [LuaFunctionRegistrar.describeFunctions],与运行时反射注册共用同一套规则,
     * 因此不会与运行结果漂移。
     */
    private suspend fun listGlobalUtils(context: Context, args: JSONObject): JSONObject {
        val project = resolveProjectPath(context, args.optString("path", ""))
            ?: return errorResult("无法确定项目路径,请先在前台打开项目或传入 path")
        if (!isAllowed(context, File(project))) return errorResult("路径不在允许范围内: $project")
        val settingsFile = File(project, "settings.json")
        if (!settingsFile.exists()) {
            return errorResult("项目缺少 settings.json: ${settingsFile.absolutePath}")
        }

        val selected = readGlobalUtils(settingsFile)

        val functions = JSONArray()
        LuaFunctionRegistrar.describeFunctions(selected).forEach { info ->
            functions.put(
                JSONObject()
                    .put("name", info.name)
                    .put("source", info.source)
                    .put("params", JSONArray(info.params))
                    .put("returns", info.returnType)
                    .put("varArgs", info.varArgs)
                    .put("contextInjected", info.contextInjected)
                    .put("overrides", info.overrides ?: JSONObject.NULL)
                    .put("note", info.note ?: JSONObject.NULL)
            )
        }

        val running = LuaActivity.getRunningActivities()
        val pages = JSONArray()
        running.forEach { (page, activity) ->
            pages.put(
                JSONObject()
                    .put("page", page)
                    .put("luaDir", activity.getLuaDir() ?: JSONObject.NULL)
                    .put("isCurrentProject", sameDir(activity.getLuaDir(), project))
            )
        }

        return textResult(
            JSONObject()
                .put("projectPath", project)
                .put("global_utils", JSONArray(selected))
                .put("available", JSONArray(LuaFunctionRegistrar.getUtilNames()))
                // 名字拼错时运行时只会跳过并不会报错,这里显式点出来
                .put(
                    "unknown",
                    JSONArray(selected.filter { it !in LuaFunctionRegistrar.getUtilNames() })
                )
                .put("registeredFunctions", functions)
                .put("runningPages", pages)
                .put(
                    "note",
                    "registeredFunctions 按当前 settings.json 推导运行时会注册的 Lua 全局函数;" +
                        "overrides 表示同名函数被后注册者覆盖(如 get/post/upload/download);" +
                        "修改 global_utils 后需重新运行项目才生效。调用请用 call_global_util。"
                )
                .toString(2)
        )
    }

    /**
     * 在**运行中**的项目里调用 global_utils 注册的 Lua 全局函数。
     *
     * 走 LuaObject.call 而非 LuaActivity.runFunc:后者有 isFunction 守卫,而 global_utils
     * 注册的是带 __call 元方法的 userdata,lua_isfunction 为 false,会被静默跳过。
     */
    private suspend fun callGlobalUtil(context: Context, args: JSONObject): JSONObject {
        val name = args.optString("name", "").trim()
        if (name.isBlank()) return errorResult("缺少参数 name")

        val running = LuaActivity.getRunningActivities()
        val activity = resolveRunningActivity(context, args)
        if (activity == null) {
            if (running.isEmpty()) {
                return errorResult("没有正在运行的项目。请先 run_project 启动调试运行,再调用 global_utils 函数。")
            }
            val pages = running.keys.joinToString(", ")
            return errorResult("未匹配到运行中的项目(当前运行页面: $pages)。请用 page 指定 pageName,或用 path 指定项目。")
        }

        val luaDir = activity.getLuaDir()
        if (luaDir.isNullOrBlank()) return errorResult("运行实例没有项目目录,无法读取 global_utils")

        val selected = readGlobalUtils(File(luaDir, "settings.json"))
        val registered = LuaFunctionRegistrar.describeFunctions(selected)
        val info = registered.firstOrNull { it.name == name }
        if (info == null) {
            val hint = if (registered.isEmpty()) {
                "当前 global_utils = $selected"
            } else {
                "可用函数: " + registered.joinToString(", ") { it.name }
            }
            return errorResult(
                "函数 $name 未由该项目的 global_utils 注册,无法调用。$hint"
            )
        }

        // 手工注册的 http / recycler 函数需要 Lua 回调或控件对象,JSON 表达不了。
        // 尤其 http 函数必须在后台线程执行才会走网络审批,放这里会绕过审批,因此直接拒绝。
        if (info.source == "http") {
            return errorResult(
                "$name 是网络请求函数:需要 Lua 回调,且必须在后台线程执行以走网络审批流程,不支持经 MCP 调用。请在项目代码中调用。"
            )
        }
        if (info.source == "recycler") {
            return errorResult("$name 需要 Lua 回调或控件对象作参数,不支持经 MCP 调用。请在项目代码中调用。")
        }

        val method = LuaFunctionRegistrar.findUtilMethod(name, selected)
        if (method != null) {
            val unsupported = unsupportedParam(method, info.contextInjected)
            if (unsupported != null) {
                return errorResult(
                    "$name 的参数类型 $unsupported 需要控件或 Java 对象,无法经 JSON 传入。" +
                        "请在项目代码中调用。签名: $name(${info.params.joinToString(", ")})"
                )
            }
        }

        val luaArgs = parseLuaArgs(args)

        if (method != null) {
            val luaSideCount = method.parameterTypes.size - (if (info.contextInjected) 1 else 0)
            val min = if (method.isVarArgs) (luaSideCount - 1).coerceAtLeast(0) else luaSideCount
            val max = if (method.isVarArgs) Int.MAX_VALUE else luaSideCount
            if (luaArgs.size < min || luaArgs.size > max) {
                val expect = if (method.isVarArgs) "至少 $min 个" else "$min 个"
                return errorResult(
                    "参数个数不符:$name 需要 $expect(Lua 侧),实际 ${luaArgs.size} 个。" +
                        "签名: $name(${info.params.joinToString(", ")})"
                )
            }
        }

        val state = activity.getLuaState() ?: return errorResult("运行实例没有 LuaState")
        val timeoutMs = args.optInt("timeoutMs", 5000).coerceIn(200, 30000)

        // 与项目自身调用一致:在运行实例的主线程上执行,因此 UI 相关函数也能正常生效。
        // 代价是脚本忙时本调用需排队,超时后如实报告"尚未完成"。
        val outcome = withTimeoutOrNull(timeoutMs.toLong()) {
            withContext(Dispatchers.Main) {
                runCatching {
                    val fn = state.getLuaObject(name)
                    if (fn == null || fn.isNil()) {
                        error("全局函数 $name 当前不存在(项目可能尚未初始化完成,或注册失败)")
                    }
                    if (!fn.isFunction() && !fn.isTable() && !fn.isUserdata()) {
                        error("全局变量 $name 不是可调用的对象")
                    }
                    fn._call_aux(luaArgs.toTypedArray(), LuaState.LUA_MULTRET).toList()
                }
            }
        } ?: return errorResult(
            "调用 $name 超时(${timeoutMs}ms)。Lua 主线程可能正忙,调用尚未返回;请稍后重试。"
        )

        val results = outcome.getOrElse { e ->
            return errorResult("调用 $name 失败: ${e.message ?: e.toString()}")
        }

        val values = JSONArray()
        results.forEach { values.put(luaValueToJson(it)) }

        return textResult(
            JSONObject()
                .put("name", name)
                .put("page", activity.getPageName() ?: JSONObject.NULL)
                .put("projectPath", luaDir)
                .put("returnCount", results.size)
                .put("result", results.firstOrNull()?.let { luaValueToJson(it) } ?: JSONObject.NULL)
                .put("results", values)
                .put("note", "调用在运行实例主线程执行(与项目自身调用同一路径),会受脚本忙碌程度影响。")
                .toString(2)
        )
    }

    /** 定位要调用的运行实例:page 优先,其次 path,再退化为当前项目 / 唯一页面。 */
    private fun resolveRunningActivity(context: Context, args: JSONObject): LuaActivity? {
        val page = args.optString("page", "").trim()
        if (page.isNotBlank()) return LuaActivity.getActivity(page)

        val running = LuaActivity.getRunningActivities()
        if (running.isEmpty()) return null

        val raw = args.optString("path", "").trim()
        if (raw.isNotBlank()) {
            val project = resolveProjectPath(context, raw) ?: return null
            return LuaActivity.getActivityByLuaDir(project)
        }

        EditorBridge.currentProjectPath()?.takeIf { it.isNotBlank() }?.let { current ->
            LuaActivity.getActivityByLuaDir(current)?.let { return it }
        }
        return if (running.size == 1) running.values.first() else null
    }

    /** 返回第一个无法经 JSON 表达的形参类型名;全部可表达时返回 null。 */
    private fun unsupportedParam(method: Method, contextInjected: Boolean): String? {
        val types = method.parameterTypes
        for ((index, type) in types.withIndex()) {
            // 首个 Context 形参由框架注入,不需要 Lua 侧传
            if (contextInjected && index == 0) continue
            val target = if (method.isVarArgs && index == types.size - 1) type.componentType else type
            if (target != null && target !in SCALAR_PARAM_TYPES) return target.simpleName
        }
        return null
    }

    /** 解析实参:JSON 数组保留类型;分隔字符串按字面量推断。 */
    private fun parseLuaArgs(args: JSONObject): List<Any?> {
        val array = args.optJSONArray("args")
        if (array != null) {
            return (0 until array.length()).map { index -> jsonArgToJava(array.opt(index)) }
        }
        val raw = args.opt("args")
        val text = when (raw) {
            null, JSONObject.NULL -> ""
            is String -> raw
            else -> raw.toString()
        }
        if (text.isBlank()) return emptyList()
        return text.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }.map { inferScalar(it) }
    }

    private fun jsonArgToJava(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is Boolean, is String, is Number -> value
        else -> value.toString()
    }

    private fun inferScalar(text: String): Any? = when {
        text.equals("true", true) -> true
        text.equals("false", true) -> false
        text.equals("nil", true) || text.equals("null", true) -> null
        INTEGER_ARG.matches(text) -> text.toLongOrNull() ?: text
        DECIMAL_ARG.matches(text) -> text.toDoubleOrNull() ?: text
        else -> text
    }

    /** 把 Lua 返回值转成 org.json 可序列化的形式。 */
    private fun luaValueToJson(value: LuaObject): Any {
        return try {
            when {
                value.isNil() -> JSONObject.NULL
                value.isBoolean() -> value.getBoolean()
                value.isInteger() -> value.getInteger()
                value.isNumber() -> value.getNumber()
                value.isString() -> value.getString()
                value.isTable() -> toJsonValue(value.getTable())
                value.isUserdata() -> {
                    val obj = runCatching { value.getObject() }.getOrNull()
                    if (obj == null) value.toString() else toJsonValue(obj)
                }
                else -> value.toString()
            }
        } catch (e: Exception) {
            "无法转换返回值: ${e.message}"
        }
    }

    /** 保证结果能被 org.json 序列化:非 JSON 类型退化为字符串。 */
    private fun toJsonValue(value: Any?): Any {
        val safe = toJsonSafe(value)
        // JSONObject.NULL 不是 JSONObject 实例,若落到 toString() 会变成字符串 "null"
        if (safe === JSONObject.NULL) return JSONObject.NULL
        return if (safe is JSONObject || safe is JSONArray || safe is String ||
            safe is Boolean || safe is Number
        ) safe else safe.toString()
    }

    private fun sameDir(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        return runCatching { File(a).canonicalPath == File(b).canonicalPath }.getOrDefault(false)
    }

    // ------------------------------------------------------------------
    // JSON 辅助
    // ------------------------------------------------------------------

    private fun textResult(text: String): JSONObject =
        JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            .put("isError", false)

    private fun errorResult(message: String): JSONObject =
        JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message)))
            .put("isError", true)

    private fun tool(name: String, description: String, properties: JSONObject, required: Array<String> = emptyArray()): JSONObject =
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

    /**
     * 把 Map/List/基本类型递归转换为 JSONObject/JSONArray。
     *
     * Android 的 org.json 在 toString 时遇到未知类型(如 HashMap)会抛异常,
     * 因此解析 settings.json 得到的嵌套 Map 必须先转换。
     */
    private fun toJsonSafe(value: Any?): Any {
        return when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> {
                val o = JSONObject()
                value.forEach { (k, v) -> if (k != null) o.put(k.toString(), toJsonSafe(v)) }
                o
            }
            is Iterable<*> -> {
                val a = JSONArray()
                value.forEach { a.put(toJsonSafe(it)) }
                a
            }
            is Array<*> -> {
                val a = JSONArray()
                value.forEach { a.put(toJsonSafe(it)) }
                a
            }
            else -> value
        }
    }

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
