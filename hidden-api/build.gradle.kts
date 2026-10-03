// 隐藏 API 的**编译期占位插件**。
//
// 为什么需要这个模块：`:core` 里的 `third/`（从 MAA-Meow 移植）直接引用
// `android.content.IContentProvider`、`android.content.pm.IPackageManager`、
// `android.os.IDeviceIdleController`、`com.android.internal.app.IAppOpsService`。
// 这四个类在设备上**存在**（就是 frameworks/base 里的 AIDL 生成物），但**不在公开的
// android.jar 里** —— 隐藏 API 在编译期被剥掉了。于是 `:core:compileReleaseJavaWithJavac`
// 报 `error: cannot find symbol / symbol: class IContentProvider / location: package android.content`
// （CI run 37126572358）。
//
// 解法与 MAA-Meow 完全一致：单独一个 Android library，里面放最小签名的手写 stub，
// 上层用 `compileOnly(project(":hidden-api"))` 引用。compileOnly 保证这些类是
// **纯编译期**的：不进 APK、不进 runtime classpath，运行时一律走设备上的真实现。
//
// 注意：这里的签名必须与真机上的 AIDL 版本**完全一致**（参数类型 + 个数），
// 否则 `Binder` 的 transaction code 会对不上，反射调用会静默返回 null。
plugins {
    alias(libs.plugins.android.library)
}

android {
    // 与 :core 保持一致；stub 里的 `AttributionSource` 需要 API 31+ 才会解析。
    compileSdk = 37

    namespace = "com.maapocket.hidden_api"

    defaultConfig {
        // 与 :core 的 minSdk 对齐，这样 compileOnly 过来的类型不会被 lint 判成新 API。
        minSdk = 28
        // 与 :core 同理：AGP 9 的 library 模块 defaultConfig 里没有 targetSdk。
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // 这些类**故意**与 android.* 的包名相同，lint 会告 InvalidPackage / 隐藏 API 警告。
        abortOnError = false
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
