// AGP 9 起 `com.android.application` / `com.android.library` 自带内建 Kotlin 支持，
// 独立 apply `org.jetbrains.kotlin.android` 会直接失败：
//   ⛔ Failed to apply plugin 'org.jetbrains.kotlin.android'
//   Solution: Remove the 'org.jetbrains.kotlin.android' plugin from this project's build file
// 所以这里**不再**在根上声明 kotlin-android。
//
// 但内建 Kotlin 默认会用 AGP 自带的那份 KGP（通常比我们想用的旧），要把它抬到
// `libs.versions.toml` 里 `kotlin` 指定的版本，办法是：在根项目的 plugins 块里用
// `apply false` 声明任意一个**同为 KGP 家族**的插件（这里用 kotlin-jvm），
// 于是 KGP 会被拉到共享 build classpath 上，内建 Kotlin 就会复用它而不是自带的。
// 这套做法抄自 MAA-Meow 的根 build.gradle.kts（同样的注释、同样的理由）。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
