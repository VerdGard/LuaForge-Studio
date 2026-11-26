---
name: luaforge-studio
description: "编写与调试 LuaForge-Studio 的 Lua/.aly 项目,并通过内置 MCP 服务(HTTP JSON-RPC, 默认端口 9123)驱动编辑器:读写源码、语法检查、编译验证、构建并安装 APK、调试运行并用控件树与截屏断言界面。核心原则:优先复用内置控件与现成 Java 能力(底部导航/标签页/抽屉/列表/输入框/工具类),禁止用 ImageView+LinearLayout 手画已有控件。关键词: LuaForge-Studio、Lua、ALY、loadlayout、import、luajava、global_utils、MCP、tools/call、run_project、check_screen、build_apk、check_syntax、debugger_status、debugger_outputs、LuaDrawable、canvas、BottomNavigationView、RecyclerView、MaterialTextField。"
---

# LuaForge-Studio 技能

面向 AI 的两部分能力:**1) 写对 LuaForge 的 Lua/布局代码**;**2) 用 MCP 工具去改、跑、验**。

**阅读顺序**:本文 → `MCP.md`(工具参数细节)→ `WidgetsCatalog.md`(控件配方)→
`RuntimeAPI.md`(全局对象/函数)→ `LuaDraw.md`(自绘)→ `NETWORK.md`(网络)。

本文每条结论都标注了源码出处;未能核实的写法一律显式标注"未实测",不要凭印象使用。

---

## 0. 先判断任务类型

| 用户目标 | 走哪条路 |
|---|---|
| **任何编码任务开始前** | 先 MCP `read_dev_doc`(或 `list_dev_docs` 看目录);文档就在 APK 内 `assets/devdoc/` + `assets/doc/` |
| 写/改功能代码(`main.lua`、`*.lua`) | §1 语法 → §3 改文件 → §3.3 验证闭环 |
| 写网络请求 | §1.9(**优先 `Http.*`**) |
| 改界面(`layout.aly`) | §0.5 控件复用铁律 → §1.5 布局写法 → §3.3 验证闭环 |
| 需要某种 UI(底部导航/标签/抽屉/列表/卡片/输入框) | §0.5 + `WidgetsCatalog.md` 配方 |
| 需要自己绘制(图表/圆环/自定义 View) | **`LuaDraw.md`** 全文 |
| 让项目跑起来看效果 | §3.3 `run_project` → `wait_for_text` → `check_screen` / `capture_screen` |
| 出安装包 | §3.3 `build_apk` → `install_apk` |
| 只想知道代码对不对 | `check_syntax` + `compile_file`(只验证,不留产物) |
| 需要 JSON/正则/压缩/加密/文件遍历/哈希 | §1.10 内置原生库 —— **优先 `require` 现成库,别无脑纯 Lua 重写** |
| 权限/网络受限排查 | `NETWORK.md` |

**铁律**:

- 凡"改了代码"的请求,必须用 `check_syntax` 或 `compile_file` 收尾;
- 凡"改了界面/逻辑"的请求,必须 `run_project` + `check_screen` 收尾;
- 凡"自绘内容"的改动,必须 `capture_screen` —— 控件树证明不了像素。

不做验证等于没做完。

---

## 0.5 控件复用铁律(最重要,先读这条)

> **宁可多花十秒查控件,也不要手写一个假的。**

| 需求 | ❌ 不要这么干 | ✅ 应该用 |
|---|---|---|
| 底部导航栏 | `ImageView` + `LinearLayout` 手拼 | **`BottomNavigationView`** + `.Menu.add(...)` |
| 顶部标签切换 | 一排 `Button` 自己写选中态 | **`TabLayout` + `ViewPager2` + `TabLayoutMediator`** |
| 侧滑菜单 | 自己写位移动画 | **`DrawerLayout` + `NavigationView`** |
| 顶部栏 / 折叠栏 | `LinearLayout` 塞标题 | **`MaterialToolbar` / `AppBarLayout` / `CollapsingToolbarLayout`** |
| 列表 | `ScrollView` 里堆 `LinearLayout` | **`RecyclerView`**(+ `RecyclerAdapterUtil` 一行建适配器)或 `ListView` + `LuaAdapter` |
| 卡片 | `FrameLayout` 加背景手画圆角 | **`MaterialCardView`** |
| 文本输入 | 裸 `EditText` | **`MaterialTextField`**(自带 error/helper/圆角) |
| 进度 | 自己画进度条 | **`LinearProgressIndicator` / `CircularProgressIndicator`** |
| 浮动按钮 | `ImageView` 摆右下角 | **`FloatingActionButton`** |
| 下拉刷新 / 芯片 / 滑块 | 手写 | **`SwipeRefreshLayout` / `ChipGroup` / `Slider`** |
| 圆角/纯色背景 | 自绘 Drawable | **`GradientDrawable`**(`setCornerRadius` + `setColor`) |
| 图标 | 自己塞 PNG | 主题资源 `androidx.R.drawable.abc_ic_search_api_material` 等 |
| 弹窗 | 自己搭 Dialog 布局 | **`MaterialAlertDialogBuilder`** |

**只有静态能力做不出的东西才自绘**,例如随数据变化的图表、进度环、签名板 ——
那才用 `LuaDrawable`(见 `LuaDraw.md`)。

判断标准:如果你正准备"用若干基础 View 拼出一个本来就有专用控件的界面",立即停下,去查目录。

---

## 1. Lua 语法与运行时

### 1.1 运行时版本

- 运行时是**编译进 `libluajava.so` 的 C 版 Lua**(JNI `app/src/main/jni/luajava/`,
  链接 `lua` 静态库;Java 侧 `System.loadLibrary("luajava")`)。
- ⚠️ **版本宏不自洽,不要在文档/注释里断言具体版本号**。`app/src/main/jni/lua/lua.h:19-24` 同时写着
  `LUA_VERSION_MAJOR "5"` / `MINOR "5"` / `RELEASE "0"`,但 `LUA_VERSION_NUM 504`。
  代码基线是 **Lua 5.4 系列**(含 `lua_newuserdatauv`、`lua_resetthread` 等 5.4 专有 API),
  在此之上做了本仓库自定义语法扩展(§1.2)。
- 预编译块签名被改成本项目专用:`LUA_SIGNATURE = "LuaForge-Studio\n"`(`lua.h:33`)。
  **标准 `luac` 的产物与它不兼容**,不要外部编译后塞进来。

### 1.2 相对标准 Lua 的语法扩展

这些**不是高亮关键字,而是 C 解析器真实实现的语法**。下表行号可直接跳转核对。

#### 表达式层

| 语法 | 形式 | 实现处 | 状态 |
|---|---|---|---|
| 三元 | `cond ? a : b` | `lparser.c:1535` | 可用,短路求值 |
| 可选访问 | `obj?.field`、`obj?.[k]` | `lparser.c:1307` | 可用 |
| 可选调用 | `obj?.m()` | `lparser.c:1313` | **不可用**,直接 `syntaxerror` |
| 空值合并 | `a ?? b` | `lparser.c:1461` | 可用,只对 `nil` 生效 |
| 管道 | `x \|> f` | `lparser.c:1463` / `lcode.c:1942` | 可用 |
| Lambda | `\x, y -> x + y` | `lparser.c:1140` | 可用,括号可省 |
| 复合赋值 | `+= -= *= /= //= %= ^= ..= &= \|= <<= >>=` | `lparser.c:1835` | 可用,**无** `&&=` / `\|\|=` |
| 逻辑符号 | `!` `!=` `&&` `\|\|` | `llex.c:700-730` | 可用,分别等价 `not` `~=` `and` `or` |
| 关键字缩写 | `$` → `local`、`@` → `::` | `llex.c:804,808` | 可用,纯词法替换 |

#### 语句层

| 语法 | 形式 | 实现处 | 状态 |
|---|---|---|---|
| try/catch/finally | `try ... catch(e) ... finally ... end` | `lparser.c:2735` | 可用;括号与变量名可省 |
| switch/case/default | `switch v do case 1 do ... end default ... end end` | `lparser.c:2368` | 可用;分隔符可为 `do`/`then`/`:`/`{` |
| when/case/else | `when cond then ... case cond then ... else ... end` | `lparser.c:2264` | 可用,分支是布尔条件 |
| continue | 同 `break` 用法 | `lparser.c:2160` | 可用 |
| const | `const x = 1` | `lparser.c:3006` | 可用,等价 `<const>` 局部 |
| global | `global x` / `global <const> y` / `global *` / `global function f()` | `lparser.c:2582` | 可用(`global` 是保留字) |
| defer | `defer <block> end` | `lparser.c:2905` | **不可用**,见下 |

#### ⚠️ `defer` 不可用

