# ExoVideoView

基于 Media3 / ExoPlayer 的视频播放控件(自带播放控制器),短名 `ExoVideoView`。

相比系统 `VideoView`,支持更多协议与容器格式。

## 一、支持的格式与协议

### 自适应流 / 流媒体协议

| 协议 | 地址示例 | 说明 |
| --- | --- | --- |
| HLS | `https://a.com/live/index.m3u8` | 含直播与点播;`.m3u8` 自动识别 |
| DASH | `https://a.com/vod/manifest.mpd` | `.mpd` 自动识别 |
| SmoothStreaming | `https://a.com/x.ism/manifest` | `.ism` / `.isml` 自动识别 |
| RTSP | `rtsp://192.168.1.10:554/stream` | 监控/摄像头常用 |
| RTMP | `rtmp://a.com/live/stream` | 直播推拉流 |
| HTTP-FLV | `https://a.com/live/stream.flv` | FLV over HTTP 直播/点播 |
| UDP / RTP | `udp://@239.0.0.1:1234` | 组播场景 |

### 渐进式容器(无需额外依赖)

- **视频**:MP4 / M4V / MOV / MKV(Matroska)/ WebM / FLV / TS / M2TS / AVI / 3GP / MPEG(PS·ES)/ OGV
- **音频**:MP3 / M4A / AAC(ADTS)/ FLAC / Opus / OGG(Vorbis)/ WAV / AMR / MKA / AC3 / E-AC3 / AC4 / DTS / MIDI

> 后缀无法判断时(如 `?token=xxx` 或 CDN 无后缀地址),可显式设置 `format`
> 或用 `mimeType` 形式指定;都不给则交由 ExoPlayer 嗅探文件头。

## 二、防盗链 / 请求头

```lua
video.setHeaders{
  ["Referer"] = "https://example.com/",
  ["Cookie"] = "session=abc",
}
-- 或布局/属性直接写
video.userAgent = "Mozilla/5.0 ..."
video.connectTimeoutMs = 20000
video.readTimeoutMs = 20000
```

## 布局

```lua
{
  ExoVideoView,
  id = "video",
  layout_width = "match_parent",
  layout_height = "250dp",
  useController = true,
  autoPlay = true,
  loop = false,
  -- format = "hls",     -- 可选:显式指定格式(后缀或完整 MIME)
  -- live = true,        -- 可选:按直播处理,降低起播延迟
}
```

## 常用方法

| 方法 | 说明 |
| --- | --- |
| `setVideoPath(path)` | 本地文件路径(相对项目或绝对路径) |
| `setVideoUrl(url)` | 网络地址(http/https/rtsp/rtmp/flv 等) |
| `setVideoUri(uri)` | 用 `Uri` 设置媒体源 |
| `setHeaders(map)` | 设置请求头(防盗链/鉴权);已有媒体源时会重建播放器 |
| `toggle()` | 在播放/暂停之间切换 |
| `setVolume(v)` | 音量 0.0 - 1.0 |
| `play()` / `pause()` / `stop()` | 播放 / 暂停 / 停止并清空媒体 |
| `seekTo(ms)` | 跳转到指定毫秒位置 |
| `getCurrentPosition()` | 当前播放位置(毫秒) |
| `getDuration()` | 媒体总时长(毫秒,未知为 0) |
| `isPlaying()` | 是否正在播放 |
| `onPause()` | 页面不可见时暂停(省电) |
| `release()` | 释放播放器资源 |

## 生命周期

页面销毁/视图脱离窗口时会自动 `release()`,也可显式调用。

## 三、可写属性

| 属性 | 类型 | 说明 |
| --- | --- | --- |
| `format` | String | 显式格式:后缀(`flv`/`m3u8`/`mpd`/`ism`/`ts`/`mkv`/`avi`...)或完整 MIME |
| `live` | Boolean | 是否按直播处理(写入较小的 live target offset) |
| `httpHeaders` | Map | 默认请求头 |
| `userAgent` | String | 自定义 UA |
| `connectTimeoutMs` | Int | 连接超时,默认 15000 |
| `readTimeoutMs` | Int | 读取超时,默认 15000 |
| `resizeMode` | String | `fit`/`fill`/`zoom`/`fixed_width`/`fixed_height` |
