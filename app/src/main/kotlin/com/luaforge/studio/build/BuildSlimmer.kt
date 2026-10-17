package com.luaforge.studio.build

import com.luaforge.studio.utils.LogCatcher
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import com.android.tools.r8.D8
import com.android.tools.r8.R8

/**
 * 构建精简:让最终 APK 只包含「项目真正用到」的类。
 *
 * ## 为什么需要它
 *
 * 用户 APK 的骨架来自 `assets/core.apk`(预编译模板),项目能引入新类的途径只有两条:
 * `libs/` 下的 jar/dex,以及 `maven` 依赖。此前这两条路都是**整包塞入**:
 * 一个只为用 3 个类而引入的 jar,会把其余全部类一起打进 APK。本模块负责按需裁剪。
 *
 * ## 三道处理
 *
 * 1. **声明类裁剪** —— 扫描项目 `.lua/.aly` 源码,抽出所有被声明/引用的 Java 类名,
 *    连同[必须类白名单][MUST_KEEP_PACKAGES]一起生成 R8 keep 规则,再把 jar 精简成 dex。
 *    未被声明且不可达的类会被丢弃。
 * 2. **重复类踢出** —— 同一个类若已存在于 `core.apk` 模板中,就从项目 jar 里剔除,
 *    避免同一类在多个 dex 里重复出现(徒增体积,且首次解析可能命中旧版本)。
 * 3. **必须类保留** —— 运行时靠字符串/反射/JNI 定位的类无法被静态分析发现,
 *    由白名单强制保住,避免「精简后启动即崩」。
 *
 * ## 安全边界(重要)
 *
 * - **绝不开启混淆**(`-dontobfuscate`)。AndroLua 里 `luajava.bindClass("com.foo.Bar")`
 *   传的是字符串,一旦类名被混淆就再也找不到,且是运行时才暴露。
 * - **绝不裁剪 core.apk 模板本身**。模板里包含 Lua 运行时、布局引擎等以字符串方式
 *   调用的海量类,静态分析无法证明安全;这里只对它做只读扫描用于去重。
 * - R8 精简失败时**自动回退**到原先的 D8 直转,保证构建不因精简而失败。
 *
 * 由设置项「构建精简」(`SettingsData.buildSlimEnabled`)控制,默认关闭。
 */
object BuildSlimmer {

    private const val TAG = "BuildSlimmer"

    /**
     * 必须保留的包前缀。
     *
     * 判据:这些类是被「字符串名 / 反射 / JNI / Lua 侧调用」触达的,
     * R8 的可达性分析看不到,必须无条件 keep。增删前请确认没有运行时隐式引用。
     */
    private val MUST_KEEP_PACKAGES = listOf(
        // Lua 运行时本体与 Java-Lua 桥:全程通过 JNI 与反射驱动
        "com.androlua.**",
        "com.luajava.**",
        "com.android.cglib.**",
        // 用户 APK 的宿主组件与内置能力(LuaActivity/布局助手/防火墙/调试器入口)
        "com.luaforge.studio.core.**",
        "com.luaforge.studio.utils.**",
        "com.luaforge.studio.widget.**",
        // Media3 通过 Class.forName 发现 HLS/DASH/SS/RTSP/RTMP 实现
        "androidx.media3.exoplayer.hls.**",
        "androidx.media3.exoplayer.dash.**",
        "androidx.media3.exoplayer.smoothstreaming.**",
        "androidx.media3.exoplayer.rtsp.**",
        "androidx.media3.datasource.rtmp.**",
        "io.antmedia.**",
        // 反射创建 View 时按类名查找,布局 XML/table 里的控件名同样是字符串
        "androidx.appcompat.widget.**",
        "com.google.android.material.**",
        "androidx.recyclerview.widget.**",
        "androidx.viewpager2.widget.**",
        "androidx.constraintlayout.widget.**",
        "androidx.coordinatorlayout.widget.**",
        "androidx.swiperefreshlayout.widget.**",
        "androidx.drawerlayout.widget.**"
    )

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /** 精简结果,用于写日志与让调用方决定后续行为。 */
    data class Result(
        /** 是否真的执行了 R8 精简(而非回退 D8)。 */
        val shrunk: Boolean,
        /** 从项目 jar 中抽出/跳过的重复类描述符(形如 `Lcom/foo/Bar;`)。 */
        val duplicateClasses: List<String> = emptyList(),
        /** 项目源码中声明的类名(点号形式)。 */
        val declaredClasses: Set<String> = emptySet()
    )

