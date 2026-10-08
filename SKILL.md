---
name: luaforge-studio
description: "编写与调试 LuaForge-Studio 的 Lua/.aly 项目,并通过 LuaForge-Studio 内置 MCP 服务(HTTP JSON-RPC, 默认端口 8787)驱动编辑器:读写源码、语法检查、编译验证、构建并安装 APK、调试运行并用控件树断言界面。关键词: LuaForge-Studio、Lua、ALY、loadlayout、import、luajava、global_utils、MemUtil、MCP、tools/call、run_project、check_screen、build_apk、check_syntax、console_status、console_outputs。"
---

# LuaForge-Studio 技能

面向 AI 的两部分能力:**1 写对 LuaForge 的 Lua/布局代码**;**2 用 MCP 工具去改、跑、验**。
本文所有结论均来自本仓库源码核实(文末列出处);未能核实的写法一律显式标注,请勿凭印象使用。

---

## 0. 先判断任务类型

| 用户目标 | 走哪条路 |
|---|---|
| 写/改功能代码(`main.lua`、`*.lua`) | §1 Lua 语法 → §3 改文件 → §3.3 验证闭环 |
| 改界面(`layout.aly` / `layout.lua`) | §1.4 布局写法 → §3.3 验证闭环 |
| 让项目跑起来看效果 | §3.3 `run_project` → `wait_for_text` → `check_screen` |
| 出安装包 | §3.3 `build_apk` → `install_apk` |
| 只想知道代码对不对 | §3.3 `check_syntax` + `compile_file`(只验证,不留产物) |

**铁律:凡"改了代码"的请求,必须用 `check_syntax` 或 `compile_file` 收尾;凡"改了界面/逻辑"的请求,必须 `run_project` + `check_screen` 收尾。** 不做验证等于没做完。

---

## 1. Lua 语法与运行时

### 1.1 运行时版本

- 运行时是**编译进 `libluajava.so` 的 C 版 Lua**(JNI 层 `app/src/main/jni/luajava/`,链接 `lua` 静态库;Java 侧 `System.loadLibrary("luajava")`,`core/src/main/java/com/luajava/LuaState.java:112`)。
- ⚠️ **版本宏本身不自洽,不要在文档/注释里断言"Lua 5.5"**。`app/src/main/jni/lua/lua.h:19-24` 同时写着:
  - `LUA_VERSION_MAJOR "5"` / `LUA_VERSION_MINOR "5"` / `LUA_VERSION_RELEASE "0"`
  - 但 `LUA_VERSION_NUM 504`,`LUA_VERSION_RELEASE_NUM (LUA_VERSION_NUM * 100 + 8)`
  代码基线是 **Lua 5.4 系列**(lparser/lcode 结构与 5.4 一致,含 `lua_newuserdatauv`、`lua_resetthread` 等 5.4 专有 API),在此之上做了大量本仓库自定义语法扩展(§1.2)。
- 预编译块签名被改成本项目专用:`LUA_SIGNATURE = "LuaForge-Studio\n"`(`lua.h:33`)。**标准 `luac` 的产物与它不兼容**,不要外部编译后塞进来。

### 1.2 相对标准 Lua 的语法扩展(重要)

这些**不是高亮关键字,而是 C 解析器实现的可编译语法**(`app/src/main/jni/lua/lparser.c`):

