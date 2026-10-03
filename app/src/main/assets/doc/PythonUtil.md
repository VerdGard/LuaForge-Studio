# PythonUtil - 文档

## 一、概述

- **功能**:在项目里运行**真正的 CPython**(非 Lua 子集、非轻量脚本引擎),与 Lua 并存。
  底层是嵌入的 CPython 解释器(`libpython3.14.so` + 标准库 + 原生扩展),
  由 C 桥 `libpython.so` 暴露为 Lua 全局函数。
- **适用场景**:需要 Python 生态的项目 —— `fastapi`/`uvicorn` 建 Web 服务、`httpx` 发请求、
  `cryptography` 做加密、`asyncio` 写并发逻辑,或直接复用已有的 Python 脚本。
- **与 Lua 的关系**:**并行,不互斥**。Lua 侧没有 GIL,Python 只在自己线程持 GIL,
  因此一个 Lua 线程与一个 Python 调用可以真正同时跑。
- **优势**:不用改写现有 Python 代码即可在 App 内运行,且 `pip` 生态的**纯 Python 包**可直接使用。

## 二、启用方式

在项目 `settings.json` 文件中添加工具类到全局工具列表:

```json
{
  "application": {
    "label": "My App",
    "debugmode": true
  },
  "global_utils": [
    "PythonUtil"
  ]
}
```

启用后,下列函数会注册为 **Lua 全局函数**,直接调用即可。

也可以在 Lua 里直接 `require "python"` 使用原生接口(见第十节),两者是同一份实现。

## 三、运行时从哪来(先读这一节)

真实的解释器运行时(`libpython3.14.so`、标准库、`lib-dynload` 下的原生扩展)
**不随 LuaForge 安装包分发**,而是由 IDE 在项目运行/打包时投放到 App 私有目录。

因此**必须先探测可用性**:

```lua
if not pythonAvailable() then
  print("当前设备/环境不支持 Python")
  return
end
```

`pythonAvailable()` 为 `false` 的典型原因:

| 原因 | 说明 |
| --- | --- |
| 设备是 32 位 (armeabi-v7a) | Python 3.12 起只提供 arm64-v8a 产物,**32 位设备无 Python 支持** |
| 运行时尚未投放 | 首次运行尚未解包,或项目未勾选 Python 支持 |

**重要**:Python 不可用时,所有调用都会抛出带原因的异常,而**不是**静默返回空值 ——
避免你拿到一个"看起来在跑、实际 import 全失败"的环境。

## 四、参数约定

注册机制按**位置**取参、按**形参类型**强转,**类型必须严格匹配**:
整数传 integer、小数传 number、布尔传 boolean、文本传 string。

可选参数统一放在方法末尾的 `opts` 里,按下标顺序可选传入。需要"跳过"某个可选参数时显式传 `nil`。

## 五、探测与初始化

### 1. pythonAvailable(是否可用)

```lua
local ok = pythonAvailable()
```

**无副作用**的探测:只加载动态库并解析符号,不初始化解释器,不会抛异常。可放心用于前置判断。

### 2. pythonVersion(版本号)

```lua
print(pythonVersion())  --> 3.14.0
```

运行时缺失时返回带说明的占位串,不抛异常。

### 3. pythonInit(初始化,幂等)

```lua
pythonInit()                     -- 用默认运行时目录
pythonInit("/sdcard/py_runtime") -- 显式指定运行时根目录(opts[0])
```

- 重复调用直接返回 `true`,不会重建解释器。
- 初始化失败抛 `IllegalStateException`,消息里带 `home` 与实际是否存在,便于定位。

## 六、执行 Python

### 4. pythonRun(执行一段代码)

```lua
local out = pythonRun([[
import sys, ssl
print("Python", sys.version.split()[0])
print("OpenSSL", ssl.OPENSSL_VERSION)
]])
print(out)
-- Python 3.14.0
-- OpenSSL 3.0.18 30 Sep 2025
```

- 返回本次执行产生的 **stdout + stderr 文本**(两者合并,顺序保持实际写入顺序)。
- `opts[0]` 可指定工作目录(字符串);省略则用**当前项目目录**(即脚本所在目录)。
  这点很重要:Python 里用相对路径读资源,基准就是你设的工作目录。

```lua
-- 指定工作目录
pythonRun("import os; print(os.getcwd())", "/sdcard/工作目录/樱花动漫")
```

### 5. pythonRunFile(执行脚本文件)

```lua
-- 不传参数
local out = pythonRunFile("/sdcard/proj/run.py")

-- 传参数:opts[0] 是工作目录, 之后依次是 argv
local out2 = pythonRunFile("/sdcard/proj/run.py", "/sdcard/proj", "--port", "5050", "--debug")
```

与命令行 `python script.py ...` 的差异(**刻意如此**,便于配合 `argparse`):

| 项 | 行为 |
| --- | --- |
| `sys.argv` | **不含脚本路径**;只含你传入的参数。不传时是空表 `[]` |
| `sys.path[0]` | 插入**脚本所在目录**,因此能 `import` 同目录的模块 |
| `__name__` | `"__main__"` |
| `__file__` | 脚本的绝对路径 |
| 工作目录 | 由 `opts[0]` 决定;省略则用当前项目目录 |

