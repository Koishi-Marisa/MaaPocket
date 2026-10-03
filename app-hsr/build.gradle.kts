import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // 注意：**不要**加 alias(libs.plugins.kotlin.android) —— AGP 9 的内建 Kotlin 会和它冲突。
    // 版本由根 build.gradle.kts 的 `alias(libs.plugins.kotlin.jvm) apply false` 统一抬升。
    alias(libs.plugins.kotlin.compose)
}

val maapocketAbis: List<String> =
    (providers.gradleProperty("maapocket.abis").orNull ?: "arm64-v8a")
        .split(",").map { it.trim() }.filter { it.isNotEmpty() }

android {
    namespace = "com.maapocket.hsr"
    compileSdk = 37
    ndkVersion = "29.0.13113456"

    defaultConfig {
        applicationId = "com.maapocket.hsr"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += maapocketAbis }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    // 固定签名。CI 每次 run 都会重新生成 ~/.android/debug.keystore，用它签出的同包名新版
    // APK 装不上旧版（真机实测：INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package
    // com.maapocket.hsr signatures do not match newer version; ignoring!），用户每次升级
    // 都得先卸载。改用仓库内固定的 keystore。
    // 口令是公开的 —— 这个 key 只用于侧载分发，不是上架用的发布密钥；要上架请替换它，
    // 或用 GitHub Secrets 覆盖下面四个值。
    signingConfigs {
        create("maapocket") {
            storeFile = rootProject.file("keystore/maapocket.jks")
            storeType = "PKCS12"
            storePassword = "maapocket"
            keyAlias = "maapocket"
            keyPassword = "maapocket"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("maapocket")
        }
    }

    lint { abortOnError = false }
}

// 见 core/build.gradle.kts 里的说明：不用 jvmToolchain，只锁字节码目标。
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
}