| 语法 | 形式 | 实现处 |
|---|---|---|
| `try/catch/finally` | `try ... catch(e) ... finally ... end` | `lparser.c:3013`,`trystat` |
| `defer` | `defer <block> end` | `lparser.c:3017`,`deferstat`(2861) |
| `switch/case/default` | `switch v do case 1 do ... end default ... end end`;分隔符可为 `do`/`then`/`:`/`{` | `lparser.c:2927`,`switchstat`(2354) |
| `when/case/else` | `when cond then ... case cond then ... else ... end`(类 if-elseif) | `lparser.c:2250`,`whenstat` |
| `continue` | 同 `break` 用法 | `lparser.c:3000` |
| `lambda` | `lambda(a,b) -> expr`、`lambda() => expr`、`lambda() -> do ... end`(表达式体/块体两种) | `lparser.c:1378`,`lambda_body`(1139) |
| 可选访问 `?.` | `obj?.field`、`obj?.[k]` | `lparser.c:1306` |
| 空合并 `??` | `a ?? b` | `lparser.c:1452`(`OPR_NULLCOAL`) |
| 管道 `\|>` | `x \|> f` | `lparser.c:1454`(`OPR_PIPE`) |
| 复合赋值 | `+= -= *= /= %= ^= ..= &= \|= <<= >>= //=`(**无** `&&=`/`\|\|=`) | `lparser.c:1661+` |

**使用建议**:除 `try/catch`(仓库内 `app/src/main/assets/layouthelper/main.lua:41-47` 有真实用例)外,其余扩展在仓库 `.lua/.aly` 中**均无用例**,属于"解析器支持但生态未使用"。给用户写业务代码时**优先用标准 Lua 写法**(`if/elseif`、`pcall`、`local`),除非用户明确要求用扩展语法。`let` 出现在词法表里但解析器无实现,`global` 受 `LUA_COMPAT_GLOBAL` 开关控制且该宏未在本仓库启用——两者都**不要用**。

### 1.3 项目结构与 settings.json

新建项目来自模板 zip(`app/src/main/assets/templates/Default.zip`),解包后:

```
<project>/
  main.lua        # 入口,必须存在
  layout.aly      # 布局(纯 Lua 表字面量)
  settings.json   # 应用配置
  Preview.png     # 模板预览图(可删)
```

`settings.json`(Default 模板实测):

```json
{
  "versionName": "1.0",
  "versionCode": "1",
  "uses_sdk": { "minSdkVersion": "23", "targetSdkVersion": "36" },
  "package": "PackageName",
  "application": { "label": "AppName", "debugmode": true },
  "user_permission": ["WRITE_EXTERNAL_STORAGE", "READ_EXTERNAL_STORAGE", "INTERNET"],
  "global_utils": []
}
```

- `package` 是 APK 包名占位符;`application.label` 是应用名,新建项目时由工具替换。
- `application.debugmode` 由 `LuaActivity` 读取(`LuaActivity.java:1387`)。
- `global_utils` 是**要注入的 Java 工具类白名单**,可选值只有 7 个(`NewProjectScreen.kt:81-88`):
  `BitmapUtil`、`GlideUtil`、`OkHttpUtil`、`UiUtil`、`RecyclerAdapterUtil`、`ThemeUtil`、`MemUtil`。
  选中后由 `LuaFunctionRegistrar.registerSelectedFunctions()` 注入(`LuaActivity.java:1395-1398`),其函数直接成为**全局函数**。各工具类的函数清单见 `app/src/main/assets/doc/*.md`。

### 1.4 入口与生命周期回调

`LuaActivity.onCreate` 会 `doFile(main.lua)`(`LuaActivity.java:274`),再按名调用下列**Lua 全局函数**(存在才调用,引自 `LuaActivity.java:274-944、1702`):

- 页面:`runFunc(pageName, arg)`、`runFunc("main", arg)`、`onCreate(savedInstanceState)`
- 生命周期:`onStart` `onResume` `onPause` `onStop` `onDestroy` `onRestart`
- 状态:`onSaveInstanceState(outState)` `onRestoreInstanceState(bundle)`
- 交互:`onKeyDown` `onKeyUp` `onKeyLongPress` `onKeyShortcut` `onTouchEvent` `onBackPressed`(**Lua 返回 `true` 可拦截**)、`onActivityReenter`、`onUserLeaveHint`、`onNightModeChanged(mode)`
- 菜单:`onCreateOptionsMenu(menu)` `onOptionsItemSelected(item)` `onCreateContextMenu` `onContextItemSelected`
- 结果:`onActivityResult(requestCode, resultCode, data)` `onResult(...)` `onRequestPermissionsResult(...)`
- 广播/服务:`onReceive(context, intent)`(622)、`onServiceConnected` `onServiceDisconnected`(938-944)
- 其他:`onConfigurationChanged(newConfig)`(898);无障碍 `onAccessibilityEvent`(359);错误 `onError(title, msg)`(1702)