`deferstat` 把块编译成闭包后,生成的是一条**立即调用全局函数 `defer(closure)`**
的指令(`lparser.c:2931` 的 `singlevaraux(..., "defer", ...)` 与紧接着的 `OP_CALL`),
而运行时**从未注册这个全局 `defer`**:

- `core/src/main/resources/lua/*.lua` 里没有;
- `LuaActivity.initLua()` 也没注册;
- 全仓库没有一处 `.lua` 使用 `defer`。

所以 `defer ... end` 能编译,运行时抛 `attempt to call a nil value (global 'defer')`。
**不要用**,需要清理就走 `try/finally`。

#### `try` 的硬限制(编译期直接报错)

`try` 体被编译成独立闭包,以下三种用法在**编译期**就报错,不会静默出错:

| 写法 | 为什么不行 | 正确做法 |
|---|---|---|
| 带值 `return x` | 值落在闭包里,交不回外层 | 先赋给局部变量,出了 `try` 再 `return` |
| `break` / `continue` 跳出 `try` | 同上 | 把整个循环放进 `try` 内 |
| `...` | 闭包看不到外层 vararg | 先把 vararg 收进 table |

裸 `return`(无值)可以写,等价于提前结束 `try` 体。
`catch e` 与 `catch(e)` 等价;裸 `catch` 后紧跟语句时解析器会自行判断那个词是不是
错误变量(`catch print("x")` 中 `print` 不会被当变量名)。

```lua
-- 正确:try 里只做,结果放局部变量
local ok, data
try
  data = json.decode(text)     -- 可能抛错
  ok = true
catch e
  ok = false
  print("解析失败: " .. tostring(e))
finally
  if not ok then data = {} end
end
if ok then
  return data                  -- 出了 try 再 return
end
```

#### 使用建议

仓库内 `.lua/.aly` **实际只用了 `try/catch`**(`assets/layouthelper/main.lua:197,214`)。
其余扩展属于"解析器支持但生态未用"。给用户写业务代码时优先用标准写法
(`if/elseif`、`pcall`、`local`)—— 可读性、工具链兼容性都更好,也不会踩到上面那些边界。

`let` 虽然在词法表 `luaX_tokens` 里,**解析器没有任何分支处理 `TK_LET`**,写了直接语法错误 —— 不要用。

### 1.3 项目结构与 settings.json

新建项目来自模板 zip(`app/src/main/assets/templates/Default.zip`),解包后:

```
<project>/
  main.lua        # 入口,必须存在
  layout.aly      # 布局(纯 Lua 表字面量)
  settings.json   # 应用配置,必须存在
  icon.png        # 应用图标(可换)
  Preview.png     # 模板预览图(可删)
  lib/            # 可选:分架构原生库 lib/<abi>/libxxx.so
  libs/           # 可选:三方 dex/jar 与扁平 so
```

`settings.json` 全部字段:

```json
{
  "versionName": "1.0",
  "versionCode": "1",
  "uses_sdk": { "minSdkVersion": "23", "targetSdkVersion": "36" },
  "package": "com.example.myapp",
  "application": {
    "label": "AppName",
    "debugmode": true,
    "encrypt": true,
    "mergeDex": true,
    "skipCompile": []
  },
  "iconPath": "icon.png",
  "user_permission": ["WRITE_EXTERNAL_STORAGE", "READ_EXTERNAL_STORAGE", "INTERNET"],
  "auto_request_permissions": false,
  "implementation": [],
  "global_utils": []
}
```

| 字段 | 类型 | 作用 | 谁读它 |
|---|---|---|---|
| `package` | String | APK 包名 | 构建期 `ApkBuilder` |
| `versionName` / `versionCode` | String | 版本 | 构建期 |
| `uses_sdk.minSdkVersion` / `targetSdkVersion` | String(数字) | 最低/目标 SDK | 构建期 |
| `application.label` | String | 应用名 | 运行时 `LuaActivity.initENV()` 设为标题 |
| `application.debugmode` | Boolean | 调试模式 | 运行时;MCP `run_project` 的 `capture=debugger` 依赖它 |
| `application.encrypt` | Boolean | 是否加密脚本 | 构建期(`PROJECT_DEFAULT` 构建类型下生效) |
| `application.mergeDex` | Boolean | 是否把 `libs/*.dex` 合并进 classes 根 | 构建期 `BuildSlimmer` |
| `application.skipCompile` | String[] | 跳过编译(保持明文)的项目内相对路径 | 构建期 |
| `iconPath` | String | 图标路径(相对项目根,禁止绝对路径与 `..`) | 构建期 / 项目属性页 |
| `user_permission` | String[] | 权限**短名或全名**均可;短名会补 `android.permission.` | 构建期写进清单;运行时自动申请(见下) |
| `auto_request_permissions` | Boolean | **是否在应用启动时自动申请 `user_permission` 里尚未授予的运行时权限**。默认 `false`;老项目没有该键时行为不变 | 运行时 `LuaActivity.initENV()` |
| `implementation` | String[] | Maven 依赖坐标(`group:artifact:version`) | 构建期 `DependencyResolver` |
| `global_utils` | String[] | 要注入的 Java 工具类白名单 | 运行时 `LuaFunctionRegistrar` |

#### `auto_request_permissions` 的精确行为

`LuaActivity.initENV()` 读到 `true` 时调 `requestDeclaredPermissions(...)`,规则:

1. 只在 `SDK_INT >= M` 时动作;
2. 遍历 `user_permission`,短名补全为 `android.permission.XXX`;
3. **只申请运行时权限** —— 判定方式是问系统 `protectionLevel == dangerous`
   (不是硬编码名单,新系统加权限不用改代码);
4. **跳过已授予的**;全部已授予时**不做空调用**(空数组调用在部分 ROM 上无回调);
5. 一次性 `requestPermissions` 全部待申请项,`requestCode = 0x4C46`
   (`LuaActivity.REQUEST_CODE_AUTO_PERMISSIONS`);
6. 结果走既有的 `onRequestPermissionsResult(requestCode, permissions, grantResults)`
   全局回调 —— 脚本可自行接管;
7. 任何异常都吞掉:申请失败不能让页面起不来。

### 1.4 入口与生命周期回调

`LuaActivity.onCreate` 会 `doFile(main.lua)`,再按名调用下列 **Lua 全局函数**(存在才调用):

**页面**

| 回调 | 参数 | 说明 |
|---|---|---|
| `runFunc(pageName, arg)` | 页面名、启动参数表 | 页面自己的入口函数名 = 文件名(不含扩展名) |
| `runFunc("main", arg)` | `main.lua` 时也会先调 `main` | pageName 为 `main` 时不重复 |
| `onCreate(savedInstanceState)` | Bundle 或 nil | `doFile` 之后立即调用 |

**生命周期**

`onStart` `onResume` `onPause` `onStop` `onDestroy` `onRestart`(无参)

**状态**

| 回调 | 参数 |
|---|---|
| `onSaveInstanceState(outState)` | Bundle |
| `onRestoreInstanceState(bundle)` | Bundle |
| `onConfigurationChanged(newConfig)` | Configuration |

**交互**

| 回调 | 参数 | 返回约定 |
|---|---|---|
| `onKeyDown` | `(keyCode, event)` | 返回 `true` 拦截 |
| `onKeyUp` | `(keyCode, event)` | 返回 `true` 拦截 |
| `onKeyLongPress` | `(keyCode, event)` | 返回 `true` 拦截 |
| `onKeyShortcut` | `(keyCode, event)` | 返回 `true` 拦截 |
| `onTouchEvent` | `(event)` | — |
| `onBackPressed` | 无 | **返回 `true` 可拦截返回** |
| `onActivityReenter` | `(resultCode, data)` | — |
| `onUserLeaveHint` | 无 | — |
| `onNightModeChanged(mode)` | Int | — |

**菜单**

| 回调 | 参数 |
|---|---|
| `onCreateOptionsMenu(menu)` | Menu |
| `onOptionsItemSelected(item)` | MenuItem |
| `onCreateContextMenu(menu, v, menuInfo)` | 三个 |
| `onContextItemSelected(item)` | MenuItem |

**结果**

| 回调 | 参数 |
|---|---|
| `onActivityResult(requestCode, resultCode, data)` | 三个 |
| `onResult(name, ...)` | **仅当** Intent 里带 `NAME` extra 时先调(即 Lua 页面之间 `activity.result()` 回传);返回 `true` 可跳过 `onActivityResult` |
| `onRequestPermissionsResult(requestCode, permissions, grantResults)` | 三个 |

**广播 / 服务**

| 回调 | 参数 |
|---|---|
| `onReceive(context, intent)` | 两个 |
| `onServiceConnected` / `onServiceDisconnected` | 无 |

**其他**

