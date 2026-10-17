---
name: luaforge-studio
description: "编写与调试 LuaForge-Studio 的 Lua/.aly 项目,并通过 LuaForge-Studio 内置 MCP 服务(HTTP JSON-RPC, 默认端口 9123)驱动编辑器:读写源码、语法检查、编译验证、构建并安装 APK、调试运行并用控件树断言界面。核心原则:优先复用内置控件与现成 Java 能力(底部导航/标签页/抽屉/列表/播放器/工具类),禁止用 ImageView+LinearLayout 手画已有控件。关键词: LuaForge-Studio、Lua、ALY、loadlayout、import、luajava、global_utils、MCP、tools/call、run_project、check_screen、build_apk、check_syntax、debugger_status、debugger_outputs、BottomNavigationView、RecyclerView、ExoVideoView。"
---

# LuaForge-Studio 技能

面向 AI 的两部分能力:**1 写对 LuaForge 的 Lua/布局代码**;**2 用 MCP 工具去改、跑、验**。
本文所有结论均来自本仓库源码核实(文末列出处);未能核实的写法一律显式标注,请勿凭印象使用。

---

## 0. 先判断任务类型

| 用户目标 | 走哪条路 |
|---|---|
| **任何编码任务开始前** | **先 MCP `read_dev_doc`(或 `list_dev_docs` 看目录)——本文档 + `MCP.md` + `NETWORK.md` 就在 APK 内 `assets/devdoc/`** |
| 写/改功能代码(`main.lua`、`*.lua`) | §1 Lua 语法 → §3 改文件 → §3.3 验证闭环 |
| 写网络请求 | §1.9 网络访问(**优先 `Http.*`**) |
| 改界面(`layout.aly` / `layout.lua`) | **§0.5 控件复用铁律 → §1.5.1 控件速查** → §1.4 布局写法 → §3.3 验证闭环 |
| 需要某种 UI(底部导航/标签/抽屉/列表/卡片/播放器...) | **§0.5 + §1.5.1**,现成控件直接用,附 `WidgetsCatalog.md` 可复制配方 |
| 让项目跑起来看效果 | §3.3 `run_project` → `wait_for_text` → `check_screen` |
| 出安装包 | §3.3 `build_apk` → `install_apk` |
| 只想知道代码对不对 | §3.3 `check_syntax` + `compile_file`(只验证,不留产物) |
| 需要 JSON/正则/压缩/加密/文件遍历等**通用能力** | §1.10 内置原生库与可复用 Java 能力 —— **优先复用 `require` 现成库,别无脑用纯 Lua 重写** |

**铁律:凡"改了代码"的请求,必须用 `check_syntax` 或 `compile_file` 收尾;凡"改了界面/逻辑"的请求,必须 `run_project` + `check_screen` 收尾。** 不做验证等于没做完。

---

## 0.5 控件复用铁律(最重要,先读这条)