### 1.5 布局(layout)写法

布局就是 **Lua 表字面量**:第一个元素是控件类名,其余键值对是属性,数字下标的元素是子控件。

```lua
{
  LinearLayoutCompat,
  orientation = "vertical",
  layout_width = "fill",
  layout_height = "fill",
  gravity = "center",
  {
    MaterialTextView,
    id = "title",
    text = "Hello",
    textSize = "20sp",
    layout_width = "wrap",
    layout_height = "wrap",
  },
}
```

要点(全部来自 `core/src/main/resources/lua/loadlayout.lua`):

- **尺寸简写**:`match` / `match_parent` / `fill` / `fill_parent` = `-1`;`wrap` / `wrap_content` = `-2`(`sizeConstants` 表 233-240)。
- **属性别名**:可直接写 `width` / `height` / `margin` / `marginTop` / `marginStart` 等,内部归一化为 `layout_*`(别名表 `attributeNormalization` 124-137,`normalizeAttributes` 141)。不归一则属性会落到兜底分支,由 luajava 抛 `... is not a field` 导致整份布局加载失败。
- **百分比尺寸**:`"%w"` / `"%h"` 表示屏宽/屏高的百分比(`dimensionConverterMap` 424-425)。**仓库内无实际用例**,仅转换表支持;用前先跑一次验证。
- **事件**:`onClick` / `onLongClick` 的值可以是**函数**,也可以是**字符串**;字符串会解析为 `(views[value] or _G[value])(v)`(`loadlayout2.lua:666-675`),即指向同名全局函数或同 `id` 的控件。
- **`id` 的作用**:带 `id = "x"` 的控件写入 `views` 表,而 `views` 默认为 `_G`(1094 行 `views[id] = view`;主函数 1162 行 `views = views or _G`)。**所以 `id` 写完即成全局变量**,`loadlayout` 之后可直接 `title.setText("x")`。若显式传第二参数则收集到该表:
  `local ids = {}; local v = loadlayout("layout", ids)` —— 此时控件进 `ids` 而非 `_G`。
- **延迟属性**:ConstraintLayout 约束与 RelativeLayout 规则延后到子控件都建好后统一应用(`loadlayout.lua:1201-1213`)。
- **`.aly` 可被 `require`**:`loadlayout.lua` 往 `package.searchers` 注入 `alyloader`(430-447),识别 `\27Lua` 预编译签名,否则按 `return <内容>` 当表达式加载。
- **两套实现**:`core/src/main/resources/lua/loadlayout.lua`(运行时,1223 行)与 `app/src/main/assets/layouthelper/loadlayout2.lua`(布局助手用,1262 行,支持 `pagesWithTitle` 等扩展)。语法一致,写项目用前者。

### 1.6 全局对象与函数

`initLua()`(`LuaActivity.java:1259-1366`)注入:

| 名字 | 内容 |
|---|---|
| `activity` | 当前 `LuaActivity` 实例 |
| `this` | 同 `activity`(别名) |
| `R` | `com.luaforge.studio.core.R` |
| `android.R` | 框架资源,写法 `android.R.id.home` |
| `androidx.R` | AppCompat 资源 |
| `material.R` | Material 组件资源 |
| `luajava` | 桥接库;额外带 `luadir` / `luapath` / `luaextdir` 三个路径字段 |
| `print` | 定制打印,`print.register("print")` 注册(`LuaActivity.java:1297`),经 `sendMsg` → `RuntimeLog.logLua` 落进 `luaforge.log`(`LuaActivity.java:1687-1696`) |
| `set(name, v)` / `call(name, ...)` | 跨线程设值/调用 |

