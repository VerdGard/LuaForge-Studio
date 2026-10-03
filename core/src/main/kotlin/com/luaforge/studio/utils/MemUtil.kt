package com.luaforge.studio.utils

import androidx.annotation.Keep

/**
 * 内存访问工具类(GG 式内存搜索/读写)。
 *
 * 由 `libmemkit.so` 提供实现,两种使用方式并存:
 *  - `global_utils` 含 "MemUtil" 时,本类的 public static 方法会被注册为**同名 Lua 全局函数**;
 *  - 也可在 Lua 里 `local mk = require "memkit"` 直接用原生接口(可选参数语义更完整)。
 *
 * ## 权限模型(决定能用哪些功能)
 * - `pid` 传 0 或自身 pid:走 `process_vm_readv/writev`,**无需 root**,可读写自身进程内存。
 * - `pid` 传其他进程:需要 `PTRACE_MODE_ATTACH`(通常要 root,且同 uid 同签名亦可)。
 *   Android 10+ SELinux enforcing 下即使 root 也可能被拒绝,此时会抛异常并带 errno。
 *
 * ## 参数约定
 * 注册机制按位置取参、按形参类型强转,**类型必须严格匹配**:
 * 整数传 integer、小数传 number、布尔传 boolean、文本传 string。
 * 可选参数统一放在方法末尾的 `opts` 里,按下标顺序可选传入。
 *
 * @version 1.0.0
 */
@Keep
object MemUtil {

    init {
        try {
            System.loadLibrary("memkit")
        } catch (e: Throwable) {
            // 与 LuaParserUtil 一致:加载失败静默降级,调用时再报可用性错误
        }
    }

    /** 原生库是否可用。 */
    @JvmStatic
    fun memAvailable(): Boolean = libLoaded

    private val libLoaded: Boolean by lazy {
        try {
            nMemVersion()
            true
        } catch (e: Throwable) {
            false
        }
    }

    // ---------------- 进程与区域 ----------------

    /** 原生库版本。 */
    @JvmStatic
    fun memVersion(): String = nMemVersion()

    /** 是否已 root(euide==0 / su 二进制 / Magisk/KSU/APatch 痕迹,快速判断,不弹授权框)。 */
    @JvmStatic
    fun memIsRoot(): Boolean = nMemIsRoot()

    /** 当前进程 pid。 */
    @JvmStatic
    fun memSelfPid(): Int = nMemSelfPid()

    /** 按进程名(comm 或 cmdline basename)查找 pid,未找到返回 -1。 */
    @JvmStatic
    fun memFindPid(name: String): Int = nMemFindPid(name)

    /**
     * 列出内存区域,返回 JSON 数组:[{"start":..,"end":..,"perms":"r-xp","path":".."}, ...]。
     * @param pid 0 表示当前进程
     */
    @JvmStatic
    fun memRegions(pid: Int): String = nMemRegions(pid)

    // ---------------- 读 ----------------

    /**
     * 读取原始字节,返回小写十六进制字符串(长度为实际读到的字节数)。
     * @throws IllegalStateException 读取失败(越权、未映射等)
     */
    @JvmStatic
    fun memReadBytes(pid: Int, addr: Long, len: Int): String = nMemReadBytes(pid, addr, len)

    /**
     * 读取整数(按 size 做符号扩展,1/2/4/8 字节)。
     * @throws IllegalStateException 读取失败
     */
    @JvmStatic
    fun memReadInt(pid: Int, addr: Long, size: Int): Long = nMemReadInt(pid, addr, size)

    /**
     * 读取浮点。
     * @param isDouble true 读 double(8B),false 读 float(4B)
     * @throws IllegalStateException 读取失败
     */
    @JvmStatic
    fun memReadFloat(pid: Int, addr: Long, isDouble: Boolean): Double =
        nMemReadFloat(pid, addr, isDouble)

    /**
     * 读取 C 字符串(读到 '\0' 或达 maxLen)。
     * @throws IllegalStateException 读取失败
     */
    @JvmStatic
    fun memReadString(pid: Int, addr: Long, maxLen: Int): String = nMemReadString(pid, addr, maxLen)

    // ---------------- 写 ----------------

    /**
     * 写入原始字节,hex 为十六进制字符串(可含空格)。
     * @return 实际写入字节数
     * @throws IllegalStateException 写入失败
     */
    @JvmStatic
    fun memWriteBytes(pid: Int, addr: Long, hex: String): Int = nMemWriteBytes(pid, addr, hex)

    /**
     * 写入整数(1/2/4/8 字节,小端)。
     * @return 实际写入字节数
     * @throws IllegalStateException 写入失败
     */
    @JvmStatic
    fun memWriteInt(pid: Int, addr: Long, value: Long, size: Int): Int =
        nMemWriteInt(pid, addr, value, size)

    /**
     * 写入浮点。
     * @param isDouble true 写 double(8B),false 写 float(4B)
     * @return 实际写入字节数
     * @throws IllegalStateException 写入失败
     */
    @JvmStatic
    fun memWriteFloat(pid: Int, addr: Long, value: Double, isDouble: Boolean): Int =
        nMemWriteFloat(pid, addr, value, isDouble)

