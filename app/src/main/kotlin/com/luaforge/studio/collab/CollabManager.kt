package com.luaforge.studio.collab

import android.content.Context
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.luaforge.studio.mcp.EditorBridge
import com.luaforge.studio.ui.settings.SettingsManager
import com.luaforge.studio.utils.LogCatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 局域网协同开发管理器(进程级单例)。
 *
 * 两种角色:
 * - **共享端(host)**:在本地项目上启动 [CollabServer],暴露项目供他人连接;
 * - **连接端(client)**:连接远端共享端,把项目同步到本地,并轮询增量变更实时刷新。
 *
 * 状态以 Compose State 暴露,设置页 / 协同页可直接观察。
 */
object CollabManager {

    private const val TAG = "CollabManager"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var server: CollabServer? = null

    @Volatile
    private var repo: CollabRepo? = null

    private var client: CollabClient? = null
    private var syncJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    /** 串行化 start/stop 的状态切换;不再用 @Synchronized(suspend 中持锁会跨挂起点)。 */
    private val stateLock = Any()

    /** 供 Compose 观察的运行状态。 */
    var status by mutableStateOf(CollabStatus())
        private set

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 以共享端身份启动:在 [projectDir] 上建立协同仓库并提供服务。
     *
     * **只允许共享项目根目录内的项目**:越界路径(例如 `/sdcard`、应用私有目录、
     * 其他 App 的目录)一律拒绝,避免把不该暴露的文件通过局域网共享出去。
     * UI 侧已用文件选择器的 rootPath 锁根,这里是**第二道校验**,
     * 防止旧配置残留或直接构造调用绕过界面。
     */
    suspend fun startHost(projectDir: File, projectName: String, port: Int, token: String): Boolean =
        withContext(Dispatchers.IO) {
            synchronized(stateLock) {
                stopInternal()
                if (!isShareableProjectDir(projectDir.absolutePath)) {
                    status = CollabStatus(
                        error = "只允许共享项目目录内的项目:${sharedProjectRoot().absolutePath}"
                    )
                    LogCatcher.i(
                        TAG,
                        "拒绝共享越界目录:${projectDir.absolutePath}"
                    )
                    return@withContext false
                }
                startHostLocked(projectDir, projectName, port, token)
            }
        }

    private fun startHostLocked(projectDir: File, projectName: String, port: Int, token: String): Boolean {
        return try {
            val r = CollabRepo(projectDir)
            val instance = CollabServer(r, port, token, projectName)
            val started = instance.start()
            if (started) {
                repo = r
                server = instance
                status = CollabStatus(
                    running = true,
                    role = CollabRole.HOST,
                    projectName = projectName,
                    projectPath = projectDir.absolutePath,
                    port = instance.boundPort,
                    addresses = CollabServer.localAddresses().map { "http://$it:${instance.boundPort}" },
                    rev = r.currentRev(),
                    head = r.headRevision()
                )
                LogCatcher.i(TAG, "共享端已启动:$projectName @ ${instance.boundPort}")
            } else {
                status = CollabStatus(error = "端口 $port 绑定失败或被占用")
            }
            started
        } catch (e: Exception) {
            LogCatcher.e(TAG, "启动共享端异常", e)
            status = CollabStatus(error = e.message ?: "未知错误")
            false
        }
    }

