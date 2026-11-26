# LuaForge-Studio 运行时 API 速查(可直接复制)

> 本文件把**随运行时可用**的全局对象、函数与 Java 类整理成可直接复制的代码。
> 写业务代码前先在这里找一遍,**能找到就直接用,不要用纯 Lua 重造轮子**。
> 配套:`WidgetsCatalog.md`(控件与 UI 配方)、`import.lua`/`loadlayout.lua`(布局机制)。

---

## 一、全局对象(启动即注入,见 SKILL.md §1.6)

| 名字 | 内容 |
| --- | --- |
| `activity` / `this` | 当前 `LuaActivity` 实例 |
| `R` | `com.luaforge.studio.core.R`(项目资源) |
| `android.R` / `androidx.R` / `material.R` | 框架 / AppCompat / Material 资源 |
| `luajava` | Java 桥接;带 `luadir` / `luapath` / `luaextdir` 路径字段 |
| `print` | 定制打印,落 `luaforge.log` |
| `set(name, v)` / `call(name, ...)` | 跨线程设值 / 调用 |

`require "import"` 之后:全局 `import`、`loadlayout`、`loadbitmap`、`loadmenu` 及工具函数(见下)。

---

## 二、import 注入的工具函数(均来自 `core/src/main/resources/lua/import.lua`)

```lua
require "import"

-- 遍历 Java 迭代器:for v in each(javaList) do ... end
for item in each(someJavaList) do
  print(item)
end

-- 遍历 Java 枚举:for v in enum(javaEnumeration) do ... end
for v in enum(e) do print(v) end

-- 打印任意 Lua/Java 值为可读字符串(调试用)
print(dump(someTable))

-- 打印调用栈(调试用)
printstack()

-- 异步任务:在子线程执行 src(函数或 .lua 路径),完成回调在主线程
task(function()
  -- 子线程:耗时活儿
  return doHeavyWork()
end, function(result)
  -- 主线程:更新 UI
  title.text = tostring(result)
end)

-- 线程:立即在子线程跑一段
local t = thread(function() print("in thread") end)

-- 定时器:timer(f, delay, period, ...)  period==0 表示只跑一次
local tm = timer(function() print("tick") end, 0, 1000)
-- tm.stop()
```

> `getids()` 是死代码(读 `luajava.ids`,无赋值点),**别用**。

---

## 三、Activity 常用方法(`activity.*`,来自 `LuaActivity.java`)

### 3.1 页面跳转与取参数

```lua
-- 打开另一个 Lua 页面(路径相对项目根)
activity.newActivity("other/main.lua")

-- 带参数:第二个参数是 Object[] 风格的表
activity.newActivity("other/main.lua", {"hello", 42})

-- 带 requestCode(目标页调用 activity.result(...) 时回到 onResult)
activity.newActivity(1, "other/main.lua", {"a"})

-- 带转场动画资源 id
activity.newActivity("other/main.lua", R.anim.slide_in, R.anim.slide_out)
```

新页面里取参数:

```lua
local a0 = activity.getArg(0)          -- 第 1 个参数(⚠️ 下标 0 起)
local a1 = activity.getArg(1)          -- 第 2 个参数
local q  = activity.getQuery("k")      -- 从 Intent data 的 Uri 查询串取
```

> ⚠️ `getArg` 的实现是 `if (arg == null || arg.length >= idx) return nil; return arg[idx]`。
> **下标是 0 起**,而且那个边界判断写反了 —— 传入等于长度的下标会越界。
> 安全写法:`for i = 0, #params - 1 do ... end`,不要靠它做边界检查。

### 3.2 结束与结果

```lua
-- 关掉当前页
activity.finish()

-- 回传结果给调用方(配合 newActivity 的 requestCode)
activity.result({"data1", "data2"})
```

`activity.result(data)` 会把 `data` 作为 `Object[]` 放进 Intent 的 `DATA` extra,
然后 `finish()`。调用方的 `onResult(name, ...)` 会收到(见 SKILL.md §1.4)。

### 3.3 路径

```lua
local dir  = activity.getLuaDir()             -- 项目目录
local ext  = activity.getLuaExtDir()          -- 外部数据目录(LuaForge-Studio/)
local path = activity.getLuaPath("a/b.lua")   -- 项目内相对 → 绝对
local epath = activity.getLuaExtPath("x.txt") -- 外部目录内相对 → 绝对
```

