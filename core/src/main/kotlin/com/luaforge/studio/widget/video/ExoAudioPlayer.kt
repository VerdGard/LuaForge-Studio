package com.luaforge.studio.widget.video

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.annotation.Keep
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * 基于 Media3 / ExoPlayer 的音频播放控件(无视频画面)。
 *
 * 适用于背景音乐、音效、播客等场景。作为 IDE 随附的内置控件,布局助手中可直接
 * 使用短名 `ExoAudioPlayer`。
 *
 * 页面销毁时应调用 [release] 释放播放器。
 */
@Keep
class ExoAudioPlayer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var player: ExoPlayer? = null

    /** 默认请求头(如防盗链 Referer、Cookie)。 */
    var httpHeaders: Map<String, String>? = null

    /** 自定义 User-Agent;为空则用 Media3 默认值。 */
    var userAgent: String? = null

    /** 网络连接/读取超时(毫秒)。 */
    var connectTimeoutMs: Int = 15000
    var readTimeoutMs: Int = 15000

    /** 显式指定格式(后缀如 `flac`/`m3u8`,或完整 MIME);为空则按地址自动推断。 */
    var format: String? = null

    /** 是否按直播流处理(如网络电台 HLS/RTMP 直播)。 */
    var live: Boolean = false

    /** 最近一次设置的媒体源,供 [setHeaders] 重建播放器后重新装载。 */
    private var lastUri: Uri? = null

    /**
     * 懒创建播放器。通过 [MediaFormats.buildPlayer] 组装,
     * 因此 HLS / DASH / RTMP 等协议在依赖齐备时自动可用。
     */
    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val created = MediaFormats.buildPlayer(
            context = context,
            headers = httpHeaders,
            userAgent = userAgent,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs
        )
        player = created
        return created
    }

    /** 是否循环播放。 */
    var loop: Boolean
        get() = player?.repeatMode == Player.REPEAT_MODE_ONE
        set(value) {
            ensurePlayer().repeatMode =
                if (value) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }

    /** 是否自动播放(默认 true)。 */
    var autoPlay: Boolean = true
        set(value) {
            field = value
            player?.playWhenReady = value
        }

    /** 音量,取值 0.0 - 1.0。 */
    fun setVolume(volume: Float) {
        ensurePlayer().volume = volume.coerceIn(0f, 1f)
    }

    fun setAudioPath(path: String) = setMediaUri(MediaFormats.toUri(path))

    fun setAudioUrl(url: String) = setMediaUri(Uri.parse(url))

    fun setAudioUri(uri: Uri) = setMediaUri(uri)

    private fun setMediaUri(uri: Uri) {
        lastUri = uri
        val p = ensurePlayer()
        val mime = MediaFormats.inferMimeType(uri, format)
        val isLive = live || MediaFormats.isStreamingScheme(uri)
        p.setMediaItem(MediaFormats.buildMediaItem(uri, mime, isLive))
        p.playWhenReady = autoPlay
        p.prepare()
    }

    /**
     * 设置请求头(防盗链 / 鉴权)。
     *
     * ```lua
     * audio.setHeaders({ ["Referer"] = "https://example.com/" })
     * ```
     */
    fun setHeaders(headers: Map<String, String>?) {
        httpHeaders = headers
        val pending = lastUri
        if (player == null || pending == null) return
        release()
        setMediaUri(pending)
    }

    fun play() { ensurePlayer().play() }
    fun pause() { player?.pause() }
    fun stop() {
        player?.stop()
        player?.clearMediaItems()
    }
    fun seekTo(positionMs: Long) { ensurePlayer().seekTo(positionMs) }
    fun getCurrentPosition(): Long = player?.currentPosition ?: 0L
    fun getDuration(): Long = player?.duration?.coerceAtLeast(0L) ?: 0L
    fun isPlaying(): Boolean = player?.isPlaying == true

    fun release() {
        player?.release()
        player = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }
}
