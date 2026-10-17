package com.luaforge.studio.compose

import android.content.Context
import com.luajava.LuaState

/**
 * Compose 桥加载器:A / C 两种分发方式共存。
 *
 * ## 两种形态
 *
 * - **A(内置)**:`LuaCompose` 随 `core` 编译进 `core.apk`,用户 APK 天然可用。
 *   零配置,代价是每个产物都背上 Compose 运行时(约 1.5MB dex)。
 * - **C(外部)**:把「Compose 运行时 + 桥」单独打成 `compose_bridge.dex` 放进项目
 *   `libs/`,仅在项目真的用 Compose 时才加载。适合按 `-PlfComposeExternal=true`
 *   构建的**不带 Compose 的 core 发行版**,让未用 Compose 的 APK 零增重。
 *
 * ## 优先级(内置优先,外部兜底)
 *
 * **内置桥优先**。原因不是"谁更好",而是**两条 Compo 运行时不能混用**:
 * 内置桥的 `androidx.compose.*` 来自 `core.apk` 模板,外部 dex 若再带一份
 * 不同版本的同一批类,ART 会在运行期随机命中其中一个,症状是
 * `NoSuchMethodError` / `NoClassDefFoundError` 这类只在真机上偶发、极难定位的崩溃。
 *
 * 所以:
 * - 内置桥可用(A 发行版)→ 用内置,跳过外部 dex(记日志说明跳过原因);
 * - 内置桥不可用(`-PlfComposeBundled=false` 的 C 发行版)→ 用外部 dex;
 * - 两者都没有 → [Source.NONE],调用方自便,绝不抛异常打断页面启动。
 *
 * 若某项目确实需要用外部桥覆盖内置(例如自建了更多组件),把它放在
 * `libs/compose_bridge.dex` 并同时用 C 发行版(`lfComposeBundled=false`)构建 ——
 * 这样模板里没有 Compose 类,不存在重复。
 *
 * ## 外部桥的类约定
 *
 * 外部 dex 里须提供一个静态方法 `register(LuaState, Context): Int`,类名二选一:
 *
 * - `com.luaforge.studio.compose.ExternalBridge`(推荐,便于与内置同名类共存)
 * - `com.luaforge.studio.compose.LuaCompose`(内置发行版里存在同名类时会被父
 *   类加载器抢先解析,**不推荐**)
 *
 * 方法名带 `register` 即可;参数类型用 `com.luajava.LuaState` 与
 * `android.content.Context`(或 `android.app.Activity`)均可 —— 这里按可赋值性
 * 挑选唯一匹配的方法。
 */
object LuaComposeLoader {

    private const val TAG = "LuaComposeLoader"

    /** 外部桥候选类名(按优先级)。 */
    private val EXTERNAL_CLASSES = listOf(
        "com.luaforge.studio.compose.ExternalBridge",
        "com.luaforge.studio.compose.LuaCompose"
    )

    /** 外部桥 dex 相对于项目根目录的候选路径(按优先级)。 */
    private val EXTERNAL_DEX_PATHS = listOf(
        "libs/compose_bridge.dex",
        "libs/compose.dex",
        "compose/compose_bridge.dex",
        "libs/compose_bridge.jar"
    )

    /** 加载结果。 */
    enum class Source {
        /** 使用了项目内的外部桥 */
        EXTERNAL,

        /** 使用了随 core 编译的内置桥 */
        BUILTIN,

        /** 两者都不可用 */
        NONE
    }

    data class Result(val source: Source, val registered: Int, val detail: String)

    /** 供外部 dex 加载用的最小接口,避免 loader 直接依赖 `LuaDexLoader` 的具体实现。 */
    fun interface DexProvider {
        /** 加载 dex 并返回其 ClassLoader;失败返回 null。 */
        fun load(path: String): ClassLoader?
    }