| 回调 | 参数 | 说明 |
|---|---|---|
| `onAccessibilityEvent` | Event | 无障碍服务 |
| `onError(title, msg)` | 两个 | **返回 `true` 表示已自行处理**,不再弹默认提示 |

`onError` 的语义值得强调:`sendError` 会先 `RuntimeLog.error(...)` 落盘,
再调 `onError`;若它返回 `true`,就不走默认的 `sendMsg(title: detail)`。
`sendError` 有**重入守卫** —— `onError` 自己又报错时只落盘、不再回调,不会递归。

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

#### 尺寸与单位

| 写法 | 含义 | 实现处 |
|---|---|---|
| `"match"` / `"match_parent"` / `"fill"` / `"fill_parent"` | `-1` | `sizeConstants` |
| `"wrap"` / `"wrap_content"` | `-2` | 同上 |
| 纯数字 | **px** | — |
| `"16dp"` / `"20sp"` / `"12pt"` / `"3mm"` / `"1in"` | 按单位换算 | `dimensionConverterMap` |
| `"%w"` / `"%h"` | 屏宽/屏高百分比 | `dimensionConverterMap`(**仓库内无实际用例,用前先验**) |

#### 属性别名(会自动归一化)

`width` `height` `weight` `margin` `marginHorizontal` `marginVertical`
`marginLeft` `marginTop` `marginRight` `marginBottom` `marginStart` `marginEnd`
→ 自动加 `layout_` 前缀(`attributeNormalization`)。

**不归一的属性**会落到兜底分支 `view["xxx"] = v`,由 luajava 抛
`xxx is not a field`,导致**整份布局加载失败**。

#### 可用的特殊属性 setter

`loadlayout.lua` 里除通用反射赋值外,还有 51 个**特殊处理**的属性:

| 属性 | 说明 |
|---|---|
| `items` | 给 ListView 直接喂数据(自动建 `ArrayListAdapter`) |
| `pages` / `pagesWithTitle` | 给 ViewPager 喂页面(表或 `.aly` 路径) |
| `background` | 支持 `"#RRGGBB"`、`"?attr名"`、图片路径、`.9.png`、或直接传 Drawable 对象 |
| `onClick` / `onLongClick` | 值可为**函数**,也可为**字符串**(见下) |
| `src` | ImageView 图片;相对路径自动补 `luadir`;http 直连;走 Glide |
| `gravity` / `layout_gravity` | 字符串常量名,支持 `"center\|bottom"` 拼接 |
| `text` / `title` / `subtitle` / `hint` / `summary` / `tooltip` | 文本类 |
| `textSize` / `textColor` / `textStyle` / `textAppearance` | 字体类 |
| `ellipsize` / `maxLines` / `scaleType` / `textAlignment` / `inputType` / `imeOptions` | 文本展示/输入 |
| `radius` / `cardElevation` / `elevation` / `strokeWidth` | 卡片外观 |
| `orientation` / `visibility` / `layerType` / `layout_weight` | 布局 |
| `layout_collapseMode` / `layout_scrollFlags` / `layout_anchor` / `layout_behavior` | Coordinator 系 |
| `padding*` / `minWidth` / `minHeight` | 尺寸 |
| `onCreate` | 控件创建后立刻回调:`value(view, layoutParams)` |

#### `id` 即全局变量

带 `id = "x"` 的控件写入 `views` 表,而 `views` 默认为 `_G`。**所以 `id` 写完即成全局变量**:

```lua
-- layout.aly 里 { MaterialTextView, id = "title", text = "Hi" }
-- main.lua 里,loadlayout 之后可直接:
title.setText("改过了")

-- 想收集到独立表而不污染 _G:
local ids = {}
local root = loadlayout("layout", ids)
ids.title.setText("也改过了")
```

#### 事件字符串的解析

`onClick = "funcName"` 的值是字符串时,`loadlayout` 生成
`function(v) (views[value] or _G[value])(v) end` —— 先查 `views`,再查 `_G`。
**名字找不到只在运行时报错**,静态看不出来。

#### `.aly` 可被 `require`

`loadlayout.lua` 往 `package.searchers` 注入了 `alyloader`,识别 `\27Lua` 预编译签名,
否则按 `return <内容>` 当表达式加载。所以 `require "page1"` 能直接拿到布局表。

#### 两套实现

| 文件 | 用途 | 差异 |
|---|---|---|
| `core/src/main/resources/lua/loadlayout.lua` | **运行时**(项目实际使用,1226 行) | 基准实现 |
| `app/src/main/assets/layouthelper/loadlayout2.lua` | 布局助手(1265 行) | 额外支持三方控件白名单(`_G.THIRD_PARTY_WIDGET_SUPPORT`)、布局助手的拖拽选中态等 |

两者对 `pages` / `pagesWithTitle` / 事件字符串 / `id` 的语义**完全一致**,
写项目代码以运行时那份为准。

语法一致;写项目代码以运行时那份为准。

#### 内置控件

随 IDE 发布、编译进 `core`(不是三方库):**`MaterialTextField`**。
直接写短名即可,属性名 = 去掉 `set` 前缀首字母小写。完整清单用 MCP `list_widgets` 查。

> 原内置播放控件(`ExoVideoView` / `SimpleVideoView` / `MusicPlayerBar` / `ExoAudioPlayer`)
> 已在 1.7.0 移除。需要播放请用系统 `VideoView` / `MediaPlayer`,或自行在 `libs/` 引库。

### 1.6 全局对象与函数

`initLua()` 注入:

| 名字 | 内容 |
|---|---|
| `activity` / `this` | 当前 `LuaActivity` 实例 |
| `R` | `com.luaforge.studio.core.R`(core 的资源) |
| `android.R` / `androidx.R` / `material.R` | 框架 / AppCompat / Material 资源 |
| `luajava` | 桥接库;额外带 `luadir` / `luapath` / `luaextdir` 三个路径字段 |
| `Https` | **仅当 `global_utils` 含 `OkHttpUtil` 时注入**:`Https.get/post/upload/download` |
| `print` | 定制打印 → `sendMsg` → logcat + `luaforge.log`(多参数以 tab 分隔) |
| `set(name, v)` / `call(name, ...)` | 跨线程设值/调用 |

`require "import"` 之后:

- `require` 加载 `import.lua` 时会**在模块体内直接执行 `env_import(_G)`**
  (`import.lua:494`),把全部工具函数与 `import` 本身写进 `_G` —— 所以只要
  `require "import"` 一次就生效,不需要接收返回值。
- `import "android.widget.Button"`、`import "android.view.*"`(支持通配);
  依次尝试 `''`、`java.lang.`、`java.util.`、`com.androlua.` 前缀。
- 未 import 的类名也能按需解析(`_G` 上的元表 `globalMT`)。
- 注入的工具函数(全部挂在 `_G` 与 `import` 环境):

#### 逐个说明(含参数、返回与坑)

**`dump(o) -> String`** —— 把任意值转成可读字符串,支持 table 递归、Java 对象 `tostring`。
循环引用会折叠为路径字符串,不会无限递归。

```lua
print(dump({a = 1, b = {c = "x"}}))
-- { ["a"] = 1; ["b"] = { ["c"] = "x" }; }
```

**`each(o) -> iterator`** —— 包 `java.lang.Iterable`,用于 `for`:

```lua
local list = activity.getResources().getStringArray(...)   -- 任何 Iterable
for v in each(someJavaList) do print(v) end
```

**`enum(e) -> iterator`** —— 包 `java.util.Enumeration`(老 API 常见):

```lua
for v in enum(someEnumeration) do print(v) end
```

**`printstack() -> nil`** —— 打印当前调用栈(含各层 upvalue),排查"谁调了我"用。
输出进 `luaforge.log`。

```lua
local function inner() printstack() end
local function outer() inner() end
outer()      -- 日志里能看到 inner -> outer -> main
```

**`compile(name) -> nil`** —— 把一个 dex/jar 加进当前页面的类加载器。
项目里 `libs/*.dex|jar` 通常由 `LuaDexLoader` 自动加载,**手动调它的场景很少**。

**`task(src, callback) -> LuaAsyncTask`** —— 子线程执行 `src`,`callback` 回到主线程。
`src` 可以是**函数**,也可以是 **`.lua` 文件路径**(相对项目根)。

```lua
task(function()
  -- 子线程:耗时活儿
  local ok, body = pcall(function() return Http.get 之类 ... end)
  return "结果"                      -- 返回值传给 callback
end, function(result)
  -- 主线程:更新 UI
  title.setText(tostring(result))
end)
```

> 注意 `callback` 是 `task` 的**最后一个实参**,由 `select("#", ...)` 取
> (`import.lua:459`)。多传中间参数时它们会作为 `src` 的入参。

**`thread(src, ...) -> LuaThread`** —— 立即在子线程启动,不等待结果。
`src` 同样可以是函数或路径,`...` 作为它的入参。

