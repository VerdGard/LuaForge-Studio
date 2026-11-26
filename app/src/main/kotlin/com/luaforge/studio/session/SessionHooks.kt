package com.luaforge.studio.session

import android.content.Context
import com.luaforge.studio.debugger.DebuggerHost
import com.luaforge.studio.utils.LogCatcher

/**
 * IDE 会话钩子的统一安装入口。
 *
 * <p>为什么需要它:调试浮窗(debugger.lua 注入)经 `LuaActivity.addSessionHook`
 * 安装。此前**只有 `SplashWelcome.onCreate` 会安装** —— 这是进程启动路径上的
 * 唯一入口,而它不是唯一的运行入口:
 *
 * <ul>
 *   <li>MCP 的 `run_project` 直接从当前界面 `startActivity(LuaActivity)`;
 *       MCP 前台保活服务是 `START_STICKY`,进程被系统回收后会**重建服务但不会
 *       重走 SplashWelcome**,此时再从 MCP 触发运行,钩子列表是空的 ——
 *       调试浮窗不注入。</li>
 *   <li>同理,任何从后台任务/通知恢复到运行页的路径都可能绕过启动页。</li>
 * </ul>
 *
 * <p>因此安装动作改为**幂等**的 `ensure(context)`,在启动页、主界面、以及
 * MCP 启动运行之前各调用一次。各个 Host 的 install 本身是幂等的
 * (`addSessionHook` 内部去重),重复调用无副作用。
 */
object SessionHooks {

    private const val TAG = "SessionHooks"

    @Volatile
    private var installed = false

    /** 幂等安装:`SplashWelcome` / `MainActivity` / MCP 启动运行前都可安全调用。 */
    @Synchronized
    fun ensure(context: Context) {
        if (installed) return
        try {
            // 调试浮窗:运行 debugmode 项目时注入 debugger.lua 并展示浮窗
            DebuggerHost.install(context)
        } catch (e: Exception) {
            LogCatcher.e(TAG, "初始化调试浮窗失败", e)
        }
        installed = true
    }
}
