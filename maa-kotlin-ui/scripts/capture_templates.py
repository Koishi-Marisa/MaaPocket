#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「手机虚拟屏无损截图」裁剪成 MaaFramework 模板，并生成多分辨率清单。

为什么必须用这个脚本：
    PC 客户端截图与手机客户端的 DPI / UI 缩放 / 安全区都不同，直接复用 PC 模板
    是当前识别失败的首要原因。模板必须来自 MaaKotlinUI 的「截图」按钮保存的
    虚拟屏原始 PNG（`.../files/screenshots/screenshot_*.png`，分辨率等于虚拟屏）。

用法::

    # 1) 先给截图叠一层坐标网格，方便量取矩形（可选）
    python scripts/capture_templates.py --grid shots/mail_720p.png --grid-out shots/mail_720p_grid.png

    # 2) 按 spec 裁剪（spec 里 rect 用截图自身像素坐标）
    python scripts/capture_templates.py --spec packs/hsr/stage1/mail_720p.json

    # 3) 生成 1080p 那一套（换一张 1080p 截图和对应 rect）
    python scripts/capture_templates.py --spec packs/hsr/stage1/mail_1080p.json

    # 4) 自检：模板在自己的源截图上应 >= 0.90，在别的界面截图上应 <= 0.60
    python scripts/capture_templates.py --spec packs/hsr/stage1/mail_720p.json --check

spec 示例：:

    {
      "source": "shots/mail_720p.png",
      "set": "720p",
      "baseline": [1280, 720],
      "nodes": {
        "HSR_Daily_EnterMailbox": {
          "asset": "hsr/daily_claim/mailbox_anchor",
          "rect": [640, 40, 160, 120]
        },
        "HSR_Daily_ClickClaimAll": {
          "asset": "hsr/daily_claim/claim_all_button",
          "rect": [1000, 40, 240, 96]
        },
        "HSR_Daily_PopupConfirm": {
          "asset": "hsr/daily_claim/reward_confirm",
          "rect": [560, 520, 160, 80]
        }
      }
    }

约定：
    * `asset` 是不带分辨率目录的规范路径（相对 `resource/base/image/`）。
    * 脚本写到 `image/<asset 目录>/<set>/<文件名>.png`；
      若 `set` 就是基线集（默认 720p），同时再写一份不带 set 目录的规范文件，
      这样即使适配层没生效，pipeline 也能直接命中基线模板。
    * 模板缩放后短边必须 >= 64px（MaaFramework 官方建议），否则报错。
    * 清单写到 `resource/base/adaptation/template_sets.json`，供运行期 TemplateSelector 使用。

依赖：Pillow（`pip install pillow`）。`--check` 额外需要 opencv-python，缺失时只跳过自检。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Dict, List, Tuple

try:
    from PIL import Image, ImageDraw
except ImportError:  # pragma: no cover
    print("[ERROR] 需要 Pillow：pip install pillow", file=sys.stderr)
    sys.exit(2)

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_IMAGE_DIR = PROJECT_ROOT / "app/src/main/assets/resource/base/image"
DEFAULT_MANIFEST = PROJECT_ROOT / "app/src/main/assets/resource/base/adaptation/template_sets.json"

# 虚拟屏设置页给出的两种分辨率；新增分辨率时同步加到这里。
SET_SIZES: Dict[str, Tuple[int, int]] = {
    "720p": (1280, 720),
    "1080p": (1920, 1080),
}

MIN_SIDE = 64


def load_json(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as fh:
        return json.load(fh)


def save_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=2)
        fh.write("\n")


def parse_rect(value) -> Tuple[int, int, int, int]:
    if not isinstance(value, list) or len(value) != 4:
        raise ValueError(f"rect 必须是 [x, y, w, h]，实际为 {value!r}")
    x, y, w, h = (int(v) for v in value)
    if w <= 0 or h <= 0:
        raise ValueError(f"rect 宽高必须为正：{value!r}")
    return x, y, w, h


