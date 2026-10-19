# 在 Lua 里写 Compose

LuaForge-Studio 提供一套 **Lua 原生 Compose DSL**:界面用 Lua 直接描述,
由固定的一组 `@Composable` 展开器放进 Compose 自己的 SlotTable。
**不是**"先把 Lua 翻译成 Kotlin 再调用 Compose"——没有代码生成、没有转译中间层。

---

## 0. 先搞清楚它是什么、不是什么

| | 说明 |
|---|---|
| ✅ 是真的 Compose | 节点最终落在 Compose 的 SlotTable 里,`state.value` 的读取会被快照系统记录,状态变化**按 group 精确重组** |
| ✅ 写法是 Lua | 用 Lua 的表、闭包、`if`/`for` 描述界面,不需要写 Kotlin |
| ❌ 不是"调用 @Composable 函数" | `@Composable` 函数编译后签名被改写(注入 `Composer`/`$changed`),Lua 侧无法直接调用 |
| ❌ 不做自动合并 | 状态变更后整段 content 闭包会重新执行(与手写 Compose 相同),请保持调用顺序稳定 |

> **关键约束(与手写 Compose 一致)**:同一 group 内每次组合的**组件调用顺序必须稳定**。
> 不要在 `Column{...}` 里"有时调用 A 有时不调用 A 且没有 key",那会让位置记忆化错位。

---

## 1. 最小例子

```lua
require "import"

local count = state(0)

local view = compose(function()
  Column({ padding = 16, gap = 12, fillMaxWidth = true, content = function()
    Text("计数:" .. count.value, size = 20, bold = true)
    Text("点下面的按钮加一", size = 13, color = "#888888")
    Button({
      text = "+1",
      onClick = function() count.value = count.value + 1 end
    })
  end })
end)

activity.setContentView(view)
```

三种入口,按场景选:

| 函数 | 返回 | 用途 |
|---|---|---|
| `compose(fn)` | `ComposeView` | 自己挂载(`setContentView` / 塞进布局) |
| `composeView(fn)` | `ComposeView` | 同上,名字更明确 |
| `composeContent(fn)` | – | 直接 `activity.setContentView`(需 `Activity` Context) |

---

## 2. 状态:`state(v)` 与 `remember(fn)`

```lua
local s = state(0)        -- 可观察状态,初始值 0

s.value                   -- 读(组合期读 = 订阅)
s.value = 10              -- 写(触发重组)
s:get() / s:set(v)        -- 等价的方法形式
```

- **读写 `value` 会自动命中** LuaJava 的 getter/setter(`getValue`/`setValue`),
  所以 `s.value` 与 `s:get()` 都能用;写 `s.value = x` 会走 `setValue`。
- `remember(fn)` 按位置缓存:同一位置只在首次组合调用 `fn`,后续复用结果。
  适合缓存"只算一次"的对象(列表数据、回调闭包等)。

```lua
local items = remember(function() return loadData() end)
```

---

## 3. 组件一览

共 **65 个**。名字全部注册为全局函数,写短名即可。

### 3.1 布局容器

| 组件 | 主要属性 |
|---|---|
| `Column` / `Row` | `gap` `space` `align` `padding` `fillMax*` `background` `corner` |
| `Box` | `align`(九宫格,默认 `center`) |
| `FlowRow` / `FlowColumn` | `gap` `space`,超出宽度/高度自动换行 |
| `ScrollColumn` / `ScrollRow` | 内容超长时可滚动(`Column`/`Row` + `verticalScroll`/`horizontalScroll`) |
| `Card` / `ElevatedCard` / `OutlinedCard` | `corner` `padding` `background` |
| `Surface` | `corner`;`background` 缺省为主题 surface 色 |
| `Scaffold` | `topBar` `bottomBar` `fab`(传闭包)+ 内容闭包,内容自动避让系统栏 |

### 3.2 懒加载列表 / 网格

