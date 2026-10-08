import java.net.URI
import java.security.MessageDigest
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.luaforge.studio"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.luaforge.studio"
        minSdk = 24
        targetSdk = 36
        versionCode = 6
        versionName = "1.6.4"

        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "BUILD_TIME", "\"${getBuildTime()}\"")
        buildConfigField("String", "COPYRIGHT_YEAR", "\"${getCurrentYear()}\"")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_ARM_MODE=arm",
                    "-DANDROID_ARM_NEON=TRUE",
                    "-DANDROID_TOOLCHAIN=clang",
                    "-DANDROID_LD=lld",
                    "-DANDROID=ON",
                    "-DCMAKE_BUILD_TYPE=${if (gradle.startParameter.taskNames.any { it.contains("Release") }) "Release" else "Debug"}"
                )

                val defaultBuildType =
                    if (gradle.startParameter.taskNames.any { it.contains("Release") }) "release" else "debug"
                arguments += listOf("-DANDROID_BUILD_TYPE=${defaultBuildType}")

                cFlags += listOf("-std=c99", "-pipe", "-fno-strict-aliasing")
                cppFlags += listOf("-std=c++11", "-pipe")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlin {
       compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    signingConfigs {
        getByName("debug") {
            keyAlias = "luaappxcore"
            keyPassword = "luaappxcore"
            storeFile = rootProject.file("debug.keystore")
            storePassword = "luaappxcore"
        }
        create("release") {
            keyAlias = "luaappxcore"
            keyPassword = "luaappxcore"
            storeFile = rootProject.file("debug.keystore")
            storePassword = "luaappxcore"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")

            externalNativeBuild {
                cmake {
                    arguments += listOf("-DANDROID_BUILD_TYPE=release")
                    cFlags += listOf("-DRELEASE_BUILD")
                    cppFlags += listOf("-DRELEASE_BUILD")
                }
            }
        }

        debug {
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
            isJniDebuggable = true

            externalNativeBuild {
                cmake {
                    arguments += listOf("-DANDROID_BUILD_TYPE=debug")
                    cFlags += listOf("-DDEBUG_BUILD")
                    cppFlags += listOf("-DDEBUG_BUILD")
                }
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    packaging {
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "DebugProbesKt.bin",
                "**/*.proto",
                "**/kotlin/**",
                "**/*.version",
                "**/androidsupportmultidexversion.txt"
            )
        }
        jniLibs {
            useLegacyPackaging = true
            excludes += listOf(
                "**/libc++_shared.so",
                "**/libc++_static.a",
                "**/*.a"
            )
        }
    }
}

// 工具函数
fun getBuildTime(): String = try {
    System.currentTimeMillis().toString()
} catch (e: Exception) {
    "1735651200000"
}

fun getCurrentYear(): String = LocalDate.now().year.toString()

// 复制 core.apk 到 assets
tasks.register<Copy>("copyCoreApkToAssets") {
    dependsOn(":core-apk:assembleRelease")

    from(project(":core-apk").layout.buildDirectory.file("outputs/apk/release/core-apk-release.apk"))
    into(layout.projectDirectory.dir("src/main/assets"))
    rename { "core.apk" }
}

tasks.named("preBuild") {
    dependsOn("copyCoreApkToAssets")
}

dependencies {
    api(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    // Module Dependencies
    api(project(":editor"))
    api(project(":core"))
    api(project(":signer"))

    // Compose Core
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.ui.tooling.preview)
    api(libs.compose.material3)
    api(libs.compose.material3.window.size)
    api(libs.compose.material.icons.extended)
    api(libs.compose.foundation)
    api(libs.compose.animation)
    api(libs.compose.animation.graphics)

    // AndroidX Core & Lifecycle
    api(libs.core.ktx)
    api(libs.core)
    api(libs.activity.compose)
    api(libs.lifecycle.runtime.ktx)
    api(libs.lifecycle.viewmodel.compose)

    // Navigation
    api(libs.navigation.compose)
    api(libs.navigation.common)
    api(libs.navigation.fragment)
    api(libs.navigation.runtime)
    api(libs.navigation.ui)

    // DataStore
    api(libs.datastore.preferences)

    // Material & Accompanist
    api(libs.material)
    api(libs.accompanist.permissions)

    // Third-party Libraries
    api(libs.compose.scrollbars) {
        exclude(group = "androidx.compose", module = "compose-bom")
    }
    api(libs.coil.compose)
    api(libs.gson)
    api(libs.ktoast)
    
    api("org.eclipse.jdt:ecj:3.33.0")
    api("com.android.tools:r8:8.2.42")
    api("io.github.kyant0:backdrop-android:2.0.0-alpha01")

}

// ============================================================================
// Python 运行时 vendoring(真 CPython,供 PythonUtil 使用)
//
// 运行时(解释器 + 标准库 + 扩展模块)不随源码分发,构建时从 Chaquopy 的
// Maven 制品拉取,并重排成运行时需要的布局:
//
//   app/build/python-runtime/jniLibs/arm64-v8a/*.so  -> nativeLibraryDir(按 soname 解析)
//   app/build/python-runtime/assets/python/*.zip     -> 安装后解包到 filesDir/python
//
// 为什么必须拆成两处(Android 硬约束,与 Chaquopy 同构):
//   1. libpython3.14.so 由桥用 soname dlopen,只有 nativeLibraryDir 能命中;
//   2. 扩展模块(_ssl 等)**没有 SONAME**,必须按绝对路径 dlopen,故走 assets 解包;
//      它们 NEEDED 的 libpython3.14.so / libssl_python.so 由 1 里已加载的库满足。
//   (Android 10+ 禁的是可写目录 execve,普通 .so 的 dlopen 不受限。)
//
// 校验:按 Maven Central 发布的 .sha1 逐件校验,不符即中止 —— 不静默使用损坏产物。
// 主源 Maven Central,失败回退阿里云 public。
// ============================================================================

val pythonRuntimeVersion = "3.14.0-0"
val pythonRuntimeAbi = "arm64-v8a"

/**
 * 是否启用运行时 vendoring。
 * 默认开启;`-PpythonRuntime=off` 可关闭(产物里无 Python,`pythonAvailable()` 恒 false)。
 */
val pythonRuntimeEnabled = (findProperty("pythonRuntime") as String?)?.lowercase() != "off"

/** 一件 Maven 产物及其官方 sha1。 */
data class PyArtifact(val name: String, val sha1: String)

// sha1 取自 Maven Central 同目录下的 .sha1(已本地核对)
val pythonArtifacts = listOf(
    PyArtifact(
        "target-$pythonRuntimeVersion-$pythonRuntimeAbi.zip",
        "4b7e24cc83c9b92b6a6b90113b6185a4f4eedafa"
    ),
    PyArtifact(
        "target-$pythonRuntimeVersion-stdlib.zip",
        "e8e17ceda5425e7e082ae84814726eff9bb0d60e"
    ),
)

val pythonMirrors = listOf(
    "https://repo1.maven.org/maven2/com/chaquo/python/target/$pythonRuntimeVersion",
    "https://maven.aliyun.com/repository/public/com/chaquo/python/target/$pythonRuntimeVersion",
)

val pythonRuntimeRoot = layout.buildDirectory.dir("python-runtime")

fun sha1Of(file: File): String {
    val md = MessageDigest.getInstance("SHA-1")
    file.inputStream().use { ins ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

val preparePythonRuntime = tasks.register("preparePythonRuntime") {
    description = "下载并重排 Chaquopy Python 运行时($pythonRuntimeAbi)"
    group = "python"

    val outDir = pythonRuntimeRoot
    // 下载缓存必须放在 outDir 之外:重排阶段会清空 outDir,若缓存在其中,
    // 已校验的产物会被自己删掉(表现为 NoSuchFileException)。
    val cacheOut = layout.buildDirectory.dir("python-runtime-downloads")
    val enabledFlag = pythonRuntimeEnabled
    val artifacts = pythonArtifacts
    val mirrors = pythonMirrors
    val version = pythonRuntimeVersion
    val abi = pythonRuntimeAbi

    inputs.property("pythonRuntimeEnabled", enabledFlag)
    inputs.property("pythonRuntimeVersion", version)
    artifacts.forEach { inputs.property("sha1.${it.name}", it.sha1) }
    outputs.dir(outDir)
    outputs.dir(cacheOut)

    doLast {
        val root = outDir.get().asFile
        if (!enabledFlag) {
            logger.lifecycle("[python-runtime] 已按 -PpythonRuntime=off 跳过 vendoring")
            File(root, "jniLibs").deleteRecursively()
            File(root, "assets").deleteRecursively()
            return@doLast
        }

        // 1) 取回产物(带缓存 + sha1 校验)
        val cacheDir = cacheOut.get().asFile
        cacheDir.mkdirs()
        val fetched = HashMap<String, File>()
        for (art in artifacts) {
            val dst = File(cacheDir, art.name)
            var ok = dst.isFile && sha1Of(dst) == art.sha1
            if (!ok) {
                dst.delete()
                for (base in mirrors) {
                    try {
                        logger.lifecycle("[python-runtime] 下载 $base/${art.name}")
                        URI("$base/${art.name}").toURL().openStream().use { ins ->
                            dst.outputStream().use { ins.copyTo(it) }
                        }
                        if (sha1Of(dst) == art.sha1) { ok = true; break }
                        logger.warn("[python-runtime] sha1 不符,丢弃: $base/${art.name}")
                        dst.delete()
                    } catch (e: Exception) {
                        logger.warn("[python-runtime] 下载失败(${e.javaClass.simpleName}): $base/${art.name}")
                        dst.delete()
                    }
                }
            }
            if (!ok) {
                throw GradleException(
                    "[python-runtime] 无法获取 ${art.name}(sha1 应为 ${art.sha1})。\n" +
                        "  请检查网络,或用 -PpythonRuntime=off 跳过(届时 App 内无 Python)。"
                )
            }
            fetched[art.name] = dst
        }

        // 2) 重排到目标布局(只清待生成的两个子树,勿动下载缓存)
        File(root, "jniLibs").deleteRecursively()
        File(root, "assets").deleteRecursively()
        val jniDir = File(root, "jniLibs/$abi")
        val assetDir = File(root, "assets/python")
        jniDir.mkdirs()
        assetDir.mkdirs()

        val targetZip = fetched.getValue("target-$version-$abi.zip")
        val stdlibZip = fetched.getValue("target-$version-stdlib.zip")
        val nativeLibs = ArrayList<String>()

        // 2a) 原生运行库 -> jniLibs/<abi>/(去掉 zip 内的 jniLibs/ 前缀)
        ZipFile(targetZip).use { zf ->
            zf.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("jniLibs/$abi/") && it.name.endsWith(".so") }
                .forEach { e ->
                    val name = e.name.substringAfterLast('/')
                    zf.getInputStream(e).use { ins ->
                        File(jniDir, name).outputStream().use { ins.copyTo(it) }
                    }
                    nativeLibs.add(name)
                }
        }
        require(nativeLibs.isNotEmpty()) { "[python-runtime] 未从 ${targetZip.name} 找到原生库" }
        nativeLibs.sort()

        // 2b) 扩展模块 -> assets/python/lib-dynload.zip(运行时解包后按绝对路径 dlopen)
        val dynloadTmp = File(root, "lib-dynload.zip")
        var dynloadCount = 0
        ZipOutputStream(dynloadTmp.outputStream().buffered()).use { out ->
            ZipFile(targetZip).use { zf ->
                zf.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("lib-dynload/$abi/") && it.name.endsWith(".so") }
                    .forEach { e ->
                        out.putNextEntry(ZipEntry(e.name.substringAfterLast('/')))
                        zf.getInputStream(e).use { it.copyTo(out) }
                        out.closeEntry()
                        dynloadCount++
                    }
            }
        }
        require(dynloadCount > 0) { "[python-runtime] 未从 ${targetZip.name} 找到扩展模块" }
        dynloadTmp.copyTo(File(assetDir, "lib-dynload.zip"), overwrite = true)
        dynloadTmp.delete()

        // 2c) 标准库(.py 原文,保留可读 traceback;.pyc 化留给体积优化阶段)
        stdlibZip.copyTo(File(assetDir, "stdlib.zip"), overwrite = true)

        // 3) 清单:供解包器/诊断核对
        File(assetDir, "BUILD.json").writeText(
            buildString {
                appendLine("{")
                appendLine("  \"chaquopyTarget\": \"$version\",")
                appendLine("  \"abi\": \"$abi\",")
                appendLine("  \"stdlibZip\": \"stdlib.zip\",")
                appendLine("  \"libDynloadZip\": \"lib-dynload.zip\",")
                appendLine("  \"nativeLibs\": [${nativeLibs.joinToString(", ") { "\"$it\"" }}],")
                appendLine("  \"libDynloadCount\": $dynloadCount")
                appendLine("}")
            }
        )

        logger.lifecycle(
            "[python-runtime] 就绪: 原生库 ${nativeLibs.size} 个, 扩展 $dynloadCount 个, " +
                "assets/python/{stdlib.zip,lib-dynload.zip,BUILD.json}"
        )
    }
}

// 把生成目录接入打包(assets 走 assets srcDir;jniLibs 走 jniLibs srcDir)
android {
    sourceSets.getByName("main") {
        jniLibs.srcDir(pythonRuntimeRoot.map { it.dir("jniLibs") })
        assets.srcDir(pythonRuntimeRoot.map { it.dir("assets") })
    }
}

tasks.named("preBuild") { dependsOn(preparePythonRuntime) }