`require "import"` 之后(全部来自 `core/src/main/resources/lua/import.lua`):

- `import "android.widget.Button"`、`import "android.view.*"` —— 支持通配;依次尝试 `''`、`java.lang.`、`java.util.`、`com.androlua.` 前缀(148-151)。
- 未 import 的类名也能按需解析(全局表 `__index`,`import.lua:174` `globalMT`)。
- 工具函数(`_M`,注入 `_G` 与 `import` 环境):
  `dump(o)`(241)、`each(o)`(Java 迭代器→Lua,229)、`enum(e)`(221)、`printstack()`(301)、`compile(name)`(216)、**`task(src, callback)`(459,异步任务)**、`thread(src, ...)`(443)、`timer(f, d, p, ...)`(468)。
  > `getids()`(388)读 `luajava.ids`,但**全仓库无赋值点**,实际返回 nil —— 属死代码,别用。
- 同一步还注入 `loadlayout`、`loadbitmap`、`loadmenu`(`import.lua:209-211`)。

> ⚠️ `toast` / `alert` / `dialog` / `sleep` 这类简写**在本仓库没有任何注册证据**,不要写。要弹窗请直接建 `MaterialAlertDialogBuilder`(见下节与 `layouthelper/main.lua:145-151`)。

### 1.7 Java 互操作

```lua
require "import"
import "android.widget.Button"
import "com.google.android.material.dialog.MaterialAlertDialogBuilder"

-- 方式一:import 后直接用类名
local btn = Button(activity)

-- 方式二:显式绑定(推荐,拿到真实 Class 对象)
local MaterialAlertDialogBuilder =
  luajava.bindClass "com.google.android.material.dialog.MaterialAlertDialogBuilder"

MaterialAlertDialogBuilder(activity)
  .setTitle("标题")
  .setMessage("内容")
  .setPositiveButton("确定", nil)
  .show()
```

`luajava` 暴露的全部函数(`app/src/main/jni/luajava/luajava.c:1540-1553`):
`bindClass`、`new`、`newInstance`、`loadLib`、`createProxy`、`newArray`、`createArray`、`astable`、`tostring`、`coding`、`clear`、`instanceof`、`getContext`、`override`。

两条容易说错的机制,以源码为准:

1. **链式调用为什么成立**:JNI 层规定"方法无返回值时返回调用者自身"——`luajava.c:656-660` 注释 `/* if no ret, return self */`。所以 `void` 方法(如 `setTitle`)也能继续 `.` 下去。
2. **属性赋值会自动补 `set` 前缀**:`view.text = "x"` 走 `javaSetter` → `javaSetMethod`,内部 `methodName = Character.toUpperCase(首字母)` 后取 `"set" + methodName`(`LuaJavaAPI.java:1651-1657`)。**因此 `view.text = "x"` 等价于 `view.setText("x")`,不是"省略前缀"这么简单,而是有专门的 setter 解析路径**。
   读属性同理走 `javaGetter`,自动尝试 `getXxx` / `isXxx`(`LuaJavaAPI.java:1479-1530`),所以 `item.ItemId` 与 `item.getItemId()` 都可用。
   若名字既非字段也无对应 setter,报 `xxx is not a field`(`luajava.c:753`)。

### 1.8 可运行的最小项目

`main.lua`(**Default 模板实测内容**):

```lua
require "import"
import "android.app.*"
import "android.os.*"
import "android.widget.*"
import "android.view.*"
import "androidx.activity.EdgeToEdge"
import "androidx.appcompat.widget.LinearLayoutCompat"

EdgeToEdge.enable(activity)

activity
.setTheme(R.style.Theme_Material3_Blue_NoActionBar)
.setTitle("AppName")
.setContentView(loadlayout("layout"))
```

`layout.aly`:

```lua
{
  LinearLayoutCompat,
  orientation = "vertical",
  layout_width = "fill",
  layout_height = "fill",
  gravity = "center",
}
```

### 1.9 易错点