    /**
     * 以连接端身份连接:[remoteHost]:[remotePort] 的共享项目同步到 [localDir]。
     * 首次为全量拉取,随后轮询增量变更。
     *
     * @param localDir 本地落地目录。**传 null 时自动推导**为
     *        `<项目库根>/<远端项目名>`,即连上就把所选项目下载到本地项目库,
     *        不需要用户事先挑目录。
     */
    suspend fun startClient(
        remoteHost: String,
        remotePort: Int,
        token: String,
        localDir: File? = null,
        onChanged: (() -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val normalizedHost = normalizeHost(remoteHost)
        if (normalizedHost.isBlank()) {
            status = CollabStatus(error = "共享端地址无效:$remoteHost")
            return@withContext false
        }
        synchronized(stateLock) {
            stopInternal()
            startClientLocked(normalizedHost, remotePort, token, localDir, onChanged)
        }
    }

    /**
     * 远端项目在本地的落地目录:`<项目库根>/<项目名>`。
     *
     * 项目名会做文件名安全化(去掉路径分隔符与 `..`),避免恶意/异常的项目名
     * 把文件写到项目库之外。
     */
    fun localDirFor(projectName: String): File {
        val safe = projectName
            .replace('\\', '_')
            .replace('/', '_')
            .trim()
            .ifBlank { "shared_project" }
        return File(sharedProjectRoot(), safe)
    }

    private fun startClientLocked(
        remoteHost: String,
        remotePort: Int,
        token: String,
        localDir: File?,
        onChanged: (() -> Unit)?
    ): Boolean {
        return try {
            val c = CollabClient(remoteHost, remotePort, token)
            val info = c.info()
            if (info == null) {
                status = CollabStatus(error = "无法连接共享端 $remoteHost:$remotePort")
                return false
            }
            // 未指定落地目录时,连上后按远端项目名自动下载到本地项目库
            val target = localDir ?: localDirFor(info.name)
            if (!target.exists()) target.mkdirs()
            if (!target.isDirectory) {
                status = CollabStatus(error = "本地目录不可用:${target.absolutePath}")
                return false
            }

            client = c
            repo = CollabRepo(target)
            status = CollabStatus(
                running = true,
                role = CollabRole.CLIENT,
                projectName = info.name,
                projectPath = target.absolutePath,
                port = remotePort,
                remoteHost = remoteHost,
                addresses = listOf("http://$remoteHost:$remotePort"),
                rev = info.rev,
                head = info.head,
                // 连接成功后立即进入「下载中」,供 UI 展示进度
                syncing = true,
                syncTotal = 0,
                syncDone = 0
            )
            // 全量同步(自动下载所选项目)
            initialSync(target)
            status = status.copy(syncing = false)
            startPolling(onChanged)
            LogCatcher.i(TAG, "已连接共享端并完成首次同步:${target.absolutePath}")
            true
        } catch (e: Exception) {
            LogCatcher.e(TAG, "连接共享端异常", e)
            // 失败时必须回滚已赋值的 client/repo,否则 isRunning() 会返回 true,
            // 而 status 显示的是错误态,造成状态不一致。
            stopInternal()
            status = CollabStatus(error = e.message ?: "未知错误")
            false
        }
    }

    /**
     * 首次全量同步:拉取清单与全部文件到 [localDir]。
     *
     * 落盘时会校验目标路径仍在 [localDir] 内:远端清单里的 `../` 之类越界路径
     * 一律跳过,避免共享端被篡改后把文件写到本地项目之外。
     */
    private fun initialSync(localDir: File) {
        val c = client ?: return
        val base = try { localDir.canonicalFile } catch (_: Exception) { localDir.absoluteFile }
        val paths = c.manifest()
        status = status.copy(syncing = true, syncTotal = paths.size, syncDone = 0)
        var done = 0
        for (path in paths) {
            done++
            val target = safeChildOf(base, path)
            if (target != null) {
                val content = c.file(path)
                if (content != null) {
                    target.parentFile?.mkdirs()
                    target.writeText(content, Charsets.UTF_8)
                }
            } else {
                LogCatcher.i(TAG, "跳过越界路径:$path")
            }
            status = status.copy(syncDone = done)
        }
    }

    /** 把远端的相对路径安全地拼到 [base] 下;越界返回 null。 */
    private fun safeChildOf(base: File, relativePath: String): File? {
        return try {
            val rel = relativePath.replace('\\', '/').trimStart('/')
            if (rel.isEmpty()) return null
            val candidate = File(base, rel).canonicalFile
            val basePath = base.canonicalPath
            if (candidate.path == basePath) return null
            if (!candidate.path.startsWith(basePath + File.separator)) return null
            candidate
        } catch (_: Exception) {
            null
        }
    }

    /** 若指定文件在编辑器中打开,则用磁盘最新内容刷新其缓冲区。 */
    private suspend fun refreshEditor(absolutePath: String) {
        val vm = EditorBridge.currentViewModel() ?: return
        try {
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                runCatching { vm.refreshEditorFromDisk(absolutePath) }
            }
        } catch (_: Exception) {
        }
    }

