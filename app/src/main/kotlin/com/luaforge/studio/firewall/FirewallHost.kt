package com.luaforge.studio.firewall

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import com.androlua.FirewallGate
import com.androlua.LuaActivity
import com.androlua.LuaSessionHook
import com.luajava.LuaState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.luaforge.studio.R
import com.luaforge.studio.mcp.ActivityTracker
import com.luaforge.studio.ui.settings.FirewallKind
import com.luaforge.studio.ui.settings.SettingsManager
import com.luaforge.studio.utils.LogCatcher
import java.io.File

/**
 * 防火墙宿主(app 模块)。
 *
 * 判定引擎 [FirewallGate] 在 core 模块,本类只在 IDE 进程里安装会话钩子:
 * 每次运行项目时把 assets/firewall.lua 注入该页 LuaState,并预热网关。
 * 用户项目打包出的 APK 只含 core 模块,从不安装钩子 -> 网关恒 inactive,零行为污染。
 */
object FirewallHost : LuaSessionHook {

    private const val TAG = "FirewallHost"

    private const val CONTAINER_DIR = "LuaForge-Studio"

    @Volatile
    private var appContext: Context? = null

    @Synchronized
    fun install(context: Context) {
        appContext = context.applicationContext
        LuaActivity.setSessionHook(this)
    }

    override fun onSessionStart(L: LuaState, luaDir: String, luaPath: String, debugMode: Boolean) {
        // 仅 IDE 调试会话注入;非调试运行(模拟产物形态)不介入
        if (!debugMode) return

        // 仅真实项目会话:布局预览等的 luaDir 是应用私有目录,
        // 若也激活会把真实项目目录误判为“其它项目”而拦截。
        if (!File(luaDir, "settings.json").exists()) return

        val context = appContext
        if (context == null) {
            LogCatcher.w(TAG, "未初始化宿主上下文,跳过防火墙注入")
            return
        }

        // 脚本缺失/读取失败 -> 不注入,网关保持上一次状态(isActive 判定由 reconfigure 决定)
        val script = try {
            context.assets.open("firewall.lua").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            LogCatcher.e(TAG, "读取 firewall.lua 失败", e)
            null
        }

        if (script != null) {
            try {
                L.LdoString(script)
            } catch (e: Exception) {
                LogCatcher.e(TAG, "注入 firewall.lua 失败", e)
            }
        }

        try {
            val settings = SettingsManager.currentSettings
            val defaultRoot = File(Environment.getExternalStorageDirectory(), CONTAINER_DIR).absolutePath
            val projectsContainer = settings.projectStoragePath.ifBlank {
                File(defaultRoot, "project").absolutePath
            }
            // 守护根=项目容器的父目录(默认 LuaForge-Studio)，
            // 用户自定义存储路径时仍能正确覆盖容器根
            val storageRoot = File(projectsContainer).parentFile?.absolutePath ?: defaultRoot
            FirewallGate.reconfigure(
                storageRoot,
                projectsContainer,
                luaDir,
                settings.crossProjectWriteGuard,
                settings.selfGuard,
                object : FirewallGate.Reporter {
                    override fun onBlock(kind: Int, projectName: String?, target: String?) {
                        Handler(Looper.getMainLooper()).post {
                            try {
                                SettingsManager.recordFirewallGuard(
                                    if (kind == 1) FirewallKind.CROSS_WRITE else FirewallKind.SELF_GUARD,
                                    projectName ?: "",
                                    context
                                )
                                showFirewallDialog(context, kind, projectName, target)
                            } catch (e: Exception) {
                                LogCatcher.e(TAG, "处理防火墙拦截事件失败", e)
                            }
                        }
                    }
                }
            )
        } catch (e: Exception) {
            LogCatcher.e(TAG, "预热防火墙网关失败", e)
        }
    }

    /** 防火墙拦截弹窗:双行说明 + 仅「好的」按钮。 */
    private fun showFirewallDialog(context: Context, kind: Int, projectName: String?, target: String?) {
        val host = ActivityTracker.current()
        if (host == null || host.isFinishing || host.isDestroyed) return
        try {
            val title = context.getString(
                if (kind == 1) R.string.firewall_block_cross_title else R.string.firewall_block_self_title
            )
            val msg = buildString {
                appendLine(context.getString(R.string.firewall_block_msg_project, projectName ?: ""))
                appendLine(target ?: "")
                appendLine()
                append(
                    if (kind == 1) {
                        context.getString(R.string.firewall_block_msg_cross)
                    } else {
                        val root = File(target ?: "").let { it.parentFile ?: it }.name
                        context.getString(
                            R.string.firewall_block_msg_self,
                            root.ifEmpty { CONTAINER_DIR }
                        )
                    }
                )
            }
            MaterialAlertDialogBuilder(host)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(R.string.ok, null)
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            LogCatcher.e(TAG, "展示防火墙拦截弹窗失败", e)
        }
    }
}
