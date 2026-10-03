# MemUtil - 文档

## 一、概述

- **功能**:内存访问工具类,提供进程内存的**搜索、读取、写入**能力,风格接近 GameGuardian。
  底层由 C 实现(`libmemkit.so`),通过 `process_vm_readv/writev` 与 `/proc/<pid>/mem` 访问目标进程。
- **适用场景**:内存数值搜索与修改、多级指针定位、进程内存快照对比、自身进程内存调试。
- **优势**:无需 root 即可操作**自身进程**内存;有 root 时可跨进程操作;所有失败路径都返回明确错误而非崩溃。

## 二、启用方式

在项目 `settings.json` 文件中添加工具类到全局工具列表:

```json
{
  "application": {
    "label": "My App",
    "debugmode": true
  },
  "global_utils": [
    "MemUtil"
  ]
}
```

启用后,下列函数会注册为 **Lua 全局函数**,直接调用即可。

## 三、权限模型(先读这一节,决定你能用哪些功能)

| 目标 pid | 是否可读写 | 说明 |
| --- | --- | --- |
| `0` 或自身 pid | ✅ 无需 root | 走 `process_vm_readv/writev`,普通应用权限即可 |
| 其他进程 | ⚠️ 需要 root | 需要 `PTRACE_MODE_ATTACH`;Android 10+ SELinux enforcing 下即使 root 也可能被拒 |

**重要**:Lua 脚本运行在宿主进程内。若要修改**其他 App** 的内存,必须拿到 root 做跨进程访问,
或让脚本运行在被注入的目标进程内。无 root 时 `memRegions`/`memScan` 对其他 pid 会失败或返回空。

## 四、参数约定

注册机制按**位置**取参、按**形参类型**强转,类型必须严格匹配:

- 整数 → Lua integer(`123`、`0xFF`)
- 浮点 → Lua number(`1.5`)
- 布尔 → Lua boolean(`true`)
- 文本 → Lua string
- 末位 `opts` 为可选参数,**按下标顺序**传入,可省略

标注 `@throws` 的函数在失败时抛 `IllegalStateException`(含 pid/addr/errno),
请用 `pcall` 包裹。搜索类函数(`memScan` 等)**不会抛异常**,部分区域不可读属正常情况。

## 五、进程与区域

### 1. memIsRoot(root 检测)

- **Lua签名**:`memIsRoot()`
- **功能**:快速判断设备是否已 root。
- **原理**:`geteuid()==0` + 常见 `su` 路径 + Magisk / KernelSU / APatch 痕迹。
- **返回**:`boolean`
- **注意**:这是**静态痕迹判断**,不会弹 root 授权框;返回 `false` 不代表一定没 root。

### 2. memSelfPid(当前进程 pid)

- **Lua签名**:`memSelfPid()`
- **返回**:`integer`

### 3. memFindPid(按进程名查 pid)

- **Lua签名**:`memFindPid(name)`
- **功能**:匹配 `/proc/<pid>/comm` 或 `cmdline` 的 basename。
- **返回**:`integer`,未找到返回 `-1`

```lua
local pid = memFindPid("com.example.game")
if pid > 0 then print("found", pid) end
```

### 4. memRegions(列出内存区域)

- **Lua签名**:`memRegions(pid)`
- **参数**:`pid` 传 `0` 表示当前进程
- **返回**:JSON 字符串

```json
[{"start":549755813888,"end":549755817984,"perms":"r-xp","path":"/system/lib64/libc.so"}]
```

- **失败时返回** `[]`

## 六、读取

### 5. memReadBytes(读原始字节)

- **Lua签名**:`memReadBytes(pid, addr, len)`
- **返回**:小写十六进制字符串,长度 = 实际读到的字节数
- **抛出**:`IllegalStateException`(读取失败)

### 6. memReadInt(读整数)

- **Lua签名**:`memReadInt(pid, addr, size)`
- **参数**:`size` 必须为 `1` / `2` / `4` / `8`
- **返回**:`integer`,**已按位宽做符号扩展**(读 1 字节 `0xFF` 得 `-1`)

### 7. memReadFloat(读浮点)

- **Lua签名**:`memReadFloat(pid, addr, isDouble)`
- **参数**:`isDouble` 为 `true` 读 8 字节 double,`false` 读 4 字节 float
- **返回**:`number`

### 8. memReadString(读 C 字符串)

- **Lua签名**:`memReadString(pid, addr, maxLen)`
- **功能**:读到 `'\0'` 或达 `maxLen` 为止
- **返回**:`string`

## 七、写入

### 9. memWriteBytes(写原始字节)

- **Lua签名**:`memWriteBytes(pid, addr, hex)`
- **参数**:`hex` 为十六进制字符串(允许空格分隔)
- **返回**:`integer` 实际写入字节数

### 10. memWriteInt(写整数)

- **Lua签名**:`memWriteInt(pid, addr, value, size)`
- **参数**:`size` 为 `1` / `2` / `4` / `8`,小端序
- **返回**:`integer` 实际写入字节数

### 11. memWriteFloat(写浮点)

- **Lua签名**:`memWriteFloat(pid, addr, value, isDouble)`
- **返回**:`integer` 实际写入字节数

## 八、搜索(GG 式流程)

典型流程:**首扫 → 改变游戏内数值 → 续扫 → 收敛后批量修改**。

### 12. memScan(首扫)

