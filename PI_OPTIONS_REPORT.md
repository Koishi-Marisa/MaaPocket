# PI-V2 可调项（option）迁移报告

范围：`MaaPocket/app-endfield/src/main/assets/pi`（终末地，源自 MaaEnd `ae43a184c024`）与
`MaaPocket/app-zzz/src/main/assets/pi`（绝区零，源自 ZenlessZoneZero-OneDragon `256c20057492`）。

## 0. 结论摘要

- **终末地本来就有可调项，一个都没丢**。`interface.json` 顶层 `option` 是空的原因是上游 MaaEnd 的
  `assets/interface.json` 顶层本来就没有 `option`/`task`，全部 option 都在 `import` 的任务片段里；
  `migrate_maaend.py` 的 `build_interface()` 把这些键**原样透传**。递归解析 `import` 后：
  **option 定义 402 个 / task 45 个 / 其中带 option 的 task 35 个**。
  用户「每个任务都没有可调项」的观感不是数据缺失，最可能是设备上 `PiInstaller` 按
  `interface.json.version + versionCode` 判断是否重新解包（`core/src/main/java/com/maapocket/core/pi/PiInstaller.kt`），
  版本号没变就复用旧解包目录 —— 本任务不改 Kotlin，只记录在此。
- **绝区零原本 0 个 option**，本次补了 **9 个**、挂到 5 个 task 上，全部有真实 pipeline 落点。
- 终末地发现并修掉一个真机必挂的问题：`StartApp` 的 `package` 写成 `<包名>/<activity>`，
  MaaPocket 的 AndroidNative 链路只吃纯包名，B 服（`com.hypergryph.endfield.bilibili`）因此在真机起不来游戏。
  迁移脚本现在统一削成纯包名，见 §2。

## 1. 跑过的命令与真实输出

```
# 1) 迁移（终末地，force 与 pack 内既有报告一致）
python scripts/migrate_maaend.py --source ..\refs\MaaEnd \
    --out app-endfield\src\main\assets\pi --schemas ..\_research\maafw_dev\tools --force
  scroll nodes  : 14 total, 3 already in resource_adb, 10 converted to Swipe, 1 left
  key nodes     : 10 converted (ESC->BACK), 24 neutralized (PC modifier->DoNothing), 47 unresolved, 0 dead upstream, 20 already in resource_adb
  overlay       : 18 files, 44 nodes
  tasks         : 47 definitions, 7 needing cpp-algo, 3 hit an unresolved node
  validation    : interface=1 pipeline=537 task-fragments=68, gating errors=0, informational=0
  pack files    : 3104 (44358309 bytes), excluding migration_report.json
  pack digest   : 4a1b3bcf2603bdff00ecde07ed97a00be5590a9bb6bdb44f052853e308aef282

# 2) 迁移（绝区零）
python scripts/migrate_onedragon_zzz.py --source ..\refs\ZenlessZoneZero-OneDragon \
    --out app-zzz\src\main\assets\pi --schemas ..\_research\maafw_dev\tools
  screens      : 79
  areas        : 700 total / 700 converted / 0 skipped
  template refs: 57 (57 dirs copied, 113 image files)
  tasks        : 30
  files        : 195 emitted (2 changed, 193 already up to date, 866502 bytes)
  （再跑一次：195 emitted (0 changed, 195 already up to date)）

# 3) 打包
python scripts/build_pi_pack.py --source app-endfield\src\main\assets\pi --out <scratch> --game endfield --clean
  文件数 : 3104 / 总大小 : 44358309 B (42.30 MB) / exit 0
python scripts/build_pi_pack.py --source app-zzz\src\main\assets\pi --out <scratch> --game zzz --clean
  文件数 : 195 / 总大小 : 866502 B (0.83 MB) / exit 0

# 4) 自检
python scripts/check_pi_options.py --root app-endfield\src\main\assets\pi \
    --upstream ..\refs\MaaEnd --validate --quiet
  import 文件数(含根)   : 67
  合并后 option 定义数  : 402
  合并后 task 数        : 45
  带 option 的 task 数  : 35
  扫描文件              : 3105（其中 *.json 625）
  JSON 不可解析         : 0 / 带 UTF-8 BOM 的文件 : 0 / 下划线开头目录 : 0
  task.option 引用未定义: 0 / import 错误 : 0
  自检结论: 全部通过

python scripts/check_pi_options.py --root app-zzz\src\main\assets\pi --validate --quiet
  扫描文件              : 196（其中 *.json 83）
  JSON 不可解析         : 0 / 带 UTF-8 BOM 的文件 : 0 / 下划线开头目录 : 0
  task.option 引用未定义: 0 / import 错误 : 0
  自检结论: 全部通过
```

**确定性**：两个脚本各重跑两次到不同目录，逐文件 sha256 比对：

```
终末地 out1 vs out2 : 3105 文件，differing = ['migration_report.json']
                     两次 pack digest 均为 4a1b3bcf2603bdff00ecde07ed97a00be5590a9bb6bdb44f052853e308aef282
绝区零 out1 vs out2 : 196 文件，differing = ['migration_report.json']
                     落盘重跑：files 195 emitted (0 changed, 195 already up to date)
```

唯一非确定字段是报告里的 `generated_at`（以及 `arguments` 里的绝对路径），与脚本原有注释一致。

## 2. 终末地：`StartApp` intent 归一（B 服真机可用）

### 2.1 根因

`app-endfield/src/main/assets/pi/tasks/AndroidOpenGame.json` 里 `ClientVersion` 的 `Bilibili` case 原本写的
`"package": "com.hypergryph.endfield.bilibili/com.u8.sdk.U8UnityContext"`。调用链：

```
MaaFramework StartApp
  -> MaaAndroidNativeControlUnitMgr::start_app()   (_research/maafw_src/source__MaaAndroidNativeControlUnit__Manager__AndroidNativeControlUnitMgr.cpp:69-84)
       把 intent 字符串原样塞进 StartGameArgs.package_name，不做 "pkg/activity" 拆分
  -> core/src/main/cpp/bridge_input.cpp:135-157 DispatchInputMessage(START_GAME) -> UpcallStartApp
  -> core/src/main/java/com/maapocket/core/remote/internal/ActivityUtils.kt:103-136 startApp(packageName, ...)
       走 PackageManager.getLaunchIntentForPackage(packageName)
```

`getLaunchIntentForPackage` 只接受纯包名，收到 `包名/activity` 会返回 `null` → 日志
`Cannot create launch intent for app ...` → 返回 false → 游戏起不来。

### 2.2 修法（`scripts/migrate_maaend.py`）

- 新增模块级 `INTENT_ACTION_TYPES = ("StartApp", "StopApp")`、`iter_intent_actions(value)`、
  `normalise_android_intents(text)`。后者用 `loads_jsonc` 解析，把 `StartApp`/`StopApp` 的
  `param.package` 里含 `/` 的串削成第一个 `/` 之前的部分；没有改动就返回 `None`，调用方照旧原样写字节。
- `Migration.copy_trees()` 在 `emit_bytes` 之前加：只要 blob 里出现 `"StartApp"` 或 `"StopApp"` 就尝试归一，
  改动记进 `self.intent_rewrites`。
- 报告新增顶层键 `android_intents`（`policy` / `rewrites` / `stop_app_note`），
  `resource_android/KNOWN_LIMITATIONS.md` 新增 `## 9. 启动 / 关闭游戏：StartApp 与 StopApp`。

本次改写 **5 处**（全部在 `tasks/AndroidOpenGame.json`）：

| 行（迁移后） | case | 原值 | 新值 |
| --- | --- | --- | --- |
| 38 | CN | `com.hypergryph.endfield/com.u8.sdk.U8UnityContext` | `com.hypergryph.endfield` |
| 60 | Bilibili | `com.hypergryph.endfield.bilibili/com.u8.sdk.U8UnityContext` | `com.hypergryph.endfield.bilibili` |
| 82 | Global | `com.gryphline.endfield.gp/...` | `com.gryphline.endfield.gp` |
| 104 | VN | `com.hypergryph.endfield.vn/...` | `com.hypergryph.endfield.vn` |
| 137 | Cloud（ClientVersionCloudLocked） | `com.hypergryph.cloud.endfield/...` | `com.hypergryph.cloud.endfield` |

**默认值保持上游的 `CN`**（不替用户选服，保证可复现）。真机是终末地 **B 服**，
在 UI 上把 `AndroidOpenGame` 的「客户端版本」选成 `B服` 即可；那条 case 的包名现在是对的。

### 2.3 已记录但无法在本任务修掉的限制

- **`StopApp` 在 Android 上是静默 no-op**。`core/src/main/cpp/bridge_input.cpp` 的
  `DispatchInputMessage` 只处理 `TOUCH_*` / `KEY_*` / `START_GAME`，`STOP_GAME` 落进 `default: return 0`，
  即「报告成功但什么都不做」。`CloseGame` 节点因此不会真的关掉游戏。`.cpp` 不在本次可改范围，已写进 KNOWN_LIMITATIONS。
- **宿主 App 不会主动拉起游戏**。`core/src/main/java/com/maapocket/core/privilege/RemoteProtocol.kt:139` 定义了
  `APP_START = "app.start"`，`RemoteEngine.kt:195` 也注册了 `server.on(Cmd.APP_START) { appStart(it) }`，
  但**全仓库没有任何 Kotlin 调用方**。所以游戏启动只能靠 pipeline 的 `StartApp` ——
  上面 §2.2 的修正就是真机能不能启动 B 服的唯一前提。

## 3. 终末地：上游 option 是否被丢弃 / 改名

`scripts/check_pi_options.py --upstream ..\refs\MaaEnd` 逐个 import 文件（`git show HEAD:assets/...`）对比：

```
  比较文件数            : 67
  上游没有对应文件      : 0
  被丢弃的 option 定义  : 0
  新增的 option 定义    : 0
  task.option 发生变化  : 0
```

原因：上游 `refs/MaaEnd/assets/interface.json` 顶层 `option`/`task` 均为 `0`，
`migrate_maaend.py` 的 `build_interface()` 对 `group`/`pretask`/`task`/`option`/`global_option`/`setting`/`import`/`preset`
是**原样透传**，中间没有任何改名或筛选逻辑。**结论：零丢弃、零改名，没有需要「救回」的 option。**

被引用但未定义的 option：0 个；import 解析错误：0 个。

## 4. 终末地：带 option 的 task

共 35 个 task 有可调项（UI 上每个这样的任务行都会出现「选项 N」按钮）：

