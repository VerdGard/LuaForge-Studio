# 更新日志

## 1.6.4
- 构建项目支持选择构建类型(构建时弹出 MD3 对话框)
  - **未加密版**:Lua / ALY 源码不做加密处理,以明文脚本打包
  - **Debug 版**:开启调试模式(打包内 `settings.json` 的 `debugmode` 置为 true)
  - **Release 版**:关闭调试模式(`debugmode` 置为 false)
  - 未加密与调试模式是两个独立维度:未加密版沿用项目自身的调试模式设置
- 设置 → MCP 服务:每个访问地址独立一行并带**复制按钮**,一键复制到剪贴板
- 设置 → 网络请求拦截:允许 / 拒绝主机名单支持**单条删除**与**清空全部**,列表行改为卡片式并配删除按钮
  - 名单超过 5 条时自动折叠,可展开查看并逐条清理
- MCP:新增 `global_utils` 工具类的查看与调用能力,工具总数 42 → 44
  - `list_global_utils`:查看项目 `global_utils` 配置,以及这些工具类在**运行时会实际注册**的 Lua 全局函数(参数、返回类型、context 注入、同名覆盖)
    - 注册清单与运行时反射注册共用同一套规则,不会与运行结果漂移
    - 顺带暴露 `unknown`(settings.json 里拼错的名字,运行时只会静默跳过)
    - 同时返回当前正在运行的页面(`runningPages`),便于确认调用目标
  - `call_global_util`:在**运行中**的项目里调用 `global_utils` 注册的 Lua 全局函数(如 `dp2px`、`parseColor`)
    - 与项目自身调用**同一条路径**(运行实例主线程执行),`dp2px`、`statusBarHeight()` 这类需要真实 `Context` 的 UI 函数也生效(首个 `Context` / `Activity` 形参由框架自动注入)
    - 参数支持 JSON 数组或分隔字符串(自动推断 number / boolean / null),并按形参类型做个数与类型校验
    - 需要控件 / Java 对象 / Lua 回调的函数(如 `GlideUtil.loadImage` 需 `ImageView`、网络请求、Recycler 适配器)会明确拒绝并说明原因
- 修复 `global_utils` 注册函数的小数入参调用失败
  - 之前 `LuaState.toJavaObject` 得到的 `Double` 直接传给 `Float` / `Int` 形参会抛 `argument type mismatch`(如 `dp2px(16.5)`)
  - 现在按形参类型做数值收窄,不再依赖 Java 的自动转换
- `LuaActivity` 新增运行实例定位能力(`getPageName` / `getRunningActivities` / `getActivityByLuaDir`),并给 `sLuaActivityMap` 的读写加同步

### 其他
- 布局助手(`loadlayout`):修复 `@id/xxx`、`@+id/xxx` 形式的控件引用
  - 之前引用未在 `views` 表注册的 id 会直接抛 `attempt to index a nil value`
  - 现在先剥离 `@id/` / `@+id/` 前缀再查表,仍找不到时给出可读报错(`deferred attribute references undefined id: xxx`)
  - 核心库 `core/src/main/resources/lua/loadlayout.lua` 与布局助手 `app/src/main/assets/layouthelper/loadlayout2.lua` 双源同步
- 布局助手:控件清单移除 9 个易混淆 / 已废弃控件,并加入容错合并
  - 移除 `AppCompatImageButton`、`AppCompatCheckedTextView`、`AppCompatRatingBar`、`AppCompatToggleButton`、`AbsoluteLayout`、`CheckedTextView`、`ImageButton`、`RatingBar`、`ToggleButton`
  - 类名表与中文名表改为按表长容错合并,两表长度不一致时不再因 `nil` 拼接崩溃
- 文件树:文件/文件夹长按菜单新增「复制相对路径」(相对项目根目录)
- 编辑器:自动换行支持**按项目独立**(设置 → 编辑器配置可开关,默认开启)
  - 关闭时所有项目共用同一个全局换行设置;切换项目 / 文件后立即应用,无需手动再切一次
- 编辑器:长按顶部运行按钮可「运行当前文件」(当前标签为 `.lua` 时展开菜单)
  - 单击运行按钮行为不变,仍运行项目入口 `main.lua`
