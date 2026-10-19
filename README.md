# 更新日志

## 1.6.9

- **新增:Lua 原生 Compose(内置桥,单一分发方式)**
  - Lua 可直接书写 Compose 界面:`compose(fn)` / `composeView(fn)` / `composeContent(fn)` 返回挂到 Compose 的 SlotTable,`state(v)` 的读写走快照系统,状态变化**按 group 精确重组**
  - **65 个组件**:布局 `Column`/`Row`/`Box`/`FlowRow`/`FlowColumn`/`ScrollColumn`/`ScrollRow`;容器 `Card`/`ElevatedCard`/`OutlinedCard`/`Surface`/`Scaffold`;懒加载 `LazyColumn`/`LazyRow`/`LazyVerticalGrid`/`LazyHorizontalGrid`;文本图形 `Text`/`Icon`/`Image`/`Spacer`/`Divider`/`VerticalDivider`/`CircularProgressIndicator`/`LinearProgressIndicator`/`Badge`/`BadgedBox`;按钮族 12 种(`Button`/`ElevatedButton`/`FilledTonalButton`/`OutlinedButton`/`TextButton`/`IconButton` 3 种/FAB 4 种);选择输入 `Switch`/`Checkbox`/`RadioButton`/`Slider`/`RangeSlider`/`TextField`/`OutlinedTextField`;Chip 4 种;导航 `TopAppBar` 4 种/`BottomAppBar`/`NavigationBar`/`NavigationRail`/`TabRow`/`ScrollableTabRow`/`Tab`;`AlertDialog`/`Dialog`;`AndroidView`;`LaunchedEffect`
    - 另含 `state(v)`、`rangeState(a,b)`、`remember(fn)` 三个状态函数
    - **`AndroidView` 让现有控件都能用**:`AndroidView({ view = 某个原生View })` 可把任何 Android 控件(`BottomNavigationView`/`TabLayout`/`RecyclerView`/`ExoVideoView`/`MaterialTextField`/`WebView`...)直接嵌进 Compose 树 —— "未封装"不等于"不能用"
  - 属性转换统一:间距 `padding`/`paddingH`/`paddingV`/`gap`/`contentPadding`、尺寸 `width`/`height`/`size`/`fillMax*`/`aspectRatio`、视觉 `background`/`corner`/`borderWidth`+`borderColor`/`shadow`/`alpha`/`offsetX`+`offsetY`、子项弹性 `weight`、颜色 `0xAARRGGBB` 或 `"#RRGGBB"`/`"#AARRGGBB"`、`size`(sp)/`bold`/`maxLines`/`align`/`space`/`overflow`/`scale`
    - Modifier 应用顺序为**尺寸 → 位移 → 圆角/背景/描边/阴影/透明 → 间距 → 点击**,背景与圆角在 `padding` 之前,避免边缘露白
  - **全局名与 `import` 的冲突**:通配 `import "android.widget.*"` 走 `_G.__index`,不会覆盖同名组件;但**显式** `import "android.widget.Button"` 会覆盖。为此额外注册 `Compose` 命名空间表(`Compose.Button` / `Compose.state` ...),与全局名是同一份实现
  - **单一分发方式**:`LuaCompose` 随 `core` 一起编译进 `core.apk`,项目开箱可用,无需任何配置
    - 已移除早期的「方案 C」(项目自带 `libs/compose_bridge.dex`)+ `-PlfComposeBundled=false` 零体积发行版。理由:两套 Compose 运行时不能混用(内置优先只是绕过问题,ART 仍可能随机命中不同版本的同名类,表现为真机上偶发 `NoSuchMethodError`),而维护成本落在每个用户项目上,收益仅是未用 Compose 的项目省约 1.5MB dex
    - 桥源码位于独立源集 `core/src/compose/kotlin`,与 `src/main/kotlin` 分开只为让「这批文件依赖 Compose 编译器插件」这件事更明显
  - 打包侧:`BuildSlimmer.usesCompose` 探测项目是否真用 Compose,只在用到时追加 Compose 的 keep 规则;`ApkBuilder` 如实播报体积来源
  - 新增文档 `assets/doc/Compose.md`(MCP `read_dev_doc` 可读);`SKILL.md` 增 §1.10.1
  - **`size` 属性双语义**:`Spacer`/`Box`/`Image` 上是**尺寸(dp)**,`Text`/`Icon`(回退文字)上是**字号(sp)**。早期版本无条件按 dp 处理,导致 `Text("x", size = 20)` 同时被加 `.size(20.dp)`,文字被挤进 20dp 方块里换行截断 —— 表现为「布局显示不全」
  - **edge-to-edge 适配**:API 35+ 系统强制 edge-to-edge,而 `composeContent` / `setContentView` 会替换掉 `LuaActivity` 自己那个挂了 inset 监听的根布局,导致内容被状态栏压住。桥现在自己在 ComposeView 上装 inset 监听(仅 API≥35 且父容器是 `android.R.id.content` 时生效)
