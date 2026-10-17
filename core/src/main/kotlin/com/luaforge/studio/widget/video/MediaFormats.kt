@file:Suppress("UnstableApiUsage")

package com.luaforge.studio.widget.video

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import java.util.Locale

/**
 * 媒体格式与协议的公共支撑层。
 *
 * 供 [ExoVideoView] / [ExoAudioPlayer] / [MusicPlayerBar] 共用,集中解决三件事:
 *
 * 1. 格式识别:把 URL 后缀映射成 Media3 的 MIME,让「无后缀 / 带查询参数」的地址
 *    也能正确走自适应流或对应 extractor;
 * 2. 协议装配:组装 [DefaultDataSource] + [DefaultHttpDataSource],使 HLS(m3u8)、
 *    DASH(mpd)、SmoothStreaming(ism)、RTSP、RTMP 在依赖齐备时可用,并支持
 *    自定义请求头(防盗链 Referer / UA)与超时;
 * 3. 直播:按需写入 [MediaItem.LiveConfiguration],降低直播场景的起播延迟。
 *
 * ### 格式覆盖范围
 * media3-extractor 随 media3-exoplayer 传递引入,以下容器无需额外模块:
 * MP4 / FragmentedMP4 / MKV(Matroska) / WebM / FLV / TS / PS / AVI /
 * MP3 / AAC(ADTS) / FLAC / OGG(Vorbis·Opus) / WAV / AMR / AC3 / AC4。
 *
 * 需要额外模块的协议与自适应流已在 build.gradle.kts 中显式引入:
 * media3-exoplayer-hls / -dash / -smoothstreaming / -rtsp / media3-datasource-rtmp。
 */
object MediaFormats {

    /** 常见视频/音频后缀 -> Media3 MIME。未收录的后缀返回 null(交给 ExoPlayer 自行嗅探)。 */
    private val EXTENSION_MIME: Map<String, String> = mapOf(
        // 自适应流 / 协议
        "m3u8" to MimeTypes.APPLICATION_M3U8,
        "mpd" to MimeTypes.APPLICATION_MPD,
        "ism" to MimeTypes.APPLICATION_SS,
        "isml" to MimeTypes.APPLICATION_SS,
        "rtsp" to MimeTypes.APPLICATION_RTSP,
        // 协议友好别名:用户更习惯直接写 format = "hls" / "dash" 等
        "hls" to MimeTypes.APPLICATION_M3U8,
        "m3u" to MimeTypes.APPLICATION_M3U8,
        "dash" to MimeTypes.APPLICATION_MPD,
        "ss" to MimeTypes.APPLICATION_SS,
        "smoothstreaming" to MimeTypes.APPLICATION_SS,
        // 视频容器
        "mp4" to MimeTypes.VIDEO_MP4,
        "m4v" to MimeTypes.VIDEO_MP4,
        "mp42" to MimeTypes.VIDEO_MP42,
        "mov" to MimeTypes.VIDEO_MP4,
        "mkv" to MimeTypes.VIDEO_MATROSKA,
        "webm" to MimeTypes.VIDEO_WEBM,
        "flv" to MimeTypes.VIDEO_FLV,
        "ts" to MimeTypes.VIDEO_MP2T,
        "m2ts" to MimeTypes.VIDEO_MP2T,
        "mts" to MimeTypes.VIDEO_MP2T,
        "avi" to MimeTypes.VIDEO_AVI,
        "3gp" to MimeTypes.VIDEO_H263,
        "3gpp" to MimeTypes.VIDEO_H263,
        "mpg" to MimeTypes.VIDEO_MPEG,
        "mpeg" to MimeTypes.VIDEO_MPEG,
        "ogv" to MimeTypes.VIDEO_OGG,
        // 音频容器
        "mp3" to MimeTypes.AUDIO_MPEG,
        "m4a" to MimeTypes.AUDIO_MP4,
        "aac" to MimeTypes.AUDIO_AAC,
        "flac" to MimeTypes.AUDIO_FLAC,
        "opus" to MimeTypes.AUDIO_OPUS,
        "ogg" to MimeTypes.AUDIO_OGG,
        "oga" to MimeTypes.AUDIO_OGG,
        "wav" to MimeTypes.AUDIO_WAV,
        "amr" to MimeTypes.AUDIO_AMR,
        "mka" to MimeTypes.AUDIO_MATROSKA,
        "ac3" to MimeTypes.AUDIO_AC3,
        "eac3" to MimeTypes.AUDIO_E_AC3,
        "ac4" to MimeTypes.AUDIO_AC4,
        "dts" to MimeTypes.AUDIO_DTS,
        "mid" to MimeTypes.AUDIO_MIDI,
        "midi" to MimeTypes.AUDIO_MIDI
    )