- 崩溃页:新增崩溃现场诊断 `[Diagnostics]`,分区展示
  - 全线程 dump:标注非 `RUNNABLE` 线程状态,便于交叉定位卡死线程与持锁线程
  - `LuaState Registry`:`LuaStateFactory.snapshotStates()` 列出所有注册过的 native 指针及 wrapper 是否已关闭
    - 只读裸指针字段,不调用 `LuaState` 的同步方法,避免在「LuaState monitor 卡死」现场让采集线程自锁
  - `Running Lua`:列出当前运行中的 Lua 页面及其 `luaDir`
  - 完整诊断同时落盘 `filesDir/crash_report/`,文件名带毫秒时间戳,供 adb pull 挖掘
  - 采集上限 300 KB,超限截断,避免 dump 撑爆 Binder Intent


## 1.6.3
- 新增「网络请求拦截」(设置 → 网络请求拦截,默认关闭)
  - 所有网络请求在真正发出前弹出 MD3 对话框展示方法、主机、完整地址、请求头、请求体与来源,用户允许后才放行
  - 覆盖范围:编辑器自身请求 + 用户项目请求
    - `http.*`(HttpURLConnection)、`okhttp.*`(OkHttp 拦截器);图片加载与 WebView 请求不拦截
  - 按主机记忆允许/拒绝,列表可在设置中查看与清空;也可随时关闭整个开关
  - 用户项目打包成 APK 后**不含**拦截逻辑(`:app` 的决策器不进入 `:core-apk`),因此打包后不拦截
  - 详见 [docs/NETWORK.md](docs/NETWORK.md)
- `http.*` 请求体预览截断到 16KB,避免大请求体额外占用内存

## 1.6.2
- MCP:新增 17 个工具,工具总数 25 → 42
  - 文件:`read_files`、`rename_file`、`make_directory`、`file_info`、`search_in_files`、`replace_in_file`、`replace_in_files`、`clean_compiled`
  - 编辑器:`refresh_editor`、`get_selection`、`goto_line`、`editor_history`(撤销/重做)、`check_syntax`
  - 项目:`list_templates`、`create_project`、`restore_backup`
  - 界面:`wait_for_text`(轮询等待界面就绪)
- 修复 MCP 写入代码后编辑器界面不刷新
  - `write_file` / `create_file` 写入后会同步刷新编辑器中已打开的同名标签
  - `delete_file` 会关闭对应标签,`rename_file` 会同步标签路径
  - 新增 `refresh_editor` 用于外部改动后强制刷新
- 编译验证不再残留产物
  - `compile_file` 默认在验证成功后删除 `.luac` / `.alyc`,需要产物时传 `keepOutput=true`
  - 新增 `clean_compiled` 清理历史残留(支持 `dryRun` 预览)
- 详见 [docs/MCP.md](docs/MCP.md)

## 1.6.1
- 修复 `loadlayout` 因属性别名导致整棵布局加载失败的问题
  - 支持 `width` / `height` / `weight` / `margin*` 等常见别名,自动归一化为 `layout_*`
  - 未知或无法设置的属性改为记录告警后跳过,不再中断整棵布局
- Lua 运行时错误现在会写入 `luaforge.log`(此前仅输出到 logcat,无法事后排查)
- MCP 新增运行时界面检查能力:`dump_screen` / `check_screen` / `get_runtime_errors` / `clear_logs`
  - 调试运行后可断言屏幕内容是否符合预期,并自动识别错误弹窗
  - 详见 [docs/MCP.md](docs/MCP.md)

## 1.6.0
- 新增 `libdecrypt.so`，支持 Lua 加密脚本自动解密加载
  - 提供 `decrypt.loadfile()` / `decrypt.dofile()` / `decrypt.load()` 接口
  - 兼容 `.luae` 加密格式及普通 `.lua` / `.luac` 文件
  - 复用 `ldump.c` / `lundump.c` 保护配置，加解密算法一致

## 1.5.0
- 支持 Maven 依赖自动下载与打包（JAR 类型）

## 1.4.0
- 优化补全数据加载机制：检测到版本变更时自动重建补全数据缓存，确保代码补全与新版本一致
- 优化应用启动流程

## 1.3.0
- 增加 `com.luaforge.studio.widget.textfield.MaterialTextField` 组件

## 1.2.1
- 增加 `onActivityReenter` 回调函数

# Lua 扩展语法

## 1. 概述

本文档基于 Lua 5.5 的深度魔改版本，全面支持现代编程语言的语法特性。主要特性包括：

- 增强型语法结构：支持 try-catch-finally 异常处理、switch-case 多分支、defer 延迟执行、when 条件分支、continue 跳转。
- 现代化操作符：三元运算符、复合赋值、可选链、空值合并、管道运算符。
- 函数式编程增强：Lambda 表达式（匿名函数）简洁语法。
- 完整的特性组合：所有扩展语法可以无缝嵌套使用。