> **宁可多花十秒查控件,也不要手写一个假的。** 本项目内置了大量 Material / AndroidX 控件,
> 以及 IDE 随附的自有控件与 Java 工具类。**遇到下面这些需求,必须先查控件目录,直接复用:**
>
> | 需求 | ❌ 不要这么干 | ✅ 应该用 |
> |---|---|---|
> | 底部导航栏 | `ImageView` 画图标 + `LinearLayout` 手拼 | **`BottomNavigationView`** + `.Menu.add(...)` |
> | 顶部标签切换 | 一排 `Button` 自己写选中态 | **`TabLayout` + `ViewPager2` + `TabLayoutMediator`** |
> | 侧滑菜单 | 自己写位移动画 | **`DrawerLayout` + `NavigationView`** |
> | 顶部栏 / 折叠栏 | `LinearLayout` 塞标题 | **`MaterialToolbar` / `AppBarLayout` / `CollapsingToolbarLayout`** |
> | 列表 | `ScrollView` 里堆 `LinearLayout` | **`RecyclerView`**(+ `RecyclerAdapterUtil` 一行建适配器) |
> | 卡片 | `FrameLayout` 加背景色手画圆角 | **`MaterialCardView`** |
> | 文本输入 | 裸 `EditText` | **`MaterialTextField`**(自带 error/helper/圆角) |
> | 进度 | 自己画进度条 | **`LinearProgressIndicator` / `CircularProgressIndicator`** |
> | 浮动按钮 | `ImageView` 摆右下角 | **`FloatingActionButton`** |
> | 下拉刷新 / 芯片 / 滑块 | 手写 | **`SwipeRefreshLayout` / `ChipGroup` / `Slider`** |
> | 视频/音频播放 | `VideoView` 或将就 | **`ExoVideoView` / `SimpleVideoView` / `MusicPlayerBar` / `ExoAudioPlayer`** |
> | 图标 | 自己塞 PNG | **系统/主题资源** `androidx.R.drawable.abc_ic_search_api_material` 等 |
>
> **控件全清单与逐条可复制配方见 `app/src/main/assets/doc/WidgetsCatalog.md`**(底部导航/标签页/抽屉/工具栏/卡片/进度/FAB/输入/列表等
> 每种需求都有现成代码,直接抄)。**能用现成 Java 能力(网络 `Http`、图片 `GlideUtil`、JSON `cjson`、
> 压缩 `zip`、摘要 `crypt/md5`、文件遍历 `lfs`、列表 `RecyclerAdapterUtil`)就不要用纯 Lua 重写。**
>
> 判断标准:如果你正准备"用若干基础 View 拼出一个本来就有专用控件的界面",立即停下,去查目录。

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
  lib/            # 可选:分架构原生库,lib/<abi>/libxxx.so(见 §1.10)
  libs/           # 可选:三方 dex/jar 与扁平 so(见 §1.10)
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
- `global_utils` 是**要注入的 Java 工具类白名单**,可选值只有 6 个(`NewProjectScreen.kt:81-88`):
  `BitmapUtil`、`GlideUtil`、`OkHttpUtil`、`UiUtil`、`RecyclerAdapterUtil`、`ThemeUtil`。
  选中后由 `LuaFunctionRegistrar.registerSelectedFunctions()` 注入(`LuaActivity.java:1395-1398`),其函数直接成为**全局函数**。各工具类的函数清单见 `app/src/main/assets/doc/*.md`。
  > 例外:`OkHttpUtil` 的 `get`/`post`/`upload`/`download` 挂在全局表 `Https` 下(`Https.get` 等),反射注册路径跳过这四个方法(`skipReflectiveMethod`)。
  > **兼容别名**:为照顾表化前的旧写法,`registerHttpFunctions` 会额外把 `get`/`post`/`upload`/`download` 注册为裸全局函数 —— 但**仅当该全局名当前为空时**才注册,已被占用则不覆盖。因此新代码**一律写 `Https.*`**;裸名只作为旧项目的兜底,不保证存在。

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
- **内置控件**(随 IDE 发布,编译进 `core`,非三方库):`MaterialTextField`、`ExoVideoView`、`SimpleVideoView`、`MusicPlayerBar`、`ExoAudioPlayer`。直接写短名即可,属性名 = 去掉 `set` 前缀首字母小写。
  完整清单见 MCP `list_widgets`,或文档 `app/src/main/assets/doc/WidgetsCatalog.md`。

#### 1.5.1 控件速查:先查这里,再动手

**任何时候要写界面,先在下表找到对应控件;有专用控件就绝不手拼。**