    /**
     * 把项目引入的 jar 转成 dex,并按需精简。
     *
     * @param jars        待转换的 jar(项目 `libs/*.jar` + Maven 下载的 jar)
     * @param androidJar  平台 `android.jar`,作为 R8/D8 的 library 输入
     * @param templateClasses 模板(`core.apk` 解压后 `classes*.dex`)已有的类描述符;
     *                     用于识别重复类,传空集合则跳过去重
     * @param projectPath 项目根目录;用于扫描声明类
     * @param outDir      dex 输出目录
     * @param enabled     构建精简开关;关闭时与旧行为一致(直接 D8)
     */
    fun jarsToDex(
        jars: List<File>,
        androidJar: String,
        templateClasses: Set<String>,
        projectPath: String,
        outDir: File,
        enabled: Boolean
    ): Result {
        if (jars.isEmpty()) return Result(shrunk = false)
        outDir.mkdirs()

        // 去重产生的临时 jar 放在 outDir 的子目录里:源 jar 的父目录可能是模板解压出来的
        // APK 工作目录,把 *_slim.jar 写在那儿会被当成资源打进 APK 根。
        // outDir 由调用方用完整体删除,临时 jar 随之清掉。
        val pruneDir = File(outDir, "slim_prune")

        val duplicates = ArrayList<String>()
        // 只在开关开启时扫描;关闭时连扫源码这一步都不做,保持与旧版行为一致。
        val declared = if (enabled) scanDeclaredClasses(projectPath) else emptySet()
        var keepFile: File? = null

        try {
            // 1. 去重:core.apk 里已有的类,从项目 jar 中剔除
            val prunedJars = if (templateClasses.isNotEmpty()) {
                pruneDuplicatesAgainstTemplate(jars, templateClasses, duplicates, pruneDir)
            } else {
                jars
            }
            if (duplicates.isNotEmpty()) {
                LogCatcher.i(TAG, "已从项目 jar 中剔除 ${duplicates.size} 个模板已有类")
            }

            // 所有 jar 的类都已被模板覆盖时没有可转的输入。
            // R8/D8 收到空输入会抛异常,而回退分支不在 try 内,会直接炸掉整个构建 —— 必须提前返回。
            if (prunedJars.isEmpty()) {
                LogCatcher.i(TAG, "全部 jar 的类均已在模板中,跳过 dex 生成")
                return Result(shrunk = duplicates.isNotEmpty(), duplicateClasses = duplicates)
            }

            // 2. 开关关闭:沿用原本的 D8 直转
            if (!enabled) {
                runD8(prunedJars, androidJar, outDir)
                return Result(shrunk = false, duplicateClasses = duplicates)
            }

            // 3. 一个声明类都没扫到时不做精简。
            //    这多半意味着项目用变量/动态方式加载类(`luajava.new(cls)`),静态扫描拿不到种子;
            //    此时照常走 R8 会把项目自己的类全部判为不可达而裁空,打出来的 APK 一运行就崩。
            //    宁可退回 D8 全量保留,也不产出一个必然崩溃的包。
            if (declared.isEmpty()) {
                LogCatcher.w(TAG, "未扫描到任何声明类,跳过精简以免裁空项目自身类(回退 D8)")
                runD8(prunedJars, androidJar, outDir)
                return Result(shrunk = false, duplicateClasses = duplicates)
            }
            LogCatcher.i(TAG, "项目源码中声明的 Java 类: ${declared.size} 个")

            // keep 规则同样放在 outDir 内,随调用方的整目录删除一并清掉。
            val pro = File(outDir, "slim_keep.pro")
            keepFile = pro
            pro.writeText(buildKeepRules(declared), Charsets.UTF_8)

            val ok = try {
                runR8(prunedJars, androidJar, outDir, pro)
                true
            } catch (t: Throwable) {
                // 精简属于增强能力,失败绝不能连累构建 —— 回退到 D8 并如实记录原因
                LogCatcher.w(TAG, "R8 精简失败,回退 D8 直转: ${t.message}")
                // 清掉 R8 可能已写出的半成品 dex,但要保住输入(keep 规则 + 临时 jar)
                outDir.listFiles()?.forEach {
                    if (it != pro && it != pruneDir) it.deleteRecursively()
                }
                false
            }

            if (!ok) {
                runD8(prunedJars, androidJar, outDir)
                return Result(shrunk = false, duplicateClasses = duplicates, declaredClasses = declared)
            }

            return Result(shrunk = true, duplicateClasses = duplicates, declaredClasses = declared)
        } finally {
            keepFile?.delete()
            pruneDir.deleteRecursively()
        }
    }