    /** 带协议头的地址按 URI 处理,否则视为本地文件。 */
    private val SCHEMES = listOf(
        "http://", "https://", "rtsp://", "rtmp://", "rtmps://",
        "udp://", "rtp://", "file://", "content://", "asset://",
        "android.resource://", "data:"
    )

    /** 把普通路径或 URL 统一成 [Uri]。 */
    fun toUri(path: String): Uri {
        val trimmed = path.trim()
        val lowered = trimmed.lowercase(Locale.ROOT)
        return if (SCHEMES.any { lowered.startsWith(it) }) Uri.parse(trimmed)
        else Uri.fromFile(File(trimmed))
    }

    /**
     * 推断 MIME。
     *
     * @param uri      媒体地址
     * @param explicit 调用方显式指定的格式(可为 null);支持传后缀(如 flv)或完整 MIME
     * @return Media3 MIME;无法判断时返回 null,交由 ExoPlayer 嗅探
     */
    fun inferMimeType(uri: Uri, explicit: String? = null): String? {
        val given = explicit?.trim().orEmpty()
        if (given.isNotEmpty()) {
            // 传了完整 MIME 就直接用
            if (given.contains('/')) return given
            // 传了后缀(flv / m3u8 / hls ...)先查表
            EXTENSION_MIME[given.lowercase(Locale.ROOT).removePrefix(".")]?.let { return it }
        }
        // RTSP 由 scheme 判定(地址通常没有后缀,如 rtsp://ip:554/stream)
        if (uri.scheme?.lowercase(Locale.ROOT) == "rtsp") return MimeTypes.APPLICATION_RTSP
        // SmoothStreaming 的规范地址形如 http://host/a.ism/manifest、a.isml/manifest:
        // 最后一段是 manifest,拿不到后缀,必须按 ".ism/"、".isml/" 前缀识别。
        val fullPath = uri.path?.lowercase(Locale.ROOT).orEmpty()
        if (fullPath.contains(".ism/") || fullPath.contains(".isml/") ||
            fullPath.endsWith(".ism") || fullPath.endsWith(".isml")
        ) {
            return MimeTypes.APPLICATION_SS
        }
        // 从最后一段取后缀
        val last = uri.lastPathSegment?.lowercase(Locale.ROOT)
        val extFromLast = last?.substringAfterLast('.', "")
            ?.takeIf { it.isNotEmpty() && it.length <= 6 }
        if (extFromLast != null) {
            EXTENSION_MIME[extFromLast]?.let { return it }
        }
        // 再退一步用整个 path 的后缀(应对 .m3u8?token=xxx 之类)
        val extFromPath = uri.path?.lowercase(Locale.ROOT)?.substringAfterLast('.', "")
        return if (extFromPath.isNullOrEmpty()) null else EXTENSION_MIME[extFromPath]
    }

    /**
     * 组装播放器:自适应流与协议扩展由 [DefaultMediaSourceFactory] 在运行时按需发现,
     * 这里的职责是给出带请求头与超时的 [DefaultHttpDataSource]。
     *
     * @param headers               默认请求头(如 Referer、Cookie)
     * @param userAgent             自定义 UA;为空则用 Media3 默认值
     * @param connectTimeoutMs      连接超时
     * @param readTimeoutMs         读取超时
     * @param crossProtocolRedirect 是否允许 http <-> https 跨协议重定向
     */
    fun buildPlayer(
        context: Context,
        headers: Map<String, String>? = null,
        userAgent: String? = null,
        connectTimeoutMs: Int = 15000,
        readTimeoutMs: Int = 15000,
        crossProtocolRedirect: Boolean = true
    ): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(connectTimeoutMs)
            .setReadTimeoutMs(readTimeoutMs)
            .setAllowCrossProtocolRedirects(crossProtocolRedirect)
        if (!userAgent.isNullOrBlank()) http.setUserAgent(userAgent)
        if (!headers.isNullOrEmpty()) http.setDefaultRequestProperties(headers)

        val dataSource = DefaultDataSource.Factory(context.applicationContext, http)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSource)
        return ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }

    /**
     * 构造 [MediaItem]。
     *
     * @param live 直播场景:写入较小的 target offset 以降低延迟
     */
    fun buildMediaItem(
        uri: Uri,
        mimeType: String? = null,
        live: Boolean = false,
        tag: Any? = null
    ): MediaItem {
        val builder = MediaItem.Builder().setUri(uri)
        if (!mimeType.isNullOrBlank()) builder.setMimeType(mimeType)
        if (tag != null) builder.setTag(tag)
        if (live) {
            builder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(3000L)
                    .setMinOffsetMs(1000L)
                    .setMaxOffsetMs(8000L)
                    .build()
            )
        }
        return builder.build()
    }

    /** 该地址是否属于「流式」协议(是否直播由清单/流本身决定,这里只判协议)。 */
    fun isStreamingScheme(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        return scheme == "rtsp" || scheme == "rtmp" || scheme == "rtmps" ||
            scheme == "udp" || scheme == "rtp"
    }
}
