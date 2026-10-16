# ExoVideoView

基于 Media3 / ExoPlayer 的视频播放控件(自带播放控制器),短名 `ExoVideoView`。

相比系统 `VideoView`,支持 HLS/DASH/RTSP/RTMP 等更多协议与更丰富的编解码。

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
}
```

## 常用方法

| 方法 | 说明 |
| --- | --- |
| `setVideoPath(path)` | 本地文件路径(相对项目或绝对路径) |
| `setVideoUrl(url)` | 网络地址(http/https/rtsp/rtmp) |
| `play()` / `pause()` / `stop()` | 播放 / 暂停 / 停止并清空媒体 |
| `seekTo(ms)` | 跳转到指定毫秒位置 |
| `getCurrentPosition()` | 当前播放位置(毫秒) |
| `getDuration()` | 媒体总时长(毫秒,未知为 0) |
| `isPlaying()` | 是否正在播放 |
| `onPause()` | 页面不可见时暂停(省电) |
| `release()` | 释放播放器资源 |

## 生命周期

页面销毁/视图脱离窗口时会自动 `release()`,也可显式调用。
