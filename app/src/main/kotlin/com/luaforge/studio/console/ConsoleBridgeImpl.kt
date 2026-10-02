package com.luaforge.studio.console

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.luaforge.studio.console.core.ConsoleBridge
import com.luaforge.studio.console.core.ConsoleRegistry
import com.luaforge.studio.console.core.ConsoleSettings
import com.luaforge.studio.console.core.ConsoleState
import com.luaforge.studio.console.core.EventTracker
import com.luaforge.studio.console.core.FileStateTracker
import com.luaforge.studio.console.core.MethodCallResult
import com.luaforge.studio.console.core.SessionInfo
import com.luaforge.studio.console.core.SessionManager
import com.luaforge.studio.console.core.StateMachine
import com.luaforge.studio.console.env.LuaEnvironment
import com.luaforge.studio.console.env.ModuleTracker
import com.luaforge.studio.console.intercept.NewActivityInterceptor
import com.luaforge.studio.console.logcat.LogcatManager
import com.luaforge.studio.console.output.OutputEntry
import com.luaforge.studio.console.output.OutputManager
import com.luaforge.studio.console.output.TypeResolver
import com.luaforge.studio.console.persist.ConsolePaths
import com.luaforge.studio.console.persist.CrashCapture
import com.luaforge.studio.console.persist.SessionArchiver
import com.luaforge.studio.console.ui.ConsolePanelView
import com.luaforge.studio.console.ui.OverlayController
import com.luaforge.studio.utils.JsonUtil
import com.luajava.LuaState
import java.io.File
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 调试控制台桥实现:路由会话/输出/拦截到各管理器。
 *
 * 本类只存在于 IDE(app 模块)。打包产物(core-apk)不含本类与 ConsoleRegistry,
 * core 侧 ConsoleBridgeRef 反射失败 → 全部钩点短路,运行路径与体积零影响。
 *
 * 防火墙不在本类注入:LuaForge-Studio 已由 FirewallHost(LuaSessionHook)承担,
 * 避免重复注入同一页 LuaState。
 */
class ConsoleBridgeImpl(private val context: Context) : ConsoleBridge {

    private val settings = ConsoleSettings(context)
    private val overlay = OverlayController(context)
    private val newActivityInterceptor = NewActivityInterceptor(context) { primary ->
        // newActivity 允许后重放失败(目标文件被删等) → error 条目入缓冲,不复播 toast
        reportError(primary)
    }

    /** 未读 Lua 错误计数 → 浮球右上角角标;打开面板 / 清空当前缓冲时清零。 */
    private val errorUnread = AtomicInteger(0)

    /**
     * 弹窗采集:make 登记实例→文本;show() 配对即时输出;当前 lua 文件切换时 dump 残留。
     *
     * 回调来自 Lua 线程(不同页面/LuaState 可为不同线程),dump 锚点又在 onMethodCall 中触发,
     * 故全部访问经 [popupLock] 串行化:裸 WeakHashMap 并发读写会致结构损坏或迭代死循环。
     */
    private val popupLock = Any()
    private val pendingPopups = WeakHashMap<Any, PopupInfo>()
    private var lastDumpFile: String? = null

    /** 弹窗登记信息:展示文本 + 类型(快照于 make 时,show/dump 时按开关门控输出)。 */
    private data class PopupInfo(val text: String, val snackbar: Boolean)

    /** 会话门控:仅 debugmode 项目激活捕获(非调试会话零捕获)。 */
    @Volatile
    private var active = false

    init {
        CrashCapture.install()
        CrashCapture.onCrash = {
            Handler(Looper.getMainLooper()).post { overlay.setBallRed(true) }
        }
        // 报错 toast 由设置项门控(默认关),同步到 core 注册表(LuaActivity.sendError 读取)
        ConsoleRegistry.setErrorToastEnabled(settings.toastLuaErrors)
        // 拦截器总开关须以持久化设置初始化(否则重建桥后回落默认 true,开关显示关仍拦截)
        newActivityInterceptor.enabled = settings.interceptNavigation
        // 打开控制台面板 → 未读错误角标清零(打开即视为已读)
        StateMachine.addListener(object : StateMachine.Listener {
            override fun onStateChanged(old: ConsoleState, new: ConsoleState) {
                if (new == ConsoleState.PANEL) clearErrorBadge()
            }
        })
        registerForegroundCallbacks()
    }

