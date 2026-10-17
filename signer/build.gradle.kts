plugins {
    `java-library`
    `maven-publish`
    kotlin("jvm")
}

dependencies {
    implementation("com.madgag.spongycastle:core:1.58.0.0")
    implementation("com.madgag.spongycastle:prov:1.58.0.0")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["java"])
                groupId = "com.mcal"
                artifactId = "apksigner"
                version = "1.2.0"
            }
        }
    }
}

// com.mcal.apksigner 属于第三方内嵌源码:按约定不改动其内容。
// 该模块现存 5 条告警,全部是它调用了 sponge-castle 1.58 里已弃用的 API:
//   CertCreator.kt:6/120            X509V3CertificateGenerator
//   DistinguishedNameValues.kt:5/59/67  X509Principal
// 升级或替换加密库会改变签名实现,不是本工程该做的决定,故只在本模块关闭
// Kotlin 告警输出。作用域仅限该模块 —— 自有代码(app / core)的告警照常输出。
kotlin {
    compilerOptions {
        freeCompilerArgs.add("-nowarn")
    }
}