| 需求 | 用哪个控件 | 详见 |
|---|---|---|
| 底部导航栏 | `BottomNavigationView` | `WidgetsCatalog.md` 配方 1 |
| 顶部标签页切换 | `TabLayout` + `ViewPager2` + `TabLayoutMediator` | 配方 2 |
| 侧滑抽屉菜单 | `DrawerLayout` + `NavigationView` | 配方 3 |
| 顶部栏 / 折叠标题栏 | `MaterialToolbar` / `AppBarLayout` / `CollapsingToolbarLayout` | 配方 4 |
| 卡片 | `MaterialCardView` | 配方 5 |
| 进度条 / 加载圈 | `LinearProgressIndicator` / `CircularProgressIndicator` | 配方 6 |
| 浮动按钮 | `FloatingActionButton` / `ExtendedFloatingActionButton` | 配方 7 |
| 文本输入 | `MaterialTextField`(或 `TextInputLayout`+`TextInputEditText`) | 配方 8 / `MaterialTextField.md` |
| 下拉刷新 + 列表 | `SwipeRefreshLayout` + `RecyclerView` | 配方 9 / `RecyclerAdapterUtil.md` |
| 芯片/标签选择 | `ChipGroup` + `Chip` | 配方 10 |
| 滑块 / 区间滑块 | `Slider` / `RangeSlider` | WidgetsCatalog §一 |
| 开关 / 复选 / 单选 | `MaterialSwitch` / `MaterialCheckBox` / `MaterialRadioButton` | WidgetsCatalog §一 |
| 视频播放 | `ExoVideoView`(自带控制器)/ `SimpleVideoView`(自定义控制器) | `ExoVideoView.md` / `SimpleVideoView.md` |
| 音乐播放条 | `MusicPlayerBar`(上/播/下/进度一体) | `MusicPlayerBar.md` |
| 纯音频 / 音效 | `ExoAudioPlayer` | `ExoAudioPlayer.md` |
| 列表适配器 | `RecyclerAdapterUtil`(一行建)或 `LuaAdapter` | `RecyclerAdapterUtil.md` / `RuntimeAPI.md` §六 |
| 网络请求 | `Http.*`(优先)/ `Https.*` | `RuntimeAPI.md` §四 / `OkHttpUtil.md` |
| JSON / 压缩 / 摘要 / 文件遍历 | `cjson` / `zip` / `crypt`+`md5` / `lfs` | §1.10 / `RuntimeAPI.md` §十 |
| 弹窗 | `MaterialAlertDialogBuilder` | `RuntimeAPI.md` §七 |
| 图片加载 | `loadbitmap` / `GlideUtil` | `RuntimeAPI.md` §五 / `GlideUtil.md` |

> **控件目录**(完整短名清单 + 每种 UI 的可复制配方)在 `app/src/main/assets/doc/WidgetsCatalog.md`;
> **运行时全局对象/函数/Java 类速查**在 `app/src/main/assets/doc/RuntimeAPI.md`。写代码前先翻这两份。
> 模板项目(`assets/templates/*.zip`:BottomNavigationView、TabLayout、DrawerLayout、FloatingActionButton、Default)就是现成范例,可对照。

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
| `Https` | **仅当 `global_utils` 含 `OkHttpUtil` 时注入**:`Https.get` / `Https.post` / `Https.upload` / `Https.download` 四个网络请求函数(注册处 `LuaFunctionRegistrar.registerHttpFunctions`,`LuaActivity.java:1395-1398`)。旧写法裸名 `get`/`post`/`upload`/`download` 仍会**在名字未被占用时**注册为兼容别名。见 §1.9 与 `assets/doc/OkHttpUtil.md` |
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

### 1.9 网络访问(优先用 `Http`)

**前提**:项目 `settings.json` 的 `user_permission` 必须包含 `INTERNET`(Default 模板已含)。
漏配时请求在网络层失败,回调里表现为 `code == -1`。

**首选 Java 内置的 `Http`**(`com.androlua.Http`,基于 `HttpURLConnection`,`core/src/main/java/com/androlua/Http.java`):

```lua
require "import"   -- 之后可直接用 Http(全局表按需解析 com.androlua. 前缀)

-- GET
Http.get("https://api.example.com/data", function(code, content, cookie, headers)
  if code == 200 then print(content) end
end)

-- POST:第二个参数可为 string,也可为 table(表单字段)
Http.post("https://api.example.com/login", {user = "a", pass = "b"},
  function(code, content, cookie, headers) end)
```