1. `main.lua` 与 `settings.json` **必须同时存在**才算合法项目(`LuaActivity.java:413` 按这两个文件识别项目)。
2. 不 `require "import"` 就没有 `import` / `loadlayout`,连 `LinearLayoutCompat` 都解析不到。
3. 尺寸:直接写数字是 **px**;带 `"100dp"` / `"20sp"` 走单位解析(`loadlayout.lua` 470-494);简写只认 §1.5 那几个词。
4. `onClick = "funcName"` 的字符串必须能在 `views` 或 `_G` 找到同名函数,否则**只在运行时**报错,静态看不出来。
5. 布局属性写错多数会 `error("Layout loading error: ... key='x'")` 打断加载,所以跑完必须读日志。
6. 调试用 `print` 即可,输出会落 `luaforge.log`,用 MCP `get_logs` / `get_runtime_errors` 读,不要只盯 logcat。

---

## 2. MCP 服务:连接与协议

### 2.1 启用

设置 → **MCP 服务** → 开启"启用 MCP 服务"。默认端口 `8787`(`McpManager.kt:128`、`SettingsManager.kt:266`),可改 1024–65535。开启"需要访问令牌"后请求须带 `Authorization: Bearer <令牌>`。

协议版本 `2024-11-05`(`McpServer.kt:395`)。

### 2.2 连接与自检

- 同设备:`http://127.0.0.1:8787/mcp`
- 局域网:`http://<设备IP>:8787/mcp`

```bash
# 状态
curl http://127.0.0.1:8787/
# 工具清单
curl -X POST http://127.0.0.1:8787/ -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
# 调工具
curl -X POST http://127.0.0.1:8787/ -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_projects","arguments":{}}}'
```

方法:`initialize`、`ping`、`tools/list`、`tools/call`、`resources/list`(返回空数组)、`prompts/list`(返回空数组)。通知(无 `id`)返回 HTTP 202。

---

## 3. MCP 调用技能

### 3.1 调用约定

- 参数**全部可选**,除清单标 **必填** 的。
- 数组类参数(`paths`、`expectTexts` 等)可传 JSON 数组,也可传**换行/逗号分隔**的字符串。
- 路径可为项目相对路径,或允许范围内的绝对路径。
- 不传 `path` 时多数工具回退到"当前打开的项目 / 当前活动文件"。

### 3.2 工具清单(50 个,`McpTools.kt` + `ConsoleTools.kt` 实测)

**项目与文件**

| 工具 | 说明 | 关键参数 |
|---|---|---|
| `list_projects` | 列项目 | — |
| `list_files` | 列文件树 | `path`、`recursive`、`maxEntries` |
| `read_file` / `read_files` | 读文本 | `path` **必填** / `paths` **必填** |
| `write_file` | 覆盖写(自动建父目录),**同步刷新已打开标签** | `path`、`content` **必填** |
| `create_file` | 新建(默认不覆盖) | `path` **必填**、`content`、`overwrite` |
| `delete_file` | 删文件或**空**目录 | `path` **必填** |
| `rename_file` | 重命名/移动 | `from`、`to` **必填**、`overwrite` |
| `make_directory` | 递归建目录 | `path` **必填** |
| `file_info` | 大小/时间/行数/MD5 | `path` **必填** |
| `search_in_files` | 文本或正则搜索 | `path`、`query` **必填**、`regex`、`ignoreCase`、`maxResults` |
| `replace_in_file` | 单文件替换 | `path`、`find`、`replace` **必填**、`regex`、`ignoreCase`、`expectCount` |
| `replace_in_files` | 批量替换(可 `dryRun`) | `path`、`find`、`replace` **必填**、`dryRun` ... |
| `get_project_info` | 项目信息 | `path` |
| `backup_project` / `restore_backup` | 备份 / 还原 | `backupPath` **必填**(还原) |
| `list_templates` / `create_project` | 模板 / 建项目 | `name`、`packageName`、`template`、`debugMode`、`globalUtils` |
| `analyze_imports` | 分析所需 import | `content` |