    /**
     * 精简项目 `libs/` 目录。
     *
     * 这里的 `.jar`/`.dex` 是**运行时**由 `LuaDexLoader.loadLibs()` 直接以文件路径加载的
     * (`DexClassLoader`),因此产物必须保持原格式与原名,不能像 Maven 依赖那样转成
     * APK 根的 `classes*.dex`。据此分两类处理:
     *
     * - `.jar`:可以安全地重写(剔除与模板重复的 `.class`),这是本目录唯一能真正"踢出"的形态。
     * - `.dex`:dex 是紧凑二进制,删除其中个别类需要完整的 dex 重写器;本模块只做**只读检测**,
     *   仅当整个 dex 的所有类都已被模板覆盖时才整体丢弃,否则保留并如实记录数量。
     *   这样绝不会因为裁剪而破坏一个语义上仍被需要的 dex。
     */
    fun slimProjectLibs(
        libsDir: File,
        templateClasses: Set<String>
    ): Result {
        if (!libsDir.isDirectory || templateClasses.isEmpty()) return Result(shrunk = false)

        val duplicates = ArrayList<String>()
        var prunedJars = 0
        var droppedDex = 0

        val files = libsDir.listFiles()?.sortedBy { it.name } ?: return Result(shrunk = false)

        for (f in files) {
            when {
                f.isFile && f.name.endsWith(".jar", ignoreCase = true) -> {
                    val classes = try {
                        collectJarClassDescriptors(f)
                    } catch (e: Exception) {
                        LogCatcher.w(TAG, "读取 libs jar 失败,原样保留: ${f.name} (${e.message})")
                        continue
                    }
                    val dup = classes.filter { templateClasses.contains(it) }
                    if (dup.isEmpty()) continue
                    if (dup.size == classes.size) {
                        // 全部类都在模板里,整个 jar 没有存在价值
                        if (f.delete()) {
                            duplicates.addAll(dup)
                            prunedJars++
                            LogCatcher.i(TAG, "libs/${f.name} 全部类已在模板中,已移除")
                        }
                        continue
                    }
                    val tmp = File(libsDir, "${f.nameWithoutExtension}.slim.tmp")
                    try {
                        rewriteJarWithout(f, tmp, dup.toSet())
                        if (f.delete() && tmp.renameTo(f)) {
                            duplicates.addAll(dup)
                            LogCatcher.i(TAG, "libs/${f.name}: 剔除 ${dup.size}/${classes.size} 个重复类")
                        } else {
                            tmp.delete()
                            LogCatcher.w(TAG, "libs/${f.name} 替换失败,保留原文件")
                        }
                    } catch (e: Exception) {
                        tmp.delete()
                        LogCatcher.w(TAG, "重写 libs jar 失败,原样保留: ${f.name} (${e.message})")
                    }
                }

                f.isFile && f.name.endsWith(".dex", ignoreCase = true) -> {
                    val classes = try {
                        parseDexClassDescriptors(f.readBytes())
                    } catch (e: Exception) {
                        LogCatcher.w(TAG, "读取 libs dex 失败,原样保留: ${f.name} (${e.message})")
                        continue
                    }
                    if (classes.isEmpty()) continue
                    val dupCount = classes.count { templateClasses.contains(it) }
                    if (dupCount == classes.size) {
                        if (f.delete()) {
                            duplicates.addAll(classes)
                            droppedDex++
                            LogCatcher.i(TAG, "libs/${f.name} 全部类已在模板中,已移除")
                        }
                    } else if (dupCount > 0) {
                        // 只报告不处理:安全优先
                        LogCatcher.w(
                            TAG,
                            "libs/${f.name}: 有 $dupCount/${classes.size} 个类与模板重复," +
                                "但 dex 无法安全剔除局部类,保留原文件"
                        )
                    }
                }
            }
        }

        return Result(
            shrunk = prunedJars > 0 || droppedDex > 0,
            duplicateClasses = duplicates
        )
    }

