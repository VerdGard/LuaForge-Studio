# ExoAudioPlayer

基于 Media3 / ExoPlayer 的音频播放控件(无视频画面),短名 `ExoAudioPlayer`。

适用于背景音乐、音效、播客等。

## 布局

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
| `setAudioUrl(url)` | 网络地址(http/https) |
| `play()` / `pause()` / `stop()` | 播放 / 暂停 / 停止 |
| `seekTo(ms)` | 跳转到指定毫秒位置 |
| `setVolume(v)` | 音量 0.0 - 1.0 |
| `release()` | 释放播放器资源 |

## 生命周期

视图脱离窗口时会自动 `release()`,也可显式调用。
