# app-zzz 资源包重采集说明

本目录下的 `src/main/assets/pi/` 是由
[OneDragon-Anything/ZenlessZoneZero-OneDragon](https://github.com/OneDragon-Anything/ZenlessZoneZero-OneDragon)（GPL-3.0，
HEAD `256c2005749247462e6bd1337a5b9e62e4064f09`，v2.0.0）**声明式数据**迁移而来的
MaaFramework Project Interface V2 资源包。生成器：`MaaPocket/scripts/migrate_onedragon_zzz.py`。

**一句话结论：这个包目前没有在任何 Android 设备上跑过一次。里面每一个 `roi`、每一张
`raw.png` 都是假设，不是已验证资产。** 本文说明哪些部分是可信的、哪些必须重采集、以及
按什么顺序做。

---

## 0. 最重要的问题：上游是 PC 端

OneDragon 是 **Windows PC 客户端**的自动化工具：

- 画面区域 `pc_rect` 是 **PC 游戏窗口内的绝对像素**，坐标系是 `1920×1080`
  （`config/project.yml` 的 `screen_standard_width: 1920` / `screen_standard_height: 1080`，
  由 `src/zzz_od/context/zzz_context.py` 传给 `ZPcController`，
  见 `src/one_dragon/base/controller/pc_game_window.py`）。
- 全部模板图是在 **PC 端截图上裁下来的**。
- 控制器层是纯 Win32：GDI `BitBlt` / `PrintWindow` 截图、`ctypes` 键鼠、虚拟手柄。
  `src/one_dragon/base/controller/` 里**没有任何** ADB / Android 代码（grep
  `adb|android` = 0 命中）。

而 MaaPocket 跑的是 **Android 手游客户端**。两者是同一款游戏的两个不同 UI：

| 维度 | PC 端（上游数据来源） | Android 端（本包运行环境） |
| --- | --- | --- |
| 分辨率 | 固定 1920×1080，16:9 | 各种比例，常见 2400×1080（20:9）、2340×1080 |
| 布局 | PC 版 UI，鼠标交互 | 移动版 UI，触摸交互，键位/按钮位置普遍不同 |
| 截图 | Win32 抓窗口 | MaaFramework ADB 截图 |
| 交互 | `ctypes` 键鼠 / 手柄 | ADB 触摸 |

**结论：`pc_rect → roi` 的换算在数学上是对的，在语义上大概率是错的。** 迁移脚本
唯一能保证的是"结构正确 + 通过 schema 校验"，不能保证"点了正确的位置"。

---

## 1. 迁移后已经正确的部分（不需要重采集）

这些是**声明式数据**，与设备无关，可以放心用：

1. **画面分类体系**：79 个画面（`screen_id` / `screen_name` 1:1 来自上游
   `assets/game_data/screen_info/*.yml`）。上游有 80 个文件，其中 `_od_merged.yml`
   是合并产物，不单独产出画面。
2. **每个区域的 OCR 文本**：`expected` 就是上游 `text` 字段的逐字转义
   （`re.escape(text)`），337 个 OCR 区域全部保留。
3. **LCS 阈值数值**：上游 `lcs_percent` 原样抄进 OCR 的 `threshold`（见 §5 的警告）。
4. **模板身份**：`template_sub_dir` / `template_id` 到磁盘路径的映射规则完整保留，
   57 个模板目录、57 张 `raw.png` 全部就位，路径与上游一一对应。
5. **画面跳转图**：`goto_list` → pipeline `next`，**0 个目标解析失败**。
   79 个画面的跳转边全部落到真实的画面节点名上。
6. **点击意图**：700 个区域全部有 `click_evidence` 标签，依据是上游
   `find_and_click_area` / `find_area` / `get_area` 的**真实调用点**，不是猜测。
   分布：`click-call-site` 234、`literal-ambiguous-use` 42（合计 `Click` 342）、
   `goto_list` 66（`Click`）、`read-only-area-usage` 196、`id_mark-fingerprint-only` 41、
   `unreferenced` 121（合计 `DoNothing` 358）。
7. **颜色过滤数据**：上游 12 个非空 `color_range` 中 11 个转成了 `ColorMatch` 辅助节点
   （第 12 个被有意丢弃，见 §5）。
8. **任务分类体系**：30 个任务（来自上游 `APP_ID` / `APP_NAME`）、7 个分组
   （来自上游 GUI 的 `src/zzz_od/gui/view/*` 目录划分）。
9. **结构正确性**：83 个 JSON 全部产出；80 个 pipeline 文件 + `interface.json`
   对 `pipeline.schema.json` / `interface.schema.json` 校验 **0 错误**。
   输出目录中**没有任何以 `_` 开头的目录名**（MaaFramework Android 打包限制）。

---

## 2. 必须重采集的部分

| 对象 | 数量 | 为什么必须重采集 |
| --- | --- | --- |
| 画面节点的识别 `roi` | 79 个画面 | 画面指纹区域在移动版 UI 上位置不同 |
| 区域节点的识别 `roi` | 583 个不同矩形 / 700 个区域 | 同上 |
| 模板图 `raw.png` | 57 张 | 全是 PC 1080p 截图裁剪，移动版图标尺寸/比例/风格都可能不同 |
| 所有 `roi` 里的绝对 X 坐标 | 全部 | 若设备不是 16:9，1920 宽的坐标空间根本覆盖不到屏幕右侧 |
| 34 个"无指纹画面"的判定逻辑 | 34 个画面 | 见 §5.2 |
| 13 个空壳任务 | 13 个 | 上游对应包没有任何画面数据，见 §5.4 |

**不需要重采集**：模板目录结构、节点命名、`next` 跳转图、OCR 文本、任务/分组分类。

---

## 3. 设计分辨率：现在就定下来

当前包的设计分辨率 = **1920×1080**，`interface.json` 里
`controller[0].display_short_side = 1080`。

含义（来自 `pipeline.schema.json` 对 `template` 的原文：
"所使用的图片需要是无损原图缩放到 720p 后的裁剪"）：
MaaFramework 会把设备截图**按短边归一化**到 `display_short_side`，
然后 `roi` 和模板图都在这个归一化坐标系里比较。`display_short_side: 1080`
意味着"短边 1080 的那个坐标系"，对 1080p PC 数据来说缩放系数正好是 1，
所以 PNG 可以逐字节搬运、不需要缩放。

重采集时怎么选：

- **保持 `display_short_side: 1080`。** 这样归一化后的短边仍是 1080，
  已迁移的 57 张模板在**尺度**上仍然可比（只是内容/位置要换）。
- **不要随便改这个值。** 它一改，包里每一个 `roi` 和每一张模板图的含义同时改变。
- 归一化后的长边 = 实际宽高比决定。例如 2400×1080 的设备归一化后就是 2400×1080，
  `roi` 的 X 可以到 2400；而当前包里所有 `roi` 的 X 都 ≤ 1920。
  这就是"非 16:9 设备右半边完全没覆盖"的具体后果。

> 如果最终确定只支持某一种设备比例，也可以把设计分辨率改成那个比例，
> 但那时**必须**同时重新生成全部 `roi` 和全部模板图，没有折中方案。

---

## 4. 重采集操作流程（按顺序执行）

### 步骤 0 —— 冻结生成器

**一旦开始手工修正 `pi/` 里的 `roi`，就不要再运行
`migrate_onedragon_zzz.py --out .../pi`。** 脚本是幂等的、会把自己的输出写回磁盘，
你的手工修改会被静默覆盖（`emit_json` 只比较内容，发现不同就重写）。

正确的做法二选一：

- **A（推荐）**：把修正回填到上游 YAML 的副本（`screen_info/*.yml` 的 `pc_rect`），
  然后用 `--source` 指向那个副本重新生成；或者
- **B**：接受 `pi/` 从此变成手写资产，把生成器当作一次性脚手架，
  并在提交信息里写明这一点。

### 步骤 1 —— 准备设备与环境

```bash
adb devices
adb shell wm size          # 记录物理分辨率，例如 2400x1080
```

记录设备分辨率，第 3 步要用。

### 步骤 2 —— 批量截图

```bash
adb shell mkdir -p /sdcard/zzz_cap
# 把游戏停在目标画面，然后：
adb shell screencap -p /sdcard/zzz_cap/email_01.png
adb pull /sdcard/zzz_cap/email_01.png cap/
```

- 用 `screencap` + `pull` 两段式，**不要**用
  `adb shell screencap -p > file.png`：PowerShell 的 `>` 会做文本转换，
  把 PNG 里的 `0x0A` 改写成 `0x0D 0x0A`，文件直接损坏。
  （`adb exec-out screencap -p > x.png` 在 cmd.exe 下是安全的，在 PowerShell 下不安全。）
- 每个画面截 **3~5 张**：同一个画面在不同剧情/进度/弹窗下内容不同，
  而 `id_mark` 区域必须在所有这些变体上都稳定命中。
- 需要覆盖的画面清单 = `resource/pipeline/` 下的 79 个文件名去掉 `.json`。

### 步骤 3 —— 归一化到短边 1080

MaaFramework 喂给识别器的图 = "截图按短边缩放到 `display_short_side`"。
你在**缩放后的图**上量出来的坐标才能直接写进 `roi`。

```bash
# ImageMagick（推荐）
magick cap/email_01.png -resize x1080! norm/email_01.png
```

若设备短边本来就是 1080，这一步是恒等变换，可以跳过。
缩放算法用默认的双线性即可；模板图裁剪本身随后会再做一次，不影响。

> 本仓库自带的 Python 发行版含 Pillow，也可以用
> `Image.open(p).resize((w, h), Image.LANCZOS).save(q)`。迁移脚本本身**不**读 PNG
> （纯 stdlib 无法解码/编码 PNG），缩放必须由外部工具做。

### 步骤 4 —— 逐个画面校 `roi`

**别直接读 pipeline JSON。** `resource/pipeline/<screen_id>.json` 里只有 `roi`，
看不出它对应哪个区域。用 `migration_report.json`：

```json
{"screens": [{"screen_id": "email", "match_mode": "id_mark", "areas": [
  {"area_name": "全部领取", "node": "email__全部领取", "pc_rect": [122,982,306,1074],
   "roi": [122,982,184,92], "kind": "ocr", "action": "Click",
   "click_evidence": "click-call-site", "id_mark": true, "goto_list": []}]}]}
```

对每个区域：

1. 在归一化截图上按 `roi` 画框，看框住的是不是 `area_name` 描述的东西；
2. 量出正确矩形 `[x, y, w, h]`（MaaFramework `roi` 的格式，**宽高不是右下角坐标**）；
3. 写回 `resource/pipeline/<screen_id>.json` 里对应节点的
   `recognition.param.roi`（若识别是 `Or`，改对应分支的 `roi`；
   `ColorMatch` 辅助节点的 `roi` 不用改，它只提供颜色参数）。

要点：

- `roi` 要**贴紧目标**，只留必要的余量。背景越杂，OCR/模板越容易误命中。
- OCR 区域（`kind: "ocr"`）可以留大一点，因为 `expected` 会做正则匹配；
  模板区域（`kind: "template"`）必须贴紧，多一个像素的背景就多一份噪声。
- 5 个上游 `pc_rect` 本身就是**退化矩形**（宽或高为 0），迁移脚本把宽高强制成 1 并记了
  warning。这几个必须完全手工重画：
  - `arcade` / `下一个游戏` `[1426,736,1426,736]`
  - `enter_game` / `B服-密码输入区域` `[1891,495,1040,527]`
  - `enter_game` / `B服-账号删除区域` `[1032,436,1032,436]`
  - `intel_board` / `关闭筛选` `[1800,40,1800,40]`
  - `ridu_weekly` / `领取奖励` `[1558,314,1558,314]`

### 步骤 5 —— 重裁模板图

对 57 个模板节点，用归一化截图裁一块**紧贴目标**的图，覆盖
`resource/image/<sub_dir>/<template_id>/raw.png`。

- **路径不能改**：pipeline 里 `template` 字段是字面路径
  （如 `"menu/back/raw.png"`），改名就断链。
- 裁剪时要避开会变化的像素（血条、倒计时、浮层、动态壁纸）。
- 上游 `config.yml` 里的 `point_list` / `auto_mask` / `template_shape`
  **无法迁移**：MaaFramework 的 `TemplateMatch` 只有 `green_mask`（布尔），
  没有任何多边形/alpha/任意掩码字段。做法是：
  1. 用图片编辑器把**不希望参与匹配**的像素涂成纯绿 `RGB(0, 255, 0)`；
  2. 在该节点的 `recognition.param` 里加 `"green_mask": true`。
- 顺带说明：包里 56 个 `mask.png` 是从上游多边形掩码生成/复制的，
  **已经证实没有任何 pipeline 文件引用它们**（扫描 0 命中）。
  它们是死文件，重采集时可以直接忽略或删除。

### 步骤 6 —— 逐画面验证（必做）

生成一次不代表可用。必须打开 MaaFramework 的 debug draw：

可用开关（`_research/maafw_dev/include/MaaFramework/MaaDef.h:75-129`）：

| 开关 | 值 | 作用 |
| --- | --- | --- |
| `MaaGlobalOption_SaveDraw` | `true` | 保存识别可视化图 |
| `MaaGlobalOption_DebugMode` | `true` | 调试模式；同时让 `MaaTaskerGetRecognitionDetail` 的 `raw` / `draws` 输出有效 |
| `MaaGlobalOption_SaveOnError` | `true` | 出错时保存截图 |
| `MaaGlobalOption_LogDir` | 路径 | 输出目录 |
| `MaaGlobalOption_DrawQuality` | 默认 85 | 可视化图质量 |

`MaaTaskerGetRecognitionDetail`（`MaaTasker.h:96-106`）返回
`hit` / `box` / `detail_json`，以及 `raw`（原始截图）和 `draws`（识别过程可视化图），
后两者**仅在 debug 模式有效**。

验证方法：

1. 一次只跑一个画面节点（用 MaaPocket 的 runner，或
   `MaaTaskerOverridePipeline` 把 `entry` 指向该画面节点）。
2. 读 `draws`：命中框是绿的，未命中是红的。
3. **验收标准**：该画面节点
   - 在自己的画面上**必须命中**；
   - 在 2~3 个相邻画面上**必须不命中**（相邻画面从 `next`/`goto_list` 里挑）。
4. 区域节点同理：命中位置必须在目标区域内，`Click` 的落点必须在按钮上。

按 79 个画面逐个过。建议先做被 `app_id` 引用的 17 个任务相关画面，
它们直接决定 17 个任务能不能跑。

### 步骤 7 —— 修 34 个"无指纹画面"

`migration_report.json` 里 `match_mode: "all_areas_fallback"` 的 34 个画面
在上游**没有任何 `id_mark` 区域**。迁移脚本只能退化成"该画面的**任意一个**区域命中
就算这个画面命中"，这在真实设备上会大面积误判。

这 34 个画面是：`arcade, area_patrol, battle, battle_result_fail, city_fund,
coffee_shop, combat_simulation, common_deploy, common_screen, compendium, coupon,
enter_game, expert_challenge, fishing, hdd, hollow_zero_battle, hollow_zero_entry,
hollow_zero_event, hollow_zero_merchant, hou_hou_bakery, intel_board, life_on_line,
loading, lost_void_entry, lostvoid_indexpanel, lostvoid_investigativeStrategy, map,
news_stand, noodle_shop, normal_world, notorious_hunt, random_play, ridu_weekly,
trigrams_collection`。

处理办法（任一）：

- 给该画面上游 YAML 补一个 `id_mark: true` 的区域（推荐，能重新生成）；
- 或手写该画面的 screen 节点：`recognition` 只留最有辨识度的那一个分支。

### 步骤 8 —— 复查被丢弃的颜色过滤

上游 12 个非空 `color_range` 中有 1 个被有意丢弃：
`compendium` / `资源栏`（`color_range = [[208,208,208],[255,255,255]]`）。
原因是这个区域**没有 OCR 文本**，而 MaaFramework 的 `color_filter` 是 OCR 的字段，
纯模板/纯点击区域没有可挂的地方。如果它确实需要颜色判定，得手写
`ColorMatch` + `And` 组合。其余 11 个的 BGR→RGB 转换（上游用 `cv2.inRange`
作用在 BGR 图上，MaaFramework `method: 4` 会先做 `COLOR_BGR2RGB`）是推理出来的，
**从未在真机上执行过**，需要在步骤 6 里一并确认。

---

## 5. 生成物中"占位"而非"已验证资产"的清单

### 5.1 全部 79 个 `resource/pipeline/*.json`

| 字段 | 状态 |
| --- | --- |
| `recognition.param.roi` | **占位**。PC 像素测量值，与移动版 UI 无对应关系 |
| `recognition.param.template` | 路径正确，但指向的是 PC 截图 |
| `recognition.param.threshold`（OCR） | **占位**，且语义可疑，见 §5.3 |
| `recognition.param.expected` | **正确**（上游原文） |
| `recognition.param.color_filter` | 见 §5.5 |
| `action` | **有依据**（源码调用点证据），但仍需人工抽查 |
| `next` | **正确**（跳转图来自上游 `goto_list`） |
| `timeout` / `max_hit` | **未设置**。上游跳转图有环，实际运行时不能无限循环 |

### 5.2 34 个 `all_areas_fallback` 画面节点

**占位逻辑**。见步骤 7。它们在具备真实指纹之前不具备画面判定能力。

### 5.3 全部 337 个 OCR 节点的 `threshold`

**语义错误，必须重新调。** 上游 `lcs_percent` 是**最长公共子序列相似度**阈值
（`str_utils.find_by_lcs`），而 MaaFramework 的 `threshold` 是**模型置信度**阈值
（`pipeline.schema.json` 原文："模型置信度阈值"，默认 `0.3`）。
迁移脚本按"把 `lcs_percent` 映射到 `threshold`"的指令原样抄写，
所以 334 个区域的 `threshold` 与 MaaFramework 的默认值不同
（`migration_report.json` 里 `ocr_threshold_needs_retune: true`），
其中 19 个是 `1.0`（要求 100% 置信度，实际几乎必然漏检）。

**建议**：重采集时把这些 `threshold` 全部改回 `0.3` 起步，只在出现误命中时上调。

### 5.4 13 个空壳任务 + 30 个任务节点

`resource/pipeline/tasks.json` 里 30 个任务入口节点：

- 17 个标为 `navigation-stub-package-known`：该上游应用拥有 1 个以上画面，
  `next` 指向这些画面节点。**它们只是导航入口，不包含上游的 Python 业务逻辑**
  （领奖、战斗、路线行走等全在 Python 里，没有可迁移的声明式等价物）。
- 13 个标为 `taxonomy-only-no-screens`：上游包存在但没有任何画面数据，
  生成的是纯 `DirectHit` + `DoNothing` 空节点，**跑了等于没跑**：
  `TaskAutoBattle, TaskChargePlan, TaskDailySignin, TaskDodgeAssistant,
  TaskEngagementReward, TaskMouseSensitivityChecker, TaskNotify, TaskNotoriousHunt,
  TaskOneDragon, TaskOperationDebug, TaskPredefinedTeamChecker, TaskRedemptionCode,
  TaskScreenshotHelper`。

**这是本次迁移最大的功能缺口**：迁移的是**画面识别与任务分类**，
不是任务实现。任何声称"ZZZ 一条龙已可运行"的说法都是错的。

### 5.5 11 个 `ColorMatch` 辅助节点

`lower` / `upper` 是推理出来的（BGR→RGB 反转），**未在真机执行过**。

### 5.6 56 个 `mask.png`

**死文件**。已证实没有任何 pipeline 引用它们（0 命中），
MaaFramework 也没有对应的多边形掩码字段。保留仅为可追溯性。

### 5.7 `interface.json`

- `controller[0].display_short_side: 1080` 是对目标设备档位的**猜测**。
- `controller[0]` 没有 `adb` 子对象（ADB 路径/配置），是否需要由 MaaPocket 侧决定。
- `attach_resource_path: ["./resource"]` 与 `resource: [{name: "国服", path: ["./resource"]}]`
  指向同一目录，来自任务要求，未在真机上验证加载行为。

### 5.8 `locales/interface/zh_cn.json`

标签来自上游 `APP_NAME` 与 GUI 目录名，结构是**嵌套 JSON**
（`task.<TaskName>.label`），因为 `PiRepository.kt` 的 `resolve()` 是
"按 `.` 拆段逐层走 JsonObject"，扁平点号键是**解析不到的**。内容未校对。

### 5.9 `migration_report.json`（816 KB，在 PI 包根目录）

它是审计产物，不是运行所需资源：MaaFramework 只读 `resource/pipeline/**` 和
`resource/image/**`，PI 加载器只读 `interface.json` 及其 `import`，
所以它会被原样打进 APK（占 PI 目录总大小约一半）。
保留在包内是为了"报告与包同版本"；若在意体积，把它移到
`app-zzz/` 下（例如 `app-zzz/migration_report.json`）并从 `assets/` 移除即可，
重新生成时用 `--out` 指向临时目录再移动。

各部分的实际大小：`resource/pipeline/` 403 KB（80 个文件）、
`resource/image/` 427 KB（113 张 PNG）、`locales/` 7 KB、`interface.json` 8 KB。

---

## 6. 未能核实的事项（明确列出）

1. **移动版 UI 与 PC 版的实际差异程度。** 本环境无 Android SDK、无设备、无游戏，
   所有结论都无法用真机截图验证。上文"必须重采集"是基于平台差异的推断，
   不排除部分画面（尤其全屏居中的弹窗）在两个端上位置接近。
2. **`display_short_side` 归一化的确切实现**（取短边缩放还是长边）。
   依据是 `pipeline.schema.json` 对 `template` 字段的描述文本，未阅读 MaaFramework
   运行时代码，也未实测。
3. **`roi` 越界时的行为**：当前有区域的 `roi` 右边界达到 1920，
   在非 16:9 设备上这些坐标落在真实画面之外。MaaFramework 越界裁剪/报错的行为未验证。
4. **`color_filter` 引用的 `ColorMatch` 节点是否能在 `Or` 分支内部正常工作**，
   以及 `ColorMatch` 的 `method: 4` 转换顺序，都只依据 schema 描述。
5. **跳转图成环时的运行时行为**：`next` 构成有环图（上游最短路径算法本来是处理环的），
   MaaFramework 的 `timeout` 语义是
   `while(!timeout) { foreach(next); sleep_until(rate_limit); }`，
   但本包没有为任何节点设置 `timeout` / `max_hit`，实际会不会死循环未验证。
6. **`interface.json` 能否被 MaaPocket 的 `PiRepository` 完整加载**：
   已用 `interface.schema.json` 校验通过，并对照 `PiModels.kt` / `PiRepository.kt`
   的数据模型逐字段核对过，但没有跑过 Android 端。
7. **本包从未被 MaaFramework 执行过**：没有生成过任何一张 debug draw 图，
   §4 步骤 6 是计划，不是结果。

---

## 7. 参考

下列路径除第一行外均相对**仓库根** `D:\Trea\二游自动化`：

| 文件 | 作用 |
| --- | --- |
| `MaaPocket/scripts/migrate_onedragon_zzz.py` | 生成器；`--help` 查看 CLI，`build_rules()` 记录全部映射规则原文 |
| `app-zzz/src/main/assets/pi/migration_report.json` | 每个画面的区域清单、每个区域的 `roi`/证据/警告、模板映射表 |
| `_research/maafw_dev/tools/pipeline.schema.json` | Pipeline 字段定义（本文所有字段名出处） |
| `_research/maafw_dev/tools/interface.schema.json` | Interface V2 字段定义 |
| `_research/maafw_dev/include/MaaFramework/MaaDef.h` | debug draw 开关 |
| `MaaPocket/docs/agents.md` | MaaPocket 的 agent 集成说明（另一条独立的未决链路） |