```lua
local t = thread(function(a, b)
  print("子线程收到", a, b)
end, 1, 2)
```

**`timer(f, delay, period, ...) -> LuaTimer`** —— 定时器。`period == 0` 表示只跑一次;
`delay` 是首次触发延迟(毫秒)。返回的 `LuaTimer` 可 `:stop()`。

```lua
-- 3 秒后触发一次
local once = timer(function() print("3s 到了") end, 3000, 0)

-- 每 1 秒触发,1 秒后开始
local tick = timer(function()
  print("tick")
end, 1000, 1000)

-- 停止
tick.stop()
```

**`loadlayout(spec[, env])`** —— 布局表/`.aly` 路径 → View。`env` 是收集 `id` 的表,
缺省为 `_G`(即 `id` 变全局变量)。详见 §1.5。

**`loadbitmap(path) -> Bitmap`** —— 加载位图。规则(`loadbitmap.lua`):

| 传入 | 行为 |
|---|---|
| `"images/icon"` | 补 `.png` 后按**项目相对路径**加载 |
| `"images/icon.jpg"` | 已有扩展名,不补 |
| `"https://..."` / `"http://..."` | 走网络加载 |
| `"/sdcard/a.png"` | 绝对路径直接读 |

> 扩展名判定正则是 `%.%a%a%a%a?$` —— 只认 **3~4 位字母**的扩展名。
> `.jpeg`(4 位)没问题,但 `.webp` 后面的 `b` 也是字母所以算命中;
> 而 `.9.png` 这类会把 `.png` 当扩展名处理。名字里带点但不以扩展名结尾时
> (如 `"v1.2/icon"`)会被误判成"已有扩展名",这时请显式写全 `"v1.2/icon.png"`。

**`loadmenu(menu, spec[, root[, n]])`** —— 把 Lua 表描述写进 `Menu` 对象。
`n` 表示前 n 项 `SHOW_AS_ACTION_ALWAYS`。详见 `WidgetsCatalog.md` 的菜单配方。

#### ⚠️ 不要用的名字

| 名字 | 原因 |
|---|---|
| `getids()` | 死代码:读 `luajava.ids`,但全仓库无赋值点,实际返回 `nil` |
| `toast(...)` / `alert(...)` / `dialog(...)` / `sleep(...)` | **本仓库没有任何注册证据**。弹窗用 `MaterialAlertDialogBuilder`,提示用 `Snackbar` 或 `activity.showToast(...)` |

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
  .setTitle("标题")          -- void 方法也能链式:JNI 层"无返回值时返回自身"
  .setMessage("内容")
  .setPositiveButton("确定", nil)
  .show()
```

`luajava` 暴露的全部函数:

`bindClass`、`new`、`newInstance`、`loadLib`、`createProxy`、`newArray`、`createArray`、
`astable`、`tostring`、`coding`、`clear`、`instanceof`、`getContext`、`override`

#### 两条必须说清的机制

1. **链式调用为什么成立**:JNI 层规定"方法无返回值时返回调用者自身"
   (`luajava.c:833` 注释 `/* if no ret, return self */`,配合
   `LuaJavaAPI` 里 `ret == null && returnType == Void.TYPE` 的判定)。
   所以 `void` 方法(如 `setTitle`)也能继续 `.` 下去。
2. **属性赋值会自动补 `set` 前缀**:`view.text = "x"` 走 `javaSetter` → `javaSetMethod`,
   内部把首字母大写成 `"set" + Name`。所以 `view.text = "x"` **等价于**
   `view.setText("x")`,不是"省略前缀"这么简单,而是有专门的 setter 解析路径。
   读属性同理走 `javaGetter`,自动尝试 `getXxx` / `isXxx`。
   若名字既非字段也无对应 setter,报 `xxx is not a field`。

#### `luajava.override` 改写已有类的方法

签名是 **`luajava.override(SomeClass, { methodName = fn, ... })`**,返回的是
**已经实例化好的对象**,不是 Class(`LuaJavaAPI.java:685-706`):

```java
Class<?> cls = new LuaEnhancer(clazz).create(filter);  // 生成子类
int r = getObjInstance(L, cls);                        // 立刻实例化
obj.setMethodInterceptor_Enhancer(...);                // 装拦截器
L.pushJavaObject(obj);                                 // 压的是实例
```

由此有三条必须知道的行为:

1. **方法表里有的方法才会被拦截**,其余走父类原实现(由 `MethodFilter` 决定);
2. 因为是"立即实例化",**构造参数决定成败** —— `getObjInstance` 在无参调用时会先试
   `new X()`,失败再试 `new X(Context)`;都失败时要靠 luajava 的构造器重载匹配,
   所以只适合无参或 `(Context)` 可构造的类(如 `View`);
3. Lua 侧写的方法签名要**与 Java 原方法一致**,不要想当然加 `self`
   —— luajava 调 Lua 函数时按 Java 实参顺序传。

```lua
local AppCompatTextView = luajava.bindClass "androidx.appcompat.widget.AppCompatTextView"

-- 返回的是实例,可直接当 View 用
local tv = luajava.override(AppCompatTextView, {
  setText = function(text)
    print("setText 被调用: " .. tostring(text))
  end,
})
```

> 该函数**在本仓库 `.lua` 里没有任何使用案例**(`loadlayout2.lua` 只做了
> `local override = luajava.override` 的取值),上面的写法按源码推导,
> 用前请先在小页面验证一次。自绘请优先用 `LuaDrawable`(见 `LuaDraw.md`),
> 那条路有确定语义且更简单。

### 1.8 可运行的最小项目

`main.lua`(Default 模板实测内容,略有整理):

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

### 1.9 网络访问(优先 `Http`)

**前提**:`settings.json` 的 `user_permission` 必须含 `INTERNET`(模板已含)。

`Http`(`com.androlua.Http`)基于 `HttpURLConnection`,是**类**(不是实例):

```lua
require "import"   -- 之后可直接用 Http(全局表按需解析 com.androlua. 前缀)

-- GET:回调 (code, content, cookie, headers)
Http.get("https://api.example.com/data", function(code, content, cookie, headers)
  if code == 200 then print(content) end
end)

-- GET 带自定义头:参数顺序 url, cookie, charset, header, callback
Http.get("https://api.example.com/user", nil, "UTF-8",
  {["Authorization"] = "Bearer token123"},
  function(code, content, cookie, headers) end)

-- POST:第二参可为字符串,也可为表(表单字段)
Http.post("https://api.example.com/login", {user = "a", pass = "b"},
  function(code, content, cookie, headers) end)

-- 下载:回调是 (code, path, headers) 三个参数
Http.download("https://example.com/a.zip", "/sdcard/a.zip",
  function(code, path, headers) end)
