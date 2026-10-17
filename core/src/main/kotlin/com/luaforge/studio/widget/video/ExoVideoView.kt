package com.luaforge.studio.widget.video

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.Keep
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

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
open class ExoVideoView @JvmOverloads constructor(
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

    protected var player: ExoPlayer? = null

    init {
        addView(playerView)
    }

    /** 默认请求头(如防盗链 Referer、Cookie);设置后对后续设置的媒体源生效。 */
    var httpHeaders: Map<String, String>? = null

    /** 自定义 User-Agent;为空则用 Media3 默认值。 */
    var userAgent: String? = null

    /** 网络连接/读取超时(毫秒),默认 15s。 */
    var connectTimeoutMs: Int = 15000
    var readTimeoutMs: Int = 15000

    /**
     * 是否按直播处理:会写入较小的 live target offset 以降低起播延迟。
     *
     * 直播流(RTSP/RTMP)即使不设置也能播放,但显式开启可减少追帧抖动。
     */
    var live: Boolean = false

    /** 显式指定媒体格式(后缀如 `flv`/`m3u8`,或完整 MIME);为空则按地址自动推断。 */
    var format: String? = null

    /** 最近一次设置的媒体源,供 [setHeaders] 重建播放器后重新装载。 */
    private var lastUri: Uri? = null

    /**
     * 懒创建播放器,避免仅作为布局占位时也占用解码资源。
     *
     * 通过 [MediaFormats.buildPlayer] 组装,因此 HLS(m3u8)/DASH(mpd)/
     * SmoothStreaming(ism)/RTSP/RTMP 在依赖齐备时自动可用。
     */
    protected fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val created = MediaFormats.buildPlayer(
            context = context,
            headers = httpHeaders,
            userAgent = userAgent,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs
        )
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
    fun setVideoPath(path: String) = setMediaUri(MediaFormats.toUri(path))

    /** 按 URL 设置媒体源(http/https/rtsp/rtmp 等)。 */
    fun setVideoUrl(url: String) = setMediaUri(Uri.parse(url))

    /** 按 Uri 设置媒体源。 */
    fun setVideoUri(uri: Uri) = setMediaUri(uri)

    /**
     * 设置媒体源。
     *
     * MIME 由 [MediaFormats.inferMimeType] 从地址后缀推断(可由 [format] 覆盖),
     * 这样 `xxx.m3u8?token=...` 这类带查询参数的地址也能正确走 HLS 分支;
     * 推断不出时留空,交给 ExoPlayer 自行嗅探。
     */
    private fun setMediaUri(uri: Uri) {
        lastUri = uri
        val p = ensurePlayer()
        val mime = MediaFormats.inferMimeType(uri, format)
        val isLive = live || MediaFormats.isStreamingScheme(uri)
        p.setMediaItem(MediaFormats.buildMediaItem(uri, mime, isLive))
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

    /**
     * 画面缩放模式:`fit`(默认,完整显示留黑边)/ `fill`(拉伸铺满)/ `zoom`(裁剪铺满)/
     * `fixed_width`(固定宽度)/ `fixed_height`(固定高度)。
     */
    var resizeMode: String
        get() = when (playerView.resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "fill"
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "zoom"
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> "fixed_width"
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> "fixed_height"
            else -> "fit"
        }
        set(value) {
            playerView.resizeMode = when (value.trim().lowercase()) {
                "fill", "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                "zoom", "crop" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                "fixed_width", "fixedwidth", "width" -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                "fixed_height", "fixedheight", "height" -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        }

    /** 在播放 / 暂停之间切换(供自定义控件或单击画面调用)。 */
    fun toggle() {
        if (isPlaying()) pause() else play()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 视图脱离窗口时释放,避免句柄泄漏导致的黑屏/音频残留
        release()
    }

    /** 普通路径视为本地文件;带协议头的按 URI 处理(兼容旧调用,实现见 [MediaFormats.toUri])。 */
    protected fun toUri(path: String): Uri = MediaFormats.toUri(path)

    /**
     * 设置请求头,用于防盗链 / 鉴权等场景。
     *
     * ```lua
     * video.setHeaders({ ["Referer"] = "https://example.com/", ["Cookie"] = "a=1" })
     * ```
     * 对之后设置的媒体源生效;由于播放器是懒创建的,通常无需重建即可生效。
     */
    fun setHeaders(headers: Map<String, String>?) {
        httpHeaders = headers
        // 数据源工厂在建造时就固化进 ExoPlayer,不能热替换:
        // 已有媒体源时重建播放器并重新装载,否则下次 ensurePlayer 自然生效。
        val pending = lastUri
        if (player == null || pending == null) return
        release()
        setMediaUri(pending)
    }
}