| 组件 | 主要属性 |
|---|---|
| `LazyColumn` / `LazyRow` | `items`(数组表)或 `count`(数量);`gap` `contentPadding` |
| `LazyVerticalGrid` / `LazyHorizontalGrid` | 同上 + `columns`(固定列数)/ `minSize`(自适应) |

content 闭包签名 `function(item, index) ... end`。

### 3.3 文本与图形

| 组件 | 主要属性 |
|---|---|
| `Text` | `text` `size` `bold` `color` `align` `maxLines` `overflow` |
| `Icon` | `icon`/`res`(drawable 名或资源 id) `color` `desc` |
| `Image` | `src`/`res` `scale`(`fit`/`crop`/`fillBounds`/`fillWidth`/`fillHeight`/`inside`/`none`) |
| `Spacer` | `width` `height` `size` |
| `Divider` / `VerticalDivider` | 通用修饰属性 |
| `CircularProgressIndicator` / `LinearProgressIndicator` | `color` |
| `Badge` | 内容闭包即徽标内容 |
| `BadgedBox` | `badge`(闭包)+ 内容闭包,角标叠在内容上 |

### 3.4 按钮族

| 组件 | 说明 |
|---|---|
| `Button` `ElevatedButton` `FilledTonalButton` `OutlinedButton` `TextButton` | 五种按钮样式 |
| `IconButton` `FilledIconButton` `OutlinedIconButton` | 图标按钮 |
| `FloatingActionButton` `SmallFloatingActionButton` `LargeFloatingActionButton` | FAB |
| `ExtendedFloatingActionButton` | 带文字的 FAB |

公共属性:`onClick` `enabled` `background` `corner`;内容用闭包或 `text`。

### 3.5 选择与输入

| 组件 | 主要属性 |
|---|---|
| `Switch` / `Checkbox` | `checked` `onChange(v)` `enabled` |
| `RadioButton` | `checked` `onClick` |
| `Slider` | `value` `min` `max` `onChange(v)` |
| `RangeSlider` | `state = rangeState(a, b)` 或 `start`/`end`;`onChange(lo, hi)` |
| `TextField` / `OutlinedTextField` | `text` `label` `hint`/`placeholder` `singleLine` `error` `onChange(v)` |

> `TextField` 是**填充式**,`OutlinedTextField` 是**描边式** —— 两者是不同组件,不是别名。

### 3.6 Chip

`AssistChip` `SuggestionChip`(点击型)、`FilterChip` `InputChip`(带 `selected` 开关)。

### 3.7 导航

| 组件 | 主要属性 |
|---|---|
| `TopAppBar` / `CenterAlignedTopAppBar` / `MediumTopAppBar` / `LargeTopAppBar` | `title` |
| `BottomAppBar` / `NavigationBar` / `NavigationRail` | 内容闭包 |
| `NavigationBarItem` / `NavigationRailItem` | `selected` `onClick` `icon` `label` `enabled` |
| `TabRow` / `ScrollableTabRow` | `tabs`(字符串数组或 `{title=...}`)`selected` `onSelect(i)` |
| `Tab` | `selected` `onClick` `text` |

### 3.8 弹窗

| 组件 | 主要属性 |
|---|---|
| `AlertDialog` | `show` `title` `text`(或内容闭包)`confirm`(闭包)`dismiss`(闭包)`onConfirm` `onDismiss` |
| `Dialog` | `show` `onDismiss` `dismissOnBack` `dismissOnClickOutside` `usePlatformDefaultWidth` `background` `corner` |

`show` 缺省为 `true`;控制它来开关弹窗。

```lua
local showDlg = state(false)

Column({ content = function()
  Button({ text = "打开", onClick = function() showDlg.value = true end })
  AlertDialog({
    show = showDlg.value,
    title = "确认",
    text = "确定要删除吗?",
    onConfirm = function() doDelete(); showDlg.value = false end,
    onDismiss = function() showDlg.value = false end
  })
end })
```

### 3.9 原生 View 互操作 —— 用上现有全部控件