    /** 错误条目入缓冲 + 未读计数 +1 → 浮球角标更新。线程安全(onError 来自 Lua 线程)。 */
    private fun reportError(primary: String) {
        appendEntry(OutputEntry.LABEL_ERROR, primary)
        errorUnread.incrementAndGet()
        Handler(Looper.getMainLooper()).post { overlay.setErrorCount(errorUnread.get()) }
    }

    /** 清空未读错误角标(面板打开 / 清空当前文件缓冲)。 */
    fun clearErrorBadge() {
        errorUnread.set(0)
        Handler(Looper.getMainLooper()).post { overlay.setErrorCount(0) }
    }

    /** 设置页「拦截界面跳转/结束请求」开关:同步拦截器总开关(默认开,即时生效)。 */
    fun setInterceptEnabled(v: Boolean) {
        newActivityInterceptor.enabled = v
    }

    /** 前后台感知:后台藏球+面板强收,前台按 BALL 态恢复;会话/logcat 不中断。 */
    private fun registerForegroundCallbacks() {
        val app = context.applicationContext as? Application ?: return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) {
                if (++started == 1) onForeground()
            }

            override fun onActivityStopped(activity: Activity) {
                if (--started <= 0) {
                    started = 0
                    onBackground()
                }
            }

            override fun onActivityResumed(activity: Activity) {
                SessionManager.onHostResumed(activity)
                if (active && StateMachine.state == ConsoleState.BALL && !overlay.isBallShowing()) {
                    overlay.showBall()
                }
            }

            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

            override fun onActivityDestroyed(activity: Activity) {
                SessionManager.onHostDestroyed(activity)
                overlay.onHostDestroyed(activity)
                if (active && StateMachine.state == ConsoleState.BALL && !overlay.isBallShowing()) {
                    overlay.showBall()
                }
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }

    private fun onForeground() {
        if (!active) return
        // 状态自愈:PANEL 但无 sheet(宿主销毁/泄漏竞态) → 回 BALL 重建
        if (StateMachine.state == ConsoleState.PANEL && !overlay.isSheetShowing()) {
            StateMachine.transition(ConsoleState.BALL)
        }
        if (StateMachine.state == ConsoleState.BALL) overlay.showBall()
    }

    private fun onBackground() {
        if (!active) return
        overlay.hideForBackground()
    }

    override fun onSessionStart(info: SessionInfo) {
        // 每次会话入/重启/换项目均重同步拦截开关
        newActivityInterceptor.enabled = settings.interceptNavigation
        val prev = SessionManager.current
        val fresh = SessionManager.begin(info)
        if (fresh) {
            if (prev != null && active) {
                SessionArchiver.archive(prev)
                LogcatManager.stop()
            }
            // 项目级输出隔离:新会话清空缓冲池,杜绝上一项目输出混入本会话
            OutputManager.clearAll()
            active = info.debugMode
            ConsolePaths.init(context)
            if (active) LogcatManager.start(info.luaDir, projectName(info))
            injectDebugParams(info)
        }
        // 每页上下文刷新(页面相关,跨页会话持续)
        FileStateTracker.updateFromSession(info)
        LuaEnvironment.probe(info.luaState)
        OutputManager.currentFile = info.luaPath ?: ""
        // 主线程早期 print(游标建立前落入兜底缓冲)并入当前文件,保证主线程输出可见
        OutputManager.rebaseCatchAll(OutputManager.currentFile)
        if (!active) return
        if (fresh) {
            StateMachine.transition(ConsoleState.BALL)
            overlay.showBall()
            overlay.setErrorCount(errorUnread.get())
        } else if (StateMachine.state == ConsoleState.BALL && !overlay.isBallShowing()) {
            overlay.showBall()
        }
    }

