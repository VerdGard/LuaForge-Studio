package com.luaforge.studio.build

import android.content.Context
import org.json.JSONObject
import com.luaforge.studio.build.maven.*
import com.luaforge.studio.ui.settings.AbiTarget
import com.luaforge.studio.ui.settings.SettingsManager
import com.luaforge.studio.utils.ConsoleUtil
import com.luaforge.studio.utils.JsonUtil
import com.luaforge.studio.utils.LogCatcher
import com.luajava.LuaState
import com.luajava.LuaStateFactory
import java.io.*
import java.util.*
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.regex.Pattern
import java.util.zip.*
// ECJ 和 D8 依赖
import org.eclipse.jdt.internal.compiler.batch.Main as EcjMain
import com.android.tools.r8.D8
import kotlinx.coroutines.runBlocking

/**
 * APK 构建类型。
 *
 * 每个类型只决定两件正交的事:是否对 Lua/ALY 源码做加密编译、是否覆盖
 * `settings.json` 里的 `debugmode`。运行时的"调试模式"即该字段
 * (`LuaActivity.initENV` 读取后赋给 `mDebug`),与本枚举无关地持续存在。
 */
enum class BuildType(
    /** 是否把 Lua/ALY 源码编译加密后打包;false 表示以明文打包。 */
    val encryptLua: Boolean,
    /** 是否覆盖打包内 `settings.json` 的 `debugmode`;null 表示沿用项目设置。 */
    val debugOverride: Boolean?,
    /**
     * 产物文件名中代表该构建类型的片段。
     *
     * 与 [AbiTarget.artifactTag] 一起构成 `名称_架构_类型.apk`。
     * 统一用小写短横线风格,避免文件名里出现空格与大写歧义
     * (`aut` 表示"跟随项目设置",不是某个具体类型)。
     */
    val artifactTag: String
) {
    /**
     * 跟随项目设置:是否加密取 settings.json 的 `encrypt`
     * (此枚举口的 encryptLua 对它不生效),调试模式沿用项目设置。
     */
    PROJECT_DEFAULT(true, null, "aut"),

    /** Debug 版:调试模式开启。 */
    DEBUG(true, true, "debug"),

    /** Release 版:调试模式关闭。 */
    RELEASE(true, false, "release");

    companion object {
        /** 供构建对话框展示的可选类型。 */
        val selectable: List<BuildType> = listOf(PROJECT_DEFAULT, DEBUG, RELEASE)
    }
}

/**
 * Lua 源码的打包方式。
 *
 * 与 [BuildType] 正交:BuildType 决定"加密与否 / 调试开关",本枚举决定
 * "加密后的字节码以什么形态进入 APK"。
 *
 * - [PLAIN_ENC]  —— 传统形态:每个 `.lua`/`.aly` 编译成字节码后仍以**独立文件**
 *   留在 `assets/` 里(文件名不变)。运行期直接按路径加载。
 * - [DEX]        —— 全部字节码打成一份载荷,以 **Java 资源**形式嵌进
 *   `classes*.dex` 的附加数据,产物 `assets/` 里没有任何 `.lua`。
 *   运行期由 `LuaDexLoader` 从 dex 里读回并解包。
 * - [SO]         —— 全部字节码打成一份载荷,加密后**追加到
 *   `libluaforge_payload.so` 尾部**,产物里既无 `.lua` 也无独立数据文件。
 *   运行期由 `PayloadContainer` 从自身 `.so` 尾部读回并解包。
 *
 * 三种方式的运行时目标一致:启动时把项目还原到应用私有目录,再走既有的
 * 文件加载链 —— 差别只在"载荷藏在哪里"。
 */
enum class PackageMode(
    /** 产物文件名中的片段,便于一眼看出打包方式。 */
    val artifactTag: String
) {
    /** 普通加密:字节码保持为独立文件。 */
    PLAIN_ENC("enc"),

    /** 封装进 dex:字节码合并为单一载荷嵌入 classes*.dex。 */
    DEX("dex"),

    /** 封装进 so:字节码合并为单一载荷追加到 libluaforge_payload.so。 */
    SO("so");

    companion object {
        /** 供构建对话框展示的可选模式。 */
        val selectable: List<PackageMode> = listOf(PLAIN_ENC, DEX, SO)

        /** 从持久化名解析,非法值回退 [PLAIN_ENC]。 */
        fun fromName(name: String?): PackageMode =
            entries.firstOrNull { it.name == name } ?: PLAIN_ENC
    }
}

class ApkBuilder {

