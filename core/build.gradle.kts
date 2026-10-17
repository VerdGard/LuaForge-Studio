// 是否把 Compose 桥(方案 A)编进 core —— 决定用户 APK 是否背上 Compose 运行时。
//
// - true (默认):LuaCompose.kt 随 core 编译,core.apk 内含 Compose,项目开箱可用 compose。
// - false:整体排除 LuaCompose.kt 与 Compose 依赖,产物**零 Compose 体积**;
//          此时 Lua 侧只能用项目自带的 libs/compose_bridge.dex(方案 C)。
//
// 用法:`./gradlew -PlfComposeBundled=false ...`,或在 gradle.properties 里改。
val lfComposeBundled: Boolean =
    (project.findProperty("lfComposeBundled") as String?)?.toBoolean() ?: true

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // Lua 侧 Compose 桥(方案 A)需要 Compose 编译器插件。
    // 不启用它,@Composable 不会被改写签名,LuaCompose.kt 无法通过编译。
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.luaforge.studio.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 23

        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            isShrinkResources = false

            ndk {
                abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
            }
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false

            ndk {
                abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
            }
        }
    }

    buildFeatures {
        buildConfig = true
        // 方案 A:Compose 桥随 core 编译进 core-apk,用户 APK 可直接用 compose。
        // 关掉 lfComposeBundled 时同时排掉 LuaCompose.kt,编译期不需要 Compose 编译器。
        compose = lfComposeBundled
    }

    // 方案 A 的桥单独放在 `src/compose/kotlin` 下,只有 lfComposeBundled=true 才把它
    // 加进 main 的源码目录。
    //
    // 为什么不用 `kotlin.exclude("...")`:AGP 的 Kotlin 源码集对 exclude() 的支持依赖
    // 具体插件版本,不同版本下可能**静默不生效**,结果就是"以为排掉了 Compose,
    // 实际产物里仍然有",而那时 buildFeatures.compose=false 又会让 LuaCompose.kt
    // 编不过 —— 变成构建期随机失败。条件 srcDir 是 AGP 的稳定契约,不会有这个歧义。
    if (lfComposeBundled) {
        sourceSets.getByName("main").kotlin.srcDir("src/compose/kotlin")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "DebugProbesKt.bin"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// 只产出 release 变体:core 是打包产物(core-apk)的唯一依赖,统一按 release 构建。
// 依赖本模块的 debug 变体需经 matchingFallbacks 回退(app/build.gradle.kts)。
androidComponents {
    beforeVariants(selector().withBuildType("debug")) { it.enable = false }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    // Jetpack Compose:Lua 脚本 Compose 桥(compose / composeContent / state ...)。
    // 用 api 传递,使打包产物 core-apk 一并带上 Compose 运行时 —— 否则用户 APK
    // 里 Lua 调 compose 时会 NoClassDefFoundError。
    //
    // lfComposeBundled=false 时整块跳过:该发行版依赖项目自备的
    // libs/compose_bridge.dex(方案 C),core 本身不带任何 Compose 类。
    if (lfComposeBundled) {
        api(libs.compose.ui)
        api(libs.compose.foundation)
        api(libs.compose.material3)
        api(libs.compose.runtime)
    }

    // Navigation
    api(libs.navigation.fragment)
    api(libs.navigation.ui)

    // Material Design
    api(libs.material)

    // AndroidX Misc
    api(libs.activity)
    api(libs.appcompat)
    api(libs.annotation)
    api(libs.collection)
    api(libs.constraintlayout)
    api(libs.coordinatorlayout)
    api(libs.customview)
    api(libs.documentfile)
    api(libs.drawerlayout)
    api(libs.dynamicanimation)
    api(libs.fragment)
    api(libs.gridlayout)
    api(libs.legacy.support.core.ui)
    api(libs.legacy.support.core.utils)
    api(libs.localbroadcastmanager)
    api(libs.palette)
    api(libs.preference)
    api(libs.startup.runtime)
    api(libs.swiperefreshlayout)
    api(libs.slidingpanelayout)
    api(libs.recyclerview)
    api(libs.transition)
    api(libs.window)
    api(libs.viewpager2)
    api(libs.cardview)
    api(libs.browser)

    // Networking & Parsing
    api(libs.gson)

    // Image Loading (Glide)
    api(libs.glide)
    api(libs.okhttp3.integration)

    // HTTP Client (OkHttp)
    api(libs.okhttp)

    // Video Playback (Media3 / ExoPlayer)
    api(libs.media3.exoplayer)
    api(libs.media3.ui)
    api(libs.media3.common)
    // 自适应流与协议扩展:HLS(m3u8)/DASH(mpd)/SmoothStreaming(ism)/RTSP/RTMP。
    // 这些模块不会自动被 exoplayer 拉取,必须显式声明;缺了对应模块时
    // DefaultMediaSourceFactory 会在运行时抛 ClassNotFoundException。
    api(libs.media3.exoplayer.hls)
    api(libs.media3.exoplayer.dash)
    api(libs.media3.exoplayer.smoothstreaming)
    api(libs.media3.exoplayer.rtsp)
    api(libs.media3.datasource.rtmp)
    api(libs.rtmp.client)
}