- **协同改为双向同步,并修复"同步把项目文件改坏"** (P0–P3)
  - **P0 根因修复:统一原子写 + 项目级写锁**
    - 新增 `utils/ProjectFileIO`:`writeTextAtomic`/`writeBytesAtomic`(同目录 tmp → `fd.sync()` → rename)、`lockFor`/`lockForProjectFile`(按 `settings.json`/`.luaforge`/`.git` 向上探测项目根)、`md5Of`
    - 编辑器保存(`saveAllModifiedFiles`/`saveAllFilesSilently`/`saveCurrentFileSilently`)、布局助手回写、MCP 写入、`CollabRepo`、`GitStore`(对象/ref/HEAD/config/checkout)**全部改走原子写**,读者不再可能看到半截文件
  - **P0 协议修复:清单与传输**
    - 新增 `GET /collab/files` 返回**工作区全量清单 + MD5**;修掉旧实现用 `changedFiles()`(与 HEAD 的 diff)当清单、共享端工作区干净时**首次同步下 0 个文件**的致命 bug
    - `GET /collab/file` 改 **Base64 + MD5**,修二进制(图片/`libs/*.dex`/加密 lua)经 JSON 字符串往返被替换成 U+FFFD 的内容损坏
    - `.luaforge` 加入忽略名单,修元数据被互相同步污染 rev 的问题
  - **P1 双向同步**
    - 新增 `POST /collab/put`、`POST /collab/delete`,带 `baseMd5` 冲突检测(不一致返 409 + `remoteMd5`,`force` 可强推)
    - 连接端 `CollabManager` 重写为**三方比对**(本地 / 远端 / 上次同步基线):只有本地变了→推,只有远端变了→拉,两边都变且不同→**记冲突、保留本地**,不再"谁新算谁赢"地互相静默覆盖
    - 首次同步同样走三方比对,连一个已有内容的本地目录**不会再把本地代码冲掉**
    - 首个 `runBlocking` 死锁隐患消除:同步函数改为**纯同步只做磁盘 IO**,待刷新路径由 suspend 调用方在主线程统一刷新
  - **P2 自动快照 + 冲突可见**
    - 本轮将覆盖本地时**先自动快照**(工作区干净则跳过,不堆空提交),留一条回档后路
    - 变更日志与 rev 的崩溃补偿:**先写日志、后写 rev**,`currentRev()` 取两者较大值 —— 修掉"rev 已增、日志未写"导致增量同步永久漏文件的断链
    - 协同页新增冲突裁决卡(逐条「用我的」/「用对端」)与「上传本地改动」按钮
  - **P3 编辑器侧冲突与只读保护**
    - `refreshEditorFromDisk` 增 `fromExternal` / `source`:外部写入(MCP、协同)遇到**用户未保存的编辑**不再静默覆盖,登记冲突由用户裁决(MCP 的 `write_file`/批量替换已按外部写入传参)
    - 修复 `>1MB` 文件把 `"文件过大"` **当成文件内容载入**、用户一保存就用这句提示覆盖真实文件的严重 bug;改为**只读占位**(`readOnlyReason` + `isWritable`)并在全部保存路径拒绝写盘


## 1.6.8

- **新增「构建精简」开关**(设置 → 构建与签名,位于自定义签名之上,默认关闭)
  - 开启后只为项目**声明/引用**到的类生成 dex:扫描 `.lua/.aly` 里的 `import "..."`、`luajava.bindClass/newInstance("...")` 收集类名,连同必须类白名单生成 R8 keep 规则做可达性裁剪
  - **自动剔除 APK 内重复类**:项目 jar 中与 `core.apk` 模板重复的类被移除;整个 jar 都被模板覆盖时直接跳过;`libs/*.dex` 只做检测(全量重复才整体丢弃),避免破坏仍被需要的 dex
  - 必须类白名单覆盖 Lua 运行时/JNI 桥、Media3 反射发现的 HLS/DASH/SS/RTSP/RTMP、常用 Material/AndroidX 控件等静态分析不可见的入口
  - **不开启混淆**(`-dontobfuscate`):`luajava.bindClass("com.foo.Bar")` 依赖字符串类名,混淆后运行期即崩
  - R8 失败自动回退 D8 直转,构建不会因精简而失败;关闭开关时行为与旧版一致
  - 顺带修复 Maven 依赖被重复转 dex 的既有问题(此前 Java 编译链会把同一批 Maven JAR 再转一遍,导致同类落进两个 `classes*.dex`)
- **设置项合并**
  - 「通知提示」并入**主题与外观**;「错误日志」并入安全防护分区,该分区更名为**安全、网络与日志**
- **新增「网络代理」**(设置 → 安全、网络与日志,位于网络请求拦截与防火墙之间,**默认空白 = 直连**)
  - 支持 `host:port`、`http://host:port`、`socks5://host:port` 三种写法;端口须在 1..65535,非法输入标红且不改变当前生效值
  - 作用于编辑器自身走 OkHttp 的出口:**Maven 依赖下载**与内置 `OkHttpUtil`;配置变更后按 `NetworkProxy.revision` 自动重建 client,无需重启
  - **不走代理**:局域网协同(直连局域网 IP)、打包后的用户 APK(`:core-apk` 不含设置逻辑)、`Http.*`(裸 `HttpURLConnection`)
  - 实现在 `core` 的 `NetworkProxy`,仅 `:app` 会调用 `configure`,因此打包产物永远直连
- **开发文档随 APK 打包 + 新增 MCP 文档工具**
  - `SKILL.md`、`MCP.md`、`NETWORK.md` 由仓库根/`docs/` 移入 `app/src/main/assets/devdoc/`,构建后可直接在设备内读取
  - 新增 MCP `list_dev_docs`(列出文档与推荐阅读顺序)与 `read_dev_doc`(按名读取、支持按行分段),SKILL.md §0 增加「任何编码任务开始前先查文档」入口;工具总数 53 → 55
- **MCP 新增 `copy_file` 工具**:支持从其他项目/目录复制文件或目录到当前项目内
  - `source` 支持绝对路径、`fromProject/相对路径`,或直接「项目名/子路径」
  - `target` 指定项目内目标路径(缺省用源文件名置于项目根),`overwrite` / `recursive` 可控
  - 目标始终限制在当前项目根目录内,越界写入会被拒绝
- **TV / 大屏兼容性增强**:manifest 声明 `android.software.leanback`(required=false)与触摸屏非必需,新增 TV banner 与 `LEANBACK_LAUNCHER` 入口;项目卡片与设置项加入遥控器(D-pad)焦点高亮
- **新增视频/音频播放组件(Media3 / ExoPlayer)**:内置 `ExoVideoView`(带控制器,支持 HLS/DASH/RTSP/RTMP)与 `ExoAudioPlayer`(纯音频,支持后台音效)
  - 已接入 `BuiltinWidgets`、布局助手 `classes.lua` / `layoutData.lua` 与 `loadlayout2.lua` 白名单,并提供 `assets/doc` 文档
