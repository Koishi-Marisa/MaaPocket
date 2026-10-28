# 阶段 1：崩铁「领取每日奖励」最小闭环

状态：代码与资源骨架已就绪；**模板需要在真机上采集**（本文第 3 节）。

## 1. 这一阶段交付了什么

| 位置 | 内容 |
|---|---|
| `app/src/main/assets/interface.json` | 只声明一个任务：`崩铁·领取每日奖励` → `HSR_Daily_ClaimRewards` |
| `app/src/main/assets/resource/base/pipeline/hsr/daily/claim_rewards.json` | 8 个节点：Enter → Verify → Click → Wait → Close → Exit |
| `app/src/main/assets/resource/base/pipeline/Startup.json` | 预启动包名 `com.miHoYo.hkrpg`（MaaKotlinUI 的 `prelaunchGame` 读 `start_up.package`） |
| `app/src/main/java/com/maafw/naruto/maa/adaptation/` | ScreenProfile / RoiScaler / TemplateSelector / PipelineAdaptation |
| `scripts/capture_templates.py` | 从虚拟屏无损截图裁剪模板、生成多分辨率清单、可选 OpenCV 自检 |
| `scripts/check_stage1_pack.py` | 不连真机也能跑的资源包静态校验 |
| `packs/hsr/stage1/capture_spec.example.json` | 裁剪 spec 骨架（`rect` 需要你填） |

## 2. 对齐原 PC 项目（March7thAssistant）

原实现 `tasks/reward/mail.py`：

```python
if auto.click_element("./assets/images/zh_CN/reward/mail/receive_all.png", "image", 0.8):
    time.sleep(2)
    Base.send_notification_with_screenshot("邮件奖励已领取", level=NotificationLevel.ALL)
    auto.click_element("./assets/images/zh_CN/base/click_close.png", "image", 0.8, max_retries=10)
```

本阶段的节点一一对应：

| 原 PC | 本资源包 | 说明 |
|---|---|---|
| `screen.change_to('mail')` 前置 | `HSR_Daily_EnterMailbox` | 阶段 1 只校验「已经在邮箱」，导航留给阶段 2 |
| `click_element(receive_all.png, image, 0.8)` | `HSR_Daily_ClickReceiveAll` | threshold 0.8，`Click target=true` |
| `time.sleep(2)` | `HSR_Daily_WaitAfterReceive.post_delay = 2000` | |
| `click_element(click_close.png, 0.8, max_retries=10)` | `HSR_Daily_CloseRewardPopup` | `max_hit = 10`；弹窗没出现时走 `HSR_Daily_Exit`，不判失败 |
| 任务收尾 | `HSR_Daily_Exit` | Android `KEYCODE_BACK(4)` |

模板来源元素（不是直接复用 PC 图，而是「知道该去手机截图的哪个元素」）。
原 PC 项目在本工作区的镜像位于 `../app-hsr/src/main/assets/pi/resource/image/`：

| 本资源包模板 | 原 PC 项目图片 | 工作区参考路径 | 原图尺寸 |
|---|---|---|---|
| `hsr/daily_claim/mail_anchor` | `screen/mail.png` | `app-hsr/.../image/screen/mail.png` | 125×35 |
| `hsr/daily_claim/receive_all` | `zh_CN/reward/mail/receive_all.png` | `app-hsr/.../image/zh_CN/reward/mail/receive_all.png` | 111×32 |
| `hsr/daily_claim/click_close` | `zh_CN/base/click_close.png` | `app-hsr/.../image/zh_CN/base/click_close.png` | 209×32 |

PC 原图只有 32~37px 高，**不满足 64px 模板规范**，只能当作「要裁哪个 UI 元素」的参考；
手机端请连按钮边框/图标一起裁大，并换用手机客户端自己的渲染。

## 3. 手机端模板采集流程

### 3.1 准备

```bash
cd maa-kotlin-ui
python scripts/setup_maaframework.py --tag v5.12.3   # 首次：拉 libMaaFramework.so + 头文件
./gradlew :app:assembleDebug
```

安装 APK → Shizuku 授权 → 设置页把虚拟屏分辨率选成 **720p**（阶段 1 基线 1280×720）。

### 3.2 截虚拟屏无损图

1. 启动游戏，手动导航到**邮箱界面**；
2. 点右上角 `⋮` → **截图**（MaaKotlinUI 内置，`captureFramePng` 存 PNG）；
3. 文件在：

```
/storage/emulated/0/Android/data/com.maafw.naruto/files/screenshots/screenshot_*.png
```

分辨率等于虚拟屏（720p 下是 1280×720），这就是模板的唯一合法来源。

### 3.3 量坐标