- **Lua签名**:`memScan(pid, type, value, opts)`
- **参数**:
  - `type`:`byte` / `word` / `dword` / `qword` / `float` / `double` / `utf8`
  - `value`:文本值,整数支持十进制与 `0x` 前缀
  - `opts`(可选,按序):
    1. `limit:integer` 最多命中数,默认 `65536`,上限 `1048576`
    2. `start:integer` 起始地址下界
    3. `end:integer` 结束地址上界
    4. `align4:boolean` 是否只匹配 4 字节对齐地址,默认 `true`。
       **仅对 `dword`/`qword`/`float`/`double` 生效**;`byte`/`word`/`utf8` 可能落在任意地址,
       强制按 4 字节对齐会漏掉绝大多数匹配,故这三类自动忽略该选项。
- **返回**:`integer` 命中数(存入活动结果集)

```lua
local n = memScan(0, "dword", "12345")
print("hits", n)
```

### 13. memScanNext(续扫)

- **Lua签名**:`memScanNext(pid, type, value)`
- **功能**:在已有结果集上按新值过滤,**结果集被原地缩小**
- **value 可省略**:省略时表示「不比较数值,只保留仍可读的地址」(用于淘汰已解除映射的命中)
- **返回**:`integer` 剩余命中数

```lua
memScan(0, "dword", "12345")
-- 游戏内改变数值后
memScanNext(0, "dword", "999")
print("remaining", memScanCount())
```

### 14. memScanCount(命中数)

- **Lua签名**:`memScanCount()`
- **返回**:`integer`

### 15. memScanResult(取单个命中地址)

- **Lua签名**:`memScanResult(index)`
- **参数**:`index` 从 `0` 开始
- **返回**:`integer` 地址

### 16. memScanResults(批量取命中地址)

- **Lua签名**:`memScanResults(offset, count)`
- **参数**:`offset` 起始下标;`count <= 0` 取到结尾
- **返回**:JSON 数组 `[addr, ...]`

### 17. memScanRefresh(按结果集重读当前值)

- **Lua签名**:`memScanRefresh(pid, type, limit)`
- **功能**:按结果集当前位置重新读取,便于观察筛选
- **返回**:JSON `[[addr,"value"], ...]`;`limit <= 0` 表示不限制

### 18. memScanWriteAll(批量修改)

- **Lua签名**:`memScanWriteAll(pid, type, value)`
- **功能**:把所有命中地址改为指定值(GG 的「修改全部」)
- **返回**:`integer` 成功写入的地址数

### 19. memScanClear(清空结果集)

- **Lua签名**:`memScanClear()`
- **返回**:`integer` 清空前的命中数

### 20. memTypeInfo(类型表)

- **Lua签名**:`memTypeInfo()`
- **返回**:JSON `[{"name":"dword","size":4}, ...]`

## 九、完整示例:修改自身进程的一个数值

```lua
local pid = memSelfPid()

-- 找一个确定存在的整数:先在 Lua 侧造一个已知值,再搜它
local holder = { value = 4242 }

local n = memScan(pid, "dword", "4242", 100)
print("hits =", n)

-- 缩小范围:用一个远大于实际命中的 limit,便于观察
for i = 0, math.min(n, 5) - 1 do
  print(string.format("  [%d] 0x%x", i, memScanResult(i)))
end

-- 修改第一个命中(注意:无 root 时只能改自身进程)
local addr = memScanResult(0)
if addr and addr > 0 then
  local wrote = memWriteInt(pid, addr, 777, 4)
  print("wrote", wrote, "bytes")
end

memScanClear()
```

## 十、错误处理示例

```lua
local ok, err = pcall(function()
  return memReadInt(0, 0xDEADBEEF, 4)   -- 未映射地址
end)
if not ok then
  print("读取失败:", err)                -- 含 pid/addr/errno 的详细原因
end
```

## 十一、`require "memkit"` 直接调用(进阶)

除全局函数外,也可直接使用原生接口,此时可选参数语义更完整(跳过 pid 位置参数):

```lua
local mk = require "memkit"

print(mk.version())
print(mk.isRoot(), mk.selfPid())

-- 搜索:memkit.scan(type, value, {pid=, limit=, start=, end=, align4=})
local n = mk.scan("dword", 12345, { limit = 200 })
print("hits", n)

-- 续扫省略 value 表示只保留仍可读的地址
print("alive", mk.scanNext("dword"))

-- 遍历结果
for _, a in ipairs(mk.results(1, 10)) do
  print(string.format("0x%x", a))
end

-- 当前值快照:{ [addr] = value }
for addr, v in pairs(mk.refresh("dword")) do
  print(string.format("0x%x = %s", addr, tostring(v)))
end
```

## 十二、限制与注意事项

1. **无 root 只能操作自身进程**。修改其他 App 内存需要 root 或注入,这不是实现难度问题,是内核/SELinux 约束。
2. **不做注入/虚拟化**。本库只提供内存读写与搜索,不包含进程注入、Hook、加速器等功能。
3. **搜索是线性的**。扫全内存较慢,建议用 `start`/`end` 限定范围,或先用 `memRegions` 找到目标模块区间。
4. **结果集是全局单例**。同时只维护一份搜索状态,多次 `memScan` 会覆盖上一次结果。
5. **写入可能被目标进程覆盖**。若目标持续写回该内存,需要循环修改。
6. **地址可能失效**。进程重新分配内存后地址会变化,`memScanNext` 省略 value 可淘汰失效地址。

## 十三、版本

`1.0.0` — 原生库 `libmemkit.so`,与 `memVersion()` 返回值一致。
