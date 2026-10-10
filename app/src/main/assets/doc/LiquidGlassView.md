# LiquidGlassView 液态玻璃控件

基于 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) 的 `backdrop`
效果封装的 Android 控件,给布局提供「磨砂玻璃」质感(鲜艳度 + 模糊 + 圆角)。

> **效果语义**:玻璃层的内容由控件**自绘**(底色 + 彩色斑块),再经 `vibrancy()` + `blur()`
> 处理。它是"半透明磨砂面板",**不是**对控件身后真实画面的实时采样/模糊(那需要整屏
> Compose 才能实现)。`glassColor` 带 alpha 时身后内容会透过面板显现,但不会被模糊。

## 为什么是 View 而不是 Compose 组件

`loadlayout` 与布局助手都通过 `luajava` 绑定 **Android View 子类**(并要求 `View.isAssignableFrom`)。
backdrop 库只提供 Compose 侧的 `Modifier.drawBackdrop(...)`,因此本控件用 `ComposeView`
把 Compose 效果包成 `FrameLayout`,从而能像普通控件一样写进布局表。

## 基本用法

```lua
{
  LiquidGlassView,
  id = "glass",
  layout_width = "match_parent",
  layout_height = "120dp",
  cornerRadius = 28,
  blurRadius = 24,
  glassColor = 0x40FFFFFF,
}
```

## 属性

| 属性 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `cornerRadius` | number | 28 | 圆角半径(px) |
| `blurRadius` | number | 24 | 模糊半径(px) |
| `glassColor` | number | `0x40FFFFFF` | 玻璃底色(ARGB),可用 `"#40FFFFFF"` |

属性名即去掉 `set` 前缀、首字母小写的 setter(`setCornerRadius` → `cornerRadius`)。
也可在代码中直接调用 setter:`glass.setGlassColor(0x33FFFFFF)`。

## 示例

```lua
-- 一个磨砂玻璃卡片
{
  LiquidGlassView,
  id = "glass",
  layout_width = "match_parent",
  layout_height = "160dp",
  layout_margin = "20dp",
  cornerRadius = 48,
  blurRadius = 36,
  glassColor = 0x40FFFFFF,
}
```

完整可运行示例见新建项目模板 **LiquidGlass**(`assets/templates/LiquidGlass.zip`)。

## 兼容性

- 效果依赖 `RenderEffect` / `RuntimeShader`。Android 12(API 31)以下或低端设备上,
  库内部会**自行降级**(跳过对应效果),不会抛错崩溃。
- 控件本身是普通 `FrameLayout`,子视图可通过布局表的数字下标元素添加。

## 完整类名

`com.luaforge.studio.widget.glass.LiquidGlassView`(短名 `LiquidGlassView`)。
