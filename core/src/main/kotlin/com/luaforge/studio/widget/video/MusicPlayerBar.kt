package com.luaforge.studio.widget.video

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.Keep
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * 一体化音乐播放条(短名 `MusicPlayerBar`)。
 *
 * 自带「上一首 / 播放暂停 / 下一首 / 进度条 / 时间显示」,支持播放列表循环与随机,
 * 适合背景音乐、播客、音效卡片等需要开箱即用控制条的场景。
 *
 * 布局助手中直接写短名:
 * ```lua
 * {
 *   MusicPlayerBar,
 *   id = "player",
 *   layout_width = "match_parent",
 *   layout_height = "wrap",
 *   -- 也可在 Lua 里设置播放列表:
 *   -- player.setPlaylist({{title="A", path="a.mp3"}, {title="B", url="https://.../b.mp3"}})
 * }
 * ```
 */
@Keep
class MusicPlayerBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val rnd = java.util.Random()

    private val prevBtn = ImageButton(context)
    private val playBtn = ImageButton(context)
    private val nextBtn = ImageButton(context)
    private val titleView = TextView(context)
    private val progress = SeekBar(context)
    private val timeView = TextView(context)

    /** 播放列表条目。 */
    data class Track(
        val title: String,
        val uri: String,
        /** 显式格式(后缀或完整 MIME),为空则按地址推断。 */
        val format: String? = null,
        /** 是否直播流。 */
        val live: Boolean = false
    )

    private val tracks = ArrayList<Track>()
    private var currentIndex = 0

    /** 是否随机播放。 */
    var shuffle: Boolean = false
    /** 是否单曲循环。 */
    var loop: Boolean = false
        set(value) {
            field = value
            player?.repeatMode = if (value) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_ALL
        }
    /** 是否自动播放。 */
    var autoPlay: Boolean = true

    /** 播放状态变化回调:(isPlaying, currentIndex, title)。 */
    var onStateChanged: ((Boolean, Int, String) -> Unit)? = null

    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null) {
                val dur = p.duration
                if (dur > 0) {
                    progress.max = dur.toInt()
                    progress.progress = p.currentPosition.toInt()
                    timeView.text = "${fmt(p.currentPosition)} / ${fmt(dur)}"
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    init {
        // 背景
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor("#1F1F1F"))
        }
        setPadding(dp(8), dp(8), dp(8), dp(8))

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        styleBtn(prevBtn, "⏮") { playPrev() }
        styleBtn(playBtn, "▶") { toggle() }
        styleBtn(nextBtn, "⏭") { playNext() }
        buttons.addView(prevBtn)
        buttons.addView(playBtn)
        buttons.addView(nextBtn)

        titleView.setTextColor(Color.WHITE)
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        titleView.maxLines = 1
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        titleView.setPadding(dp(8), 0, dp(8), 0)
        val titleLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        titleView.layoutParams = titleLp

        row.addView(buttons)
        row.addView(titleView)
        addView(row)

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        progress.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) player?.seekTo(value.toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        timeView.setTextColor(Color.LTGRAY)
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        timeView.setPadding(dp(8), 0, 0, 0)
        bottom.addView(progress)
        bottom.addView(timeView)

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        col.addView(row)
        col.addView(bottom)
        addView(col)
    }

    private fun styleBtn(btn: ImageButton, glyph: String, onClick: () -> Unit) {
        btn.setImageDrawable(GlyphDrawable(glyph))
        btn.background = null
        btn.setOnClickListener { onClick() }
        btn.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    }

    /** 默认请求头(如防盗链 Referer、Cookie),对之后设置的播放列表生效。 */
    var httpHeaders: Map<String, String>? = null

    /** 自定义 User-Agent;为空则用 Media3 默认值。 */
    var userAgent: String? = null

    /** 网络连接/读取超时(毫秒)。 */
    var connectTimeoutMs: Int = 15000
    var readTimeoutMs: Int = 15000

    /**
     * 懒创建播放器。通过 [MediaFormats.buildPlayer] 组装,
     * 因此 m3u8 / mpd / rtmp 等协议在依赖齐备时自动可用。
     */
    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val p = MediaFormats.buildPlayer(
            context = context,
            headers = httpHeaders,
            userAgent = userAgent,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs
        )
        p.repeatMode = Player.REPEAT_MODE_ALL
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playBtn.setImageDrawable(GlyphDrawable(if (isPlaying) "⏸" else "▶"))
                onStateChanged?.invoke(isPlaying, currentIndex, currentTitle())
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentIndex = p.currentMediaItemIndex
                titleView.text = currentTitle()
                onStateChanged?.invoke(p.isPlaying, currentIndex, currentTitle())
            }
        })
        player = p
        return p
    }

    private fun currentTitle(): String =
        tracks.getOrNull(currentIndex)?.title ?: ""

    /**
     * 设置播放列表并从头播放。
     *
     * 参数可直接传 Lua 表,例如:
     * ```lua
     * player.setPlaylist({
     *   {title = "第一首", path = "music/a.mp3"},
     *   {title = "第二首", url = "https://.../b.mp3"},
     * })
     * ```
     * 每个条目支持 `title`(显示名,缺省取文件名)、`path`(本地路径)或 `url`(网络地址)。
     */
    @Keep
    fun setPlaylist(list: Any?) {
        tracks.clear()
        val items = normalizeTracks(list)
        items.forEach { item ->
            val title = item["title"] ?: item["path"]?.substringAfterLast('/') ?: "Track"
            val uri = item["url"] ?: item["path"] ?: return@forEach
            val fmt = item["format"] ?: item["mime"]
            val isLive = item["live"]?.equals("true", ignoreCase = true) == true
            tracks.add(Track(title, uri, fmt, isLive))
        }
        val p = ensurePlayer()
        p.clearMediaItems()
        tracks.forEach { track ->
            val uri = MediaFormats.toUri(track.uri)
            val mime = MediaFormats.inferMimeType(uri, track.format)
            val isLive = track.live || MediaFormats.isStreamingScheme(uri)
            p.addMediaItem(MediaFormats.buildMediaItem(uri, mime, isLive, tag = track.title))
        }
        currentIndex = 0
        p.shuffleModeEnabled = shuffle
        p.prepare()
        p.playWhenReady = autoPlay
        titleView.text = currentTitle()
    }

    /** 把 Lua 表 / Java List 归一化成条目表列表。 */
    @Suppress("UNCHECKED_CAST")
    private fun normalizeTracks(list: Any?): List<Map<String, String>> {
        val out = ArrayList<Map<String, String>>()
        when (list) {
            is com.luajava.LuaTable<*, *> -> {
                for (entry in list.entries) {
                    val v = entry.value
                    if (v is com.luajava.LuaTable<*, *>) out.add(luaTableToStringMap(v))
                    else if (v is Map<*, *>) out.add(v.entries.associate { it.key.toString() to it.value.toString() })
                }
            }
            is List<*> -> {
                list.forEach { v ->
                    if (v is com.luajava.LuaTable<*, *>) out.add(luaTableToStringMap(v))
                    else if (v is Map<*, *>) out.add(v.entries.associate { it.key.toString() to it.value.toString() })
                }
            }
            is Map<*, *> -> out.add(list.entries.associate { it.key.toString() to it.value.toString() })
        }
        return out
    }

    private fun luaTableToStringMap(t: com.luajava.LuaTable<*, *>): Map<String, String> {
        val m = HashMap<String, String>()
        for (e in t.entries) {
            val k = e.key?.toString() ?: continue
            val v = e.value?.toString() ?: continue
            m[k] = v
        }
        return m
    }

    fun play() { ensurePlayer().play() }
    fun pause() { player?.pause() }
    fun stop() {
        player?.stop()
        player?.clearMediaItems()
    }

    fun toggle() {
        val p = ensurePlayer()
        if (p.isPlaying) p.pause() else p.play()
    }

    /** 播放上一首(到头部则回到最后一首)。 */
    fun playPrev() {
        val p = ensurePlayer()
        if (tracks.isEmpty()) return
        currentIndex = (currentIndex - 1 + tracks.size) % tracks.size
        p.seekTo(currentIndex, 0L)
        p.play()
    }

    /** 播放下一首(随机模式下随机选一首)。 */
    fun playNext() {
        val p = ensurePlayer()
        if (tracks.isEmpty()) return
        currentIndex = if (shuffle) rnd.nextInt(tracks.size) else (currentIndex + 1) % tracks.size
        p.seekTo(currentIndex, 0L)
        p.play()
    }

    fun seekTo(positionMs: Long) { ensurePlayer().seekTo(positionMs) }
    fun getCurrentPosition(): Long = player?.currentPosition ?: 0L
    fun getDuration(): Long = player?.duration?.coerceAtLeast(0L) ?: 0L
    fun isPlaying(): Boolean = player?.isPlaying == true

    fun release() {
        handler.removeCallbacks(ticker)
        player?.release()
        player = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(ticker)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }

    /**
     * 设置请求头(防盗链 / 鉴权),对之后调用 [setPlaylist] 生效。
     *
     * 由于数据源工厂在建造时固化,已创建播放器的请求头变更需重建:
     * 这里直接释放,下一次 setPlaylist/play 会按新头重建。
     */
    fun setHeaders(headers: Map<String, String>?) {
        httpHeaders = headers
        if (player != null) release()
    }

    private fun dp(v: Number): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun fmt(ms: Long): String {
        val total = ms / 1000
        val m = total / 60
        val s = total % 60
        return "%02d:%02d".format(m, s)
    }

    /** 用 Unicode 字形绘制按钮图标,避免依赖 PNG 资源。 */
    private class GlyphDrawable(private val glyph: String) : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
        }
        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            paint.textSize = b.height() * 0.55f
            val cy = b.exactCenterY() - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(glyph, b.exactCenterX(), cy, paint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }
}
