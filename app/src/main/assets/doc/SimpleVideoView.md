# SimpleVideoView

无控制器视频播放控件,短名 `SimpleVideoView`。

继承自 `ExoVideoView`,但**默认不显示内置控制条**,适合需要自定义控制 UI 的场景:
沉浸式全屏播放、点击画面才弹出控制条、只做背景/直播画面等。

底层仍是 Media3 / ExoPlayer,支持 HLS / DASH / RTSP / RTMP 等协议。

---

## 一、布局

```lua
{
  SimpleVideoView,
  id = "video",
  layout_width = "match_parent",
  layout_height = "220dp",
  autoPlay = true,       -- 准备完成后自动播放(默认 true)
  loop = false,          -- 是否循环
  muted = false,         -- 是否静音
  resizeMode = "fit",    -- fit / fill / zoom / fixed_width / fixed_height
}
```

---

## 二、属性(可在布局表里直接写,也可之后用 setter)

| 属性 | 类型 | 说明 |
| --- | --- | --- |
| `videoPath` | String | 本地文件路径(相对项目根或绝对路径) |
| `videoUrl` | String | 网络地址(http/https/rtsp/rtmp) |
| `loop` | Boolean | 是否循环播放 |
| `autoPlay` | Boolean | 准备完成后是否自动播放(默认 `true`) |
| `muted` | Boolean | 是否静音 |
| `volume` | Float | 音量 0.0 - 1.0 |
| `resizeMode` | String | 画面缩放:`fit`(默认)/`fill`/`zoom`/`fixed_width`/`fixed_height` |

> 说明:`resizeMode` 的 setter 会做归一化,`stretch`→`fill`、`crop`→`zoom`、`width`→`fixed_width`、`height`→`fixed_height` 均可用。

---

## 三、方法

| 方法 | 说明 |
| --- | --- |
| `setVideoPath(path)` | 设置本地媒体源 |
| `setVideoUrl(url)` | 设置网络媒体源 |
| `setVideoUri(uri)` | 用 `Uri` 设置媒体源 |
| `play()` / `pause()` / `toggle()` | 播放 / 暂停 / 切换 |
| `stop()` | 停止并清空媒体 |
| `seekTo(ms)` | 跳转到指定毫秒位置 |
| `getCurrentPosition()` | 当前播放位置(毫秒) |
| `getDuration()` | 媒体总时长(毫秒,未知为 0) |
| `isPlaying()` | 是否正在播放 |
| `setVolume(v)` | 设置音量 0.0 - 1.0 |
| `setOnToggleCallback(fn)` | 注册「点击画面」回调 |
| `onPause()` | 页面不可见时暂停(省电) |
| `release()` | 释放播放器资源 |

---

## 四、完整示例:自定义控制条

```lua
require "import"
import "android.widget.*"
import "androidx.appcompat.widget.LinearLayoutCompat"
import "com.google.android.material.button.MaterialButton"

local layout = {
  LinearLayoutCompat,
  orientation = "vertical",
  layout_width = "fill",
  layout_height = "fill",
  {
    SimpleVideoView,
    id = "video",
    layout_width = "fill",
    layout_height = "0dp",
    layout_weight = 1,
    videoPath = "video/demo.mp4",   -- 或 videoUrl = "https://.../demo.m3u8"
    autoPlay = true,
    resizeMode = "fit",
  },
  {
    LinearLayoutCompat,
    orientation = "horizontal",
    layout_width = "fill",
    layout_height = "wrap",
    gravity = "center",
    {
      MaterialButton, id = "btnPlay", text = "播放/暂停",
      onClick = function()
        video.toggle()               -- 自定义按钮切换播放
      end,
    },
    {
      MaterialButton, id = "btnFit", text = "缩放",
      onClick = function()
        video.resizeMode = (video.resizeMode == "fit") and "fill" or "fit"
      end,
    },
  },
}

activity.setContentView(loadlayout(layout))

-- 点击画面也可切换(自行决定是否弹控制条)
video.setOnToggleCallback(function()
  video.toggle()
end)

-- 页面回调中释放资源
function onDestroy()
  video.release()
end
```

---

## 五、生命周期

- 视图脱离窗口时会自动 `release()`。
- 建议在 `onPause` 里调用 `video.onPause()` 省电,`onDestroy` 里调用 `video.release()`。
- `release()` 后再次调用 `play()`/设置媒体会自动重建播放器。