- **MCP 前台保活与通知栏常驻**
  - 新增「前台保活」开关:以前台服务(DataSync)常驻,降低退后台被系统回收的概率
  - 新增「通知栏常驻」:保活期间显示常驻通知,点击通知上的按钮可一键复制 MCP 访问地址
  - 新增「电池优化豁免」入口,一键拉起系统白名单授权
- **自定义签名文件设置**:可在「构建与签名」中指定自己的 `.jks` / `.keystore`(文件、库口令、别名、密钥口令);未配置或文件不可读时自动回退内置默认签名
- **局域网项目协同开发**
  - 内置**极简 Git 实现**(标准松散对象,可被 git 命令行读取):支持快照提交、提交历史与**回档**
  - 共享端:选择项目目录启动协同服务,局域网内其他设备可连接;连接端:把远端项目同步到本地目录
  - 连接端轮询增量变更,文件写入/删除后**实时刷新**编写端与客户端;MCP 的写入/删除会登记为变更广播给连接端
  - 新增「协同开发」页面(侧栏入口),提供角色切换、地址/端口/令牌配置、快照与回档
  - **共享范围收紧**:共享端只能选项目库根目录(`/storage/emulated/0/LuaForge-Studio/project/`)下的**一级项目文件夹**
    - 选择对话框锁根(`rootPath`),面包屑与「上一级」都无法越界;项目库根目录本身也不允许共享
    - 启动时二次校验(canonicalPath 比对),旧配置残留或手工改设置绕过界面也会被拒绝
    - 全量同步与增量同步都会校验远端路径,`../` 之类越界路径直接跳过并记日志
  - **连接端自动下载**:连上共享端即按远端项目名自动下载到本地项目库,不再需要手动挑落地目录
    - 下载过程在协同页显示 `正在下载 N / M` 进度,完成后提示实际落地路径
- **性能优化**
  - 修复**布局助手返回后主线程阻塞写盘**:`replaceCurrentFileContent` 原来在 ActivityResult 回调(主线程)里用 `runBlocking` 同步写文件,现在整体挪到 IO 线程,写盘完成后回主线程重开文件
  - 修复**文件选择器主线程扫盘**:`loadFilesAndDirectories` 改为挂起函数并在 IO 线程执行 `listFiles` + 逐项 `stat`;扩展名过滤的集合预先归一化,不再对每个文件重复 `map`
  - 修复**设置项逐字符落盘**:网络代理输入框原来每敲一个字符就调用一次 `saveSettings`(重写全部设置键),改为停止输入 500ms 后防抖写入一次
  - 修复**协同共享端热路径全库扫描**:`/collab/info` 不再统计 `fileCount`,原先每次请求都会对整个工作区逐文件读内容算 sha1,而连接端每批变更都会拉一次
- **设置项重组**
  - 语法高亮颜色合并进「编辑器配置」,不再单列卡片
  - 「构件设置」(打包架构)从「编辑器配置」移出,与「自定义签名文件」合并为独立的「构建与签名」卡片

## 1.6.7

- **修复日志 "失败 0" 被误记为 ERROR**:`RuntimeLog.logLua` 原按关键词(失败/错误/error...)判级别,把 `[svg] 校验失败 0`、`校验失败总数 = 0`、`23 items, 0 errors` 这类"零失败"的成功播报也记成 `[ERROR]`
  - 现改为:先查强错误信号(`traceback`/`attempt to`/`nil value`/`layout loading error`...),再对**以「关键词...0」或「0...关键词」收尾**的消息抑制为 INFO;关键词与 0 之间只允许分隔符与有限计数标签,`错误: 除数为 0` 这类真错误不受影响
  - 强错误信号优先于零计数抑制,避免真错误被误判为成功
- **Https 表化的向后兼容**:`get`/`post`/`upload`/`download` 仍注册在全局表 `Https` 下,同时把同一实现注册为**裸全局兼容别名**——仅当该全局名当前为空时注册,已被占用则不覆盖(不覆盖用户自定义函数)。旧项目 `get(...)` / `post(...)` 不再 `attempt to call a nil value`
  - `LuaFunctionRegistrar.registerLegacyHttpAlias` 统一处理;`describeFunctions` 同步暴露这四个裸名,保持「查看=运行时」
- **运行期按名加载接入 `lib/<abi>/`**:`LuaActivity.loadLib` / `LuaService.loadLib` 之前只查 `libDir` 与 `luaDir/lib*.so`,现按设备首选 ABI 优先查 `luaDir/lib/<abi>/lib<名>.so`,再回退 `lib/lib<名>.so`(与 `LuaDexLoader.loadArchLibs` 目录约定一致)
- **构件设置 ABI 三模式文案精简**:`同时包含 32 位与 64 位...` 等描述缩短为一行(中英同步),导航栏三块固定等高(标题 1 行 + 说明最多 2 行、超出省略),中英文换行数不同也不参差
- **彻底弃用旧「调试控制台」**，改用内置 `debugger.lua`（浮窗式调试器，来源 Aqora，已适配本项目 Lua 方言）
  - 删除 app 模块 `com.luaforge.studio.console.**`（约 40 文件）、core 侧 `ConsoleBridgeRef` 反射门面、MCP `ConsoleTools.kt` 与 manifest 里的 `ConsoleInitializer` 提供者
  - `core`/`a` 侧移除全部控制台埋点：`LuaActivity` 的 traced require / `onEvent` / 音量键唤回浮球 / `reportConsoleError`，`LuaJavaAPI` 的方法调用拦截与 popup 捕获，`LuaPrint` 回归原始简单实现
  - 适配点：本项目把 `lambda` 作为**解析器保留字**（`lambda(params) -> expr`），`debugger.lua` 原用的 `lambda(t):method(...)` 无法编译 → 改写为普通 `SimpleClass(t)`；不替换 `_G.error`（避免破坏 `pcall`/`assert`），仅接管 `_G.onError`；浮窗 `LayoutParams` 补 `type`（API≥26 用 `TYPE_APPLICATION_OVERLAY` 并查 `Settings.canDrawOverlays`，否则 `TYPE_PHONE`，无权限时回退挂 `decorView`）；颜色优先 `require "Colors"`，失败回退内置色
  - 加载源**内置到 assets**：`app/src/main/assets/debugger.lua`，由 `DebuggerHost`（LuaSessionHook）在会话启动时注入