    override fun onSessionEnd(info: SessionInfo) {
        val last = SessionManager.detach(info)
        if (!last) return
        if (active) {
            SessionArchiver.archive(info)
            overlay.closeAll()
            StateMachine.transition(ConsoleState.IDLE)
            LogcatManager.stop()
        }
        active = false
        EventTracker.clear()
        ModuleTracker.clear()
        ConsolePanelView.persistedTab = 0
        SessionManager.end()
    }

    private fun projectName(info: SessionInfo): String =
        (info.luaDir?.let { File(it).name }?.ifBlank { null }) ?: "project"

    /** 跨 Intent 的 debugParams JSON → Lua 全局 debugParams 真 table(须在 doFile 前调用)。 */
    private fun injectDebugParams(info: SessionInfo) {
        val json = info.debugParamsJson ?: return
        if (json.isBlank()) return
        val map = try {
            JsonUtil.parseObject(json)
        } catch (_: Exception) {
            return
        }
        try {
            info.luaState.newTable()
            pushMap(info.luaState, map)
            info.luaState.setGlobal("debugParams")
        } catch (_: Exception) {
        }
    }

    private fun pushMap(state: LuaState, map: Map<String, Any?>) {
        for ((k, v) in map) {
            pushValue(state, v)
            state.setField(-2, k)
        }
    }

    private fun pushValue(state: LuaState, v: Any?) {
        when (v) {
            null -> state.pushNil()
            is Boolean -> state.pushBoolean(v)
            is Int -> state.pushInteger(v.toLong())
            is Long -> state.pushInteger(v)
            is Number -> state.pushNumber(v.toDouble())
            is String -> state.pushString(v)
            is Map<*, *> -> {
                state.newTable()
                for ((k, value) in v) {
                    pushValue(state, value)
                    state.setField(-2, k?.toString() ?: "")
                }
            }
            is List<*> -> {
                state.newTable()
                v.forEachIndexed { i, value ->
                    pushValue(state, value)
                    state.setField(-2, (i + 1).toString())
                }
            }
            else -> state.pushObjectValue(v)
        }
    }

    override fun onPrint(text: String?, luaTypes: IntArray?, rawArgs: Array<out Any?>?) {
        if (!settings.capturePrint) return
        // 不 gate active:主线程顶层 chunk 的 print 可能先于 onSessionStart 到达(游标未建),
        // 一律入兜底缓冲,会话建立后由 rebaseCatchAll 并入当前文件;非调试会话随后 clearAll 清空。
        val depth = settings.parseDepth
        val l1 = ArrayList<String>(luaTypes?.size ?: 0)
        val l2 = ArrayList<String>(luaTypes?.size ?: 0)
        val contents = ArrayList<String>(luaTypes?.size ?: 0)
        val fullContents = ArrayList<String>(luaTypes?.size ?: 0)
        if (luaTypes != null) {
            for (i in luaTypes.indices) {
                val r = TypeResolver.resolve(luaTypes[i], rawArgs?.getOrNull(i), depth)
                l1 += r.level1
                l2 += r.type
                contents += r.content
                fullContents += r.full ?: r.content
            }
        }
        val primary = if (luaTypes != null) contents.joinToString("\t") else (text ?: "")
        val fullText = if (luaTypes != null) fullContents.joinToString("\t") else (text ?: "")
        appendEntry(OutputEntry.LABEL_PRINT, primary, l1, l2, fullText = fullText)
    }

    override fun onPopupCaptured(instance: Any, text: String?, snackbar: Boolean) {
        if (!active) return
        synchronized(popupLock) { pendingPopups[instance] = PopupInfo(text ?: "", snackbar) }
    }

    override fun onPopupShown(instance: Any) {
        if (!active) return
        val info = synchronized(popupLock) { pendingPopups.remove(instance) }
        if (info != null) outputPopup(info)
    }

    /** 当前 lua 文件切换锚点触发:残留「从未 show」的弹窗一次性落缓冲。 */
    private fun dumpPendingPopups() {
        // 锁内只做搬运,输出放锁外:outputPopup 会读设置并写输出缓冲,不宜持锁调用
        val drained: List<PopupInfo> = synchronized(popupLock) {
            if (pendingPopups.isEmpty()) {
                emptyList()
            } else {
                val out = ArrayList<PopupInfo>(pendingPopups.size)
                val it = pendingPopups.entries.iterator()
                while (it.hasNext()) {
                    out.add(it.next().value)
                    it.remove()
                }
                out
            }
        }
        for (info in drained) outputPopup(info)
    }

