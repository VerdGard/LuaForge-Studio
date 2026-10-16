# MaterialTextField

Material 文本输入框(短名 `MaterialTextField`),IDE 随附的内置控件。

内部是 `TextInputLayout`(OutlinedBox 风格)+ `TextInputEditText`,自带浮动标签、
错误提示、辅助说明、起始图标与尾部图标。比裸 `EditText` 更省事,推荐优先使用。

---

## 一、布局

```lua
{
  MaterialTextField,
  id = "field",
  layout_width = "fill",
  layout_height = "wrap",
  hint = "用户名",          -- 浮动标签
  singleLine = true,        -- 单行
  textSize = 16,            -- 字号(sp)
  boxCornerRadii = 16,      -- 圆角半径(px/dp 由控件按 px 处理)
  endIconMode = 0,          -- 尾部图标模式(TextInputLayout.EndIconMode.*)
}
```

属性名 = 去掉 `set` 前缀、首字母小写(`setHelperText` → `helperText`)。

---

## 二、属性

| 属性 | 类型 | 说明 |
| --- | --- | --- |
| `hint` | String | 浮动标签文本 |
| `text` | String | 输入内容 |
| `error` | String | 错误提示(显示即进入错误态) |
| `helperText` | String | 底部辅助说明 |
| `singleLine` | Boolean | 是否单行 |
| `textSize` | Float | 字号(sp) |
| `boxCornerRadii` | Float | 四角圆角半径 |
| `endIconMode` | Int | 尾部图标模式(`TextInputLayout` 的 `END_ICON_*`) |

---

## 三、方法

| 方法 | 说明 |
| --- | --- |
| `setText(str)` / `getText()` | 设置 / 读取文本 |
| `setHint(str)` | 设置标签 |
| `setError(str)` / `setErrorEnabled(bool)` | 设置错误提示 / 启用错误态 |
| `setHelperText(str)` | 设置辅助说明 |
| `setTextSize(float)` | 字号(sp) |
| `setSingleLine(bool)` | 单行 |
| `setSelection(start, end)` | 设定选区 |
| `setBoxCornerRadii(float)` | 圆角半径 |
| `setStartIconDrawable(drawable 或 resId)` | 起始图标 |
| `setStartIconTintList(color)` | 起始图标着色 |
| `setEndIconMode(int)` | 尾部图标模式 |
| `addTextChangedListener(watcher)` | 监听文本变化 |
| `removeTextChangedListener(watcher)` | 移除监听 |
| `getEditText()` / `getLayout()` | 取内部 `TextInputEditText` / `TextInputLayout` |

> 该控件是 `FrameLayout` 子类,焦点与 `clearFocus` 会转发给内部输入框。

---

## 四、完整示例

```lua
require "import"
import "android.widget.*"
import "androidx.appcompat.widget.LinearLayoutCompat"
import "com.google.android.material.button.MaterialButton"
import "com.google.android.material.textfield.TextInputLayout"

local layout = {
  LinearLayoutCompat,
  orientation = "vertical",
  layout_width = "fill",
  layout_height = "fill",
  padding = "16dp",
  {
    MaterialTextField,
    id = "field",
    layout_width = "fill",
    layout_height = "wrap",
    hint = "用户名",
    singleLine = true,
    boxCornerRadii = 16,
    endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT,  -- 尾部清除按钮
  },
  {
    MaterialButton,
    id = "btn",
    text = "提交",
    layout_width = "wrap",
    layout_height = "wrap",
    layout_marginTop = "12dp",
    onClick = function()
      local v = field.getText()
      if v == "" then
        field.setError("不能为空")
      else
        field.setError(nil)
        field.setHelperText("已提交:" .. v)
      end
    end,
  },
}

activity.setContentView(loadlayout(layout))

-- 监听文本变化:用「接口类{ 方法表 }」语法生成 Java 代理(与模板里监听器写法一致)
import "android.text.TextWatcher"
field.addTextChangedListener(TextWatcher{
  beforeTextChanged = function(s, start, count, after) end,
  onTextChanged = function(s, start, before, count) end,
  afterTextChanged = function(s)
    print("输入:" .. tostring(s))
  end,
})
```
