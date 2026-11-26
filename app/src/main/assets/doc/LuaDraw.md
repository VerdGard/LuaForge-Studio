# LuaDraw — 用 Lua 自定义绘制

本文覆盖 LuaForge-Studio 里**两条**能"自己画"的通道,以及它们的边界:

| 通道 | 入口 | 适用 | 谁负责回调 |
| --- | --- | --- | --- |
| **A. LuaDrawable** | `LuaDrawable(function(canvas, paint, self) ... end)` | 给任意 View 当背景/前景/图标,内容随状态重绘 | Android 的 `Drawable.draw(Canvas)` |
| **B. canvas 原生模块** | `require "canvas"` | 直接拿一个 `Canvas` 或对一个 `SurfaceHolder` 加锁绘制 | 你自己 |

两条通道都能拿到 `android.graphics.Canvas`,因此在其中可用的**绘制原语是同一套**:
`Paint` / `Path` / `RectF` / `Bitmap` 全部经 luajava 直接调用,本文第 4 节把常用
`Canvas` 方法按用途列全,第 5 节给可直接运行的完整示例。

> 本文所有结论均对照仓库源码核实,出处见文末。标注"未实测"的部分请自行验证后再依赖。

---

## 1. 通道 A:LuaDrawable

### 1.1 它是什么

`com.androlua.LuaDrawable`(`core/src/main/java/com/androlua/LuaDrawable.java`)是一个
Android `Drawable`,把 `draw(Canvas)` 回调转发给 Lua 函数:

```java
public LuaDrawable(LuaFunction func) { ... }

@Override
public void draw(Canvas p1) {
    if (mOnDraw == null) {
        // 第一次绘制:把 canvas / paint / self 交给 Lua
        Object r = mDraw.call(p1, mPaint, this);
        // 若 Lua 返回了函数,记为"后续绘制回调"
        if (r != null && r instanceof LuaFunction) mOnDraw = (LuaFunction) r;
    }
    if (mOnDraw != null) mOnDraw.call(p1);
}
```

由此得到两条**必须记住**的语义:

1. **首次调用给三个参数**(`canvas`, `paint`, `self`),之后只给 `canvas`。
   所以「初始化一次、之后只重绘」的写法是:**首次返回一个函数**。
2. 首次调用**没有返回值**时,`mOnDraw` 保持 `null` —— 结果是**每帧都会重新
   走"首次"分支**,每次都给三个参数。若你的函数每次都返回 `nil`,它会被
   反复当初始化调用(不报错,但每帧都做一遍初始化,通常不是你想要的)。

最小可用形态:

```lua
require "import"
local LuaDrawable = luajava.bindClass "com.androlua.LuaDrawable"
local Paint = luajava.bindClass "android.graphics.Paint"

local view = ...
view.setBackground(LuaDrawable(function(canvas, paint, self)
  paint.setColor(0xFF6750A4)
  paint.setStyle(Paint.Style.FILL)
  canvas.drawCircle(100, 100, 60, paint)
end))
```

上面这种**不返回函数**的写法是合法的:每次绘制都会以三个参数重新调用,
`paint` 是同一个实例(`mPaint`),所以状态会延续,行为与"每帧重绘"一致。
只有在**需要缓存昂贵对象**(路径、Bitmap、渐变着色器)时才需要"返回函数"的写法。

### 1.2 返回函数:把初始化与重绘分开

```lua
local Path = luajava.bindClass "android.graphics.Path"
local LinearGradient = luajava.bindClass "android.graphics.LinearGradient"
local Shader = luajava.bindClass "android.graphics.Shader"

view.setBackground(LuaDrawable(function(canvas, paint, self)
  -- ===== 只执行一次:构造昂贵对象 =====
  local path = Path()
  path.moveTo(0, 200)
  path.cubicTo(100, 0, 300, 400, 400, 200)

  local gradient = LinearGradient(
    0, 0, 400, 0,
    0xFFFF565E, 0xFF6E81D9,
    Shader.TileMode.CLAMP
  )

  -- ===== 之后每次绘制只走这里 =====
  return function(c)
    paint.setShader(gradient)
    paint.setStyle(Paint.Style.STROKE)
    paint.setStrokeWidth(6)
    c.drawPath(path, paint)
    paint.setShader(nil)     -- 用完清掉,避免影响别的绘制
  end
end))
```

