plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

object Versions {
    const val versionName = "1.0.0"
}

group = "io.github.Rosemoe.sora-editor"

version = Versions.versionName

android {
    namespace = "io.github.rosemoe.sora"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
}

dependencies {

    // Material
    api(libs.material)

    // AndroidX
    api(libs.androidx.annotation)

    api(libs.collection)

    // Kotlin
    api(libs.kotlin.stdlib)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)

    // Android Testing
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

// io.github.rosemoe.sora(sora-editor) 属于第三方内嵌源码:按约定不改动其内容。
// 该模块现存 2 条告警,均为其自身写法所致:
//   LineStyles.kt:76   泛型 unchecked cast(LineAnchorStyle -> T)
//   RenderNodeHolder.kt:79  对非空类型的冗余 `!!`
// 改源码会让后续同步上游变成手工合并,因此只在本模块关闭 Kotlin 告警输出。
// 作用域仅限该模块 —— 自有代码(app / core)的告警照常输出。
kotlin {
    compilerOptions {
        freeCompilerArgs.add("-nowarn")
    }
}