    /**
     * 依次尝试外部桥与内置桥。
     *
     * @param luaDir    项目根目录(用于定位 `libs/compose_bridge.dex`),空则跳过外部桥
     * @param dexLoader 项目 dex 加载器(通常是 `LuaDexLoader`),空则跳过外部桥
     */
    fun register(L: LuaState, context: Context, luaDir: String?, dexLoader: DexProvider?): Result {
        // 1. 内置桥(方案 A):与 core.apk 里的 Compose 运行时版本天然一致,优先使用
        val builtin = tryBuiltin(L, context)
        if (builtin != null) {
            if (!luaDir.isNullOrBlank()) {
                val ext = EXTERNAL_DEX_PATHS.map { java.io.File(luaDir, it) }.firstOrNull { it.isFile }
                if (ext != null) {
                    android.util.Log.w(
                        TAG,
                        "检测到 ${ext.name},但内置 Compose 桥已生效 —— 跳过外部桥,避免两套 " +
                            "Compose 运行时混用。如需使用外部桥,请用 -PlfComposeBundled=false 构建的 core。"
                    )
                }
            }
            return builtin
        }
        // 2. 外部桥(方案 C):内置桥未编入时的唯一来源
        if (!luaDir.isNullOrBlank() && dexLoader != null) {
            val r = tryExternal(L, context, luaDir, dexLoader)
            if (r != null) return r
        }
        return Result(Source.NONE, 0, "未找到可用的 Compose 桥(内置桥未编入且项目无 libs/compose_bridge.dex)")
    }

    private fun tryExternal(
        L: LuaState,
        context: Context,
        luaDir: String,
        dexLoader: DexProvider
    ): Result? {
        val dex = EXTERNAL_DEX_PATHS
            .map { java.io.File(luaDir, it) }
            .firstOrNull { it.isFile }
            ?: return null
        val cl = try {
            dexLoader.load(dex.absolutePath)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "加载外部 Compose 桥 dex 失败: ${dex.absolutePath}", t)
            null
        } ?: return null

        for (className in EXTERNAL_CLASSES) {
            val n = invokeRegister(className, cl, L, context) ?: continue
            android.util.Log.i(TAG, "已使用外部 Compose 桥: $className(${dex.name},注册 $n 个函数)")
            return Result(Source.EXTERNAL, n, "$className @ ${dex.name}")
        }
        return null
    }

    private fun tryBuiltin(L: LuaState, context: Context): Result? {
        // 走反射而不是直接调用:外部发行版可能整文件排除 LuaCompose.kt,
        // 那样直接引用会在类链接期就 NoClassDefFoundError。
        val n = invokeRegister("com.luaforge.studio.compose.LuaCompose", null, L, context) ?: return null
        android.util.Log.i(TAG, "已使用内置 Compose 桥(注册 $n 个函数)")
        return Result(Source.BUILTIN, n, "builtin")
    }

    /**
     * 反射调用目标类的 `register(LuaState, Context): Int`。
     *
     * 不写死参数类型:外部桥可能把第二个参数声明成 `Activity`。按
     * 「形参可接收实参」筛选,并要求返回 int。
     */
    private fun invokeRegister(
        className: String,
        loader: ClassLoader?,
        L: LuaState,
        context: Context
    ): Int? {
        val cls = try {
            Class.forName(className, true, loader ?: LuaComposeLoader::class.java.classLoader)
        } catch (_: Throwable) {
            return null
        }
        val method = cls.methods.firstOrNull { m ->
            m.name == "register" &&
                m.parameterTypes.size == 2 &&
                m.parameterTypes[0].isAssignableFrom(L.javaClass) &&
                m.parameterTypes[1].isAssignableFrom(context.javaClass) &&
                (m.returnType == Int::class.javaPrimitiveType || m.returnType == Int::class.java)
        } ?: cls.methods.firstOrNull { m ->
            m.name == "register" &&
                m.parameterTypes.size == 2 &&
                m.parameterTypes[0].isAssignableFrom(L.javaClass) &&
                m.parameterTypes[1].isAssignableFrom(context.javaClass)
        } ?: run {
            android.util.Log.w(TAG, "$className 缺少 register(LuaState, Context) 方法")
            return null
        }
        return try {
            val ret = method.invoke(null, L, context)
            (ret as? Number)?.toInt() ?: 0
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "调用 $className.register 失败", t)
            null
        }
    }
}
