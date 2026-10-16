package com.luaforge.studio.widget

import androidx.annotation.Keep

/**
 * 随 IDE 一起发布的自有控件注册表。
 *
 * 这些控件不是用户项目的三方库,而是编译进 `core` 的 Android View 子类,
 * 因此:
 * - 布局助手 `classes.lua` 会 `importClass` 注册短名
 * - `loadlayout2.lua` 的 `builtinWidgetPrefixes` 放行 `com.luaforge.studio.`
 * - MCP 经 `list_widgets` 暴露,便于外部工具发现可用控件与其属性
 *
 * 维护约定:新增自有控件时,同步更新此处、`app/src/main/assets/layouthelper/classes.lua`
 * 与 `layoutData.lua`(布局助手「添加控件」列表),并补 `app/src/main/assets/doc/` 文档。
 */
@Keep
object BuiltinWidgets {

    /** 单个内置控件的元信息。 */
    data class Widget(
        /** 全限定类名(写入布局表的也可是短名)。 */
        val className: String,
        /** 布局助手短名。 */
        val shortName: String,
        /** 中文显示名。 */
        val displayName: String,
        /** 布局助手分类。 */
        val group: String,
        /** 可经 luajava 调用的属性(属性名 -> 说明)。 */
        val properties: Map<String, String>,
        /** 使用说明文档(assets 内相对路径,可为空)。 */
        val doc: String = ""
    )

    val all: List<Widget> = listOf(
        Widget(
            className = "com.luaforge.studio.widget.textfield.MaterialTextField",
            shortName = "MaterialTextField",
            displayName = "Material 文本输入框",
            group = "Data Input & Display Controls",
            properties = linkedMapOf(
                "hint" to "String	提示文本",
                "text" to "String	输入内容",
                "error" to "String	错误提示",
                "helperText" to "String	辅助说明",
                "singleLine" to "Boolean	单行",
                "textSize" to "Float	字号(sp)",
                "boxCornerRadii" to "Float	圆角半径",
                "endIconMode" to "Int	尾部图标模式"
            )
        ),
        Widget(
            className = "com.luaforge.studio.widget.video.ExoVideoView",
            shortName = "ExoVideoView",
            displayName = "ExoPlayer 视频播放器",
            group = "Video & Media",
            properties = linkedMapOf(
                "videoPath" to "String	本地文件路径(相对项目或绝对路径)",
                "videoUrl" to "String	网络地址(http/https/rtsp/rtmp)",
                "useController" to "Boolean	是否显示内置控制器(默认 true)",
                "loop" to "Boolean	是否循环播放",
                "autoPlay" to "Boolean	准备完成后是否自动播放(默认 true)",
                "muted" to "Boolean	是否静音",
                "volume" to "Float	音量 0.0-1.0",
                "play" to "Function()	开始播放",
                "pause" to "Function()	暂停",
                "stop" to "Function()	停止并清空媒体",
                "seekTo" to "Function(Long)	跳转到指定毫秒位置",
                "getCurrentPosition" to "Function():Long	当前播放位置(毫秒)",
                "getDuration" to "Function():Long	媒体总时长(毫秒)",
                "isPlaying" to "Function():Boolean	是否正在播放",
                "onPause" to "Function()	暂停(供页面生命周期调用)",
                "release" to "Function()	释放播放器资源"
            )
        ),
        Widget(
            className = "com.luaforge.studio.widget.video.ExoAudioPlayer",
            shortName = "ExoAudioPlayer",
            displayName = "ExoPlayer 音频播放器",
            group = "Video & Media",
            properties = linkedMapOf(
                "audioPath" to "String	本地文件路径",
                "audioUrl" to "String	网络地址(http/https)",
                "loop" to "Boolean	是否循环播放",
                "autoPlay" to "Boolean	准备完成后是否自动播放(默认 true)",
                "volume" to "Float	音量 0.0-1.0",
                "play" to "Function()	开始播放",
                "pause" to "Function()	暂停",
                "stop" to "Function()	停止并清空媒体",
                "seekTo" to "Function(Long)	跳转到指定毫秒位置",
                "getCurrentPosition" to "Function():Long	当前播放位置(毫秒)",
                "getDuration" to "Function():Long	媒体总时长(毫秒)",
                "isPlaying" to "Function():Boolean	是否正在播放",
                "release" to "Function()	释放播放器资源"
            )
        )
    )
}
