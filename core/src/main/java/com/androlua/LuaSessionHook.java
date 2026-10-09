package com.androlua;

import com.luajava.LuaState;

/**
 * IDE 调试会话钩子。
 *
 * <p>core 定义、IDE(app 模块)安装:LuaActivity 在 initLua() 之后、执行用户脚本之前
 * 回调本接口,供 IDE 注入脚本(防火墙 / 调试浮窗等)。打包产物不安装 -> 钩子为空,
 * 运行时零开销、零行为污染。
 *
 * <p>可安装多个钩子(LuaActivity.addSessionHook),按安装顺序依次回调。
 */
public interface LuaSessionHook {

    /**
     * 会话开始(initLua 之后、执行用户脚本之前)。
     *
     * @param L        当前页 LuaState(已完成 openLibs 与全局注册)
     * @param luaDir   运行项目目录
     * @param luaPath  入口脚本路径
     * @param debugMode 项目 settings.json 的 debugmode(IDE 会话判定依据)
     * @param toolLaunch true = 工具型启动(布局助手预览等),浮窗类钩子应跳过
     */
    void onSessionStart(LuaState L, String luaDir, String luaPath, boolean debugMode, boolean toolLaunch);

    /**
     * 会话结束(页面销毁)。默认空实现,供需要清理现场资源的钩子覆写。
     *
     * @param L 当前页 LuaState(即将被回收;如需引用须自行判空/容错)
     */
    default void onSessionEnd(LuaState L) {}
}
