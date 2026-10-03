plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val maapocketAbis: List<String> =
    (providers.gradleProperty("maapocket.abis").orNull ?: "arm64-v8a")
        .split(",").map { it.trim() }.filter { it.isNotEmpty() }

android {
    namespace = "com.maapocket.endfield"
    compileSdk = 37
    ndkVersion = "29.0.13113456"

    defaultConfig {
        applicationId = "com.maapocket.endfield"
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
        jniLibs {
            // MaaEnd 的 agent（go-service / cpp-algo）以单文件 ELF 形式随包，
            // 同样必须落成真实文件才能 exec。
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    lint { abortOnError = false }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
}