- `StashBackpack` → `StashBackpackType`
- `ItemTransfer` → `WhatToTransfer`, `TransferAll`, `EnableReturnTransfer`, `OriginRegion`, `DestinationRegion`, `ItemTransferStashBackpackWhenBothFull`, `ItemTransferStashBackpack`
- `RealTimeTask` → `SklandMap`, `VideoBrowser`, `AutoSkip`, `AutoCloseInteraction`, `AutoFight`, `AutoPick`, `QuickTeleport`, `AutoPuzzleSolving`, `AutoAeroSalvage`, `AutoZipline`, `AutoPat`, `AutoTaskInterval`
- `CloseGamePC` → `CloseGamePCApplyGameSetting`
- `CloseGame` → `ClientVersion`, `ClientVersionCloudLocked`
- `AndroidOpenGame` → `ClientVersion`, `ClientVersionCloudLocked`
- `AccountSwitch` → `AccountSwitchMatchMode`
- `CreditShoppingN2` → `CreditShoppingReserve`, `CreditShoppingClueSend`, `CreditShoppingQuickGiveDuplicateClues`, `CreditShoppingClueStockLimit`, `CreditShoppingPriority1`, `CreditShoppingPriority2`, `CreditShoppingPriority3`, `CreditShoppingForce`, `CreditShoppingKeepShelfRecord`
- `TrialOfSwordmancy` → `TrialOfSwordmancyMode`
- `ClaimSimulationRewards` → `ClaimSimulationArea`
- `DailyRewards` → `DailyEmailRewards`, `DailyTaskRewards`, `DailyClaimDeliveryJobsRewards`, `DailyEventRewards`, `DailyProtocolPassRewards`
- `BatchDeleteFriends` → `BatchDeleteFriendsInactiveDays`
- `BatchAddFriends` → `BatchAddFriends`
- `GiftOperator` → `StashBackpackSubTask`, `OnlyReceiveGift`
- `VisitFriends` → `VisitFriendsRemark`, `VisitFriendsPriorityRemarkEnable`, `ProductionAssistControl`
- `DijiangRewards` → `AutoStartExchange`, `StageTaskSetting`, `ClueSetting`, `QuickGiveDuplicateClues`, `SelectToGrow`
- `ProtocolSpace` → `ProtocolSpaceSchedule`, `AutoFightSetting`, `ProtocolSpaceTeamChoose`, `ProtocolSpaceMode`
- `AutoEssence` → `AutoEssenceSchedule`, `AutoEssenceMenu`, `AutoFightSettingFull`
- `SharedZiplineDelete` → `SharedZiplineDeleteValleyIV`, `SharedZiplineDeleteWuling`
- `ZiplineImport` → `ZiplineImportClearLogin`
- `ImportBluePrints` → `ImportBluePrints`
- `SwitchTeam` → `SwitchTeamTeamChoose`
- `PuzzleSolver` → `PuzzleSolverMode`
- `AutoEcoFarm` → `SelectFarm`, `StashBackpackSubTask`
- `AutoCollect` → `AutoCollectSchedule`, `AutoCollectValleyIV`, `AutoCollectWuling`, `AutoCollectMode`, `AutoCollectStashBackpackSubTask`
- `ResourceRecycleStation` → `ResourceRecycleStationRegion`
- `EssenceFilter` → `ExportInventory`
- `BatchUseDetector` → `BatchUseDetectorRegion`, `BatchUseDetectorCategory`, `BatchUseDetectorTimes`
- `GearAssembly` → `GearAssemblyType`, `GearAssemblySortOrder`
- `AutoSell` → `AutoSellValleyIV`, `AutoSellWuling`, `AutoSellPriceType`
- `AutoStockStaple` → `AutoStockStapleSchedule`, `AutoStockStapleValleyIV`, `AutoStockStapleWuling`
- `AutoStockpile` → `AutoStockpileServerTime`, `AutoStockpileAllowDataUpload`, `AutoStockpileElasticValleyIV`, `AutoStockpileElasticWuling`, `AutoStockpileMinBuy`
- `DeliveryJobs` → `ValleyIV`, `Wuling`, `PackCargoSelectItem`, `DeliveryJobsAutoDeliveryPreferZipline`, `DeliveryJobsOngoingDeliveryFallback`
- `SeizeDeliveryJobs` → `SeizeDeliveryJobsReward`, `SeizeDeliveryJobsRefreshLimit`, `SeizeDeliveryJobsCommissionSource`, `SeizeDeliveryJobsPostProcessing`
- `SellProduct` → `SellProductSchedule`, `SellProductOperatorAutoSwitch`, `SellProductSelectionStrategy`, `SellProductPriorityRules`, `SellProductItemReserveRules`, `ValleyIVSell`, `WulingSell`

**10 个 task 没有任何 option**（上游就是如此，不是迁移丢的）：

`ReceiveProdManual`, `SimpleProductionBatchStart`, `Crafting`, `IntelArchive`, `ReadAllWiki`, `BakerEntry`, `PullCountCalculator`, `AeroSalvage`, `WeaponUpgrade`, `EnvironmentMonitoring`

其中 `AndroidOpenGame`（`tasks/AndroidOpenGame.json`）带 `ClientVersion` + `ClientVersionCloudLocked`，
就是真机启动终末地要用的那个任务。

## 5. 终末地：合并后 option 全清单（402 个）

路径均相对 `app-endfield/src/main/assets/pi/`。

