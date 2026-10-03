plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