```bash
pip install pillow
python scripts/capture_templates.py --grid shots/mail_720p.png
# 输出 shots/mail_720p_grid.png，红竖线=x，绿横线=y，步长 100px
```

把 `packs/hsr/stage1/capture_spec.example.json` 复制成 `mail_720p.json`，
把 `rect` 填成 `[x, y, w, h]`（截图自身像素坐标）。

### 3.4 裁剪 + 自检

```bash
python scripts/capture_templates.py --spec packs/hsr/stage1/mail_720p.json --check
```

产物：

```
app/src/main/assets/resource/base/image/hsr/daily_claim/
├── mail_anchor.png          # 720p 规范路径（基线副本，适配层失效也能用）
├── receive_all.png
├── click_close.png
└── 720p/
    ├── mail_anchor.png
    ├── receive_all.png
    └── click_close.png
app/src/main/assets/resource/base/adaptation/template_sets.json   # 节点 -> 各分辨率模板
```

`--check` 用的是 `TM_CCOEFF_NORMED`：同图应 ≥ 0.90；再用别的界面截图跑一次应 ≤ 0.60。
达不到就换一块更有区分度的 UI 元素，而不是先降 threshold。

### 3.5 静态校验

```bash
python scripts/check_stage1_pack.py
```

必须 `0 error`。模板没采完时会有 `WARN`，属于预期。

## 4. 真机验收

1. 设置页选 720p → 启动游戏 → 停在邮箱界面；
2. 脚本页勾选「崩铁·领取每日奖励」→ 开始任务；
3. 日志里应依次出现：

```
适配层：1280x720@160dpi ... 模板节点=3，roi缩放=0
  适配 HSR_Daily_EnterMailbox TemplateMatch template=hsr/daily_claim/720p/mail_anchor.png threshold=0.80 roi=[0,0,0,0]
开始任务 HSR_Daily_ClaimRewards
任务 HSR_Daily_ClaimRewards 结束，状态 3000
```

Node / Recognition / Action 级事件在引擎日志里看（App 侧**不**注册 `MaaTaskerAddContextSink`，
这是 MaaKotlinUI 的崩溃红线）：

```
/storage/emulated/0/Android/data/com.maafw.naruto/files/maa_logs/maafw.log
```

验收标准（与原提示词一致）：

- [ ] 任务能启动，不卡死；
- [ ] `HSR_Daily_EnterMailbox` 识别成功；
- [ ] `HSR_Daily_ClickReceiveAll` 点击生效（邮件奖励真的被领走）；
- [ ] 奖励弹窗被 `HSR_Daily_CloseRewardPopup` 关闭；
- [ ] `HSR_Daily_Exit` 正常结束，状态 3000；
- [ ] 失败时日志能定位到具体节点名 / 模板 / roi / 当前分辨率。

排错优先级（不要先改 threshold）：

1. 模板是不是来自**本机虚拟屏截图**；
2. 模板短边是否 ≥ 64px、是否含边框/图标而不是纯文字；
3. 虚拟屏是不是 720p（1080p 时适配层会把 roi ×1.5、模板切到 `1080p/` 集）；
4. `resource/base/image/` 是否真的被 `AssetResourceDeployer` 部署到 userDir（看日志「资源部署完成」）。

## 5. 红线（沿用 MaaKotlinUI）

- 不注册 `MaaTaskerAddContextSink`；
- 不用原生 Swipe 多途径点数组；
- 不在 `CustomRecognition` 里嵌套 OCR，不在 `CustomAction` 里取 image；
- 识别/截图/注入全部留在引擎进程，App 只做 UI 与编排。

## 6. 阶段 1 已知边界

- 任务起点是「已经在邮箱界面」。主界面 → 手机菜单 → 邮箱的导航要等手机端 `menu` / `mail` 图标模板采到后，在阶段 2 补 `NavTo_mail`（原 PC 的 `screen.change_to('mail')`）；
- 包名固定国服 `com.miHoYo.hkrpg`（`Startup.json`）。B服 `com.miHoYo.hkrpg.bilibili` 需要改这一处，阶段 1 不做选项化；
- ROI 暂时全屏。模板稳定后再按 `roi` 收窄（适配层会自动按分辨率缩放）；
- 没有 OCR：奖励弹窗的关闭用 `click_close` 视觉模板，不依赖文字；
- 关闭游戏时引擎侧仍写死 `com.tencent.KiHan`（阶段 1 不要开启「任务结束后关闭游戏」）。

## 7. 阶段 2 预告（等阶段 1 真机通过后再做）

1. 补 `NavTo_mail`：主界面 → 菜单 → 邮箱；
2. 同一任务扩到每日实训 / 委托 / 无名勋礼（结构相同，复用适配层）；
3. 用 1080p 虚拟屏再采一套模板，验证 ≥ 90% 成功率。