要点:

- **`self` 用不上就忽略**。它是 `LuaDrawable` 本身,`getPaint()` 能拿到同一个 `Paint`。
- 返回的函数**只收到 `canvas`**,`paint` 需从闭包捕获 —— 这是设计使然,
  不要指望第二个参数。
- 闭包捕获的 Lua 对象存活时间与 `Drawable` 一致;`Drawable` 被 GC 后闭包才会释放。

### 1.3 主动触发重绘

`Drawable` 不会自己知道数据变了。两种做法:

```lua
-- 方式一:改完数据后让宿主 View 重绘(最常用)
value = value + 1
view.invalidate()

-- 方式二:在 Lua 侧持有 Drawable 引用,调它的 invalidateSelf()
local d = LuaDrawable(function(canvas, paint)
  -- ...
end)
view.setBackground(d)
-- 之后
d.invalidateSelf()
```

`Drawable.invalidateSelf()` 只在**已设置 callback**(即已挂到 View 上)时才会真正
触发重绘;`view.invalidate()` 则总是有效。日常用方式一即可。

### 1.4 挂在哪些位置

| 位置 | 写法 | 注意 |
| --- | --- | --- |
| 背景 | `view.setBackground(d)` | 最常见;尺寸由 View 决定 |
| 前景 | `view.setForeground(d)` | 需 API 23+ |
| ImageView 内容 | `imageView.setImageDrawable(d)` | 需要 `setBounds` 才有尺寸,见下 |
| 菜单图标 | `menuItem.setIcon(d)` | 图标尺寸由菜单决定 |
| 布局属性 | `background = <Drawable 实例>` | `loadlayout` 的 `background` 支持直接传对象 |

`setImageDrawable` 场景要**手动**给边界 —— 这个位置没有 View 帮忙算尺寸,
不设的话 `bounds` 宽高为 0,什么也画不出来:

```lua
local d = LuaDrawable(function(canvas, paint, self)
  -- 作为 background/foreground 时,View 会按自身尺寸自动设好 bounds
  local b = self.getBounds()
  canvas.drawRect(b, paint)
end)
d.setBounds(0, 0, 200, 200)
imageView.setImageDrawable(d)
```

`self.getBounds()` 返回 `android.graphics.Rect`(`left/top/right/bottom`),
**永远以它为准**而不是硬编码尺寸 —— View 大小由布局决定。

### 1.5 尺寸驱动的自适应绘制

```lua
local RectF = luajava.bindClass "android.graphics.RectF"

local d = LuaDrawable(function(canvas, paint, self)
  local b = self.getBounds()
  local w, h = b.width(), b.height()
  paint.setStyle(Paint.Style.FILL)
  paint.setColor(0xFF3B82F6)
  -- 内切圆:半径取短边一半,画在中心
  canvas.drawCircle(b.centerX(), b.centerY(), math.min(w, h) / 2, paint)
  return nil    -- 故意不返回:每帧用最新 bounds 重算(见下方说明)
end)
```

> 这里故意"不返回函数":bounds 会随布局变化,**只在初始化时算一次会画错**。
> 每次都重算的代价是几十条字节码,远小于布局变化的频率。

### 1.6 错误处理

Lua 回调里抛错不会让应用崩溃,`LuaDrawable` 会走:

```java
catch (LuaException e) {
    mContext.sendError("onDraw", e);   // 落 luaforge.log + 走 onError
}
```

因此绘制代码出错时:**看日志,不要等崩溃**。MCP 的 `get_runtime_errors` 或
`get_editor_logs level=ERROR` 都能拿到 `onDraw` 这条。

注意 `catch` 只捕获 `LuaException`。经 luajava 调用 Java 抛出的运行时异常会被
包装成 `LuaException`,所以实际覆盖面足够;但**native 崩溃**(如越界写)`catch`
兜不住,会直接 SIGSEGV。

### 1.7 与 `require "canvas"` 的关系

