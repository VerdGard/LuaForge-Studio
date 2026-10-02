package com.androlua;

import com.luajava.LuaState;

/**
 * IDE 调试会话钩子。
 *
 * <p>core 定义、IDE(app 模块)安装:LuaActivity 在 initLua() 之后、执行用户脚本之前
 * 回调本接口,供 IDE 注入防火墙脚本并预热判定网关。打包产物不安装 -> 钩子为空,
 * 运行时零开销、零行为污染。
 */
public interface LuaSessionHook {

    /**
     * @param L        当前页 LuaState(已完成 openLibs 与全局注册)
     * @param luaDir   运行项目目录
     * @param luaPath  入口脚本路径
     * @param debugMode 项目 settings.json 的 debugmode(IDE 会话判定依据)
     */
    void onSessionStart(LuaState L, String luaDir, String luaPath, boolean debugMode);
}
