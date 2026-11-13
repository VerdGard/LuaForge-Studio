# FastIOUtil — C 语言实现的极速文件读写

基于原生库 `libfastio.so` 的文件读写工具。与 Lua 自带 `io.open`、Java 侧
`File.readBytes` / `BufferedOutputStream` 相比,在**大文件连续搬运**场景下
明显更快,且不会把整份内容反复过一遍 Java 堆。

## 启用

在项目 `settings.json` 的 `global_utils` 中加入 `FastIOUtil`:

```json
{
  "global_utils": ["FastIOUtil"]
}
```

之后下列函数**直接成为 Lua 全局函数**(无需 `require`、无需 `import`)。

## 函数

### 读

| 函数 | 说明 | 返回 |
| --- | --- | --- |
| `readBytes(path[, maxBytes])` | 读整个文件为字符串(二进制安全) | 内容 / `nil` |
| `readText(path[, charset])` | 读文本,默认 `UTF-8` | 文本 / `nil` |
| `readChunk(path, offset, length)` | 从 `offset` 读 `length` 字节,不读整个文件 | 内容 / `nil` |
| `fileSize(path)` | 文件字节数 | 字节数 / `-1` |
| `exists(path)` | 路径是否存在 | `true` / `false` |

- `maxBytes > 0` 时**只读前 `maxBytes` 字节**,防止误读超大文件打满内存。
- 读文本时遇无效字节序列按替换字符处理,不会因单个坏字节整体失败。
- 二进制内容在 Lua 侧就是普通字符串,可直接 `#s` 取长度、`string.byte` 取值。

### 写

| 函数 | 说明 | 返回 |
| --- | --- | --- |
| `writeBytes(path, data[, createDir, append, sync])` | 写字节 | `true` / `false` |
| `writeText(path, text[, charset, createDir, append])` | 写文本,默认 `UTF-8` | `true` / `false` |
| `writeBytesAtomic(path, data)` | 原子写(临时文件 + `fsync` + `rename`) | `true` / `false` |
| `truncate(path, length)` | 截断/扩展到指定长度 | `true` / `false` |

- `createDir` 默认 `true`:**自动递归创建父目录**,不需要自己 `mkdirs`。
- `append` 默认 `false`(截断重写);日志类追加写传 `true`。
- `sync = true` 会等待数据真正落盘后才返回,断电不丢;普通输出可省略。
- 原生路径**循环处理短写**,不会静默产出截断文件 —— 这是 `FileOutputStream`
  不保证的部分。

### 拷贝 / 目录

| 函数 | 说明 | 返回 |
| --- | --- | --- |
| `copyFile(src, dst[, createDir])` | 原生拷贝(优先 `sendfile`) | 拷贝字节数 / `-1` |
| `mkdirs(path)` | 递归建目录(等价 `mkdir -p`) | `true` / `false` |
| `delete(path)` | 删文件(不删非空目录) | `true` / `false` |
| `isNativeAvailable()` | 原生库是否可用(诊断用) | `true` / `false` |

`copyFile` 全程不经用户态缓冲(走 `sendfile`),适合把几十 MB 的资源搬进
项目目录;`mkdirs` 与 `writeBytes` 的 `createDir` 都容忍"目录已存在"。

## 示例

```lua
require "import"

-- 1. 读文本并统计行数
local text = readText("/sdcard/Download/big.csv")
if text then
  local _, lines = text:gsub("\n", "\n")
  print("行数", lines + 1)
end

-- 2. 写文本(自动建父目录)
writeText("/sdcard/LuaForge-Studio/out/result.txt", "hello")

-- 3. 二进制读写
local raw = readBytes("/sdcard/Download/a.apk", 4)   -- 只读前 4 字节看魔数
print(raw:byte(1), raw:byte(2), raw:byte(3), raw:byte(4))

-- 4. 大文件拷贝(不进 Java 堆)
local n = copyFile("/sdcard/Download/big.zip", "/sdcard/LuaForge-Studio/big_copy.zip")
print("拷贝字节数", n)

-- 5. 分段读取,处理超大文件
local size = fileSize("/sdcard/Download/big.log")
local chunk = 256 * 1024
for off = 0, size - 1, chunk do
  local part = readChunk("/sdcard/Download/big.log", off, chunk)
  -- 处理 part ...
end

-- 6. 原子写:读者永远看不到半截文件
writeBytesAtomic("/sdcard/LuaForge-Studio/config.json", '{"ok":true}')
```

## 选型建议

| 场景 | 建议 |
| --- | --- |
| 小于几十 KB 的小文件、需要 `file:lines()` 等迭代接口 | 直接用 Lua `io.*`,更简单 |
| 大文件整份读写 | **`FastIOUtil`**,避免 Java 堆峰值 |
| 超大文件分块处理 | `fileSize` + `readChunk` |
| 需要"要么全有要么全无"的配置/索引写 | `writeBytesAtomic` |
| 跨目录搬大文件 | `copyFile` |

## 备注

- 原生库加载失败时(极少数 ABI 不匹配场景)所有函数**自动退化为 Java 实现**,
  接口与返回值不变,只是速度回到普通水平;可用 `isNativeAvailable()` 确认。
- 不提供编码自动探测:编码由调用方显式给出,`charset` 非法时回退 `UTF-8`。
- 不做路径沙箱限制:权限与路径合法性由调用方保证,与 `io.*` 一致。
