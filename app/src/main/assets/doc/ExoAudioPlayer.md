# ExoAudioPlayer

基于 Media3 / ExoPlayer 的音频播放控件(无视频画面),短名 `ExoAudioPlayer`。

适用于背景音乐、音效、播客、网络电台等。

## 一、支持的音频格式

以下格式随 `media3-extractor` 内置,无需额外依赖:

MP3 / M4A / AAC(ADTS)/ FLAC / Opus / OGG(Vorbis)/ WAV / AMR / MKA /
AC3 / E-AC3 / AC4 / DTS / MIDI

同时支持流式地址:`m3u8`(HLS 音频流)、`mpd`(DASH)、`rtmp`(网络电台直播)等,
与视频控件共用同一套协议扩展。

## 二、布局

```lua
{
  ExoAudioPlayer,
  id = "audio",
  layout_width = "match_parent",
  layout_height = "0dp",
  autoPlay = true,
  loop = true,
}
```

## 常用方法

| 方法 | 说明 |
| --- | --- |
| `setAudioPath(path)` | 本地文件路径 |
| `setAudioUrl(url)` | 网络地址(http/https/rtmp 等) |
| `setAudioUri(uri)` | 用 `Uri` 设置媒体源 |
| `setHeaders(map)` | 设置请求头(防盗链/鉴权);已有媒体源时会重建播放器 |
| `play()` / `pause()` / `stop()` | 播放 / 暂停 / 停止 |
| `seekTo(ms)` | 跳转到指定毫秒位置 |
| `setVolume(v)` | 音量 0.0 - 1.0 |
| `release()` | 释放播放器资源 |

## 生命周期

视图脱离窗口时会自动 `release()`,也可显式调用。

## 三、可写属性

| 属性 | 类型 | 说明 |
| --- | --- | --- |
| `format` | String | 显式格式:后缀(`mp3`/`flac`/`opus`/`m3u8`...)或完整 MIME |
| `live` | Boolean | 是否按直播流处理(网络电台) |
| `httpHeaders` | Map | 默认请求头 |
| `userAgent` | String | 自定义 UA |
| `connectTimeoutMs` | Int | 连接超时,默认 15000 |
| `readTimeoutMs` | Int | 读取超时,默认 15000 |

## 四、示例

```lua
{
  ExoAudioPlayer,
  id = "bgm",
  layout_width = "0dp",
  layout_height = "0dp",
  autoPlay = true,
  loop = true,
}

-- 本地音频
bgm.setAudioPath("music/bgm.mp3")

-- 网络电台(HLS 直播)
bgm.live = true
bgm.setHeaders{ ["Referer"] = "https://radio.example.com/" }
bgm.setAudioUrl("https://radio.example.com/live/index.m3u8")
```