`LuaDrawable` 拿到的是 Android 原生 `Canvas`(`android.graphics.Canvas`)。
`require "canvas"` 模块则是给这个 `Canvas` **补了一套 Lua 风格的函数表**
(`canvas.drawCircle(canvas, ...)` 这种"把 canvas 当第一个参数"的形式)。

两者可以混用:在 `LuaDrawable` 回调里直接调 `Canvas` 的 Java 方法即可,
不必绕 `require "canvas"`(它主要服务于 `SurfaceHolder` 那类手动加锁场景)。

---

## 2. 通道 B:`require "canvas"` 原生模块

### 2.1 加载

```lua
local canvas = require "canvas"
```

模块由 `app/src/main/jni/canvas/canvas.c` 编译成 `libcanvas.so`,随运行时一起打包,
`require` 直接命中。导出两类函数:

| 函数 | 作用 | 返回 | 可用性 |
| --- | --- | --- | --- |
| `canvas.lockCanvas(holder)` | 对 `SurfaceHolder` 加锁取一个自带方法表的 `Canvas` | 加锁后的 canvas | 可用;仅 SurfaceView 场景需要 |
| `canvas.unlockCanvasAndPost(holder, canvas)` | 提交并解锁 | — | 与 `lockCanvas` 成对使用 |
| `canvas.get(canvasObj)` | 试图给已存在的 Java 对象装方法表 | 原对象 | **不要用,见下** |

其余 `drawXxx` / `getXxx` / `save` / `restore` 等都是**方法**,挂在 canvas 对象上,
不是模块函数:

```lua
local c = canvas.lockCanvas(holder)
c.drawCircle(c, x, y, r, paint)   -- 注意:c 要作为第一个参数传回去
```

> ⚠️ 这是本模块最容易踩的坑:方法**不会自动带上 self**。所有函数签名都是
> `f(canvas, ...)`,手写时容易漏掉第一个 `c`。

#### 为什么不要用 `canvas.get`

`getCanvas()` 的实现(`canvas.c:2693` 起)是:

```c
lua_getmetatable(L, 1);          // 取参数 1 的元表
lua_pushstring(L, "__index");
lua_newtable(L);                 // 新建一张表,逐个塞进 50 多个绘制方法
...  lua_settable(L, -3);  ...
lua_rawset(L, -3);               // 元表.__index = 这张新表
```

它把**元表的 `__index` 直接替换成一张只含 canvas 方法的普通表**。而经 luajava
拿到的 Java 对象共用同一个元表(`JavaObject`),其 `__index` 原本是负责方法解析
的 C 函数 —— 调用过 `canvas.get` 之后,该 LuaState 里**所有 Java 对象的方法解析
都会被顶掉**,后续的 `view.setText(...)` / `paint.setColor(...)` 会变成
`attempt to call a nil value`。

所以:

- **要画东西,直接调 Java `Canvas` 的方法**:`android.graphics.Canvas` 本身就是
  luajava 对象,`canvas.drawCircle(x, y, r, paint)` 直接可用(见第 4 节),
  根本不需要本模块;
- 只有 `lockCanvas` 那条路是安全的 —— 它为自己的新 userdata **新建了一份独立元表**,
  不碰共享元表。

### 2.2 `lockCanvas` 的适用场景

`SurfaceHolder.lockCanvas()` 用于**SurfaceView** 式的手动绘制循环:

```lua
local canvas = require "canvas"
local Callback = luajava.bindClass "android.view.SurfaceHolder$Callback"

local holder = surfaceView.getHolder()
holder.addCallback(Callback {
  surfaceCreated = function(h)
    local c = canvas.lockCanvas(h)
    c.drawColor(c, 0xFF101010)
    canvas.unlockCanvasAndPost(h, c)
  end,
})
```

`lockCanvas` 返回的 canvas 已带方法表,可连续调用;`unlockCanvasAndPost` 必须
**成对调用**,否则下一次 `lockCanvas` 会抛 `IllegalStateException`。

> 手动 Surface 绘制的帧同步、尺寸变化、生命周期都要自己管;除非确实需要
> 高频重绘(游戏/示波器),否则优先用通道 A + `view.invalidate()`。

### 2.3 方法清单与参数个数(逐条)

下表从 `canvas.c` 的 `statement_num ==` 判断直接读出。
**"参数个数"含 canvas 自身**,即 `n` 表示调用时要传 `n` 个实参。