`AndroidView` 把**任意现有 Android 控件**塞进 Compose 树。这是"Compose 里用
`BottomNavigationView` / `TabLayout` / `RecyclerView` / `ExoVideoView` / `MaterialTextField` /
`WebView`"的入口。

```lua
require "import"
import "androidx.recyclerview.widget.RecyclerView"

local rv = RecyclerView(activity)          -- 照旧用 Java 控件
rv.setAdapter(myAdapter)

Column({ padding = 16, content = function()
  Text("下面是一个原生 RecyclerView")
  AndroidView({ view = rv, fillMaxWidth = true, height = 300 })
end })
```

| 属性 | 说明 |
|---|---|
| `view` | 直接给一个已有 View 实例(最常用) |
| `factory` | 闭包,返回 View;`view` 为空时才用 |
| `onUpdate` | 闭包,收到 View,每次重组调用(改数据/状态用) |

> `AndroidView` 里滚动手势交给原生控件,和 Compose 的嵌套滚动**不会**自动打通
> (与手写 Compose 的限制相同)。列表优先用 `LazyColumn`,确需原生控件时再嵌。

### 3.10 副作用

`LaunchedEffect` —— `key` + 内容闭包,首次进入(或 `key` 变化)时在协程里执行一次。
**里面不能登记节点**,只做数据加载/订阅。

---

### 3.11 全局名与 `import` 的冲突

组件名是**全局函数**。`import "android.widget.*"` 这种通配导入只在键不存在时才回填,
**不会**覆盖 `Button` / `Switch` / `Card` 等。

但**显式** `import "android.widget.Button"` 会直接覆盖同名全局 —— 此时用命名空间表:

```lua
Compose.Text("用 Compose 的 Text,不受 import 影响")
Compose.Button({ text = "确定", onClick = fn })
Compose.state(0)
```

`Compose` 表里包含全部 65 个组件 + `compose` / `composeView` / `composeContent` /
`state` / `rangeState` / `remember`,与全局名**是同一份实现**。

---

### 3.12 属性名的宽容度

`gap`/`space`/`align`/`color`/`overflow`/`scale` 等取值对大小写、首尾空白不敏感
(内部会 `lowercase().trim()`),`align = "center"` 与 `align = "Center"` 等价。
未知取值一律回落到默认值,**不会抛异常**。

## 4. 属性速查

### 间距与尺寸(单位 dp,数字即可)

```lua
padding = 16                    -- 四边
paddingH = 12, paddingV = 8     -- 水平/垂直(也接受 paddingHorizontal / paddingVertical)
gap = 8                         -- 子项间距(Row/Column/LazyColumn/FlowRow)
contentPadding = 16             -- 列表内边距,也可 contentPaddingH / contentPaddingV
width = 120, height = 40, size = 40
fillMaxWidth = true             -- 可选 fillMaxWidthFraction = 0.5
fillMaxHeight = true            -- 可选 fillMaxHeightFraction = 0.5
fillMaxSize = true
aspectRatio = 1.5               -- 宽高比
```

> **`size` 有两种含义,取决于用在哪个组件上:**
>
> - **尺寸(dp)**:`Spacer` `Box` `Image` `Icon` 等 —— 限制**控件本身**的大小;
> - **字号(sp)**:`Text`(`Icon` 无图片资源而回退成文字时同理)—— 只影响**文字大小**,
>   不会给控件加尺寸约束。
>
> 也就是说 `Text("hi", size = 20)` 的字号是 20sp、控件宽高仍由内容撑开;
> 而 `Spacer({ size = 20 })` 是一块 20dp × 20dp 的空隙。
> 若给 `Text` 同时限宽,请用 `width` / `maxLines` / `overflow`,不要指望 `size`。

### 视觉

```lua
corner = 12                     -- 圆角
background = "#F5F5F5"          -- 背景色
borderWidth = 1, borderColor = "#DDDDDD"   -- 描边(不给 borderWidth 时为 1dp)
shadow = 4                      -- 阴影高度
alpha = 0.6                     -- 透明度 0..1
offsetX = 0, offsetY = -8       -- 相对位移
```

