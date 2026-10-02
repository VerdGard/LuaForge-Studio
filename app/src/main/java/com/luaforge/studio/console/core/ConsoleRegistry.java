package com.luaforge.studio.console.core;

/**
 * 调试控制台桥注册表。未注册实现时 core 侧全部钩点单次判空短路。
 *
 * <p>本类只存在于 IDE(app 模块);打包产物无此类 -> core 的 ConsoleBridgeRef 反射失败 -> 零行为影响。
 */
public final class ConsoleRegistry {

    private static volatile ConsoleBridge bridge;

    /** Lua 报错是否以 Toast 回显(设置项控制;未集成控制台时保持 true=原有行为)。 */
    private static volatile boolean errorToastEnabled = true;

    private ConsoleRegistry() {}

    public static void register(ConsoleBridge b) {
        bridge = b;
    }

    public static void unregister() {
        bridge = null;
    }

    public static ConsoleBridge get() {
        return bridge;
    }

    public static void setErrorToastEnabled(boolean enabled) {
        errorToastEnabled = enabled;
    }

    /** Lua 报错 Toast 回显是否开启(LuaActivity.sendError 读取)。 */
    public static boolean isErrorToastEnabled() {
        return errorToastEnabled;
    }
}