    // ------------------------------------------------------------------
    // R8 / D8 调用
    // ------------------------------------------------------------------

    /**
     * 用 R8 把 jar 精简成 dex。
     *
     * 只做 shrink(`--release`),显式关闭混淆与优化:
     * - `-dontobfuscate` 是硬性要求,Lua 侧靠字符串类名反射;
     * - `-dontoptimize` 保守起见,避免优化引入本机未验证的行为差异。
     */
    private fun runR8(
        jars: List<File>,
        androidJar: String,
        outDir: File,
        keepFile: File
    ) {
        val args = mutableListOf<String>()
        args.add("--release")
        args.add("--lib")
        args.add(androidJar)
        args.add("--output")
        args.add(outDir.absolutePath)
        args.add("--pg-conf")
        args.add(keepFile.absolutePath)
        args.add("--min-api")
        args.add("21")
        jars.forEach { args.add(it.absolutePath) }

        LogCatcher.i(TAG, "执行 R8 精简,输入 ${jars.size} 个 jar")
        R8.main(args.toTypedArray())
    }

    /** 原有行为:不做任何裁剪,直接把 jar 转成 dex。 */
    private fun runD8(jars: List<File>, androidJar: String, outDir: File) {
        val args = mutableListOf<String>()
        args.add("--lib")
        args.add(androidJar)
        args.add("--output")
        args.add(outDir.absolutePath)
        jars.forEach { args.add(it.absolutePath) }
        args.add("--min-api")
        args.add("21")

        LogCatcher.i(TAG, "执行 D8 转换,输入 ${jars.size} 个 jar")
        D8.main(args.toTypedArray())
    }

    // ------------------------------------------------------------------
    // keep 规则
    // ------------------------------------------------------------------

    /**
     * 生成 R8 keep 规则。
     *
     * 声明类用**精确类名** keep,并保留其全部成员(`{ *; }`),
     * 这样 R8 才能沿方法体继续做可达性分析,把「相关引用」一并留下。
     */
    private fun buildKeepRules(declared: Set<String>): String = buildString {
        appendLine("# 由 BuildSlimmer 自动生成 —— 请勿手工维护")
        appendLine("-dontobfuscate")
        appendLine("-dontoptimize")
        // 注意:不要写 -dontcompress —— R8 8.2.42 不认识该规则,会导致整份配置解析失败
        appendLine("-dontwarn **")
        appendLine()
        appendLine("# 必须类白名单:运行时经字符串/反射/JNI 触达,静态分析不可见")
        MUST_KEEP_PACKAGES.forEach { appendLine("-keep class $it { *; }") }
        appendLine()
        appendLine("# 项目源码声明的类")
        declared.sorted().forEach { appendLine("-keep class $it { *; }") }
    }

    // ------------------------------------------------------------------
    // 项目声明类扫描
    // ------------------------------------------------------------------

    /**
     * 扫描项目源码,抽取被声明/引用的 Java 类名。
     *
     * 覆盖 Lua 侧实际会用到的几种写法:
     * - `import "android.widget.Button"`
     * - `luajava.bindClass("android.widget.Button")`
     * - `luajava.newInstance("android.widget.Button")`
     * - `luajava.new(SomeClass)` 由变量表达,无法静态解析,交由白名单兜底
     */
    fun scanDeclaredClasses(projectPath: String): Set<String> {
        val root = File(projectPath)
        if (!root.isDirectory) return emptySet()

        val out = HashSet<String>()
        root.walkTopDown()
            .filter { it.isFile }
            .filter {
                val n = it.name.lowercase()
                n.endsWith(".lua") || n.endsWith(".aly")
            }
            .forEach { f ->
                val text = try {
                    f.readText()
                } catch (_: Exception) {
                    return@forEach
                }
                CLASS_PATTERNS.forEach { regex ->
                    regex.findAll(text).forEach { m ->
                        val name = m.groupValues[1]
                        // 过滤掉框架自身的伪类名与明显不是类的短串
                        if (name.contains('.') && !name.startsWith(".") && !name.endsWith(".")) {
                            out.add(name)
                        }
                    }
                }
            }
        return out
    }

