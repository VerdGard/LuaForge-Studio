# 网络与权限

本文说清两件容易踩坑的事:

1. **网络权限**怎么声明,漏了会怎么失败,以及 1.7.0 新增的自动申请开关;
2. **两条 Lua 网络通道**(`Http` / `Https`)的用法与回调约定。

> 历史说明:1.7.0 移除了「网络请求拦截」(逐请求弹框确认)与「网络代理」
> (进程级 `ProxySelector`),以及「文件防火墙」。编辑器不再对用户的网络或
> 文件写入做任何中间干预。下面只描述当前实际存在的行为。

---

## 1. 网络权限

`settings.json` 的 `user_permission` 必须含 `INTERNET`(Default 模板已含)。

漏配时请求在网络层直接失败,`Http.*` 回调里表现为 `code == -1`。

```json
{
  "user_permission": ["INTERNET", "ACCESS_NETWORK_STATE"]
}
```

`ACCESS_NETWORK_STATE` 只在你需要判断网络可用性时用;普通请求不需要。
两者都是 **install-time 权限**,不会弹任何系统对话框。

### 1.1 自动申请权限(1.7.0 新增)

项目属性页「权限」卡片里有 **自动申请权限** 开关,**默认关闭**(`settings.json`
顶层布尔字段 `auto_request_permissions`)。

开启后,`LuaActivity.onCreate` 会在页面脚本执行之前:

1. 只在 `SDK_INT >= 23` 时动作;
2. 遍历 `user_permission`,短名补 `android.permission.` 前缀;
3. **只挑运行时权限** —— 判定方式是查系统 `protectionLevel == dangerous`,
   不是硬编码名单(新系统加权限不用改代码);
4. 跳过已授予的;**全部已授予时不做空调用**(空数组调用在部分 ROM 上无回调);
5. 一次性 `requestPermissions(..., REQUEST_CODE_AUTO_PERMISSIONS = 0x4C46)`;
6. 结果进全局 `onRequestPermissionsResult(requestCode, permissions, grantResults)`;
7. 任何异常都吞掉 —— 申请失败不能让页面起不来。

要点:

- `INTERNET` / `ACCESS_NETWORK_STATE` 是 install-time 权限,**不会被这一步申请**;
- 关闭时(默认)申请时机完全由脚本决定;
- 老项目 `settings.json` 里没有这个键 → 行为与 1.7.0 之前完全一致。

在 Lua 里自己接管结果:

```lua
function onRequestPermissionsResult(requestCode, permissions, grantResults)
  if requestCode ~= 0x4C46 then return false end
  for i, p in ipairs(permissions) do
    print("权限 " .. p .. " 结果: " .. tostring(grantResults[i]))
  end
  return true      -- 返回 true 表示已处理
end
```

---

## 2. 两条 Lua 网络通道

### 2.1 首选 `Http`(`com.androlua.Http`)

基于 `HttpURLConnection`。`require "import"` 之后可直接写 `Http.get(...)`
(`_G` 元表会依次尝试 `''` / `java.lang.` / `java.util.` / `com.androlua.` 前缀)。

```lua
-- GET:回调 (code, content, cookie, headers)
Http.get("https://api.example.com/data", function(code, content, cookie, headers)
  if code == 200 then print(content) end
end)

-- 带自定义头:顺序是 url, cookie, charset, header, callback
Http.get("https://api.example.com/user", nil, "UTF-8",
  {["Authorization"] = "Bearer t"},
  function(code, content, cookie, headers) end)

-- POST:第二参可为字符串或表单字段表
Http.post("https://api.example.com/login", {user = "a", pass = "b"},
  function(code, content, cookie, headers) end)

-- 下载:回调是 (code, path, headers)
Http.download("https://example.com/a.zip", "/sdcard/a.zip",
  function(code, path, headers) end)
```

- 静态方法:`get` / `post` / `put` / `delete` / `download`
- **失败时只有两个参数** `(code, message)`,且 `code == -1`
- 全局默认头:`Http.setHeader(t)` / `setCookie(s)` / `setUserAgent(s)` / `setReferer(s)`
- 回调在 `onPostExecute`(**主线程**)触发,可直接更新 UI
- ⚠️ **不要写 `import "Http"`**:`import(package)` 不做前缀补全,短名会报
  `cannot find Http`;短名解析只发生在 `_G` 的元表访问路径上

### 2.2 次选 `Https.*`(OkHttp)

仅当 `settings.json` 的 `global_utils` 含 `OkHttpUtil` 时才存在,提供
`Https.get` / `Https.post` / `Https.upload` / `Https.download`。
详见 `doc/OkHttpUtil.md`。

> 旧写法裸名 `get(...)` / `post(...)` 只是兼容别名,且**仅在该全局名未被占用时**才注册。
> 新代码一律写 `Https.*`。

### 2.3 原生 socket

```lua
local socket = require "socket.core"    -- ⚠️ 没有 luaopen_socket,require "socket" 会失败
```

基于原生 `libsocket.so`,与 Java 层无关。

---

## 3. 已知限制

- `Http` 的回调在**主线程**,里面做重活会卡 UI;耗时处理请转 `task(...)`。
- 下载大文件时 `Http.download` 不报告进度;需要进度用 `Https.download` 或原生 `socket`。
- `INTERNET` 权限缺失时**所有**网络请求都静默失败(`code == -1`),不会有异常;
  排查时先 `get_project_info` 看 `user_permission` 里有没有 `INTERNET`。
- WebView 与 `socket` 不受 JVM 侧任何配置影响(本来也没有配置了)。

---

## 4. 事实来源

- `Http`:`core/src/main/java/com/androlua/Http.java`
- `OkHttpUtil`:`core/src/main/kotlin/com/luaforge/studio/utils/OkHttpUtil.kt`
- 权限自动申请:`core/src/main/java/com/androlua/LuaActivity.java`
  (`REQUEST_CODE_AUTO_PERMISSIONS`、`requestDeclaredPermissions`、`isRuntimePermission`)、
  `app/src/main/kotlin/com/luaforge/studio/ui/attribute/AttributeScreen.kt`、
  `app/src/main/kotlin/com/luaforge/studio/utils/ProjectUtil.kt`(模板默认值)
- 内置原生库清单:`app/src/main/jni/CMakeLists.txt`
