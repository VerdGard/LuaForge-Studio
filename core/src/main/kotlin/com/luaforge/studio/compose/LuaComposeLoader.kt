package com.luaforge.studio.compose

import android.content.Context
import com.luajava.LuaState

/**
 * Compose 桥的注册入口。
 *
 * ## 只有一种分发方式:内置
 *
 * `LuaCompose` 随 `core` 一起编译进 `core.apk`,用户 APK 天然可用,零配置。
 * 早期版本还有一条"外部桥"路径(方案 C:项目自带 `libs/compose_bridge.dex`,
 * 配套 `-PlfComposeBundled=false` 的零体积 core 发行版),已移除。移除的理由:
 *
 * - **两套 Compose 运行时不能混用**。内置桥的 `androidx.compose.*` 来自模板,
 *   外部 dex 若再带一份不同版本的同一批类,ART 会在运行期随机命中其中一个,
 *   症状是只在真机上偶发的 `NoSuchMethodError` / `NoClassDefFoundError`。
 *   靠"内置优先"来规避这件事本身就是脆弱的设计。
 * - **成本落在每个用户项目上**:要自己产出 dex、自己保证版本与 core 对齐。
 * - **收益很小**:只是让未用 Compose 的项目省约 1.5MB dex,而模板体积不是瓶颈。
 *
 * ## 为什么仍然用反射而不是直接调用
 *
 * 不是为了"支持外部桥",而是为了**失败隔离**:`register` 内部要建 60 多个
 * `JavaFunction`、注册全局名,任何一处抛错都不应该让页面启动失败。反射 + 兜底
 * 能把异常收敛成一条日志。同时这也让 `LuaActivity` 不必在类链接期依赖
 * Compose 类 —— 即使将来某个发行版裁掉桥,页面仍能正常跑起来(只是没有
 * `compose` 函数)。
 */
object LuaComposeLoader {

    private const val TAG = "LuaComposeLoader"

    /** 内置桥类的全限定名(`@file:JvmName("LuaCompose")` 生成的名字)。 */
    private const val BUILTIN_CLASS = "com.luaforge.studio.compose.LuaCompose"

    /** 注册结果。 */
    enum class Source {
        /** 内置桥注册成功 */
        BUILTIN,

        /** 桥不可用(类缺失或 register 调用失败) */
        NONE
    }

    data class Result(val source: Source, val registered: Int, val detail: String)

    /**
     * 注册 Lua 侧 Compose 全局函数(`compose` / `composeContent` / `state` / `Column` ...)。
     *
     * 任何失败都只记日志、返回 [Source.NONE],绝不抛异常打断页面启动 ——
     * 没用 Compose 的项目不应该看到任何噪音。
     */
    fun register(L: LuaState, context: Context): Result {
        val n = invokeRegister(L, context)
            ?: return Result(Source.NONE, 0, "内置 Compose 桥不可用")
        android.util.Log.i(TAG, "Compose 桥已启用(注册 $n 个函数)")
        return Result(Source.BUILTIN, n, "builtin")
    }

    /**
     * 反射调用 `LuaCompose.register(LuaState, Context): Int`。
     *
     * 不写死参数类型:按「形参可接收实参」筛选,并要求返回 int 或 Integer。
     */
    private fun invokeRegister(L: LuaState, context: Context): Int? {
        val cls = try {
            Class.forName(BUILTIN_CLASS, true, LuaComposeLoader::class.java.classLoader)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "找不到内置 Compose 桥类 $BUILTIN_CLASS: ${t.message}")
            return null
        }
        val method = cls.methods.firstOrNull { m ->
            m.name == "register" &&
                m.parameterTypes.size == 2 &&
                m.parameterTypes[0].isAssignableFrom(L.javaClass) &&
                m.parameterTypes[1].isAssignableFrom(context.javaClass) &&
                (m.returnType == Int::class.javaPrimitiveType || m.returnType == Integer::class.java)
        } ?: run {
            android.util.Log.w(TAG, "$BUILTIN_CLASS 缺少 register(LuaState, Context) 方法")
            return null
        }
        return try {
            val ret = method.invoke(null, L, context)
            (ret as? Number)?.toInt() ?: 0
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "调用 $BUILTIN_CLASS.register 失败", t)
            null
        }
    }
}