#### 绘制

| 方法 | 参数个数 | 签名 |
| --- | --- | --- |
| `drawARGB` | 5 | `(c, a, r, g, b)` 四个 0–255 整数 |
| `drawRGB` | 4 | `(c, r, g, b)` |
| `drawColor` | 2 或 3 | `(c, color)` / `(c, color, porterDuffMode)` |
| `drawPaint` | 2 | `(c, paint)` 用 paint 铺满整个裁剪区 |
| `drawPoint` | 4 | `(c, x, y, paint)` |
| `drawPoints` | 3 或 5 | `(c, floatArray, paint)` / `(c, offset, count, floatArray, paint)` |
| `drawLine` | 6 | `(c, x0, y0, x1, y1, paint)` |
| `drawLines` | 3 或 5 | `(c, floatArray, paint)` / `(c, offset, count, floatArray, paint)` |
| `drawRect` | 3 或 6 | `(c, rectOrRectF, paint)` / `(c, left, top, right, bottom, paint)` |
| `drawRoundRect` | 5 | `(c, rectF, rx, ry, paint)` —— **只收 RectF,且没有 4 参数版本** |
| `drawCircle` | 5 | `(c, cx, cy, radius, paint)` |
| `drawOval` | 3 | `(c, rectF, paint)` |
| `drawArc` | 6 | `(c, rectF, startAngle, sweepAngle, useCenter, paint)` |
| `drawPath` | 3 | `(c, path, paint)` |
| `drawBitmap` | 4 / 5 / 10 | 见下 |
| `drawBitmapMesh` | 9 | `(c, bitmap, meshWidth, meshHeight, verts, vertOffset, colors, colorOffset, paint)`;**未实测** |
| `drawText` | 5 或 7 | `(c, text, x, y, paint)` / `(c, text, start, end, x, y, paint)` —— 7 参数时 `text` 也可是 `CharSequence` / `char[]` |
| `drawPosText` | 4 或 6 | `(c, text, floatPosArray, paint)` / `(c, charArray, index, count, floatPosArray, paint)`;**未实测** |
| `drawTextOnPath` | 6 或 8 | `(c, text, path, hOffset, vOffset, paint)` / `(c, charArray, index, count, path, hOffset, vOffset, paint)` |
| `drawPicture` | 2 或 3 | `(c, picture)` / `(c, picture, rectF)` |
| `drawVertices` | 13 | `(c, mode, vertexCount, verts, vertOffset, texs, texOffset, colors, colorOffset, indices, indexOffset, indexCount, paint)`;**未实测** |

`drawBitmap` 的形态(**按第 4 个参数的类型分派**):

| 参数个数 | 第 4 参类型 | 形式 |
| --- | --- | --- |
| `4` | userdata | `(c, bitmap, matrix, paint)` |
| `5` | number | `(c, bitmap, left, top, paint)` |
| `5` | userdata | `(c, bitmap, srcRect, dstRect, paint)` —— `srcRect` 必须是 `Rect`,`dstRect` 可 `Rect` 或 `RectF` |
| `10` | 颜色数组 | `(c, intColorsArray, offset, stride, x, y, width, height, hasAlpha, paint)`,对应 `Canvas.drawBitmap(int[], ...)` |

> 注意 `5` 参数时**第 4 个参数的类型决定走哪条分支**:想用 `left/top` 就必须传数字,
> 想用 `src/dst` 就必须传 Rect;传错类型会两个分支都不进,**静默什么也不画**。

#### 变换与状态

