package com.luaforge.studio.mcp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.androlua.LuaLexer
import com.androlua.LuaTokenTypes
import com.luaforge.studio.langs.lua.tools.CompleteHashmapUtils
import com.luaforge.studio.ui.editor.backupProject
import com.luaforge.studio.ui.editor.buildProject
import com.luaforge.studio.ui.editor.getAppNameFromSettings
import com.luaforge.studio.ui.settings.SettingsManager
import com.luaforge.studio.utils.ConsoleUtil
import com.luaforge.studio.utils.FileUtil
import com.luaforge.studio.utils.LogCatcher
import com.luaforge.studio.utils.ProjectUtil
import com.luajava.LuaState
import com.luajava.LuaStateFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
                    "line" to intProp("打开后跳转的行号(可选,从 1 开始)")
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
                "编译单个 .lua/.aly 文件(等价于编辑器“编译文件”)",
                obj("path" to strProp("文件路径,缺省为当前活动文件"))
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
                "compile_file" -> compileFile(context, args)
                "build_apk" -> buildApk(context, args)
                "run_project" -> runProject(context, args)
                "install_apk" -> installApk(context, args)
                "backup_project" -> backupProjectTool(context, args)
                "analyze_imports" -> analyzeImports(context, args)
                "get_logs" -> getLogs(args)
                "get_settings" -> getSettings()
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
        return textResult(
            JSONObject()
                .put("path", file.absolutePath)
                .put("bytes", content.toByteArray(Charsets.UTF_8).size)
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
        return textResult(
            JSONObject()
                .put("path", file.absolutePath)
                .put("created", true)
                .put("overwritten", existedBefore)
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
        return if (deleted) {
            textResult(JSONObject().put("path", file.absolutePath).put("deleted", true).toString(2))
        } else {
            errorResult("删除失败(目录非空或无权限): ${file.absolutePath}")
        }
    }

    private suspend fun getProjectInfo(context: Context, args: JSONObject): JSONObject {
        val project = resolveProjectPath(context, args.optString("path", ""))
            ?: return errorResult("无法确定项目路径,请先在前台打开项目或传入 path")
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
        if (!file.exists() || !file.isFile) return errorResult("文件不存在: ${file.absolutePath}")

        withContext(Dispatchers.Main) {
            vm.openFile(file, project)
        }
        val line = args.optInt("line", 0)
        if (line > 0) {
            withContext(Dispatchers.Main) {
                vm.getActiveEditor()?.post { vm.getActiveEditor()?.setSelection(line - 1, 0) }
            }
        }
        return textResult(JSONObject().put("opened", file.absolutePath).put("line", line).toString(2))
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
        return if (compiledPath != null) {
            textResult(
                JSONObject()
                    .put("success", true)
                    .put("source", file.absolutePath)
                    .put("output", compiledPath)
                    .toString(2)
            )
        } else {
            errorResult("编译失败: ${errorMsg ?: "未知错误"}")
        }
    }

    private suspend fun buildApk(context: Context, args: JSONObject): JSONObject {
        val project = resolveProjectPath(context, args.optString("path", ""))
            ?: return errorResult("无法确定项目路径,请先在前台打开项目或传入 path")
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

    private suspend fun getLogs(args: JSONObject): JSONObject {
        val lines = args.optInt("lines", 200).coerceIn(1, 5000)
        val file = File(LOG_FILE_PATH)
        if (!file.exists()) return errorResult("日志文件不存在: $LOG_FILE_PATH")

        val content = withContext(Dispatchers.IO) {
            // 大文件只读取末尾部分,避免占用内存
            val maxBytes = 256 * 1024
            if (file.length() <= maxBytes) {
                file.readText(Charsets.UTF_8)
            } else {
                file.inputStream().use { stream ->
                    stream.skip(file.length() - maxBytes)
                    String(stream.readBytes(), Charsets.UTF_8)
                }
            }
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