    /** 启动增量轮询。 */
    private fun startPolling(onChanged: (() -> Unit)?) {
        syncJob?.cancel()
        syncJob = scope.launch {
            var since = status.rev
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val c = client ?: break
                val changes = c.changesSince(since) ?: continue
                if (changes.isEmpty()) continue
                val localDir = status.projectPath?.let { File(it) } ?: continue
                val base = try { localDir.canonicalFile } catch (_: Exception) { localDir.absoluteFile }
                var changedAny = false
                for (change in changes) {
                    // 与全量同步同样做越界防护:远端路径不得跳出本地项目目录
                    val target = safeChildOf(base, change.path)
                    since = maxOf(since, change.rev)
                    if (target == null) {
                        LogCatcher.i(TAG, "跳过越界变更:${change.path}")
                        continue
                    }
                    if (change.deleted) {
                        target.delete()
                        refreshEditor(target.absolutePath)
                        changedAny = true
                    } else {
                        val content = c.file(change.path) ?: continue
                        target.parentFile?.mkdirs()
                        // 仅在内容变化时写入,避免无谓刷新
                        val existing = if (target.exists()) target.readText(Charsets.UTF_8) else null
                        if (existing != content) {
                            target.writeText(content, Charsets.UTF_8)
                            // 该文件若正在编辑器中打开,立即把新内容推回,实现编写即刷新
                            refreshEditor(target.absolutePath)
                            changedAny = true
                        }
                    }
                }
                val info = c.info()
                status = status.copy(rev = since, head = info?.head ?: status.head)
                if (changedAny) {
                    try { onChanged?.invoke() } catch (_: Exception) {}
                }
            }
        }
    }

    /** 停止协同(共享端 / 连接端通用)。线程安全,可在主线程调用。 */
    fun stop() {
        synchronized(stateLock) { stopInternal() }
    }

    /** 在持有 [stateLock] 的前提下执行实际的停止逻辑。 */
    private fun stopInternal() {
        server?.stop()
        server = null
        syncJob?.cancel()
        syncJob = null
        client = null
        repo = null
        status = CollabStatus()
    }

    fun isRunning(): Boolean = server?.isRunning == true || client != null

    /** 当前协同仓库(共享端可用)。 */
    fun currentRepo(): CollabRepo? = repo

    /**
     * 记录一次本地文件变更(共享端由 MCP 写入时调用,以便广播给连接端)。
     * 传入绝对路径时会自动换算为项目内相对路径;项目外路径直接忽略。
     */
    fun recordLocalChange(path: String, deleted: Boolean): Long? {
        val r = repo ?: return null
        val rel = try {
            val base = r.projectDir.canonicalPath
            val abs = File(path).canonicalPath
            when {
                abs == base -> ""
                abs.startsWith("$base/") -> abs.substring(base.length + 1)
                else -> return null
            }
        } catch (_: Exception) {
            return null
        }
        if (rel.isBlank()) return null
        return r.recordChange(rel, deleted)
    }

    /**
     * 归一化用户填写的共享端地址。
     *
     * 容忍以下几种常见输入:
     * - `192.168.1.10`            → 原样
     * - `http://192.168.1.10`     → 去掉 scheme
     * - `192.168.1.10:9188`       → 去掉端口
     * - `  192.168.1.10/  `       → 去空白与尾斜杠
     */
    private fun normalizeHost(raw: String): String {
        var h = raw.trim()
        if (h.isEmpty()) return ""
        val scheme = h.indexOf("://")
        if (scheme >= 0) h = h.substring(scheme + 3)
        h = h.substringBefore('/')
        // IPv6 形如 [::1]:9188 —— 只保留方括号内的地址部分(端口由 remotePort 单独传);
        // 裸 IPv4 / 主机名直接用 : 截断端口。
        h = if (h.startsWith("[")) {
            val end = h.indexOf(']')
            if (end > 0) h.substring(0, end + 1) else h
        } else {
            h.substringBefore(':')
        }
        return h.trim()
    }

    private const val POLL_INTERVAL_MS = 1500L

    // ------------------------------------------------------------------
    // 共享范围限制
    // ------------------------------------------------------------------

    /**
     * 允许共享的项目根目录:`<外部存储>/LuaForge-Studio/project`。
     *
     * 与应用内项目的固定存储位置保持一致(见 SettingsManager 的
     * projectStoragePath 默认值),这样「共享端只共享自己项目库里的项目」
     * 不会因为设置里的自定义路径而漂移。
     */
    fun sharedProjectRoot(): File {
        val settings = SettingsManager.currentSettings
        val configured = settings.projectStoragePath
        if (configured.isNotBlank()) return File(configured).absoluteFile
        return File(Environment.getExternalStorageDirectory(), "LuaForge-Studio/project").absoluteFile
    }

    /**
     * 判断 [path] 是否为**允许共享的项目目录**。
     *
     * 判定规则:
     * - 必须存在且是目录;
     * - 必须严格位于 [sharedProjectRoot] 之下,**不能等于根目录本身**(共享整个项目库
     *   会把所有项目一起暴露);
     * - 必须直接是根目录的一级子目录,即 `root/<项目名>`,防止把子目录片段或
     *   更深的嵌套目录单独共享出去。
     *
     * 比较时使用规范化路径(canonicalPath),因此 `..`、软链接等都无法绕过。
     */
    fun isShareableProjectDir(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return try {
            val root = sharedProjectRoot().canonicalFile
            val target = File(path).canonicalFile
            if (!target.exists() || !target.isDirectory) return false
            // 一级子目录:父目录必须正好是 root
            target.parentFile?.canonicalPath == root.canonicalPath
        } catch (_: Exception) {
            false
        }
    }

    /** 供 UI 直接使用:把用户选的目录纠正为合法的项目目录,null 表示不合法。 */
    fun resolveShareableProjectDir(path: String?): File? =
        if (isShareableProjectDir(path)) File(path!!).absoluteFile else null
}

/** 协同角色。 */
enum class CollabRole { HOST, CLIENT }

/** 协同运行状态快照。 */
data class CollabStatus(
    val running: Boolean = false,
    val role: CollabRole = CollabRole.HOST,
    val projectName: String = "",
    val projectPath: String? = null,
    val port: Int = 9188,
    val remoteHost: String = "",
    val addresses: List<String> = emptyList(),
    val rev: Long = 0L,
    val head: String? = null,
    val error: String? = null,
    /** 正在首次全量下载 */
    val syncing: Boolean = false,
    /** 本次全量下载的总文件数 */
    val syncTotal: Int = 0,
    /** 本次全量下载已完成数 */
    val syncDone: Int = 0
)