| 方法 | 参数个数 | 签名 |
| --- | --- | --- |
| `save` | 1 或 4 | `(c)` / `(c, left, top, right, bottom)`;返回保存层级 |
| `restore` | 1 | `(c)` |
| `restoreToCount` | 2 | `(c, saveCount)` |
| `getSaveCount` | 1 | `(c)` → 整数 |
| `translate` | 3 | `(c, dx, dy)` |
| `rotate` | 2 或 4 | `(c, degrees)` / `(c, degrees, px, py)` |
| `scale` | 3 或 5 | `(c, sx, sy)` / `(c, sx, sy, px, py)` |
| `skew` | 3 | `(c, sx, sy)` |
| `concat` | 2 | `(c, matrix)` |
| `setMatrix` | 2 | `(c, matrix)` |
| `getMatrix` | 1 或 2 | `(c)` / `(c, matrix)`(传入时写入该 matrix) |
| `saveLayer` | 4 或 7 | `(c, left, top, right, bottom, paint, flags)` 等;实现见源码 |
| `saveLayerAlpha` | 4 或 7 | 同上,最后一参为 alpha |
| `setBitmap` | 2 | `(c, bitmap)` |
| `setDensity` | 2 | `(c, density)` |
| `setDrawFilter` | 2 | `(c, drawFilter)` |

#### 裁剪与查询

| 方法 | 参数个数 | 返回值 |
| --- | --- | --- |
| `clipRect` | 2 / 3 / 5 / 6 | 布尔(是否改变了裁剪区) |
| `clipPath` | 2 或 3 | 布尔 |
| `clipRegion` | 2 或 3 | 布尔 |
| `getClipBounds` | 1 或 2 | `Rect` / 写入传入的 `Rect` |
| `getWidth` | 1 | 整数 |
| `getHeight` | 1 | 整数 |
| `getDensity` | 1 | 整数 |
| `getMaximumBitmapWidth` | 1 | 整数 |
| `getMaximumBitmapHeight` | 1 | 整数 |
| `isOpaque` | 1 | 布尔 |
| `isHardwareAccelerated` | 1 | 布尔 |
| `quickReject` | 3 或 6 | 布尔(命中即跳过) |

> `clipRect` 的 `5` 参数版本是 `(c, left, top, right, bottom)`,
> `6` 参数版本多一个 `Region.Op`。`3` 参数版本是 `(c, rectOrRectF, op)`。

### 2.4 参数类型规则(重要)

`canvas.c` 用 `lua_touserdata(L, n)` 取每个参数,**不会做类型检查也不会转换**。
由此有三条硬约束:

1. **数字必须是 Lua number**,整数也行(Lua 5.4 的整数会被 `lua_tonumber` 正常读取)。
2. **对象必须是 luajava 包装过的 userdata**,也就是经 `luajava.bindClass` /
   `import` 造出来的 `Paint` / `RectF` / `Path` / `Bitmap` 实例。
   直接传 Lua table(例如 `{left=0, top=0}`)会读到垃圾指针 —— **崩溃,不是报错**。
3. **参数个数必须精确匹配**之一。个数不对时函数 `lua_error("Error. Invalid number
   of parameters.")`,这个错误会被 `pcall` 捕获,不会崩。

### 2.5 `RectF` 与 `Rect` 怎么造

```lua
local RectF = luajava.bindClass "android.graphics.RectF"
local Rect  = luajava.bindClass "android.graphics.Rect"

local rf = RectF(0, 0, 200, 120)     -- 四参构造
local r  = Rect(0, 0, 200, 120)
print(rf.width(), rf.height(), rf.centerX(), rf.centerY())
```

`drawRoundRect` / `drawOval` / `drawArc` **只接受 `RectF`**(源码里按 `RectF`
取方法 id);`drawRect` 两种都收。

---

## 3. `Paint` 速查(两条通道共用)

`Paint` 是绘制的主力,经 luajava 直接调:

```lua
local Paint = luajava.bindClass "android.graphics.Paint"
local p = Paint()

p.setAntiAlias(true)                     -- 抗锯齿,几乎总要开
p.setColor(0xFF6750A4)                   -- ARGB
p.setStyle(Paint.Style.FILL)             -- FILL / STROKE / FILL_AND_STROKE
p.setStrokeWidth(4)                      -- 描边宽度(px)
p.setStrokeCap(Paint.Cap.ROUND)          -- BUTT / ROUND / SQUARE
p.setStrokeJoin(Paint.Join.ROUND)        -- MITER / ROUND / BEVEL
p.setAlpha(0x80)                         -- 0–255
p.setTextSize(36)                        -- 字号(px)
p.setTextAlign(Paint.Align.CENTER)       -- LEFT / CENTER / RIGHT
p.setTypeface(Typeface.DEFAULT_BOLD)
p.setShadowLayer(6, 0, 3, 0x55000000)    -- 半径 / dx / dy / 颜色
p.setShader(shader)                      -- LinearGradient / RadialGradient / BitmapShader
p.setXfermode(xfermode)                  -- 混合模式
```

