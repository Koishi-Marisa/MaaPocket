#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把原 PC 项目（March7thAssistant 迁移包）转换成 MaaKotlinUI 的资源布局。

输入：`app-hsr/src/main/assets/pi`（MaaPocket 里已经迁移好的 PI-V2 资源包）
输出：`maa-kotlin-ui/app/src/main/assets/`（MaaKotlinUI 只认 `resource/base`）

转换内容：
  * `resource/pipeline/**`  -> `resource/base/pipeline/**`
  * `resource/image/**`     -> `resource/base/image/**`
  * `resource/locale/**`    -> `resource/base/locale/**`
  * `interface.json`        -> 重写 controller/resource/display 字段，并追加手机端任务入口
  * `adaptation/template_sets.json` -> 基线声明为 1920×1080（PC 模板 1:1 使用）

刻意不做的事：
  * 不重采样任何 PC 模板（用户要求「直接用 PC 资源」；虚拟屏必须设 1080p）；
  * 不复制 `resource_bilibili` 覆盖层（MaaKotlinUI 单资源包，复制会把 B服分支强加给国服）。

用法::

    python scripts/sync_pc_hsr_pack.py
    python scripts/sync_pc_hsr_pack.py --dry-run
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_SOURCE = PROJECT_ROOT.parent / "app-hsr/src/main/assets/pi"
DEFAULT_DEST = PROJECT_ROOT / "app/src/main/assets"

CLAIM_ENTRY = "HSR_Daily_ClaimRewards"

STARTUP_JSON = {
    "start_up": {
        "recognition": "DirectHit",
        "action": "StartApp",
        "package": "com.miHoYo.hkrpg",
    }
}

TEMPLATE_SETS = {
    "baseline": [1920, 1080],
    "sets": ["1080p"],
    "nodes": {},
}


def log(msg: str) -> None:
    print(msg, flush=True)


def copy_tree(src: Path, dst: Path) -> int:
    count = 0
    for path in src.rglob("*"):
        if not path.is_file():
            continue
        rel = path.relative_to(src)
        out = dst / rel
        out.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, out)
        count += 1
    return count