### 3.4 屏幕 / 视图 / 资源

```lua
local w, h = activity.getWidth(), activity.getHeight()   -- 屏幕像素
local root = activity.getDecorView()
local res  = activity.getResources()
local d    = res.getDisplayMetrics().density             -- dp → px 的倍数
```

### 3.5 跨页面共享数据

同一进程内所有 Lua 页面共享一份 `Map`:

```lua
activity.setSharedData("user", {name = "张三"})
local u  = activity.getSharedData("user")
local v  = activity.getSharedData("nope", "默认值")   -- 不存在时返回默认值
```

> 只在本进程内有效,**页面全部退出即丢**;要持久化请写文件或用 `SharedPreferences`。

### 3.6 选项菜单

```lua
function onCreateOptionsMenu(menu)
  menu.add(0, 1, 0, "设置")
  return true
end

function onOptionsItemSelected(item)
  if item.getItemId() == 1 then
    -- ...
    return true
  end
  return false
end

-- 需要主动拿菜单时可读(通常用不到)
local menu = activity.getOptionsMenu()
```

---

## 四、网络请求(优先 `Http`,`com.androlua.Http`)

> 前提:`settings.json` 的 `user_permission` 含 `INTERNET`(漏了则回调 `code == -1`)。
> 更多细节(含自动申请权限)见 `devdoc/NETWORK.md`。

```lua
require "import"

-- GET
Http.get("https://api.example.com/data", function(code, content, cookie, headers)
  if code == 200 then print(content) end
end)

-- GET 带自定义请求头(header 为表)
Http.get("https://api.example.com/user", nil, "UTF-8",
  {["Authorization"] = "Bearer token123"},
  function(code, content, cookie, headers) end)

-- POST:第二参可为字符串或表单字段表
Http.post("https://api.example.com/login", {user = "a", pass = "b"},
  function(code, content, cookie, headers) end)

-- 下载文件:回调是 (code, path, headers) 三个参数
Http.download("https://example.com/a.zip", "/sdcard/a.zip",
  function(code, path, headers) end)

-- 全局默认头 / UA / Cookie(可选)
Http.setUserAgent("LuaForge/1.0")
Http.setCookie("session=abc")

-- 失败时回调只有两个参数且 code == -1
Http.get("https://bad.example.com", function(code, msg)
  if code == -1 then print("请求失败:" .. tostring(msg)) end
end)
```

**次选 `Https.*`**(OkHttp,需 `global_utils` 含 `OkHttpUtil`):`Https.get/post/upload/download`。详见 `OkHttpUtil.md`。

---

## 五、图片加载

```lua
require "import"

-- 通用"加载图片为 Bitmap"(本地相对项目、绝对路径、http 均可)
local bmp = loadbitmap("images/icon")            -- 自动补 .png
imageView.setImageBitmap(bmp)
local bmp2 = loadbitmap("https://example.com/a.png")

-- 项目内图片直接做 ImageView 的 src(布局里)
-- { ImageView, id="img", src="images/icon.png", layout_width="64dp", layout_height="64dp" }

-- 项目图标
-- { ImageView, imageResource=R.drawable.icon }
```

更丰富的异步加载 / 圆角 / 缓存用 `GlideUtil`(需 `global_utils` 含 `GlideUtil`),见 `GlideUtil.md`。

---

## 六、列表与适配器

### 6.1 推荐:`RecyclerAdapterUtil`(一行建适配器)

需 `settings.json` 的 `global_utils` 含 `RecyclerAdapterUtil`,见 `RecyclerAdapterUtil.md`。

```lua
local adapter = createAdapter(data, itemLayout, {
  onBindViewHolder = function(vh, item, position)
    vh.itemView.title.text = item.title
  end,
})
list.setAdapter(adapter)
insertItem(adapter, 0, newItem)     -- 自带动画,无需 notify
```

### 6.2 传统:`LuaAdapter` / `LuaArrayAdapter`(绑定 ListView)

```lua
require "import"
import "android.widget.ListView"

-- 每条数据是一个表,键名对应 item 布局里的 id
local data = {
  { name = "第一项", desc = "说明" },
  { name = "第二项", desc = "说明" },
}

local itemLayout = {
  LinearLayoutCompat, orientation = "vertical",
  layout_width = "fill", layout_height = "wrap", padding = "12dp",
  { MaterialTextView, id = "name", textSize = "16sp" },
  { MaterialTextView, id = "desc", textSize = "12sp" },
}

local adapter = LuaAdapter(activity, data, itemLayout)
list.setAdapter(adapter)
```