常用测量:

```lua
local w = p.measureText("hello")
local metrics = p.getFontMetrics()
local h = metrics.descent - metrics.ascent
```

> **单位是 px**。要给 dp,自己乘 `activity.getResources().getDisplayMetrics().density`。

---

## 4. 常用绘制配方

### 4.1 圆角卡片背景(替代 GradientDrawable)

```lua
local GradientDrawable = luajava.bindClass "android.graphics.drawable.GradientDrawable"

local bg = GradientDrawable()
bg.setShape(GradientDrawable.RECTANGLE)
bg.setColor(0xFFF3EDF7)
bg.setCornerRadius(24)
card.setBackground(bg)
```

这类**静态**背景不需要 `LuaDrawable` —— 现成的 `GradientDrawable` 更省事。
只有在需要"随数据变化"时才用 `LuaDrawable`。

### 4.2 进度环

```lua
local RectF = luajava.bindClass "android.graphics.RectF"
local Paint = luajava.bindClass "android.graphics.Paint"

local progress = 0.35   -- 0..1

local d = LuaDrawable(function(canvas, paint, self)
  local b = self.getBounds()
  local stroke = 12
  local rf = RectF(
    b.left + stroke / 2, b.top + stroke / 2,
    b.right - stroke / 2, b.bottom - stroke / 2
  )

  paint.setAntiAlias(true)
  paint.setStyle(Paint.Style.STROKE)
  paint.setStrokeWidth(stroke)
  paint.setStrokeCap(Paint.Cap.ROUND)

  -- 底环
  paint.setColor(0x22000000)
  canvas.drawArc(rf, 0, 360, false, paint)

  -- 进度
  paint.setColor(0xFF6750A4)
  canvas.drawArc(rf, -90, 360 * progress, false, paint)
end)
ring.setBackground(d)
```

改进度后调 `ring.invalidate()` 即可看到更新。

### 4.3 折线图(数据驱动)

```lua
local Path  = luajava.bindClass "android.graphics.Path"
local Paint = luajava.bindClass "android.graphics.Paint"

local data = { 12, 30, 22, 48, 40, 61, 55 }

local chart = LuaDrawable(function(canvas, paint, self)
  local b = self.getBounds()
  if #data < 2 then return end

  local minV, maxV = data[1], data[1]
  for _, v in ipairs(data) do
    if v < minV then minV = v end
    if v > maxV then maxV = v end
  end
  local span = (maxV - minV) == 0 and 1 or (maxV - minV)
  local stepX = b.width() / (#data - 1)

  local path = Path()
  for i, v in ipairs(data) do
    local x = b.left + stepX * (i - 1)
    local y = b.bottom - (v - minV) / span * b.height()
    if i == 1 then path.moveTo(x, y) else path.lineTo(x, y) end
  end

  paint.setAntiAlias(true)
  paint.setStyle(Paint.Style.STROKE)
  paint.setStrokeWidth(4)
  paint.setColor(0xFF2196F3)
  canvas.drawPath(path, paint)
end)
chartView.setBackground(chart)
```

### 4.4 文本居中(基线要自己算)

`drawText` 的 `y` 是**基线**,不是顶部。垂直居中:

```lua
local fm = paint.getFontMetrics()
local baseline = b.centerY() - (fm.ascent + fm.descent) / 2
paint.setTextAlign(Paint.Align.CENTER)
canvas.drawText("标题", b.centerX(), baseline, paint)
```

### 4.5 画到 Bitmap 再复用(离屏绘制)

需要反复用同一图案时,画一次 Bitmap 比每次重绘路径便宜:

```lua
local Bitmap      = luajava.bindClass "android.graphics.Bitmap"
local Canvas      = luajava.bindClass "android.graphics.Canvas"
local BitmapDrawable = luajava.bindClass "android.graphics.drawable.BitmapDrawable"

local bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
local off = Canvas(bmp)

local p = Paint()
p.setColor(0xFF6750A4)
off.drawCircle(128, 128, 100, p)

view.setBackground(BitmapDrawable(activity.getResources(), bmp))
```