- `Http` 是**类**(不是全局实例),`require "import"` 后可直接写 `Http.get(...)`:
  在 `_G` 上访问 `Http` 会经元表(`import.lua` 的 `globalMT`)依次尝试 `''`、`java.lang.`、`java.util.`、`com.androlua.` 前缀,从而绑定到 `com.androlua.Http`。
  也可显式 `import "com.androlua.Http"` 或 `luajava.bindClass "com.androlua.Http"`。
  ⚠️ **不要写 `import "Http"`**:`import(package)` 不做前缀补全(只按原样 `bindClass` / 查包),短名 `Http` 会直接报 `cannot find Http`;短名前缀解析只发生在 `_G` 的元表访问路径上。
- 可用静态方法:`get` / `post` / `put` / `delete` / `download`。可选参数按位置追加:
  `url` → `cookie`(string) → `charset`(string) → `header`(table);POST 另有 `file`(table)重载做 `multipart/form-data` 上传。
- 回调统一为 `function(code, content, cookie, headers)`。**失败时只有两个参数** `(code, message)`,且 `code == -1`
  (网络异常 / 被网络拦截拒绝都是 `-1`)。`Http.download(url, savePath, callback)` 回调是 `(code, path, headers)`(三个参数)。
- 全局默认头:`Http.setHeader(table)`、`Http.setCookie(str)`、`Http.setUserAgent(str)`、`Http.setReferer(str)`。
- 回调在 `onPostExecute`(**主线程**)触发,可直接更新 UI。

**次选 `Https.*`**(OkHttp 实现):仅当 `settings.json` 的 `global_utils` 含 `OkHttpUtil` 时才有,
只提供 `Https.get` / `Https.post` / `Https.upload` / `Https.download`。需要 OkHttp 特性时再用,详见 §1.6 与 `assets/doc/OkHttpUtil.md`。
> ⚠️ 旧写法裸名 `get(...)` / `post(...)` 等**已不再是主路径**:它们仅作为兼容别名的兜底,且只有在该全局名未被占用时才注册。新代码请一律写 `Https.*`。

**通用注意**:两条通道在 **IDE 内运行**时都会被「网络请求拦截」拦下(见 `assets/devdoc/NETWORK.md`),
**打包成 APK 后不拦截**;开发期若请求静默返回 `-1`,先查拦截名单。

**网络代理**:设置 → 安全、网络与日志 → 网络代理,默认空白(直连)。配了代理后,**编辑器自身**的
`Https.*`(OkHttp)与 Maven 依赖下载会走代理;**局域网协同与打包后的 APK 不走代理**。
代理只影响 IDE 的出口,不会写进用户项目,`Http.*`(裸 `HttpURLConnection`)当前也不走代理 ——
详见 `assets/devdoc/NETWORK.md` 第 8 节。

### 1.10 内置原生库与可复用 Java 能力(优先复用,别无脑纯 Lua 重写)

**原则:能用内置原生库或现成 Java 类做到的,不要用纯 Lua 重写。** 例如 JSON 编解码用 `require "cjson"`(C 实现),别手写字符串解析;哈希/加密用 `crypt` / `md5` / `microaes`;压缩用 `zlib` / `zip`;正则用 `regex`;文件遍历用 `lfs`;网络用 §1.9 的 `Http`。

#### 随运行时内置的原生库(直接 `require`)

这些 `.so` 编进应用本体(`nativeLibraryDir`),Lua 侧直接 `require "模块名"` 即可,无需自己编译:

| `require` 名 | 能力 |
|---|---|
| `cjson` | JSON 编解码(C,极快):`cjson.encode` / `cjson.decode` |
| `json` | 另一套 JSON 绑定:`json.encode` / `json.decode` |
| `yyjson` | 高性能 JSON(解析 / 序列化 / 可变 DOM) |
| `bson` | BSON 编解码(MongoDB 格式) |
| `lfs` | 文件系统遍历 / 属性 / 目录操作 |
| `luv` | libuv 事件循环 / 异步 IO |
| `socket.core` | LuaSocket TCP/UDP;子模块 `socket.serial` / `socket.unix` / `mime.core`(⚠️ 该库**没有** `luaopen_socket`,写 `require "socket"` 会失败) |
| `xml.core` | LuaXML 解析;亦可用包装脚本 `require "xml"` |
| `zlib` | zlib 压缩 / 解压(`inflate` / `deflate`)与 `crc32` / `adler32` |
| `zip` | zip 归档读写 |
| `crypt` | 编解码 / 摘要:`base64` `hex` `sha1` `hmac` `des` `dh` 等 |
| `base64` | Base64 编解 |
| `md5` | MD5 摘要 |
| `microaes` | AES 全家桶(ecb / cbc / gcm / ctr / xts / siv 等) |
| `regex` | POSIX 正则:`find` / `match` / `gmatch` / `gsub` / `split` |
| `rawio` | 原始文件读写 |
| `time` | 计时 / 时间戳:`now` / `diff` / `start` / `elapsed` 等 |
| `unix` | POSIX 系统调用(另导出 `unix.unsafe` 命名空间) |
| `ffi` | LuaJIT 风格 ffi 子集(另导出 `cffi`) |
| `sensor` | 设备传感器 |
| `gl` | OpenGL 绑定(LuaGL) |
| `canvas` | Android Canvas 绑定 |
| `tcc` | 内嵌 TCC 编译器 |
| `decrypt` | 加密脚本加载(`loadfile` / `dofile` / `load`) |
| `luaparser` | Lua 源码解析(IDE 用) |

> ⚠️ `lsqlite3` 目录存在但**未接入顶层 `CMakeLists.txt`**(`add_and_optimize_library` 列表里没有),所以不随包构建,**别写 `require "lsqlite3"`**。
> 加载机制:全局 `package.cpath` 为 `nativeLibraryDir/lib?.so;libDir/lib?.so`(`LuaApplication.java:181`),`require "xxx"` 命中 `libxxx.so` 后调 `luaopen_xxx`;子模块(`a.b`)走 `searcher_Croot`,先加载 `liba.so` 再调 `luaopen_a_b`。

#### 项目自带的原生库与三方 dex

- **`lib/<abi>/libxxx.so`**:分架构原生库,只加载与设备首选 ABI(`Build.SUPPORTED_ABIS[0]`,如 `arm64-v8a` / `armeabi-v7a`)匹配的子目录;无该子目录时回退 `lib/*.so`(扁平)。加载后 `require "xxx"`(`LuaDexLoader.loadArchLibs`)。
  运行期按名加载(`activity.loadLib("xxx")` / `service.loadLib("xxx")`)同样按此约定查找:先 `lib/<abi>/libxxx.so`,再 `lib/libxxx.so`。
- **`libs/*.dex` / `libs/*.jar`**:三方 dex/jar,动态加载后其类可**按裸类名或全限定名**直接在 Lua 用(`LuaDexLoader.loadLibs`)。
- **`libs/*.so`**:兼容旧版的扁平原生库(不分架构)。
- 用 MCP `list_project_libraries` 可列出项目 `lib/<abi>/*.so`、`libs/*.dex|jar` 与设备首选 ABI。

#### 现成的 Java 能力(别用纯 Lua 重写)

- **网络**:`Http.get` / `Http.post` / `Http.put` / `Http.delete` / `Http.download`(`com.androlua.Http`,见 §1.9),比纯 Lua socket 省事得多。
- **`global_utils` 白名单**(项目 `settings.json`,见 §1.3):开启后其函数直接成为全局函数 —— 位图 `BitmapUtil`、图片加载 `GlideUtil`、网络 `OkHttpUtil`(→ `Https.*`)、UI `UiUtil`、列表 `RecyclerAdapterUtil`、主题 `ThemeUtil`。各函数清单见 `app/src/main/assets/doc/*.md`。