def crop_and_scale(img: Image.Image, rect: Tuple[int, int, int, int], set_key: str) -> Image.Image:
    sw, sh = img.size
    x, y, w, h = rect
    if x < 0 or y < 0 or x + w > sw or y + h > sh:
        raise ValueError(f"rect {rect} 超出截图范围 {sw}x{sh}")
    crop = img.crop((x, y, x + w, y + h))
    target_w, target_h = SET_SIZES[set_key]
    scale = target_h / sh
    if abs((sw / sh) - (target_w / target_h)) > 0.02:
        print(f"  [WARN] 截图宽高比 {sw}x{sh} 与 {set_key} 的 16:9 不一致，按短边缩放")
    out_w = max(1, round(crop.width * scale))
    out_h = max(1, round(crop.height * scale))
    return crop.resize((out_w, out_h), Image.LANCZOS)


def grid_overlay(src: Path, dst: Path, step: int) -> None:
    img = Image.open(src).convert("RGB")
    draw = ImageDraw.Draw(img)
    w, h = img.size
    for x in range(0, w, step):
        draw.line([(x, 0), (x, h)], fill=(255, 0, 0), width=1)
        draw.text((x + 2, 2), str(x), fill=(255, 0, 0))
    for y in range(0, h, step):
        draw.line([(0, y), (w, y)], fill=(0, 255, 0), width=1)
        draw.text((2, y + 2), str(y), fill=(0, 255, 0))
    dst.parent.mkdir(parents=True, exist_ok=True)
    img.save(dst)
    print(f"[OK] 网格图已保存：{dst}（红= x，绿= y，步长 {step}px）")


def manifest_set_key_for_baseline(baseline: Tuple[int, int]) -> str:
    for key, size in SET_SIZES.items():
        if size == baseline:
            return key
    return "720p"


def update_manifest(manifest_path: Path, set_key: str, node_entries: Dict[str, str], baseline: Tuple[int, int]) -> None:
    manifest = {}
    if manifest_path.is_file():
        manifest = load_json(manifest_path)
    manifest.setdefault("baseline", list(baseline))
    sets: List[str] = list(manifest.get("sets") or [])
    if set_key not in sets:
        sets.append(set_key)
    # 基线集排第一，其余按数值排序
    sets.sort(key=lambda k: SET_SIZES.get(k, (0, 0))[1])
    manifest["sets"] = sets
    nodes = manifest.setdefault("nodes", {})
    for node, rel in node_entries.items():
        node_map = nodes.setdefault(node, {})
        node_map[set_key] = rel
    save_json(manifest_path, manifest)


def run_spec(spec_path: Path, image_dir: Path, manifest_path: Path, allow_small: bool, check: bool) -> int:
    spec = load_json(spec_path)
    source = Path(spec["source"])
    if not source.is_absolute():
        source = (spec_path.parent / source).resolve()
    if not source.is_file():
        print(f"[ERROR] 截图不存在：{source}", file=sys.stderr)
        return 1

    set_key = str(spec.get("set", "720p"))
    if set_key not in SET_SIZES:
        print(f"[ERROR] 未知模板集 {set_key!r}，可选：{', '.join(SET_SIZES)}", file=sys.stderr)
        return 1
    baseline = tuple(int(v) for v in spec.get("baseline", [1280, 720]))
    baseline_set = manifest_set_key_for_baseline(baseline)

    img = Image.open(source).convert("RGBA")
    print(f"[INFO] 源截图 {source} = {img.size[0]}x{img.size[1]}，目标集 {set_key}")
    if set_key not in (baseline_set, "1080p"):
        print(f"  [WARN] {set_key} 不是基线集 {baseline_set}")

    nodes = spec.get("nodes") or {}
    if not nodes:
        print("[ERROR] spec.nodes 为空", file=sys.stderr)
        return 1

    manifest_entries: Dict[str, str] = {}
    failed = False
    for node, item in nodes.items():
        asset = str(item.get("asset", "")).strip()
        if not asset:
            print(f"  [ERROR] {node}: 缺少 asset", file=sys.stderr)
            failed = True
            continue
        try:
            rect = parse_rect(item.get("rect"))
            template = crop_and_scale(img, rect, set_key)
        except Exception as exc:  # noqa: BLE001
            print(f"  [ERROR] {node}: {exc}", file=sys.stderr)
            failed = True
            continue

        if min(template.size) < MIN_SIDE and not allow_small:
            print(
                f"  [ERROR] {node}: 模板缩放后 {template.size[0]}x{template.size[1]}，"
                f"短边 < {MIN_SIDE}px（换一块更大的 UI 元素，或加 --allow-small 仅作调试）",
                file=sys.stderr,
            )
            failed = True
            continue

        asset_path = Path(asset)
        stem, parent = asset_path.stem, asset_path.parent
        rel = (parent / set_key / f"{stem}.png").as_posix()
        out = image_dir / rel
        out.parent.mkdir(parents=True, exist_ok=True)
        template.save(out)
        manifest_entries[node] = rel

        if set_key == baseline_set:
            canonical = image_dir / parent / f"{stem}.png"
            canonical.parent.mkdir(parents=True, exist_ok=True)
            template.save(canonical)

        print(f"  [OK] {node}: rect={rect} -> {out} ({template.size[0]}x{template.size[1]})")

    if manifest_entries:
        update_manifest(manifest_path, set_key, manifest_entries, baseline)
        print(f"[OK] 模板集清单已更新：{manifest_path}")

    if check and not failed:
        run_check(source, nodes, image_dir, set_key)
    return 1 if failed else 0