    companion object {
        // 正则表达式模式数组
        private val PATTERNS = arrayOf(
            "require\\s+\"([a-zA-Z0-9_.]+)\"",  // require "aaa"
            "require\"([a-zA-Z0-9_.]+)\"",       // require"aaa"
            "require\\(\"([a-zA-Z0-9_.]+)\"\\)",  // require("aaa")
            "require\\(\"([a-zA-Z0-9_.]+)\"\\s*,\\s*[^)]+\\)",  // require("aaa", data)
            "require\\s+\'([a-zA-Z0-9_.]+)\'",  // require 'aaa'
            "require\'([a-zA-Z0-9_.]+)\'",       // require'aaa'
            "require\\(\'([a-zA-Z0-9_.]+)\'\\)",  // require('aaa')
            "require\\(\'([a-zA-Z0-9_.]+)\'\\s*,\\s*[^)]+\\)",  // require('aaa', data)

            // import 语句
            "import\\s+\"([a-zA-Z0-9_.]+)\"",  // import "aaa"
            "import\"([a-zA-Z0-9_.]+)\"",       // import"aaa"
            "import\\(\"([a-zA-Z0-9_.]+)\"\\)",  // import("aaa")
            "import\\(\"([a-zA-Z0-9_.]+)\"\\s*,\\s*[^)]+\\)",  // import("aaa", data)
            "import\\s+\'([a-zA-Z0-9_.]+)\'",  // import 'aaa'
            "import\'([a-zA-Z0-9_.]+)\'",       // import'aaa'
            "import\\(\'([a-zA-Z0-9_.]+)\'\\)",  // import('aaa')
            "import\\(\'([a-zA-Z0-9_.]+)\'\\s*,\\s*[^)]+\\)"  // import('aaa', data)
        )

        // LuaState 单例实例
        private var sharedLuaState: LuaState? = null

        // 获取共享的 LuaState 实例
        private fun getSharedLuaState(): LuaState {
            synchronized(this) {
                if (sharedLuaState == null || sharedLuaState!!.isClosed) {
                    sharedLuaState = LuaStateFactory.newLuaState()
                    sharedLuaState!!.openLibs()
                    LogCatcher.i("ApkBuilder", "创建共享 LuaState 实例")
                }
                return sharedLuaState!!
            }
        }

        // 清理共享 LuaState
        /**
         * 释放构建期间复用的 LuaState。
         *
         * 每次构建结束都调用,因此这里**必须真正 close()**,而不只是 gc():
         * 构建会编译整个项目的脚本,一个 LuaState 里会积累大量 proto / 字符串,
         * 只 gc 不关会把这些 native 内存留到下一次 GC(甚至终结器队列),
         * 连做几次构建就能看到 native 堆持续上涨直至 OOM。
         *
         * close() 幂等(内部先置 0),缓存字段同时置空,下次构建重新创建。
         */
        private fun cleanupSharedLuaState() {
            synchronized(this) {
                sharedLuaState?.let { state ->
                    if (!state.isClosed) {
                        try {
                            state.gc(LuaState.LUA_GCCOLLECT, 1)
                            state.top = 0
                        } catch (e: Exception) {
                            LogCatcher.e("ApkBuilder", "清理共享 LuaState 失败", e)
                        }
                        try {
                            state.close()
                            LogCatcher.i("ApkBuilder", "已关闭共享 LuaState")
                        } catch (e: Exception) {
                            LogCatcher.e("ApkBuilder", "关闭共享 LuaState 失败", e)
                        }
                    }
                }
                sharedLuaState = null
            }
        }

        // 核心构建方法
        fun bin(
            context: Context,
            mRootDir: String,
            projectPath: String,
            appName: String,
            packageName: String,
            versionName: String,
            versionCode: String,
            iconPath: String?,
            permissions: Array<String>?,
            isDebug: Boolean,
            outputPath: String,
            minSdkVersion: Int,
            targetSdkVersion: Int,
            mavenDependencies: List<String> = emptyList(),
            buildType: BuildType = BuildType.PROJECT_DEFAULT,
            /** 项目 settings.json 的 encrypt：PROJECT_DEFAULT 构建类型下生效。 */
            encryptEnabled: Boolean = true,
            /** 项目 settings.json 的 mergeDex：是否将 libs 目录下的 dex 合并至 classes 根。 */
            mergeDexEnabled: Boolean = true,
            /** 打包目标架构:通用 / 仅 64 位 / 仅 32 位。默认通用版以保持向后兼容。 */
            abiTarget: AbiTarget = AbiTarget.UNIVERSAL,
            /** 跳过编译(不加密)的文件:相对项目根的路径,打包时保持明文。 */
            skipCompileFiles: List<String> = emptyList(),
            /**
             * 构建精简:只为项目实际声明/引用的类生成 dex,并剔除与模板重复的类。
             *
             * 必须放在参数表末尾 —— 调用方(EditorFunctions)是位置传参,插在中间会错位。
             * 默认 false,关闭时构建行为与旧版一致。
             */
            buildSlimEnabled: Boolean = false,
            /**
             * Lua 源码的打包方式(普通加密 / 封装进 dex / 封装进 so)。
             *
             * 同样是追加参数,理由与 buildSlimEnabled 相同。
             */
            packageMode: PackageMode = PackageMode.PLAIN_ENC
        ): String {
            LogCatcher.i("ApkBuilder", "开始构建APK")
            LogCatcher.i("ApkBuilder", "项目路径: $projectPath")
            LogCatcher.i("ApkBuilder", "输出路径: $outputPath")
            LogCatcher.i("ApkBuilder", "包名: $packageName")
            LogCatcher.i("ApkBuilder", "应用名称: $appName")
            LogCatcher.i("ApkBuilder", "版本号: $versionCode")
            LogCatcher.i("ApkBuilder", "版本名称: $versionName")
            LogCatcher.i("ApkBuilder", "minSdkVersion: $minSdkVersion")
            LogCatcher.i("ApkBuilder", "targetSdkVersion: $targetSdkVersion")
            LogCatcher.i("ApkBuilder", "调试模式: $isDebug")
            LogCatcher.i("ApkBuilder", "权限数量: ${permissions?.size ?: 0}")
            LogCatcher.i("ApkBuilder", "Maven依赖数量: ${mavenDependencies.size}")
            LogCatcher.i("ApkBuilder", "构建类型: $buildType")

            // 加密与否:PROJECT_DEFAULT 取项目 settings.json 的 encrypt,其余构建类型显式决定
            val encryptLua = when (buildType) {
                BuildType.PROJECT_DEFAULT -> encryptEnabled
                else -> buildType.encryptLua
            }
            LogCatcher.i("ApkBuilder", "是否加密 Lua/ALY 源码: $encryptLua")
            LogCatcher.i("ApkBuilder", "跳过编译(不加密)文件数: ${skipCompileFiles.size}")
            LogCatcher.i("ApkBuilder", "构建精简: ${if (buildSlimEnabled) "开启" else "关闭"}")
            LogCatcher.i("ApkBuilder", "打包方式: $packageMode")

            var unsignedApkPath: String? = null

            // 本次构建的独立日志(逐步 + 堆栈),与全局 luaforge.log 分开
            val blog = BuildLogger.start(context, "$appName -> $outputPath")
            BuildLogger.info(blog, "packageName=$packageName versionName=$versionName versionCode=$versionCode")
            BuildLogger.info(blog, "minSdk=$minSdkVersion targetSdk=$targetSdkVersion isDebug=$isDebug")
            BuildLogger.info(blog, "buildType=$buildType packageMode=$packageMode encryptLua=$encryptLua")
            BuildLogger.info(
                blog,
                "mergeDex=$mergeDexEnabled abi=$abiTarget buildSlim=$buildSlimEnabled " +
                    "skipCompile=${skipCompileFiles.size} mavenDeps=${mavenDependencies.size}"
            )
            BuildLogger.info(
                blog,
                "memory max=${Runtime.getRuntime().maxMemory() / 1024 / 1024}MB " +
                    "used=${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024}MB"
            )
            val buildStartNanos = System.nanoTime()

            try {
                // 1. 确保输出目录存在
                var stepNanos = BuildLogger.stepBegin(blog, "10 准备输出目录", outputPath)
                val outputFile = File(outputPath)
                val outputDir = outputFile.parentFile
                outputDir?.takeIf { !it.exists() }?.mkdirs()
                BuildLogger.stepEnd(blog, "10 准备输出目录", stepNanos)

                // 2. 获取核心APK路径（从assets复制到缓存）
                stepNanos = BuildLogger.stepBegin(blog, "20 提取 core.apk")
                val coreApkPath = extractCoreApkToCache(context)
                if (coreApkPath == null) {
                    BuildLogger.stepFail(blog, "20 提取 core.apk", null, "extractCoreApkToCache 返回 null")
                    BuildLogger.finish(blog)
                    return "error: 无法提取核心APK文件"
                }
                BuildLogger.stepEnd(
                    blog, "20 提取 core.apk", stepNanos,
                    "size=${File(coreApkPath).length()}B"
                )

                // 对权限进行预处理，自动补全系统权限前缀
                val processedPermissions = permissions?.map { perm ->
                    if (!perm.contains('.')) {
                        "android.permission.$perm"
                    } else {
                        perm
                    }
                }?.toTypedArray()

                // 3. 修改AndroidManifest.xml
                stepNanos = BuildLogger.stepBegin(blog, "30 修改 AndroidManifest.xml")
                val tempApkPath = modifyAndroidManifest(
                    context,
                    coreApkPath,
                    projectPath,
                    appName,
                    packageName,
                    versionName,
                    versionCode,
                    processedPermissions,
                    minSdkVersion,
                    targetSdkVersion,
                    isDebug,
                    iconPath
                )

                if (tempApkPath == null) {
                    BuildLogger.stepFail(blog, "30 修改 AndroidManifest.xml", null, "modifyAndroidManifest 返回 null")
                    BuildLogger.finish(blog)
                    return "error: 修改AndroidManifest.xml失败"
                }
                BuildLogger.stepEnd(blog, "30 修改 AndroidManifest.xml", stepNanos)

                // 4. 下载 Maven JAR 依赖
                stepNanos = BuildLogger.stepBegin(blog, "40 处理 Maven 依赖", "count=${mavenDependencies.size}")
                val mavenJars = processMavenJarDependencies(context, mavenDependencies)
                BuildLogger.stepEnd(blog, "40 处理 Maven 依赖", stepNanos, "jars=${mavenJars.size}")

                // 5. 生成未签名APK的临时路径
                val unsignedApkFile = File(outputDir, "unsigned_${System.currentTimeMillis()}.apk")
                unsignedApkPath = unsignedApkFile.absolutePath

                // 6. 复制项目文件到临时APK（生成未签名APK）
                stepNanos = BuildLogger.stepBegin(blog, "60 打入项目文件并生成未签名 APK")
                unsignedApkPath = addProjectFilesToApk(
                    context,
                    tempApkPath,
                    projectPath,
                    unsignedApkPath,
                    mavenJars,
                    buildType,
                    encryptLua,
                    mergeDexEnabled,
                    abiTarget,
                    skipCompileFiles,
                    buildSlimEnabled,
                    packageMode,
                    minSdkVersion
                )

                if (unsignedApkPath == null) {
                    BuildLogger.stepFail(blog, "60 打入项目文件并生成未签名 APK", null, "addProjectFilesToApk 返回 null")
                    BuildLogger.finish(blog)
                    return "error: 添加项目文件到APK失败"
                }
                BuildLogger.stepEnd(
                    blog, "60 打入项目文件并生成未签名 APK", stepNanos,
                    "size=${File(unsignedApkPath!!).length()}B"
                )

                // 7. 清理临时文件
                File(tempApkPath).delete()
                File(coreApkPath).delete()

                // 8. 对未签名APK进行签名
                stepNanos = BuildLogger.stepBegin(blog, "80 zipalign + 签名")
                val signedApkPath =
                    signApkWithDefaultKey(context, unsignedApkPath, outputPath, targetSdkVersion)

                // 9. 清理临时文件
                File(unsignedApkPath).delete()

                if (signedApkPath == null) {
                    BuildLogger.stepFail(blog, "80 zipalign + 签名", null, "signApkWithDefaultKey 返回 null")
                    BuildLogger.finish(blog)
                    return "error: APK签名失败"
                }
                BuildLogger.stepEnd(
                    blog, "80 zipalign + 签名", stepNanos,
                    "size=${File(signedApkPath).length()}B"
                )

                LogCatcher.i("ApkBuilder", "APK构建并签名成功: $signedApkPath")
                BuildLogger.info(
                    blog,
                    "构建成功: 总耗时 ${(System.nanoTime() - buildStartNanos) / 1_000_000}ms"
                )
                BuildLogger.info(blog, "产物: $signedApkPath")
                BuildLogger.finish(blog)
                return signedApkPath

            } catch (e: Exception) {
                LogCatcher.e("ApkBuilder", "构建APK失败", e)
                // 完整堆栈进构建日志:这是"定位到堆栈"的主要落点
                BuildLogger.error(
                    blog,
                    "构建抛出异常,总耗时 ${(System.nanoTime() - buildStartNanos) / 1_000_000}ms",
                    e
                )
                BuildLogger.finish(blog)

                // 清理临时文件
                unsignedApkPath?.let { File(it).delete() }

                return "error: 构建失败: ${e.message}"
            } finally {
                // 构建完成后清理共享资源
                cleanupSharedLuaState()
                System.gc()
            }
        }

        /**
         * 处理 Maven JAR 依赖
         * @return 下载的 JAR 文件列表
         */
        private fun processMavenJarDependencies(
            context: Context,
            mavenDependencies: List<String>
        ): List<File> {
            if (mavenDependencies.isEmpty()) {
                return emptyList()
            }
            
            LogCatcher.i("ApkBuilder", "开始处理 Maven JAR 依赖，共 ${mavenDependencies.size} 个")
            
            // 解析依赖字符串
            val parsedDependencies = mavenDependencies.mapNotNull { MavenDependency.parse(it) }
            if (parsedDependencies.isEmpty()) {
                LogCatcher.w("ApkBuilder", "没有有效的 Maven 依赖")
                return emptyList()
            }
            
            val downloadedJars = mutableListOf<File>()
            val downloader = MavenDownloader(context)
            val repositories = MavenRepository.getDefaultRepositories()
            
            for ((index, dep) in parsedDependencies.withIndex()) {
                LogCatcher.i("ApkBuilder", "处理依赖 [${index + 1}/${parsedDependencies.size}]: ${dep.getCoordinates()}")
                
                // 同步下载
                val result = runBlocking {
                    downloader.downloadDependency(dep, repositories, null)
                }
                
                when (result) {
                    is MavenDownloader.DownloadResult.Success -> {
                        // 查找 JAR 文件
                        val jarFile = result.files.find { it.extension == "jar" }
                        if (jarFile != null && jarFile.exists()) {
                            downloadedJars.add(jarFile)
                            LogCatcher.i("ApkBuilder", "  - 下载成功: ${jarFile.name} (${jarFile.length() / 1024} KB)")
                        } else {
                            LogCatcher.w("ApkBuilder", "  - 未找到 JAR 文件，可能不是 JAR 类型")
                        }
                    }
                    is MavenDownloader.DownloadResult.Failure -> {
                        LogCatcher.w("ApkBuilder", "  - 下载失败: ${result.reason}")
                    }
                }
            }
            
            LogCatcher.i("ApkBuilder", "Maven JAR 依赖处理完成，成功下载 ${downloadedJars.size} 个 JAR")
            return downloadedJars
        }

        /**
         * 将 Maven JAR 依赖添加到 DEX 编译流程
         */
        private fun addMavenJarsToDexCompilation(
            context: Context,
            jarFiles: List<File>,
            workDir: File,
            androidJarPath: String?,
            templateClasses: Set<String> = emptySet(),
            projectPath: String = "",
            buildSlimEnabled: Boolean = false,
            minApi: Int = 21
        ) {
            if (jarFiles.isEmpty()) return

            val lib = androidJarPath ?: extractAndroidJarToCache(context) ?: run {
                LogCatcher.w("ApkBuilder", "缺少 android.jar,Maven JAR 无法转 DEX")
                return
            }

            LogCatcher.i("ApkBuilder", "将 ${jarFiles.size} 个 JAR 添加到 DEX 编译")

            try {
                val dexOutputDir = File(workDir, "maven_dex_output")
                dexOutputDir.mkdirs()

                // 统一由 BuildSlimmer 处理:开关关闭时等价于原来的 D8 直转,
                // 开启时先剔除模板已有类再做 R8 可达性裁剪(失败自动回退 D8)。
                val slim = BuildSlimmer.jarsToDex(
                    jars = jarFiles,
                    androidJar = lib,
                    templateClasses = templateClasses,
                    projectPath = projectPath,
                    outDir = dexOutputDir,
                    enabled = buildSlimEnabled,
                    minApi = minApi
                )
                LogCatcher.i(
                    "ApkBuilder",
                    "Maven JAR 转 DEX 完成: 精简=${slim.shrunk}, " +
                        "剔除重复类=${slim.duplicateClasses.size}, 声明类=${slim.declaredClasses.size}"
                )

                // 将生成的 DEX 复制到 APK 工作目录
                addDexFilesToApkWorkDir(dexOutputDir, workDir)

                // 清理临时目录
                dexOutputDir.deleteRecursively()

            } catch (e: Exception) {
                LogCatcher.e("ApkBuilder", "Maven JAR 转 DEX 失败", e)
            }
        }

        /**
         * 构建包含 Maven JAR 的 classpath 字符串
         */
        private fun buildClasspathWithMavenJars(
            androidJarPath: String,
            mavenJars: List<File>
        ): String {
            val classpathParts = mutableListOf(androidJarPath)
            classpathParts.addAll(mavenJars.map { it.absolutePath })
            return classpathParts.joinToString(File.pathSeparator)
        }

        // 使用默认签名密钥对APK进行签名
        private fun signApkWithDefaultKey(
            context: Context,
            unsignedApkPath: String,
            outputPath: String,
            targetSdk: Int
        ): String? {
            LogCatcher.i("ApkBuilder", "开始对APK进行签名")

            try {
                // 1. 解析签名配置:优先使用用户自定义签名文件,否则用内置默认签名
                val signConfig = resolveSignConfig(context)
                val jksPath = signConfig.storePath
                if (jksPath == null) {
                    LogCatcher.e("ApkBuilder", "无法取得签名文件")
                    return null
                }

                LogCatcher.i("ApkBuilder", "签名文件路径: $jksPath")
                LogCatcher.i("ApkBuilder", "输入APK路径: $unsignedApkPath")
                LogCatcher.i("ApkBuilder", "输出APK路径: $outputPath")

                // 2. 验证输入文件是否存在
                val inputApkFile = File(unsignedApkPath)
                if (!inputApkFile.exists()) {
                    LogCatcher.e("ApkBuilder", "输入APK文件不存在: $unsignedApkPath")
                    return null
                }

                LogCatcher.i("ApkBuilder", "输入APK文件大小: ${inputApkFile.length()} 字节")

                // 3. 调用签名方法
                val success = signerApk(
                    context,                     // context
                    jksPath,                     // keyPath
                    signConfig.storePassword,   // pass
                    signConfig.keyAlias,        // alias
                    signConfig.keyPassword,     // keyPass
                    unsignedApkPath,            // inPath
                    outputPath,                 // outPath
                    targetSdk                   // 页对齐宽度依据
                )

                // 4. 清理临时签名文件(内置签名提取自 assets,需删除;自定义签名直接引用用户文件,不删)
                if (signConfig.temporary) File(jksPath).delete()

                if (success) {
                    val signedApkFile = File(outputPath)
                    if (signedApkFile.exists()) {
                        LogCatcher.i(
                            "ApkBuilder",
                            "APK签名成功: $outputPath, 大小: ${signedApkFile.length()} 字节"
                        )
                        return outputPath
                    } else {
                        LogCatcher.e("ApkBuilder", "签名成功但输出文件不存在: $outputPath")
                        return null
                    }
                } else {
                    LogCatcher.e("ApkBuilder", "APK签名失败")
                    return null
                }

            } catch (e: Exception) {
                LogCatcher.e("ApkBuilder", "签名过程异常", e)
                e.printStackTrace()
                return null
            }
        }

        /** 一次签名所需的密钥参数。 */
        private data class SignConfig(
            val storePath: String?,
            val storePassword: String,
            val keyAlias: String,
            val keyPassword: String,
            /** true 表示 storePath 是临时提取的内置签名,签名后应删除。 */
            val temporary: Boolean
        )

        /**
         * 解析签名配置。
         *
         * - 开启自定义签名且文件可读 → 使用用户指定的 keystore,不复制、不删除;
         * - 否则回退到内置签名(从 assets 的 signature.jks 提取到缓存,签名后删除)。
         *
         * 自定义签名文件缺失时回退内置签名并记录告警,避免因配置错误导致构建整体失败。
         */
        private fun resolveSignConfig(context: Context): SignConfig {
            val settings = SettingsManager.currentSettings
            if (settings.signCustomEnabled) {
                val path = settings.signStorePath
                val storeFile = File(path)
                if (storeFile.exists() && storeFile.isFile && storeFile.canRead()) {
                    if (settings.signKeyAlias.isBlank()) {
                        LogCatcher.w("ApkBuilder", "自定义签名未配置别名,回退内置签名")
                    } else {
                        LogCatcher.i("ApkBuilder", "使用自定义签名文件: $path")
                        return SignConfig(
                            storePath = storeFile.absolutePath,
                            storePassword = settings.signStorePassword,
                            keyAlias = settings.signKeyAlias,
                            keyPassword = settings.signKeyPassword.ifBlank { settings.signStorePassword },
                            temporary = false
                        )
                    }
                } else {
                    LogCatcher.w("ApkBuilder", "自定义签名文件不可读,回退内置签名: $path")
                }
            }
            return SignConfig(
                storePath = extractJksToCache(context),
                storePassword = "LuaForge-Studio",
                keyAlias = "LuaForge-Studio",
                keyPassword = "LuaForge-Studio",
                temporary = true
            )
        }

        // 从assets提取JKS签名文件到缓存
        private fun extractJksToCache(context: Context): String? {
            LogCatcher.i("ApkBuilder", "开始提取签名文件")

            try {
                // 创建缓存目录
                val cacheDir = File(context.cacheDir, "apk_sign")
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }

                // 目标文件路径
                val jksFile = File(cacheDir, "signature.jks")

                // 从assets复制文件
                val assetManager = context.assets
                assetManager.open("signature.jks").use { input ->
                    FileOutputStream(jksFile).use { output ->
                        val buffer = ByteArray(8192)
                        var length: Int
                        while (input.read(buffer).also { length = it } > 0) {
                            output.write(buffer, 0, length)
                        }
                        output.flush()
                    }
                }

                LogCatcher.i(
                    "ApkBuilder",
                    "签名文件已提取到: ${jksFile.absolutePath}, 大小: ${jksFile.length()} 字节"
                )
                return jksFile.absolutePath

            } catch (e: IOException) {
                LogCatcher.e("ApkBuilder", "提取签名文件失败", e)
                return null
            }
        }