    /** 与 [scanDeclaredClasses] 配套的类名提取正则。 */
    private val CLASS_PATTERNS = listOf(
        Regex("""import\s+["']([a-zA-Z_][\w.$]*)["']"""),
        Regex("""luajava\.bindClass\s*\(\s*["']([a-zA-Z_][\w.$]*)["']"""),
        Regex("""luajava\.newInstance\s*\(\s*["']([a-zA-Z_][\w.$]*)["']"""),
        Regex("""luajava\.new\s*\(\s*["']([a-zA-Z_][\w.$]*)["']"""),
        Regex("""bindClass\s*\(\s*["']([a-zA-Z_][\w.$]*)["']""")
    )

    // ------------------------------------------------------------------
    // 重复类处理
    // ------------------------------------------------------------------

    /**
     * 把 jar 中「core.apk 模板已经提供」的类剔除,返回处理后的 jar 列表。
     *
     * 只重写真正含重复类的 jar,不重复写盘;剔除后为空的 jar 直接跳过。
     */
    private fun pruneDuplicatesAgainstTemplate(
        jars: List<File>,
        templateClasses: Set<String>,
        duplicates: MutableList<String>,
        workDir: File
    ): List<File> {
        workDir.mkdirs()
        val result = ArrayList<File>()
        for (jar in jars) {
            val jarClasses = try {
                collectJarClassDescriptors(jar)
            } catch (e: Exception) {
                LogCatcher.w(TAG, "读取 jar 失败,原样保留: ${jar.name} (${e.message})")
                result.add(jar)
                continue
            }

            val dup = jarClasses.filter { templateClasses.contains(it) }
            if (dup.isEmpty()) {
                result.add(jar)
                continue
            }
            if (dup.size == jarClasses.size) {
                // 整个 jar 都已被模板覆盖,没必要再转 dex
                duplicates.addAll(dup)
                LogCatcher.i(TAG, "jar 全部类均已在模板中,跳过: ${jar.name}")
                continue
            }

            // 不写在源 jar 旁边:源 jar 可能就躺在模板解压出来的 APK 工作目录里
            val pruned = File(workDir, "${jar.nameWithoutExtension}_slim.jar")
            try {
                rewriteJarWithout(jar, pruned, dup.toSet())
                duplicates.addAll(dup)
                result.add(pruned)
                LogCatcher.i(TAG, "${jar.name}: 剔除 ${dup.size}/${jarClasses.size} 个重复类")
            } catch (e: Exception) {
                LogCatcher.w(TAG, "重写 jar 失败,原样保留: ${jar.name} (${e.message})")
                pruned.delete()
                result.add(jar)
            }
        }
        return result
    }