def run_check(source: Path, nodes: dict, image_dir: Path, set_key: str) -> None:
    try:
        import cv2  # type: ignore
    except ImportError:
        print("[SKIP] --check 需要 opencv-python（pip install opencv-python）")
        return

    src = cv2.imread(str(source))
    if src is None:
        print(f"[WARN] cv2 无法读取 {source}")
        return
    target_w, target_h = SET_SIZES[set_key]
    if (src.shape[1], src.shape[0]) != (target_w, target_h):
        src = cv2.resize(src, (target_w, target_h), interpolation=cv2.INTER_AREA)
    print("[CHECK] TM_CCOEFF_NORMED 自检（同图应 >= 0.90）")
    for node, item in nodes.items():
        asset = str(item.get("asset", "")).strip()
        rel = (Path(asset).parent / set_key / f"{Path(asset).stem}.png").as_posix()
        path = image_dir / rel
        tpl = cv2.imread(str(path), cv2.IMREAD_UNCHANGED)
        if tpl is None:
            print(f"  [WARN] {node}: 读不到模板 {path}")
            continue
        if tpl.shape[2] == 4:
            tpl = cv2.cvtColor(tpl, cv2.COLOR_BGRA2BGR)
        if tpl.shape[0] > src.shape[0] or tpl.shape[1] > src.shape[1]:
            print(f"  [WARN] {node}: 模板大于截图，跳过")
            continue
        res = cv2.matchTemplate(src, tpl, cv2.TM_CCOEFF_NORMED)
        score = float(res.max())
        flag = "OK" if score >= 0.90 else "LOW"
        print(f"  [{flag}] {node}: score={score:.3f} template={path.name}")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--spec", type=Path, help="裁剪 spec JSON")
    parser.add_argument("--image-dir", type=Path, default=DEFAULT_IMAGE_DIR, help="模板输出根目录")
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST, help="模板集清单路径")
    parser.add_argument("--allow-small", action="store_true", help="允许短边 < 64px（仅调试）")
    parser.add_argument("--check", action="store_true", help="裁剪后用 OpenCV 自检匹配分数")
    parser.add_argument("--grid", type=Path, help="给截图叠加坐标网格")
    parser.add_argument("--grid-out", type=Path, help="网格图输出路径")
    parser.add_argument("--grid-step", type=int, default=100, help="网格步长（像素）")
    args = parser.parse_args(argv)

    if args.grid:
        out = args.grid_out or args.grid.with_name(args.grid.stem + "_grid.png")
        grid_overlay(args.grid, out, args.grid_step)
        return 0

    if not args.spec:
        parser.print_help()
        return 2

    return run_spec(args.spec, args.image_dir, args.manifest, args.allow_small, args.check)


if __name__ == "__main__":
    sys.exit(main())
