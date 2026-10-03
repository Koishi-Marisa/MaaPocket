import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // 注意：**不要**加 alias(libs.plugins.kotlin.android) —— AGP 9 的内建 Kotlin 会和它冲突。
    alias(libs.plugins.kotlin.compose)
}

val maapocketAbis: List<String> =
    (providers.gradleProperty("maapocket.abis").orNull ?: "arm64-v8a")
        .split(",").map { it.trim() }.filter { it.isNotEmpty() }

android {
    namespace = "com.maapocket.zzz"
    compileSdk = 37
    ndkVersion = "29.0.13113456"

    defaultConfig {
        applicationId = "com.maapocket.zzz"
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

    // 见 app-hsr/build.gradle.kts 的说明：固定签名，否则同包名的新版 APK 装不上旧版。
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