> 处理顺序是**尺寸 → 位移 → 圆角/背景/描边/阴影/透明 → 间距 → 点击**。
> 背景和圆角在 `padding` 之前,所以背景会**铺满含内边距的整个区域**;
> 若顺序反过来,边缘会露白。

### 弹性权重(仅 Row / Column / ScrollColumn / ScrollRow 的子项)

```lua
Row({ fillMaxWidth = true, content = function()
  Text("固定宽度", { width = 80 })
  Text("占满剩余", { weight = 1, align = "center" })
end })
```

### 点击

任何组件加 `onClick = fn` 即可点击;若该组件本身有点击语义(`Button` 等),
组件自己的 `onClick` 优先,`Modifier` 的点击可显式关掉:`clickableOff = true`。

### 排列(`space`)

- `Row`:`start`(默认) / `center` / `end` / `between` / `around` / `evenly`
- `Column`:`top`(默认) / `center` / `bottom` / `between` / `around` / `evenly`

给了 `gap` 同时又给 `space` 时,`space` 优先。

### 对齐(`align`)

- `Column` → 水平:`start`(默认) / `center` / `end`(=`right`)
- `Row` → 垂直:`top`(默认) / `center` / `bottom`
- `Box` → 九宫格:`topStart` `topCenter` `topEnd` `centerStart` `center`(默认) `centerEnd` `bottomStart` `bottomCenter` `bottomEnd`
- `Text` → 文字对齐:`start`/`left` `center` `end`/`right`

### 颜色(两种写法)

```lua
color = 0xFFFF5722              -- Lua 数字 0xAARRGGBB
color = "#FF5722"               -- "#RRGGBB",自动补不透明
color = "#80FF5722"             -- "#AARRGGBB",带透明度
background = "#F5F5F5"
```

### 字体(仅 `Text` 及回退成文字的 `Icon`)

```lua
size = 18         -- sp,字号
bold = true
maxLines = 2
overflow = "ellipsis"   -- 或 "clip"(默认)
```

---

## 5. 内容闭包的两种写法

```lua
-- 1 表 + content 字段(推荐,属性与内容都清晰)
Column({ padding = 16, content = function()
  Text("hi")
end })

-- 2 直接传函数(等价,更短)
Column(function()
  Text("hi")
end)
```

`Text("hi")` 也能直接吃字符串:`collect` 会把非表非函数的第一个参数当作 `text`。

---

## 6. 列表

```lua
local data = { "苹果", "香蕉", "橙子" }

LazyColumn({ items = data, gap = 8, fillMaxWidth = true, content = function(item, index)
  Card({ fillMaxWidth = true, corner = 8, content = function()
    Text(index .. ". " .. item, padding = 12)
  end })
end })
```

`items` 必须是 Lua 数组表(连续整数键)。每个 item 有独立的槽位,
`item`/`index` 会作为参数传给 content 闭包。

---

## 7. 条件与循环

每次组合都会重新执行 content 闭包,所以**直接用 Lua 控制流**即可:

```lua
Column({ gap = 8, content = function()
  if count.value > 0 then
    Text("正数")
  else
    Text("非正数")
  end

  for i = 1, 3 do
    Text("第 " .. i .. " 行")   -- 注意:顺序稳定,行数固定才安全
  end
end })
```

> **行数会变**的列表请用 `LazyColumn` + `items`,而不是 `for` 循环——
> 位置记忆化要求同一 group 的调用序列稳定。

---

## 8. 副作用

```lua
LaunchedEffect({ key = "load", content = function()
  -- 首次进入(key 变化时重跑一次)
  count.value = fetchRemoteCount()
end })
```

`content` 闭包在协程里执行,**不能**在里面登记节点(它不是组合期)。
只做数据加载、网络请求、订阅等。

---

## 9. 发行方式(仅内置桥)