### 1.11 易错点

1. `main.lua` 与 `settings.json` **必须同时存在**才算合法项目(`LuaActivity.java:413` 按这两个文件识别项目)。
2. 不 `require "import"` 就没有 `import` / `loadlayout`,连 `LinearLayoutCompat` 都解析不到。
3. 尺寸:直接写数字是 **px**;带 `"100dp"` / `"20sp"` 走单位解析(`loadlayout.lua` 470-494);简写只认 §1.5 那几个词。
4. `onClick = "funcName"` 的字符串必须能在 `views` 或 `_G` 找到同名函数,否则**只在运行时**报错,静态看不出来。
5. 布局属性写错多数会 `error("Layout loading error: ... key='x'")` 打断加载,所以跑完必须读日志。
6. 调试用 `print` 即可,输出会落 `luaforge.log`,用 MCP `get_logs` / `get_runtime_errors` 读,不要只盯 logcat。

---

## 2. MCP 服务:连接与协议

### 2.1 启用

设置 → **MCP 服务** → 开启"启用 MCP 服务"。默认端口 `9123`(`McpManager.kt:128`、`SettingsManager.kt:282`),可改 1024–65535。开启"需要访问令牌"后请求须带 `Authorization: Bearer <令牌>`。

协议版本 `2024-11-05`(`McpServer.kt:395`)。

### 2.2 连接与自检

- 同设备:`http://127.0.0.1:9123/mcp`
- 局域网:`http://<设备IP>:9123/mcp`