def build_interface(src: dict, has_locale: bool) -> dict:
    claim_task = {
        "name": "崩铁·领取每日奖励（PC 模板）",
        "entry": CLAIM_ENTRY,
        "description": (
            "手机端最小入口：要求游戏已停在邮箱界面。"
            "识别 screen/mail.png → 点击 zh_CN/reward/mail/receive_all.png（0.8）"
            "→ 等 2 秒 → 最多点 10 次 zh_CN/base/click_close.png → BACK 退出。"
            "模板全部直接复用 PC 资源，虚拟屏需设为 1080p。"
        ),
        "default_check": False,
        "group": ["reward"],
        # 让用户在任务面板里直接选国服 / B服；保存后 GamePackageResolver 会读这个值
        "option": ["Server"],
    }

    claim_in_mailbox = {
        "name": "崩铁·领取奖励（已打开邮箱）",
        "entry": "HSR_Daily_ClickReceiveAll",
        "description": (
            "手动把游戏停在邮箱界面后运行：只做「全部领取」→ 关奖励弹窗 → BACK。"
            "用于 main 界面找不到「邮箱」按钮的机型/分辨率，跳过入口识别。"
        ),
        "default_check": False,
        "group": ["reward"],
        "option": ["Server"],
    }

    phone_daily = {
        "name": "崩铁·完成每日（邮件+委托+清体力）",
        "entry": "HSR_Phone_CompleteDaily",
        "description": (
            "手机端 720p 日常：手机菜单进邮件全部领取、委托领取奖励、每日实训领取，"
            "再进星际和平指南→生存索引→拟造花萼，挑战并按「再来一次」循环，"
            "体力不足/达到 12 次后退出。需要虚拟屏设为 720p。"
        ),
        "default_check": True,
        "group": ["daily"],
        "option": ["Server"],
    }

    phone_startup = {
        "name": "手机·启动游戏（B服）",
        "entry": "HSR_Phone_StartUpGame",
        "description": (
            "StartApp 启动 B服 客户端，等待 30 秒，OCR 点击「点击进入」，"
            "再等主界面手机菜单图标出现。适合单独用于冷启动。"
        ),
        "default_check": False,
        "group": ["startup"],
        "option": ["Server"],
    }

    # 原版 Task_start_up_game 在手机端实测能进主界面，默认先跑它，再跑每日。
    # 手机自建启动任务易在加载页空转，不再暴露给用户。
    tasks = [claim_task, claim_in_mailbox, phone_daily]
    pc_startup = None
    for task in src.get("task", []):
        if task.get("entry") == CLAIM_ENTRY:
            continue
        t = dict(task)
        t["default_check"] = False
        if t.get("entry") == "Task_start_up_game":
            t["default_check"] = True
            t["name"] = "启动游戏（先运行，再跑每日）"
            pc_startup = t
        else:
            tasks.append(t)
    if pc_startup is not None:
        tasks.insert(2, pc_startup)

    out = dict(src)
    out["name"] = "MaaPocket 崩铁（PC 资源直用）"
    out["version"] = "0.1.0-pc"
    out["label"] = "崩坏：星穹铁道"
    out["title"] = "MaaPocket · 崩坏：星穹铁道"
    out["description"] = (
        "从 March7thAssistant 迁移的完整 PC 资源包，转换为 MaaKotlinUI 的 "
        "resource/base 布局后直接使用。模板为 PC 1920×1080 截图，"
        "因此虚拟屏必须设为 1080p；未在手机端重采前，识别成功率不保证。"
    )
    out["welcome"] = (
        "PC 模板直用：请先在设置页把虚拟屏分辨率选为 1080p。"
        "任务列表来自原 PC 项目；键盘/OCR 相关的节点在手机上仍可能失败。"
    )
    out["controller"] = [
        {
            "name": "Android",
            "type": "Adb",
            "label": "Android（Shizuku / Root）",
            "display_long_side": 1920,
        }
    ]
    out["resource"] = [
        {
            "name": "base",
            "label": "PC 资源（1920×1080）",
            "path": ["./resource/base"],
        }
    ]
    out["languages"] = {"zh_cn": "./resource/locale/zh_cn.json"} if has_locale else {}
    out["task"] = tasks
    out["agent"] = []
    return out


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE, help="原 PC 迁移包目录（pi/）")
    parser.add_argument("--dest", type=Path, default=DEFAULT_DEST, help="MaaKotlinUI assets 目录")
    parser.add_argument("--dry-run", action="store_true", help="只打印计划，不写文件")
    args = parser.parse_args(argv)

    src = args.source.resolve()
    dest = args.dest.resolve()
    src_resource = src / "resource"
    if not (src / "interface.json").is_file() or not src_resource.is_dir():
        print(f"[ERROR] 源资源包不完整：{src}", file=sys.stderr)
        return 1

    dest_base = dest / "resource/base"
    pipeline_dirs = [p for p in (src_resource / "pipeline").iterdir() if p.is_dir()]
    image_src = src_resource / "image"
    locale_src = src_resource / "locale"

    log(f"[INFO] source = {src}")
    log(f"[INFO] dest   = {dest}")
    log(f"[INFO] pipeline 子目录: {', '.join(sorted(p.name for p in pipeline_dirs))}")

    if args.dry_run:
        log("[DRY] 将复制 pipeline/image/locale 并重写 interface.json")
        return 0

    # 1) pipeline：先删掉 PC 来源的子目录，再整目录复制（幂等；保留 hsr/ 与 Startup.json）
    for pdir in pipeline_dirs:
        target = dest_base / "pipeline" / pdir.name
        if target.exists():
            shutil.rmtree(target)
        n = copy_tree(pdir, target)
        log(f"[OK] pipeline/{pdir.name}: {n} 个文件")

    # 2) image：覆盖同名文件，保留以后手机端新增的模板
    n = copy_tree(image_src, dest_base / "image")
    log(f"[OK] image: {n} 个文件")

    # 3) locale
    has_locale = (locale_src / "zh_cn.json").is_file()
    if has_locale:
        n = copy_tree(locale_src, dest_base / "locale")
        log(f"[OK] locale: {n} 个文件")

    # 4) Startup.json（MaaKotlinUI 的 prelaunchGame 读 start_up.package）
    startup = dest_base / "pipeline/Startup.json"
    startup.parent.mkdir(parents=True, exist_ok=True)
    startup.write_text(json.dumps(STARTUP_JSON, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    log(f"[OK] {startup.relative_to(dest)}")

    # 5) 适配层清单：PC 模板基线 1920×1080
    manifest = dest_base / "adaptation/template_sets.json"
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text(json.dumps(TEMPLATE_SETS, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    log(f"[OK] {manifest.relative_to(dest)}")

    # 6) interface.json
    src_interface = json.loads((src / "interface.json").read_text(encoding="utf-8"))
    out_interface = build_interface(src_interface, has_locale)
    (dest / "interface.json").write_text(
        json.dumps(out_interface, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    log(f"[OK] interface.json（{len(out_interface['task'])} 个任务，"
        f"{len(out_interface.get('option', {}))} 个选项）")

    log("[DONE] 现在把虚拟屏分辨率设为 720p 再构建")
    return 0


if __name__ == "__main__":
    sys.exit(main())