改动数据用它的方法(都会自动 `notifyDataSetChanged`):

| 方法 | 说明 |
|---|---|
| `adapter.insert(pos, item)` | 在 `pos`(**0 起算**)插入一条 |
| `adapter.remove(pos)` | 删除 `pos` 处(0 起算) |
| `adapter.addAll(items)` | 批量追加 |
| `adapter.clear()` | 清空 |
| `adapter.setNotifyOnChange(false)` | 批量改动前关掉通知,改完再 `notifyDataSetChanged()` |

```lua
adapter.insert(0, { name = "新的第一项", desc = "" })
adapter.remove(2)
adapter.clear()
```

其它适配器:

```lua
-- 多类型(按 position 或数据类型分不同 item 布局)
local multi = LuaMultiAdapter(activity, data, { [0] = itemLayout1, [1] = itemLayout2 })

-- 简单文本列表
local arr = LuaArrayAdapter(activity, android.R.layout.simple_list_item_1, { "A", "B", "C" })
list.setAdapter(arr)
```

---

## 七、对话框(优先 MaterialAlertDialogBuilder,别用不存在的 `alert()`/`toast()`)

```lua
require "import"
import "com.google.android.material.dialog.MaterialAlertDialogBuilder"

MaterialAlertDialogBuilder(activity)
  .setTitle("标题")
  .setMessage("内容")
  .setPositiveButton("确定", function(dialog, which)
    print("点了确定")
  end)
  .setNegativeButton("取消", nil)
  .show()
```

```lua
-- 轻量反馈用 Snackbar(需 Material 依赖,已随运行时有)
import "com.google.android.material.snackbar.Snackbar"
Snackbar.make(activity.getDecorView(), "已保存", Snackbar.LENGTH_SHORT).show()
```

> **不要写** `toast(...)` / `alert(...)` / `dialog(...)` / `sleep(...)`:本仓库无这些全局简写的注册证据。

---

## 八、服务 / 广播 / 线程

### 8.1 Lua 服务

```lua
-- 启动同项目的 service/main.lua(会自动作为 Service 运行)
activity.startService("service/main.lua")

-- 带参数启动
activity.startService("service/main.lua", {"arg1"})

-- 停止
activity.stopService()
```

服务的生命周期回调写在服务自己的 Lua 文件里(`LuaService` 按名调用这几个全局函数,
`LuaService.java:281-340`):

| 回调 | 参数 |
|---|---|
| `onCreate()` | — |
| `onStartCommand(intent, flags, startId)` | 三个 |
| `onStart(arg)` | `startService(path, arg)` 传进来的表 |
| `onDestroy()` | — |
| `onReceive(context, intent)` | 广播 |
| `onError(title, msg)` | 出错 |

### 8.2 广播

三种登记方式(`LuaActivity.java:647-660`):

```lua
require "import"
import "android.content.IntentFilter"

-- 方式一(最常用):只给 filter,收到后走全局 onReceive
activity.registerReceiver(IntentFilter("android.intent.action.BATTERY_CHANGED"))

function onReceive(context, intent)
  print("收到广播: " .. intent.getAction())
end

-- 方式二:传一个 OnReceiveListener(用「接口类{方法表}」语法生成代理)
local ltr = LuaBroadcastReceiver.OnReceiveListener {
  onReceive = function(context, intent)
    print("局部处理: " .. intent.getAction())
  end,
}
activity.registerReceiver(ltr, IntentFilter("com.example.EVENT"))
```

> 方式一每次注册会**先注销上一个**(`if (mReceiver != null) unregisterReceiver(...)`),
> 所以同时监听多个 action 请写进同一个 `IntentFilter`。

### 8.3 子线程 / 异步任务 / 定时器

见本文第二节的 `task` / `thread` / `timer`。

---

## 九、WebView

```lua
require "import"
import "com.androlua.LuaWebView"
-- 布局里可直接用 WebView,或 LuaWebView(activity)
web.loadUrl("https://example.com")
web.loadData("<h1>Hi</h1>", "text/html", "utf-8")
```

---

## 十、文件 / 压缩 / 摘要(要原生工具,别纯 Lua 重写)

