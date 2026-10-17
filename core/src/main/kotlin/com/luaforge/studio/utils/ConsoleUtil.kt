package com.luaforge.studio.utils

import androidx.annotation.Keep
import com.luajava.LuaException
import com.luajava.LuaState
import java.io.File

@Keep
object ConsoleUtil {
    /**
     * 编译脚本文件（Lua 或 ALY）
     * @param state Lua 状态
     * @param path 文件路径
     * @return Lua table 对象 {path, error}
     */
    @JvmStatic
    fun build(state: LuaState, path: String): Any {
        synchronized(state) {
            try {
                // 检查文件是否存在
                val file = File(path)
                if (!file.exists()) {
                    throw LuaException("File does not exist: $path")
                }

                // 获取文件扩展名
                val extension = getFileExtension(file)

                // 根据扩展名判断类型
                return when (extension.lowercase()) {
                    "lua" -> compileLuaFile(state, path)
                    "aly" -> compileALYFile(state, path)
                    else -> throw LuaException("Unsupported file type: .$extension")
                }
            } catch (e: LuaException) {
                // 将 Lua 异常信息返回给 Lua 层
                return createResultTable(state, null, e.message)
            } catch (e: Exception) {
                // 将其他异常信息返回给 Lua 层
                return createResultTable(state, null, "Internal error: ${e.message}")
            }
        }
    }

    /**
     * 获取文件扩展名
     */
    private fun getFileExtension(file: File): String {
        val name = file.name
        val dotIndex = name.lastIndexOf('.')
        return if (dotIndex > 0 && dotIndex < name.length - 1) {
            name.substring(dotIndex + 1)
        } else {
            ""
        }
    }

    /**
     * 创建结果 table
     */
    private fun createResultTable(state: LuaState, path: String?, error: String?): Any {
        // 保存当前栈顶位置
        val topBefore = state.top

        // 创建一个新的 Lua table
        state.newTable()

        // 设置 path 字段
        if (path != null) {
            state.pushString(path)
            state.setField(-2, "path")
        } else {
            state.pushNil()
            state.setField(-2, "path")
        }

        // 设置 error 字段
        if (error != null) {
            state.pushString(error)
            state.setField(-2, "error")
        } else {
            state.pushNil()
            state.setField(-2, "error")
        }

        // 现在栈顶是创建的 table，确保只有一个元素
        val newTop = state.top
        if (newTop != topBefore + 1) {
            // 栈状态异常，清理并返回空 table
            state.top = topBefore
            state.newTable()
        }

        // 获取 table 对象
        try {
            val table = state.toJavaObject(-1)
            // 弹出 table
            state.pop(1)
            return table ?: "null"
        } catch (e: Exception) {
            // 如果无法获取 table 对象，返回一个简单的 Map
            state.pop(state.top - topBefore)  // 清理栈
            val resultMap = HashMap<String, Any?>()
            resultMap["path"] = path
            resultMap["error"] = error
            return resultMap
        }
    }

    /**
     * 编译 Lua 文件成字节码。
     *
     * <p>必须走 [LuaState.LloadFile],**不能**用 Lua 侧的 `loadfile`:
     * 用户脚本可能用了 Compose DSL 的命名参数(`Text("x", size = 20)`),
     * 那不是合法 Lua 语法,要靠 [LuaState.LloadFile] 里的
     * [com.luaforge.studio.lua.LuaSugar] 改写后才能通过。
     * 早期实现把编译逻辑写成一段 Lua 代码再 `loadfile`,绕过了这层改写,
     * 结果带命名参数的项目在「打包成 APK」时一律编译失败。
     */
    private fun compileLuaFile(state: LuaState, path: String): Any {
        return withCleanStack(state) {
            try {
                val ok = state.LloadFile(path)
                if (ok != 0) {
                    val err = state.toString(-1)
                    state.pop(1)
                    return@withCleanStack createResultTable(state, null, err)
                }
                dumpTopAndWrite(state, File(path + "c"))
            } catch (e: Exception) {
                createResultTable(state, null, "Internal error: ${e.message}")
            }
        }
    }

    /**
     * 编译 ALY(布局表)成字节码。
     *
     * <p>ALY 文件本身是一个布局 table 字面量,需要包一层 `local layout=... return layout`
     * 才能作为 chunk 编译。拼接在**字节层**完成(ISO-8859-1 往返),
     * 这样源文件是 GBK 还是 UTF-8 都不会被破坏;
     * 再交给 [LuaState.LloadBuffer] —— 它同样带命名参数改写。
     */
    private fun compileALYFile(state: LuaState, path: String): Any {
        return withCleanStack(state) {
            try {
                val raw = File(path).readBytes()
                val prefix = "local layout=".toByteArray(Charsets.ISO_8859_1)
                val suffix = "\nreturn layout".toByteArray(Charsets.ISO_8859_1)
                val wrapped = prefix + raw + suffix
                val ok = state.LloadBuffer(wrapped, File(path).name)
                if (ok != 0) {
                    val err = state.toString(-1)
                    state.pop(1)
                    return@withCleanStack createResultTable(state, null, err)
                }
                dumpTopAndWrite(state, File(path + "c"))
            } catch (e: Exception) {
                createResultTable(state, null, "Internal error: ${e.message}")
            }
        }
    }

    /**
     * 把栈顶函数 dump 成字节码并写盘;栈顶函数会被弹出。
     *
     * @return 结果 table(`path` 为产物路径,失败时 `error` 为原因)
     */
    private fun dumpTopAndWrite(state: LuaState, out: File): Any {
        val dumped = state.dump(-1)
        state.pop(1)
        if (dumped == null || dumped.isEmpty()) {
            return createResultTable(state, null, "无法生成字节码(栈顶不是可编译的函数)")
        }
        out.writeBytes(dumped)
        return createResultTable(state, out.absolutePath, null)
    }

    /**
     * 在干净的栈上执行编译,结束后把栈恢复到进入时的深度。
     *
     * [ApkBuilder] 复用一个共享 [LuaState] 连续编译整个项目的文件,
     * 任何一次编译残留的栈元素都会累积,最终把虚拟机栈挤爆
     * (报 "stack overflow")。旧实现在 finally 里直接 `top = 0`,
     * 这里改为按进入深度恢复,既能清理又不破坏调用方已有的栈内容。
     */
    private inline fun withCleanStack(state: LuaState, block: () -> Any): Any {
        val entryTop = state.top
        return try {
            block()
        } finally {
            state.top = entryTop
        }
    }
}