---

## 2. 词法扩展

### 2.1 新增操作符

| 符号 | 等价于/描述 | 说明 |
|------|------------|------|
| `? :` | if-else 三元 | 条件运算符 `(cond) ? true_expr : false_expr` |
| `?.` | 安全访问 | 可选链操作符，避免 nil 报错 |
| `??` | 空值合并 | 左侧为 nil 时返回右侧值 |
| `|>` | 管道操作符 | 将值传入函数 `value |> func` |
| `!` | `not` | 逻辑非 |
| `!=` | `~=` | 不等于 |
| `&&` | `and` | 逻辑与 |
| `||` | `or` | 逻辑或 |
| `$` | `local` | 局部变量声明缩写 |
| `@` | `::` | 标签声明（用于 goto） |

### 2.2 复合赋值

支持完整的 C 风格复合赋值操作符：

| 操作符 | 示例 | 等价于 |
|--------|------|--------|
| `+=` | `a += 5` | `a = a + 5` |
| `-=` | `a -= 3` | `a = a - 3` |
| `*=` | `a *= 2` | `a = a * 2` |
| `/=` | `a /= 4` | `a = a / 4` |
| `//=` | `a //= 2` | `a = a // 2` |
| `%=` | `a %= 3` | `a = a % 3` |
| `^=` | `a ^= 2` | `a = a ^ 2` |
| `..=` | `s ..= "x"` | `s = s .. "x"` |
| `&=` | `bits &= mask` | `bits = bits & mask` |
| `|=` | `flags |= 0x01` | `flags = flags | 0x01` |
| `<<=` | `a <<= 2` | `a = a << 2` |
| `>>=` | `a >>= 1` | `a = a >> 1` |

**示例代码：**
```lua
$ counter = 10
counter += 5      -- 15
counter *= 2      -- 30

$ message = "Hello"
message ..= " World"  -- "Hello World"

$ flags = 0b1100
flags &= 0b1010   -- 0b1000
```

---

## 3. 语法扩展

### 3.1 三元运算符

简洁的条件表达式，支持嵌套使用。

**语法格式：**
```lua
(condition) ? true_expression : false_expression
```

**示例代码：**
```lua
$ age = 18
$ status = (age >= 18) ? "adult" : "minor"
print(status)  -- adult

-- 嵌套使用
$ score = 85
$ grade = score >= 90 ? "A" : score >= 80 ? "B" : "C"
print(grade)  -- B
```

---

### 3.2 可选链

安全访问嵌套对象属性，避免因中间值为 nil 而抛出错误。

**语法格式：**
```lua
obj?.field          -- 安全访问字段
obj?.[index]        -- 安全访问数组元素
obj?.method?()      -- 安全调用方法
```

**示例代码：**
```lua
$ user = {
  profile = {
    name = "Alice",
    settings = { theme = "dark" }
  }
}

print(user?.profile?.name)          -- Alice
print(user?.profile?.address?.city) -- nil（不会报错）
print(user?.nonexist?.field)        -- nil

-- 与空值合并结合使用
$ city = user?.profile?.address?.city ?? "unknown"
print(city)  -- unknown
```

---

### 3.3 空值合并

当左侧值为 nil 时返回右侧值，否则返回左侧值。

**语法格式：**
```lua
value ?? default_value
```

**示例代码：**
```lua
$ name = nil
$ display = name ?? "Anonymous"  -- "Anonymous"

$ count = 0
$ result = count ?? 100          -- 0（0 不是 nil）

$ a = nil; $ b = nil; $ c = 42
$ value = a ?? b ?? c ?? 0       -- 42
```

---

### 3.4 Lambda 表达式

简洁的匿名函数定义语法，支持多种写法。

**语法格式：**
```lua
\参数列表 -> 表达式                 -- 单表达式自动返回
\参数列表 => 语句块                 -- 多语句需显式 return
lambda 参数列表 -> 表达式           -- 完整写法
```

**示例代码：**
```lua
-- 基础用法
$ add = \x, y -> x + y
print(add(3, 5))  -- 8

$ square = \x -> x * x
print(square(4))  -- 16

-- 多语句块
$ complex = \x, y -> do
  $ temp = x + y
  return temp * 2
end

-- 闭包
$ factor = 3
$ multiplier = \x -> x * factor
print(multiplier(5))  -- 15

-- 高阶函数
$ make_adder = \n -> \x -> x + n
$ add5 = make_adder(5)
print(add5(10))  -- 15

-- 与管道结合
$ numbers = {1, 2, 3, 4, 5}
$ doubled = map(numbers, \x -> x * 2)
```

