plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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