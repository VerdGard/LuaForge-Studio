package com.luaforge.studio.utils

import android.content.Context
import androidx.annotation.Keep
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Python 运行时的解包器:把随包分发的 assets/python 下的 zip 铺到 app 私有目录。
 *
 * ## 分工(与构建期 `preparePythonRuntime` 对应)
 * - 原生运行库(`libpython3.14.so` 等)不在这里:它们由 jniLibs 直接进
 *   `nativeLibraryDir`,交给系统加载器按 soname 解析。
 * - 这里只负责两类**必须落在文件系统上**的内容:
 *   - 标准库 `.py` -> `<home>/`(解释器按 `PYTHONHOME` 找)
 *   - 扩展模块 `.so` -> `<home>/lib-dynload/`(它们没有 SONAME,必须按绝对路径 dlopen)
 *
 * ## 幂等与原子性
 * 以 `BUILD.json` 的**全文**作为版本指纹写进 `<home>/.installed`;指纹一致即跳过。
 * 解包先落到同级 `<home>.new`,成功后再改名替换 —— 中途崩溃只会留下 `.new`,
 * 不会污染正在用的运行时。替换时旧的先挪到 `<home>.old`,失败可回滚。
 *
 * 不用 `File.deleteRecursively()` 直接铺进 `<home>`:那样崩溃会留下"残缺但看起来存在"
 * 的运行时,解释器启动后 import 随机失败,排查成本远高于一次重解包。
 *
 * ## 可测试性
 * 解包/替换的核心([ensureWith])只依赖一个"按名取流"的函数,不依赖 Android 的
 * AssetManager —— 这样同一份逻辑能在 JVM 上直接跑(见测试),而不是只能上真机试。
 */
@Keep
internal object PythonRuntimeInstaller {

    private const val TAG = "PythonRuntime"

    /** 与构建期 `preparePythonRuntime` 约定的 assets 子目录。 */
    private const val ASSET_DIR = "python"
    private const val MANIFEST_NAME = "BUILD.json"
    private const val STDLIB_ZIP = "stdlib.zip"
    private const val DYNLOAD_ZIP = "lib-dynload.zip"

    /** 指纹文件名(落在 home 内)。 */
    private const val STAMP_NAME = ".installed"

    /** 解包与替换必须串行:两个线程同时改名会互相踩。 */
    private val lock = Any()

    /**
     * 读取包内清单。
     *
     * @return 清单全文;`null` 表示本 APK 未分发 Python 运行时(例如 32 位包
     *   或构建时 `-PpythonRuntime=off`)。调用方据此区分"没有"与"解包失败"。
     */
    fun bundledManifest(context: Context): String? =
        readManifest { context.assets.open("$ASSET_DIR/$MANIFEST_NAME") }

    /**
     * 读清单的纯函数版本(不依赖 Android)。
     *
     * @return 清单全文;打不开时 `null`(区分"本包没有运行时"与"解包失败")。
     */
    internal fun readManifest(open: () -> InputStream): String? = try {
        open().use { String(it.readBytes(), Charsets.UTF_8) }
    } catch (e: Exception) {
        null
    }

    /** 已解包内容是否与包内清单一致(不看内容本身,只看指纹)。 */
    fun isInstalled(home: File, manifest: String): Boolean {
        val stamp = File(home, STAMP_NAME)
        if (!stamp.isFile) return false
        return try {
            stamp.readText() == manifest
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 确保 [home] 就绪。
     *
     * @return true 表示已就绪(含"本来就在");false 表示解包失败或包内没有运行时,
     *   调用方应据此报错 —— 不要静默继续。
     */
    fun ensure(context: Context, home: File, manifest: String): Boolean =
        ensureWith(home, manifest) { context.assets.open("$ASSET_DIR/$it") }

    /**
     * 解包并原子替换的核心实现。
     *
     * @param open 按 assets 内相对名取输入流(生产环境传 AssetManager,测试可传文件源)
     */
    internal fun ensureWith(
        home: File,
        manifest: String,
        open: (String) -> InputStream
    ): Boolean = synchronized(lock) {
        if (isInstalled(home, manifest)) return true

        val parent = home.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false

        val staging = File(parent, home.name + ".new")
        val backup = File(parent, home.name + ".old")

        try {
            staging.deleteRecursively()
            if (!staging.mkdirs()) return false

            extractZip(open, STDLIB_ZIP, staging)
            extractZip(open, DYNLOAD_ZIP, File(staging, "lib-dynload"))
            File(staging, STAMP_NAME).writeText(manifest)
        } catch (e: Exception) {
            RuntimeLog.error(TAG, "Python 运行时解包失败: ${e.message}", e)
            staging.deleteRecursively()
            return false
        }

        // 原子替换:先备份旧的,失败可回滚
        backup.deleteRecursively()
        if (home.exists() && !home.renameTo(backup)) {
            RuntimeLog.error(TAG, "无法备份旧运行时: ${home.absolutePath}")
            staging.deleteRecursively()
            return false
        }
        if (!staging.renameTo(home)) {
            RuntimeLog.error(TAG, "无法就位新运行时: ${staging.absolutePath} -> ${home.absolutePath}")
            if (backup.exists()) backup.renameTo(home)
            staging.deleteRecursively()
            return false
        }
        backup.deleteRecursively()

        RuntimeLog.log(TAG, "Python 运行时已解包到 ${home.absolutePath}")
        return true
    }

    /** 把 [open] 提供的一个 zip 解到 [destDir],保留 zip 内的相对路径。 */
    private fun extractZip(open: (String) -> InputStream, name: String, destDir: File) {
        open(name).use { raw ->
            ZipInputStream(raw.buffered()).use { zis ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val out = File(destDir, entry.name)
                    // 防御 zip-slip:我们的包是自己构建的,但别留这个口子
                    if (!isInside(destDir, out)) throw IOException("非法 zip 条目: ${entry.name}")
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos ->
                            while (true) {
                                val n = zis.read(buf)
                                if (n < 0) break
                                fos.write(buf, 0, n)
                            }
                        }
                    }
                    zis.closeEntry()
                }
            }
        }
    }

    private fun isInside(dir: File, file: File): Boolean {
        val base = dir.canonicalPath
        val target = file.canonicalPath
        return target == base || target.startsWith(base + File.separator)
    }
}