    /** 收集 `core.apk`(或解压后工作目录)内全部 `classes*.dex` 的类描述符。 */
    fun collectApkClassDescriptors(apk: File): Set<String> {
        val out = HashSet<String>()
        ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.matches(Regex("""classes\d*\.dex""")) }
                .forEach { entry ->
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    out.addAll(parseDexClassDescriptors(bytes))
                }
        }
        return out
    }

    /**
     * 收集某个目录下全部 `classes*.dex` 的类描述符。
     *
     * `ApkBuilder` 会把模板解压到工作目录后再打包,此时直接的 dex 文件比回头再开一遍
     * `core.apk` 更贴合实际产物,也省一次解压。
     */
    fun collectDexClassDescriptorsIn(dir: File): Set<String> {
        val out = HashSet<String>()
        val files = dir.listFiles() ?: return out
        for (f in files) {
            if (!f.isFile) continue
            if (!f.name.matches(Regex("""classes\d*\.dex""", RegexOption.IGNORE_CASE))) continue
            try {
                out.addAll(parseDexClassDescriptors(f.readBytes()))
            } catch (e: Exception) {
                LogCatcher.w(TAG, "解析 ${f.name} 失败,跳过: ${e.message}")
            }
        }
        return out
    }

    /** 收集 jar 内全部 `.class` 的类描述符。 */
    private fun collectJarClassDescriptors(jar: File): Set<String> {
        val out = HashSet<String>()
        ZipFile(jar).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.endsWith(".class") && !it.name.endsWith("module-info.class") }
                .forEach { entry ->
                    out.add(classFileToDescriptor(entry.name))
                }
        }
        return out
    }

    /** `com/foo/Bar.class` → `Lcom/foo/Bar;` */
    private fun classFileToDescriptor(entryName: String): String {
        val noExt = entryName.removeSuffix(".class")
        return "L$noExt;"
    }

    /** 写出一个新 jar,跳过 [remove] 中的类。 */
    private fun rewriteJarWithout(src: File, dst: File, remove: Set<String>) {
        ZipFile(src).use { zip ->
            ZipOutputStream(dst.outputStream().buffered()).use { out ->
                zip.entries().asSequence().forEach { entry ->
                    val skip = entry.name.endsWith(".class") &&
                        remove.contains(classFileToDescriptor(entry.name))
                    if (skip) return@forEach
                    out.putNextEntry(ZipEntry(entry.name))
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // dex 解析
    // ------------------------------------------------------------------

    /**
     * 从 dex 字节流中读出全部类描述符。
     *
     * 只读 header 与 `class_defs` 区,不解析指令,因此足够快也足够安全。
     * dex 头字段布局(偏移均为小端):
     * - `0x38` string_ids_size, `0x3C` string_ids_off
     * - `0x40` type_ids_size,   `0x44` type_ids_off
     * - `0x60` class_defs_size, `0x64` class_defs_off
     * 每个 class_def 32 字节,首 4 字节是 class_idx → 查 type_ids 得 string_idx → 查 string_ids。
     */
    fun parseDexClassDescriptors(bytes: ByteArray): List<String> {
        if (bytes.size < 0x70) return emptyList()
        // magic: "dex\n" + 3 位版本 + \0
        if (bytes[0] != 'd'.code.toByte() || bytes[1] != 'e'.code.toByte() ||
            bytes[2] != 'x'.code.toByte() || bytes[3] != '\n'.code.toByte()
        ) {
            return emptyList()
        }

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        try {
            val stringIdsSize = buf.getInt(0x38)
            val stringIdsOff = buf.getInt(0x3C)
            val typeIdsSize = buf.getInt(0x40)
            val typeIdsOff = buf.getInt(0x44)
            val classDefsSize = buf.getInt(0x60)
            val classDefsOff = buf.getInt(0x64)

            if (stringIdsSize <= 0 || typeIdsSize <= 0 || classDefsSize <= 0) return emptyList()

            val out = ArrayList<String>(classDefsSize)
            for (i in 0 until classDefsSize) {
                val defOff = classDefsOff + i * 32
                if (defOff + 4 > bytes.size) break
                val classIdx = buf.getInt(defOff)
                if (classIdx < 0 || classIdx >= typeIdsSize) continue

                val typeOff = typeIdsOff + classIdx * 4
                if (typeOff + 4 > bytes.size) continue
                val stringIdx = buf.getInt(typeOff)
                if (stringIdx < 0 || stringIdx >= stringIdsSize) continue

                val strOffPos = stringIdsOff + stringIdx * 4
                if (strOffPos + 4 > bytes.size) continue
                val strOff = buf.getInt(strOffPos)

                val name = readDexString(bytes, strOff)
                if (name.isNotEmpty()) out.add(name)
            }
            return out
        } catch (_: Exception) {
            // 脏数据:返回已解析出的部分,让上层保守处理
            return emptyList()
        }
    }

    /** 读取 dex `string_data_item`:先 ULEB128 长度,再以 `\0` 结尾的 MUTF-8。 */
    private fun readDexString(bytes: ByteArray, offset: Int): String {
        if (offset < 0 || offset >= bytes.size) return ""
        var p = offset
        // 跳过 ULEB128 的 utf16 长度
        while (p < bytes.size && (bytes[p].toInt() and 0x80) != 0) p++
        p++ // 长度字段自身最后一字节
        if (p >= bytes.size) return ""
        var end = p
        while (end < bytes.size && bytes[end] != 0.toByte()) end++
        return String(bytes, p, end - p, Charsets.UTF_8)
    }
}