| # | option | cases | default_case | kind | 定义位置（全路径:行号） |
| --- | --- | --- | --- | --- | --- |
| 1 | `AcceptAllGifts` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/GiftOperator.json:1040` |
| 2 | `AccountSwitchEmail` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/AccountSwitch.json:90` |
| 3 | `AccountSwitchLastFourDigits` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/AccountSwitch.json:52` |
| 4 | `AccountSwitchMatchMode` | 2 | LastFourDigits | select | `app-endfield/src/main/assets/pi/tasks/AccountSwitch.json:20` |
| 5 | `AutoAeroSalvage` | 2 | - | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:935` |
| 6 | `AutoCloseInteraction` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:256` |
| 7 | `AutoCollectMode` | 2 | Always | select | `app-endfield/src/main/assets/pi/tasks/AutoCollect.json:147` |
| 8 | `AutoCollectSchedule` | 7 | ['AutoCollectScheduleMonday', 'AutoCollectScheduleTuesday', 'AutoCollectScheduleWednesday', 'AutoCollectScheduleThursday', 'AutoCollectScheduleFriday', 'AutoCollectScheduleSaturday', 'AutoCollectScheduleSunday'] | select | `app-endfield/src/main/assets/pi/tasks/AutoCollect.json:29` |
| 9 | `AutoCollectStashBackpackSubTask` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoCollect.json:121` |
| 10 | `AutoCollectTargetInventory` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/AutoCollect.json:402` |
| 11 | `AutoCollectValleyIV` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoCollect.json:1075` |
| 12 | `AutoCollectValleyIVCommonRoutes` | 2 | ['CommonRoute1', 'CommonRoute2'] | select | `app-endfield/src/main/assets/pi/tasks/AutoCollect.json:1165` |
| 13 | `AutoCollectValleyIVRareRoutes` | 5 | ['Route4', 'Route5', 'Route6', 'Route13', 'Route14'] | select | `app-endfield/src/main/assets/pi/tasks/AutoCollect.json:1092` |
| 14 | `AutoCollectWuling` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoCollect.json:1199` |
| 15 | `AutoCollectWulingCommonRoutes` | 6 | ['CommonRoute3', 'CommonRoute4', 'CommonRoute5', 'CommonRoute6', 'CommonRoute7', 'CommonRoute8'] | select | `app-endfield/src/main/assets/pi/tasks/AutoCollect.json:1380` |
| 16 | `AutoCollectWulingRareRoutes` | 12 | ['Route1', 'Route2', 'Route3', 'Route7', 'Route8', 'Route9', 'Route10', 'Route11', 'Route12', 'Route15', 'Route16', 'Route17'] | select | `app-endfield/src/main/assets/pi/tasks/AutoCollect.json:1216` |
| 17 | `AutoEssenceChooseLocation` | 12 | ['VFTheHub', 'VFOriginiumSciencePark', 'VFOriginLodespring', 'VFPowerPlateau', 'WLWulingCity', 'WLQingboStockade', 'WLMarkerStone', 'WLTestArea', 'WLSwordVaultDale', 'WLYinglungPass', 'WLNorthWulingExclusionZone', 'WLSnowyForest'] | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/AutoEssence.json:118` |
| 18 | `AutoEssenceDoOverride` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/AutoEssence.json:640` |
| 19 | `AutoEssenceLocationSecondary_VFOriginLodespring` | 16 | s2_9 | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/Location/VFOriginLodespring.json:3` |
| 20 | `AutoEssenceLocationSecondary_VFOriginiumSciencePark` | 16 | s2_2 | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/Location/VFOriginiumSciencePark.json:3` |
| 21 | `AutoEssenceLocationSecondary_VFPowerPlateau` | 16 | s2_2 | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/Location/VFPowerPlateau.json:3` |
| 22 | `AutoEssenceLocationSecondary_VFTheHub` | 16 | s2_2 | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/Location/VFTheHub.json:3` |
| 23 | `AutoEssenceLocationSecondary_WLMarkerStone` | 16 | s2_2 | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/Location/WLMarkerStone.json:3` |
| 24 | `AutoEssenceLocationSecondary_WLNorthWulingExclusionZone` | 16 | s2_9 | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/Location/WLNorthWulingExclusionZone.json:3` |
| 25 | `AutoEssenceLocationSecondary_WLQingboStockade` | 16 | s2_9 | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/Location/WLQingboStockade.json:3` |
| 26 | `AutoEssenceLocationSecondary_WLSnowyForest` | 16 | s2_2 | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/Location/WLSnowyForest.json:3` |
| 27 | `AutoEssenceLocationSecondary_WLSwordVaultDale` | 16 | s2_2 | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/Location/WLSwordVaultDale.json:3` |
| 28 | `AutoEssenceLocationSecondary_WLTestArea` | 16 | s2_9 | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/Location/WLTestArea.json:3` |
| 29 | `AutoEssenceLocationSecondary_WLWulingCity` | 16 | s2_2 | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/Location/WLWulingCity.json:3` |
| 30 | `AutoEssenceLocationSecondary_WLYinglungPass` | 16 | s2_2 | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/Location/WLYinglungPass.json:3` |
| 31 | `AutoEssenceLocationSlot1` | 5 | ['s1_2', 's1_3', 's1_4'] | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/Location/Slot1.json:3` |
| 32 | `AutoEssenceMenu` | 3 | Random | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/AutoEssence.json:247` |
| 33 | `AutoEssenceObtainMode` | 3 | ObtainScaling2 | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/AutoEssence.json:325` |
| 34 | `AutoEssenceObtainModeClaimOnly` | 2 | ObtainScaling2 | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/AutoEssence.json:400` |
| 35 | `AutoEssenceObtainModeClaimOnlyForcedFilter` | 2 | ObtainScaling2 | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/AutoEssence.json:454` |
| 36 | `AutoEssenceRepeatCount` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/AutoEssence/AutoEssence.json:682` |
| 37 | `AutoEssenceSchedule` | 7 | ['AutoEssenceScheduleMonday', 'AutoEssenceScheduleTuesday', 'AutoEssenceScheduleWednesday', 'AutoEssenceScheduleThursday', 'AutoEssenceScheduleFriday', 'AutoEssenceScheduleSaturday', 'AutoEssenceScheduleSunday'] | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/AutoEssence.json:26` |
| 38 | `AutoEssenceSelectLocation` | 12 | VFTheHub | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/Location/SelectLocation.json:3` |
| 39 | `AutoEssenceSpMedicationExpireWithinDays` | 5 | Days3 | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/AutoEssence.json:568` |
| 40 | `AutoEssenceWeaponTypeClaymore` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/Target/Target.json:633` |
| 41 | `AutoEssenceWeaponTypeLance` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/Target/Target.json:482` |
| 42 | `AutoEssenceWeaponTypePistol` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/Target/Target.json:796` |
| 43 | `AutoEssenceWeaponTypeSword` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/Target/Target.json:243` |
| 44 | `AutoEssenceWeaponTypeWand` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/Target/Target.json:3` |
| 45 | `AutoEssenceWeaponsClaymore` | 11 | ['wpn_claym_0017', 'wpn_claym_0007', 'wpn_claym_0004', 'wpn_claym_0013', 'wpn_claym_0016', 'wpn_claym_0008', 'wpn_claym_0006'] | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/Target/Target.json:649` |
| 46 | `AutoEssenceWeaponsLance` | 10 | ['wpn_lance_0007', 'wpn_lance_0015', 'wpn_lance_0012', 'wpn_lance_0016', 'wpn_lance_0010', 'wpn_lance_0011', 'wpn_lance_0014'] | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/Target/Target.json:498` |
| 47 | `AutoEssenceWeaponsPistol` | 9 | ['wpn_pistol_0005', 'wpn_pistol_0011', 'wpn_pistol_0009', 'wpn_pistol_0007', 'wpn_pistol_0008', 'wpn_pistol_0010'] | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/Target/Target.json:812` |
| 48 | `AutoEssenceWeaponsSword` | 17 | ['wpn_sword_0010', 'wpn_sword_0014', 'wpn_sword_0016', 'wpn_sword_0011', 'wpn_sword_0017', 'wpn_sword_0021', 'wpn_sword_0022', 'wpn_sword_0012', 'wpn_sword_0006', 'wpn_sword_0013', 'wpn_sword_0026'] | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/Target/Target.json:259` |
| 49 | `AutoEssenceWeaponsWand` | 17 | ['wpn_funnel_0008', 'wpn_funnel_0013', 'wpn_funnel_0015', 'wpn_funnel_0018', 'wpn_funnel_0010', 'wpn_funnel_0011', 'wpn_funnel_0016', 'wpn_funnel_0017', 'wpn_funnel_0009', 'wpn_funnel_0006', 'wpn_funnel_0019', 'wpn_funnel_0020'] | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/Target/Target.json:19` |
| 50 | `AutoExtractSeed` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/DijiangRewards.json:793` |
| 51 | `AutoFight` | 2 | - | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:424` |
| 52 | `AutoFightAttack` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:469` |
| 53 | `AutoFightAxis` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:581` |
| 54 | `AutoFightAxisData` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:623` |
| 55 | `AutoFightAxisFullSetting` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:601` |
| 56 | `AutoFightAxisSkipComboCooldown` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:644` |
| 57 | `AutoFightBreakAccumulatingPower` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:745` |
| 58 | `AutoFightCombo` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:496` |
| 59 | `AutoFightDodge` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:523` |
| 60 | `AutoFightDodgeCompat` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:553` |
| 61 | `AutoFightEndSkill` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:772` |
| 62 | `AutoFightHealthDangerousSwitch` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:799` |
| 63 | `AutoFightLockTarget` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:826` |
| 64 | `AutoFightReserveSkillLevel` | 3 | 1 | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:702` |
| 65 | `AutoFightSetting` | 2 | - | select | `app-endfield/src/main/assets/pi/tasks/ProtocolSpace.json:118` |
| 66 | `AutoFightSettingFull` | 2 | - | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:449` |
| 67 | `AutoFightSkill` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:672` |
| 68 | `AutoPat` | 2 | - | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:1011` |
| 69 | `AutoPick` | 2 | - | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:854` |
| 70 | `AutoPuzzleSolving` | 2 | - | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:911` |
| 71 | `AutoSellPriceGlobal` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/AutoSell.json:152` |
| 72 | `AutoSellPricePerCategory` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/AutoSell.json:183` |
| 73 | `AutoSellPriceType` | 2 | AutoSellPriceTypePerCategory | select | `app-endfield/src/main/assets/pi/tasks/AutoSell.json:89` |
| 74 | `AutoSellTicketOverflowValleyIV` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/AutoSell.json:110` |
| 75 | `AutoSellTicketOverflowWuling` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/AutoSell.json:131` |
| 76 | `AutoSellValleyIV` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoSell.json:25` |
| 77 | `AutoSellWuling` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoSell.json:57` |
| 78 | `AutoSkip` | 2 | - | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:160` |
| 79 | `AutoSkipAll` | 2 | - | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:187` |
| 80 | `AutoSkipChoose` | 2 | - | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:210` |
| 81 | `AutoSkipNext` | 2 | - | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:233` |
| 82 | `AutoStartExchange` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/DijiangRewards.json:32` |
| 83 | `AutoStockBuyCulturalGoodsValleyIV` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:608` |
| 84 | `AutoStockBuyCulturalGoodsValleyIVGiftLimit` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:759` |
| 85 | `AutoStockBuyCulturalGoodsValleyIVItems` | 7 | - | select | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:626` |
| 86 | `AutoStockBuyCulturalGoodsWuling` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:1721` |
| 87 | `AutoStockBuyCulturalGoodsWulingGiftLimit` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:1952` |
| 88 | `AutoStockBuyCulturalGoodsWulingItems` | 11 | - | select | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:1739` |
| 89 | `AutoStockBuyDailyGoodsValleyIV` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:175` |
| 90 | `AutoStockBuyDailyGoodsValleyIVEngravingPermitLimit` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:327` |
| 91 | `AutoStockBuyDailyGoodsValleyIVFoodAndBuffLimit` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:363` |
| 92 | `AutoStockBuyDailyGoodsValleyIVItems` | 7 | - | select | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:194` |
| 93 | `AutoStockBuyDailyGoodsWuling` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:1124` |
| 94 | `AutoStockBuyDailyGoodsWulingEngravingPermitLimit` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:1312` |
| 95 | `AutoStockBuyDailyGoodsWulingFoodAndBuffLimit` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:1348` |
| 96 | `AutoStockBuyDailyGoodsWulingItems` | 9 | - | select | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:1143` |
| 97 | `AutoStockBuyStockFactoryGoodsValleyIV` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:489` |
| 98 | `AutoStockBuyStockFactoryGoodsValleyIVDetectorCompassLimit` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:554` |
| 99 | `AutoStockBuyStockFactoryGoodsValleyIVItems` | 2 | ['KeenValleyDetector', 'KeenValleyCompass'] | select | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:507` |
| 100 | `AutoStockBuyStockFactoryGoodsWuling` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:1510` |
| 101 | `AutoStockBuyStockFactoryGoodsWulingArtificingCatalystLimit` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:1613` |
| 102 | `AutoStockBuyStockFactoryGoodsWulingDetectorCompassLimit` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:1649` |
| 103 | `AutoStockBuyStockFactoryGoodsWulingItems` | 4 | ['WulingArtificingCatalyst', 'KeenWulingDetector', 'KeenWulingCompass'] | select | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:1529` |
| 104 | `AutoStockMinDiscountValleyIV` | 9 | -50% | select | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:903` |
| 105 | `AutoStockMinDiscountWuling` | 9 | -50% | select | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:2168` |
| 106 | `AutoStockReserveValleyIV` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:148` |
| 107 | `AutoStockReserveWuling` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:1097` |
| 108 | `AutoStockStapleSchedule` | 7 | ['AutoStockStapleScheduleMonday', 'AutoStockStapleScheduleTuesday', 'AutoStockStapleScheduleWednesday', 'AutoStockStapleScheduleThursday', 'AutoStockStapleScheduleFriday', 'AutoStockStapleScheduleSaturday', 'AutoStockStapleScheduleSunday'] | select | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:30` |
| 109 | `AutoStockStapleValleyIV` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:122` |
| 110 | `AutoStockStapleWuling` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoStockStaple.json:1071` |
| 111 | `AutoStockpileAllowDataUpload` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoStockpile.json:84` |
| 112 | `AutoStockpileElasticValleyIV` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoStockpile.json:124` |
| 113 | `AutoStockpileElasticWuling` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoStockpile.json:142` |
| 114 | `AutoStockpileMinBuy` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/AutoStockpile.json:160` |
| 115 | `AutoStockpileServerTime` | 4 | CN_UTC_PLUS_8 | select | `app-endfield/src/main/assets/pi/tasks/AutoStockpile.json:32` |
| 116 | `AutoTaskInterval` | 3 | AutoTaskIntervalNormal | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:953` |
| 117 | `AutoUseSpMedication` | 2 | UseMedication | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/AutoEssence.json:528` |
| 118 | `AutoZipline` | 2 | - | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:988` |
| 119 | `BatchAddFriends` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/BatchAddFriends.json:25` |
| 120 | `BatchDeleteFriendsInactiveDays` | 6 | Days30 | select | `app-endfield/src/main/assets/pi/tasks/BatchDeleteFriends.json:20` |
| 121 | `BatchUseDetectorCategory` | 2 | Detector | select | `app-endfield/src/main/assets/pi/tasks/BatchUseDetector.json:52` |
| 122 | `BatchUseDetectorRegion` | 2 | Wuling | select | `app-endfield/src/main/assets/pi/tasks/BatchUseDetector.json:27` |
| 123 | `BatchUseDetectorTimes` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/BatchUseDetector.json:83` |
| 124 | `ClaimSimulationArea` | 2 | ValleyIV | select | `app-endfield/src/main/assets/pi/tasks/ClaimSimulationRewards.json:25` |
| 125 | `ClientVersion` | 4 | CN | select | `app-endfield/src/main/assets/pi/tasks/AndroidOpenGame.json:23` |
| 126 | `ClientVersionCloudLocked` | 1 | Cloud | select | `app-endfield/src/main/assets/pi/tasks/AndroidOpenGame.json:122` |
| 127 | `CloseGamePCApplyGameSetting` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/CloseGamePC.json:20` |
| 128 | `CloseGamePCAutoHDR` | 3 | Unchanged | select | `app-endfield/src/main/assets/pi/tasks/CloseGamePC.json:444` |
| 129 | `CloseGamePCGameSettingDisplayType` | 2 | Window | select | `app-endfield/src/main/assets/pi/tasks/CloseGamePC.json:257` |
| 130 | `CloseGamePCGameSettingFrameRate` | 4 | Unchanged | select | `app-endfield/src/main/assets/pi/tasks/CloseGamePC.json:393` |
| 131 | `CloseGamePCGameSettingGraphicsQuality` | 6 | Unchanged | select | `app-endfield/src/main/assets/pi/tasks/CloseGamePC.json:320` |
| 132 | `CloseGamePCGameSettingLanguage` | 15 | Unchanged | select | `app-endfield/src/main/assets/pi/tasks/CloseGamePC.json:84` |
| 133 | `CloseGamePCGameSettingRegion` | 2 | CN | select | `app-endfield/src/main/assets/pi/tasks/CloseGamePC.json:55` |
| 134 | `CloseGamePCGameSettingResolution` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/CloseGamePC.json:292` |
| 135 | `ClueSend` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/DijiangRewards.json:357` |
| 136 | `ClueSetting` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/DijiangRewards.json:282` |
| 137 | `ClueStockLimit` | 2 | 2 | select | `app-endfield/src/main/assets/pi/tasks/DijiangRewards.json:374` |
| 138 | `CreditShoppingClueSend` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/CreditShopping.json:50` |
| 139 | `CreditShoppingClueStockLimit` | 2 | 2 | select | `app-endfield/src/main/assets/pi/tasks/CreditShopping.json:97` |
| 140 | `CreditShoppingForce` | 3 | Refresh | select | `app-endfield/src/main/assets/pi/tasks/CreditShopping.json:2142` |
| 141 | `CreditShoppingKeepShelfRecord` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/CreditShopping.json:2338` |
| 142 | `CreditShoppingPriority1` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/CreditShopping.json:123` |
| 143 | `CreditShoppingPriority1AutoGetCredits` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/CreditShopping.json:181` |
| 144 | `CreditShoppingPriority1DiscountValue` | 6 | Any | select | `app-endfield/src/main/assets/pi/tasks/CreditShopping.json:635` |
| 145 | `CreditShoppingPriority1Items` | 14 | ['ArsenalTicket', 'Oroberyl'] | select | `app-endfield/src/main/assets/pi/tasks/CreditShopping.json:247` |
| 146 | `CreditShoppingPriority1UnconditionalPurchase` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/CreditShopping.json:214` |
| 147 | `CreditShoppingPriority2` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/CreditShopping.json:792` |
| 148 | `CreditShoppingPriority2AutoGetCredits` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/CreditShopping.json:850` |
| 149 | `CreditShoppingPriority2DiscountValue` | 6 | -75% | select | `app-endfield/src/main/assets/pi/tasks/CreditShopping.json:1316` |
| 150 | `CreditShoppingPriority2Items` | 14 | ['ArmsInspector', 'ArmsINSPKit', 'ArsenalTicket', 'CastDie', 'ElementaryCognitiveCarrier', 'ElementaryCombatRecord', 'HeavyCastDie', 'IntermediateCombatRecord', 'Oroberyl', 'Protodisk', 'Protoprism', 'Protohedron', 'Protoset', 'TCreds'] | select | `app-endfield/src/main/assets/pi/tasks/CreditShopping.json:916` |
| 151 | `CreditShoppingPriority2UnconditionalPurchase` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/CreditShopping.json:883` |
| 152 | `CreditShoppingPriority3` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/CreditShopping.json:1473` |
| 153 | `CreditShoppingPriority3AutoGetCredits` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/CreditShopping.json:1531` |
| 154 | `CreditShoppingPriority3DiscountValue` | 6 | Any | select | `app-endfield/src/main/assets/pi/tasks/CreditShopping.json:1985` |
| 155 | `CreditShoppingPriority3Items` | 14 | ['ArsenalTicket', 'Oroberyl'] | select | `app-endfield/src/main/assets/pi/tasks/CreditShopping.json:1597` |
| 156 | `CreditShoppingPriority3UnconditionalPurchase` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/CreditShopping.json:1564` |
| 157 | `CreditShoppingQuickGiveDuplicateClues` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/CreditShopping.json:73` |
| 158 | `CreditShoppingRefreshCheckPrice` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/CreditShopping.json:2216` |
| 159 | `CreditShoppingReserve` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/CreditShopping.json:2357` |
| 160 | `CrisisDrills` | 5 | - | select | `app-endfield/src/main/assets/pi/tasks/ProtocolSpace.json:847` |
| 161 | `DailyClaimDeliveryJobsRewards` | 2 | - | select | `app-endfield/src/main/assets/pi/tasks/DailyRewards.json:104` |
| 162 | `DailyEmailRewards` | 2 | - | select | `app-endfield/src/main/assets/pi/tasks/DailyRewards.json:32` |
| 163 | `DailyEventRewards` | 2 | - | select | `app-endfield/src/main/assets/pi/tasks/DailyRewards.json:54` |
| 164 | `DailyProtocolPassRewards` | 2 | - | select | `app-endfield/src/main/assets/pi/tasks/DailyRewards.json:126` |
| 165 | `DailyTaskAssemble` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/DailyRewards.json:175` |
| 166 | `DailyTaskCreation` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/DailyRewards.json:259` |
| 167 | `DailyTaskLevelUpOperator` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/DailyRewards.json:205` |
| 168 | `DailyTaskRewards` | 2 | - | select | `app-endfield/src/main/assets/pi/tasks/DailyRewards.json:76` |
| 169 | `DailyTaskUpgradeWeapon` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/DailyRewards.json:148` |
| 170 | `DeliveryJobsAtLeastMinimumQuoteActionOriginLodespring` | 4 | Transfer | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:634` |
| 171 | `DeliveryJobsAtLeastMinimumQuoteActionOriginiumSciencePark` | 4 | Transfer | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:286` |
| 172 | `DeliveryJobsAtLeastMinimumQuoteActionPowerPlateau` | 4 | Transfer | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:982` |
| 173 | `DeliveryJobsAtLeastMinimumQuoteActionTestArea` | 4 | Transfer | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:1705` |
| 174 | `DeliveryJobsAtLeastMinimumQuoteActionWulingCity` | 4 | Transfer | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:1357` |
| 175 | `DeliveryJobsAutoDeliveryPreferZipline` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:1827` |
| 176 | `DeliveryJobsBelowMinimumQuoteActionOriginLodespring` | 4 | AcceptJobOnly | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:695` |
| 177 | `DeliveryJobsBelowMinimumQuoteActionOriginiumSciencePark` | 4 | AcceptJobOnly | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:347` |
| 178 | `DeliveryJobsBelowMinimumQuoteActionPowerPlateau` | 4 | AcceptJobOnly | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:1043` |
| 179 | `DeliveryJobsBelowMinimumQuoteActionTestArea` | 4 | AcceptJobOnly | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:1766` |
| 180 | `DeliveryJobsBelowMinimumQuoteActionWulingCity` | 4 | AcceptJobOnly | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:1418` |
| 181 | `DeliveryJobsOngoingDeliveryFallback` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:1865` |
| 182 | `DeliveryJobsQuoteThresholdOriginLodespring` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:606` |
| 183 | `DeliveryJobsQuoteThresholdOriginiumSciencePark` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:258` |
| 184 | `DeliveryJobsQuoteThresholdPowerPlateau` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:954` |
| 185 | `DeliveryJobsQuoteThresholdTestArea` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:1677` |
| 186 | `DeliveryJobsQuoteThresholdWulingCity` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:1329` |
| 187 | `DestinationRegion` | 2 | Wuling | select | `app-endfield/src/main/assets/pi/tasks/ItemTransfer.json:22985` |
| 188 | `DiscardUnmatched` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/EssenceFilter.json:505` |
| 189 | `EnableClickAnywhere` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:376` |
| 190 | `EnableCloseSpecialPanel` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:400` |
| 191 | `EnableReturnTransfer` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/ItemTransfer.json:22844` |
| 192 | `EnableTutorialBook` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:346` |
| 193 | `EnableTutorialGuide` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:316` |
| 194 | `EssenceFilterAfterBattle` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/AutoEssence.json:708` |
| 195 | `EssenceFilterAfterBattleDiscardUnmatched` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/AutoEssence.json:1114` |
| 196 | `EssenceFilterAfterBattleFlawlessEssence` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/AutoEssence.json:858` |
| 197 | `EssenceFilterAfterBattleFuturePromisingMinTotal` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/AutoEssence/AutoEssence.json:1010` |
| 198 | `EssenceFilterAfterBattleKeepFuturePromising` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/AutoEssence.json:950` |
| 199 | `EssenceFilterAfterBattleKeepSlot3Level3Practical` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/AutoEssence.json:1032` |
| 200 | `EssenceFilterAfterBattleLockFuturePromising` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/AutoEssence.json:982` |
| 201 | `EssenceFilterAfterBattleLockSlot3Practical` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/AutoEssence.json:1064` |
| 202 | `EssenceFilterAfterBattlePureEssence` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/AutoEssence.json:895` |
| 203 | `EssenceFilterAfterBattleRarity4Weapon` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/AutoEssence.json:814` |
| 204 | `EssenceFilterAfterBattleRarity5Weapon` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/AutoEssence.json:787` |
| 205 | `EssenceFilterAfterBattleRarity6Weapon` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/AutoEssence.json:760` |
| 206 | `EssenceFilterAfterBattleSelectEssence` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/AutoEssence.json:841` |
| 207 | `EssenceFilterAfterBattleSelectExtraRules` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/AutoEssence.json:932` |
| 208 | `EssenceFilterAfterBattleSelectWeaponRarity` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoEssence/AutoEssence.json:742` |
| 209 | `EssenceFilterAfterBattleSlot3MinLevel` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/AutoEssence/AutoEssence.json:1092` |
| 210 | `ExportCalculatorScript` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/EssenceFilter.json:533` |
| 211 | `ExportInventory` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/EssenceFilter.json:27` |
| 212 | `FastCollect` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/DijiangRewards.json:156` |
| 213 | `FillItemPrioritiesValleyIV` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:1911` |
| 214 | `FillItemPrioritiesWuling` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:24242` |
| 215 | `FlawlessEssence` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/EssenceFilter.json:192` |
| 216 | `FuturePromisingMinTotal` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/EssenceFilter.json:401` |
| 217 | `GameSettingAutoHDR` | 3 | Unchanged | select | `app-endfield/src/main/assets/pi/tasks/pretasks/GameSetting.json:197` |
| 218 | `GameSettingDisplayType` | 2 | Window | select | `app-endfield/src/main/assets/pi/tasks/pretasks/GameSetting.json:106` |
| 219 | `GameSettingFrameRate` | 4 | Unchanged | select | `app-endfield/src/main/assets/pi/tasks/pretasks/GameSetting.json:174` |
| 220 | `GameSettingGraphicsQuality` | 6 | Unchanged | select | `app-endfield/src/main/assets/pi/tasks/pretasks/GameSetting.json:143` |
| 221 | `GameSettingLanguage` | 15 | Unchanged | select | `app-endfield/src/main/assets/pi/tasks/pretasks/GameSetting.json:39` |
| 222 | `GameSettingRegion` | 2 | CN | select | `app-endfield/src/main/assets/pi/tasks/pretasks/GameSetting.json:24` |
| 223 | `GameSettingResolution` | 3 | 1280x720 | select | `app-endfield/src/main/assets/pi/tasks/pretasks/GameSetting.json:127` |
| 224 | `GearAssemblySortOrder` | 2 | Lower | select | `app-endfield/src/main/assets/pi/tasks/GearAssembly.json:29` |
| 225 | `GearAssemblyType` | 3 | SetGear | select | `app-endfield/src/main/assets/pi/tasks/GearAssembly.json:54` |
| 226 | `GiftCount` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/GiftOperator.json:1062` |
| 227 | `GotoValleyIV` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoEcoFarm.json:41` |
| 228 | `GotoWulin` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoEcoFarm.json:64` |
| 229 | `GrowthChamberStage` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/DijiangRewards.json:258` |
| 230 | `ImportBluePrints` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/ImportBluePrints.json:25` |
| 231 | `ItemTransferStashBackpack` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/ItemTransfer.json:23037` |
| 232 | `ItemTransferStashBackpackWhenBothFull` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/ItemTransfer.json:23058` |
| 233 | `KeepFuturePromising` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/EssenceFilter.json:341` |
| 234 | `KeepSlot3Level3Practical` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/EssenceFilter.json:423` |
| 235 | `KeymapFight` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/setting/Keymap.json:70` |
| 236 | `KeymapGeneral` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/setting/Keymap.json:19` |
| 237 | `LevelUpOperatorMaxLevelThreshold` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/DailyRewards.json:235` |
| 238 | `LockFuturePromising` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/EssenceFilter.json:373` |
| 239 | `LockSlot3Practical` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/EssenceFilter.json:455` |
| 240 | `ManufacturingStage` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/DijiangRewards.json:234` |
| 241 | `OnlyReceiveGift` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/GiftOperator.json:987` |
| 242 | `OperatorEXPRewardsSetOption` | 2 | - | select | `app-endfield/src/main/assets/pi/tasks/ProtocolSpace.json:898` |
| 243 | `OperatorProgression` | 4 | - | select | `app-endfield/src/main/assets/pi/tasks/ProtocolSpace.json:757` |
| 244 | `OriginLodespring` | 6 | Transfer | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:408` |
| 245 | `OriginRegion` | 2 | ValleyIV | select | `app-endfield/src/main/assets/pi/tasks/ItemTransfer.json:22907` |
| 246 | `OriginiumSciencePark` | 6 | Transfer | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:60` |
| 247 | `PackCargoSelectItem` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:1886` |
| 248 | `PowerPlateau` | 6 | Transfer | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:756` |
| 249 | `ProductionAssistControl` | 3 | ['ProductionAssistControlNexus', 'ProductionAssistMFGCabin', 'ProductionAssistGrowthChamber'] | select | `app-endfield/src/main/assets/pi/tasks/VisitFriends.json:137` |
| 250 | `PromotionsRewardsSetOption` | 2 | - | select | `app-endfield/src/main/assets/pi/tasks/ProtocolSpace.json:926` |
| 251 | `ProtocolSpaceFailedCount` | 6 | ProtocolSpaceFailedCount3 | select | `app-endfield/src/main/assets/pi/tasks/ProtocolSpace.json:541` |
| 252 | `ProtocolSpaceLevel` | 5 | ProtocolSpaceLevel05 | select | `app-endfield/src/main/assets/pi/tasks/ProtocolSpace.json:677` |
| 253 | `ProtocolSpaceMode` | 2 | ByCount | select | `app-endfield/src/main/assets/pi/tasks/ProtocolSpace.json:209` |
| 254 | `ProtocolSpaceObtainMode` | 3 | ObtainScaling2 | select | `app-endfield/src/main/assets/pi/tasks/ProtocolSpace.json:242` |
| 255 | `ProtocolSpaceObtainModeClaim` | 2 | ObtainScaling2 | select | `app-endfield/src/main/assets/pi/tasks/ProtocolSpace.json:315` |
| 256 | `ProtocolSpaceSchedule` | 7 | ['ProtocolSpaceScheduleMonday', 'ProtocolSpaceScheduleTuesday', 'ProtocolSpaceScheduleWednesday', 'ProtocolSpaceScheduleThursday', 'ProtocolSpaceScheduleFriday', 'ProtocolSpaceScheduleSaturday', 'ProtocolSpaceScheduleSunday'] | select | `app-endfield/src/main/assets/pi/tasks/ProtocolSpace.json:26` |
| 257 | `ProtocolSpaceSpMedicationExpireWithinDays` | 5 | Days3 | select | `app-endfield/src/main/assets/pi/tasks/ProtocolSpace.json:398` |
| 258 | `ProtocolSpaceSuccessCount` | 6 | ProtocolSpaceSuccessCountUnlimited | select | `app-endfield/src/main/assets/pi/tasks/ProtocolSpace.json:470` |
| 259 | `ProtocolSpaceTab` | 3 | - | select | `app-endfield/src/main/assets/pi/tasks/ProtocolSpace.json:597` |
| 260 | `ProtocolSpaceTeamChoose` | 6 | ProtocolSpaceTeamChooseDefault | select | `app-endfield/src/main/assets/pi/tasks/ProtocolSpace.json:137` |
| 261 | `ProtocolSpaceUseSpMedication` | 2 | UseMedication | select | `app-endfield/src/main/assets/pi/tasks/ProtocolSpace.json:346` |
| 262 | `PrudentRefresh` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/CreditShopping.json:2260` |
| 263 | `PrudentRefreshThreshold` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/CreditShopping.json:2287` |
| 264 | `PureEssence` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/EssenceFilter.json:229` |
| 265 | `PuzzleSolverMode` | 3 | - | select | `app-endfield/src/main/assets/pi/tasks/PuzzleSolver.json:24` |
| 266 | `QuickGiveDuplicateClues` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/DijiangRewards.json:333` |
| 267 | `QuickTeleport` | 2 | - | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:882` |
| 268 | `Rarity4Weapon` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/EssenceFilter.json:148` |
| 269 | `Rarity5Weapon` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/EssenceFilter.json:121` |
| 270 | `Rarity6Weapon` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/EssenceFilter.json:94` |
| 271 | `ReceptionRoomStage` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/DijiangRewards.json:210` |
| 272 | `RecoveryEmotionStage` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/DijiangRewards.json:186` |
| 273 | `ResourceRecycleStationRegion` | 2 | ['Wuling', 'ValleyIV'] | select | `app-endfield/src/main/assets/pi/tasks/ResourceRecycleStation.json:25` |
| 274 | `ReturnTransferAll` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/ItemTransfer.json:22870` |
| 275 | `ReturnTransferTimes` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/ItemTransfer.json:22887` |
| 276 | `ReturnWhatToTransfer` | 271 | 赤铜矿 | select | `app-endfield/src/main/assets/pi/tasks/ItemTransfer.json:11418` |
| 277 | `SeizeDeliveryJobsCommissionSource` | 8 | Unlimited | select | `app-endfield/src/main/assets/pi/tasks/SeizeDeliveryJobs.json:55` |
| 278 | `SeizeDeliveryJobsDeliveryPointOriginLodespring` | 5 | ['DeliverTargetMap01Lv00601', 'DeliverTargetMap01Lv00602', 'DeliverTargetMap01Lv00603', 'DeliverTargetMap01Lv006Recycle01', 'DeliverTargetMap01Lv006Recycle03'] | select | `app-endfield/src/main/assets/pi/tasks/SeizeDeliveryJobs.json:953` |
| 279 | `SeizeDeliveryJobsDeliveryPointOriginiumSciencePark` | 5 | ['DeliverTargetMap01Lv00501', 'DeliverTargetMap01Lv00502', 'DeliverTargetMap01Lv00503', 'DeliverTargetMap01Lv005Recycle02', 'DeliverTargetMap01Lv005Recycle03'] | select | `app-endfield/src/main/assets/pi/tasks/SeizeDeliveryJobs.json:865` |
| 280 | `SeizeDeliveryJobsDeliveryPointPowerPlateau` | 5 | ['DeliverTargetMap01Lv00701', 'DeliverTargetMap01Lv00702', 'DeliverTargetMap01Lv00703', 'DeliverTargetMap01Lv007Recycle02', 'DeliverTargetMap01Lv007Recycle03'] | select | `app-endfield/src/main/assets/pi/tasks/SeizeDeliveryJobs.json:1041` |
| 281 | `SeizeDeliveryJobsDeliveryPointTestArea` | 3 | ['No1TypeCAnchorArea', 'No3TypeCAnchorArea', 'JingweiFieldArea'] | select | `app-endfield/src/main/assets/pi/tasks/SeizeDeliveryJobs.json:797` |
| 282 | `SeizeDeliveryJobsDeliveryPointWulingCity` | 4 | ['Owl', 'MaterialResearchInstitute', 'Observatory', 'TechProductionOffice'] | select | `app-endfield/src/main/assets/pi/tasks/SeizeDeliveryJobs.json:454` |
| 283 | `SeizeDeliveryJobsPostDeparturePreferZipline` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/SeizeDeliveryJobs.json:675` |
| 284 | `SeizeDeliveryJobsPostDepartureRiskAcknowledgement` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/SeizeDeliveryJobs.json:656` |
| 285 | `SeizeDeliveryJobsPostProcessing` | 4 | Disable | select | `app-endfield/src/main/assets/pi/tasks/SeizeDeliveryJobs.json:599` |
| 286 | `SeizeDeliveryJobsRefreshLimit` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/SeizeDeliveryJobs.json:713` |
| 287 | `SeizeDeliveryJobsRepeatCount` | 3 | 3 | select | `app-endfield/src/main/assets/pi/tasks/SeizeDeliveryJobs.json:732` |
| 288 | `SeizeDeliveryJobsReward` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/SeizeDeliveryJobs.json:33` |
| 289 | `SeizeDeliveryJobsSpecifyDeliveryPointAllUnlimited` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/SeizeDeliveryJobs.json:533` |
| 290 | `SeizeDeliveryJobsSpecifyDeliveryPointOriginLodespring` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/SeizeDeliveryJobs.json:923` |
| 291 | `SeizeDeliveryJobsSpecifyDeliveryPointOriginiumSciencePark` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/SeizeDeliveryJobs.json:835` |
| 292 | `SeizeDeliveryJobsSpecifyDeliveryPointPowerPlateau` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/SeizeDeliveryJobs.json:1011` |
| 293 | `SeizeDeliveryJobsSpecifyDeliveryPointTestArea` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/SeizeDeliveryJobs.json:767` |
| 294 | `SeizeDeliveryJobsSpecifyDeliveryPointUnlimited` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/SeizeDeliveryJobs.json:502` |
| 295 | `SeizeDeliveryJobsSpecifyDeliveryPointValleyIVUnlimited` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/SeizeDeliveryJobs.json:567` |
| 296 | `SeizeDeliveryJobsSpecifyDeliveryPointWulingCity` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/SeizeDeliveryJobs.json:424` |
| 297 | `SelectEssence` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/EssenceFilter.json:175` |
| 298 | `SelectExtraRules` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/EssenceFilter.json:322` |
| 299 | `SelectFarm` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/AutoEcoFarm.json:24` |
| 300 | `SelectOperator` | 32 | Any | select | `app-endfield/src/main/assets/pi/tasks/GiftOperator.json:26` |
| 301 | `SelectToGrow` | 3 | Any | select | `app-endfield/src/main/assets/pi/tasks/DijiangRewards.json:400` |
| 302 | `SelectToGrowItems` | 18 | ['Wulingstone', 'Igneosite', 'FalseAggela', 'BlightedJadeleaf', 'Cosmagaric', 'Bloodcap', 'Umbronyx', 'Auronyx', 'Kalkonyx', 'Vitrodendra', 'Chrysodendra', 'Kalkodendra', 'RubyBolete', 'RedBolete', 'PinkBolete', 'TalosCap', 'CrimsonSpearleaf', 'Protocolith'] | select | `app-endfield/src/main/assets/pi/tasks/DijiangRewards.json:442` |
| 303 | `SelectWeaponRarity` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/EssenceFilter.json:76` |
| 304 | `SellProductForceRefreshOperatorCache` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:4182` |
| 305 | `SellProductItemReserveRules` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:1435` |
| 306 | `SellProductOnlyPreferredItems` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:224` |
| 307 | `SellProductOperatorAutoSwitch` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:4152` |
| 308 | `SellProductPriorityRules` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:126` |
| 309 | `SellProductReserveItem1` | 27 | None | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:1457` |
| 310 | `SellProductReserveItem1Mode` | 2 | Quantity | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:1859` |
| 311 | `SellProductReserveItem1Value` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:1885` |
| 312 | `SellProductReserveItem2` | 27 | None | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:1907` |
| 313 | `SellProductReserveItem2Mode` | 2 | Quantity | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:2308` |
| 314 | `SellProductReserveItem2Value` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:2334` |
| 315 | `SellProductReserveItem3` | 27 | None | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:2356` |
| 316 | `SellProductReserveItem3Mode` | 2 | Quantity | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:2757` |
| 317 | `SellProductReserveItem3Value` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:2783` |
| 318 | `SellProductReserveItem4` | 27 | None | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:2805` |
| 319 | `SellProductReserveItem4Mode` | 2 | Quantity | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:3206` |
| 320 | `SellProductReserveItem4Value` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:3232` |
| 321 | `SellProductReserveItem5` | 27 | None | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:3254` |
| 322 | `SellProductReserveItem5Mode` | 2 | Quantity | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:3655` |
| 323 | `SellProductReserveItem5Value` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:3681` |
| 324 | `SellProductReserveItem6` | 27 | None | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:3703` |
| 325 | `SellProductReserveItem6Mode` | 2 | Quantity | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:4104` |
| 326 | `SellProductReserveItem6Value` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:4130` |
| 327 | `SellProductSchedule` | 7 | ['SellProductScheduleMonday', 'SellProductScheduleTuesday', 'SellProductScheduleWednesday', 'SellProductScheduleThursday', 'SellProductScheduleFriday', 'SellProductScheduleSaturday', 'SellProductScheduleSunday'] | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:34` |
| 328 | `SellProductSelectionStrategy` | 3 | Rarity | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:153` |
| 329 | `SellProductStockMinimumUnitPrice` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:201` |
| 330 | `SellProductValleyIVPriorityItem1` | 15 | None | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:277` |
| 331 | `SellProductValleyIVPriorityItem2` | 15 | None | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:470` |
| 332 | `SellProductValleyIVPriorityItem3` | 15 | None | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:663` |
| 333 | `SellProductValleyIVPriorityItem4` | 15 | None | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:856` |
| 334 | `SellProductValleyIVPriorityItem5` | 15 | None | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:1049` |
| 335 | `SellProductValleyIVPriorityItem6` | 15 | None | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:1242` |
| 336 | `SellProductValleyIVPriorityRules` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:247` |
| 337 | `SellProductWulingPriorityItem1` | 15 | None | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:5054` |
| 338 | `SellProductWulingPriorityItem2` | 15 | None | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:5247` |
| 339 | `SellProductWulingPriorityItem3` | 15 | None | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:5440` |
| 340 | `SellProductWulingPriorityItem4` | 15 | None | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:5633` |
| 341 | `SellProductWulingPriorityItem5` | 15 | None | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:5826` |
| 342 | `SellProductWulingPriorityItem6` | 15 | None | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:6019` |
| 343 | `SellProductWulingPriorityRules` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:5024` |
| 344 | `SendCluesDirect` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/DijiangRewards.json:309` |
| 345 | `SharedZiplineDeleteValleyIV` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/SharedZiplineDelete.json:23` |
| 346 | `SharedZiplineDeleteValleyIVRegions` | 6 | ['VFTheHub', 'VFValleyPass', 'VFOriginiumSciencePark', 'VFAburreyQuarry', 'VFOriginLodespring', 'VFPowerPlateau'] | select | `app-endfield/src/main/assets/pi/tasks/SharedZiplineDelete.json:44` |
| 347 | `SharedZiplineDeleteWuling` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/SharedZiplineDelete.json:112` |
| 348 | `SharedZiplineDeleteWulingRegions` | 9 | ['WLWulingCity', 'WLJingyuValley', 'WLQingboStockade', 'WLMarkerStone', 'WLTestArea', 'WLSwordVaultDale', 'WLYinglungPass', 'WLNorthWulingExclusionZone', 'WLSnowyForest'] | select | `app-endfield/src/main/assets/pi/tasks/SharedZiplineDelete.json:133` |
| 349 | `SkillUpRewardsSetOption` | 2 | - | select | `app-endfield/src/main/assets/pi/tasks/ProtocolSpace.json:954` |
| 350 | `SkipThumbDiscard` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/EssenceFilter.json:294` |
| 351 | `SkipThumbLock` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/EssenceFilter.json:266` |
| 352 | `SklandMap` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:36` |
| 353 | `SklandMapOpacity` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:67` |
| 354 | `Slot3MinLevel` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/EssenceFilter.json:483` |
| 355 | `SortBy` | 4 | Default | select | `app-endfield/src/main/assets/pi/tasks/DijiangRewards.json:826` |
| 356 | `SortOrder` | 2 | ASC | select | `app-endfield/src/main/assets/pi/tasks/DijiangRewards.json:960` |
| 357 | `StageTaskSetting` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/DijiangRewards.json:135` |
| 358 | `StashBackpackSubTask` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/StashBackpack.json:24` |
| 359 | `StashBackpackType` | 2 | ['StashBackpackQuick', 'StashBackpackUsableItemsLimited'] | select | `app-endfield/src/main/assets/pi/tasks/StashBackpack.json:50` |
| 360 | `SupplyPlanLimits` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/ProtocolSpace.json:1010` |
| 361 | `SwitchTeamTeamChoose` | 5 | SwitchTeamTeamChoose01 | select | `app-endfield/src/main/assets/pi/tasks/SwitchTeam.json:26` |
| 362 | `TestArea` | 6 | Transfer | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:1479` |
| 363 | `TransferAll` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/ItemTransfer.json:22807` |
| 364 | `TransferTimes` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/ItemTransfer.json:22824` |
| 365 | `TrialOfSwordmancyAutoFight` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/TrialOfSwordmancy.json:146` |
| 366 | `TrialOfSwordmancyMode` | 3 | Daily | select | `app-endfield/src/main/assets/pi/tasks/TrialOfSwordmancy.json:23` |
| 367 | `TrialOfSwordmancyOverflow` | 3 | None | select | `app-endfield/src/main/assets/pi/tasks/TrialOfSwordmancy.json:87` |
| 368 | `TrialOfSwordmancyRecoverBeforeBattle` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/TrialOfSwordmancy.json:182` |
| 369 | `TrialOfSwordmancyReturnToWorld` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/TrialOfSwordmancy.json:202` |
| 370 | `TrialOfSwordmancyStopOnOverflow` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/TrialOfSwordmancy.json:127` |
| 371 | `ValleyIV` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:32` |
| 372 | `ValleyIVInfraStation` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:4964` |
| 373 | `ValleyIVReconstructionHQ` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:4994` |
| 374 | `ValleyIVRefugeeCamp` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:4934` |
| 375 | `ValleyIVSell` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:4905` |
| 376 | `VideoBrowser` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:88` |
| 377 | `VideoBrowserOpacity` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:139` |
| 378 | `VideoBrowserURL` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/RealTimeTask.json:120` |
| 379 | `VisitFriendsPriorityRemark` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/VisitFriends.json:115` |
| 380 | `VisitFriendsPriorityRemarkEnable` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/VisitFriends.json:77` |
| 381 | `VisitFriendsRemark` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/VisitFriends.json:30` |
| 382 | `WaitExchangeBeforeStart` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/DijiangRewards.json:65` |
| 383 | `WaitExchangeBeforeStartThreshold` | 0 | - | unknown | `app-endfield/src/main/assets/pi/tasks/DijiangRewards.json:98` |
| 384 | `WeaponProgression` | 2 | - | select | `app-endfield/src/main/assets/pi/tasks/ProtocolSpace.json:817` |
| 385 | `WeaponTuneRewardsSetOption` | 2 | - | select | `app-endfield/src/main/assets/pi/tasks/ProtocolSpace.json:982` |
| 386 | `WhatToFillValleyIVPriority1` | 242 | item_plant_moss_powder_3 | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:1938` |
| 387 | `WhatToFillValleyIVPriority2` | 243 | None | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:7511` |
| 388 | `WhatToFillValleyIVPriority3` | 243 | None | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:13088` |
| 389 | `WhatToFillValleyIVPriority4` | 243 | None | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:18665` |
| 390 | `WhatToFillWulingPriority1` | 242 | item_plant_moss_powder_3 | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:24269` |
| 391 | `WhatToFillWulingPriority2` | 243 | None | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:29842` |
| 392 | `WhatToFillWulingPriority3` | 243 | None | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:35419` |
| 393 | `WhatToFillWulingPriority4` | 243 | None | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:40996` |
| 394 | `WhatToTransfer` | 271 | 蓝铁矿 | select | `app-endfield/src/main/assets/pi/tasks/ItemTransfer.json:29` |
| 395 | `Wuling` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:1104` |
| 396 | `WulingCardiacRemediationStation` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:6271` |
| 397 | `WulingCity` | 6 | Transfer | select | `app-endfield/src/main/assets/pi/tasks/DeliveryJobs.json:1131` |
| 398 | `WulingSell` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:6212` |
| 399 | `WulingSkyKingFlatsConstructionSite` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:6241` |
| 400 | `WulingXiranflowCloudseederStation` | 2 | Yes | select | `app-endfield/src/main/assets/pi/tasks/OutpostTrading.json:6301` |
| 401 | `ZiplineImportClearLogin` | 2 | No | select | `app-endfield/src/main/assets/pi/tasks/ZiplineImport.json:23` |
| 402 | `ZiplineRouting` | 2 | Auto | select | `app-endfield/src/main/assets/pi/tasks/setting/Zipline.json:17` |