- **所有 print 与调试信息统一走 debugger**
  - `debugger.lua` 覆盖全局 `print`：写入浮窗缓冲，并 `pcall(context.sendMsg)` 保留 `luaforge.log` 落盘
  - Lua 报错经 `_G.onError` 进入浮窗；`LuaActivity.sendError` 改为 `runFunc("onError", ...)` 未接管时才回退 `sendMsg`
  - `core` 的 `LuaSessionHook` 改为多槽接口并新增 `onSessionEnd(LuaState)`，`onSessionStart` 增加 `boolean toolLaunch` 形参；`LuaActivity` 提供 `addSessionHook`/`removeSessionHook`，工具型启动（布局助手）经 `debugger_disable` extra 抑制注入
- **MCP**：移除 6 个 `console_*` 工具，新增 3 个 debugger 工具
  - `debugger_status`：当前运行页面 / 项目 / 浮窗是否注入 / 缓冲条数
  - `debugger_outputs`：读取浮窗缓冲（print / 报错），支持 `limit`、`keyword` 过滤
  - `debugger_clear`：清空浮窗缓冲
  - 数据源为运行中 LuaState 暴露的只读全局 `__lfDebugger`，与浮窗展示同源
- print/报错只进调试浮窗，不再在屏幕重复弹 Toast
  - 浮窗接管 `print`/`onError` 后调用 `LuaActivity.setDebuggerActive(true)`，`MainHandler` 在该标记期间跳过旧 Toast 回显（仅落盘 `luaforge.log`）
  - 浮窗销毁时复位标记，非调试运行保持原有 Toast 行为
- 浮窗与 PopupMenu 弹窗改用圆角（20dp）：浮窗底色随模式在 `Colors.colorPrimary` / 报错红之间切换，PopupMenu 经 `mPopup` 反射铺圆角背景
- **Jetpack Compose 支持**：Lua 用 table 描述 UI 树，由 Kotlin 桥渲染为 Material3 组件(桥随 core 编译,无外部 dex 方案)
  - 新增全局函数 `compose(tree)`（返回可挂载的 `ComposeView`）/ `composeContent(tree)`（直接设为当前页面内容视图），由 `LuaActivity.initLua` 注册
  - 支持标签：`Column` / `Row` / `Box` / `Card` / `Text` / `Button` / `Spacer` / `Divider`；通用属性 `padding` / `fillMaxWidth` / `width` / `height` / `background` / `corner`；颜色写作 `0xAARRGGBB`
  - 不直接调用 `@Composable`：`@Composable` 函数签名被编译器改写（注入 `Composer`）且必须在 composition 上下文执行，Lua 无法直接调用，故采用「描述树 → 渲染」形态
  - 新增模板 `templates/Compose.zip`（可直接运行的最小示例）与文档 `assets/doc/Compose.md`
- 编辑器新增**符号自动补全**（设置 → 编辑器配置，默认开启）
  - 点击底部符号栏的 `(` `[` `{` `"` `'` 时自动补成对，并把光标移到括号中间；关闭后仅插入单个符号
- 设置新增**打包设置**卡片：选择打包时保留的 CPU 架构
  - 三选一：**通用版**（32 位 + 64 位）/ **arm64-v8a**（64 位）/ **armeabi-v7a**（32 位）
  - 清理阶段按选择裁剪 APK 的 `lib/` 目录，减小产物体积
- **MCP 默认端口由 `8787` 改为 `9123`**
  - 代码 `McpManager.kt` / `SettingsManager.kt` 三处默认值同步；设置页仍可改（1024–65535）
  - 文档 `assets/devdoc/MCP.md`、`assets/devdoc/SKILL.md` 中示例地址一并更新
- 设置 → 编辑器配置：移除「滑动手势」开关及其在编辑器中的上下滑动显隐快捷功能栏逻辑
  - 同步清理 `CodeEditorView` 的触摸监听、`EditorTabs`/`CodeEditScreen` 的透传链路、`SettingsManager` 字段与 `get_settings` 暴露项
- 修复与增强**布局助手**（`assets/layouthelper/main.lua`）
  - 修复：启动诊断误报。无三方库的项目（N 三方库）本应 `dex_loaders=0 scanned=0`，旧实现却把这条无害统计经 `Error()` 记为 `[ERROR]`，且日志出现 `[Layouthelper] [Layouthelper]` 双前缀 → 现统一日志出口（仅本函数补标签），诊断降级为 `INFO`，并追加 `libs_exists` / `lib_files` / `third_party_views` 便于判断
  - 修复：图片属性候选漏配 `.jpeg` / `.webp` / `.bmp` / `.gif`，且未做大写兼容 → `addDir` 补齐并大小写不敏感
  - 增强：项目 `libs/*.dex` 中解析出的三方 View 子类会**注册为全局控件**并加入「添加控件」列表（新增「三方控件」分类，上限 80 个）；点击时若未预注册，经 `_G.__lfThirdParty` 懒解析；仍解析不出时给出明确报错而非静默失败
  - 修复：`libsDir.listFiles()` 为 nil 时不再崩溃（空目录保护）
