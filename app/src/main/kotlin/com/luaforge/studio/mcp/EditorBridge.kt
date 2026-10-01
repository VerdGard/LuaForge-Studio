package com.luaforge.studio.mcp

import com.luaforge.studio.ui.editor.viewmodel.EditorViewModel

/**
 * 编辑器桥接。
 *
 * MCP 服务运行在独立线程上,需要访问当前已经打开的编辑器(ViewModel)与项目路径。
 * 编辑器界面在进入时通过 [register] 注册,在离开时通过 [unregister] 注销。
 *
 * 注意:所有修改编辑器内容的方法都必须在主线程调用,MCP 工具层已通过
 * `withContext(Dispatchers.Main)` 保证。
 */
object EditorBridge {

    @Volatile
    private var viewModel: EditorViewModel? = null

    @Volatile
    private var projectPath: String? = null

    fun register(vm: EditorViewModel, project: String) {
        viewModel = vm
        projectPath = project
    }

    fun unregister(vm: EditorViewModel) {
        if (viewModel === vm) {
            viewModel = null
            projectPath = null
        }
    }

    fun currentViewModel(): EditorViewModel? = viewModel

    fun currentProjectPath(): String? = projectPath

    /** 编辑器是否处于可用状态(已初始化且注册)。 */
    fun isAvailable(): Boolean = viewModel?.isInitialized == true
}