## 6. 绝区零：新增的 9 个 option

每个 option 的 `cases[].pipeline_override` 只覆盖**迁移包里真实存在**的节点字段（一律是 `enabled`），
没有编造节点，也没有改 `recognition`/`action` 的语义。

| option | 默认 | cases | 挂到的 task | 上游依据 | pipeline 落点（全路径:行号） |
| --- | --- | --- | --- | --- | --- |
| `GameRegion` | `CN` | `CN`, `CNB`, `INTL` | `TaskOneDragon` | `src/one_dragon/base/config/game_account_config.py:18 GameRegionEnum` | `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1064`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1048`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:992`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1080`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1032`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1156`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1116`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1012`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1136`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1176`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1096`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:916`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1284`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:900`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1268`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:864`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1232`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:932`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1300`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:884`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1252`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1548`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1376`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1412`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1468`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1428`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1488`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1448`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1340`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1396`; `app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1360` |
| `AutoUltimate` | `Off` | `On`, `Off` | `TaskAutoBattle`, `TaskOneDragon` | `src/zzz_od/application/battle_assistant/battle_assistant_config.py:61 auto_ultimate_enabled` | `app-zzz/src/main/assets/pi/resource/pipeline/battle.json:396` |
| `DriveDiscDismantleLevel` | `LevelA` | `LevelA`, `LevelS`, `LevelB` | `TaskDriveDiscDismantle` | `src/zzz_od/application/drive_disc_dismantle/drive_disc_dismantle_config.py:28 dismantle_level` | `app-zzz/src/main/assets/pi/resource/pipeline/drive_disc_dismantle.json:115`; `app-zzz/src/main/assets/pi/resource/pipeline/drive_disc_dismantle.json:215`; `app-zzz/src/main/assets/pi/resource/pipeline/drive_disc_dismantle.json:135` |
| `DriveDiscDismantleAbandon` | `Off` | `On`, `Off` | `TaskDriveDiscDismantle` | `src/zzz_od/application/drive_disc_dismantle/drive_disc_dismantle_config.py:40 dismantle_abandon` | `app-zzz/src/main/assets/pi/resource/pipeline/drive_disc_dismantle.json:95` |
| `DailySigninTarget` | `HouHouBakery` | `HouHouBakery`, `NewsStand` | `TaskDailySignin` | `src/zzz_od/application/daily_signin/daily_signin_config.py:12 selected_sign` | `app-zzz/src/main/assets/pi/resource/pipeline/hou_hou_bakery.json:28`; `app-zzz/src/main/assets/pi/resource/pipeline/news_stand.json:192` |
| `SuibianYumchaSin` | `On` | `On`, `Off` | `TaskSuibianTemple` | `src/zzz_od/application/suibian_temple/suibian_temple_config.py:16 yum_cha_sin` | `app-zzz/src/main/assets/pi/resource/pipeline/suibian_temple_yumchaxian.json:60` |
| `SuibianYumchaRefresh` | `On` | `On`, `Off` | `TaskSuibianTemple` | `src/zzz_od/application/suibian_temple/suibian_temple_config.py:25 yum_cha_sin_period_refresh` | `app-zzz/src/main/assets/pi/resource/pipeline/suibian_temple_yumchaxian.json:116` |
| `SuibianPawnshop` | `Both` | `Both`, `Omnicoin`, `Crest` | `TaskSuibianTemple` | `src/zzz_od/application/suibian_temple/suibian_temple_config.py:133 pawnshop_omnicoin_enabled`; `src/zzz_od/application/suibian_temple/suibian_temple_config.py:154 pawnshop_crest_enabled` | `app-zzz/src/main/assets/pi/resource/pipeline/suibian_temple_pawnshop.json:113`; `app-zzz/src/main/assets/pi/resource/pipeline/suibian_temple_pawnshop.json:93` |
| `SuibianBooboxPurchase` | `Off` | `On`, `Off` | `TaskSuibianTemple` | `src/zzz_od/application/suibian_temple/suibian_temple_config.py:97 boo_box_purchase_enabled` | `app-zzz/src/main/assets/pi/resource/pipeline/suibian_temple_boobox.json:61` |

### 6.1 逐条说明

**`GameRegion`**（默认 `CN`）

- 上游依据：`src/one_dragon/base/config/game_account_config.py:18 GameRegionEnum`
- 落点节点：`enter_game__B服-同意按钮`、`enter_game__B服-密码输入区域`、`enter_game__B服-登录`、`enter_game__B服-账号删除区域`、`enter_game__B服-账号输入区域`、`enter_game__B服新-切换账号`、`enter_game__B服新-同意隐私政策`、`enter_game__B服新-手机号登录`、`enter_game__B服新-登录记录`、`enter_game__B服新-账号列表`、`enter_game__B服新-隐私政策提示`、`enter_game__国服-同意按钮`、`enter_game__国服-同意按钮-新`、`enter_game__国服-密码输入区域`、`enter_game__国服-密码输入区域-新`、`enter_game__国服-账号密码`、`enter_game__国服-账号密码-新`、`enter_game__国服-账号密码进入游戏`、`enter_game__国服-账号密码进入游戏-新`、`enter_game__国服-账号输入区域`、`enter_game__国服-账号输入区域-新`、`enter_game__国服-返回按钮`、`enter_game__国际服-密码输入区域`、`enter_game__国际服-换服`、`enter_game__国际服-换服-亚洲`、`enter_game__国际服-换服-欧洲`、`enter_game__国际服-换服-港澳台`、`enter_game__国际服-换服-美国`、`enter_game__国际服-点击登录`、`enter_game__国际服-账号密码进入游戏`、`enter_game__国际服-账号输入区域`
- pipeline 依据：`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1064`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1048`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:992`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1080`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1032`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1156`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1116`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1012`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1136`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1176`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1096`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:916`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1284`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:900`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1268`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:864`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1232`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:932`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1300`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:884`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1252`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1548`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1376`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1412`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1468`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1428`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1488`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1448`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1340`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1396`；`app-zzz/src/main/assets/pi/resource/pipeline/enter_game.json:1360`
- 挂载 task：`TaskOneDragon`