- **代码补全范围扩大**：新增项目函数与全局函数补全
  - 跨文件扫描项目内 `.lua`/`.aly` 的 `function name(...)` 定义并加入补全（跳过注释、长注释与 `build`/`.git` 等目录）
  - `function M.foo(...)` / `M:foo(...)` 归入「表名 → 成员」，输入 `M.` 时提示成员
  - 同时把 `settings.json` 的 `global_utils` 在运行时会注册的全局函数（经 `LuaFunctionRegistrar.describeFunctions`，与运行时同源）一并补全
  - 接入点 `EditorViewModel.refreshExtraFunctions()`（打开项目时于 IO 线程扫描并注入已打开编辑器）
- **网络请求函数改为全局表 `Https`**：`get`/`post`/`upload`/`download` 由裸全局名改为 `Https.get` / `Https.post` / `Https.upload` / `Https.download`
  - 反射注册路径同步跳过 OkHttpUtil 的这四个方法，确保裸全局名不再注册（`registerUtilClass` / `describeFunctions` / `findUtilMethod` 共用 `skipReflectiveMethod` 规则）
  - `OkHttpUtil.kt` 重构：四处重复逻辑合并为 `applyCommonHeaders` + `execute`，`OkHttpClient` 改双重检查锁，响应体一律 `use{}` 关闭避免连接泄漏
  - 文档 `assets/doc/OkHttpUtil.md` 与 MCP 的 `note` 同步为 `Https.*`
- 编辑器配置调整
  - 移除「智能排序」开关（及 `SettingsManager` / `CodeEditScreen` / `DraggableSymbolPanel` 中整套频率统计逻辑）
  - 新增「长按选中整行」开关（设置 → 编辑器配置，默认开启），关闭后长按不再整行选中
  - 编辑器配置各项统一改为**默认开启**（`enableTabHistory` / `completionCaseSensitive` / `hexColorHighlightEnabled` 等）
- **原生库与三方库动态加载**
  - 新增项目 `lib/<abi>/`（`arm64-v8a` / `armeabi-v7a`）分架构加载原生库，无该 ABI 子目录时回退 `lib/*.so`；`libs/` 继续加载扁平 `.so` 与动态加载 `*.dex` / `*.jar`（`LuaDexLoader.loadArchLibs` / `loadLibs`）
  - 加载后 `require "模块名"`（`libcjson.so` → `require "cjson"`）；`libs/` 的 dex 加载后其类可按裸类名或全限定名直接在 Lua 使用
  - MCP 新增 `list_project_libraries` 工具，列出项目 `lib/<abi>/*.so`、`libs/*.dex|jar`、`libs/*.so` 与设备首选 ABI（工具数 48 → 49）
- **错误日志:记录 / 查看 / 导出 + Lua 错误不再拖崩编辑器**
  - `LuaActivity` 的错误出口统一收敛为 `sendError(String, Throwable)` 并加**重入守卫**:`onError` 回调自身再报错时只落盘、不再递归(此前会 StackOverflow 把整个进程带崩)
  - `runFunc` / `doString` 的 catch 从 `LuaException` 放宽到 `Throwable`;`MainHandler` 的 case 1/2/3 加兜底,主线程 Handler 抛出的异常不再直接终止进程
  - 设置页新增「错误日志」卡片:记录开关(`webide_log_config`,默认开)、查看(只读 `[ERROR]` 行)、导出(FileProvider 分享 `luaforge.log`)、清除
  - 新增 `LogFileAccess`(读/清空日志),开关状态经 `LogConfigRepository.isLogEnabled()` 读取
- **编辑器 ViewModel 泄漏修复**:`EditorViewModel.onCleared()` 原来用 `removeListener { }` 移除设置监听器 —— 传入的是新 lambda,`equals` 不成立,原监听器一直留在 `SettingsManager`(单例)里持有已销毁的 ViewModel。改为保存同一引用后按引用移除
- **构建类型精简**:删除「未加密版」(`BuildType.UNENCRYPTED`),构建对话框只剩 跟随项目设置 / Debug / Release
- **项目属性文案**:「加密项目」→「Lua代码加密」,「合并dex」→「第三方库合并」(中英同步)
- **设置页合并与改造**
  - 「打包设置」与「编辑器配置」合并进同一张卡片,并更名为**构件设置**
  - ABI 选择改为**导航栏样式**:三种模式横排、选中高亮、必选其一(`AbiTargetNavBar` 取代原 `AbiTargetOption` 单选列)
- **关于页**:新增开发者「恣桐」,并将 GitHub 仓库地址统一为 https://github.com/VerdGard/LuaForge-Studio
- **模板统一补齐目录**:`ProjectUtil.extractTemplate()` 解包后为所有模板创建 `libs/`、`lib/arm64-v8a/`、`lib/armeabi-v7a/`
- **core / core-apk 只出 Release 变体**:`androidComponents { beforeVariants(debug) { it.enable = false } }`;`app` 的 `debug` 构建补 `matchingFallbacks += listOf("release")`
- **MCP:运行完成后取信息并退回编辑界面**
  - `run_project` 新增 `waitMs` / `capture`(`debugger`/`errors`/`log`) / `clearLogs` / `captureLines` / `stop` / `returnToEditor`:`stop=true` 时在 `waitMs` 后抓取运行输出、结束运行并回到编辑界面
  - 新增 `stop_project` 工具,用于单独关闭残留运行实例并回编辑界面(工具数 49 → 50)
- **MCP:读文件不转义 + 大文件按行读写与校验**
  - `read_file` 新增 `raw=true`(直接返回文件原文,不包 JSON、不做转义)、`startLine`/`endLine`(1 起算闭区间)、`withMd5`;`read_files` 同步
  - `write_file` 新增 `mode`(`overwrite`/`replaceLines`/`append`) 与 `expectedMd5`/`expectedLines` 写入前校验;返回带 `lines`/`md5` 便于比对
