pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven { url = uri("https://jitpack.io") }
        mavenCentral()
    }
}

rootProject.name = "MaaPocket"

// 隐藏 API 的**编译期占位符**（`android.content.IContentProvider` 之类）。
// 只被 `compileOnly` 引用，绝不参与打包 —— 这些类在设备上是系统提供的。
// 少了它 `:core` 的 javac 会报 `cannot find symbol: class IContentProvider`。
include(":hidden-api")
// 共享的 Android 宿主库：特权进程 + native external lib + MaaFramework 绑定 + PI 资源装载 + UI
include(":core")
// 每个游戏一个 APK；三者共享 :core，只有 PI 资源包与包名/图标不同
include(":app-hsr")
include(":app-zzz")
include(":app-endfield")