    /** 弹窗输出:按类型开关门控(不标注是否调用 show,仅捕获内容)。 */
    private fun outputPopup(info: PopupInfo) {
        val want = if (info.snackbar) settings.captureSnackbar else settings.captureToast
        if (!want) return
        val label = if (info.snackbar) OutputEntry.LABEL_SNACKBAR else OutputEntry.LABEL_TOAST
        appendEntry(label, info.text)
    }

    override fun onError(title: String?, message: String?) {
        if (!active) return
        val primary = if (title.isNullOrBlank()) (message ?: "") else "$title: ${message ?: ""}"
        reportError(primary)
    }

    override fun onMethodCall(
        receiver: Any?,
        methodName: String?,
        args: Array<out Any?>?,
        luaState: Long
    ): MethodCallResult {
        if (!active) return MethodCallResult.ALLOW
        // 弹窗残留 dump 锚点:当前 lua 文件变化(会话游标切换) → 上一文件未 show 的弹窗一次性落缓冲
        val curFile = OutputManager.currentFile
        if (curFile != lastDumpFile) {
            lastDumpFile = curFile
            dumpPendingPopups()
        }
        // 观察 setContentView(布局判定);显式字符串参数直传,否则靠 require 模块推 .aly
        if (methodName == "setContentView" && receiver is Activity) {
            FileStateTracker.onSetContentView(args?.firstOrNull() as? String)
        }
        // newActivity 阻塞确认 / 同参丢弃 / 异参列表单选(弹窗用调用方 Activity 作 context)
        return newActivityInterceptor.intercept(receiver as? Activity, methodName, args)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!active) return false
        // IDLE(会话意外隐藏)/ CLOSED(显式关闭)状态下按音量下键恢复浮球,其余状态放行系统音量。
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN &&
            (StateMachine.state == ConsoleState.IDLE || StateMachine.state == ConsoleState.CLOSED)
        ) {
            StateMachine.transition(ConsoleState.BALL)
            overlay.showBall()
            return true
        }
        return false
    }

    override fun onEvent(funcName: String?, args: Array<out Any?>?) {
        if (!active) return
        EventTracker.record(
            file = FileStateTracker.relativePath.ifBlank { OutputManager.currentFile },
            funcName = funcName ?: "?",
            args = args,
            timeMs = System.currentTimeMillis(),
            isMainThread = Looper.getMainLooper().thread === Thread.currentThread()
        )
    }

    override fun onRequire(moduleName: String?, funcParams: Map<String, Int>?, nativeModule: Boolean) {
        if (!active) return
        ModuleTracker.recordRequire(OutputManager.currentFile, moduleName, funcParams, nativeModule)
    }

    override fun onBindClass(className: String?, clazz: Class<*>?) {
        if (!active) return
        ModuleTracker.recordBindClass(OutputManager.currentFile, className, clazz)
    }

    /** 浮球真实显示态(OverlayController 持有窗口引用)。 */
    override fun isBallShowing(): Boolean = overlay.isBallShowing()

    /** 浮窗面板真实显示态。 */
    override fun isPanelShowing(): Boolean = overlay.isSheetShowing()

    private fun appendEntry(
        label: String,
        primary: String,
        luaTypes: List<String> = emptyList(),
        typeDetails: List<String> = emptyList(),
        fullText: String? = null
    ) {
        OutputManager.append(
            OutputEntry(
                id = OutputManager.nextId(),
                file = OutputManager.currentFile,
                relFile = FileStateTracker.relativePath.ifBlank { OutputManager.currentFile },
                label = label,
                primary = primary,
                luaTypes = luaTypes,
                typeDetails = typeDetails,
                isMainThread = Looper.getMainLooper().thread === Thread.currentThread(),
                timestampMs = System.currentTimeMillis(),
                fullText = fullText
            )
        )
    }
}
