package com.luaforge.studio.collab

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.luaforge.studio.utils.LogCatcher
import com.luaforge.studio.mcp.EditorBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    /** 供 Compose 观察的运行状态。 */
    var status by mutableStateOf(CollabStatus())
        private set

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /** 以共享端身份启动:在 [projectDir] 上建立协同仓库并提供服务。 */
    @Synchronized
    fun startHost(projectDir: File, projectName: String, port: Int, token: String): Boolean {
        stop()
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
     */
    @Synchronized
    fun startClient(
        remoteHost: String,
        remotePort: Int,
        token: String,
        localDir: File,
        onChanged: (() -> Unit)? = null
    ): Boolean {
        stop()
        return try {
            if (!localDir.exists()) localDir.mkdirs()
            val c = CollabClient(remoteHost, remotePort, token)
            val info = c.info()
            if (info == null) {
                status = CollabStatus(error = "无法连接共享端 $remoteHost:$remotePort")
                return false
            }
            client = c
            repo = CollabRepo(localDir)
            status = CollabStatus(
                running = true,
                role = CollabRole.CLIENT,
                projectName = info.name,
                projectPath = localDir.absolutePath,
                port = remotePort,
                remoteHost = remoteHost,
                addresses = listOf("http://$remoteHost:$remotePort"),
                rev = info.rev,
                head = info.head
            )
            // 全量同步
            initialSync(localDir)
            startPolling(onChanged)
            LogCatcher.i(TAG, "已连接共享端,开始实时同步")
            true
        } catch (e: Exception) {
            LogCatcher.e(TAG, "连接共享端异常", e)
            status = CollabStatus(error = e.message ?: "未知错误")
            false
        }
    }

    /** 首次全量同步:拉取清单与全部文件。 */
    private fun initialSync(localDir: File) {
        val c = client ?: return
        val paths = c.manifest()
        for (path in paths) {
            val content = c.file(path) ?: continue
            val target = File(localDir, path)
            target.parentFile?.mkdirs()
            target.writeText(content, Charsets.UTF_8)
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
                var changedAny = false
                for (change in changes) {
                    val target = File(localDir, change.path)
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
                    since = maxOf(since, change.rev)
                }
                val info = c.info()
                status = status.copy(rev = since, head = info?.head ?: status.head)
                if (changedAny) {
                    try { onChanged?.invoke() } catch (_: Exception) {}
                }
            }
        }
    }

    @Synchronized
    fun stop() {
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

    private const val POLL_INTERVAL_MS = 1500L
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
    val error: String? = null
)