**`AutoUltimate`**（默认 `Off`）

- 上游依据：`src/zzz_od/application/battle_assistant/battle_assistant_config.py:61 auto_ultimate_enabled`
- 落点节点：`battle__按键-终结技`
- pipeline 依据：`app-zzz/src/main/assets/pi/resource/pipeline/battle.json:396`
- 挂载 task：`TaskAutoBattle`、`TaskOneDragon`

**`DriveDiscDismantleLevel`**（默认 `LevelA`）

- 上游依据：`src/zzz_od/application/drive_disc_dismantle/drive_disc_dismantle_config.py:28 dismantle_level`
- 落点节点：`drive_disc_dismantle__按钮-A及以下`、`drive_disc_dismantle__按钮-B`、`drive_disc_dismantle__按钮-S及以下`
- pipeline 依据：`app-zzz/src/main/assets/pi/resource/pipeline/drive_disc_dismantle.json:115`；`app-zzz/src/main/assets/pi/resource/pipeline/drive_disc_dismantle.json:215`；`app-zzz/src/main/assets/pi/resource/pipeline/drive_disc_dismantle.json:135`
- 挂载 task：`TaskDriveDiscDismantle`

**`DriveDiscDismantleAbandon`**（默认 `Off`）

- 上游依据：`src/zzz_od/application/drive_disc_dismantle/drive_disc_dismantle_config.py:40 dismantle_abandon`
- 落点节点：`drive_disc_dismantle__按钮-全选已弃置`
- pipeline 依据：`app-zzz/src/main/assets/pi/resource/pipeline/drive_disc_dismantle.json:95`
- 挂载 task：`TaskDriveDiscDismantle`