```bash
# 状态
curl http://127.0.0.1:9123/
# 工具清单
curl -X POST http://127.0.0.1:9123/ -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
# 调工具
curl -X POST http://127.0.0.1:9123/ -H 'Content-Type: application/json' \
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

### 3.2 工具清单(55 个,`McpTools.kt` 实测)

**项目与文件**

| 工具 | 说明 | 关键参数 |
|---|---|---|
| `list_projects` | 列项目 | — |
| `list_files` | 列文件树 | `path`、`recursive`、`maxEntries` |
| `read_file` | 读文本;`raw=true` 直接返回**原文(不转义)**,`startLine`/`endLine` 按行读(1 起算闭区间) | `path` **必填**、`startLine`、`endLine`、`raw`、`withMd5` |
| `read_files` | 一次读多个文本文件,支持按行读与 MD5 | `paths` **必填**、`startLine`、`endLine`、`withMd5` |
| `write_file` | 写文本(自动建父目录),**同步刷新已打开标签**;`mode`=overwrite/replaceLines/append,`expectedMd5`/`expectedLines` 做写入前校验 | `path`、`content` **必填**、`mode`、`startLine`、`endLine`、`expectedMd5`、`expectedLines` |
| `create_file` | 新建(默认不覆盖) | `path` **必填**、`content`、`overwrite` |
| `delete_file` | 删文件或**空**目录 | `path` **必填** |
| `copy_file` | 复制文件/目录到**当前项目内**(跨项目复制) | `source` **必填**、`target`、`overwrite`、`recursive` |
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
| `list_widgets` | 列出随 IDE 发布的内置控件(含短名/分类/可写属性) | — |
| `list_project_libraries` | 列项目 `lib/<abi>/*.so`、`libs/*.dex\|jar`、`libs/*.so` 与设备首选 ABI | `path` |
| `list_lib_classes` | 列用户导入 `libs/*.dex\|jar` **内含的类**(可 `filter` 子串过滤、`className` 额外返回成员) | `path`、`lib`、`filter`、`className`、`page`、`maxResults` |
| `invoke_lib_method` | 反射调用项目 `libs/` 库内类的**静态方法**或**构造器**(仅标量参数) | `className` **必填**、`method`、`args`、`constructor`、`page`、`path`、`timeoutMs` |
| `list_dev_docs` | 列出随 IDE 打包的开发文档(`assets/devdoc/` + `assets/doc/`),含推荐阅读顺序 | — |
| `read_dev_doc` | **读开发文档(写代码前优先调用)**:SKILL/MCP/NETWORK/WidgetsCatalog/RuntimeAPI 及各控件文档;支持按行分段 | `name`、`startLine`、`endLine`、`maxChars` |

**编辑器**

`get_editor_state`、`open_file`(`path`、`line`)、`set_editor_content`(`content` **必填**)、`insert_text`(`text` **必填**)、`get_selection`、`goto_line`(`line` **必填**)、`refresh_editor`、`editor_history`(`action`= undo/redo)、`save_files`、`format_code`、`check_syntax`(`path`/`content`)、`get_settings`

**构建与运行**

`compile_file`(`path`、`keepOutput`)、`clean_compiled`、`build_apk`(`path`)、`run_project`(`path`、`waitMs`、`capture`、`stop`、`clearLogs`、`captureLines`、`returnToEditor`)、`stop_project`(`page`/`path`、`returnToEditor`)、`install_apk`(`path` **必填**)

**运行时界面检查**

`dump_screen`(`includeInvisible`、`maxDepth`)、`check_screen`(`expectTexts`、`expectAnyOf`、`absentTexts`、`minViews`)、`wait_for_text`(`text` **必填**、`absent`、`timeoutMs`、`intervalMs`)、`get_runtime_errors`(`lines`)、`clear_logs`、`get_logs`(`lines`)

**global_utils 查看与调用**

| 工具 | 说明 | 关键参数 |
|---|---|---|
| `list_global_utils` | 查看项目 `global_utils` 配置及这些工具类**运行时会实际注册**的 Lua 全局函数(参数、返回类型、context 注入、同名覆盖);顺带暴露 `unknown`(拼错的名字)与 `runningPages` | `path` |
| `call_global_util` | 在**运行中**的项目里调用 `global_utils` 注册的 Lua 全局函数(与项目自身调用同一路径;首个 `Context`/`Activity` 形参自动注入) | `name` **必填**、`args`、`page`、`path`、`timeoutMs` |

**调试浮窗(debugger.lua,只读 + 清空,与浮窗同源)**

| 工具 | 说明 | 关键参数 |
|---|---|---|
| `debugger_status` | 调试浮窗状态:当前运行页面 / 项目 / 是否注入浮窗 / 缓冲条数 | `page`、`path` |
| `debugger_outputs` | 读取浮窗缓冲条目(print / Lua 报错,按发生顺序) | `limit`、`keyword`、`page`、`path` |
| `debugger_clear` | 清空浮窗缓冲 | `page`、`path` |

> 调试运行(debugmode 项目)时 `assets/debugger.lua` 注入页面并接管全局 `print` / `onError`,
> 所有 print 与调试信息进入浮窗缓冲;这批工具读的是运行中 LuaState 暴露的只读全局 `__lfDebugger`(内存缓冲),
> 与浮窗展示同源,区别于 `get_logs` / `get_runtime_errors`(读 `luaforge.log` 文本尾部)。
> 非 debugmode 项目或用 `debugger_disable` 抑制的工具型启动不注入浮窗。

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

**一次性运行取信息(MCP 场景推荐)**

只需"跑一下、拿到输出、回到编辑界面"时,不必走上面的多步:

```text
run_project stop=true waitMs=2000 capture="debugger,errors" clearLogs=true
# → 启动后等 2s,抓取调试浮窗缓冲 + 日志错误,然后结束运行并退回编辑界面
# 返回: { started, entry, output:{debugger,errorCount,errors,...}, stopped, returnedToEditor }
stop_project                  # 若上一步没结束运行(如 stop=false),用这个收尾
```

- `capture` 取值:`debugger`(调试浮窗缓冲,需项目 debugmode=true)/ `errors`(日志错误行)/ `log`(日志尾部)。
- `stop=true` 时默认 `waitMs=1500`,让脚本有执行机会;要精确等待请显式传 `waitMs`。
- `stop_project` 也可单独用来关闭残留运行实例,`returnToEditor=true` 会回到编辑界面。

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

**局域网协同的共享范围更窄**:共享端只能共享**项目库根目录下的一级项目文件夹**
(`settings.projectStoragePath`,默认 `/storage/emulated/0/LuaForge-Studio/project/`),
项目库根目录本身、更深层的子目录以及库外的任意路径都会被拒绝。判定用
`canonicalPath`,因此 `..` 与软链接无法绕过;全量同步与增量同步也会校验远端路径。
连接端连上后按远端项目名自动下载到本地项目库,无需手动指定落地目录。

日志:`/storage/emulated/0/LuaForge-Studio/luaforge.log`。`get_logs` 读取上限 512KB,`read_file` 上限 2MB。
超过 2MB 的文件用 `read_file startLine/endLine` 按行读,并配合 `file_info` 的 `md5`/`lines` 做完整性校验;
写回用 `write_file mode=replaceLines startLine/endLine` + `expectedMd5`(或 `expectedLines`)防止并发覆盖。
需要文件**原文**(不要把 `\n` 当转义处理)时传 `read_file raw=true`。

### 3.5 已知限制

- 服务为**进程内实现,无前台 Service**;应用被杀即停。
- 编辑器操作需主线程;`open_file` 依赖 API 34+ 的编辑器实现。
- `check_screen` 只看当前前台 Activity;界面未起会返回"没有前台 Activity"。
- 单次 `dump_screen` 最多 3000 节点,超出截断并置 `truncated=true`。
- `resolveProjectPath`:未传 `path` 且未打开项目时,仅当项目根**只有一个子目录**才自动选中。
- `delete_file` 只能删空目录;递归删除需客户端先列目录再逐个删。
- `run_project stop=true` 结束运行依赖实例能从运行表中移除;若脚本卡死,`stop_project` 的 `timeoutMs` 到点仍会尝试回到编辑界面。
- `capture=debugger` 只对 debugmode 项目有效;非 debug 项目会在 `output.debuggerNote` 里说明未注入浮窗。
- 错误日志已可在 **设置 → 错误日志** 里查看/导出(记录开关默认开启);日志路径仍为 `luaforge.log`。

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
- 内置控件:`core/src/main/kotlin/com/luaforge/studio/widget/BuiltinWidgets.kt`(注册表)、`widget/textfield/MaterialTextField.kt`、`widget/video/{ExoVideoView,SimpleVideoView,MusicPlayerBar,ExoAudioPlayer}.kt`;模板 `app/src/main/assets/templates/{Default,BottomNavigationView,TabLayout,DrawerLayout,FloatingActionButton}.zip`
- 核心开发文档:`app/src/main/assets/devdoc/`(`SKILL.md` 本文档、`MCP.md`、`NETWORK.md`)—— 随 APK 打包,可用 MCP `read_dev_doc` 直接读取
- 控件与运行时文档:`app/src/main/assets/doc/`(`WidgetsCatalog.md`、`RuntimeAPI.md`、`MaterialTextField.md`、`ExoVideoView.md`、`SimpleVideoView.md`、`MusicPlayerBar.md`、`ExoAudioPlayer.md` 及各 `*Util.md`)
- 模板:`app/src/main/assets/templates/Default.zip`、`app/src/main/kotlin/com/luaforge/studio/ui/project/NewProjectScreen.kt`
- MCP:`app/src/main/kotlin/com/luaforge/studio/mcp/{McpServer,McpManager,McpTools}.kt`、`assets/devdoc/MCP.md`
- 路径与日志:`app/src/main/kotlin/com/luaforge/studio/utils/{FileUtil,LogConfig}.kt`

> 本文由源码逐条核实生成。凡"仓库内无用例""宏不自洽""死代码"等不确定项均已显式标注,请勿凭印象替换为更"标准"的说法。
