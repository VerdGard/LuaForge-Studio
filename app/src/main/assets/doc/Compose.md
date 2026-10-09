# Jetpack Compose

在 Lua 里用 **table 描述 UI 树**,由 Kotlin 桥渲染为真正的 Material3 组件。

> 为什么不是直接调用 `@Composable`:Compose 的 `@Composable` 函数会被编译器改写签名
> (额外注入 `Composer` 参数),必须在 composition 上下文中执行,Lua 无法直接调用。
> 因此采用「Lua 描述树 → Kotlin 渲染」的形态。

## 全局函数

| 函数 | 说明 |
| --- | --- |
| `compose(tree)` | 返回一个可挂载的 `ComposeView`(可交给 `loadlayout` / `setContentView`) |
| `composeContent(tree)` | 直接把 `tree` 渲染并设为当前页面内容视图(等价于 `activity.setContentView(view)`) |

## 节点格式

每个节点是一个 table:

```lua
{ "标签", <可选位置文本>, 属性 = 值, ... , <子节点 table>, ... }
```

- `[1]` 必须是标签字符串。
- 位置文本(第二个位置参数)只对 `Text` / `Button` 有意义,等价于 `text = "...")`。
- 其余位置参数若不是字符串,会被当作子节点递归解析。

### 支持的标签

| 标签 | 属性 | 说明 |
| --- | --- | --- |
| `Column` | `spacing` | 纵向排列;子节点按顺序渲染 |
| `Row` | `spacing` | 横向排列 |
| `Box` | — | 层叠容器 |
| `Card` | `corner`(圆角,默认 12) | Material3 圆角卡片 |
| `Text` | `text`/位置文本, `size`, `bold`, `color`, `align` | 文本 |
| `Button` | `text`/位置文本, `onClick` | 按钮;`onClick` 为 Lua 函数 |
| `Spacer` | `h`(高), `w`(宽) | 间距 |
| `Divider` | — | 分隔线 |

### 通用属性(所有标签可用)

| 属性 | 类型 | 说明 |
| --- | --- | --- |
| `padding` | number | 内边距(dp) |
| `fillMaxWidth` | boolean | 宽度撑满父容器 |
| `width` / `height` | number | 固定尺寸(dp) |
| `background` | number | 背景色 `0xAARRGGBB`(会按 `corner` 裁剪) |
| `corner` | number | 圆角半径(dp,用于 `background` / `Card`) |

### 颜色

用 Lua 十六进制数字,`0xAARRGGBB`:

```lua
color = 0xFF6650a4         -- 完全不透明的紫色
background = 0x22000000    -- 半透明黑
```

## 示例

```lua
composeContent({
  "Column",
  fillMaxWidth = true,
  padding = 20,
  spacing = 14,
  { "Text", "标题", size = 26, bold = true, align = "center", fillMaxWidth = true },
  { "Card", corner = 18, fillMaxWidth = true,
    { "Column", spacing = 10,
      { "Text", "卡片内容", size = 16, bold = true },
    },
  },
  { "Row", spacing = 12,
    { "Button", "点我", onClick = function ()
        print("clicked")
      end },
  },
  { "Divider" },
  { "Text", "提示", size = 12, color = 0xFF6650a4 },
})
```

只取视图对象自行挂载:

```lua
local view = compose({ "Text", "Hello Compose" })
activity.setContentView(view)
```

## 注意

- 在 UI 线程调用最稳妥;`composeContent` 已做线程切换。
- `onClick` 等回调在 UI 线程被调用,内部若更新 UI 无需再切线程。
- 组件集为「基础集」,后续可按需扩展标签与属性。
