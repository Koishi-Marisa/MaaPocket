import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    // 注意：**不要**加 alias(libs.plugins.kotlin.android) —— AGP 9 的内建 Kotlin 会和它冲突。
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val maapocketAbis: List<String> =
    (providers.gradleProperty("maapocket.abis").orNull ?: "arm64-v8a")
        .split(",").map { it.trim() }.filter { it.isNotEmpty() }

android {
    namespace = "com.maapocket.core"
    compileSdk = 37
    ndkVersion = "29.0.13113456"

    defaultConfig {
        minSdk = 28
        targetSdk = 36

        ndk {
            abiFilters += maapocketAbis
        }

        externalNativeBuild {
            cmake {
                // MaaFramework 的 .so 与我们的 external lib 共用一份 libc++_shared
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // 承重：liblauncher.so 必须以真实文件落在 nativeLibraryDir 上，
            // dlopen 与「当可执行文件跑」都依赖它，不能压进 APK 的 zip 里。
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
    }
}

// 不用 kotlin { jvmToolchain(17) }：本仓 settings.gradle.kts 没有装 foojay-resolver，
// 显式声明 toolchain 会让 Gradle 去下载 JDK 17，CI 上直接失败。
// 改成「用跑 Gradle 的那个 JDK 编译、字节码目标锁 17」，与 compileOptions 保持一致。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    // aar 里带 libjnidispatch.so，用 jar 会在设备上找不到 native 分发库
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.libsu)

    implementation(libs.timber)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