- **文档**:`assets/devdoc/SKILL.md` 工具数同步 50 并补「一次性运行取信息」流程、大文件读写校验、错误日志入口;`assets/devdoc/MCP.md` 同步工具清单与 `run_project`/`stop_project`/`read_file`/`write_file` 说明
- **打包设置文案**：「仅 64 位」改为 `arm64-v8a`、「仅 32 位」改为 `armeabi-v7a`（中英文案同步）
- **废除 Lua 侧 Jetpack Compose 桥与液态玻璃控件**
  - 移除全局函数 `compose(tree)` / `composeContent(tree)` 与 `LuaCompose.kt`（注册处 `LuaActivity.initLua` 一并删除）；Lua 项目改回 `loadlayout` / 原生 View 体系
  - 移除内置控件 `LiquidGlassView`（`core` 的 `widget/glass`、backdrop 依赖 `io.github.kyant0:backdrop-android`）；`BuiltinWidgets`、布局助手 `classes.lua` / `layoutData.lua`、白名单注释同步清理
  - `core` 模块不再需要 Compose：移除 `kotlin.compose` 插件、`buildFeatures.compose` 与 `compose.ui` / `foundation` / `material3` 依赖（IDE 自身 UI 仍在 `app` 模块使用 Compose，不受影响）
  - 删除模板 `Compose.zip` / `LiquidGlass.zip` 与文档 `doc/Compose.md` / `doc/LiquidGlassView.md`；`list_widgets` 示例改回 `MaterialTextField`
- **移除 `MemUtil` 全局工具**
  - 删除 `core` 的 `MemUtil.kt` 与底层 `libmemkit.so`（JNI 源 `app/src/main/jni/memkit/`、构建入口 `CMakeLists.txt` 一并移除）
  - `settings.json` 的 `global_utils` 白名单由 7 项减为 6 项；`LuaFunctionRegistrar.UTIL_CLASS_MAP`、新建项目「全局工具」选项、文档 `doc/MemUtil.md` 同步清理
  - `ApkBuilder.UTIL_NATIVE_LIBS`（用于打包时保留工具类原生库）整体移除
- **修复调试浮窗的两个显示问题**
  - 返回编辑器时浮窗延迟销毁：根因是浮窗走系统级悬浮窗（`TYPE_APPLICATION_OVERLAY`），独立于运行界面生命周期，要等 `LuaActivity.onDestroy` 才移除
  - 浮窗会浮现在其他程序之上：现在浮窗始终挂到运行界面的 `DecorView`，与运行界面同窗口 —— 界面不可见即隐藏、界面销毁即移除，仅在本项目运行界面显示
  
## 1.6.5
- 新增 `MemUtil` 全局工具类与底层 `libmemkit.so`(C 实现,内存**读写 / 搜索**,风格接近 GameGuardian)
  - 在项目 `settings.json` 的 `global_utils` 加入 `MemUtil` 即注册为 Lua 全局函数;也可 `require "memkit"` 直接用原生接口(可选参数语义更完整)
  - 能力:root 检测、进程名→pid、`/proc/<pid>/maps` 区域枚举、原始字节 / 整数(按位宽符号扩展)/ 浮点 / C 字符串的读写,以及 GG 式搜索(首扫 + 续扫原地缩小 + 结果遍历 + 批量修改 + 当前值快照刷新);类型覆盖 `byte` / `word` / `dword` / `qword` / `float` / `double` / `utf8`
  - 权限模型如实反映内核约束:**自身进程**无需 root(走 `process_vm_readv/writev`);跨进程需 `PTRACE_MODE_ATTACH`(root 或同 uid 同签名),Android 10+ SELinux enforcing 下即使 root 也可能被拒 —— 失败时带 errno 抛异常,不静默返回 0
  - 单点读写失败抛 `IllegalStateException`(含 pid / addr / errno),用 `pcall` 包裹;批量搜索不抛(部分区域不可读属常态)
  - 函数名统一加 `mem` 前缀(`memScan` / `memReadInt` / ...),避免污染全局命名空间;详见 [app/src/main/assets/doc/MemUtil.md](app/src/main/assets/doc/MemUtil.md)
- 修复打包时 `global_utils` 工具类的原生库被当「未引用」删除(即 `MemUtil` 打包失效)
  - 根因:`cleanUnusedLibraries` 只按 Lua 侧 `require` / `import` 扫描出的模块判定 `.so` 去留,而工具类是在 **Java 侧** `System.loadLibrary`,脚本里**不会**出现 `require "xxx"` → 该 `.so` 永远匹配不上,打包时被删
  - 症状:IDE 内预览正常(用 `nativeLibraryDir` 里的库),打包出的 APK 里该工具类整体失效、直到调用才报错
  - 现在新增 `UTIL_NATIVE_LIBS`(工具类名 → 其 `loadLibrary` 的原生库)映射,清理阶段显式保留;清理策略仍是「引用才保留」,非工具类项目体积不受影响

## 1.6.4

- 构建项目支持选择构建类型（构建时弹出 MD3 对话框）
  - **跟随项目设置**（默认）：加密与调试模式均取项目属性里的设置
  - **未加密版**：Lua / ALY 源码不做加密处理，以明文脚本打包
  - **Debug 版**：开启调试模式（打包内 `settings.json` 的 `debugmode` 置为 true）
  - **Release 版**：关闭调试模式（`debugmode` 置为 false）
  - 未加密与调试模式是两个独立维度：只有「未加密版」会关掉加密，调试模式默认仍沿用项目自身设置
- 设置 → MCP 服务：每个访问地址独立一行并带**复制按钮**，一键复制到剪贴板
- 设置 → 安全防护 → 网络请求拦截：允许 / 拒绝主机名单支持**单条删除**与**清空全部**，列表行改为卡片式并配删除按钮
  - 名单超过 5 条时自动折叠，可展开查看并逐条清理