---

### 3.5 管道运算符

将值从左到右传递通过一系列函数，提高代码可读性。

**语法格式：**
```lua
value |> function1 |> function2 |> function3
```

**示例代码：**
```lua
$ double = \x -> x * 2
$ add1 = \x -> x + 1
$ square = \x -> x * x

$ result = 5 |> double |> add1 |> square
print(result)  -- ((5*2)+1)^2 = 121

-- 与三元结合
$ value = (x > 0) ? x : 0 |> double |> add1

-- 与可选链结合
$ user = { score = 80 }
$ level = user?.score |> \s -> s >= 60 ? "pass" : "fail"
```

---

### 3.6 Try-Catch-Finally

完整的异常处理机制，支持错误捕获和资源清理。

**语法格式：**
```lua
try
  -- 可能抛出错误的代码
  error("something wrong")
catch (error_variable)
  -- 错误处理代码
finally
  -- 无论是否出错都会执行的清理代码
end
```

**示例代码：**
```lua
-- 基础用法
try
  $ file = io.open("data.txt", "r")
  $ content = file:read("*a")
  print(content)
catch (err)
  print("Error reading file:", err)
finally
  if file then file:close() end
end

-- 嵌套使用
try
  print("Outer try")
  try
    error("inner error")
  catch (e)
    print("Inner catch:", e)
    error("rethrown")
  finally
    print("Inner finally")
  end
catch (e)
  print("Outer catch:", e)
finally
  print("Outer finally")
end

-- 与 return 结合
function test()
  try
    return "from try"
  catch (e)
    return "from catch"
  finally
    print("finally runs before return")
  end
end
```

---

### 3.7 Switch-Case 语句

多分支选择结构，支持多值匹配。

**语法格式：**
```lua
switch expression do
  case value1 then
    -- 代码块
  case value2, value3 then
    -- 多值匹配
  default
    -- 默认分支
end
```

**示例代码：**
```lua
$ command = "start"

switch command do
  case "start", "run" then
    print("Starting...")
    -- 执行启动逻辑
  case "stop", "halt" then
    print("Stopping...")
  case "restart" then
    print("Restarting...")
  default
    print("Unknown command:", command)
end

-- 数值匹配
$ score = 85
switch math.floor(score / 10) do
  case 9, 10 then
    print("Grade A")
  case 8 then
    print("Grade B")
  case 7 then
    print("Grade C")
  default
    print("Grade D")
end
```

---

### 3.8 Defer 语句

延迟执行，在作用域结束时自动运行，常用于资源释放。

**语法格式：**
```lua
defer statement end
```

**示例代码：**
```lua
function processFile(filename)
  $ file = io.open(filename, "r")
  defer file:close() end
  
  -- 无论发生什么，file:close() 都会在函数退出前执行
  $ data = file:read("*a")
  if #data == 0 then
    return nil  -- defer 仍会执行
  end
  return process(data)
end

-- 多个 defer 按后进先出顺序执行
function test()
  defer print("first") end
  defer print("second") end
  defer print("third") end
  print("body")
end
-- 输出顺序: body, third, second, first

-- 在块中使用
do
  $ resource = acquire()
  defer release(resource) end
  -- 使用资源
end  -- 退出块时自动释放
```

---

### 3.9 When 语句

简洁的条件分支，类似多路 if-elseif。

**语法格式：**
```lua
when condition1 then
  -- 代码块
case condition2 then
  -- 代码块
else
  -- 默认分支
end
```

**示例代码：**
```lua
$ temperature = 25

when temperature > 30 then
  print("Hot")
case temperature > 20 then
  print("Warm")
case temperature > 10 then
  print("Cool")
else
  print("Cold")
end

-- 与逻辑操作符结合
$ age = 25
$ hasLicense = true

when age >= 18 && hasLicense then
  print("Can drive")
case age >= 18 && !hasLicense then
  print("Need license")
else
  print("Too young")
end
```

---

### 3.10 Continue 语句

跳过当前循环迭代，进入下一次循环。

**语法格式：**
```lua
continue  -- 在 for, while, repeat 循环中使用
```