        // 从assets提取核心APK到缓存
        private fun extractCoreApkToCache(context: Context): String? {
            try {
                // 创建缓存目录
                val cacheDir = File(context.cacheDir, "apk_build")
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }

                // 目标文件路径
                val coreApkFile = File(cacheDir, "core.apk")

                // 缓存有效性必须与**内容**挂钩。
                //
                // 旧实现按"文件在 5 分钟内创建过"就复用缓存。应用升级后
                // `assets/core.apk` 已经换了新模板,可缓存文件仍然"很新",
                // 于是用旧模板打包 —— 表现为升级 IDE 后新加的内置能力在
                // 产物里不存在,极难联想到是缓存问题。
                //
                // 这里改为比对 assets 中条目的长度:长度不同必然要重新提取;
                // assets 长度取不到时(不同 ROM 行为差异)退化为每次重新提取,
                // 宁可慢一点也不能打错包。
                val assetLength = try {
                    context.assets.openFd("core.apk").use { it.length }
                } catch (_: Throwable) {
                    -1L
                }
                if (coreApkFile.exists() && assetLength > 0 && coreApkFile.length() == assetLength) {
                    return coreApkFile.absolutePath
                }

                // 从assets复制文件
                val assetManager = context.assets
                assetManager.open("core.apk").use { input ->
                    FileOutputStream(coreApkFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var length: Int
                        while (input.read(buffer).also { length = it } > 0) {
                            output.write(buffer, 0, length)
                        }
                        output.flush()
                    }
                }

                LogCatcher.i("ApkBuilder", "核心APK已提取到: ${coreApkFile.absolutePath}")
                return coreApkFile.absolutePath

            } catch (e: IOException) {
                LogCatcher.e("ApkBuilder", "提取核心APK失败", e)
                return null
            }
        }

        // 修改AndroidManifest.xml
        private fun modifyAndroidManifest(
            context: Context,
            coreApkPath: String,
            projectPath: String,
            appName: String,
            packageName: String,
            versionName: String,
            versionCode: String,
            permissions: Array<String>?,
            minSdkVersion: Int,
            targetSdkVersion: Int,
            isDebug: Boolean,
            iconPath: String?
        ): String? {
            try {
                // 1. 创建临时目录
                val tempDir = File(context.cacheDir, "apk_temp_${System.currentTimeMillis()}")
                if (!tempDir.exists()) {
                    tempDir.mkdirs()
                }

                // 2. 解压APK到临时目录
                unzipFile(coreApkPath, tempDir.absolutePath)

                // 3. 直接获取AndroidManifest.xml文件（标准APK结构）
                val manifestFile = File(tempDir, "AndroidManifest.xml")
                if (!manifestFile.exists()) {
                    LogCatcher.e("ApkBuilder", "未找到AndroidManifest.xml，APK结构可能异常")
                    deleteDirectory(tempDir)
                    return null
                }

                // 4. 替换图标文件（如果存在）
                replaceIconFile(projectPath, tempDir, iconPath)

                // 5. 使用AxmlEditor修改AndroidManifest.xml
                modifyManifestWithAxmlEditor(
                    manifestFile,
                    appName,
                    packageName,
                    versionName,
                    versionCode,
                    permissions,
                    minSdkVersion,
                    targetSdkVersion,
                    isDebug
                )

                // 6. 重新打包成临时APK
                val tempApkPath = File(tempDir.parent, "temp_modified.apk").absolutePath

                // 删除已存在的临时文件
                val tempApkFile = File(tempApkPath)
                if (tempApkFile.exists()) {
                    tempApkFile.delete()
                }

                // 压缩目录为ZIP文件
                compressDirectoryToZip(tempDir.absolutePath, tempApkPath)

                // 验证临时APK是否创建成功
                if (!tempApkFile.exists()) {
                    LogCatcher.e("ApkBuilder", "临时APK文件未创建成功: $tempApkPath")
                    deleteDirectory(tempDir)
                    return null
                }

                LogCatcher.i(
                    "ApkBuilder",
                    "临时APK创建成功: $tempApkPath, 大小: ${tempApkFile.length()} 字节"
                )

                // 7. 清理临时目录
                deleteDirectory(tempDir)

                return tempApkPath

            } catch (e: Exception) {
                LogCatcher.e("ApkBuilder", "修改AndroidManifest.xml失败", e)
                return null
            }
        }

        // 替换图标文件
        private fun replaceIconFile(projectPath: String, tempDir: File, iconPath: String?) {
            try {
                // 优先用调用方传入的绝对路径（已在构建入口按 iconPath 消歧），回退项目根 icon.png
                val projectIconFile = iconPath?.let { File(it).takeIf { f -> f.isFile } }
                    ?: File(projectPath, "icon.png")

                if (!projectIconFile.exists() || !projectIconFile.isFile) {
                    LogCatcher.i("ApkBuilder", "未找到项目图标，跳过图标替换")
                    return
                }

                LogCatcher.i(
                    "ApkBuilder",
                    "找到项目图标: ${projectIconFile.absolutePath}, 大小: ${projectIconFile.length()} 字节"
                )

                // 在解压目录中查找res/9T.png文件
                val resDir = File(tempDir, "res")
                if (!resDir.exists() || !resDir.isDirectory) {
                    LogCatcher.w("ApkBuilder", "解压目录中未找到res目录，跳过图标替换")
                    return
                }

                val icon9TFile = File(resDir, "9T.png")
                if (!icon9TFile.exists() || !icon9TFile.isFile) {
                    LogCatcher.w("ApkBuilder", "在res目录中未找到9T.png文件，跳过图标替换")
                    return
                }

                LogCatcher.i(
                    "ApkBuilder",
                    "找到9T.png文件: ${icon9TFile.absolutePath}, 大小: ${icon9TFile.length()} 字节"
                )

                // 复制项目图标到9T.png位置
                FileInputStream(projectIconFile).use { input ->
                    FileOutputStream(icon9TFile).use { output ->
                        val buffer = ByteArray(8192)
                        var length: Int
                        while (input.read(buffer).also { length = it } > 0) {
                            output.write(buffer, 0, length)
                        }
                    }
                }

                LogCatcher.i(
                    "ApkBuilder",
                    "图标已替换: ${projectIconFile.absolutePath} -> ${icon9TFile.absolutePath}, 新大小: ${icon9TFile.length()} 字节"
                )

            } catch (e: Exception) {
                LogCatcher.e("ApkBuilder", "替换图标文件失败", e)
            }
        }

        // 使用AxmlEditor修改AndroidManifest.xml
        private fun modifyManifestWithAxmlEditor(
            manifestFile: File,
            appName: String,
            packageName: String,
            versionName: String,
            versionCode: String,
            permissions: Array<String>?,
            minSdkVersion: Int,
            targetSdkVersion: Int,
            isDebug: Boolean
        ) {
            LogCatcher.i("ApkBuilder", "开始修改AndroidManifest.xml")

            val manifestData = readFileToBytes(manifestFile)

            val originalPackageName = getOriginalPackageName(manifestData)
            LogCatcher.i("ApkBuilder", "原始包名: $originalPackageName, 目标包名: $packageName")

            val editor = com.nwdxlgzs.AxmlEditor.AxmlEditor(manifestData)

            if (packageName.isNotEmpty()) {
                editor.setPackageName(packageName)
            }

            if (appName.isNotEmpty()) {
                editor.setAppName(appName)
            }

            try {
                val versionCodeInt = versionCode.toInt()
                editor.setVersionCode(versionCodeInt)
            } catch (e: NumberFormatException) {
                LogCatcher.w("ApkBuilder", "版本号格式错误: $versionCode")
                editor.setVersionCode(1)
            }

            if (versionName.isNotEmpty()) {
                editor.setVersionName(versionName)
            }

            if (minSdkVersion > 0) {
                editor.setMinimumSdk(minSdkVersion)
            }

            if (targetSdkVersion > 0) {
                editor.setTargetSdk(targetSdkVersion)
            }

            if (permissions != null && permissions.isNotEmpty()) {
                editor.setUsePermissions(permissions)
            }

            editor.commit()

            if (originalPackageName != null && packageName.isNotEmpty() && originalPackageName != packageName) {
                editor.fixPermissionNames(originalPackageName, packageName)
                LogCatcher.i(
                    "ApkBuilder",
                    "已修复权限名称中的包名: $originalPackageName -> $packageName"
                )
            }

            FileOutputStream(manifestFile).use { fos ->
                editor.writeTo(fos)
            }

            LogCatcher.i("ApkBuilder", "AndroidManifest.xml修改完成")
        }

        // 辅助方法：从二进制AndroidManifest中读取原始包名
        private fun getOriginalPackageName(manifestData: ByteArray): String? {
            try {
                val reader = com.nwdxlgzs.AxmlEditor.rt.Reader(manifestData)
                val packageName = arrayOfNulls<String>(1)

                reader.accept(object : com.nwdxlgzs.AxmlEditor.rt.Visitor() {
                    override fun child(
                        ns: String?,
                        name: String?
                    ): com.nwdxlgzs.AxmlEditor.rt.NodeVisitor {
                        return object :
                            com.nwdxlgzs.AxmlEditor.rt.NodeVisitor(super.child(ns, name)) {
                            override fun attr(
                                ns: String?,
                                name: String?,
                                resourceId: Int,
                                type: Int,
                                value: Any?
                            ) {
                                if (name.equals("package", ignoreCase = true) && value is String) {
                                    packageName[0] = value
                                }
                                super.attr(ns, name, resourceId, type, value)
                            }
                        }
                    }
                })

                return packageName[0]
            } catch (e: IOException) {
                LogCatcher.e("ApkBuilder", "读取原始包名失败", e)
                return null
            }
        }

        // 添加项目文件到APK
        private fun addProjectFilesToApk(
            context: Context,
            tempApkPath: String,
            projectPath: String,
            outputPath: String,
            mavenJars: List<File> = emptyList(),
            buildType: BuildType = BuildType.PROJECT_DEFAULT,
            encryptLua: Boolean = true,
            mergeDexEnabled: Boolean = true,
            abiTarget: AbiTarget = AbiTarget.UNIVERSAL,
            skipCompileFiles: List<String> = emptyList(),
            buildSlimEnabled: Boolean = false,
            packageMode: PackageMode = PackageMode.PLAIN_ENC,
            /** 目标 minSdk:D8/R8 的 desugaring 依据。 */
            minSdkVersion: Int = 21
        ): String? {
            val L = getSharedLuaState()
            // 复用 bin() 开启的会话;独立调用(如 MCP)时 active 为 null,记录自动跳过
            val blog = BuildLogger.active()

            try {
                // 1. 创建临时工作目录
                var stepNanos = BuildLogger.stepBegin(blog, "61 解压模板到工作目录")
                val workDir = File(context.cacheDir, "apk_work_${System.currentTimeMillis()}")
                if (!workDir.exists()) {
                    workDir.mkdirs()
                }

                // 2. 解压临时APK
                unzipFile(tempApkPath, workDir.absolutePath)
                BuildLogger.stepEnd(blog, "61 解压模板到工作目录", stepNanos, "dir=$workDir")

                // 2.5 收集模板自带类,供构建精简做重复类剔除
                //     解压后的 workDir 根目录就是模板的 classes*.dex,直接读比再开一遍
                //     core.apk 更贴合实际产物。开关关闭时不扫,避免无谓开销。
                val templateClasses: Set<String> = if (buildSlimEnabled) {
                    BuildSlimmer.collectDexClassDescriptorsIn(workDir).also {
                        LogCatcher.i("ApkBuilder", "模板自带类 ${it.size} 个")
                    }
                } else {
                    emptySet()
                }

                // 2.6 探测项目是否用到 Compose,只用于如实播报体积来源。
                //     Compose 运行时在 core.apk 模板里,模板永不裁剪,所以这里无法"移除"它 ——
                //     Compose 桥总是随 core 编入,不存在"零 Compose 体积"的发行版。
                if (BuildSlimmer.usesCompose(projectPath)) {
                    LogCatcher.i(
                        "ApkBuilder",
                        "项目使用 Compose:构建精简只做 keep 保护,不裁剪模板内的 Compose 运行时"
                    )
                }

                // 3. 递归分析项目文件和库文件，获取所有引用的模块
                stepNanos = BuildLogger.stepBegin(blog, "63 分析脚本引用模块")
                val referencedModules = recursivelyAnalyzeModules(projectPath, workDir)
                BuildLogger.stepEnd(
                    blog, "63 分析脚本引用模块", stepNanos,
                    "modules=${referencedModules.size}"
                )
                LogCatcher.i(
                    "ApkBuilder",
                    "总共找到 ${referencedModules.size} 个引用的模块: $referencedModules"
                )

                // 4. 清理未引用的库文件
                cleanUnusedLibraries(workDir, referencedModules, projectPath)

                // 4.5 按目标架构过滤 lib/ 下的 ABI 目录(通用版保留全部)
                applyAbiFilter(workDir, abiTarget)

                // 5. 加密core.apk中引用的库文件(未加密版跳过,保持明文)
                if (encryptLua) {
                    encryptCoreLibraries(L, workDir, referencedModules)
                } else {
                    LogCatcher.i("ApkBuilder", "未加密版:跳过 core 库文件加密")
                }

                // 6. 创建assets目录
                val assetsDir = File(workDir, "assets")
                if (!assetsDir.exists()) {
                    assetsDir.mkdirs()
                }

                // 7. 复制项目文件到assets
                stepNanos = BuildLogger.stepBegin(blog, "67 复制项目文件到 assets")
                copyProjectToAssets(projectPath, assetsDir.absolutePath)

                // 7.5 精简项目 libs/ 目录(开关关闭时零开销跳过)
                if (buildSlimEnabled) {
                    // dex 重写需要 android.jar 作为 library 输入。这里提前取一次
                    // (后面 Java 编译还会用同一份缓存,不会重复解压)。
                    val slimAndroidJar = extractAndroidJarToCache(context)
                    if (slimAndroidJar == null) {
                        LogCatcher.w("ApkBuilder", "缺少 android.jar,libs dex 只能整体丢弃、无法局部裁剪")
                    }
                    val slimLibs = BuildSlimmer.slimProjectLibs(
                        File(assetsDir, "libs"),
                        templateClasses,
                        slimAndroidJar,
                        minSdkVersion
                    )
                    LogCatcher.i(
                        "ApkBuilder",
                        "项目 libs 精简完成: 生效=${slimLibs.shrunk}, 剔除重复类=${slimLibs.duplicateClasses.size}"
                    )
                }

                // 8. 按构建类型覆盖 settings.json 的调试模式
                applyBuildSettingsOverrides(assetsDir, buildType.debugOverride)

                // 9. 加密项目文件(未加密版跳过,保持明文)
                stepNanos = BuildLogger.stepBegin(blog, "69 编译加密脚本", "encrypt=$encryptLua")
                if (encryptLua) {
                    encryptProjectFiles(L, assetsDir, skipCompileFiles)
                    BuildLogger.stepEnd(blog, "69 编译加密脚本", stepNanos)
                } else {
                    LogCatcher.i("ApkBuilder", "未加密版:跳过项目文件加密")
                    BuildLogger.info(blog, "未加密版:跳过脚本加密")
                }

                // 9.1 按打包方式处理:dex / so 模式会把脚本从 assets 里"抽走",
                //     产物中不再有独立的 .lua 文件。抽出的文件稍后统一处理,
                //     必须在 Java 编译与 DEX 合并之前完成(decx 模式要先生成载荷类)。
                stepNanos = BuildLogger.stepBegin(blog, "691 按打包方式处理脚本", "mode=$packageMode")
                val payloadForDex: ByteArray? = when (packageMode) {
                    PackageMode.PLAIN_ENC -> {
                        LogCatcher.i("ApkBuilder", "普通加密模式:脚本以独立文件保留在 assets")
                        null
                    }

                    PackageMode.DEX -> {
                        val payload = extractPayloadFromAssets(assetsDir, skipCompileFiles)
                        if (payload == null) {
                            LogCatcher.e("ApkBuilder", "dex 模式:没有可打包的脚本", null)
                            BuildLogger.stepFail(blog, "691 按打包方式处理脚本", null, "dex 模式无脚本")
                            deleteDirectory(workDir)
                            return null
                        }
                        payload
                    }

                    PackageMode.SO -> {
                        val payload = extractPayloadFromAssets(assetsDir, skipCompileFiles)
                        if (payload == null) {
                            LogCatcher.e("ApkBuilder", "so 模式:没有可打包的脚本", null)
                            BuildLogger.stepFail(blog, "691 按打包方式处理脚本", null, "so 模式无脚本")
                            deleteDirectory(workDir)
                            return null
                        }
                        if (!embedPayloadIntoSo(workDir, payload)) {
                            LogCatcher.e("ApkBuilder", "so 模式:载荷嵌入原生库失败", null)
                            BuildLogger.stepFail(blog, "691 按打包方式处理脚本", null, "嵌入 .so 失败")
                            deleteDirectory(workDir)
                            return null
                        }
                        null
                    }
                }
                BuildLogger.stepEnd(
                    blog, "691 按打包方式处理脚本", stepNanos,
                    "payload=${payloadForDex?.size ?: 0}B"
                )

                // ------------------ Java 编译与 DEX 生成 ------------------
                stepNanos = BuildLogger.stepBegin(blog, "71 编译 Java 与生成 DEX")
                val androidJarPath = extractAndroidJarToCache(context)

                if (androidJarPath != null) {
                    // 先处理 Maven JAR 转 DEX（如果没有 Java 源文件也要处理）
                    if (mavenJars.isNotEmpty()) {
                        addMavenJarsToDexCompilation(
                            context, mavenJars, workDir, androidJarPath,
                            templateClasses = templateClasses,
                            projectPath = projectPath,
                            buildSlimEnabled = buildSlimEnabled,
                            minApi = minSdkVersion
                        )
                    }
                    
                    // 编译 Java 并生成 DEX（传入 Maven JAR）
                    compileJavaAndGenerateDexWithD8(
                        context,
                        projectPath,
                        workDir,
                        androidJarPath,
                        mavenJars,
                        templateClasses = templateClasses,
                        buildSlimEnabled = buildSlimEnabled,
                        dexPayload = payloadForDex,
                        minApi = minSdkVersion
                    )

                    BuildLogger.stepEnd(blog, "71 编译 Java 与生成 DEX", stepNanos)
                    // 删除 assets 下的 java 源文件（避免打包进 APK）
                    val assetsJavaDir = File(assetsDir, "java")
                    if (assetsJavaDir.exists() && assetsJavaDir.isDirectory) {
                        assetsJavaDir.deleteRecursively()
                        LogCatcher.i("ApkBuilder", "已删除 assets 中的 java 源文件目录")
                    }
                } else {
                    // dex 打包模式**依赖**这一步产物:载荷是作为 Java 类编进
                    // dex 的,没有 android.jar 就编不出载荷类,打出来的 APK
                    // 会既没有 .lua 也没有载荷,启动即失败。这里必须显式报错,
                    // 不能像普通模式那样"跳过编译"了事。
                    if (payloadForDex != null) {
                        LogCatcher.e(
                            "ApkBuilder",
                            "dex 打包模式缺少 android.jar,无法生成载荷类",
                            null
                        )
                        BuildLogger.stepFail(
                            blog, "71 编译 Java 与生成 DEX", null,
                            "dex 模式缺少 android.jar"
                        )
                        deleteDirectory(workDir)
                        return null
                    }
                    LogCatcher.w("ApkBuilder", "缺少 android.jar，跳过 Java 编译")
                }
                // ---------------------------------------------------------

                // 9.5 合并 libs/*.dex 至 APK 根 classes*.dex（构建选项关闭时跳过）
                stepNanos = BuildLogger.stepBegin(blog, "75 合并 libs dex 到 APK 根", "mergeDex=$mergeDexEnabled")
                if (mergeDexEnabled) {
                    mergeLibsDexToApkRoot(assetsDir, workDir)
                } else {
                    LogCatcher.i("ApkBuilder", "构建选项已关闭合并 libs dex，保留 assets/libs/*.dex")
                }
                BuildLogger.stepEnd(blog, "75 合并 libs dex 到 APK 根", stepNanos)

                // 10. 删除已存在的输出文件
                val outputFile = File(outputPath)
                if (outputFile.exists()) {
                    outputFile.delete()
                }

                // 11. 重新打包成最终APK
                stepNanos = BuildLogger.stepBegin(blog, "77 压缩为最终 APK")
                compressDirectoryToZip(workDir.absolutePath, outputPath)
                BuildLogger.stepEnd(
                    blog, "77 压缩为最终 APK", stepNanos,
                    "size=${File(outputPath).length()}B"
                )

                // 12. 验证APK文件
                if (!outputFile.exists()) {
                    LogCatcher.e("ApkBuilder", "APK文件未创建成功: $outputPath")
                    BuildLogger.stepFail(blog, "77 压缩为最终 APK", null, "输出文件未生成: $outputPath")
                    deleteDirectory(workDir)
                    return null
                }

                val fileSize = outputFile.length()
                LogCatcher.i("ApkBuilder", "APK文件创建成功: $outputPath, 大小: $fileSize 字节")

                // 13. 清理工作目录
                deleteDirectory(workDir)

                return outputPath

            } catch (e: Exception) {
                LogCatcher.e("ApkBuilder", "添加项目文件失败", e)
                BuildLogger.error(blog, "打入项目文件时抛出异常", e)
                return null
            }
        }

        // ------------------------------------------------------------------
        // 打包模式:载荷抽取与嵌入
        // ------------------------------------------------------------------

        /**
         * 把已加密编译的项目脚本从 `assets` 里抽出来打成单一载荷。
         *
         * 抽取后**删除源文件** —— 这正是 dex / so 模式"产物里不再有 lua 文件"
         * 的实现点。`settings.json`、图标等非脚本文件保持原位,因为运行时与
         * 签名流程都还要用它们。
         *
         * @return 载荷字节;没有可抽取脚本时返回 null
         */
        private fun extractPayloadFromAssets(
            assetsDir: File,
            skipCompileFiles: List<String>
        ): ByteArray? {
            val payload = LuaPayloadBuilder.build(assetsDir, skipCompileFiles.toSet())
                ?: return null

            var removed = 0
            assetsDir.walkBottomUp()
                .filter { it.isFile && it.name.endsWith(".lua", ignoreCase = true) }
                .forEach { file ->
                    val rel = file.relativeTo(assetsDir).path.replace('\\', '/')
                    if (skipCompileFiles.contains(rel)) return@forEach
                    if (file.delete()) removed++
                }

            LogCatcher.i(
                "ApkBuilder",
                "已从 assets 抽取脚本: ${removed} 个文件 -> 载荷 ${payload.size} 字节"
            )
            return payload
        }

        /**
         * 把载荷追加进 `libluaforge_payload.so`(so 打包模式)。
         *
         * 目标 `.so` 由 CMake 编进 `core.apk` 模板,解压后位于
         * `lib/<abi>/libluaforge_payload.so`。多架构时要**每个 ABI 各追加一份**
         * —— 设备只会加载其中一个,其它 ABI 的那份是给别的设备用的。
         *
         * @return 是否至少成功嵌入了一个 ABI 的库
         */
        private fun embedPayloadIntoSo(workDir: File, payload: ByteArray): Boolean {
            val seed = java.util.Random(System.currentTimeMillis()).nextInt()

            val soFiles = workDir.walkTopDown()
                .filter {
                    it.isFile && it.name == "libluaforge_payload.so"
                }
                .toList()

            if (soFiles.isEmpty()) {
                LogCatcher.e(
                    "ApkBuilder",
                    "模板里没有 libluaforge_payload.so,无法使用 so 打包模式",
                    null
                )
                return false
            }

            var ok = 0
            for (so in soFiles) {
                val rel = so.relativeTo(workDir).path
                if (LuaPayloadBuilder.appendPayloadToSo(so, payload, seed)) {
                    ok++
                    LogCatcher.i("ApkBuilder", "载荷已嵌入: $rel")
                } else {
                    LogCatcher.w("ApkBuilder", "载荷嵌入失败: $rel")
                }
            }
            LogCatcher.i("ApkBuilder", "so 打包模式: ${ok}/${soFiles.size} 个 ABI 已嵌入载荷")
            return ok > 0
        }

        // 加密core.apk中的库文件
        private fun encryptCoreLibraries(
            L: LuaState,
            workDir: File,
            referencedModules: Set<String>
        ) {
            LogCatcher.i("ApkBuilder", "开始加密core.apk中的库文件")

            if (L.isClosed) {
                LogCatcher.e("ApkBuilder", "LuaState无效或已关闭，无法加密库文件")
                throw RuntimeException("LuaState无效或已关闭")
            }

            val luaDir = File(workDir, "lua")
            if (!luaDir.exists() || !luaDir.isDirectory) {
                LogCatcher.i("ApkBuilder", "core.apk中未找到lua目录，跳过库文件加密")
                return
            }

            var encryptedCount = 0
            var failedCount = 0

            for (moduleName in referencedModules) {
                val luaFilePath = moduleName.replace('.', '/') + ".lua"
                val luaFile = File(luaDir, luaFilePath)

                if (luaFile.exists() && luaFile.isFile) {
                    val success = encryptLuaFile(L, luaFile)
                    if (success) {
                        encryptedCount++
                    } else {
                        failedCount++
                    }
                }
            }

            LogCatcher.i(
                "ApkBuilder",
                "core.apk库文件加密完成: 成功 $encryptedCount 个, 失败 $failedCount 个"
            )
        }

        /**
         * 按构建类型覆盖打包进 assets 的 `settings.json` 的 `debugmode`。
         *
         * 该字段是运行时"调试模式"的唯一来源:core 的 `LuaActivity.initENV`
         * 读取 `application.debugmode` 赋给 `mDebug`,`mDebug` 为 true 时
         * 运行日志同时以 toast 弹出(`LuaActivity.java:1642`)。
         *
         * @param debugOverride null 表示沿用项目自身设置,不做修改
         */
        private fun applyBuildSettingsOverrides(assetsDir: File, debugOverride: Boolean?) {
            if (debugOverride == null) return

            val settingsFile = File(assetsDir, "settings.json")
            if (!settingsFile.exists()) {
                LogCatcher.w("ApkBuilder", "assets 中未找到 settings.json,跳过调试模式覆盖")
                return
            }

            try {
                val parsed = JsonUtil.parseObject(settingsFile.readText())
                val jsonMap = parsed.toMutableMap()
                val application = JsonUtil.asMutableMap(jsonMap["application"]) ?: mutableMapOf()
                application["debugmode"] = debugOverride
                jsonMap["application"] = application

                settingsFile.writeText(JSONObject(jsonMap).toString(4))
                LogCatcher.i("ApkBuilder", "已覆盖 settings.json 调试模式: $debugOverride")
            } catch (e: Exception) {
                // 不做静默降级:调试模式错误会让用户拿到与预期不符的包
                throw RuntimeException("覆盖 settings.json 调试模式失败: ${e.message}", e)
            }
        }

        // 加密项目文件
        private fun encryptProjectFiles(
            L: LuaState,
            assetsDir: File,
            skipCompileFiles: List<String>
        ) {
            LogCatcher.i("ApkBuilder", "开始加密项目文件")

            if (L.isClosed) {
                LogCatcher.e("ApkBuilder", "LuaState无效或已关闭，无法加密项目文件")
                throw RuntimeException("LuaState无效或已关闭")
            }

            // 归一化跳过列表:统一用 '/' 分隔,去掉首尾空白与前导 './'
            val skipSet = skipCompileFiles
                .map { it.trim().replace('\\', '/').removePrefix("./") }
                .filter { it.isNotEmpty() }
                .toSet()
            if (skipSet.isNotEmpty()) {
                LogCatcher.i("ApkBuilder", "跳过编译(不加密)文件: $skipSet")
            }

            val counters =
                intArrayOf(0, 0, 0, 0) // [luaEncrypted, alyEncrypted, luaFailed, alyFailed]
            encryptDirectoryRecursive(L, assetsDir, assetsDir, counters, skipSet)

            LogCatcher.i(
                "ApkBuilder",
                "项目文件加密完成: Lua成功 ${counters[0]} 个, ALY成功 ${counters[1]} 个, Lua失败 ${counters[2]} 个, ALY失败 ${counters[3]} 个"
            )
        }

        // 递归加密目录
        private fun encryptDirectoryRecursive(
            L: LuaState,
            baseDir: File,
            dir: File,
            counters: IntArray,
            skipSet: Set<String>
        ) {
            val files = dir.listFiles() ?: return

            for (file in files) {
                when {
                    file.isDirectory -> encryptDirectoryRecursive(
                        L, baseDir, file, counters, skipSet
                    )
                    file.isFile -> {
                        val fileName = file.name.lowercase(Locale.getDefault())

                        // 命中「跳过编译」则保持明文原样打包
                        if (isSkipped(file, baseDir, skipSet)) {
                            LogCatcher.i("ApkBuilder", "跳过编译(保持明文): ${file.name}")
                            continue
                        }

                        when {
                            fileName.endsWith(".lua") -> {
                                val success = encryptLuaFile(L, file)
                                if (success) {
                                    counters[0]++ // luaEncrypted
                                } else {
                                    counters[2]++ // luaFailed
                                }
                            }

                            fileName.endsWith(".aly") -> {
                                val success = encryptAlyFile(L, file)
                                if (success) {
                                    counters[1]++ // alyEncrypted
                                } else {
                                    counters[3]++ // alyFailed
                                }
                            }
                        }
                    }
                }
            }
        }

        /**
         * 判断文件是否在「跳过编译」列表中。
         *
         * 列表存的是**相对项目根**的路径;而这里遍历的是 assets 副本,
         * 故用相对 assets 根的路径去比对(两者目录结构一致)。
         */
        private fun isSkipped(
            file: File,
            assetsRoot: File,
            skipSet: Set<String>
        ): Boolean {
            if (skipSet.isEmpty()) return false
            val rel = try {
                file.relativeTo(assetsRoot).path.replace('\\', '/')
            } catch (e: Exception) {
                return false
            }
            return rel in skipSet
        }

        // 加密Lua文件
        private fun encryptLuaFile(L: LuaState, luaFile: File): Boolean {
            return try {
                // 使用 ConsoleUtil.build 编译文件
                val result = ConsoleUtil.build(L, luaFile.absolutePath)

                // 从返回的 table 中获取 path
                val resultTable = result as? Map<*, *>
                val encryptedPath = resultTable?.get("path") as? String
                val errorMsg = resultTable?.get("error") as? String

                if (encryptedPath != null) {
                    val encryptedFile = File(encryptedPath)

                    if (encryptedFile.exists()) {
                        if (!luaFile.delete()) {
                            LogCatcher.w("ApkBuilder", "无法删除原Lua文件: ${luaFile.absolutePath}")
                            encryptedFile.delete()
                            false
                        } else {
                            val renamedFile = File(luaFile.absolutePath)
                            if (!encryptedFile.renameTo(renamedFile)) {
                                LogCatcher.e(
                                    "ApkBuilder",
                                    "无法重命名.luac文件为.lua: $encryptedPath"
                                )
                                encryptedFile.delete()
                                false
                            } else {
                                true
                            }
                        }
                    } else {
                        // 如果加密后的文件不存在，抛出异常停止构建
                        throw RuntimeException("加密后的文件不存在: $encryptedPath")
                    }
                } else {
                    // 如果编译出错，抛出异常停止构建
                    throw RuntimeException("Lua文件编译失败: ${errorMsg ?: "未知错误"}")
                }
            } catch (e: Exception) {
                // 捕获异常并重新抛出，停止构建进程
                LogCatcher.e("ApkBuilder", "加密Lua文件异常: ${luaFile.absolutePath}", e)
                throw RuntimeException("加密Lua文件失败: ${e.message}")
            }
        }

        // 加密Aly文件
        private fun encryptAlyFile(L: LuaState, alyFile: File): Boolean {
            return try {
                // 使用 ConsoleUtil.build 编译文件
                val result = ConsoleUtil.build(L, alyFile.absolutePath)

                // 从返回的 table 中获取 path
                val resultTable = result as? Map<*, *>
                val encryptedPath = resultTable?.get("path") as? String
                val errorMsg = resultTable?.get("error") as? String

                if (encryptedPath != null) {
                    val encryptedFile = File(encryptedPath)

                    if (encryptedFile.exists()) {
                        if (!alyFile.delete()) {
                            LogCatcher.w("ApkBuilder", "无法删除原ALY文件: ${alyFile.absolutePath}")
                            encryptedFile.delete()
                            false
                        } else {
                            val newPath = alyFile.absolutePath.replace(".aly", ".lua")
                            val renamedFile = File(newPath)

                            if (!encryptedFile.renameTo(renamedFile)) {
                                LogCatcher.e(
                                    "ApkBuilder",
                                    "无法重命名.alyc文件为.lua: $encryptedPath"
                                )
                                encryptedFile.delete()
                                false
                            } else {
                                true
                            }
                        }
                    } else {
                        // 如果加密后的文件不存在，抛出异常停止构建
                        throw RuntimeException("加密后的文件不存在: $encryptedPath")
                    }
                } else {
                    // 如果编译出错，抛出异常停止构建
                    throw RuntimeException("ALY文件编译失败: ${errorMsg ?: "未知错误"}")
                }
            } catch (e: Exception) {
                // 捕获异常并重新抛出，停止构建进程
                LogCatcher.e("ApkBuilder", "加密ALY文件异常: ${alyFile.absolutePath}", e)
                throw RuntimeException("加密ALY文件失败: ${e.message}")
            }
        }

        // 获取相对路径
        private fun getRelativePath(baseDir: File, file: File): String {
            val basePath = baseDir.absolutePath
            val filePath = file.absolutePath

            return if (filePath.startsWith(basePath)) {
                filePath.substring(basePath.length + 1)
            } else filePath
        }

        // 递归分析项目文件和库文件，获取所有引用的模块
        private fun recursivelyAnalyzeModules(projectPath: String, workDir: File): Set<String> {
            val allModules = HashSet<String>()
            val analyzedFiles = HashSet<String>()
            val moduleQueue = LinkedList<String>()

            val projectModules = analyzeProjectForModules(projectPath)
            allModules.addAll(projectModules)
            moduleQueue.addAll(projectModules)

            LogCatcher.i("ApkBuilder", "项目文件分析完成，找到 ${projectModules.size} 个模块")

            while (moduleQueue.isNotEmpty()) {
                val module = moduleQueue.poll() ?: break

                val luaFilePath = module.replace('.', '/') + ".lua"
                val luaFile = File(workDir, "lua/$luaFilePath")

                val fileKey = luaFile.absolutePath
                if (analyzedFiles.contains(fileKey)) {
                    continue
                }

                if (luaFile.exists() && luaFile.isFile) {
                    analyzedFiles.add(fileKey)

                    val newModules = analyzeFileForModules(luaFile)

                    for (newModule in newModules) {
                        if (!allModules.contains(newModule)) {
                            allModules.add(newModule)
                            moduleQueue.add(newModule)
                        }
                    }
                }
            }

            return allModules
        }

        // 分析项目文件，提取所有引用的模块
        private fun analyzeProjectForModules(projectPath: String): Set<String> {
            val modules = HashSet<String>()
            val projectDir = File(projectPath)

            analyzeDirectoryForModules(projectDir, modules)

            LogCatcher.i("ApkBuilder", "项目文件中共找到 ${modules.size} 个引用的模块")
            return modules
        }

        // 递归分析目录中的Lua和ALY文件
        private fun analyzeDirectoryForModules(dir: File, modules: MutableSet<String>) {
            val files = dir.listFiles() ?: return

            for (file in files) {
                when {
                    file.isDirectory -> analyzeDirectoryForModules(file, modules)
                    file.isFile && (file.name.lowercase(Locale.getDefault()).endsWith(".lua") ||
                            file.name.lowercase(Locale.getDefault()).endsWith(".aly")) -> {
                        analyzeFileAndAddToSet(file, modules)
                    }
                }
            }
        }

        // 分析单个文件，提取require和import语句，将结果添加到Set中
        private fun analyzeFileAndAddToSet(file: File, modules: MutableSet<String>) {
            val fileModules = analyzeFileForModules(file)
            modules.addAll(fileModules)
        }

        // 分析单个文件，提取require和import语句，返回Set<String>
        private fun analyzeFileForModules(file: File): Set<String> {
            val modules = HashSet<String>()
            BufferedReader(FileReader(file)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    for (pattern in PATTERNS) {
                        val p = Pattern.compile(pattern)
                        val m = p.matcher(line)

                        while (m.find()) {
                            val moduleName = m.group(1)
                            if (moduleName != null && moduleName.trim().isNotEmpty()) {
                                modules.add(moduleName)
                            }
                        }
                    }
                }
            }
            return modules
        }

        /**
         * 按目标架构过滤 APK 内的原生库目录。
         *
         * - [AbiTarget.UNIVERSAL] 保留 armeabi-v7a 与 arm64-v8a
         * - [AbiTarget.ARM64]     仅保留 arm64-v8a,删除 lib/armeabi-v7a
         * - [AbiTarget.ARM32]     仅保留 armeabi-v7a,删除 lib/arm64-v8a
         *
         * 通过删除整个 ABI 目录实现,不触碰库文件本身的引用扫描逻辑。
         */
        private fun applyAbiFilter(workDir: File, abiTarget: AbiTarget) {
            val removeDirs = when (abiTarget) {
                AbiTarget.UNIVERSAL -> emptyList()
                AbiTarget.ARM64 -> listOf("armeabi-v7a")
                AbiTarget.ARM32 -> listOf("arm64-v8a")
            }
            for (abi in removeDirs) {
                val dir = File(workDir, "lib/$abi")
                if (dir.exists()) {
                    deleteDirectory(dir)
                    LogCatcher.i("ApkBuilder", "按目标架构($abiTarget)移除 lib/$abi")
                }
            }
        }

        // 清理未引用的库文件
        private fun cleanUnusedLibraries(
            workDir: File,
            referencedModules: Set<String>,
            projectPath: String
        ) {
            // 必须保留的文件集合。
            //
            // libluaforge_payload.so 是 so 打包模式的**载荷容器**:它不会被任何
            // 项目脚本 require,因此 isSoFileReferenced 永远判它"未引用"并把文件删掉
            // —— 载荷随之消失,产物启动即找不到脚本。它必须无条件保留。
            val mustKeepFiles = hashSetOf("libluajava.so", "libluaforge_payload.so")

            // 检查项目中是否使用了 LuaParserUtil，如果是则保留 libluaparse.so
            val projectDir = File(projectPath)
            if (projectDir.exists()) {
                val usesLuaParserUtil = checkProjectForLuaParserUtil(projectDir)
                if (usesLuaParserUtil) {
                    mustKeepFiles.add("libluaparser.so")
                }

            }

            val luaDir = File(workDir, "lua")
            if (luaDir.exists() && luaDir.isDirectory) {
                cleanLuaDirectory(luaDir, referencedModules)
            }

            val libArmV7aDir = File(workDir, "lib/armeabi-v7a")
            if (libArmV7aDir.exists() && libArmV7aDir.isDirectory) {
                cleanLibDirectory(libArmV7aDir, referencedModules, mustKeepFiles)
            }

            val libArm64V8aDir = File(workDir, "lib/arm64-v8a")
            if (libArm64V8aDir.exists() && libArm64V8aDir.isDirectory) {
                cleanLibDirectory(libArm64V8aDir, referencedModules, mustKeepFiles)
            }

            LogCatcher.i("ApkBuilder", "库文件清理完成")
        }

        // 检查项目目录中是否存在 LuaParserUtil 的引用
        private fun checkProjectForLuaParserUtil(projectDir: File): Boolean {
            return checkDirectoryForLuaParserUtil(projectDir)
        }

        // 递归检查目录中的文件
        private fun checkDirectoryForLuaParserUtil(dir: File): Boolean {
            val files = dir.listFiles() ?: return false

            for (file in files) {
                when {
                    file.isDirectory -> {
                        if (checkDirectoryForLuaParserUtil(file)) {
                            return true
                        }
                    }

                    file.isFile -> {
                        val fileName = file.name.lowercase(Locale.getDefault())
                        // 只检查 Lua 和 ALY 文件
                        if (fileName.endsWith(".lua") || fileName.endsWith(".aly")) {
                            if (checkFileForLuaParserUtil(file)) {
                                return true
                            }
                        }
                    }
                }
            }
            return false
        }

        // 检查单个文件是否包含 LuaParserUtil 引用
        private fun checkFileForLuaParserUtil(file: File): Boolean {
            return try {
                BufferedReader(FileReader(file)).use { reader ->
                    val content = reader.readText()
                    content.contains("com.luaforge.studio.utils.LuaParserUtil")
                }
            } catch (e: Exception) {
                false
            }
        }

        // 清理lua目录中的文件
        private fun cleanLuaDirectory(luaDir: File, referencedModules: Set<String>) {
            cleanLuaDirectoryRecursive(luaDir, "", referencedModules)
        }

        private fun cleanLuaDirectoryRecursive(
            dir: File,
            relativePath: String,
            referencedModules: Set<String>
        ) {
            val files = dir.listFiles() ?: return

            for (file in files) {
                val newRelativePath =
                    if (relativePath.isEmpty()) file.name else "$relativePath/${file.name}"

                when {
                    file.isDirectory -> cleanLuaDirectoryRecursive(
                        file,
                        newRelativePath,
                        referencedModules
                    )

                    file.isFile && file.name.lowercase(Locale.getDefault()).endsWith(".lua") -> {
                        val isReferenced = isLuaFileReferenced(newRelativePath, referencedModules)

                        if (!isReferenced) {
                            if (!file.delete()) {
                                LogCatcher.w("ApkBuilder", "无法删除文件: ${file.absolutePath}")
                            }
                        }
                    }
                }
            }
        }

        // 检查lua文件是否被引用
        private fun isLuaFileReferenced(
            luaFilePath: String,
            referencedModules: Set<String>
        ): Boolean {
            val moduleName = luaFilePath.substring(0, luaFilePath.length - 4).replace("/", ".")
            return referencedModules.contains(moduleName)
        }

        // 清理lib目录中的文件
        private fun cleanLibDirectory(
            libDir: File,
            referencedModules: Set<String>,
            mustKeepFiles: Set<String>
        ) {
            val files = libDir.listFiles() ?: return

            for (file in files) {
                if (file.isFile && file.name.lowercase(Locale.getDefault()).endsWith(".so")) {
                    val soName = file.name

                    if (mustKeepFiles.contains(soName)) {
                        continue
                    }

                    val isReferenced = isSoFileReferenced(soName, referencedModules)

                    if (!isReferenced) {
                        if (!file.delete()) {
                            LogCatcher.w("ApkBuilder", "无法删除文件: ${file.absolutePath}")
                        }
                    }
                }
            }
        }

        // 检查so文件是否被引用
        private fun isSoFileReferenced(
            soFileName: String,
            referencedModules: Set<String>
        ): Boolean {
            if (soFileName.startsWith("lib") && soFileName.endsWith(".so")) {
                val moduleName = soFileName.substring(3, soFileName.length - 3)
                if (!moduleName.contains(".")) {
                    return referencedModules.contains(moduleName)
                }
            }
            return false
        }

        // 复制项目文件到assets目录
        private fun copyProjectToAssets(projectPath: String, assetsPath: String) {
            try {
                val projectDir = File(projectPath)
                val assetsDir = File(assetsPath)

                if (!projectDir.exists() || !projectDir.isDirectory) {
                    throw IOException("项目目录不存在: $projectPath")
                }

                copyDirectory(projectDir, assetsDir)
                LogCatcher.i("ApkBuilder", "项目文件已复制到assets: $assetsPath")

            } catch (e: IOException) {
                LogCatcher.e("ApkBuilder", "复制项目文件失败", e)
                throw RuntimeException(e)
            }
        }

        // 工具方法：读取文件到字节数组
        private fun readFileToBytes(file: File): ByteArray {
            FileInputStream(file).use { fis ->
                ByteArrayOutputStream().use { bos ->
                    val buffer = ByteArray(8192)
                    var length: Int
                    while (fis.read(buffer).also { length = it } > 0) {
                        bos.write(buffer, 0, length)
                    }
                    return bos.toByteArray()
                }
            }
        }

        // 工具方法：解压ZIP文件
        private fun unzipFile(zipFilePath: String, destDirectory: String) {
            val destDir = File(destDirectory)
            if (!destDir.exists()) {
                destDir.mkdirs()
            }

            ZipFile(zipFilePath).use { zipFile ->
                val entries = zipFile.entries()

                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val filePath = destDirectory + File.separator + entry.name

                    if (!entry.isDirectory) {
                        File(filePath).parentFile?.mkdirs()

                        zipFile.getInputStream(entry).use { input ->
                            FileOutputStream(filePath).use { output ->
                                val buffer = ByteArray(8192)
                                var length: Int
                                while (input.read(buffer).also { length = it } > 0) {
                                    output.write(buffer, 0, length)
                                }
                            }
                        }
                    } else {
                        val dir = File(filePath)
                        dir.mkdirs()
                    }
                }
            }

            LogCatcher.i("ApkBuilder", "ZIP文件解压完成: $zipFilePath -> $destDirectory")
        }

        // 压缩目录为ZIP文件
        private fun compressDirectoryToZip(sourceDir: String, zipFilePath: String) {
            val sourceDirFile = File(sourceDir)
            if (!sourceDirFile.exists()) {
                throw IOException("源目录不存在: $sourceDir")
            }

            val zipFile = File(zipFilePath)
            val parentDir = zipFile.parentFile
            parentDir?.takeIf { !it.exists() }?.mkdirs()

            if (zipFile.exists()) {
                zipFile.delete()
            }

            FileOutputStream(zipFile).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    compressDirectoryRecursive(sourceDirFile, "", zos)
                }
            }

            LogCatcher.i(
                "ApkBuilder",
                "目录压缩完成: $sourceDir -> $zipFilePath, 大小: ${zipFile.length()} 字节"
            )
        }

        // 递归压缩目录
        private fun compressDirectoryRecursive(
            file: File,
            relativePath: String,
            zos: ZipOutputStream
        ) {
            if (file.isHidden) {
                return
            }

            if (file.isDirectory) {
                var dirPath = relativePath
                if (dirPath.isNotEmpty() && !dirPath.endsWith("/")) {
                    dirPath += "/"
                }

                if (dirPath.isNotEmpty()) {
                    zos.putNextEntry(ZipEntry(dirPath))
                    zos.closeEntry()
                }

                val children = file.listFiles()
                children?.forEach { childFile ->
                    val childPath = dirPath + childFile.name
                    compressDirectoryRecursive(childFile, childPath, zos)
                }
                return
            }

            val filePath = relativePath
            val zipEntry = ZipEntry(filePath)

            // Android R+要求resources.arsc必须STORED
            if ("resources.arsc" == file.name) {
                // 读取文件数据
                val fileData = file.readBytes()

                zipEntry.method = ZipEntry.STORED
                zipEntry.size = fileData.size.toLong()
                zipEntry.compressedSize = fileData.size.toLong()
                zipEntry.crc = calculateCrc32(fileData)  // 现在调用 ByteArray 版本

                // 直接写入数据，不需要 FileInputStream
                zos.putNextEntry(zipEntry)
                zos.write(fileData)
                zos.closeEntry()
            } else {
                zipEntry.method = ZipEntry.DEFLATED
                FileInputStream(file).use { fis ->
                    zos.putNextEntry(zipEntry)
                    val buffer = ByteArray(8192)
                    var length: Int
                    while (fis.read(buffer).also { length = it } > 0) {
                        zos.write(buffer, 0, length)
                    }
                    zos.closeEntry()
                }
            }
        }

        // 计算文件的CRC32值（File版本）
        private fun calculateCrc32(file: File): Long {
            val crc = CRC32()
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var length: Int
                while (fis.read(buffer).also { length = it } != -1) {
                    crc.update(buffer, 0, length)
                }
            }
            return crc.value
        }

        // 计算字节数组的CRC32值（ByteArray版本）
        private fun calculateCrc32(data: ByteArray): Long {
            val crc = CRC32()
            crc.update(data)
            return crc.value
        }

        // 工具方法：复制目录
        private fun copyDirectory(source: File, destination: File) {
            if (source.isDirectory) {
                if (!destination.exists()) {
                    destination.mkdirs()
                }

                val files = source.list()
                files?.forEach { file ->
                    val srcFile = File(source, file)
                    val destFile = File(destination, file)
                    copyDirectory(srcFile, destFile)
                }
            } else {
                FileInputStream(source).use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(8192)
                        var length: Int
                        while (input.read(buffer).also { length = it } > 0) {
                            output.write(buffer, 0, length)
                        }
                    }
                }
            }
        }

        // 工具方法：删除目录
        private fun deleteDirectory(dir: File) {
            if (dir.exists()) {
                val files = dir.listFiles()
                files?.forEach { file ->
                    if (file.isDirectory) {
                        deleteDirectory(file)
                    } else {
                        file.delete()
                    }
                }
                dir.delete()
            }
        }

        // 签名APK
        private fun signerApk(
            context: Context,  // 添加 context 参数
            keyPath: String,
            pass: String,
            alias: String,
            keyPass: String,
            inPath: String,
            outPath: String,
            /** 目标 SDK:转发给 zipalign 决定页对齐宽度。 */
            targetSdk: Int
        ): Boolean {
            return try {
                // 先对输入的APK进行对齐处理（使用真正的 zipalign）
                val alignedPath = inPath + ".aligned"
                val aligned = zipAlignApk(context, inPath, alignedPath, targetSdk)

                val inputFile = if (aligned) {
                    File(alignedPath)
                } else {
                    LogCatcher.w("ApkBuilder", "对齐失败，使用原始文件签名")
                    File(inPath)
                }

                // 确保输入文件存在
                if (!inputFile.exists()) {
                    LogCatcher.e("ApkBuilder", "输入文件不存在: ${inputFile.absolutePath}")
                    return false
                }

                val signer = com.mcal.apksigner.ApkSigner(inputFile, File(outPath))
                signer.v1SigningEnabled = true
                signer.v2SigningEnabled = true
                signer.v3SigningEnabled = true
                signer.signRelease(File(keyPath), pass, alias, keyPass)

                // 清理临时对齐文件
                if (aligned && File(alignedPath).exists()) {
                    File(alignedPath).delete()
                }

                true
            } catch (e: Throwable) {
                LogCatcher.e("ApkBuilder", "签名过程异常", e as Exception?)
                e.printStackTrace()
                false
            }
        }

        /**
         * 使用系统 zipalign 工具对齐 APK
         * 从 /data/app/.../lib/arm64/libzipalign.so 直接执行
         */
        private fun zipAlignApk(
            context: Context,
            inputPath: String,
            outputPath: String,
            /** 目标 SDK:决定页对齐宽度(35+ 用 16KB)。 */
            targetSdk: Int
        ): Boolean {
            return try {
                // 获取 zipalign 路径：/data/app/.../lib/arm64/libzipalign.so
                val zipalignPath = File(context.applicationInfo.nativeLibraryDir, "libzipalign.so").absolutePath
                
                // 检查文件是否存在
                if (!File(zipalignPath).exists()) {
                    LogCatcher.e("ApkBuilder", "zipalign 不存在: $zipalignPath")
                    return simpleZipAlign(inputPath, outputPath)
                }

                LogCatcher.i("ApkBuilder", "使用 zipalign: $zipalignPath")

                // 删除已存在的输出文件
                File(outputPath).delete()

                // 构建命令：zipalign -f -p -v <align> input.apk output.apk
                //
                // 对齐宽度必须与目标设备页大小匹配:Android 15(API 35)+ 要求
                // `lib/*.so` 按 **16KB** 页对齐,否则在 16KB 页设备上直接装不上
                // (INSTALL_FAILED_INVALID_APK)或运行即崩。
                // 旧代码写死 4,只在 4KB 页设备上正常。
                val alignBytes = if (targetSdk >= 35) "16384" else "4"
                val cmd = arrayOf(
                    zipalignPath,
                    "-f",           // 强制覆盖
                    "-p",           // 页对齐
                    "-v",           // 详细输出
                    alignBytes,
                    inputPath,
                    outputPath
                )

                LogCatcher.i("ApkBuilder", "执行: ${cmd.joinToString(" ")}")

                // 执行进程
                val process = Runtime.getRuntime().exec(cmd)
                
                // 读取标准输出
                Thread {
                    process.inputStream.bufferedReader().use { reader ->
                        reader.lineSequence().forEach { line ->
                            LogCatcher.i("ApkBuilder", "zipalign: $line")
                        }
                    }
                }.start()
                
                // 读取错误输出
                Thread {
                    process.errorStream.bufferedReader().use { reader ->
                        reader.lineSequence().forEach { line ->
                            LogCatcher.e("ApkBuilder", "zipalign err: $line")
                        }
                    }
                }.start()

                // 等待完成
                val exitCode = process.waitFor()
                
                // 检查是否成功
                val success = exitCode == 0 && File(outputPath).exists() && File(outputPath).length() > 0
                
                if (success) {
                    LogCatcher.i("ApkBuilder", "zipalign 对齐成功: $outputPath")
                } else {
                    LogCatcher.e("ApkBuilder", "zipalign 失败，exit=$exitCode，回退到简单对齐")
                    // 失败后回退到简单对齐
                    return simpleZipAlign(inputPath, outputPath)
                }

                success

            } catch (e: Exception) {
                LogCatcher.e("ApkBuilder", "zipalign 异常，回退到简单对齐", e)
                simpleZipAlign(inputPath, outputPath)
            }
        }

        // 简化的 zipalign 实现 - 作为后备方案
        private fun simpleZipAlign(inputPath: String, outputPath: String): Boolean {
            var success = false
            var inputZip: ZipFile? = null
            var fos: FileOutputStream? = null
            var zos: ZipOutputStream? = null

            try {
                inputZip = ZipFile(inputPath)
                fos = FileOutputStream(outputPath)
                zos = ZipOutputStream(fos)

                val entries = inputZip.entries().toList().sortedBy { it.name }

                for (entry in entries) {
                    val newEntry = ZipEntry(entry.name)
                    val data = inputZip.getInputStream(entry).use { it.readBytes() }

                    // 对于需要 STORED 的文件（如 resources.arsc），确保正确设置
                    if (entry.method == ZipEntry.STORED || entry.name == "resources.arsc") {
                        newEntry.method = ZipEntry.STORED
                        newEntry.size = data.size.toLong()
                        newEntry.compressedSize = data.size.toLong()
                        newEntry.crc = calculateCrc32(data)
                    } else {
                        newEntry.method = ZipEntry.DEFLATED
                    }

                    zos.putNextEntry(newEntry)
                    zos.write(data)
                    zos.closeEntry()
                }

                success = true
            } catch (e: Exception) {
                LogCatcher.e("ApkBuilder", "简单对齐APK失败", e)
                success = false
            } finally {
                try {
                    zos?.close()
                } catch (_: Exception) {
                }
                try {
                    fos?.close()
                } catch (_: Exception) {
                }
                try {
                    inputZip?.close()
                } catch (_: Exception) {
                }
            }

            return success
        }

        // ------------------ Java 编译相关函数 ------------------
        // 从 assets 提取 android.jar 到缓存
        private fun extractAndroidJarToCache(context: Context): String? {
            try {
                val cacheDir = File(context.cacheDir, "java_build")
                if (!cacheDir.exists()) cacheDir.mkdirs()

                val androidJar = File(cacheDir, "android.jar")
                // 与 core.apk 同理:缓存必须按内容失效。
                // 应用升级后 `assets/android.jar` 可能换了 API level,
                // 复用过期的 android.jar 会让 ECJ 按错误的平台签名编译,
                // 报出"方法不存在"这类与真实代码无关的错误。
                val assetLength = try {
                    context.assets.openFd("android.jar").use { it.length }
                } catch (_: Throwable) {
                    -1L
                }
                if (androidJar.exists() && androidJar.length() > 0 &&
                    (assetLength <= 0 || androidJar.length() == assetLength)
                ) {
                    return androidJar.absolutePath
                }

                context.assets.open("android.jar").use { input ->
                    FileOutputStream(androidJar).use { output ->
                        input.copyTo(output)
                    }
                }
                LogCatcher.i("ApkBuilder", "android.jar 已提取到: ${androidJar.absolutePath}")
                return androidJar.absolutePath
            } catch (e: Exception) {
                LogCatcher.e("ApkBuilder", "提取 android.jar 失败", e)
                return null
            }
        }

        // 编译 Java 文件并生成 DEX（使用 ECJ 和 D8）
        private fun compileJavaAndGenerateDexWithD8(
            context: Context,
            projectPath: String,
            workDir: File,
            androidJarPath: String,
            mavenJars: List<File> = emptyList(),
            templateClasses: Set<String> = emptySet(),
            buildSlimEnabled: Boolean = false,
            /**
             * dex 打包模式的载荷。非 null 时会额外生成一个承载载荷常量的
             * Java 源文件一起编译,结果 dex 里就带上了脚本(产物无 .lua 文件)。
             */
            dexPayload: ByteArray? = null,
            /** 目标 minSdk,传给 D8/R8 决定 desugaring 行为。 */
            minApi: Int = 21
        ) {
            // 1. 检查 java 目录
            val javaDir = File(projectPath, "java")
            val hasJavaSource = javaDir.exists() && javaDir.isDirectory && javaDir.walk().any { it.isFile && it.name.endsWith(".java", ignoreCase = true) }
            
            // 2. 构建 classpath（包含 android.jar 和 Maven JAR）
            val classpath = if (mavenJars.isNotEmpty()) {
                buildClasspathWithMavenJars(androidJarPath, mavenJars)
            } else {
                androidJarPath
            }
            
            // 3. 收集所有 Java 文件
            val javaFiles = mutableListOf<File>()
            if (hasJavaSource) {
                javaDir.walk().forEach { file ->
                    if (file.isFile && file.name.endsWith(".java", ignoreCase = true)) {
                        javaFiles.add(file)
                    }
                }
            }

            // 3.5 dex 打包模式:生成一个承载载荷字符串常量的 Java 源文件一起编译。
            //     这样脚本就是 dex 里的常量数据,产物 assets 里不再有 .lua。
            //     载荷类必须先于"没有 java 源文件就 return"的判断生成 ——
            //     否则纯 Lua 项目(没有 java/ 目录)在 dex 模式下会什么都不编译。
            if (dexPayload != null && dexPayload.isNotEmpty()) {
                val generatedDir = File(workDir, "payload_src")
                generatedDir.deleteRecursively()
                val pkgDir = File(
                    generatedDir,
                    LuaPayloadBuilder.DEX_PAYLOAD_CLASS.substringBeforeLast('/')
                )
                pkgDir.mkdirs()
                val srcFile = File(
                    pkgDir,
                    LuaPayloadBuilder.DEX_PAYLOAD_CLASS.substringAfterLast('/') + ".java"
                )
                // 载荷在写进 dex 之前必须加密:dex 里的字符串常量是
                // 最容易被 `strings` 直接读出来的位置,明文等于没打包。
                val encrypted = PayloadCrypto.encrypt(
                    dexPayload,
                    LuaPayloadBuilder.DEX_PAYLOAD_SEED,
                    LuaPayloadBuilder.DEX_PAYLOAD_SEGMENT
                )
                srcFile.writeText(
                    LuaPayloadBuilder.buildDexSource(encrypted),
                    Charsets.UTF_8
                )
                javaFiles.add(srcFile)
                LogCatcher.i(
                    "ApkBuilder",
                    "dex 打包模式:已生成载荷类源文件(${dexPayload.size} 字节载荷)"
                )
            }

            if (javaFiles.isEmpty()) {
                LogCatcher.i("ApkBuilder", "没有可编译的 Java 文件，跳过 Java 编译")
                return
            }

            LogCatcher.i("ApkBuilder", "开始编译 Java 文件，共 ${javaFiles.size} 个")
            LogCatcher.i("ApkBuilder", "编译 classpath: $classpath")

            // 5. 创建临时输出目录
            val classOutputDir = File(workDir, "java_build_classes")
            classOutputDir.mkdirs()

            // 6. 使用 ECJ 编译 Java 源文件
            val outputStream = ByteArrayOutputStream()
            val errorStream = ByteArrayOutputStream()
            val outWriter = PrintWriter(outputStream)
            val errWriter = PrintWriter(errorStream)
            val ecj = EcjMain(outWriter, errWriter, false, null, null)
            
            val args = mutableListOf<String>()
            args.add("-d")
            args.add(classOutputDir.absolutePath)
            args.add("-cp")
            args.add(classpath)
            args.add("-1.8")
            javaFiles.forEach { file -> args.add(file.absolutePath) }

            try {
                val success = ecj.compile(args.toTypedArray())
                outWriter.flush()
                errWriter.flush()
                val output = outputStream.toString()
                val error = errorStream.toString()
                
                if (!success) {
                    LogCatcher.e("ApkBuilder", "Java 编译失败，输出: $output, 错误: $error")
                    throw RuntimeException("Java 编译失败，返回码: $success\n$error")
                }
                LogCatcher.i("ApkBuilder", "Java 编译成功")
            } catch (e: Exception) {
                LogCatcher.e("ApkBuilder", "Java 编译异常", e)
                throw RuntimeException("Java 编译失败: ${e.message}", e)
            }

            // 7. 将编译后的 class 文件打包为 JAR
            val jarFile = File(workDir, "project_classes.jar")
            createJarFromDirectory(classOutputDir, jarFile)

            // 8. 收集所有需要转 DEX 的输入
            //
            // 只放项目自身的 java 编译产物:Maven JAR 已在 addMavenJarsToDexCompilation()
            // 里转成 dex 放进 workDir,这里再传一次会让同一批类落进两个 classes*.dex
            // —— 既白涨体积,又可能让运行期命中旧的一份。mavenJars 仅作 ECJ 的 classpath。
            val dexInputs = mutableListOf<File>()
            dexInputs.add(jarFile)

            // 9. 生成 DEX:统一走 BuildSlimmer(开关关闭时等价于原 D8 直转)
            LogCatcher.i("ApkBuilder", "开始生成项目 Java DEX,输入: ${dexInputs.size} 个")
            try {
                val dexOutputDir = File(workDir, "java_build_dex")
                dexOutputDir.mkdirs()

                val slim = BuildSlimmer.jarsToDex(
                    jars = dexInputs,
                    androidJar = androidJarPath,
                    templateClasses = templateClasses,
                    projectPath = projectPath,
                    outDir = dexOutputDir,
                    enabled = buildSlimEnabled,
                    minApi = minApi
                )
                LogCatcher.i(
                    "ApkBuilder",
                    "项目 Java DEX 完成: 精简=${slim.shrunk}, 剔除重复类=${slim.duplicateClasses.size}"
                )

                // 将生成的 dex 文件复制到 APK 工作目录
                addDexFilesToApkWorkDir(dexOutputDir, workDir)

                // 清理临时文件
                classOutputDir.deleteRecursively()
                jarFile.delete()
                dexOutputDir.deleteRecursively()
                
            } catch (e: Exception) {
                LogCatcher.e("ApkBuilder", "D8 DEX 生成失败", e)
                throw RuntimeException("D8 DEX 生成失败: ${e.message}", e)
            }
        }

        // 合并 assets/libs/*.dex 至 APK 根 classes*.dex
        // 将项目 libs/ 下的 dex 从 assets/libs 移出，按 workDir 根现有 classes*.dex
        // 最大索引续号重命名（classes.dex 索引 1，classes2.dex 索引 2...），源文件移除
        private fun mergeLibsDexToApkRoot(assetsDir: File, workDir: File) {
            val libsDir = File(assetsDir, "libs")
            if (!libsDir.exists() || !libsDir.isDirectory) return
            val dexFiles = libsDir.listFiles { _, name ->
                name.endsWith(".dex", ignoreCase = true)
            } ?: return
            if (dexFiles.isEmpty()) return

            // 计算根目录现有 classes*.dex 的最大索引（classes.dex 索引为 1）
            val existingDex = workDir.listFiles { _, name ->
                name.matches(Regex("classes(\\d*)\\.dex", RegexOption.IGNORE_CASE))
            } ?: emptyArray()
            val maxIndex = existingDex.mapNotNull { file ->
                val match = Regex("classes(\\d*)\\.dex", RegexOption.IGNORE_CASE).find(file.name)
                match?.groupValues?.get(1)?.toIntOrNull() ?: 1
            }.maxOrNull() ?: 0

            var nextIndex = maxIndex + 1
            for (dexFile in dexFiles.sortedBy { it.name }) {
                val targetName = if (nextIndex == 1) "classes.dex" else "classes${nextIndex}.dex"
                val targetFile = File(workDir, targetName)
                dexFile.copyTo(targetFile, overwrite = true)
                dexFile.delete()
                nextIndex++
            }
            LogCatcher.i("ApkBuilder", "已合并 ${dexFiles.size} 个 libs/*.dex 至 APK 根")
        }

        // 将目录中的所有 DEX 文件复制到目标目录，并自动重命名避免覆盖
        private fun addDexFilesToApkWorkDir(sourceDir: File, targetDir: File) {
            val dexFiles = sourceDir.listFiles { _, name -> name.endsWith(".dex", ignoreCase = true) } ?: return
            if (dexFiles.isEmpty()) return

            // 获取目标目录中现有的所有 classes*.dex
            val existingDex = targetDir.listFiles { _, name ->
                name.matches(Regex("classes(\\d*)\\.dex", RegexOption.IGNORE_CASE))
            } ?: emptyArray()

            // 计算现有最大的索引（classes.dex 索引为 1，classes2.dex 索引为 2...）
            val maxIndex = existingDex.mapNotNull { file ->
                val match = Regex("classes(\\d*)\\.dex", RegexOption.IGNORE_CASE).find(file.name)
                match?.groupValues?.get(1)?.toIntOrNull() ?: 1
            }.maxOrNull() ?: 1

            // 对每个新 dex 分配下一个可用索引
            var nextIndex = maxIndex + 1
            for (dexFile in dexFiles.sortedBy { it.name }) {
                val targetName = if (nextIndex == 1) "classes.dex" else "classes${nextIndex}.dex"
                val targetFile = File(targetDir, targetName)
                dexFile.copyTo(targetFile, overwrite = true)
                nextIndex++
            }
            LogCatcher.i("ApkBuilder", "已添加 ${dexFiles.size} 个 DEX 文件到 APK")
        }

        // 将目录打包为 JAR 文件
        private fun createJarFromDirectory(sourceDir: File, jarFile: File) {
            JarOutputStream(FileOutputStream(jarFile)).use { jos ->
                sourceDir.walk().forEach { file ->
                    if (file.isFile) {
                        val relativePath = file.relativeTo(sourceDir).path.replace('\\', '/')
                        val entry = JarEntry(relativePath)
                        jos.putNextEntry(entry)
                        file.inputStream().use { it.copyTo(jos) }
                        jos.closeEntry()
                    }
                }
            }
        }
        // ------------------ 结束 ------------------
    }
}