### 6. pythonFinalize(结束解释器)

```lua
pythonFinalize()
```

幂等。结束后已导入模块的状态全部丢弃,再次执行需重新 `pythonInit()`。
一般不需要手动调用(进程退出即整体释放),仅用于"换一套运行时目录"的场景。

## 七、完整示例:在 App 内跑一个 Web 服务

```lua
-- 0) 前置判断
if not pythonAvailable() then
  print("设备不支持 Python(需要 arm64-v8a)")
  return
end

-- 1) 初始化
pythonInit()
print("Python", pythonVersion())

-- 2) 起一个最小 HTTP 服务(后台线程, 不阻塞 Lua)
local appDir = "/sdcard/工作目录/樱花动漫"

local code = [[
import sys
sys.path.insert(0, '.')          # 让项目内模块可 import
from yhdm.app import app          # 项目自身的 ASGI 应用
import uvicorn
uvicorn.run(app, host="127.0.0.1", port=5050, log_level="info")
]]

-- pythonRun 是同步的:放到 Lua 线程里跑, 主线程继续做别的事
thread(function()
  local ok, err = pcall(pythonRun, code, appDir)
  if not ok then print("服务异常:", err) end
end)

-- 3) 主线程照常工作(与 Python 真并行)
print("服务启动中,Lua 侧继续执行")
```

## 八、错误处理示例

脚本自身抛异常时,**不会**被吞掉 —— 错误文本连同完整 traceback 一起抛出:

```lua
local ok, err = pcall(pythonRun, [[
raise ValueError("业务参数不合法")
]])

print(ok)   --> false
print(err)  --> Python 执行失败:
            --   Traceback (most recent call last):
            --     File "<luaforge>", line 1, in <module>
            --   ValueError: 业务参数不合法
```

用 `pcall` 捕获后可以按类型分发:

```lua
local ok, err = pcall(pythonRunFile, scriptPath)
if not ok then
  if tostring(err):find("FileNotFoundError") then
    print("脚本文件不存在,请检查路径")
  elseif tostring(err):find("ModuleNotFoundError") then
    print("缺少依赖,请确认已安装该包")
  else
    print("Python 出错:", err)
  end
end
```

## 九、与 Lua 的并行模型

| 关系 | 语义 |
| --- | --- |
| Lua 线程 ↔ Python 线程 | **真并行**(Lua 侧无 GIL,互不阻塞) |
| Python 线程 ↔ Python 线程 | 受 GIL 串行(标准构建) |
| 同一次 `pythonRun` 内部 | 不可重入:调用被串行化,不会与另一次调用交错 |

要点:

- **Python 是阻塞的**:`pythonRun` 会一直等到 Python 代码返回。
  起服务、长任务请放进 `thread(...)` 里,否则会卡住 Lua 主线程。
- 不要在 Python 里回调 Lua 对象:跨语言回调会破坏 Lua 的线程安全约定,
  需要双向通信时用文件、socket 或共享目录传递。

## 十、`require "python"` 直接调用(进阶)

`global_utils` 之外,也可以在 Lua 里直接 require 原生模块:

```lua
local py = require "python"

if not py.available() then print("不可用") return end
assert(py.init(os.getenv("PYHOME")))     -- 返回 bool, 失败不抛异常

local out = py.run("print('hello')")
print(out)

-- runFile 的 argv 是变参:第 1 个是 cwd, 之后是参数
local out2 = py.runFile("/path/cli.py", "/path", "--flag")

py.finalize()
```

与全局函数的差异:

| | 全局函数 | `require "python"` |
| --- | --- | --- |
| `init` 失败 | 抛异常(带目录诊断) | 返回 `false` |
| 工作目录默认值 | 当前项目目录 | 需显式传入,否则继承进程 cwd |
| 版本号 | 干净的数字版本 | 同 |

## 十一、限制与注意事项

1. **仅 arm64-v8a**:Python 3.12+ 无 32 位产物,armeabi-v7a 设备上 `pythonAvailable()` 恒为 `false`。
2. **体积**:引入 Python 运行时会使 APK 明显变大(运行时 + 标准库 + 依赖包)。
   仅为需要 Python 的项目勾选本工具类。
3. **原生扩展**:只有**预编译的 Android 版**轮子能用。纯 Python 包可直接使用;
   带 C/Rust 扩展的包需要有对应的 `android_arm64_v8a` 轮子。
4. **GIL**:Python 侧多线程仍是串行的。需要 CPU 并行请用多进程或等待后续的子解释器支持。
5. **首次运行较慢**:首次需解包运行时,之后有缓存。
6. **不要重复 `init` / `finalize`**:`init` 幂等,但 `finalize` 会丢弃全部模块状态;
   循环里反复 `finalize` + `init` 会显著拖慢且可能残留资源。

## 十二、版本

- PythonUtil 1.0.0
- 内置 Python:3.14.0
