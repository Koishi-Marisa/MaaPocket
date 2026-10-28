# Repository Guidelines

## 项目结构与模块组织

本项目为单模块 Android 应用（`app`），内嵌 MaaFramework，并通过 Jetpack Compose 提供用于在 Android 上自动化运行火影忍者的 UI。

- `app/src/main/java/com/maafw/naruto/` — Kotlin/Java 源码，按功能组织：`ui/`（Compose 界面）、`remote/`（Shizuku/Root 引擎服务）、`maa/`（JNA 绑定）、`capture/`、`inject/`、`service/`、`schedule/`、`data/`、`third/`。
- `app/src/main/aidl/` — 远程引擎、logcat、状态监听的 AIDL 接口。
- `app/src/main/native/` — C/C++ 桥接层（`bridge.cpp`、`launcher.c`），通过 CMake 构建。
- `app/src/main/cpp/include/` — MaaFramework/C SDK 头文件。
- `app/src/main/assets/` — 游戏资源（`interface.json`、`resource/`），由脚本同步。
- `app/src/main/jniLibs/arm64-v8a/` — 预编译原生库（`.so`）。
- `scripts/` — 资源同步与 MaaFramework 部署相关 Python 工具。
- 根目录 `README.md` 及中文 `*.md` 文档涵盖构建与集成细节。

## 构建、测试与开发命令

需要 JDK 17+、Android SDK（build-tools 34.0.0），并设置 `ANDROID_HOME`。

```bash
# 构建 debug APK（产物：app/build/outputs/apk/debug/app-debug.apk）
./gradlew :app:assembleDebug

# 若内置 aapt2 解析失败，指定 SDK 自带 aapt2
./gradlew :app:assembleDebug -Pandroid.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/34.0.0/aapt2

# 重新部署 MaaFramework 预编译库与头文件
python scripts/setup_maaframework.py --tag v5.12.3

# 构建前同步火影业务资源
python scripts/update_narutomobile_assets.py
```

## GitHub 仓库信息查询

- 需要查看 GitHub 仓库情况（如 release、action、commit、issue）时，优先使用 `gh` CLI（如 `gh release list`、`gh run list`、`gh issue list`、`gh api` 等），避免直接抓取网页。
- 若本机未安装 `gh`，先询问用户是否要安装，取得同意后再安装使用。

## 版本号约定

应用版本定义在 `app/build.gradle` 的 `android.defaultConfig` 中：

- `versionCode`：递增的整数版本序列号，每次发布必须比上一次更大。
- `versionName`：面向用户的版本号，遵循 `major.minor`（或 `major.minor.patch`）语义化格式。

约定：

- 每次发布/打版本时同步更新 `versionCode` 与 `versionName`，二者放入同一次提交或 PR 中。
- 功能或破坏性变更提升 `versionName` 的主/次版本号；仅修复或小改动可递增 patch 版本号。
- `versionCode` 只增不减，不得复用或回退到历史值。
- 版本号变更应独立、可审查，建议提交信息带 `build` 或 `chore` 作用域，例如 `build(app): 升版本号到 1.1 (69)`。

## 编码风格与命名约定

- Kotlin 遵循标准 Android 约定：4 空格缩进，函数/变量使用 `camelCase`，类使用 `PascalCase`，常量使用 `UPPER_SNAKE_CASE`。
- Compose UI 位于 `ui/` 下，每个文件对应一个界面或组件集合，包根为 `com.maafw.naruto`。
- 未配置专门的 lint/格式化工具；保持与周围代码一致，非必要不新增构建依赖。

## 测试指南

当前仓库暂无单元测试或仪器测试。若后续新增，建议使用 JUnit + AndroidX 测试栈，放在 `app/src/test/` 或 `app/src/androidTest/`，并通过 `./gradlew :app:testDebugUnitTest` 运行。引擎、截屏和输入注入相关改动必须在真机手动验证。

## 提交与 Pull Request 规范

提交信息使用 Conventional Commits 风格，并采用中文摘要，例如 `feat(log): 添加连接环境诊断快照`、`fix(engine): 修复 Root 和 Shizuku 引擎连接`。

- 使用带作用域前缀：`feat`、`fix`、`build`、`ci`、`docs`、`refactor`、`test`。
- CI 在每次 push/PR 时运行 `android-build.yml`，PR 必须构建成功。
- 在 PR 描述中说明改动动机与行为变化，关联相关 issue；UI 改动附截图。
- 不要提交生成产物（`cache/`、`build/`、同步后的游戏资源），除非有意更新内置示例资源。

## 引擎集成红线

以下 MaaFramework 集成规则不可破坏（详见 `滑动卡死问题修复报告.md`）：

- 不要手动将历史 `libbridge.so`/`liblauncher.so` 放入 `jniLibs`，交由 CMake 构建。
- 不要注册 `MaaTaskerAddContextSink`（focus 监听），会导致引擎崩溃。
- 不要使用 MaaFramework Swipe 多触点数组，应改用 `NonlinearSwipe`/`MultiSwipeCustom`。
- 避免在 `CustomRecognition` 中嵌套 OCR，避免在 `CustomAction` 中访问 image。

## 协作与提交规则

- 每完成一部分阶段性工作，向用户询问是否进行一次 commit 提交，保持提交粒度适中、可独立理解，但又禁止自行提交。
- 除非用户明确要求推送，否则禁止自行执行 `git push`。
- 拒绝一切强行推送（force push）行为；如出现文件冲突，应在本地先解决后再提交。
- 若本地与远程 Git 树根不一致，应先 clone 远程仓库，再把本地部署工作迁移到新仓库中，不得盲目用远程代码覆盖本地内容。
