plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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
        compose = true
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    // Jetpack Compose:Lua 脚本 Compose 桥(compose 全局函数)。
    // 用 api 传递,使打包产物 core-apk 也带上 Compose 运行时。
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.material3)

    // 液态玻璃控件(LiquidGlassView)依赖 backdrop 效果的 Compose 实现。
    // 控件本体在 core(与 MaterialTextField 同层),故依赖一并声明在此;
    // 经 api 传递,app 与打包产物 core-apk 均可拿到。
    //
    // 版本必须锁 2.0.0-alpha01:自 2.0.0-rc01 起其 aar 的 minCompileSdk 升到 37,
    // 而本项目 compileSdk = 36,升级会直接构建失败。
    //
    // backdrop 的 Android 变体会传递依赖 org.jetbrains.compose.*:1.10.0
    // (映射到 androidx.compose.*:1.10.0),而本项目 Compose 锁 1.8.0-alpha08。
    // 不做排除时 Gradle 取高版本,会把整套 Compose/Material3 静默抬到 1.10.0,
    // 影响面远超本控件。此处排除其 Compose 传递依赖,统一用本项目已声明的版本。
    api("io.github.kyant0:backdrop-android:2.0.0-alpha01") {
        exclude(group = "org.jetbrains.compose.foundation")
        exclude(group = "org.jetbrains.compose.ui")
        exclude(group = "org.jetbrains.compose.animation")
        exclude(group = "org.jetbrains.compose.runtime")
        exclude(group = "org.jetbrains.compose.annotation-internal")
        exclude(group = "org.jetbrains.compose.collection-internal")
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
}