**编辑器**

`get_editor_state`、`open_file`(`path`、`line`)、`set_editor_content`(`content` **必填**)、`insert_text`(`text` **必填**)、`get_selection`、`goto_line`(`line` **必填**)、`refresh_editor`、`editor_history`(`action`= undo/redo)、`save_files`、`format_code`、`check_syntax`(`path`/`content`)、`get_settings`

**构建与运行**

`compile_file`(`path`、`keepOutput`)、`clean_compiled`、`build_apk`(`path`)、`run_project`、`install_apk`(`path` **必填**)

**运行时界面检查**

`dump_screen`(`includeInvisible`、`maxDepth`)、`check_screen`(`expectTexts`、`expectAnyOf`、`absentTexts`、`minViews`)、`wait_for_text`(`text` **必填**、`absent`、`timeoutMs`、`intervalMs`)、`get_runtime_errors`(`lines`)、`clear_logs`、`get_logs`(`lines`)

**global_utils 查看与调用**

| 工具 | 说明 | 关键参数 |
|---|---|---|
| `list_global_utils` | 查看项目 `global_utils` 配置及这些工具类**运行时会实际注册**的 Lua 全局函数(参数、返回类型、context 注入、同名覆盖);顺带暴露 `unknown`(拼错的名字)与 `runningPages` | `path` |
| `call_global_util` | 在**运行中**的项目里调用 `global_utils` 注册的 Lua 全局函数(与项目自身调用同一路径;首个 `Context`/`Activity` 形参自动注入) | `name` **必填**、`args`、`page`、`path`、`timeoutMs` |

**调试控制台(只读 + 清空,与浮窗同源)**

| 工具 | 说明 | 关键参数 |
|---|---|---|
| `console_status` | 控制台状态:会话(项目/文件/调试模式/已运行时长)、面板(浮球/浮窗/状态机)、当前文件与布局、捕获开关、缓冲与错误计数 | — |
| `console_outputs` | 控制台「输出」缓冲:print / Lua 报错 / Toast / Snackbar(含逐参 Lua 类型与真实类型) | `file`、`label`、`limit`、`includeTypes` |
| `console_events` | 控制台事件条目:Lua 侧实际被调用的函数及参数摘要 | `limit`、`func` |
| `console_modules` | 控制台「环境」:require 模块 / bindClass 类 / `libs/*.dex` 方法签名 | `file` |
| `console_logcat` | 本调试会话 logcat(与控制台「Logcat」页同源) | `lines`、`level` |
| `console_clear` | 清空控制台输出缓冲(不动 `luaforge.log`) | `scope` |

> `console_*` 仅在**调试运行(debugmode 项目)**时产生数据;与既有 `get_logs` / `get_runtime_errors`(读 `luaforge.log` 文本)不同,这批工具读的是控制台的**结构化缓冲**,与浮窗展示同源。

### 3.3 标准工作流

**改代码并验证**

```text
write_file path=".../main.lua" content="..."
refresh_editor                 # 让编辑器界面反映磁盘新内容
check_syntax                   # 语法自检
compile_file keepOutput=false  # 编译验证;成功后自动删 .luac/.alyc
```

**改界面并验证(必做)**

```text
clear_logs                     # 1. 干净现场
run_project                    # 2. 启动调试运行
wait_for_text text="Hello"     # 3. 等界面就绪(别用固定 sleep)
dump_screen                    # 4. 看控件树(可选)
check_screen expectTexts=["Hello"] minViews=3   # 5. 断言
get_runtime_errors             # 6. 失败时读 Lua 报错
```

`check_screen` 的文本匹配是**子串**匹配:`expectTexts` 需全部出现,`expectAnyOf` 命中其一,`absentTexts` 必须都不出现。未通过时返回 `{"passed": false, "isError": true, "failures": [...], "detectedErrorMarkers": [...]}`。`detectedErrorMarkers` 会自动识别 `runtime error`、`is not a field`、`layout loading error`、`traceback` 等错误弹窗特征——**这是判断"代码真跑起来了"的关键信号**。