- MCP：新增 `global_utils` 工具类的查看与调用能力，工具总数 42 → 44
  - `list_global_utils`：查看项目 `global_utils` 配置，以及这些工具类在**运行时会实际注册**的 Lua 全局函数（参数、返回类型、context 注入、同名覆盖）
    - 注册清单与运行时反射注册共用同一套规则，不会与运行结果漂移
    - 顺带暴露 `unknown`（settings.json 里拼错的名字，运行时只会静默跳过）
    - 同时返回当前正在运行的页面（`runningPages`），便于确认调用目标
  - `call_global_util`：在**运行中**的项目里调用 `global_utils` 注册的 Lua 全局函数（如 `dp2px`、`parseColor`）
    - 与项目自身调用**同一条路径**（运行实例主线程执行），`dp2px`、`statusBarHeight()` 这类需要真实 `Context` 的 UI 函数也生效（首个 `Context` / `Activity` 形参由框架自动注入）
    - 参数支持 JSON 数组或分隔字符串（自动推断 number / boolean / null），并按形参类型做个数与类型校验
    - 需要控件 / Java 对象 / Lua 回调的函数（如 `GlideUtil.loadImage` 需 `ImageView`、网络请求、Recycler 适配器）会明确拒绝并说明原因
- 新增「调试控制台」（浮球 + 非全屏面板，竖屏 BottomSheet / 横屏侧栏两种形态）
  - 浮球：自适应长方形胶囊，支持拖动与点击，未读 Lua 错误以内嵌计数芯片呈现；崩溃时转为 error 色
  - 面板 6 页签：输出 / 结构 / 环境 / Logcat / 调试 / 设置（页签由左侧竖排导航承担）
    - 输出：print / Lua 报错 / Toast / Snackbar，按文件分组，含逐参 Lua 类型与真实类型
    - 结构：当前项目文件树（惰性展开），选中 `.lua` 显示悬浮操作栏
    - 环境：环境信息 / Lua 模块 / 原生库 / Java 类库 四类折叠卡
    - Logcat：按等级（V / D / I / W / E / F / S）过滤，日志分块懒加载、主线程只增量追加
    - 调试：重启项目 / 重建当前文件
    - 设置：输出 / 拦截 / 报错 分组开关（默认全折叠）
  - 面板不占满全屏（内容区固定高度），关闭重开保留上次页签
  - 仅在**调试运行（debugmode 项目）**时激活捕获；从隐藏 / 关闭状态按音量下键可唤回浮球
- MCP：适配调试控制台，新增 6 个只读工具，工具总数 44 → 50
  - `console_status`：控制台状态（会话 / 面板 / 当前文件与布局 / 捕获开关 / 缓冲与错误计数）
  - `console_outputs`：控制台「输出」缓冲（print / 报错 / Toast / Snackbar，含逐参 Lua 类型与真实类型）
  - `console_events`：控制台事件条目（Lua 侧被实际调用的函数及参数摘要）
  - `console_modules`：控制台「环境」信息（require 模块 / bindClass 类 / `libs/*.dex` 方法签名）
  - `console_logcat`：本调试会话 logcat（与控制台「Logcat」页同源）
  - `console_clear`：清空控制台输出缓冲（与 `clear_logs` 清 `luaforge.log` 互补）
  - 与 MCP 既有 `get_logs` / `get_runtime_errors`（读 `luaforge.log` 文本）不同，这批工具读的是控制台的**结构化缓冲**，与浮窗展示同源
  - 仅在**调试运行（debugmode 项目）**时产生数据；详见 [assets/devdoc/MCP.md](app/src/main/assets/devdoc/MCP.md)
- 修复 `global_utils` 注册函数的小数入参调用失败
  - 之前 `LuaState.toJavaObject` 得到的 `Double` 直接传给 `Float` / `Int` 形参会抛 `argument type mismatch`（如 `dp2px(16.5)`）
  - 现在按形参类型做数值收窄，不再依赖 Java 的自动转换
- `LuaActivity` 新增运行实例定位能力（`getPageName` / `getRunningActivities` / `getActivityByLuaDir`），并给 `sLuaActivityMap` 的读写加同步
- 新增 `memory` 库（`core/src/main/resources/lua/memory.lua`），纯 Lua 内存管理，`require "memory"` 即可使用
  - `start(cfg)`：自动模式，内部用 `Handler` + 协程定时巡检，不额外起线程
  - `start_manual(cfg)` + `tick()`：手动模式，由项目自己驱动巡检节奏
  - 自适应 GC：按当前用量与峰值比例动态调 `setpause` / `setstepmul`
  - 泄漏检测：滚动窗口采样（最多 10 次）统计平均增长，持续增长即告警并回收；回落则进入冷却或分步 GC
  - `stop()` / `monitoring()` / `force_gc()` / `get_status()` / `on_destroy()` / `help()`
  - 入参越界（如 `pause`、`stepmul`、`interval`）自动收敛到合法默认值
- 新增**液态玻璃控件** `LiquidGlassView`（基于 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) 的 backdrop 效果）
  - 用 `ComposeView` 把 Compose 侧效果包成普通 Android View，使 `loadlayout` / 布局助手能像普通控件一样使用
  - 属性：`cornerRadius`（圆角 px）、`blurRadius`（背景模糊 px）、`glassColor`（玻璃底色 ARGB）
  - 已同步：布局助手控件清单（`classes.lua` 短名 + `layoutData.lua`「特殊视图」分类）、白名单放行 `com.luaforge.studio.`、文档 `assets/doc/LiquidGlassView.md`
  - MCP 新增 `list_widgets`，列出内置控件及其可写属性
  - 低端 / 旧版本设备由库自行降级（跳过不支持的 RenderEffect / RuntimeShader），不崩溃
- 三方控件支持（设置 → 编辑器配置，默认开启）
  - 使用 `.aly` 布局前预载项目 `libs/*.dex|jar`，并把 `DexClassLoader` 链路打通到裸类名解析
    - 背景：`luajava.bindClass` 走 `Class.forName`，只看宿主 classpath，看不到 `activity.loadDex` 追加的装载器
  - 关闭后仅允许系统与官方控件（布局助手按白名单门控）
