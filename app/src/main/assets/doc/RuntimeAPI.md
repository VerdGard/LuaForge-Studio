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

```lua
-- 页面跳转
activity.newActivity("other/main.lua")            -- 打开另一个 Lua 页面
activity.newActivity("other/main.lua", {arg1})    -- 带参数
activity.newActivity(1, "other/main.lua")         -- 带 requestCode(配合 onActivityResult)

-- 结束/结果
activity.finish()
activity.setResult(activity.RESULT_OK, intent)

-- 取参数(新页面里)
local q = activity.getQuery("key")     -- URL/Intent 查询参数
local a = activity.getArg(1)           -- newActivity 传入的第 1 个参数

-- 路径
local dir  = activity.getLuaDir()      -- 项目目录
local ext  = activity.getLuaExtDir()   -- 外部数据目录
local path = activity.getLuaPath("a/b.lua")

-- 屏幕尺寸
local w, h = activity.getWidth(), activity.getHeight()

-- 视图/资源
local root = activity.getDecorView()
local res  = activity.getResources()

-- 共享数据(同进程跨页面)
activity.setSharedData("key", "value")
local v = activity.getSharedData("key")
local v2 = activity.getSharedData("key", "默认值")   -- 带默认

-- 选项菜单(配合 onCreateOptionsMenu/onOptionsItemSelected)
local menu = activity.getOptionsMenu()
```

---

## 四、网络请求(优先 `Http`,`com.androlua.Http`)

> 前提:`settings.json` 的 `user_permission` 含 `INTERNET`。开发期会被 IDE 网络拦截,打包后不拦截。

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

-- LuaAdapter(context, 数据表, item 布局表/文件)
local itemLayout = {
  LinearLayoutCompat, orientation="vertical",
  layout_width="fill", layout_height="wrap", padding="12dp",
  { MaterialTextView, id="name", textSize="16sp" },
}
local adapter = LuaAdapter(activity, data, itemLayout)
list.setAdapter(adapter)

-- 多类型
local multi = LuaMultiAdapter(activity, data, { [0]=itemLayout1, [1]=itemLayout2 })
-- 数组适配器(简单文本)
local arr = LuaArrayAdapter(activity, R.layout.simple_list_item_1, { "A", "B", "C" })
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

```lua
-- 启动/绑定 Lua 服务(同项目 service/main.lua)
activity.startService("service/main.lua")
activity.bindService(ServiceConnection{ ... }, 0)
activity.stopService()

-- 广播接收
require "import"
import "android.content.IntentFilter"
local filter = IntentFilter("android.intent.action.BATTERY_CHANGED")
activity.registerReceiver(IntentFilter("com.example.EVENT"))
-- 收到后在全局 onReceive(context, intent) 处理

-- 子线程 / 异步任务 / 定时器(见第二节 task/thread/timer)
```

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

```lua
-- 需在 settings.json 的 global_utils 里? 不用 —— 这些是随运行时的原生库,直接 require:
local lfs = require "lfs"            -- 目录遍历/属性
for f in lfs.dir(activity.getLuaDir()) do print(f) end
print(lfs.attributes("main.lua").size)

local zlib = require "zlib"          -- 压缩
local crypt = require "crypt"        -- base64/hex/sha1/hmac
local md5 = require "md5"
local json = require "cjson"         -- 或 require "yyjson"

-- LuaUtil 里的现成方法(通过 luajava 调)
import "com.androlua.LuaUtil"
LuaUtil.copyDir("a", "b")
LuaUtil.unZip("/path/a.zip")
LuaUtil.getFileMD5("/path/file")
```

> 完整原生库清单与"别写 lsqlite3"等注意点见 SKILL.md §1.10。

---

## 十一、日志与调试

```lua
print("会落 luaforge.log,并被调试浮窗接管")   -- 运行时唯一推荐输出方式
-- MCP: get_logs / get_runtime_errors / debugger_outputs 读取
```

---

## 十二、易错点回顾

1. 不 `require "import"` 就没有 `import`/`loadlayout`,连 `LinearLayoutCompat` 都解析不到。
2. 界面用 `loadlayout`,列表用适配器;导航控件(底部栏/抽屉)加菜单用 `.Menu.add(...)`。
3. 弹窗用 `MaterialAlertDialogBuilder`;`toast`/`alert` 不存在。
4. 子线程做耗时,主线程更新 UI:`task(fn, callback)`。
5. 属性赋值自动补 `set`(`view.text = "x"` 等价 `setText`);方法无返回时链式返回自身。
6. 路径:相对项目写 `"main.lua"`;绝对路径直接写;`loadbitmap` 会自动补 `.png`。