**`DailySigninTarget`**（默认 `HouHouBakery`）

- 上游依据：`src/zzz_od/application/daily_signin/daily_signin_config.py:12 selected_sign`
- 落点节点：`hou_hou_bakery__盲盒`、`news_stand__刮刮卡`
- pipeline 依据：`app-zzz/src/main/assets/pi/resource/pipeline/hou_hou_bakery.json:28`；`app-zzz/src/main/assets/pi/resource/pipeline/news_stand.json:192`
- 挂载 task：`TaskDailySignin`

**`SuibianYumchaSin`**（默认 `On`）

- 上游依据：`src/zzz_od/application/suibian_temple/suibian_temple_config.py:16 yum_cha_sin`
- 落点节点：`suibian_temple_yumchaxian__按钮-定期采办`
- pipeline 依据：`app-zzz/src/main/assets/pi/resource/pipeline/suibian_temple_yumchaxian.json:60`
- 挂载 task：`TaskSuibianTemple`

**`SuibianYumchaRefresh`**（默认 `On`）

- 上游依据：`src/zzz_od/application/suibian_temple/suibian_temple_config.py:25 yum_cha_sin_period_refresh`
- 落点节点：`suibian_temple_yumchaxian__按钮-定期采办-刷新`
- pipeline 依据：`app-zzz/src/main/assets/pi/resource/pipeline/suibian_temple_yumchaxian.json:116`
- 挂载 task：`TaskSuibianTemple`