- 项目属性新增「图标路径」（`settings.json` 顶层 `iconPath`）
  - 路径框只读 + 文件夹按钮：仅改引用路径，不复制文件，文件选择器锁根在项目内
  - 点击大图更换图片：走内置文件选择器（默认 `/sdcard/DCIM`），选中即复制到项目根并保持原名
  - 路径自检：绝对路径 / 含 `..` / 空值一律回退 `icon.png` 并回写修正
  - 项目卡片缩略图跟随 `iconPath`，保存后即时刷新
- 项目属性新增「构建选项」：`encrypt`（加密项目）与 `mergeDex`（合并 `libs/*.dex`），存于 `settings.json` 的 `application` 层
  - `mergeDex` 开启时把 `libs/*.dex` 移出 `assets`，并按现有 `classes*.dex` 续号重命名到 APK 根
- 新增「防火墙」（设置 → 安全防护，两项默认开启）
  - 越级写入拦截：运行中的项目写入 / 删除 / 重命名其它项目目录时拦截，读取不受影响
  - 自我守护：拦截项目删除或移动 LuaForge-Studio 工作目录的行为
  - Lua 侧改写写入入口：`io.open` / `io.output`、`os.remove` / `os.rename`、`os.execute` / `io.popen`（含危险命令词法判定）、`lfs.remove` / `rmdir` / `rename`
  - Java 侧同步拦截 `File`、`LuaUtil`、`Runtime.exec`、`ProcessBuilder` 与输出流构造
  - 命中时弹 MD3 对话框说明，并按项目累计「已守护 N 次」，计数显示在设置页
  - 仅在编辑器内运行项目时生效（需项目开启调试模式）；打包成 APK 后不含防火墙逻辑
- 设置 → 安全防护：由原「网络请求拦截」卡片扩展而来，内含「网络请求拦截」「防火墙」两个分区标题
- 布局助手（`loadlayout`）：修复 `@id/xxx`、`@+id/xxx` 形式的控件引用
  - 之前引用未在 `views` 表注册的 id 会直接抛 `attempt to index a nil value`
  - 现在先剥离 `@id/` / `@+id/` 前缀再查表，仍找不到时给出可读报错（`deferred attribute references undefined id: xxx`）
  - 核心库 `core/src/main/resources/lua/loadlayout.lua` 与布局助手 `app/src/main/assets/layouthelper/loadlayout2.lua` 双源同步
- 布局助手：控件清单移除 9 个易混淆 / 已废弃控件，并加入容错合并
  - 移除 `AppCompatImageButton`、`AppCompatCheckedTextView`、`AppCompatRatingBar`、`AppCompatToggleButton`、`AbsoluteLayout`、`CheckedTextView`、`ImageButton`、`RatingBar`、`ToggleButton`
  - 类名表与中文名表改为按表长容错合并，两表长度不一致时不再因 `nil` 拼接崩溃
- 文件树：文件/文件夹长按菜单新增「复制相对路径」（相对项目根目录）
- 编辑器：自动换行支持**按项目独立**（设置 → 编辑器配置可开关，默认开启）
  - 关闭时所有项目共用同一个全局换行设置；切换项目 / 文件后立即应用，无需手动再切一次
- 编辑器：长按顶部运行按钮可「运行当前文件」（当前标签为 `.lua` 时展开菜单）
  - 单击运行按钮行为不变，仍运行项目入口 `main.lua`
- 崩溃页：新增崩溃现场诊断 `[Diagnostics]`，分区展示
  - 全线程 dump：标注非 `RUNNABLE` 线程状态，便于交叉定位卡死线程与持锁线程
  - `LuaState Registry`：`LuaStateFactory.snapshotStates()` 列出所有注册过的 native 指针及 wrapper 是否已关闭
    - 只读裸指针字段，不调用 `LuaState` 的同步方法，避免在「LuaState monitor 卡死」现场让采集线程自锁
  - `Running Lua`：列出当前运行中的 Lua 页面及其 `luaDir`
  - 完整诊断同时落盘 `filesDir/crash_report/`，文件名带毫秒时间戳，供 adb pull 挖掘
  - 采集上限 300 KB，超限截断，避免 dump 撑爆 Binder Intent
  
## 1.6.3
- 新增「网络请求拦截」(设置 → 网络请求拦截,默认关闭)
  - 所有网络请求在真正发出前弹出 MD3 对话框展示方法、主机、完整地址、请求头、请求体与来源,用户允许后才放行
  - 覆盖范围:编辑器自身请求 + 用户项目请求
    - `http.*`(HttpURLConnection)、`okhttp.*`(OkHttp 拦截器);图片加载与 WebView 请求不拦截
  - 按主机记忆允许/拒绝,列表可在设置中查看与清空;也可随时关闭整个开关
  - 用户项目打包成 APK 后**不含**拦截逻辑(`:app` 的决策器不进入 `:core-apk`),因此打包后不拦截
  - 详见 [assets/devdoc/NETWORK.md](app/src/main/assets/devdoc/NETWORK.md)
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
- 详见 [assets/devdoc/MCP.md](app/src/main/assets/devdoc/MCP.md)

## 1.6.1
- 修复 `loadlayout` 因属性别名导致整棵布局加载失败的问题
  - 支持 `width` / `height` / `weight` / `margin*` 等常见别名,自动归一化为 `layout_*`
  - 未知或无法设置的属性改为记录告警后跳过,不再中断整棵布局
- Lua 运行时错误现在会写入 `luaforge.log`(此前仅输出到 logcat,无法事后排查)
- MCP 新增运行时界面检查能力:`dump_screen` / `check_screen` / `get_runtime_errors` / `clear_logs`
  - 调试运行后可断言屏幕内容是否符合预期,并自动识别错误弹窗
  - 详见 [assets/devdoc/MCP.md](app/src/main/assets/devdoc/MCP.md)

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
