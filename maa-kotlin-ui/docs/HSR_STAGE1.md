# 阶段 1：崩铁每日奖励 + 原 PC 资源直用

状态：已把原 PC 项目（March7thAssistant 迁移包）转换成 MaaKotlinUI 的资源布局并直接使用；
虚拟屏默认已改为 **1080p（1920×1080）**，与 PC 模板 1:1。

## 1. 这一阶段交付了什么

| 位置 | 内容 |
|---|---|
| `app/build.gradle` | applicationId 改为 `com.maapocket.hsr`，应用名 MaaPocket，versionCode 70 / versionName 1.2 |
| `app/src/main/assets/interface.json` | 原 PC 项目的 32 个任务 + 手机端入口「崩铁·领取每日奖励」，共 33 个 |
| `app/src/main/assets/resource/base/pipeline/**` | PC 迁移包的全部 pipeline（screen 57 文件 / task 32 文件）+ Startup.json + 手机端领取链 |
| `app/src/main/assets/resource/base/image/**` | PC 项目的 396 张模板，原样使用，不重采样 |
| `app/src/main/assets/resource/base/locale/zh_cn.json` | 界面文案 |
| `app/src/main/assets/resource/base/adaptation/template_sets.json` | 基线 1920×1080；暂时没有手机端模板集 |
| `scripts/sync_pc_hsr_pack.py` | PC 资源 → MaaKotlinUI 布局的可重复转换脚本 |
| `app/src/main/java/com/maafw/naruto/maa/adaptation/` | 分辨率换算 / 模板集选择 / 诊断日志 |
| `scripts/auto_capture_templates.py` | 可选：adb 自动拉图 + 多尺度定位 + 裁剪手机端模板 |
| `scripts/check_stage1_pack.py` | 静态校验（当前 0 error） |

## 2. PC 资源转换

```bash
python scripts/sync_pc_hsr_pack.py
```

做的事：

```
app-hsr/.../pi/resource/pipeline/  ->  maa-kotlin-ui/.../assets/resource/base/pipeline/
app-hsr/.../pi/resource/image/     ->  .../resource/base/image/
app-hsr/.../pi/resource/locale/    ->  .../resource/base/locale/
interface.json                     ->  重写 controller/resource/display，追加手机端任务
```

刻意不做：

- **不重采样模板**。PC 模板就是 1920×1080 像素裁剪，虚拟屏必须 1080p；
- 不复制 `resource_bilibili` 覆盖层（单资源包下会把 B服登录分支强加给国服）；
- 不改 PC pipeline 的节点语义（`ClickKey`/`DoNothing` 占位等原样保留）。

校验结果（`python scripts/check_stage1_pack.py`）：

```
interface.json：33 个任务
pipeline：556 个节点，引用完整
模板引用：231 个，已采集 231 个
0 error
```

191 个模板短边 < 64px 是 PC 资产本身的尺寸，属预期；手机端重采是阶段 2 的事。

## 3. 构建与发布

推送到 `maa-kotlin-ui/**` 后，GitHub Actions 会：

1. 拉 MaaFramework v5.14.3 原生库；
2. 构建 release APK，用仓库内固定 keystore 签名；
3. 上传 artifact，并覆盖发布到
   [Release v0.1.0-stage1](https://github.com/Koishi-Marisa/MaaPocket/releases/tag/v0.1.0-stage1)。

本地构建：

```bash
python scripts/setup_maaframework.py --tag v5.14.3
chmod +x gradlew
bash ./gradlew :app:assembleRelease \
  -Pandroid.aapt2FromMavenOverride="$ANDROID_HOME/build-tools/34.0.0/aapt2"
```

## 4. 真机验证

1. 安装 APK（包名 `com.maapocket.hsr`，会覆盖旧的 MaaPocket 崩铁包）；
2. 设置页确认「虚拟屏分辨率」是 **1080p**（现在也是默认值）；
3. 手动把游戏停在邮箱界面；
4. 脚本页默认只勾选「崩铁·领取每日奖励（PC 模板）」→ 启动任务。
   （33 个 PC 任务仍可选，但默认全部不勾，避免 B服 登录/OCR 节点让首跑卡很久。）

预期日志：

```
虚拟屏分辨率：1920x1080@320dpi
适配层：1920x1080@320dpi (scale=1.000/1.000, set=1080p)，模板节点=0，roi缩放=0
开始任务 HSR_Daily_ClaimRewards
任务 HSR_Daily_ClaimRewards 结束，状态 3000
```

节点事件在引擎日志：

```
/storage/emulated/0/Android/data/com.maapocket.hsr/files/maa_logs/maafw.log
```

验收标准：EnterMailbox 识别成功 → receive_all 点击生效 → 2 秒后 click_close 关闭弹窗 →
BACK 退出，状态 3000。

失败排查顺序：

1. 虚拟屏是不是 1080p（720p 下 PC 模板必然不匹配）；
2. 模板是不是被 `AssetResourceDeployer` 部署到了 userDir（看「资源部署完成」日志）；
3. `roi` 是否超出 1920×1080；
4. 最后才考虑调 threshold。

## 5. 已知边界

- PC 模板在手机客户端的 UI 缩放/DPI 下**不保证**匹配，这正是阶段 2 要重采的原因；
- PC pipeline 里有 51 个 `ClickKey`（ESC/P/M/F3）和 20 个 OCR 占位 `DoNothing`，
  手机上这些节点语义仍不完整；
- 手机端领取任务要求「已经在邮箱界面」；主界面→菜单→邮箱的导航仍用 PC 的
  `NavTo_mail`（单跳，从 menu 可用，从 main 会诚实失败）；
- **B服已支持**：App 会自动识别本机装的是 `com.miHoYo.hkrpg` 还是
  `com.miHoYo.hkrpg.bilibili`；也可以在任务的 `Server` 选项里显式选择。
  解析结果会写入引擎共享配置，并覆盖 `StartUpGame` / `CloseGame` 的 `package`。
  但 B服 的登录 / 协议页仍可能需要手动处理（`resource_bilibili` 覆盖层没有导入）；
- 「任务结束后关闭游戏」也已改为关闭解析出的包名（国服 / B服），不再写死。

## 6. 可选：手机端模板重采（阶段 2）

PC 模板直用只是先跑通链路。要提成功率，按下面流程把模板替换成手机原生渲染：

```bash
# 手机连 adb：自动拉最新虚拟屏截图
python scripts/auto_capture_templates.py \
  --spec packs/hsr/stage1/mail_1080p.json --dry-run

# 确认分数后正式写入，再重新构建
python scripts/auto_capture_templates.py --spec packs/hsr/stage1/mail_1080p.json
```

- spec 默认 `source: adb:latest`；截图目录是
  `/storage/emulated/0/Android/data/com.maapocket.hsr/files/screenshots`；
- RGBA 参考图走 `TM_SQDIFF_NORMED + mask`，不透明图走 `TM_CCOEFF_NORMED`；
- 自动匹配失败的元素退回 `capture_templates.py` 手工 rect（网格辅助）。

## 7. 引擎红线（沿用 MaaKotlinUI）

- 不注册 `MaaTaskerAddContextSink`；
- 不用原生 Swipe 多途径点数组；
- 不在 `CustomRecognition` 里嵌套 OCR，不在 `CustomAction` 里取 image；
- 识别/截图/注入留在引擎进程，App 只做 UI 与编排。
