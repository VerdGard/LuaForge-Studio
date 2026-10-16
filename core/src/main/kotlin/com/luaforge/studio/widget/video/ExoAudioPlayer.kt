package com.luaforge.studio.widget.video

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.annotation.Keep
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

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

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val created = ExoPlayer.Builder(context).build()
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

    fun setAudioPath(path: String) = setMediaUri(toUri(path))

    fun setAudioUrl(url: String) = setMediaUri(Uri.parse(url))

    fun setAudioUri(uri: Uri) = setMediaUri(uri)

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

    private fun toUri(path: String): Uri {
        val lowered = path.lowercase()
        val hasScheme = lowered.startsWith("http://") || lowered.startsWith("https://") ||
            lowered.startsWith("rtsp://") || lowered.startsWith("file://") ||
            lowered.startsWith("content://")
        return if (hasScheme) Uri.parse(path) else Uri.fromFile(File(path))
    }
}
