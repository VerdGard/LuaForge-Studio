package com.luaforge.studio.collab

import android.content.Context
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.luaforge.studio.mcp.EditorBridge
import com.luaforge.studio.ui.settings.SettingsManager
import com.luaforge.studio.utils.LogCatcher
import com.luaforge.studio.utils.ProjectFileIO
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
 * - **共享端(host)**:在本地项目上启动 [CollabServer],暴露项目供他人连接,
 *   并**监视本地改动**登记为变更(编辑器保存、MCP 写入、协同 put 都算);
 * - **连接端(client)**:连上共享端后**双向**同步 —— 拉远端增量,也把本地改动推回去。
 *
 * ## 双向同步与冲突
 *
 * 两端各自维护一份**基线**([baseline]):上次同步后两端一致的内容 MD5。
 * 每次同步用三方比较判定,而不是"谁新算谁赢":
 *
 * | 本地 vs 基线 | 远端 vs 基线 | 处理 |
 * |---|---|---|
 * | 未变 | 变了 | 应用远端 |
 * | 变了 | 未变 | 推送到远端 |
 * | 未变 | 未变 | 跳过 |
 * | 变了 | 变了且互不相同 | **冲突**,保留本地,登记到 [status].conflicts 等用户裁决 |
 *
 * 没有这层比较时,两端各改各的会互相静默覆盖,用户看到的就是"同步把项目改坏了"。
 *
 * ## 写盘安全
 *
 * 所有落盘都经 [ProjectFileIO](原子写 + 项目级锁),与编辑器保存、MCP 写入互斥,
 * 读者永远看不到半截文件。
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

    /**
     * 上次同步后两端一致的内容指纹:相对路径 -> MD5。
     *
     * 双向同步的核心状态。并发访问来自轮询协程与 UI 读状态,用并发 Map。
     * 客户端与共享端共用同一份(共享端用它做本地变更监视)。
     */
    private val baseline = java.util.concurrent.ConcurrentHashMap<String, String>()


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
                // 共享端也要监视本地改动:编辑器保存不会经过 CollabManager,
                // 若只靠 MCP 主动调 recordLocalChange,用户手改的文件永远不同步出去。
                startHostWatch(r, projectDir)
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
        // 分两步:先在锁内把客户端建起来(不碰主线程),再在锁外做首次同步。
        // 首次同步要刷新编辑器缓冲区,必须切主线程(见 refreshEditor),
        // 若在 synchronized(stateLock) 里等待主线程,一旦主线程此时正在读
        // CollabManager.status 就会死锁。
        val target = synchronized(stateLock) {
            stopInternal()
            startClientLocked(normalizedHost, remotePort, token, localDir)
        } ?: return@withContext false
        try {
            initialSync(target)
            startPolling(onChanged)
            LogCatcher.i(TAG, "已连接共享端并完成首次同步:${target.absolutePath}")
            true
        } catch (e: Exception) {
            LogCatcher.e(TAG, "首次同步失败", e)
            stop()
            status = CollabStatus(error = e.message ?: "首次同步失败")
            false
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

    /**
     * 建立连接并把状态置为「同步中」。
     *
     * **不做首次同步**(见 [startClient] 的分工):本函数在 `synchronized(stateLock)`
     * 内执行,不能等待主线程。成功时返回落地目录,失败返回 null。
     */
    private fun startClientLocked(
        remoteHost: String,
        remotePort: Int,
        token: String,
        localDir: File?
    ): File? {
        return try {
            val c = CollabClient(remoteHost, remotePort, token)
            val info = c.info()
            if (info == null) {
                status = CollabStatus(error = "无法连接共享端 $remoteHost:$remotePort")
                return null
            }
            // 未指定落地目录时,连上后按远端项目名自动下载到本地项目库
            val target = localDir ?: localDirFor(info.name)
            if (!target.exists()) target.mkdirs()
            if (!target.isDirectory) {
                status = CollabStatus(error = "本地目录不可用:${target.absolutePath}")
                return null
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
            target
        } catch (e: Exception) {
            LogCatcher.e(TAG, "连接共享端异常", e)
            // 失败时必须回滚已赋值的 client/repo,否则 isRunning() 会返回 true,
            // 而 status 显示的是错误态,造成状态不一致。
            stopInternal()
            status = CollabStatus(error = e.message ?: "未知错误")
            null
        }
    }

    /**
     * 连接成功后的首次同步。
     *
     * 与旧实现的关键区别:**不再无条件用远端覆盖本地**。首次同步同样走三方比对
     * ([syncOnce]),基线为空时按"谁有内容"决定下载/上传,两边都有且内容不同的
     * 一律登记为冲突等用户裁决 —— 连接一个已有内容的本地目录时,不会再出现
     * "一连上就把我的代码冲掉"。
     */
    private suspend fun initialSync(localDir: File) {
        val outcome = syncOnce(localDir)
        refreshEditors(outcome)
        status = status.copy(syncing = false)
    }

    /**
     * 刷新受影响的编辑器缓冲区。
     *
     * 单独抽出来,是为了让 [syncOnce] 保持**纯同步**(只做磁盘 IO),
     * 不掺入主线程调度 —— 否则在持有 stateLock 时等待主线程会形成死锁风险。
     */
    private suspend fun refreshEditors(outcome: SyncOutcome) {
        for ((absPath, reason) in outcome.refresh) {
            refreshEditor(absPath, source = reason)
        }
    }

    /**
     * 执行一轮双向同步。
     *
     * ## 三方比对
     *
     * 对每个文件取三个指纹:本地磁盘([ProjectFileIO.md5Of])、远端清单、上次同步基线
     * ([baseline])。据此判定谁改过:
     *
     * | 本地变了 | 远端变了 | 处理 |
     * |---|---|---|
     * | 否 | 否 | 跳过 |
     * | 是 | 否 | 推送本地 |
     * | 否 | 是 | 应用远端 |
     * | 是 | 是(内容相同) | 只更新基线 |
     * | 是 | 是(内容不同) | 记冲突,保留本地,不动远端 |
     *
     * 只有"两边都改"才会产生冲突,这正是需要人工裁决的唯一种情况。
     *
     * 本函数**只做磁盘 IO**,不切主线程;需要刷新的编辑器路径收集在返回值里,
     * 由调用方([refreshEditors])在 suspend 上下文完成 —— 这样它可以在
     * `synchronized` 块内安全调用。
     *
     * @return 改动结果(是否变了 + 待刷新的编辑器路径)
     */
    private fun syncOnce(localDir: File): SyncOutcome {
        val c = client ?: return SyncOutcome()
        val base = try { localDir.canonicalFile } catch (_: Exception) { localDir.absoluteFile }
        val remote = c.manifest().associate { it.path to it.md5 }

        // 本地指纹只扫一次:下面既要算全集又要算计划,重复走全项目目录开销不小
        val local = localFileMd5s(base)

        // 全集 = 远端 ∪ 基线 ∪ 本地(本地独有文件也要有机会上传)
        val paths = LinkedHashSet<String>()
        paths.addAll(remote.keys)
        paths.addAll(baseline.keys)
        paths.addAll(local.keys)

        // 先算计划,再执行:因为"覆盖本地"之前要先拍快照,
        // 而快照是否必要取决于这一轮到底会不会写本地文件。
        val plan = ArrayList<Pair<String, Action>>()
        for (path in paths) {
            val target = safeChildOf(base, path)
            if (target == null) {
                LogCatcher.i(TAG, "跳过越界路径:$path")
                continue
            }
            val localMd5 = local[path]
            val remoteMd5 = remote[path]
            val baseMd5 = baseline[path]
            val localChanged = localMd5 != baseMd5
            val remoteChanged = remoteMd5 != baseMd5
            val action = when {
                !localChanged && !remoteChanged -> Action.Skip
                localChanged && !remoteChanged -> Action.Push
                !localChanged && remoteChanged -> Action.Pull
                localMd5 != null && localMd5 == remoteMd5 -> Action.AlignBaseline
                else -> Action.Conflict
            }
            plan.add(path to action)
        }

        // 本轮会覆盖本地 → 覆盖前先自动快照,给用户留一条回档后路。
        if (plan.any { it.second == Action.Pull }) {
            autoSnapshot("同步前自动快照(将被远端覆盖)")
        }

        status = status.copy(syncing = true, syncTotal = plan.size, syncDone = 0)
        var done = 0
        var changedAny = false
        val found = ArrayList<FileConflict>()
        val refresh = ArrayList<Pair<String, String>>()

        for ((path, action) in plan) {
            done++
            val target = safeChildOf(base, path)
            if (target == null) {
                status = status.copy(syncDone = done)
                continue
            }
            val localMd5 = local[path]
            val remoteMd5 = remote[path]
            when (action) {
                Action.Skip -> Unit

                Action.Push -> {
                    // 只有本地变了:推上去
                    val r = pushOne(c, path, target, localMd5)
                    if (r == PushOutcome.CONFLICT) {
                        found.add(FileConflict(path, localMd5, remoteMd5))
                    } else if (r == PushOutcome.OK) {
                        changedAny = true
                    }
                }

                Action.Pull -> {
                    // 只有远端变了:拉下来
                    val reason = applyRemote(c, target, path, remoteMd5)
                    if (reason != null) {
                        refresh.add(target.absolutePath to reason)
                        changedAny = true
                    }
                }

                Action.AlignBaseline -> {
                    // 两侧改动结果一致,不算冲突,同步基线即可
                    if (localMd5 != null) baseline[path] = localMd5
                }

                Action.Conflict -> found.add(FileConflict(path, localMd5, remoteMd5))
            }
            status = status.copy(syncDone = done)
        }

        status = status.copy(conflicts = found)
        if (found.isNotEmpty()) {
            LogCatcher.w(TAG, "同步发现 ${found.size} 处冲突,已保留本地内容并等待裁决")
        }
        return SyncOutcome(changed = changedAny, refresh = refresh)
    }

    /**
     * 一轮同步的结果。
     *
     * @param changed 是否有文件被实际改动(用于触发 UI 刷新)
     * @param refresh 需要在主线程刷新的编辑器:绝对路径 -> 原因(用于日志)
     */
    private data class SyncOutcome(
        val changed: Boolean = false,
        val refresh: List<Pair<String, String>> = emptyList()
    )

    private enum class PushOutcome { OK, CONFLICT, FAILED }

    /** 单文件同步动作(先算计划再执行,便于在执行前统一做快照)。 */
    private enum class Action { Skip, Push, Pull, AlignBaseline, Conflict }

    /**
     * 同步前自动快照。
     *
     * 目的很单纯:双向同步会**覆盖本地工作区**(拉远端内容),一旦用户其实更想要
     * 原来那版,得有东西可回。只有工作区相对 HEAD 有改动时才真提交,避免每轮
     * 同步都堆一条空提交把历史刷满。
     *
     * 失败不阻断同步(快照是保险,不是前置条件),只记日志。
     */
    private fun autoSnapshot(reason: String) {
        val r = repo ?: return
        try {
            val dirty = r.git.changedFiles()
            if (dirty.isEmpty()) return
            val sha = r.snapshot(reason, "LuaForge Studio(自动)")
            LogCatcher.i(TAG, "已自动快照 ${sha.take(8)}(改动 ${dirty.size} 个文件):$reason")
            status = status.copy(head = r.headRevision())
        } catch (e: Exception) {
            LogCatcher.w(TAG, "自动快照失败:${e.message}")
        }
    }

    /** 把单个本地文件推给共享端;成功后更新基线。 */
    private fun pushOne(c: CollabClient, path: String, target: File, localMd5: String?): PushOutcome {
        val baseMd5 = baseline[path]
        return try {
            if (localMd5 == null) {
                // 本地已删除
                val r = c.delete(path, baseMd5)
                when {
                    r.ok -> {
                        baseline.remove(path)
                        PushOutcome.OK
                    }
                    r.conflict -> PushOutcome.CONFLICT
                    else -> {
                        LogCatcher.w(TAG, "推送删除失败 $path: ${r.message}")
                        PushOutcome.FAILED
                    }
                }
            } else {
                val bytes = ProjectFileIO.readBytes(target)
                if (bytes == null) return PushOutcome.FAILED
                val r = c.put(path, bytes, baseMd5)
                when {
                    r.ok -> {
                        baseline[path] = localMd5
                        PushOutcome.OK
                    }
                    r.conflict -> PushOutcome.CONFLICT
                    else -> {
                        LogCatcher.w(TAG, "推送失败 $path: ${r.message}")
                        PushOutcome.FAILED
                    }
                }
            }
        } catch (e: Exception) {
            LogCatcher.e(TAG, "推送异常 $path", e)
            PushOutcome.FAILED
        }
    }

    /**
     * 应用远端内容到本地(原子写);[remoteMd5] 为 null 表示远端已删除。
     *
     * @return 需要刷新编辑器时返回原因描述,无变化返回 null
     */
    private fun applyRemote(
        c: CollabClient,
        target: File,
        path: String,
        remoteMd5: String?
    ): String? {
        if (remoteMd5 == null) {
            if (target.exists() && target.delete()) {
                baseline.remove(path)
                return "远端删除"
            }
            return null
        }
        val file = c.file(path) ?: return null
        target.parentFile?.mkdirs()
        ProjectFileIO.writeBytesAtomic(target, file.bytes, ProjectFileIO.lockForProjectFile(target))
        baseline[path] = file.md5
        return "远端更新"
    }

    /** 扫描本地项目,返回 相对路径 -> MD5(排除 `.git` / `.luaforge` / `build` 等)。 */
    private fun localFileMd5s(base: File): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val git = repo?.git
        fun walk(dir: File, prefix: String) {
            val children = dir.listFiles()?.sortedBy { it.name } ?: return
            for (child in children) {
                if (git?.isIgnoredName(child.name) == true) continue
                val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDirectory) walk(child, rel)
                else if (child.isFile) ProjectFileIO.md5Of(child)?.let { out[rel] = it }
            }
        }
        walk(base, "")
        return out
    }

    /**
     * 把本地改动推给共享端(供 UI 手动"立即上传"以及轮询里定期调用)。
     *
     * 与 [syncOnce] 的区别:这里**不拉取**远端,只做上行。轮询里先拉后推,
     * 所以本地改动最终仍会到达对端。
     */
    suspend fun pushLocalChanges(): Int = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext 0
        val dir = status.projectPath?.let { File(it) } ?: return@withContext 0
        val base = try { dir.canonicalFile } catch (_: Exception) { dir.absoluteFile }
        var pushed = 0
        val found = ArrayList<FileConflict>()
        for ((path, md5) in localFileMd5s(base)) {
            val baseMd5 = baseline[path]
            if (md5 == baseMd5) continue
            val target = safeChildOf(base, path) ?: continue
            when (pushOne(c, path, target, md5)) {
                PushOutcome.OK -> pushed++
                PushOutcome.CONFLICT -> found.add(FileConflict(path, md5, null))
                PushOutcome.FAILED -> Unit
            }
        }
        if (found.isNotEmpty()) {
            status = status.copy(
                conflicts = (status.conflicts + found).distinctBy { it.path }
            )
        }
        pushed
    }

    /**
     * 裁决一处冲突。
     *
     * @param keepLocal true = 用本地覆盖远端;false = 用远端覆盖本地。
     * @return 是否处理成功
     */
    suspend fun resolveConflict(path: String, keepLocal: Boolean): Boolean = withContext(Dispatchers.IO) {
        val dir = status.projectPath?.let { File(it) } ?: return@withContext false
        val base = try { dir.canonicalFile } catch (_: Exception) { dir.absoluteFile }
        val target = safeChildOf(base, path) ?: return@withContext false
        val ok = if (keepLocal) {
            val c = client
            if (c == null) {
                // 共享端:本地就是唯一真相,直接把当前内容记为基线即可
                ProjectFileIO.md5Of(target)?.let { baseline[path] = it } ?: baseline.remove(path)
                true
            } else {
                val bytes = ProjectFileIO.readBytes(target)
                if (bytes == null) {
                    c.delete(path, null, force = true).ok
                } else {
                    val r = c.put(path, bytes, null, force = true)
                    if (r.ok) ProjectFileIO.md5Of(target)?.let { baseline[path] = it }
                    r.ok
                }
            }
        } else {
            val c = client
            if (c == null) {
                // 共享端没有"远端"可拉:本地即真相,直接清掉这条冲突即可
                ProjectFileIO.md5Of(target)?.let { baseline[path] = it } ?: baseline.remove(path)
                true
            } else {
                val remote = c.manifest().firstOrNull { it.path == path }
                // 以 applyRemote 的实际结果为准:远端读失败时不能假装处理成功
                applyRemote(c, target, path, remote?.md5) != null
            }
        }
        if (ok) status = status.copy(conflicts = status.conflicts.filterNot { it.path == path })
        ok
    }

    /** 当前待裁决的冲突列表。 */
    fun currentConflicts(): List<FileConflict> = status.conflicts

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

    /**
     * 若指定文件在编辑器中打开,则用磁盘最新内容刷新其缓冲区。
     *
     * [source] 只用于日志(便于排查"谁改的");真正的"不覆盖未保存编辑"逻辑
     * 在 [com.luaforge.studio.ui.editor.viewmodel.EditorViewModel.refreshEditorFromDisk] 内,
     * 那里会检查 `isModified` 并登记冲突而不是静默覆盖。
     */
    private suspend fun refreshEditor(absolutePath: String, source: String = "协同同步") {
        val vm = EditorBridge.currentViewModel() ?: return
        try {
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                runCatching { vm.refreshEditorFromDisk(absolutePath, fromExternal = true, source = source) }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 启动同步轮询(**双向**)。
     *
     * 每一轮做两件事:
     * 1. 拉远端增量(`changesSince`),但**不直接覆盖本地** —— 改为重跑一次
     *    [syncOnce] 的三方比对,避免"远端有人动过"就把本地未同步的改动静默冲掉;
     * 2. 把本地改动推给共享端(`syncOnce` 已包含上行)。
     *
     * 只有真的拉到了远端变更才重算,避免每 1.5s 全库算一遍 MD5。
     */
    private fun startPolling(onChanged: (() -> Unit)?) {
        syncJob?.cancel()
        syncJob = scope.launch {
            var since = status.rev
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val c = client ?: break
                val localDir = status.projectPath?.let { File(it) } ?: break
                val changes = c.changesSince(since)
                var changedAny = false
                if (changes != null && changes.isNotEmpty()) {
                    for (change in changes) since = maxOf(since, change.rev)
                    // 远端有变更:走完整三方比对(含上行),冲突会被登记而不是覆盖
                    val outcome = syncOnce(localDir)
                    refreshEditors(outcome)
                    changedAny = outcome.changed
                } else {
                    // 远端没变:只做上行,把本地新改的文件推过去
                    val pushed = runCatching { pushLocalChanges() }.getOrDefault(0)
                    changedAny = pushed > 0
                }
                val info = c.info()
                status = status.copy(rev = since, head = info?.head ?: status.head, syncing = false)
                if (changedAny) {
                    try { onChanged?.invoke() } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * 共享端的本地变更监视。
     *
     * 编辑器保存、布局助手回写、回档等路径都不经过协同代码,无法逐个埋点;
     * 这里用"定期比对内容指纹"的方式兜底:把当前工作区指纹与上一轮对比,
     * 新增/变化/消失的文件登记为变更,连接端即可增量拉取。
     *
     * 相比给每条写入路径加钩子,这种做法的好处是**不会漏**;代价是磁盘 IO,
     * 因此间隔比客户端轮询长(默认 2s),且只算 MD5 不读全文。
     */
    private fun startHostWatch(r: CollabRepo, projectDir: File) {
        syncJob?.cancel()
        val base = try { projectDir.canonicalFile } catch (_: Exception) { projectDir.absoluteFile }
        // 启动时先拍一张,作为"已知状态";此时不应把已有文件当成新变更广播
        val known = java.util.concurrent.ConcurrentHashMap<String, String>()
        known.putAll(snapshotProject(base))
        baseline.clear()
        baseline.putAll(known)

        syncJob = scope.launch {
            while (isActive) {
                delay(HOST_WATCH_INTERVAL_MS)
                val current = runCatching { snapshotProject(base) }.getOrDefault(emptyMap())
                var changed = 0
                for ((path, md5) in current) {
                    if (known[path] == md5) continue
                    known[path] = md5
                    r.recordChange(path, deleted = false)
                    changed++
                }
                for (path in known.keys.toList()) {
                    if (current.containsKey(path)) continue
                    known.remove(path)
                    r.recordChange(path, deleted = true)
                    changed++
                }
                if (changed > 0) {
                    status = status.copy(rev = r.currentRev())
                    LogCatcher.i(TAG, "共享端检测到 $changed 处本地变更,已广播")
                }
            }
        }
    }

    /** 抓取项目当前的内容指纹(排除 `.git` / `.luaforge` / `build` 等)。 */
    private fun snapshotProject(base: File): Map<String, String> = localFileMd5s(base)

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
        baseline.clear()
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

    /** 共享端本地变更监视间隔:比客户端轮询长,因为要扫全项目算指纹。 */
    private const val HOST_WATCH_INTERVAL_MS = 2000L

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
    val syncDone: Int = 0,
    /** 待用户裁决的冲突(两端都改过的同一文件) */
    val conflicts: List<FileConflict> = emptyList()
)

/**
 * 一处同步冲突。
 *
 * 产生条件:同一文件**两端都相对上次同步有改动,且改后内容不同**。这种情况下无论
 * 保留哪边都会丢掉另一边的劳动成果,所以必须由用户裁决,不能自动合并。
 *
 * @param path      项目内相对路径
 * @param localMd5  本地当前内容指纹(本地已删除时为 null)
 * @param remoteMd5 远端当前内容指纹(远端已删除时为 null)
 */
data class FileConflict(
    val path: String,
    val localMd5: String?,
    val remoteMd5: String?
)