---

## 5. 完整可运行示例

把下面内容存成项目里的 `draw_demo.lua`,在 `main.lua` 里 `require "draw_demo"`:

```lua
-- draw_demo.lua —— LuaDrawable 自适应绘制一屏演示
require "import"
import "android.widget.*"
import "android.view.*"
import "androidx.appcompat.widget.LinearLayoutCompat"

local LuaDrawable = luajava.bindClass "com.androlua.LuaDrawable"
local Paint       = luajava.bindClass "android.graphics.Paint"
local Path        = luajava.bindClass "android.graphics.Path"
local RectF       = luajava.bindClass "android.graphics.RectF"
local GradientDrawable = luajava.bindClass "android.graphics.drawable.GradientDrawable"

-- 简易自定义 View:一个画圆环的方块
local function makeRingView()
  local view = View(activity)
  view.setBackground(LuaDrawable(function(canvas, paint, self)
    local b = self.getBounds()
    if b.width() <= 0 then return end          -- 还没测量,跳过
    local stroke = 10
    local r = math.min(b.width(), b.height()) / 2 - stroke
    local rf = RectF(
      b.centerX() - r, b.centerY() - r,
      b.centerX() + r, b.centerY() + r
    )
    paint.setAntiAlias(true)
    paint.setStyle(Paint.Style.STROKE)
    paint.setStrokeWidth(stroke)
    paint.setStrokeCap(Paint.Cap.ROUND)

    paint.setColor(0x22000000)
    canvas.drawArc(rf, 0, 360, false, paint)

    paint.setColor(0xFF6750A4)
    canvas.drawArc(rf, -90, 270, false, paint)
  end))
  return view
end

-- 简易折线 View
local function makeLineView(data)
  local view = View(activity)
  view.setBackground(LuaDrawable(function(canvas, paint, self)
    local b = self.getBounds()
    if b.width() <= 0 or #data < 2 then return end

    local minV, maxV = data[1], data[1]
    for _, v in ipairs(data) do
      if v < minV then minV = v end
      if v > maxV then maxV = v end
    end
    local span = (maxV - minV) == 0 and 1 or (maxV - minV)
    local stepX = b.width() / (#data - 1)

    local path = Path()
    for i, v in ipairs(data) do
      local x = b.left + stepX * (i - 1)
      local y = b.bottom - (v - minV) / span * b.height()
      if i == 1 then path.moveTo(x, y) else path.lineTo(x, y) end
    end

    paint.setAntiAlias(true)
    paint.setStyle(Paint.Style.STROKE)
    paint.setStrokeWidth(5)
    paint.setColor(0xFF2196F3)
    canvas.drawPath(path, paint)
  end))
  return view
end

-- 布局参数:MATCH_PARENT / WRAP_CONTENT 是常量,直接引用类上的静态字段
local LP = LinearLayoutCompat.LayoutParams
local MATCH, WRAP = -1, -2          -- 与 LP.MATCH_PARENT / LP.WRAP_CONTENT 等值

local root = LinearLayoutCompat(activity)
root.setOrientation(1)              -- 1 = VERTICAL(也可用 LinearLayoutCompat.VERTICAL)
root.setPadding(40, 60, 40, 40)
root.setBackgroundColor(0xFFFDFBFF)

local title = TextView(activity)
title.setText("LuaDraw 演示")
title.setTextSize(22)               -- 单位 sp
title.setTextColor(0xFF1D1B20)
root.addView(title)

-- 自绘区:圆环
local ring = makeRingView()
root.addView(ring, LP(MATCH, 360))

-- 自绘区:折线
local line = makeLineView({ 12, 30, 22, 48, 40, 61, 55 })
root.addView(line, LP(MATCH, 400))

-- 静态卡片的背景用现成 GradientDrawable,不必每帧重绘
local cardBg = GradientDrawable()
cardBg.setShape(GradientDrawable.RECTANGLE)
cardBg.setColor(0xFFF3EDF7)
cardBg.setCornerRadius(28)          -- 单位 px;要 dp 请乘 displayMetrics.density

local tip = TextView(activity)
tip.setText("静态背景用 GradientDrawable,动态内容用 LuaDrawable")
tip.setTextSize(14)
tip.setTextColor(0xFF49454F)
tip.setPadding(28, 28, 28, 28)
tip.setBackground(cardBg)
root.addView(tip, LP(MATCH, WRAP))

activity.setContentView(root)
```

