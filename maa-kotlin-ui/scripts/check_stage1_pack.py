#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""阶段 1 资源包静态校验（不连真机也能跑）。

检查 `app/src/main/assets` 下的：
1. `interface.json` 能解析，`task[].entry` 非空且不重复；
2. 所有 pipeline JSON 能解析，节点名全局唯一；
3. `next` / `on_error` 引用的节点都存在（不存在算 error，MaaFramework 会直接报节点缺失）；
4. `template` 引用的文件能在 `resource/base/image/` 找到（找不到只算 warning —— 模板要真机采集后才有）；
5. 找到的模板短边 >= 64px（MaaFramework 官方建议）；
6. `adaptation/template_sets.json` 里登记的节点必须真的在 pipeline 里，登记的文件必须存在（warning）；
7. 非全屏 `roi` 必须落在 720p 基线内，否则必然匹配不到。

用法::

    python scripts/check_stage1_pack.py [--assets app/src/main/assets]

退出码 0 = 没有 error，1 = 有 error。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Dict, List, Tuple

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ASSETS = PROJECT_ROOT / "app/src/main/assets"
# 当前资源包直接复用原 PC 项目的 1920×1080 模板与 roi。
BASELINE = (1920, 1080)
MIN_TEMPLATE_SIDE = 64

errors: List[str] = []
warnings: List[str] = []
oks: List[str] = []


def ok(msg: str) -> None:
    oks.append(msg)


def warn(msg: str) -> None:
    warnings.append(msg)


def err(msg: str) -> None:
    errors.append(msg)


def load_json(path: Path):
    try:
        with path.open("r", encoding="utf-8") as fh:
            return json.load(fh)
    except Exception as exc:  # noqa: BLE001
        err(f"{path}: 无法解析 JSON：{exc}")
        return None


def collect_pipeline(pipeline_root: Path) -> Dict[str, Tuple[Path, dict]]:
    nodes: Dict[str, Tuple[Path, dict]] = {}
    for path in sorted(pipeline_root.rglob("*.json")):
        data = load_json(path)
        if not isinstance(data, dict):
            continue
        for name, node in data.items():
            if not isinstance(node, dict):
                err(f"{path}: 节点 {name} 不是对象")
                continue
            if name in nodes:
                err(f"节点名重复：{name}（{nodes[name][0]} 与 {path}）")
                continue
            nodes[name] = (path, node)
    return nodes


def check_interface(assets: Path) -> None:
    path = assets / "interface.json"
    if not path.is_file():
        err(f"缺少 {path}")
        return
    data = load_json(path)
    if not isinstance(data, dict):
        return
    tasks = data.get("task") or []
    if not tasks:
        err("interface.json 没有 task")
        return
    seen = set()
    for task in tasks:
        entry = str(task.get("entry", "")).strip()
        name = str(task.get("name", "")).strip()
        if not entry:
            err(f"任务 {name!r} 的 entry 为空")
        if entry in seen:
            err(f"task entry 重复：{entry}")
        seen.add(entry)
    ok(f"interface.json：{len(tasks)} 个任务")


def check_pipeline(pipeline_root: Path) -> Dict[str, Tuple[Path, dict]]:
    if not pipeline_root.is_dir():
        err(f"缺少 pipeline 目录 {pipeline_root}")
        return {}
    nodes = collect_pipeline(pipeline_root)
    if not nodes:
        err("pipeline 目录里没有任何节点")
        return nodes

    for name, (path, node) in nodes.items():
        for key in ("next", "on_error"):
            for ref in node.get(key) or []:
                if ref not in nodes:
                    err(f"{name}: {key} 引用了不存在的节点 {ref}")
        roi = node.get("roi")
        if isinstance(roi, list) and len(roi) == 4:
            x, y, w, h = (int(v) for v in roi)
            if w > 0 and h > 0:
                if x < 0 or y < 0 or x + w > BASELINE[0] or y + h > BASELINE[1]:
                    err(f"{name}: roi={roi} 超出 1920×1080 基线 {BASELINE}")
    ok(f"pipeline：{len(nodes)} 个节点，引用完整")
    return nodes


def check_templates(assets: Path, nodes: Dict[str, Tuple[Path, dict]]) -> None:
    image_root = assets / "resource/base/image"
    referenced = 0
    missing = 0
    small: List[str] = []
    for name, (_path, node) in nodes.items():
        template = node.get("template")
        if not isinstance(template, str) or not template:
            continue
        referenced += 1
        candidates = [image_root / template]
        # 也接受任一分辨率目录下的同名文件（采集脚本生成）
        rel = Path(template)
        for set_dir in (rel.parent / "720p", rel.parent / "1080p"):
            candidates.append(image_root / set_dir / rel.name)
        existing = [p for p in candidates if p.is_file()]
        if not existing:
            missing += 1
            warn(f"{name}: 模板尚未采集，缺少 {image_root / template}")
            continue
        for p in existing:
            try:
                from PIL import Image

                with Image.open(p) as im:
                    w, h = im.size
                if min(w, h) < MIN_TEMPLATE_SIDE:
                    small.append(f"{name}={w}x{h}")
            except ImportError:  # pragma: no cover
                pass
            except Exception as exc:  # noqa: BLE001
                err(f"{name}: 模板 {p} 无法读取：{exc}")
    ok(f"模板引用：{referenced} 个，已采集 {referenced - missing} 个")
    if small:
        preview = "、".join(small[:5]) + (" …" if len(small) > 5 else "")
        warn(f"{len(small)} 个模板短边 < {MIN_TEMPLATE_SIDE}px（PC 迁移资产，属预期）：{preview}")
    if missing:
        warn(f"共 {missing} 个模板缺失（见 docs/HSR_STAGE1.md）")


def check_manifest(assets: Path, nodes: Dict[str, Tuple[Path, dict]]) -> None:
    path = assets / "resource/base/adaptation/template_sets.json"
    if not path.is_file():
        warn(f"缺少模板集清单 {path}，适配层将按资源包原值运行")
        return
    data = load_json(path)
    if not isinstance(data, dict):
        return
    baseline = data.get("baseline") or list(BASELINE)
    if list(baseline) != list(BASELINE):
        warn(f"清单 baseline={baseline} 与适配层基线 {list(BASELINE)} 不一致")
    node_map = data.get("nodes") or {}
    image_root = assets / "resource/base/image"
    for node, variants in node_map.items():
        if node not in nodes:
            err(f"清单登记了不存在的节点：{node}")
            continue
        if not isinstance(variants, dict) or not variants:
            err(f"清单节点 {node} 没有分辨率变体")
            continue
        for set_key, rel in variants.items():
            if not (image_root / rel).is_file():
                warn(f"清单节点 {node} 的 {set_key} 模板尚未生成：{rel}")
    ok(f"模板集清单：{len(node_map)} 个节点")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--assets", type=Path, default=DEFAULT_ASSETS)
    args = parser.parse_args(argv)
    assets = args.assets.resolve()

    check_interface(assets)
    nodes = check_pipeline(assets / "resource/base/pipeline")
    check_templates(assets, nodes)
    check_manifest(assets, nodes)

    for line in oks:
        print(f"[OK]   {line}")
    for line in warnings:
        print(f"[WARN] {line}")
    for line in errors:
        print(f"[FAIL] {line}")
    print(f"\n汇总：{len(oks)} ok / {len(warnings)} warn / {len(errors)} error")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