**`SuibianPawnshop`**（默认 `Both`）

- 上游依据：`src/zzz_od/application/suibian_temple/suibian_temple_config.py:133 pawnshop_omnicoin_enabled`；`src/zzz_od/application/suibian_temple/suibian_temple_config.py:154 pawnshop_crest_enabled`
- 落点节点：`suibian_temple_pawnshop__按钮-云纹徽-周期`、`suibian_temple_pawnshop__按钮-百通宝-周期`
- pipeline 依据：`app-zzz/src/main/assets/pi/resource/pipeline/suibian_temple_pawnshop.json:113`；`app-zzz/src/main/assets/pi/resource/pipeline/suibian_temple_pawnshop.json:93`
- 挂载 task：`TaskSuibianTemple`

**`SuibianBooboxPurchase`**（默认 `Off`）

- 上游依据：`src/zzz_od/application/suibian_temple/suibian_temple_config.py:97 boo_box_purchase_enabled`
- 落点节点：`suibian_temple_boobox__按钮-聘用`
- pipeline 依据：`app-zzz/src/main/assets/pi/resource/pipeline/suibian_temple_boobox.json:61`
- 挂载 task：`TaskSuibianTemple`

说明：这些 option 全部挂在 `resource/pipeline/tasks.json` 里的任务桩上（`TaskAutoBattle` / `TaskOneDragon` /
`TaskDailySignin` / `TaskDriveDiscDismantle` / `TaskSuibianTemple`），由 `task.option` 引用，
用户勾选后 `PiSelection.applyOption` 会把 `pipeline_override` 合并进 bundle，
`{"<node>": {"enabled": false}}` 会把对应节点关掉 —— 迁移后的节点原本都没有 `enabled` 字段，属纯新增，无副作用。

`TaskDailySignin` 在 `tasks.json` 里没有 `next`（上游 OneDragon 的每日签到目标由 `selected_sign` 决定，
迁移包没有路由节点），落点 `hou_hou_bakery__盲盒` / `news_stand__刮刮卡` 是真实节点，
但**当前没有任务链会走到它们**；这一点如实记录，未做额外接线。

## 7. 绝区零：没有迁移的配置项（91 条）

统一原因：**迁移包里没有对应的流程节点**，`pipeline_override` 无处落地；按任务约束不编造。

