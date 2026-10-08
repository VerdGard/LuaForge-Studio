package com.luaforge.studio.utils

import android.content.Context
import androidx.annotation.Keep
import com.androlua.LuaApplication
import com.androlua.LuaContext
import java.io.File

/**
 * Python 嵌入工具类(真 CPython,非子集解释器)。
 *
 * 由 `libpython.so` 提供实现,两种使用方式并存:
 *  - `global_utils` 含 "PythonUtil" 时,本类的 public static 方法会被注册为**同名 Lua 全局函数**;
 *  - 也可在 Lua 里 `local py = require "python"` 直接用原生接口(可选参数语义更完整)。
 *
 * ## 运行时从哪来
 * 预编译的 CPython 运行时随包分发(构建期由 `preparePythonRuntime` 从 Chaquopy 制品拉取):
 *  - 原生库(`libpython3.14.so` 等)-> jniLibs -> `nativeLibraryDir`,系统加载器按 soname 解析
 *  - 标准库 + 扩展模块 -> assets/python 下的 zip,**首次 [pythonInit] 时解包**到 app 私有目录
 *
 * 因此 32 位包(无 arm64 产物)或构建时 `-PpythonRuntime=off` 的产物里没有运行时:
 * `pythonAvailable()` 返回 false,所有调用抛 IllegalStateException —— 不静默降级,
 * 否则用户会拿到一个"看起来能用但 import 全失败"的环境。
 *
 * ## 与 Lua 并行
 * 调用 Python 前后由原生层用 `PyGILState_Ensure/Release` 包裹,因此:
 *  - Lua 线程 <-> Python 线程:**真并行**(Lua 侧无 GIL)
 *  - Python 线程之间:受 GIL 串行(标准构建,非 free-threaded)
 *  - 全部调用串行化在一把互斥锁上,`pythonRun` 期间不会与另一次 `pythonRun` 交错
 *
 * ## 参数约定
 * 注册机制按位置取参、按形参类型强转,**类型必须严格匹配**(整数传 integer、布尔传 boolean、
 * 文本传 string)。可选参数统一放在方法末尾的 `opts` 里,按下标顺序可选传入;
 * 需要"跳过"某个可选参数时显式传 `nil`(故 opts 用可空类型,缺参不会 NPE)。
 *
 * @version 1.0.0
 */
@Keep
object PythonUtil {

    init {
        try {
            System.loadLibrary("python")
        } catch (e: Throwable) {
            // 与 MemUtil / LuaParserUtil 一致:加载失败静默降级,调用时再报可用性错误
        }
    }

    /** 运行时在 app 私有目录下的目录名(与解包器约定一致)。 */
    private const val RUNTIME_DIR_NAME = "python"

    /**
     * 桥 `libpython.so` 是否加载成功。
     *
     * 与 [pythonAvailable] 的区别:本函数只看"桥能不能用",不看"运行时有没有投放"。
     * 用于区分两类故障:桥没加载(ABI 不支持) vs 运行时缺失(尚未投放)。
     */
    @JvmStatic
    fun pythonBridgeLoaded(): Boolean = bridgeLoaded

    private val bridgeLoaded: Boolean by lazy {
        try {
            nPyAvailable()
            true
        } catch (e: Throwable) {
            false
        }
    }

    // ---------------- 探测与初始化 ----------------

