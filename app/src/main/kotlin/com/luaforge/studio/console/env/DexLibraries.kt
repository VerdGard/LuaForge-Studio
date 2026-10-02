package com.luaforge.studio.console.env

import dalvik.system.DexFile
import dalvik.system.PathClassLoader
import java.io.File

/**
 * Java 类库:仅扫描项目根目录 libs/ 下的 .dex 文件(dex 内类名 + 反射方法签名)。
 *
 * 结果按目录缓存:一次扫描要走 PathClassLoader + 逐类反射全部方法,开销在百毫秒级,
 * 而「环境」页每次切回都会重扫。缓存以每个 dex 的大小与修改时间为指纹,文件一变即失效。
 */
object DexLibraries {

    data class DexLib(val dexFile: String, val classes: List<ModuleTracker.JavaLib>)

    private class CachedScan(val fingerprint: String, val libs: List<DexLib>)

    private val cache = HashMap<String, CachedScan>()

    /** 扫项目 luaDir/libs 目录下的 dex 文件,返回每个 dex 的类列表(类名 → 方法签名)。 */
    fun scan(luaDir: String?): List<DexLib> {
        if (luaDir.isNullOrBlank()) return emptyList()
        val dir = File(luaDir, "libs")
        if (!dir.isDirectory) return emptyList()
        val dexFiles = dir.listFiles { f -> f.isFile && f.extension.equals("dex", true) }
            ?.sortedBy { it.name } ?: return emptyList()

        val fingerprint = dexFiles.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
        synchronized(cache) {
            cache[dir.absolutePath]?.let { if (it.fingerprint == fingerprint) return it.libs }
        }

        val libs = dexFiles.mapNotNull { scanDex(it) }
        synchronized(cache) { cache[dir.absolutePath] = CachedScan(fingerprint, libs) }
        return libs
    }

    private fun scanDex(f: File): DexLib? {
        return try {
            val loader = PathClassLoader(f.absolutePath, DexLibraries::class.java.classLoader)
            val dex = DexFile(f)
            val classes = dex.entries()
                .toList()
                .filter { !it.startsWith("META-INF") }
                .sorted()
                .mapNotNull { name ->
                    try {
                        val c = dex.loadClass(name, loader) ?: return@mapNotNull null
                        val methods = c.declaredMethods
                            .filter { it.declaringClass == c }
                            .sortedBy { it.name }
                            .take(200)
                            .map { "${it.name}(${it.parameterTypes.joinToString(", ") { p -> p.simpleName }}) : ${it.returnType.simpleName}" }
                        ModuleTracker.JavaLib(c.name, methods)
                    } catch (e: Exception) {
                        null
                    }
                }
            dex.close()
            if (classes.isEmpty()) null else DexLib(f.name, classes)
        } catch (e: Exception) {
            null
        }
    }
}