`LuaCompose` 随 `core` 一起编译进 `core.apk`,项目**开箱可用,无需任何配置**:
不用放 dex、不用写 `import`、不用改 `settings.json`。

### 为什么没有「项目自带 dex」的外部桥

早期版本还有一套「方案 C」:把 Compose 运行时 + 桥单独打成
`libs/compose_bridge.dex` 放进项目,配合 `-PlfComposeBundled=false` 的零体积 core 发行版,
让不用 Compose 的项目省下约 1.5MB dex。已移除,原因:

1. **两套 Compose 运行时不能混用。** 外部 dex 若带不同版本的 `androidx.compose.*`,
   ART 可能随机命中其中一个,症状是 `NoSuchMethodError` / `NoClassDefFoundError`
   这类只在真机偶发、极难定位的崩溃。「内置优先」只是绕开,不是解决。
2. **成本错位。** 维护代价落在每个用户项目上(每个项目都得同步一份 dex,升级 Compose 时还会对不上),
   换来的只是未用 Compose 的项目省 1.5MB。
3. **探测不可靠。** 「项目是否真用 Compose」只能靠扫源码猜,猜错的代价是运行时函数不存在。

因此现在只有一种形态:桥在模板里,`compose` / `composeView` / `composeContent` /
`state` / `remember` 等全局函数在 `LuaActivity.initLua` 时注册。

### 失败隔离

`register` 内部要建 60 多个组件工厂,任何一环抛异常都不应拖垮页面启动。
所以注册过程整体包在 try-catch 里:失败时只记日志、不注册任何函数,**绝不抛异常**。
如果你发现 `compose` 是 `nil`,先看 `luaforge.log` 里的注册失败原因。

### 源码位置

桥在独立源集 `core/src/compose/kotlin`,与 `core/src/main/kotlin` 分开只是为了
让「这批文件依赖 Compose 编译器插件」这件事更明显,**不是**可选开关 —— 它总是编入。

---

## 10. 常见问题

**Q:报 `未支持的组件: Xxx`?**
A:组件名拼错或该组件尚未封装。见 §3 列表;`else` 分支会渲染一条红色提示文本。

**Q:`s.value` 读到的是 `nil`?**
A:`state(0)` 的初始值必须是 Lua 能转成 Java 对象的值。表/函数请先用 `remember` 建好后放进 state。

**Q:改了 state 但不刷新?**
A:确认写的是 `s.value = x`(或 `s:set(x)`),并且**在组合期读过**这个 state。
只在事件回调里写、组合期从不读的 state 不会建立订阅。

**Q:组件顺序为什么会影响显示?**
A:位置记忆化按调用顺序分配槽位(与手写 Compose 相同)。不要在同一 group 里
"动态增减不带 key 的兄弟组件",用 `LazyColumn` 或固定结构。

**Q:Compose 会不会让我的 APK 变大?**
A:会,Compose 运行时约 1.5MB dex,且**无条件**随 core 一起打包 —— 没有「零体积」开关。
这是有意的取舍:一套运行时永远比「按项目切换两套」可靠。

**Q:`size` 属性到底是 dp 还是 sp?**
A:看组件。`Spacer` / `Box` / `Image` 上是**尺寸(dp)**,`Text` / `Icon`(回退成文字时)上是**字号(sp)**。
早期版本无条件按 dp 处理,于是 `Text("x", size = 20)` 会被同时加上 `.size(20.dp)` 和
`fontSize = 20.sp`,文字挤进 20dp 方块里换行截断,看起来就是「布局显示不全」。

**Q:内容被状态栏压住 / 顶部按钮点不到?**
A:API 35+ 系统强制 edge-to-edge,而 `composeContent` / `setContentView` 会替换掉
`LuaActivity` 自己那个挂了 inset 监听的根布局。桥现在自己在 ComposeView 上装 inset 监听
(仅 API ≥ 35 且父容器是 `android.R.id.content` 时生效)。如果你的布局是手动塞进别的容器,
需要自己调 `ViewCompat.setOnApplyWindowInsetsListener`。
