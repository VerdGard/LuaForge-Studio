package com.luaforge.studio.console.output

/**
 * 单文件输出缓冲,容量封顶防内存无限增长。
 *
 * 用 ArrayDeque 而非 ArrayList:封顶后每来一条新输出都要从头摘一条,
 * ArrayList.removeAt(0) 是 O(n) 整段前移,ArrayDeque.removeFirst 为 O(1)。
 *
 * 方法自带同步:输出可能来自多条 Lua 线程,而调用方(OutputManager.append)只锁了
 * 缓冲池的取用、并未覆盖写入本身,故此处必须自行串行化。
 */
class OutputBuffer(val fileKey: String, private val maxEntries: Int = MAX_ENTRIES) {

    private val entries = ArrayDeque<OutputEntry>()

    @Synchronized
    fun append(entry: OutputEntry) {
        entries.addLast(entry)
        while (entries.size > maxEntries) entries.removeFirst()
    }

    /** 返回副本:调用方可能在迭代期间持续有新输出写入。 */
    @Synchronized
    fun all(): List<OutputEntry> = ArrayList(entries)

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size(): Int = entries.size

    companion object {
        const val MAX_ENTRIES = 2000
    }
}
