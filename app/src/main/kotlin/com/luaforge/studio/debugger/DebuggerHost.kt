package com.luaforge.studio.debugger

import android.content.Context
import com.androlua.LuaActivity
import com.androlua.LuaSessionHook
import com.luaforge.studio.utils.LogCatcher
import com.luajava.LuaState
import java.io.File

/**
 * 调试浮窗宿主(app 模块)。
 *
 * 彻底替换旧「调试控制台」:调试运行(debugmode 项目)时,把 assets/debugger.lua 注入
 * 该页 LuaState 并实例化浮窗。脚本会接管全局 `print` 与 `onError`,因此项目里的所有
 * print 输出与 Lua 报错都进入浮窗缓冲(并经 LuaActivity.sendMsg 落盘 luaforge.log)。
 *
 * 同时暴露只读全局供 IDE / MCP 读取现场:
 * - `__lfDebugger`        浮窗实例
 * - `__lfDebuggerCount()` 当前缓冲条数
 * - `__lfDebuggerDump()`  返回缓冲数组
 * - `__lfDebuggerClear()` 清空缓冲
 *
 * 类只存在于 IDE(app 模块):打包产物(core-apk)不安装本钩子,零开销、零行为污染。
 */
object DebuggerHost : LuaSessionHook {

    private const val TAG = "DebuggerHost"
    private const val ASSET = "debugger.lua"

    /** 注入脚本后立即实例化并展示浮窗;class 引用用后即弃,避免污染全局。 */
    private const val BOOTSTRAP = """
local cls = __lfDebuggerClass
local dbg = cls(activity)
dbg:showFloatWindow()
_G.__lfDebugger = dbg
_G.__lfDebuggerDump = function ()
  local out = {}
  local prints = dbg.prints
  for i = 1, #prints do out[i] = tostring(prints[i]) end
  return out
end
_G.__lfDebuggerCount = function () return #(dbg.prints) end
_G.__lfDebuggerClear = function () dbg.prints = {} return true end
_G.__lfDebuggerClass = nil
"""

    private const val DESTROY = """
if _G.__lfDebugger then
  pcall(function () _G.__lfDebugger:destroy() end)
  _G.__lfDebugger = nil
end
_G.__lfDebuggerDump = nil
_G.__lfDebuggerCount = nil
_G.__lfDebuggerClear = nil
"""

    @Volatile
    private var appContext: Context? = null

    @Synchronized
    fun install(context: Context) {
        appContext = context.applicationContext
        LuaActivity.addSessionHook(this)
    }

    override fun onSessionStart(
        L: LuaState,
        luaDir: String,
        luaPath: String,
        debugMode: Boolean,
        toolLaunch: Boolean
    ) {
        // 仅调试运行;工具型启动(布局助手预览)不注入浮窗
        if (toolLaunch || !debugMode) return
        val context = appContext ?: return
        // 仅真实项目会话(与防火墙同判据):布局预览等私有目录不注入
        if (!File(luaDir, "settings.json").exists()) return

        val script = try {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            LogCatcher.e(TAG, "读取 $ASSET 失败", e)
            return
        }

        val bootstrap = buildString(script.length + BOOTSTRAP.length + 32) {
            append("__lfDebuggerClass = (function ()\n")
            append(script)
            append("\nend)()\n")
            append(BOOTSTRAP)
        }

        try {
            synchronized(L) {
                val bytes = bootstrap.toByteArray(Charsets.UTF_8)
                if (L.LloadBuffer(bytes, ASSET) == 0) {
                    if (L.pcall(0, 0, 0) != 0) {
                        LogCatcher.e(TAG, "调试浮窗注入执行失败: ${L.toString(-1)}")
                    }
                } else {
                    LogCatcher.e(TAG, "调试浮窗注入编译失败: ${L.toString(-1)}")
                }
                L.setTop(0)
            }
        } catch (e: Exception) {
            LogCatcher.e(TAG, "调试浮窗注入失败", e)
        }
    }

    override fun onSessionEnd(L: LuaState) {
        try {
            synchronized(L) {
                val bytes = DESTROY.toByteArray(Charsets.UTF_8)
                if (L.LloadBuffer(bytes, "debugger_destroy") == 0) {
                    L.pcall(0, 0, 0)
                }
                L.setTop(0)
            }
        } catch (e: Exception) {
            LogCatcher.e(TAG, "移除调试浮窗失败", e)
        }
    }
}