    /**
     * Python 运行时是否可用。
     *
     * 三个条件同时满足才为 true:
     *  1. 桥 `libpython.so` 加载成功(设备 ABI 受支持)
     *  2. `libpython3.14.so` 能被 dlopen
     *  3. **默认运行时目录已被投放**(见 [defaultHome])
     *
     * 只做 dlopen 与目录检查,**不初始化解释器**,不会抛异常,可放心用于前置判断。
     *
     * 注意:第 3 条只看默认目录。若你用 `pythonInit(home)` 指定了别的运行时根目录,
     * 本函数可能返回 false —— 那是"按默认约定不可用",不代表你自定义的目录不可用。
     *
     * 产物方面:Python 运行时只提供 arm64-v8a(3.12+ 无 32 位产物),
     * 32 位设备上第 1/2 条即不满足,恒为 false。
     */
    @JvmStatic
    fun pythonAvailable(context: Context): Boolean {
        if (!bridgeLoaded || !nPyAvailable()) return false
        val home = File(defaultHome(context))
        val manifest = PythonRuntimeInstaller.bundledManifest(context) ?: return false
        // 已解包(指纹一致) 或 包内有运行时待解包 —— 都算可用
        return home.isDirectory || PythonRuntimeInstaller.isInstalled(home, manifest)
    }

    /**
     * Python 版本号(如 "3.14.0")。运行时缺失时返回带说明的占位串,不抛异常。
     */
    @JvmStatic
    fun pythonVersion(context: Context): String =
        if (bridgeLoaded) nPyVersion() else "3.14 (未投放)"
    

    /**
     * 初始化 Python 解释器(幂等:重复调用直接返回 true)。
     *
     * @param opts 可选参数:
     *   - `opts[0]` runtimeHome:String? —— 运行时根目录;省略则用 app 私有目录下的 [RUNTIME_DIR_NAME]
     * @return 成功返回 true
     * @throws IllegalStateException 运行时缺失或初始化失败(含具体原因)
     */
    @JvmStatic
    fun pythonInit(context: Context, vararg opts: Any?): Boolean {
        if (!bridgeLoaded) {
            throw IllegalStateException(
                "libpython.so 未加载:当前设备不受支持(仅 arm64-v8a 提供 Python 运行时)"
            )
        }
        val home = File(optString(opts, 0, null) ?: defaultHome(context))

        // 先确保运行时已就位(幂等:已解包且指纹一致时只读一个文件)
        if (!ensureRuntime(context, home)) {
            throw IllegalStateException(
                "Python 初始化失败:运行时不可用\n" +
                    "  home     = ${home.absolutePath}\n" +
                    "  installed = ${PythonRuntimeInstaller.isInstalled(home, PythonRuntimeInstaller.bundledManifest(context) ?: "")}\n" +
                    "本 APK " + if (PythonRuntimeInstaller.bundledManifest(context) == null)
                    "未分发 Python 运行时(32 位包,或构建时 -PpythonRuntime=off)"
                else "的运行时解包失败,详见 luaforge.log"
            )
        }

        val ok = nPyInit(home.absolutePath, context.applicationInfo.nativeLibraryDir, context.cacheDir.absolutePath)
        if (!ok) {
            throw IllegalStateException(
                "Python 初始化失败:解释器未能启动\n" +
                    "  home   = ${home.absolutePath}\n" +
                    "  请确认设备为 arm64-v8a,且 nativeLibraryDir 中有 libpython3.14.so。"
            )
        }
        return true
    }

    /**
     * 确保 [home] 里的运行时与包内分发的一致。
     *
     * 包内没有运行时(32 位包 / 构建时关闭)时**直接返回 false**,由调用方报出可读原因;
     * 不在这里抛异常,便于 [pythonAvailable] 之类的探测路径复用。
     */
    private fun ensureRuntime(context: Context, home: File): Boolean {
        val manifest = PythonRuntimeInstaller.bundledManifest(context) ?: return false
        return PythonRuntimeInstaller.ensure(context, home, manifest)
    }

    // ---------------- 执行 ----------------

