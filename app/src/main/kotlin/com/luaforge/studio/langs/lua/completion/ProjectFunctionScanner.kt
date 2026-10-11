package com.luaforge.studio.langs.lua.completion

import java.io.File

/**
 * 扫描项目内的 Lua/ALY 源文件,提取以 `function` 声明的函数,供跨文件补全使用。
 *
 * - 只收集全局函数声明(`function name(...)`)。`local function` 只作用于文件内部,
 *   跨文件不可见,而当前文件的标识符已由增量分析器提供,故这里只补跨文件的部分。
 * - 形如 `function M.foo(...)` / `function M:foo(...)` 的方法定义,按「表名 -> 成员」归类,
 *   交给已有的包补全(输入 `M.` 时提示成员,提交为 `M.foo`)。
 *
 * 目标是「尽力而为」的补全,不做完整 Lua 语法解析:单文件读取失败或少量误判不影响整体。
 */
object ProjectFunctionScanner {

    data class Result(
        /** 全局函数名 -> 描述。 */
        val functions: Map<String, String>,
        /** 表名 -> 成员函数名列表。 */
        val packages: Map<String, List<String>>
    )

    /** 匹配 `function <name>` / `function <a.b.c>`,名称部分允许 `.` 与 `:` 分隔。 */
    private val FUNCTION_DEF = Regex(
        """^\s*function\s+([A-Za-z_]\w*(?:\s*[.:]\s*[A-Za-z_]\w*)*)\s*\("""
    )

    /** 扫描时跳过的目录:构建产物与 IDE 元数据。 */
    private val SKIP_DIRS = setOf("build", ".git", ".idea", ".gradle")

    private const val DESC_PROJECT_FUNCTION = "项目函数"

    fun scan(projectRoot: File): Result {
        if (!projectRoot.isDirectory) return Result(emptyMap(), emptyMap())

        val functions = LinkedHashMap<String, String>()
        val packages = LinkedHashMap<String, MutableSet<String>>()

        val stack = ArrayDeque<File>()
        stack.addLast(projectRoot)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                val name = child.name
                when {
                    child.isDirectory -> {
                        if (name.startsWith(".") || name in SKIP_DIRS) continue
                        stack.addLast(child)
                    }
                    child.isFile && child.canRead() && isLuaSource(name) ->
                        scanFile(child, functions, packages)
                }
            }
        }

        return Result(functions, packages.mapValues { it.value.toList() })
    }

    private fun isLuaSource(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".lua") || lower.endsWith(".aly")
    }

    private fun scanFile(
        file: File,
        functions: MutableMap<String, String>,
        packages: MutableMap<String, MutableSet<String>>
    ) {
        try {
            var inBlockComment = false
            file.forEachLine { raw ->
                if (inBlockComment) {
                    // 长注释延续到 `]]` 为止;闭合行直接跳过(行内残留代码极罕见,忽略)
                    if (raw.contains("]]")) inBlockComment = false
                    return@forEachLine
                }
                val trimmed = raw.trimStart()
                if (trimmed.startsWith("--")) {
                    // `--[[` / `--[=[` 开启长注释
                    if (trimmed.contains("[[") && !trimmed.contains("]]")) inBlockComment = true
                    return@forEachLine
                }
                val match = FUNCTION_DEF.find(raw) ?: return@forEachLine
                val full = match.groupValues[1].replace(" ", "")
                val lastSep = full.lastIndexOfAny(charArrayOf('.', ':'))
                if (lastSep <= 0 || lastSep >= full.length - 1) {
                    functions[full] = DESC_PROJECT_FUNCTION
                } else {
                    // `M.foo` -> 表 M 的成员 foo;`a.b.c` 归到表 `a.b`
                    val table = full.substring(0, lastSep)
                    val member = full.substring(lastSep + 1)
                    packages.getOrPut(table) { LinkedHashSet() }.add(member)
                }
            }
        } catch (e: Exception) {
            // 单文件读取失败不影响整体扫描
        }
    }
}