| 上游位置 | 键 | 未迁移原因 |
| --- | --- | --- |
| `battle_assistant/battle_assistant_config.py:12` | `dodge_assistant_config` | 指向另一个应用的配置名，迁移包里没有「闪避助手」流程 |
| `battle_assistant/battle_assistant_config.py:20` | `screenshot_interval` | 纯运行参数（截图轮询间隔），不是画面交互，没有节点可改 |
| `battle_assistant/battle_assistant_config.py:28` | `control_method` | PC 输入方式（键鼠 / 手柄），Android 上不存在这个选择 |
| `battle_assistant/battle_assistant_config.py:43` | `auto_battle_config` | 上游的「战斗配置」是 YAML 脚本，迁移包没有对应的战斗逻辑 agent |
| `battle_assistant/battle_assistant_config.py:52` | `use_merged_file` | YAML 载入细节，不影响画面 |
| `charge_plan/charge_plan_config.py:300` | `loop` | 体力计划里有「循环」按钮（charge_plan__按钮-循环），但本 option 只做了登录节点组，未纳入 |
| `charge_plan/charge_plan_config.py:308` | `daily_reset_plan_times` | 完成后的后处理逻辑，没有节点 |
| `charge_plan/charge_plan_config.py:316` | `last_daily_reset_dt` | 运行时记录，不是用户配置 |
| `charge_plan/charge_plan_config.py:324` | `skip_plan` | 跳过体力计划，迁移包里没有对应节点 |
| `charge_plan/charge_plan_config.py:332` | `double_reward` | 双倍奖励开关，迁移包里没有对应节点 |
| `charge_plan/charge_plan_config.py:340` | `combat_simulation_double_reward_config` | 复合配置，没有节点 |
| `charge_plan/charge_plan_config.py:349` | `restore_charge` | 恢复体力方式（Enum），迁移包没有对应节点 |
| `coffee/coffee_config.py:26` | `transport_point` | 传送点选择，需要地图寻路；迁移包只迁了画面识别，没有寻路 |
| `coffee/coffee_config.py:35` | `run_charge_plan_afterwards` | 跑完咖啡后触发另一个应用，跨应用编排，没有节点 |
| `commission_assistant/commission_assistant_config.py:33` | `pause_in_background` | 窗口失焦暂停，Android 前台应用概念不同 |
| `commission_assistant/commission_assistant_config.py:41` | `dialog_click_interval` | 点击间隔，纯运行参数 |
| `commission_assistant/commission_assistant_config.py:49` | `story_mode` | 剧情处理方式，迁移包里没有「跳过剧情」按钮节点 |
| `commission_assistant/commission_assistant_config.py:57` | `dialog_option` | 对话框选项位置，落在 commission_assistant__右侧选项区域 / __中间选项区域 两个区域节点上，但这两个节点的语义是「点哪里」而不是「选哪个」，改 enabled 会把整个区域点关掉，风险大于收益 |
| `commission_assistant/commission_assistant_config.py:65` | `dodge_config` | 同 dodge_assistant_config |
| `commission_assistant/commission_assistant_config.py:73` | `dodge_switch` | PC 快捷键，Android 没有键盘映射 |
| `commission_assistant/commission_assistant_config.py:81` | `auto_battle` | 战斗配置名，同 auto_battle_config |
| `commission_assistant/commission_assistant_config.py:89` | `auto_battle_switch` | PC 快捷键 |
| `commission_assistant/commission_assistant_config.py:97` | `sleep_after_empty_screen` | 等待时长，纯运行参数 |
| `devtools/operation_debug/operation_debug_config.py:17` | `operation_template` | devtools 应用在迁移包里没有 pipeline 文件 |
| `devtools/operation_debug/operation_debug_config.py:25` | `repeat_enabled` | devtools 应用在迁移包里没有 pipeline 文件 |
| `devtools/screenshot_helper/screenshot_helper_config.py:16` | `frequency_second` | devtools 应用在迁移包里没有 pipeline 文件 |
| `devtools/screenshot_helper/screenshot_helper_config.py:24` | `length_second` | devtools 应用在迁移包里没有 pipeline 文件 |
| `devtools/screenshot_helper/screenshot_helper_config.py:32` | `key_save` | devtools 应用在迁移包里没有 pipeline 文件 |
| `devtools/screenshot_helper/screenshot_helper_config.py:40` | `dodge_detect` | devtools 应用在迁移包里没有 pipeline 文件 |
| `devtools/screenshot_helper/screenshot_helper_config.py:48` | `screenshot_before_key` | devtools 应用在迁移包里没有 pipeline 文件 |
| `devtools/screenshot_helper/screenshot_helper_config.py:56` | `mini_map_angle_detect` | devtools 应用在迁移包里没有 pipeline 文件 |
| `hollow_zero/lost_void/lost_void_config.py:27` | `daily_plan_times` | 次数上限，需要逐轮计数，pipeline 没有对应节点 |
| `hollow_zero/lost_void/lost_void_config.py:35` | `weekly_plan_times` | 次数上限，同 daily_plan_times |
| `hollow_zero/lost_void/lost_void_config.py:43` | `extra_task` | 额外任务类型选择，没有节点 |
| `hollow_zero/lost_void/lost_void_config.py:55` | `mission_name` | 副本名，靠 OCR 匹配，没有对应节点 |
| `hollow_zero/lost_void/lost_void_config.py:63` | `challenge_config` | 挑战配置 YAML 名，迁移包没有对应逻辑 |
| `hollow_zero/lost_void/lost_void_challenge_config.py:110` | `predefined_team_idx` | 配队索引，需要队伍选择界面建模，未迁移 |
| `hollow_zero/lost_void/lost_void_challenge_config.py:122` | `choose_team_by_priority` | 配队策略，同 predefined_team_idx |
| `hollow_zero/lost_void/lost_void_challenge_config.py:134` | `manually_choose_agent` | 手动选代理人，同 predefined_team_idx |
| `hollow_zero/lost_void/lost_void_challenge_config.py:146` | `team_info` | 队伍成员列表，同 predefined_team_idx |
| `hollow_zero/lost_void/lost_void_challenge_config.py:154` | `auto_battle` | 战斗配置名 |
| `hollow_zero/lost_void/lost_void_challenge_config.py:166` | `artifact_priority_new` | 鸣徽优先级，需要空洞内部商店/掉落建模 |
| `hollow_zero/lost_void/lost_void_challenge_config.py:174` | `artifact_priority` | 鸣徽优先级，同上 |
| `hollow_zero/lost_void/lost_void_challenge_config.py:196` | `artifact_priority_2` | 鸣徽优先级，同上 |
| `hollow_zero/lost_void/lost_void_challenge_config.py:208` | `region_type_priority` | 区域类型优先级，同上 |
| `hollow_zero/lost_void/lost_void_challenge_config.py:220` | `period_buff_no` | 周期增益编号，同上 |
| `hollow_zero/lost_void/lost_void_challenge_config.py:228` | `buy_only_priority_1` | 购买策略，同上 |
| `hollow_zero/lost_void/lost_void_challenge_config.py:236` | `buy_only_priority_2` | 购买策略，同上 |
| `hollow_zero/lost_void/lost_void_challenge_config.py:247` | `store_gold` | 购买策略，同上 |
| `hollow_zero/lost_void/lost_void_challenge_config.py:258` | `store_blood` | 购买策略，同上 |
| `hollow_zero/lost_void/lost_void_challenge_config.py:269` | `store_blood_min` | 购买策略，同上 |
| `hollow_zero/lost_void/lost_void_challenge_config.py:277` | `investigation_strategy` | 调查战略名，同上 |
| `hollow_zero/lost_void/lost_void_challenge_config.py:288` | `chase_new_mode` | 玩法开关，同上 |
| `hollow_zero/withered_domain/withered_domain_config.py:35` | `mission_name` | 副本名，同上 |
| `hollow_zero/withered_domain/withered_domain_config.py:43` | `challenge_config` | 挑战配置名，同上 |
| `hollow_zero/withered_domain/withered_domain_config.py:55` | `weekly_plan_times` | 次数上限，同上 |
| `hollow_zero/withered_domain/withered_domain_config.py:63` | `daily_plan_times` | 次数上限，同上 |
| `hollow_zero/withered_domain/withered_domain_config.py:71` | `extra_task` | 额外任务类型，同上 |
| `hollow_zero/withered_domain/withered_domain_config.py:79` | `extra_exit` | 额外退出条件，同上 |
| `intel_board/intel_board_config.py:18` | `predefined_team_idx` | 配队索引，未迁移 |
| `intel_board/intel_board_config.py:27` | `auto_battle_config` | 战斗配置名 |
| `intel_board/intel_board_config.py:36` | `exp_grind_mode` | 刷经验模式，迁移包没有对应节点 |
| `life_on_line/life_on_line_config.py:18` | `daily_plan_times` | 次数上限，未迁移 |
| `life_on_line/life_on_line_config.py:31` | `predefined_team_idx` | 配队索引，未迁移 |
| `notorious_hunt/notorious_hunt_config.py:76` | `weekly_challenge_start_weekday` | 起始星期，需要日期判断，pipeline 没有对应节点 |
| `notorious_hunt/notorious_hunt_config.py:84` | `loop` | 循环开关，迁移包里没有对应节点 |
| `random_play/random_play_config.py:29` | `transport_point` | 传送点选择，需要地图寻路 |
| `random_play/random_play_config.py:37` | `agent_name_1` | 随机玩法的代理人，需要选人界面建模 |
| `random_play/random_play_config.py:45` | `agent_name_2` | 同上 |
| `shiyu_defense/shiyu_defense_config.py:38` | `team_list` | 配队列表，需要队伍选择界面建模 |
| `shiyu_defense/shiyu_defense_config.py:125` | `critical_max_node_idx` | 高难节点索引，需要关卡建模 |
| `suibian_temple/suibian_temple_config.py:34` | `adventure_duration` | 游历时长，落在 suibian_temple_adventure__弹窗-游历时间选择 上，但该弹窗没有独立选项节点，只能整块开关，做不到「选哪个时长」 |
| `suibian_temple/suibian_temple_config.py:43` | `adventure_mission_1` | 游历委托选择，同 adventure_duration |
| `suibian_temple/suibian_temple_config.py:52` | `adventure_mission_2` | 同上 |
| `suibian_temple/suibian_temple_config.py:61` | `adventure_mission_3` | 同上 |
| `suibian_temple/suibian_temple_config.py:70` | `adventure_mission_4` | 同上 |
| `suibian_temple/suibian_temple_config.py:79` | `craft_drag_times` | 制造拖拽次数，纯运行参数 |
| `suibian_temple/suibian_temple_config.py:88` | `good_goods_purchase_enabled` | 好物铺购买，迁移包里没有好物铺画面 |
| `suibian_temple/suibian_temple_config.py:106` | `boo_box_adventure_price` | 邦布盲盒价格档位，落在 __区域-邦布类型 之外的文本节点上，没有独立选择节点 |
| `suibian_temple/suibian_temple_config.py:115` | `boo_box_craft_price` | 同上 |
| `suibian_temple/suibian_temple_config.py:124` | `boo_box_sell_price` | 同上 |
| `suibian_temple/suibian_temple_config.py:175` | `pawnshop_crest_unlimited_denny_enabled` | 当铺「不限量兑换丁尼」子开关，迁移包里没有对应节点 |
| `suibian_temple/suibian_temple_config.py:184` | `auto_manage_enabled` | 自动管理（种田逻辑），迁移包没有对应画面 |
| `world_patrol/world_patrol_config.py:22` | `auto_battle` | 战斗配置名 |
| `world_patrol/world_patrol_config.py:30` | `route_list` | 路线文件选择，world_patrol 在迁移包里没有 pipeline 文件 |
| `world_patrol/world_patrol_config.py:38` | `ui_disappear_action` | UI 消失后的处置，同上 |
| `world_patrol/world_patrol_config.py:46` | `ui_disappear_seconds` | 等待时长，同上 |
| `world_patrol/world_patrol_config.py:54` | `route_retry_times` | 重试次数，同上 |
| `world_patrol/world_patrol_config.py:62` | `route_retry_action` | 重试策略，同上 |
| `world_patrol/world_patrol_config.py:70` | `daily_loop_count` | 循环次数，同上 |
| `world_patrol/world_patrol_config.py:78` | `loop_interval_seconds` | 循环间隔，同上 |

被跳过的大类是：配队与自动战斗脚本名（`predefined_team_idx` / `team_info` / `team_list` / `auto_battle*`）、
键位绑定（`dodge_switch` / `auto_battle_switch` / `key_save`）、各类时间与次数上限
（`daily_plan_times` / `weekly_plan_times` / `craft_drag_times` / `loop_interval_seconds`）、
空洞玩法策略（`mission_name` / `challenge_config` / `artifact_priority*` / `store_*` / `investigation_strategy`）、
传送点（`transport_point`）、以及 devtools 全部键。这些都由 OneDragon 自己的 PC 端配置/GUI 承载，
在 MaaFramework 的 pipeline 里没有等价落点。

## 8. 绝区零是否需要区服选择

**需要。** 国服 / B服 / 国际服是三个不同的游戏客户端，登录画面节点（enter_game__国服-* / enter_game__B服* / enter_game__国际服-*）都已迁入，因此需要区服选择。

- 实现方式：做成 GameRegion 选项，只切换登录节点组的 enabled，不改 resource 名称（仍是「国服」/ ./resource），也不加 StartApp。
- 缺口：上游是 PC 工具，靠 game_path 找 exe，仓库里没有硬编码 Android 包名，所以迁移包里没有真实包名可用，无法生成 StartApp 覆盖。

因此本次只把国服 / B服 / 国际服做成 `GameRegion` 三选一（开关三组登录节点），
`resource` 仍只有 `国服`（`./resource`），**没有**新增 `StartApp`。

## 9. 改动的文件

- `MaaPocket/scripts/migrate_maaend.py`：新增 `INTENT_ACTION_TYPES` / `iter_intent_actions` /
  `normalise_android_intents`；`Migration.__init__` 加 `intent_rewrites`；`copy_trees` 接入归一；
  报告加 `android_intents`；`build_limitations` 加汇总行与 `## 9.`。
- `MaaPocket/scripts/migrate_onedragon_zzz.py`：新增 `GAME_REGION_NODES` / `_region_case` / `_toggle_case` /
  `_pick_case` / `TASK_OPTIONS`（9 项）/ `UNMAPPED_CONFIG_KEYS`（91 条）/ `Migration.build_option_defs`；
  `build_interface` 改为输出真实 `option` 映射、`task.option` 与 locale 的 `option` 段；报告加 `options`。
- `MaaPocket/scripts/check_pi_options.py`（新增工具脚本）：Python 复刻 `PiRepository.readAndMerge`，
  统计合并后的 option/task；`--upstream` 做上游 diff；`--validate` 做全量自检。
- 产物：`app-endfield/src/main/assets/pi/**`（`interface.json` 未变；变更 `tasks/AndroidOpenGame.json`、
  `resource_android/KNOWN_LIMITATIONS.md`、`migration_report.json`）、
  `app-zzz/src/main/assets/pi/**`（变更 `interface.json`、`locales/interface/zh_cn.json`、`migration_report.json`）。

未改动任何 `.kt` / `.gradle.kts`；未 `git add` / `commit` / `push`。

## 10. 已知未解决

1. `StopApp` 在 Android 上是静默 no-op（`core/src/main/cpp/bridge_input.cpp` `default: return 0`）——
   `CloseGame` 不会真的关游戏，需要改 C++ 或改走宿主 `app.stop`。不在本任务可改范围。
2. 宿主 App 没有任何代码调用 `app.start`（`RemoteProtocol.kt:139` / `RemoteEngine.kt:195` 只有定义与注册）——
   拉起游戏完全依赖 pipeline 的 `StartApp`。
3. 设备上的 `PiInstaller` 只按 `interface.json.version + versionCode` 判断是否重新解包，
   升级 APK 但版本号不变时会复用旧解包目录。若用户看到的是旧包，需要升版本号或清应用数据。
4. 绝区零没有 Android 包名可抄（上游是 PC 工具，靠 `game_path` 找 exe），
   所以区服 option 只能做成登录节点开关，无法生成 `StartApp`。
5. 绝区零 `TaskDailySignin` 等 13 个 task 在 `tasks.json` 里没有 `next`，
   是上游就缺少对应画面信息的声明式桩，本次没有补画面接线。