```

#### 方法一览(`core/src/main/java/com/androlua/Http.java`)

| 方法 | 动词 | 说明 |
|---|---|---|
| `Http.get(url[, cookie[, charset]][, header], cb)` | GET | 多个重载,见下 |
| `Http.post(url, data[, cookie][, charset][, header], cb)` | POST | `data` 可为 String 或表 |
| `Http.post(url, data, file, cb)` | POST | 表单 + **文件上传**(multipart/form-data) |
| `Http.put(url, data[, cookie][, charset][, header], cb)` | PUT | 与 post 同形 |
| `Http.delete(url[, cookie][, charset][, header], cb)` | DELETE | — |
| `Http.download(url, savePath, cb)` | GET | 落盘到 `savePath` |

#### 回调签名

| 场景 | 参数 |
|---|---|
| 成功 | `(code, content, cookie, headers)` |
| **失败** | `(code, message)` —— 只有两个参数,且 `code == -1` |
| `download` | `(code, path, headers)` |

判断失败要看 `code == -1`,**不要**只看 `content` 是否为空:

```lua
Http.get("https://bad.example.com", function(code, content, cookie, headers)
  if code == -1 then
    print("请求失败: " .. tostring(content))   -- 此时 content 是错误信息
    return
  end
  print(code, content)
end)
```

#### 文件上传

`post` 的 "data + file" 重载会组 `multipart/form-data`:

```lua
Http.post(
  "https://api.example.com/upload",
  { name = "avatar" },                      -- 普通表单字段
  { file = "/sdcard/DCIM/a.jpg" },          -- 文件字段: 字段名 -> 本地路径
  function(code, content, cookie, headers) end
)
```

#### 全局默认头

```lua
Http.setUserAgent("LuaForge/1.0")
Http.setCookie("session=abc")
Http.setReferer("https://example.com")
Http.setHeader({ ["X-Token"] = "xxx" })     -- HashMap<String,String>
```

这些都是**进程级静态状态**,设置后影响后续所有 `Http.*` 请求。

#### 其它要点

- 回调在 `onPostExecute`(**主线程**)触发,可直接更新 UI。
- `Http` 是**类**不是实例:`Http.get(...)`,不要 `Http():get(...)`。
- ⚠️ **不要写 `import "Http"`**:`import(package)` 不做前缀补全,短名会直接报
  `cannot find Http`;短名前缀解析只发生在 `_G` 的元表访问路径上。

**次选 `Https.*`**(OkHttp):仅当 `global_utils` 含 `OkHttpUtil` 时才有,见 `OkHttpUtil.md`。

> 1.7.0 起**没有**网络代理与请求拦截;1.6.x 文档里的这两项已不存在。
> 权限与自动申请见 `NETWORK.md` 第 1 节。

### 1.10 内置原生库(优先复用,别无脑纯 Lua 重写)

这些 `.so` 编进应用本体,Lua 侧直接 `require` 即可:

| `require` 名 | 能力 | 主要函数 |
|---|---|---|
| `cjson` | JSON 编解码(C,极快) | `cjson.encode` / `cjson.decode` |
| `json` | 另一套 JSON 绑定 | `json.encode` / `json.decode` |
| `yyjson` | 高性能 JSON(解析/序列化/可变 DOM) | 见模块头文件 |
| `bson` | BSON 编解码 | `encode` / `decode` / `objectid` / `binary` / `regex` / `date` / `timestamp` |
| `lfs` | 文件系统遍历/属性/目录 | `lfs.dir` / `lfs.attributes` / `lfs.mkdir` / `lfs.rmdir` / `lfs.touch` / `lfs.link` |
| `luv` | libuv 事件循环 / 异步 IO | 见 luv 文档 |
| `socket.core` | LuaSocket TCP/UDP;另有 `socket.serial` / `socket.unix` / `mime.core` | — |
| `xml.core` / `xml` | LuaXML 解析 | — |
| `zlib` | 压缩 | `deflate(level)` / `inflate()` 返回流函数;`crc32` / `adler32` / `version` |
| `zip` | zip 归档读写 | `zip.open` + `archive:add(name, type, src)` / `stat` / `open` / `get_name` |
| `crypt` | 编解码/摘要(**摘要均返回二进制**) | `base64encode/decode`、`hexencode/decode`、`sha1`、`hmac_sha1`、`hmac_hash`、`desencode/decode`、`dhexchange`、`dhsecret`、`hashkey`、`randomkey` |
| `base64` | Base64 | `encode` / `decode` |
| `md5` | MD5 摘要 | `sum` / `exor` / `crypt` / `decrypt` |
| `microaes` | AES 全模式 | 见 `micro_aes.h` |
| `regex` | POSIX 正则(**不是** Lua 的 `%` 模式语法) | `find` / `match` / `gmatch` / `gsub` / `split` / `count` / `comp` |
| `rawio` | 原始文件读写 | `read` / `write` / `tsread` 及各别名 |
| `time` | 计时(毫秒) | `now` / `time` / `start` / `elapsed(start)` / `countdown(ms)` / `isEnded` / `remaining` / `diff` |
| `unix` | POSIX 系统调用 | 另有 `unix.unsafe` 命名空间 |
| `ffi` | LuaJIT 风格 ffi 子集 | 另导出 `cffi` |
| `sensor` | 设备传感器 | — |
| `gl` | OpenGL 绑定 | — |
| `canvas` | Android Canvas 绑定 | 见 `LuaDraw.md` 第 2 节 |
| `tcc` | 内嵌 TCC 编译器 | — |
| `decrypt` | 加密脚本加载 | `loadfile` / `dofile` / `load` |
| `luaparser` | Lua 源码解析(IDE 用) | — |

> ⚠️ `lsqlite3` 目录存在但**未接入顶层 `CMakeLists.txt`**,不随包构建 —— **别写 `require "lsqlite3"`**。
> ⚠️ `socket` **没有** `luaopen_socket`,写 `require "socket"` 会失败,要用 `require "socket.core"`。

#### 常用库的最小可运行示例

**JSON(`cjson`)** —— 首选,比纯 Lua 快一个数量级:

```lua
local cjson = require "cjson"

local text = cjson.encode({ name = "张三", tags = {"a", "b"}, n = 42 })
print(text)                       -- {"name":"张三","tags":["a","b"],"n":42}

local obj = cjson.decode(text)
print(obj.name, obj.tags[2])      -- 张三  b
```

需要"数组 / 对象"的区分控制时用它的配置项:

```lua
cjson.encode_empty_table_as_object(false)   -- 空表编码成 [] 而不是 {}
cjson.encode_number_precision(14)           -- 浮点精度
cjson.encode_sparse_array(true)             -- 允许稀疏数组
```

**文件系统(`lfs`)**:

```lua
local lfs = require "lfs"

-- 遍历目录(注意包含 "." 与 "..",与 shell 的 ls -a 一致)
for name in lfs.dir(activity.getLuaDir()) do
  if name ~= "." and name ~= ".." then print(name) end
end

-- 属性(不存在的路径返回 nil + 错误信息)
local attr = lfs.attributes("/sdcard/a.txt")
if attr then
  print(attr.size, attr.modification, attr.mode)   -- 字节 / mtime / "file"|"directory"
end

lfs.mkdir("/sdcard/newdir")          -- 单层创建;失败返回 nil, 错误信息
lfs.rmdir("/sdcard/newdir")          -- 只能删空目录
lfs.touch("/sdcard/a.txt")           -- 只更新已存在文件的时间戳,不会创建文件
lfs.touch("/sdcard/a.txt", 1700000000, 1700000000)   -- 指定 atime / mtime
```

**压缩(`zlib`)** —— 先用 `deflate(level)` 建流,再调它:

```lua
local zlib = require "zlib"

local data = string.rep("hello ", 1000)

