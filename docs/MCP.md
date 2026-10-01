# MCP 服务(LuaForge-Studio 1.6.2)

把编辑器的能力(读写代码、调试运行、构建 APK、界面检查)通过 [Model Context Protocol](https://modelcontextprotocol.io) 暴露给外部 AI 客户端。

## 1. 启用

设置 → **MCP 服务** → 打开“启用 MCP 服务”。

| 项 | 说明 |
| --- | --- |
| 监听端口 | 1024–65535,默认 `8787`;修改后服务自动重启 |
| 需要访问令牌 | 开启后请求须携带 `Authorization: Bearer <令牌>`;开启时若令牌为空会自动生成 |

启用后设置页会显示全部可用地址,例如 `http://192.168.1.5:8787`。

## 2. 协议

- 传输:HTTP/1.1,`POST /` 承载 JSON-RPC 2.0(`Content-Type: application/json`)
- `GET /` 返回服务状态(运行状态、端口、工具数量、地址)
- `OPTIONS` 返回 204,带 CORS 头,可直接被浏览器侧客户端调用
- 协议版本:`2024-11-05`
- 支持方法:`initialize`、`ping`、`tools/list`、`tools/call`、`resources/list`、`prompts/list`
- JSON-RPC 通知(无 `id`)返回 HTTP 202,无响应体

自检:

```bash
curl http://127.0.0.1:8787/
curl -X POST http://127.0.0.1:8787/ -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## 3. 客户端配置

```json
{
  "mcpServers": {
    "luaforge": {
      "type": "http",
      "url": "http://192.168.1.5:8787"
    }
  }
}
```

若开启了令牌,追加请求头:

```json
{ "headers": { "Authorization": "Bearer <你的令牌>" } }
```

## 4. 工具清单(42 个)

参数均为可选,除非标 **必填**。带 `array` 的参数可传 JSON 数组,也可传换行/逗号分隔的字符串。

### 项目与文件

| 工具 | 说明 | 参数 |
| --- | --- | --- |
| `list_projects` | 列出所有项目(名称与路径) | — |
| `list_files` | 列出项目或指定目录的文件树 | `path`、`recursive`(默认 true)、`maxEntries`(默认 500) |
| `read_file` | 读取文本文件内容 | `path` **必填** |
| `read_files` | 一次读取多个文本文件 | `paths` **必填**(数组) |
| `write_file` | 覆盖写入文本文件(自动创建父目录),并刷新编辑器中已打开的同名标签 | `path` **必填**、`content` **必填** |
| `create_file` | 新建文件,已存在时默认不覆盖 | `path` **必填**、`content`、`overwrite` |
| `delete_file` | 删除文件或**空**目录,并关闭对应编辑器标签 | `path` **必填** |
| `rename_file` | 重命名/移动文件或目录,并同步编辑器标签 | `from` **必填**、`to` **必填**、`overwrite` |
| `make_directory` | 递归创建目录 | `path` **必填** |
| `file_info` | 文件/目录信息(大小、修改时间、行数、MD5) | `path` **必填** |
| `search_in_files` | 在项目内按文本或正则搜索,返回命中文件与行号 | `path`、`query` **必填**、`regex`、`ignoreCase`、`maxResults` |
| `replace_in_file` | 单个文件内替换(支持正则与出现次数断言) | `path` **必填**、`find` **必填**、`replace` **必填**、`regex`、`ignoreCase`、`expectCount` |
| `replace_in_files` | 项目内批量替换,支持 `dryRun` 预览 | `path`、`find` **必填**、`replace` **必填**、`regex`、`ignoreCase`、`dryRun` |
| `get_project_info` | 项目信息(settings.json、文件数、图标等) | `path` |
| `backup_project` | 备份项目到 `LuaForge-Studio/backup` | `path` |
| `restore_backup` | 把备份 zip 还原为项目 | `backupPath` **必填**、`projectName`、`overwrite` |
| `list_templates` | 列出可用于新建项目的模板 | — |
| `create_project` | 按模板新建项目 | `name`、`packageName`、`template`、`debugMode`、`globalUtils`、`overwrite` |
| `analyze_imports` | 分析代码用到的类,生成 import 列表 | `content`(缺省用当前活动文件) |

> `delete_file` 只能删空目录,递归删除请由客户端先列目录再逐个删除。

### 编辑器

| 工具 | 说明 | 参数 |
| --- | --- | --- |
| `get_editor_state` | 已打开标签、活动文件、未保存改动 | — |
| `open_file` | 在编辑器中打开文件 | `path` **必填**、`line` |
| `set_editor_content` | 替换当前活动文件全部内容并落盘 | `content` **必填** |
| `insert_text` | 在光标处插入文本 | `text` **必填** |
| `get_selection` | 读取当前选中的文本与光标位置 | — |
| `goto_line` | 把光标移动到指定行(1 起算) | `line` **必填**、`column` |
| `refresh_editor` | 用磁盘最新内容重载编辑器(外部改动后强制刷新界面);缺省刷新全部已打开文件 | `path` |
| `editor_history` | 撤销/重做当前活动文件的编辑 | `action` **必填**(`undo` / `redo`) |
| `save_files` | 保存所有已修改文件 | — |
| `format_code` | 格式化当前活动文件 | — |
| `check_syntax` | 对 Lua/ALY 代码做语法检查,返回是否通过及错误行 | `path`、`content`(缺省用当前活动文件) |
| `get_settings` | 读取应用设置(主题、编辑器等) | — |

### 构建与运行

| 工具 | 说明 | 参数 |
| --- | --- | --- |
| `compile_file` | 编译单个 `.lua`/`.aly`;默认在验证成功后**删除** `.luac`/`.alyc` 产物 | `path`(缺省用活动文件)、`keepOutput`(true 时保留产物) |
| `clean_compiled` | 清理项目内的编译产物(历史残留) | `path`、`dryRun` |
| `build_apk` | 构建并签名 APK,返回输出路径 | `path` |
| `run_project` | 调试运行项目(启动 `LuaActivity` 加载 `main.lua`) | `path` |
| `install_apk` | 唤起系统安装器安装 APK | `path` **必填** |

> `compile_file` 的产物命名规则:`main.lua` → `main.luac`,`main.aly` → `main.alyc`。
> 需求是“只验证能否编译”时保持默认即可;需要产物时显式传 `keepOutput=true`。

### 运行时界面检查

| 工具 | 说明 | 参数 |
| --- | --- | --- |
| `dump_screen` | 导出前台界面控件树 | `includeInvisible`、`maxDepth`(默认 40) |
| `check_screen` | 按预期校验界面,返回是否通过及差异 | `expectTexts`、`expectAnyOf`、`absentTexts`、`minViews`、`includeInvisible` |
| `wait_for_text` | 轮询等待界面出现(或消失)指定文本,用于运行后确认界面就绪 | `text` **必填**、`absent`、`timeoutMs`(默认 10000)、`intervalMs`(默认 300) |
| `get_runtime_errors` | 读取日志中的运行时错误 | `lines`(默认 50) |
| `clear_logs` | 清空 `luaforge.log` | — |
| `get_logs` | 读取应用日志末尾内容 | `lines`(默认 200) |

## 5. 运行时界面检查工作流

这是本版本新增的核心能力:**不再只看进程是否起来,而是断言屏幕是否真的符合预期**。

```text
clear_logs                      # 1. 清空日志,取得干净的现场
run_project                     # 2. 启动调试运行
wait_for_text text="Hello"      # 3. 等待界面就绪(避免固定 sleep)
dump_screen                     # 4. 查看界面树(可选)
check_screen expectTexts=["Hello"] minViews=3
                                # 5. 断言;未通过时 isError=true
get_runtime_errors              # 6. 若失败,读取 Lua 报错 / 布局加载失败
```

编辑-验证闭环同样建议串起来:

```text
write_file path=".../main.lua" content="..."
refresh_editor                  # 让编辑器界面立刻反映磁盘内容
check_syntax                    # 语法自检
compile_file keepOutput=false   # 编译验证,成功后自动清理产物
```

`check_screen` 返回:

```json
{
  "passed": false,
  "failures": ["缺少文本: Hello"],
  "passedChecks": ["未出现文本: Runtime error"],
  "detectedErrorMarkers": ["is not a field"],
  "textCount": 4,
  "texts": ["..."]
}
```

- 文本匹配为**子串**匹配,`expectTexts` 需全部出现,`expectAnyOf` 命中其一即可,`absentTexts` 必须都不出现
- 参数可传 JSON 数组,也可传换行/逗号分隔的字符串
- `detectedErrorMarkers` 自动识别 `runtime error`、`is not a field`、`layout loading error`、`traceback` 等错误弹窗特征
- 未通过时同时返回 `"isError": true`,客户端可直接据此判定

## 6. 路径与安全

- 读写删除仅允许应用项目根目录(默认 `LuaForge-Studio/projects`,可在设置中改)及其下的 `build`、`backup`
- 路径统一做 `canonicalPath` 归一化后前缀匹配,已过滤空值与文件系统根
- 默认只监听本机可访问的 LAN 地址;服务随应用进程存活,退出应用即停止
- 勾选“需要访问令牌”后可限制访问来源
- 日志文件:`/storage/emulated/0/LuaForge-Studio/luaforge.log`

## 7. 已知限制

- 服务为进程内实现,**未做前台 Service**:应用被系统杀掉后服务随之停止
- 编辑器相关操作必须在主线程执行,`open_file` 依赖 API 34+ 的编辑器实现
- `check_screen` 只读取当前前台 Activity;界面未启动时会返回“没有前台 Activity”
- 单次 `dump_screen` 最多 3000 个节点,超出部分被截断并置 `truncated=true`
- 日志读取上限 512KB / `read_file` 上限 2MB