> 上面刻意用 `LP(MATCH, 360)` 这类**显式构造**而不是链式 `setXxx().setYyy()`:
> luajava 允许对 `void` 方法继续 `.` 下去,但读代码时容易误以为它返回了新对象。
> 显式分句在排查"到底哪句没生效"时更省事。

验证方式(MCP):

```text
clear_logs
run_project
wait_for_text text="LuaDraw 演示"
capture_screen                       # 自绘内容只有像素能证明
get_runtime_errors                   # 绘制抛错会以 onDraw 落进日志
```

---

## 6. 边界与已知限制

| 事项 | 说明 |
| --- | --- |
| 硬件加速 | `LuaDrawable` 走正常 `Drawable` 路径,受 View 的硬件加速设置影响。设了 `layerType = "software"` 的 View 才能保证 `setShadowLayer` 等效果一致 |
| 崩溃不可捕获 | `canvas` 模块传错参数类型(如传 table 代替 `RectF`)会读野指针直接崩,`pcall` 拦不住 |
| 绘制性能 | Lua 回调每帧执行,**不要在里面做文件 IO、网络、分配大量对象**;昂贵对象用 1.2 的"返回函数"写法缓存 |
| 线程 | `draw()` 在主线程;Lua 侧不能在里面 `sleep` 或阻塞等待 |
| `drawBitmapMesh` / `drawPosText` / `drawVertices` | 这三个在 `canvas.c` 里有完整实现,但**仓库内无任何使用案例**,且 `drawPosText` 在 API 29+ 已被系统标记 deprecated,按"未实测"对待 |
| `LuaDrawable` 在仓库里的实际用法 | `core/src/main/resources/lua/loadmenu.lua:9` 只做了 `luajava.bindClass` 取值,**没有任何调用**;项目代码里用它需要你自己绑定类(见本文示例) |
| 与 Compose 混用 | Compose 侧没有对应的 Canvas 封装;要自绘请用 `AndroidView` 包一个自定义 View,或直接在 `loadlayout` 的 View 上用 `LuaDrawable` |

---

## 7. 与 `canvas` 模块逐函数对照:该选谁

| 需求 | 选 |
| --- | --- |
| 给按钮/卡片加个动态背景 | 通道 A(`LuaDrawable` + `view.invalidate()`) |
| 做一个自绘 View 放进 `loadlayout` | 通道 A |
| SurfaceView 高频重绘 | 通道 B(`lockCanvas` / `unlockCanvasAndPost`) |
| 只需要静态圆角/纯色背景 | 都不用,`GradientDrawable` |
| 手头已有一个 `android.graphics.Canvas` 对象 | 都不用,直接调它的 Java 方法 |

---

## 8. 事实来源

- `LuaDrawable` 的首次三参 / 后续一参语义:`core/src/main/java/com/androlua/LuaDrawable.java:28-43`
- `Paint` 实例唯一、经 `getPaint()` 暴露:`LuaDrawable.java:63-65`
- 错误转发到 `sendError("onDraw", e)`:`LuaDrawable.java:39-41`
- canvas 模块的函数表与参数个数:`app/src/main/jni/canvas/canvas.c`(`luaopen_canvas` 3241 起,
  各函数 `statement_num ==` 判断)
- canvas 原生库随运行时打包:`app/src/main/jni/CMakeLists.txt`(`add_and_optimize_library(canvas)`)
- luajava 参数转换规则:`core/src/main/java/com/luajava/LuaJavaAPI.java`、`app/src/main/jni/luajava/luajava.c`
- `GradientDrawable` 现有用法:`app/src/main/assets/debugger.lua:103-112`、`app/src/main/assets/layouthelper/method.lua`

> 本文标注"未实测"的三处(`drawBitmapMesh` / `drawPosText` / `drawVertices`)
> 在仓库内没有任何调用案例,参数语义仅从源码的参数读取顺序推断,使用前请先在小页面验证。