    // ---------------- 搜索 ----------------

    /**
     * 首次搜索,命中地址存入活动结果集,返回命中数(受 limit 限制)。
     *
     * @param pid    0 表示当前进程
     * @param type   byte/word/dword/qword/float/double/utf8
     * @param value  文本值;整数支持十进制与 0x 前缀
     * @param opts   可选,按序:limit(Int, 默认65536) / start(Long) / end(Long) / align4(Boolean, 默认true)
     */
    @JvmStatic
    fun memScan(pid: Int, type: String, value: String, vararg opts: Any): Int {
        val limit = optInt(opts, 0, 65536)
        val start = optLong(opts, 1, 0L)
        val end = optLong(opts, 2, 0L)
        val align4 = optBool(opts, 3, true)
        return nMemScan(pid, type, value, limit, start, end, align4)
    }

    /**
     * 在已有结果集上按新值过滤(结果集被原地缩小),返回剩余命中数。
     *
     * @param value 省略或传 null 表示「未知/已变化」——仅保留仍可读的地址。
     */
    @JvmStatic
    fun memScanNext(pid: Int, type: String, vararg value: Any): Int {
        val v = value.firstOrNull()?.toString() ?: unknownMarker
        return nMemScanNext(pid, type, v)
    }

    /** 当前结果集命中数。 */
    @JvmStatic
    fun memScanCount(): Int = nMemScanCount()

    /** 取第 index 个命中地址(下标从 0 开始)。 */
    @JvmStatic
    fun memScanResult(index: Int): Long = nMemScanResult(index)

    /**
     * 批量取命中地址,返回 JSON 数组。
     * @param offset 起始下标;count <= 0 表示取到结尾
     */
    @JvmStatic
    fun memScanResults(offset: Int, count: Int): String = nMemScanResults(offset, count)

    /** 清空结果集,返回清空前的命中数。 */
    @JvmStatic
    fun memScanClear(): Int = nMemScanClear()

    /**
     * 把所有命中地址改为指定值(GG 的「修改全部」)。
     * @return 成功写入的地址数
     */
    @JvmStatic
    fun memScanWriteAll(pid: Int, type: String, value: String): Int =
        nMemScanWriteAll(pid, type, value)

    /**
     * 按结果集当前位置重新读取,返回 JSON:[[addr,"value"], ...],便于观察筛选。
     * @param limit <= 0 表示不限制
     */
    @JvmStatic
    fun memScanRefresh(pid: Int, type: String, limit: Int): String =
        nMemScanRefresh(pid, type, limit)

    /** 类型表 JSON:[{"name":"dword","size":4}, ...]。 */
    @JvmStatic
    fun memTypeInfo(): String = nMemTypeInfo()

    // ---------------- opts 解析(private,不会被注册) ----------------

    /**
     * 与 libmemkit.so 内 MK_UNKNOWN 严格一致的哨兵值。
     * 续扫时用它表示「不比较数值,只保留仍可读的地址」。
     */
    private const val unknownMarker = "\u0001memkit:any\u0001"

    private fun optInt(opts: Array<out Any>, i: Int, def: Int): Int {
        val v = opts.getOrNull(i) ?: return def
        return (v as? Number)?.toInt() ?: def
    }

    private fun optLong(opts: Array<out Any>, i: Int, def: Long): Long {
        val v = opts.getOrNull(i) ?: return def
        return (v as? Number)?.toLong() ?: def
    }

    private fun optBool(opts: Array<out Any>, i: Int, def: Boolean): Boolean {
        val v = opts.getOrNull(i) ?: return def
        return v as? Boolean ?: def
    }

    // ---------------- JNI ----------------

    private external fun nMemVersion(): String

    private external fun nMemIsRoot(): Boolean

    private external fun nMemSelfPid(): Int

    private external fun nMemFindPid(name: String): Int

    private external fun nMemRegions(pid: Int): String

    private external fun nMemReadBytes(pid: Int, addr: Long, len: Int): String

    private external fun nMemReadInt(pid: Int, addr: Long, size: Int): Long

    private external fun nMemReadFloat(pid: Int, addr: Long, isDouble: Boolean): Double

    private external fun nMemReadString(pid: Int, addr: Long, maxLen: Int): String

    private external fun nMemWriteBytes(pid: Int, addr: Long, hex: String): Int

    private external fun nMemWriteInt(pid: Int, addr: Long, value: Long, size: Int): Int

    private external fun nMemWriteFloat(pid: Int, addr: Long, value: Double, isDouble: Boolean): Int

    private external fun nMemScan(
        pid: Int, type: String, value: String,
        limit: Int, start: Long, end: Long, align4: Boolean
    ): Int

    private external fun nMemScanNext(pid: Int, type: String, value: String): Int

    private external fun nMemScanCount(): Int

    private external fun nMemScanResult(index: Int): Long

    private external fun nMemScanResults(offset: Int, count: Int): String

    private external fun nMemScanClear(): Int

    private external fun nMemScanWriteAll(pid: Int, type: String, value: String): Int

    private external fun nMemScanRefresh(pid: Int, type: String, limit: Int): String

    private external fun nMemTypeInfo(): String
}
