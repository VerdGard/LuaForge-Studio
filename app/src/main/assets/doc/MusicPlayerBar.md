# MusicPlayerBar

一体化音乐播放条,短名 `MusicPlayerBar`。

自带「上一首 / 播放暂停 / 下一首 / 进度条 / 时间显示」,开箱即用。适合背景音乐、
播客、歌单、音效卡片等场景。底层是 Media3 / ExoPlayer,支持本地文件与网络地址。

## 支持的格式

与 `ExoAudioPlayer` 共用同一套格式/协议支持:

- **容器**:MP3 / M4A / AAC / FLAC / Opus / OGG / WAV / AMR / MKA / AC3 / E-AC3 / AC4 / DTS / MIDI
- **流式**:`m3u8`(HLS,含 `podcast.m3u8` 这类点播清单)、`mpd`(DASH)、`rtmp`(直播)
- **防盗链**:通过 `setHeaders{...}` 或 `httpHeaders` 设置 `Referer` / `Cookie`

---

## 一、布局

```lua
{
  MusicPlayerBar,
  id = "player",
  layout_width = "match_parent",
  layout_height = "wrap",
  autoPlay = true,     -- 设置播放列表后是否自动播放(默认 true)
  loop = true,         -- 是否单曲循环
  shuffle = false,     -- 是否随机播放
}
```

---

## 二、属性

| 属性 | 类型 | 说明 |
| --- | --- | --- |
| `autoPlay` | Boolean | 设置播放列表后是否自动播放(默认 `true`) |
| `loop` | Boolean | 单曲循环(置 `true` 时 `REPEAT_MODE_ONE`,否则整表循环) |
| `shuffle` | Boolean | 随机播放(`playNext` 在随机模式下随机选曲) |
| `onStateChanged` | Function | 播放状态变化回调(见下) |
| `httpHeaders` | Map | 默认请求头(`Referer`/`Cookie` 等) |
| `userAgent` | String | 自定义 UA |
| `connectTimeoutMs` / `readTimeoutMs` | Int | 网络超时,默认 15000 |

---

## 三、方法

| 方法 | 说明 |
| --- | --- |
| `setPlaylist(table)` | 设置播放列表(见下方数据格式),并从头播放 |
| `setHeaders(map)` | 设置请求头(防盗链/鉴权);会释放现有播放器,下次播放按新头重建 |
| `play()` / `pause()` / `toggle()` | 播放 / 暂停 / 切换 |
| `playNext()` / `playPrev()` | 下一首 / 上一首 |
| `stop()` | 停止并清空 |
| `seekTo(ms)` | 跳转到指定毫秒 |
| `getCurrentPosition()` | 当前播放位置(毫秒) |
| `getDuration()` | 总时长(毫秒,未知为 0) |
| `isPlaying()` | 是否正在播放 |
| `release()` | 释放播放器资源 |

### 播放列表数据格式

`setPlaylist` 接收一个 Lua 数组,每个元素是一首曲目。曲目字段:

| 字段 | 说明 |
| --- | --- |
| `title` | 显示名(可选,缺省取文件名) |
| `path` | 本地文件路径(与 `url` 二选一) |
| `url` | 网络地址 http/https/rtmp(与 `path` 二选一) |
| `format` | 可选。显式格式:后缀(`flac`/`m3u8`/`mpd`)或完整 MIME;空则按地址推断 |
| `live` | 可选。是否按直播流处理(写入较小的 live target offset) |

> 地址不带可识别后缀时(如 CDN 的 `?token=xxx`),建议显式给 `format`,
> 否则交由 ExoPlayer 嗅探文件头,直播流可能起播偏慢。

---

## 四、完整示例

```lua
require "import"
import "android.widget.*"
import "androidx.appcompat.widget.LinearLayoutCompat"

local layout = {
  LinearLayoutCompat,
  orientation = "vertical",
  layout_width = "fill",
  layout_height = "fill",
  padding = "16dp",
  {
    MusicPlayerBar,
    id = "player",
    layout_width = "fill",
    layout_height = "wrap",
    autoPlay = false,
  },
}

activity.setContentView(loadlayout(layout))

-- 设置播放列表
player.setPlaylist({
  {title = "本地歌曲", path = "music/song1.mp3"},
  {title = "网络歌曲", url = "https://example.com/song2.mp3"},
  {title = "播客",     url = "https://example.com/podcast.m3u8"},          -- HLS 自动识别
  {title = "电台直播", url = "https://example.com/live/stream.m3u8", live = true},
  {title = "无后缀CDN", url = "https://cdn.example.com/a?token=x", format = "mp3"},
})

-- 播放状态变化时更新界面(回调参数:是否在播放、当前索引、当前标题)
player.onStateChanged = function(isPlaying, index, title)
  print("playing=" .. tostring(isPlaying) .. " index=" .. index .. " title=" .. title)
end

-- 随机开、循环关
player.shuffle = true
player.loop = false

-- 播放控制由内置按钮负责;也可代码控制:
-- player.toggle()
-- player.playNext()

function onDestroy()
  player.release()
end
```

---

## 五、生命周期

- 视图脱离窗口时会自动 `release()`。
- 页面销毁建议显式调用 `player.release()`,释放解码器与音频焦点。
- 该控件背景为深色圆角,内部为白色字形按钮;如需自定义外观,可在布局中设置 `background`/`backgroundColor` 覆盖。