**出包**

```text
build_apk                     # 构建并签名,返回输出路径
install_apk path="<上一步输出>"
```

### 3.4 路径与安全边界

MCP 只允许访问这些根(`McpTools.kt:1429-1443`):

- 可配置的项目根:`settings.projectStoragePath`;未配置时 `getExternalFilesDir(null)/projects`(`FileUtil.kt:12-18`)
- `/storage/emulated/0/LuaForge-Studio`(含日志)
- 其下 `build`、`backup`

路径经 `canonicalPath` 归一化后做前缀匹配,空值与文件系统根 `/` 被过滤。

日志:`/storage/emulated/0/LuaForge-Studio/luaforge.log`。`get_logs` 读取上限 512KB,`read_file` 上限 2MB。

### 3.5 已知限制

- 服务为**进程内实现,无前台 Service**;应用被杀即停。
- 编辑器操作需主线程;`open_file` 依赖 API 34+ 的编辑器实现。
- `check_screen` 只看当前前台 Activity;界面未起会返回"没有前台 Activity"。
- 单次 `dump_screen` 最多 3000 节点,超出截断并置 `truncated=true`。
- `resolveProjectPath`:未传 `path` 且未打开项目时,仅当项目根**只有一个子目录**才自动选中。
- `delete_file` 只能删空目录;递归删除需客户端先列目录再逐个删。

---

## 4. 端到端:让 AI 改一个 Lua 项目

1. `list_projects` 确认目标;`list_files` 看结构,确认存在 `main.lua` + `settings.json`。
2. `read_file` 读 `main.lua` 与 `layout.aly`,**先看懂再改**。
3. 用 `write_file` / `replace_in_file` 改(单点改动用 `replace_in_file` + `expectCount=1` 更安全)。
4. `check_syntax` → `compile_file keepOutput=false`。
5. `clear_logs` → `run_project` → `wait_for_text` → `check_screen`。
6. 失败读 `get_runtime_errors`。常见: `is not a field`(属性/方法名错)、`layout loading error`(布局属性错)、`attempt to index a nil value`(忘 `import` 或 `id` 不存在)。
7. 需要出包再 `build_apk`。

**`check_syntax` 通过 ≠ 跑得起来**:布局事件字符串、缺失的 `import`、拼错的 `id` 都只在运行时暴露,所以第 5 步不能省。

---

## 5. 事实来源

- Lua 运行时与回调:`core/src/main/java/com/androlua/LuaActivity.java`(`initLua` 1259-1366、回调 274-944)、`app/src/main/jni/lua/lua.h`(版本宏/签名)、`core/src/main/java/com/luajava/LuaState.java`
- 语言扩展:`app/src/main/jni/lua/llex.h`(保留字枚举)、`llex.c`(字面量)、`lparser.c`(try/switch/when/defer/continue/lambda/`?.`/`??`/`|>`/复合赋值)
- Lua 库:`core/src/main/resources/lua/{import,loadlayout,loadmenu,loadbitmap,xml,Colors}.lua`、`app/src/main/assets/layouthelper/loadlayout2.lua`
- Java 桥接:`app/src/main/jni/luajava/luajava.c`(void 返回 self、`ljlib` 函数表)、`core/src/main/java/com/luajava/LuaJavaAPI.java`(setter/getter 解析)
- 模板:`app/src/main/assets/templates/Default.zip`、`app/src/main/kotlin/com/luaforge/studio/ui/project/NewProjectScreen.kt`
- MCP:`app/src/main/kotlin/com/luaforge/studio/mcp/{McpServer,McpManager,McpTools}.kt`、`docs/MCP.md`
- 路径与日志:`app/src/main/kotlin/com/luaforge/studio/utils/{FileUtil,LogConfig}.kt`

> 本文由源码逐条核实生成。凡"仓库内无用例""宏不自洽""死代码"等不确定项均已显式标注,请勿凭印象替换为更"标准"的说法。