-- deflate(level): level 取 0..9 或省略(=默认);返回一个"流函数"
local d = zlib.deflate(9)
-- 流函数(chunk[, flush]) 返回 4 个值:
--   chunk, isFinal, totalIn, totalOut
local packed, done = d(data, "finish")   -- flush: "none"|"sync"|"full"|"finish"
print(#data, #packed, done)              -- 6000  几十  true

local i = zlib.inflate()
local unpacked, iDone = i(packed)
print(unpacked == data, iDone)           -- true  true
```

> 大文件可以分块喂:`d(part1, "none")` → `d(part2, "none")` → `d("", "finish")`,
> 每步把返回的第一段拼起来。`finish` 之后流会被自动关闭,再调用会报
> `IllegalState: calling deflate function when stream was previously closed`。

校验和 —— 与 `deflate` 一样是**先建流再喂数据**,返回 `(校验值, 累计长度)`:

```lua
local zlib = require "zlib"

local crc = zlib.crc32()             -- 建流(初始值 0)
local ok, len = crc("abc")           -- 返回两个值
print(string.format("%08x", ok), len)   -- 352441c2  3

-- 分块累计:多次调用会累加
local c = zlib.crc32()
c("ab"); local rest = c("c")
print(string.format("%08x", rest))   -- 352441c2

-- adler32 用法完全相同
local a = zlib.adler32()
print(a("abc"))                      -- 数值 + 长度

-- version() 返回**多个数字**(主/次/修订),不是字符串
local major, minor, patch = zlib.version()
print(major, minor, patch)
```

**正则(`regex`)** —— POSIX 语法,与 Lua 的 `string.find` 模式**不同**(用 `%d`→`[0-9]`、`()` 分组):

```lua
local re = require "regex"

-- find:返回 起点, 终点, 捕获组...(未命中返回 nil)
print(re.find("hello world", "wor"))              -- 7  9
print(re.find("abc123", "([a-z]+)([0-9]+)"))      -- 1  6  abc  123

-- match:返回 捕获组...;无捕获组时返回整个匹配
print(re.match("abc123", "([a-z]+)([0-9]+)"))     -- abc  123
print(re.match("abc123", "[0-9]+"))               -- 123

-- gsub:返回 替换后的字符串, 替换次数
local out, n = re.gsub("a1b2", "[0-9]", "#")
print(out, n)                                     -- a#b#  2

-- gmatch:迭代器
for m in re.gmatch("a1b2c3", "[0-9]") do print(m) end    -- 1 2 3

-- split:返回**迭代器**,不是表;用 for 取
for part in re.split("a,b,c", ",") do print(part) end    -- a b c

-- 需要表就自己收集
local parts = {}
for p in re.split("a,b,c", ",") do parts[#parts + 1] = p end

-- count:匹配次数
print(re.count("a1b2", "[0-9]"))                  -- 2

-- comp:预编译,避免重复编同一个模式
local c = re.comp("([a-z]+)([0-9]+)")
print(c:find("abc123"))        -- 1  6  abc  123(方法版,返回同 re.find)
print(c:match("abc123"))       -- abc  123
print(c:exec("abc123"))        -- 1  6  {["1"]="abc", ["2"]="123"}(命中)/ nil
```

方法版与函数版的区别(`algo.h:651-684`):

| 方法 | 返回 |
|---|---|
| `c:find(s)` | 起点, 终点, 捕获组... |
| `c:match(s)` | 捕获组...;无捕获组时是整个匹配 |
| `c:exec(s)` | 起点, 终点, **捕获组表** —— 恰好三个值 |
| `c:tfind(s)` | 起点, 终点, **子串表** |

> **这是 POSIX 正则,不是 Lua 的 `%` 模式**:字符类写 `[0-9]`、`[a-z]`、`\d` 不可用;
> 分组用 `()`;`*` `+` `?` `{n,m}` 语义与标准 ERE 一致。要 Lua 原生模式直接用
> `string.find` / `string.gsub`,别混着写。

**摘要(`crypt` / `md5` / `base64`)**:

```lua
local crypt = require "crypt"

print(crypt.base64encode("hello"))             -- aGVsbG8=
print(crypt.base64decode("aGVsbG8="))          -- hello
print(crypt.hexencode(string.char(1, 2)))      -- 0102
print(crypt.hexdecode("0102") == string.char(1, 2))   -- true
print(#crypt.sha1("abc"))                       -- 20(二进制摘要长度)
print(crypt.hexencode(crypt.sha1("abc")))       -- a9993e364706816aba3e25717850c26c9cd0d89d
-- hmac 同理,也都返回二进制;第一个参数是 key
print(crypt.hexencode(crypt.hmac_sha1("key", "msg")))
print(crypt.hexencode(crypt.hmac_hash("key", "msg")))
local b64 = require "base64"
print(b64.encode("hello"), b64.decode("aGVsbG8="))    -- aGVsbG8=  hello
```

`md5` 模块只有 4 个函数,**没有 `sumhexa`**:

| 函数 | 签名 | 返回 |
|---|---|---|
| `md5.sum(s)` | 任意二进制字符串 | **16 字节二进制摘要**(不是十六进制!) |
| `md5.exor(s1, s2)` | 等长字符串 | 逐字节异或结果;长度不等直接报错 |
| `md5.crypt(msg, key, seed)` | 三个参数 | 加密串(自带 seed 前缀) |
| `md5.decrypt(cipher, key)` | 两个参数 | 还原明文;满足 `decrypt(crypt(m,k,s),k) == m` |

要十六进制摘要,自己转:

```lua
local md5 = require "md5"
local crypt = require "crypt"

print(crypt.hexencode(md5.sum("abc")))     -- 900150983cd24fb0d6963f7d28e17f72
```

**`crypt` 的摘要函数(`sha1` / `hmac_sha1` / `hmac_hash`)与 `md5.sum` 全部返回二进制**,
要十六进制字符串一律用 `crypt.hexencode` 包一层,别直接把返回值当校验串打印。

**计时(`time`)**:

所有时间戳单位都是**毫秒**。

| 函数 | 签名 | 返回 |
|---|---|---|
| `time.now()` | — | 当前时间戳(毫秒) |
| `time.time()` | — | 当前**秒**数(不是毫秒) |
| `time.start()` | — | 起始时间戳(毫秒),就是 `now()` |
| `time.elapsed(start)` | **必传 start** | 从 `start` 到现在的毫秒数 |
| `time.diff(t1, t2)` | 两个时间戳 | `t1 - t2`(毫秒,可为负) |
| `time.countdown(ms)` | 时长(毫秒) | 结束时间戳 |
| `time.isEnded(endTs)` | 结束时间戳 | 布尔 |
| `time.remaining(endTs)` | 结束时间戳 | 剩余毫秒,已结束为 0 |

```lua
local time = require "time"

-- 方式一:手动取时间戳
local t0 = time.now()
-- ... 干活 ...
print(time.diff(time.now(), t0))   -- 耗时(毫秒)

-- 方式二:start / elapsed 配对(注意 elapsed 要传 start 的返回值)
local start = time.start()
-- ... 干活 ...
print(time.elapsed(start))         -- 耗时(毫秒)

-- 倒计时
local endTs = time.countdown(3000)      -- 3 秒后结束
print(time.isEnded(endTs))              -- false
print(time.remaining(endTs))            -- 约 3000(递减)
```

**zip 归档** —— `add` 的第三个参数是**来源类型**,不是内容:

```lua
local zip = require "zip"

-- 打开/创建:第二个参数可给 zip.CREATE 等标志;失败返回 (nil, 错误信息)
local a = assert(zip.open("/sdcard/data.zip", zip.CREATE))

-- add(名字, 类型, 来源...):类型只接受 "file" / "string" / "zip"
a:add("readme.txt", "string", "hello")           -- 内联字符串
a:add("photo.jpg", "file", "/sdcard/DCIM/a.jpg") -- 从磁盘文件
print(a:get_num_files())
a:close()

-- 读回:open 的是"文件句柄",read 传 Lua 的读格式
local r = assert(zip.open("/sdcard/data.zip"))
local f = r:open("readme.txt")     -- 也可传序号(整数)
print(f:read("a"))                 -- "a" = 读到结尾(Lua 5.3+ 写法)
f:close()
r:close()
```

其它可用的归档方法:`stat(name)`(取 `size`/`crc`/`mtime`)、`get_name(idx)`、
`name_locate(name)`、`replace`、`rename`、`delete`、`add_dir`、`get_archive_comment`、
`set_archive_comment`。

> 读格式用 `"a"`(`*a` 是 5.1 旧写法,本运行时按 5.4 语义解析,建议统一写 `"a"`)。

加载机制:全局 `package.cpath` 为 `nativeLibraryDir/lib?.so;libDir/lib?.so`,
`require "xxx"` 命中 `libxxx.so` 后调 `luaopen_xxx`;子模块(`a.b`)走 `searcher_Croot`,
先加载 `liba.so` 再调 `luaopen_a_b`。

#### 项目自带的原生库与三方 dex

| 位置 | 说明 |
|---|---|
| `lib/<abi>/libxxx.so` | 分架构原生库,只加载与设备首选 ABI 匹配的子目录;无该子目录时回退 `lib/*.so` |
| `libs/*.dex` / `libs/*.jar` | 三方 dex/jar,动态加载后其类可按裸类名或全限定名直接用 |
| `libs/*.so` | 兼容旧版的扁平原生库(不分架构) |

用 MCP `list_project_libraries` 可列出这些内容与设备首选 ABI。

#### 现成的 Java 能力(别用纯 Lua 重写)

| 能力 | 用什么 |
|---|---|
| 网络 | `Http.get/post/put/delete/download`(见 §1.9) |
| 位图 | `BitmapUtil`(需 `global_utils`) |
| 图片加载/圆角/缓存 | `GlideUtil`(需 `global_utils`) |
| 网络(OkHttp) | `Https.*`(需 `global_utils` 含 `OkHttpUtil`) |
| 极速文件读写 | `FastIOUtil`(需 `global_utils`) |
| 列表适配器 | `RecyclerAdapterUtil`(需 `global_utils`)或 `LuaAdapter` / `LuaArrayAdapter` |
| 文件/zip/摘要 | `com.androlua.LuaUtil` 静态方法:`copyDir` / `unZip` / `zip` / `getFileMD5` / `getFileSha1` / `readAsset` |

`global_utils` 可选值只有 5 个(`NewProjectScreen.kt:81-87`):
`BitmapUtil`、`GlideUtil`、`OkHttpUtil`、`FastIOUtil`、`RecyclerAdapterUtil`。

> 例外:`OkHttpUtil` 的 `get/post/upload/download` 挂在全局表 `Https` 下
> (反射注册路径跳过这四个方法)。旧写法裸名 `get`/`post`… 仅在全局名未被占用时
> 注册为兼容别名 —— **新代码一律写 `Https.*`**。

### 1.11 Compose(用 Lua 直接写界面)

除 `loadlayout` 外还可用 **Lua 原生 Compose DSL**。**完整参考见 `doc/Compose.md`**。

```lua
local count = state(0)

local view = compose(function()
  Column({ padding = 16, gap = 12, fillMaxWidth = true, content = function()
    Text("计数:" .. count.value, size = 20, bold = true)
    Button({ text = "+1", onClick = function() count.value = count.value + 1 end })
  end })
end)

activity.setContentView(view)
```

关键点(违反会静默出错,不报异常):

| 事项 | 正确做法 |
|---|---|
| 入口 | `compose(fn)` / `composeView(fn)` 返回 `ComposeView`;`composeContent(fn)` 直接设为内容 |
| 状态 | `local s = state(0)`;读 `s.value`,写 `s.value = x` |
| 记忆化 | `remember(function() ... end)` 按**调用位置**缓存 |
| 属性 | dp/sp 用**数字**;颜色写 `0xAARRGGBB` 或 `"#RRGGBB"` |
| 顺序 | 同一 group 内组件调用顺序必须稳定 |
| 变长列表 | 用 `LazyColumn({ items = t, content = function(item, i) ... end })`,不要 `for` 堆节点 |
| 副作用 | `LaunchedEffect({ key = "k", content = function() ... end })` |
| 重名 | 显式 `import "android.widget.Button"` 会覆盖同名组件,改用 `Compose.Button` |
| `size` 属性 | `Spacer`/`Box`/`Image` 上是**尺寸(dp)**;`Text`/`Icon` 上是**字号(sp)** |

**65 个组件**,完整清单与属性见 `Compose.md`。`AndroidView({ view = 某原生View })`
可把任何现有 Android 控件嵌进 Compose 树。

**体积**:Compose 运行时(约 1.5MB dex)**无条件**随 core 打进每个项目 APK,没有零体积开关。

### 1.12 自绘(LuaDrawable / canvas)

见 **`doc/LuaDraw.md`**。最短可用形态:

```lua
local LuaDrawable = luajava.bindClass "com.androlua.LuaDrawable"
local Paint = luajava.bindClass "android.graphics.Paint"

view.setBackground(LuaDrawable(function(canvas, paint, self)
  local b = self.getBounds()          -- 以 bounds 为准,别硬编码尺寸
  paint.setAntiAlias(true)
  paint.setColor(0xFF6750A4)
  canvas.drawCircle(b.centerX(), b.centerY(), math.min(b.width(), b.height()) / 2, paint)
end))
```

三条硬规则:

1. **首次回调给三个参数**(`canvas`, `paint`, `self`);**返回函数**则之后只给 `canvas`。
2. 不返回函数时**每次绘制都会重新走首次分支**,`paint` 是同一实例。
3. 数据变了要 `view.invalidate()`,`Drawable` 不会自己知道。

### 1.13 易错点

1. `main.lua` 与 `settings.json` **必须同时存在**才算合法项目。
2. 不 `require "import"` 就没有 `import` / `loadlayout`,连 `LinearLayoutCompat` 都解析不到。
3. 尺寸:直接写数字是 **px**;`"100dp"` / `"20sp"` 走单位解析;简写只认 §1.5 那几个词。
4. `onClick = "funcName"` 的字符串必须能在 `views` 或 `_G` 找到同名函数,否则**只在运行时**报错。
5. 布局属性写错多数会 `error("Layout loading error: ... key='x'")` 打断加载,所以跑完必须读日志。
6. 调试用 `print` 即可,输出会落 `luaforge.log`,用 MCP `get_logs` / `get_runtime_errors` 读。
7. `canvas` 原生模块的方法**不会自动带 self**,第一个参数永远要传 canvas 本身。
8. **不要用 `require "canvas"` 的 `get()`** —— 它会替换共享的 Java 对象元表,把该
   LuaState 里所有 Java 方法解析搞坏(详见 `LuaDraw.md` §2.1)。

---

## 2. MCP 服务:连接与协议

### 2.1 启用

侧边栏 **MCP** → 开启「启用 MCP 服务」。默认端口 `9123`,可改 1024–65535。
开启「需要访问令牌」后请求须带 `Authorization: Bearer <令牌>`。协议版本 `2024-11-05`。

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

方法:`initialize`、`ping`、`tools/list`、`tools/call`、`resources/list`(空)、`prompts/list`(空)。
通知(无 `id`)返回 HTTP 202。

---

## 3. MCP 调用技能

### 3.1 调用约定

- 参数**全部可选**,除清单标 **必填** 的。
- 数组类参数(`paths`、`expectTexts` 等)可传 JSON 数组,也可传**换行/逗号分隔**的字符串。
- 路径可为项目相对路径,或允许范围内的绝对路径。
- 不传 `path` 时多数工具回退到"当前打开的项目 / 当前活动文件"。

### 3.2 工具清单(59 个)

**项目与文件**

| 工具 | 说明 | 关键参数 |
|---|---|---|
| `list_projects` | 列项目 | — |
| `list_files` | 列文件树 | `path`、`recursive`、`maxEntries` |
| `read_file` | 读文本;`raw=true` 返回原文(不转义),`startLine`/`endLine` 按行读(1 起闭区间) | `path` **必填**、`startLine`、`endLine`、`raw`、`withMd5` |
| `read_files` | 一次读多个 | `paths` **必填**、`startLine`、`endLine`、`withMd5` |
| `write_file` | 写文本(自动建父目录),**同步刷新已打开标签**;`mode`=overwrite/replaceLines/append | `path`、`content` **必填**、`mode`、`startLine`、`endLine`、`expectedMd5`、`expectedLines` |
| `create_file` | 新建(默认不覆盖) | `path` **必填**、`content`、`overwrite` |
| `delete_file` | 删文件或**空**目录 | `path` **必填** |
| `copy_file` | 复制文件/目录到当前项目内 | `source` **必填**、`target`、`overwrite`、`recursive` |
| `rename_file` | 重命名/移动 | `from`、`to` **必填**、`overwrite` |
| `make_directory` | 递归建目录 | `path` **必填** |
| `file_info` | 大小/时间/行数/MD5 | `path` **必填** |
| `search_in_files` | 文本或正则搜索 | `path`、`query` **必填**、`regex`、`ignoreCase`、`maxResults` |
| `replace_in_file` | 单文件替换 | `path`、`find`、`replace` **必填**、`regex`、`ignoreCase`、`expectCount` |
| `replace_in_files` | 批量替换(可 `dryRun`) | `path`、`find`、`replace` **必填**、`dryRun` |
| `get_project_info` | 项目信息(settings.json、文件数、图标) | `path` |
| `backup_project` / `restore_backup` | 备份 / 还原 | `backupPath` **必填**(还原) |
| `list_templates` / `create_project` | 模板 / 建项目 | `name`、`packageName`、`template`、`debugMode`、`globalUtils` |
| `analyze_imports` | 分析所需 import | `content` |
| `list_widgets` | 列内置控件(短名/分类/可写属性) | — |
| `list_project_libraries` | 列 `lib/<abi>/*.so`、`libs/*.dex\|jar` 与设备 ABI | `path` |
| `list_lib_classes` | 列 `libs/` 内编译出的类 | `path`、`lib`、`filter`、`className`、`page`、`maxResults` |
| `invoke_lib_method` | 反射调用 `libs/` 内类的静态方法/构造器(仅标量参数) | `className` **必填**、`method`、`args`、`constructor`、`page`、`path`、`timeoutMs` |
| `list_dev_docs` | 列随 IDE 打包的开发文档,含推荐阅读顺序 | — |
| `read_dev_doc` | **读开发文档(写代码前优先调用)**;支持按行分段 | `name`、`startLine`、`endLine`、`maxChars` |

**编辑器**

`get_editor_state`、`open_file`(`path`、`line`)、`set_editor_content`(`content` **必填**)、
`insert_text`(`text` **必填**)、`get_selection`、`goto_line`(`line` **必填**)、
`refresh_editor`、`editor_history`(`action`=undo/redo)、`save_files`、`format_code`、
`check_syntax`(`path`/`content`)、`get_settings`

**构建与运行**

`compile_file`(`path`、`keepOutput`)、`clean_compiled`、`build_apk`(`path`)、
`run_project`(`path`、`waitMs`、`capture`、`stop`、`clearLogs`、`captureLines`、
`returnToEditor`、`reuse`)、`stop_project`(`page`/`path`、`returnToEditor`、`timeoutMs`)、
`install_apk`(`path` **必填**)

**运行时界面检查**

| 工具 | 说明 | 关键参数 |
|---|---|---|
| `dump_screen` | 导出控件树 | `includeInvisible`、`maxDepth` |
| `capture_screen` | **截屏落盘 PNG**。控件树只能证明"控件在",截屏才能证明"真的画出来了"。走 `View.draw(Canvas)` 软件渲染,无需悬浮窗/MediaProjection | `maxWidth`、`inline`、`path` |
| `check_screen` | 按预期校验界面 | `expectTexts`、`expectAnyOf`、`absentTexts`、`minViews` |
| `wait_for_text` | 轮询等待文本出现/消失 | `text` **必填**、`absent`、`timeoutMs`、`intervalMs` |
| `get_runtime_errors` | 读日志中的运行时错误 | `lines` |
| `clear_logs` | 清空 `luaforge.log` | — |
| `get_logs` | 读日志原始文本尾部 | `lines` |
| `get_editor_logs` | **把 `luaforge.log` 解析成结构化条目**,可精确按级别/tag/关键字过滤 | `level`、`tag`、`query`、`limit` |
| `list_build_logs` | 列每次构建的独立日志 | `limit` |
| `read_build_log` | 读一次构建的完整过程日志(逐步耗时 + 失败堆栈) | `path`、`maxChars` |

**global_utils**

| 工具 | 说明 | 关键参数 |
|---|---|---|
| `list_global_utils` | 看项目 `global_utils` 配置及**运行时会实际注册**的全局函数 | `path` |
| `call_global_util` | 在**运行中**的项目里调用 `global_utils` 注册的全局函数 | `name` **必填**、`args`、`page`、`path`、`timeoutMs` |

**调试浮窗**

| 工具 | 说明 | 关键参数 |
|---|---|---|
| `debugger_status` | 浮窗状态:页面 / 项目 / 是否注入 / 缓冲条数 | `page`、`path` |
| `debugger_outputs` | 读浮窗缓冲(print / Lua 报错) | `limit`、`keyword`、`page`、`path` |
| `debugger_clear` | 清空浮窗缓冲 | `page`、`path` |

> 调试运行(debugmode 项目)时 `assets/debugger.lua` 注入页面并接管全局 `print` / `onError`。
> 读的是运行中 LuaState 暴露的只读全局 `__lfDebugger`(内存缓冲),与浮窗展示同源,
> 区别于 `get_logs` / `get_runtime_errors` 读的磁盘文本。

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

`check_screen` 的文本匹配是**子串**匹配:`expectTexts` 需全部出现,`expectAnyOf`
命中其一,`absentTexts` 必须都不出现。未通过时返回
`{"passed": false, "isError": true, "failures": [...], "detectedErrorMarkers": [...]}`。
`detectedErrorMarkers` 会自动识别 `runtime error`、`is not a field`、
`layout loading error`、`traceback` 等错误弹窗特征 —— **这是判断"代码真跑起来了"的关键信号**。

**自绘内容必须截图**

```text
run_project
wait_for_text text="..."
capture_screen inline=true     # 像素才是自绘的唯一证据
```

**一次性运行取信息(推荐)**

```text
run_project stop=true waitMs=2000 capture="debugger,errors" clearLogs=true
# → 启动后等 2s,抓调试浮窗缓冲 + 日志错误,然后结束运行并退回编辑界面
# 返回: { started, entry, output:{debugger,errorCount,errors,...}, stopped, returnedToEditor }
```

- `capture` 取值:`debugger`(需 debugmode=true)/ `errors` / `log`。
- `stop=true` 时默认 `waitMs=1500`。

**重复调用的语义**

`run_project` 对**已在运行**的项目默认是「先结束旧实例、再启动新实例」,始终只保留
一个运行页面 —— 这样才保证本次会话重新走 `onSessionStart`,调试浮窗按**本次**注入。

- 改了代码直接再调一次 `run_project` 即可,不需要先 `stop_project`;
- 确实只想"贴近已跑着的实例、不重跑脚本"时传 `reuse=true`(此时返回 `reused=true`,
  改了代码看不到效果);
- 返回字段:`reused`、`restarted`。

**出包**

```text
build_apk                     # 构建并签名,返回输出路径
install_apk path="<上一步输出>"
```

### 3.4 路径与安全边界

MCP 只允许访问这些根:

- 可配置的项目根:`settings.projectStoragePath`;未配置时 `getExternalFilesDir(null)/projects`
- `/storage/emulated/0/LuaForge-Studio`(含日志、崩溃记录)
- 其下 `build`、`backup`

路径经 `canonicalPath` 归一化后做前缀匹配,空值与文件系统根 `/` 被过滤。

**日志与崩溃记录**

| 内容 | 路径 | 回退 |
|---|---|---|
| 运行/IDE 日志 | `/storage/emulated/0/LuaForge-Studio/luaforge.log` | `filesDir/logs/luaforge.log` |
| 崩溃记录 | `/storage/emulated/0/LuaForge-Studio/crash/crash_*.log` | `filesDir/crash/` |
| 构建日志 | `/storage/emulated/0/LuaForge-Studio/buildlogs/` | `filesDir/buildlogs/` |

`get_logs` 读取上限 512KB,`read_file` 上限 2MB。超过 2MB 用 `read_file startLine/endLine`
按行读,配合 `file_info` 的 `md5`/`lines` 做完整性校验;写回用
`write_file mode=replaceLines` + `expectedMd5` 防并发覆盖。需要原文传 `read_file raw=true`。

### 3.5 已知限制

- 服务为**进程内实现**;应用被杀即停(除非开前台保活)。
- 编辑器操作需主线程;`open_file` 依赖 API 34+ 的编辑器实现。
- `check_screen` 只看当前前台 Activity;界面未起会返回"没有前台 Activity"。
- 单次 `dump_screen` 最多 3000 节点,超出截断并置 `truncated=true`。
- `resolveProjectPath`:未传 `path` 且未打开项目时,仅当项目根**只有一个子目录**才自动选中。
- `delete_file` 只能删空目录;递归删除需客户端先列目录再逐个删。
- `run_project stop=true` 依赖实例能从运行表移除(按**对象身份**判断);脚本卡死时
  `stop_project` 的 `timeoutMs` 到点仍会尝试回到编辑界面。
- `capture=debugger` 只对 debugmode 项目有效。

---

## 4. 端到端:让 AI 改一个 Lua 项目

1. `list_projects` 确认目标;`list_files` 看结构,确认存在 `main.lua` + `settings.json`。
2. `read_file` 读 `main.lua` 与 `layout.aly`,**先看懂再改**。
3. 用 `write_file` / `replace_in_file` 改(单点改动用 `replace_in_file` + `expectCount=1` 更安全)。
4. `check_syntax` → `compile_file keepOutput=false`。
5. `clear_logs` → `run_project` → `wait_for_text` → `check_screen`(自绘加 `capture_screen`)。
6. 失败读 `get_runtime_errors` 或 `get_editor_logs level=ERROR`。常见:
   - `is not a field` → 属性/方法名错
   - `layout loading error` → 布局属性错
   - `attempt to index a nil value` → 忘 `import` 或 `id` 不存在
   - `onDraw: ...` → `LuaDrawable` 回调里出错
7. 需要出包再 `build_apk`。

**`check_syntax` 通过 ≠ 跑得起来**:布局事件字符串、缺失的 `import`、拼错的 `id`
都只在运行时暴露,所以第 5 步不能省。

---

## 5. 事实来源

- Lua 运行时与回调:`core/src/main/java/com/androlua/LuaActivity.java`
  (`initLua` 1397-1500、回调 214-944、`initENV` 1575-1610、`sendError` 1861-1880)、
  `app/src/main/jni/lua/lua.h`(版本宏/签名)、`core/src/main/java/com/luajava/LuaState.java`
- 语言扩展:`app/src/main/jni/lua/llex.h`、`llex.c`、`lparser.c`
- Lua 库:`core/src/main/resources/lua/{import,loadlayout,loadmenu,loadbitmap,Colors,memory}.lua`、
  `app/src/main/assets/layouthelper/loadlayout2.lua`
- Java 桥接:`app/src/main/jni/luajava/luajava.c`(void 返回 self、`ljlib` 函数表、共享元表)、
  `core/src/main/java/com/luajava/LuaJavaAPI.java`(setter/getter 解析、`javaOverride`)
- 内置控件:`core/src/main/kotlin/com/luaforge/studio/widget/BuiltinWidgets.kt`、
  `widget/textfield/MaterialTextField.kt`
- 模板:`app/src/main/assets/templates/*.zip`、
  `app/src/main/kotlin/com/luaforge/studio/ui/project/NewProjectScreen.kt`
- 自绘:`core/src/main/java/com/androlua/LuaDrawable.java`、`app/src/main/jni/canvas/canvas.c`
- 权限自动申请:`core/src/main/java/com/androlua/LuaActivity.java`
  (`REQUEST_CODE_AUTO_PERMISSIONS`、`requestDeclaredPermissions`、`isRuntimePermission`)、
  `app/src/main/kotlin/com/luaforge/studio/ui/attribute/AttributeScreen.kt`
- MCP:`app/src/main/kotlin/com/luaforge/studio/mcp/{McpServer,McpManager,McpTools}.kt`、`devdoc/MCP.md`
- 路径与日志:`app/src/main/kotlin/com/luaforge/studio/utils/{FileUtil,LogConfig,ProjectUtil}.kt`、
  `app/src/main/kotlin/com/luaforge/studio/ui/crash/{CrashRecorder,CrashStore}.kt`
- 网络:`core/src/main/java/com/androlua/Http.java`、`core/src/main/kotlin/com/luaforge/studio/utils/OkHttpUtil.kt`

> 本文由源码逐条核实生成。凡"仓库内无用例""宏不自洽""死代码"等不确定项均已显式标注,
> 请勿凭印象替换为更"标准"的说法。