**示例代码：**
```lua
-- for 循环
for i = 1, 10 do
  if i % 2 == 0 then
    continue  -- 跳过偶数
  end
  print("odd:", i)  -- 输出 1,3,5,7,9
end

-- while 循环
$ i = 0
while i < 10 do
  i = i + 1
  if i == 5 then
    continue  -- 跳过 5
  end
  print(i)  -- 输出 1,2,3,4,6,7,8,9,10
end

-- repeat 循环
$ j = 0
repeat
  j = j + 1
  if j == 3 then
    continue
  end
  print(j)  -- 输出 1,2,4,5
until j >= 5

-- 嵌套循环
for i = 1, 3 do
  for j = 1, 3 do
    if j == 2 then
      continue  -- 只跳过内层循环的当前迭代
    end
    print(i, j)
  end
end
```

---

### 3.11 局部声明缩写

使用 `$` 快速声明局部变量。

**语法格式：**
```lua
$ variable = value
$ var1, var2 = value1, value2
```

**示例代码：**
```lua
$ name = "Lua"
$ x, y = 10, 20
$ result = x + y

-- 与三元结合
$ max = (x > y) ? x : y

-- 在块中使用
do
  $ temp = calculate()
  print(temp)
end
-- temp 在这里不可访问
```

---

### 3.12 标签与 Goto

使用 `@` 声明标签，支持 goto 跳转。

**语法格式：**
```lua
@label@  -- 标签声明
goto label
```

**示例代码：**
```lua
-- 循环模拟
$ count = 1
@start@
print("count:", count)
count = count + 1
if count <= 3 then
  goto start
end

-- 错误处理
$ success, err = pcall(function()
  if some_condition then
    goto error_handler
  end
  -- 正常逻辑
  return
  @error_handler@
  print("Error occurred")
end)
```

---

## 4. 特性组合使用

所有扩展语法可以无缝组合，实现简洁而强大的代码。

### 4.1 综合示例

```lua
-- 复杂数据处理管道
$ processUserData = \data -> do
  try
    $ result = data?.users
      ?.[0]
      ?.profile
      ?.name ?? "anonymous"
      |> upper
      |> \name -> name .. " (" .. (data?.version ?? 1) .. ")"
    
    $ counter = 0
    counter += (result != "anonymous") ? 10 : 0
    
    when counter > 5 then
      print("High priority user")
    case counter > 0 then
      print("Normal user")
    else
      print("Anonymous user")
    end
    
    return result
  catch (e)
    return "error: " .. e
  finally
    print("Processing completed")
  end
end

-- 资源管理
function safeFileOperation(filename, operation)
  $ file = io.open(filename, "r")
  if !file then
    return nil, "Cannot open file"
  end
  defer file:close() end
  
  $ content = file:read("*a")
  $ result = content 
    |> operation 
    |> \r -> r ?? "no result"
  
  return result
end

-- 状态机
$ state = "initial"
while true do
  when state == "initial" then
    print("Initializing...")
    state = "running"
  case state == "running" then
    print("Running...")
    state = (counter++ > 10) ? "finished" : "running"
  case state == "finished" then
    print("Finished")
    break
  else
    print("Unknown state:", state)
    break
  end
end

-- 错误处理与清理
try
  $ conn = createConnection()
  defer conn:close() end
  
  $ data = conn:query("SELECT * FROM users")
  $ processed = data 
    |> filter(\u -> u.age > 18)
    |> map(\u -> {
      name = u.name |> upper,
      adult = true
    })
  
  switch #processed do
    case 0 then
      print("No adult users")
    case 1 then
      print("One adult user:", processed[1].name)
    default
      print("Multiple adult users:", #processed)
  end
catch (err)
  print("Database error:", err)
finally
  print("Database operation completed")
end
```

---

## 5. 完整特性列表

| 特性类别 | 具体特性 | 语法示例 |
|----------|----------|----------|
| 条件表达式 | 三元运算符 | `(a>b) ? a : b` |
| | 空值合并 | `value ?? default` |
| | 可选链 | `obj?.field?.[index]` |
| 赋值操作 | 复合赋值 | `+= -= *= /= //= %= ^= ..= &= |= <<= >>=` |
| 逻辑操作 | 逻辑非 | `!condition` |
| | 不等于 | `a != b` |
| | 逻辑与 | `a && b` |
| | 逻辑或 | `a || b` |
| 函数式编程 | Lambda | `\x,y -> x+y` |
| | 管道 | `value |> func1 |> func2` |
| 异常处理 | Try-Catch-Finally | `try ... catch(e) ... finally ... end` |
| 控制流 | Switch-Case | `switch x do case v: ... default ... end` |
| | When | `when c1 then ... case c2 then ... else ... end` |
| | Continue | `continue` |
| 资源管理 | Defer | `defer cleanup() end` |
| 语法糖 | 局部声明 | `$ var = value` |
| | 标签 | `@label@` |
