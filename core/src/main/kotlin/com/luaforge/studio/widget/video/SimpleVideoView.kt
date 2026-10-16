package com.luaforge.studio.widget.video

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.Keep

/**
 * 无控制器视频播放控件(短名 `SimpleVideoView`)。
 *
 * 继承 [ExoVideoView] 但默认**不显示内置控制器**,适合:
 * - 需要自定义控制条(播放/暂停/进度/全屏按钮)的场景;
 * - 背景播放、直播画面、需要点击画面才切换控制条的沉浸式播放。
 *
 * 通过 [onToggleCallback] 可注册"点击画面"回调,便于自行弹出自定义控制条。
 *
 * ```lua
 * {
 *   SimpleVideoView,
 *   id = "video",
 *   layout_width = "match_parent",
 *   layout_height = "220dp",
 *   autoPlay = true,
 *   resizeMode = "fit",
 * }
 * -- video.setOnToggleCallback(function() video.toggle() end)
 * ```
 */
@Keep
class SimpleVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ExoVideoView(context, attrs, defStyleAttr) {

    /**
     * 点击画面时触发的回调(可为 nil)。
     *
     * 通过属性赋值即生效:
     * ```lua
     * video.onToggleCallback = function() video.toggle() end
     * ```
     * 自动生成的 setter 名为 `setOnToggleCallback`,故 `video.setOnToggleCallback(fn)` 亦等价。
     */
    var onToggleCallback: (() -> Unit)? = null

    init {
        // 默认关掉内置控制器,交由使用方自定义
        useController = false
        // 点击画面(内层播放视图或自身)触发回调
        val clicker = android.view.View.OnClickListener { onToggleCallback?.invoke() }
        setOnClickListener(clicker)
        playerView.setOnClickListener(clicker)
    }
}