    /**
     * 执行一段 Python 代码,返回本次执行产生的 **stdout + stderr 文本**。
     *
     * 脚本自身抛异常时不吞掉:错误文本连同 traceback 一起作为异常抛出,
     * 便于在 Lua 侧用 `pcall` 捕获后原样展示。
     *
     * @param opts 可选参数:
     *   - `opts[0]` cwd:String? —— 工作目录;省略则用当前项目目录(`luaDir`)
     * @return 输出文本(可能为空串)
     * @throws IllegalStateException 未初始化或脚本执行失败
     */
    @JvmStatic
    fun pythonRun(context: Context, code: String, vararg opts: Any?): String {
        requireInit()
        val cwd = optString(opts, 0, null) ?: projectDir(context)
        return nPyRun(code, cwd)
    }

    /**
     * 执行一个 Python 脚本文件,返回本次执行产生的 **stdout + stderr 文本**。
     *
     * 与命令行 `python script.py ...` 的差异(刻意如此):
     *  - `sys.argv` **不含脚本路径**,只含 [opts] 里传入的额外参数
     *    且省略参数时 `sys.argv` 为空表(不是 `['script.py']`),便于与 `argparse` 配合时
     *    不产生多余的位置参数
     *  - 脚本所在目录会被插入 `sys.path[0]`,因此**能 import 同目录模块**
     *  - `__name__` 为 `"__main__"`,`__file__` 为脚本绝对路径
     *
     * @param opts 可选参数:
     *   - `opts[0]`      cwd:String?  —— 工作目录;省略则用当前项目目录(`luaDir`)
     *   - `opts[1..]`    argv:Any?[] —— 传给脚本的参数(逐个转成字符串)
     * @return 输出文本(可能为空串)
     * @throws IllegalStateException 未初始化、脚本不存在或执行失败
     */
    @JvmStatic
    fun pythonRunFile(context: Context, path: String, vararg opts: Any?): String {
        requireInit()
        val cwd = optString(opts, 0, null) ?: projectDir(context)
        val argv = opts.drop(1).mapNotNull { it?.toString() }.toTypedArray()
        return nPyRunFile(path, argv, cwd)
    }

    /**
     * 结束解释器并释放运行时(幂等)。
     *
     * 说明:finalize 后 `sys` 里已导入的模块状态全部丢弃,再次执行需重新 [pythonInit]。
     * 一般不需要手动调用 —— 进程退出即整体释放;仅在需要"换一套运行时目录"时才有用。
     */
    @JvmStatic
    fun pythonFinalize(context: Context) {
        if (bridgeLoaded) nPyFinalize()
    }

    // ---------------- opts 解析(private,不会被注册) ----------------

    /** 运行时根目录默认值:app 私有目录下的 [RUNTIME_DIR_NAME]。 */
    private fun defaultHome(context: Context): String =
        File(context.filesDir, RUNTIME_DIR_NAME).absolutePath

    /**
     * 取当前项目目录(与运行时 `LuaActivity` 解析出的 `luaDir` 同源)。
     *
     * 取不到时回落到 app 私有目录:宁可在"能跑但工作目录不对"与"直接报错"之间选前者,
     * 因为脚本可能完全不依赖相对路径。
     */
    private fun projectDir(context: Context): String {
        val dir = when (context) {
            is LuaContext -> context.luaDir
            else -> (context.applicationContext as? LuaApplication)?.localDir
        }
        return dir ?: context.filesDir.absolutePath
    }

    private fun requireInit() {
        if (!bridgeLoaded) {
            throw IllegalStateException(
                "libpython.so 未加载:当前设备不受支持(仅 arm64-v8a 提供 Python 运行时)"
            )
        }
    }

    private fun optString(opts: Array<out Any?>, i: Int, def: String?): String? {
        val v = opts.getOrNull(i) ?: return def
        return v as? String ?: def
    }

    // ---------------- JNI ----------------

    private external fun nPyAvailable(): Boolean

    private external fun nPyVersion(): String

    private external fun nPyInit(home: String, libDir: String, tmpDir: String): Boolean

    private external fun nPyRun(code: String, cwd: String?): String

    private external fun nPyRunFile(path: String, argv: Array<String>, cwd: String?): String

    private external fun nPyFinalize()
}
