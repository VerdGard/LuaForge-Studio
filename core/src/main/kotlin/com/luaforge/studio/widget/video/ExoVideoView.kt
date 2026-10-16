package com.luaforge.studio.widget.video

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.Keep
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

/**
 * 基于 Media3 / ExoPlayer 的视频播放控件(自带播放控制器)。
 *
 * 相比系统 [android.widget.VideoView],支持更多流媒体协议(HLS/DASH/RTSP)、
 * 更丰富的编解码与自定义控制器。作为 IDE 随附的内置控件,可在布局助手中直接
 * 使用短名 `ExoVideoView`;MCP 经 `list_widgets` 暴露其属性。
 *
 * 生命周期:内部持有一个 [ExoPlayer] 实例,页面销毁时应调用 [release] 释放。
 */
@Keep
class ExoVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 内层播放视图(可选自带控制器)。 */
    val playerView: PlayerView = PlayerView(context).apply {
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private var player: ExoPlayer? = null

    init {
        addView(playerView)
    }

    /** 懒创建播放器,避免仅作为布局占位时也占用解码资源。 */
    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val created = ExoPlayer.Builder(context).build()
        created.repeatMode = Player.REPEAT_MODE_OFF
        player = created
        playerView.player = created
        return created
    }

    /** 是否显示内置控制器。 */
    var useController: Boolean
        get() = playerView.useController
        set(value) { playerView.useController = value }

    /** 是否循环播放。 */
    var loop: Boolean
        get() = player?.repeatMode == Player.REPEAT_MODE_ONE
        set(value) {
            ensurePlayer().repeatMode =
                if (value) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }

    /** 是否自动播放(准备完成后立即播放,默认 true)。 */
    var autoPlay: Boolean = true
        set(value) {
            field = value
            player?.playWhenReady = value
        }

    /** 是否静音。 */
    var muted: Boolean
        get() = player?.volume == 0f
        set(value) { ensurePlayer().volume = if (value) 0f else 1f }

    /** 音量,取值 0.0 - 1.0。 */
    fun setVolume(volume: Float) {
        ensurePlayer().volume = volume.coerceIn(0f, 1f)
    }

    /** 按普通路径/URL 设置媒体源(相对路径按项目工作目录解析)。 */
    fun setVideoPath(path: String) = setMediaUri(toUri(path))

    /** 按 URL 设置媒体源(http/https/rtsp 等)。 */
    fun setVideoUrl(url: String) = setMediaUri(Uri.parse(url))

    /** 按 Uri 设置媒体源。 */
    fun setVideoUri(uri: Uri) = setMediaUri(uri)

    private fun setMediaUri(uri: Uri) {
        val p = ensurePlayer()
        p.setMediaItem(MediaItem.fromUri(uri))
        p.playWhenReady = autoPlay
        p.prepare()
    }

    fun play() { ensurePlayer().play() }

    fun pause() { player?.pause() }

    fun stop() {
        player?.stop()
        player?.clearMediaItems()
    }

    fun seekTo(positionMs: Long) { ensurePlayer().seekTo(positionMs) }

    /** 当前播放位置(毫秒)。 */
    fun getCurrentPosition(): Long = player?.currentPosition ?: 0L

    /** 媒体总时长(毫秒,未知时 0)。 */
    fun getDuration(): Long = player?.duration?.coerceAtLeast(0L) ?: 0L

    fun isPlaying(): Boolean = player?.isPlaying == true

    /** 在应用进入后台/不可见时暂停以省电。 */
    fun onPause() { player?.pause() }

    /** 释放播放器资源;再次播放会自动重建。 */
    fun release() {
        player?.release()
        player = null
        playerView.player = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 视图脱离窗口时释放,避免句柄泄漏导致的黑屏/音频残留
        release()
    }

    /** 普通路径视为本地文件;带协议头的按 URI 处理。 */
    private fun toUri(path: String): Uri {
        val lowered = path.lowercase()
        val hasScheme = lowered.startsWith("http://") || lowered.startsWith("https://") ||
            lowered.startsWith("rtsp://") || lowered.startsWith("rtmp://") ||
            lowered.startsWith("file://") || lowered.startsWith("content://") ||
            lowered.startsWith("asset://") || lowered.startsWith("android.resource://")
        return if (hasScheme) Uri.parse(path) else Uri.fromFile(File(path))
    }
}