这些是**随运行时的原生库**,直接 `require`,不需要配 `global_utils`:

```lua
local lfs = require "lfs"            -- 目录遍历 / 属性 / 增删
for name in lfs.dir(activity.getLuaDir()) do print(name) end
local attr = lfs.attributes(activity.getLuaPath("main.lua"))
if attr then print(attr.size, attr.modification, attr.mode) end

local cjson = require "cjson"        -- JSON(首选)
local text = cjson.encode({a = 1})
local obj  = cjson.decode(text)

local crypt = require "crypt"        -- base64 / hex / sha1 / hmac
print(crypt.base64encode("hi"))
print(crypt.hexencode(crypt.sha1("abc")))    -- ⚠️ sha1 返回二进制,必须 hexencode
```

`LuaUtil` 的现成方法(静态,经 luajava 直接调):

```lua
import "com.androlua.LuaUtil"

LuaUtil.copyFile("/sdcard/a", "/sdcard/b")   -- 无返回值(内部异常被吞)
LuaUtil.copyDir("/sdcard/d1", "/sdcard/d2")  -- 返回 boolean
LuaUtil.rmDir(File("/sdcard/d2"))            -- 参数是 File 对象
LuaUtil.unZip("/sdcard/a.zip")               -- 解到同名目录
LuaUtil.zip("/sdcard/d1")                    -- 压缩成 d1.zip
LuaUtil.getFileMD5("/sdcard/a")              -- 十六进制字符串
LuaUtil.getFileSha1("/sdcard/a")
LuaUtil.getMD5("字符串")
LuaUtil.readAsset(activity, "x.txt")         -- byte[]
```

> 完整原生库清单、每个库的可用函数与"别写 `require "lsqlite3"`"等注意点见
> **SKILL.md §1.10**;`lfs` / `zlib` / `zip` / `regex` / `time` 的最小示例也在那里。

---

## 十一、自绘(LuaDrawable)

需要"随数据变化"的自定义图形(图表、进度环、签名板)时:

```lua
local LuaDrawable = luajava.bindClass "com.androlua.LuaDrawable"
local Paint = luajava.bindClass "android.graphics.Paint"

view.setBackground(LuaDrawable(function(canvas, paint, self)
  local b = self.getBounds()          -- 永远以 bounds 为准
  paint.setAntiAlias(true)
  paint.setColor(0xFF6750A4)
  canvas.drawCircle(b.centerX(), b.centerY(), math.min(b.width(), b.height()) / 2, paint)
end))

-- 数据变了要让 View 重绘
view.invalidate()
```

**静态**圆角/纯色背景别用它,用 `GradientDrawable` 就行:

```lua
local GradientDrawable = luajava.bindClass "android.graphics.drawable.GradientDrawable"
local bg = GradientDrawable()
bg.setShape(GradientDrawable.RECTANGLE)
bg.setColor(0xFFF3EDF7)
bg.setCornerRadius(24)
card.setBackground(bg)
```

完整参考(含 `require "canvas"` 原生模块的逐函数说明、参数类型陷阱、5 个完整示例)见
**`app/src/main/assets/doc/LuaDraw.md`**。

---

## 十二、日志与调试

```lua
print("会落 luaforge.log,并被调试浮窗接管")   -- 运行时唯一推荐输出方式
-- MCP: get_logs / get_runtime_errors / get_editor_logs / debugger_outputs 读取
```

日志位置:`/storage/emulated/0/LuaForge-Studio/luaforge.log`
(外部不可写时回退 `filesDir/logs/luaforge.log`)。

崩溃会另存到 `/storage/emulated/0/LuaForge-Studio/crash/`(设置页「崩溃记录」可查看/删除)。

---

## 十三、易错点回顾

1. 不 `require "import"` 就没有 `import`/`loadlayout`,连 `LinearLayoutCompat` 都解析不到。
2. 界面用 `loadlayout`,列表用适配器;导航控件(底部栏/抽屉)加菜单用 `.Menu.add(...)`。
3. 弹窗用 `MaterialAlertDialogBuilder`;`toast`/`alert` 不存在。
4. 子线程做耗时,主线程更新 UI:`task(fn, callback)`。
5. 属性赋值自动补 `set`(`view.text = "x"` 等价 `setText`);方法无返回时链式返回自身。
6. 路径:相对项目写 `"main.lua"`;绝对路径直接写;`loadbitmap` 会自动补 `.png`。
