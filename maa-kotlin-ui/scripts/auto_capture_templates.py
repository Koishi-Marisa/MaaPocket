#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""半自动采集：用原 PC 模板在手机截图里自动定位元素，再裁成手机端模板。

为什么是「半自动」：
    游戏画面不是标准 Android 控件，没有 accessibility 节点树可读；唯一可靠的
    定位依据还是图像。这里把「量 rect」这一步自动化：
      原 PC 模板（已知元素长什么样）
        → 在当前手机虚拟屏截图上做多尺度模板匹配
        → 用命中的框（+padding）裁剪出**手机原生渲染**的模板
        → 写入 image/<set>/... 并更新 template_sets.json

    仍然需要人做的只有两件：把游戏停在目标界面、点一次 App 的「截图」。
    匹配不到的元素（PC/手机 UI 差异过大、纯文字按钮）会明确报错，可退回
    capture_templates.py 手工指定 rect。

用法::

    python scripts/auto_capture_templates.py --spec packs/hsr/stage1/auto_spec_720p.json

spec::

    {
      "source": "../../../shots/mail_720p.png",
      "set": "720p",
      "baseline": [1280, 720],
      "reference_root": "../../../app-hsr/src/main/assets/pi/resource/image",
      "scale_range": [0.5, 2.0, 0.05],
      "debug_out": "../../../shots/mail_720p_auto_debug.png",
      "nodes": {
        "HSR_Daily_EnterMailbox": {
          "asset": "hsr/daily_claim/mail_anchor",
          "ref": "screen/mail.png",
          "pad": 12,
          "min_score": 0.55
        },
        "HSR_Daily_ClickReceiveAll": {
          "asset": "hsr/daily_claim/receive_all",
          "ref": "zh_CN/reward/mail/receive_all.png",
          "pad": 12,
          "min_score": 0.55
        },
        "HSR_Daily_CloseRewardPopup": {
          "asset": "hsr/daily_claim/click_close",
          "ref": "zh_CN/base/click_close.png",
          "pad": 12,
          "min_score": 0.55
        }
      }
    }

自动拉图（可选）：
    spec 里把 `source` 写成 `"adb:latest"`，或命令行加 `--adb-pull`，
    工具会用 adb 从 App 的截图目录拉取最新一张虚拟屏 PNG 再开始匹配：

        adb shell ls -t /storage/emulated/0/Android/data/com.maafw.naruto/files/screenshots
        adb pull <最新文件> <项目>/shots/

    这只用于开发期采集模板；APK 运行时仍然不依赖 PC / adb。

依赖：Pillow + opencv-python（自动拉图额外需要 adb 在 PATH 上）。
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Dict, List, Optional, Tuple

try:
    from PIL import Image, ImageDraw
except ImportError:  # pragma: no cover
    print("[ERROR] 需要 Pillow：pip install pillow", file=sys.stderr)
    sys.exit(2)

try:
    import cv2  # type: ignore
except ImportError:  # pragma: no cover
    print("[ERROR] 自动定位需要 opencv-python：pip install opencv-python", file=sys.stderr)
    sys.exit(2)

# 复用裁剪脚本里的分辨率约定、缩放与清单写入
from capture_templates import (  # noqa: E402
    DEFAULT_IMAGE_DIR,
    DEFAULT_MANIFEST,
    MIN_SIDE,
    SET_SIZES,
    crop_and_scale,
    manifest_set_key_for_baseline,
    update_manifest,
)

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_REF_ROOT = PROJECT_ROOT.parent / "app-hsr/src/main/assets/pi/resource/image"
DEFAULT_SHOTS_DIR = PROJECT_ROOT / "shots"
DEFAULT_ADB_REMOTE_DIR = (
    "/storage/emulated/0/Android/data/com.maafw.naruto/files/screenshots"
)


class RefTemplate:
    def __init__(self, bgr, mask, source: Path):
        self.bgr = bgr
        self.mask = mask
        self.source = source


def load_ref(path: Path) -> RefTemplate:
    """读取参考模板；RGBA 的透明区转成 mask，并裁到不透明区的包围盒。"""
    raw = cv2.imread(str(path), cv2.IMREAD_UNCHANGED)
    if raw is None:
        raise ValueError(f"读不到参考模板：{path}")

    if raw.ndim == 3 and raw.shape[2] == 4:
        bgr = raw[:, :, :3]
        alpha = raw[:, :, 3]
        mask = (alpha > 8).astype("uint8") * 255
        if mask.min() == 0 and mask.max() == 255:
            x, y, w, h = cv2.boundingRect(mask)
            bgr = bgr[y:y + h, x:x + w]
            mask = mask[y:y + h, x:x + w]
        else:
            mask = None
    else:
        bgr = raw[:, :, :3] if raw.ndim == 3 else cv2.cvtColor(raw, cv2.COLOR_GRAY2BGR)
        mask = None
    return RefTemplate(bgr=bgr, mask=mask, source=path)


def adb_pull_latest(serial: Optional[str], remote_dir: str, dest_dir: Path) -> Path:
    """用 adb 拉取截图目录里最新的一张 PNG。"""
    adb = shutil.which("adb")
    if not adb:
        raise RuntimeError("PATH 里找不到 adb；安装 platform-tools，或把 spec.source 改成具体 PNG 路径")

    cmd = [adb] + (["-s", serial] if serial else [])
    devices = subprocess.run(cmd + ["devices"], capture_output=True, text=True, timeout=30)
    if devices.returncode != 0:
        raise RuntimeError(
            f"adb devices 失败：{devices.stderr.strip() or devices.stdout.strip()}"
        )
    ready = [
        line.split("\t")[0].strip()
        for line in devices.stdout.splitlines()[1:]
        if "\tdevice" in line
    ]
    if not ready:
        raise RuntimeError("没有处于 device 状态的手机；打开 USB 调试并在手机上确认授权")
    if serial and serial not in ready:
        raise RuntimeError(f"指定设备 {serial} 不在线（在线：{', '.join(ready)}）")

    listing = subprocess.run(
        cmd + ["shell", "ls", "-t", remote_dir],
        capture_output=True, text=True, timeout=30,
    )
    if listing.returncode != 0:
        raise RuntimeError(
            "读取截图目录失败（Android 11+ 可能限制 shell 访问 Android/data）："
            f"{listing.stderr.strip() or listing.stdout.strip()}"
        )
    names = [
        line.strip() for line in listing.stdout.splitlines()
        if line.strip().lower().endswith(".png")
    ]
    if not names:
        raise RuntimeError(f"{remote_dir} 里没有 PNG；先在 App 里点一次「⋮ → 截图」")

    latest = names[0]
    dest_dir.mkdir(parents=True, exist_ok=True)
    local = dest_dir / latest
    pull = subprocess.run(
        cmd + ["pull", f"{remote_dir}/{latest}", str(local)],
        capture_output=True, text=True, timeout=300,
    )
    if pull.returncode != 0 or not local.is_file() or local.stat().st_size == 0:
        raise RuntimeError(f"adb pull 失败：{pull.stderr.strip() or pull.stdout.strip()}")

    print(f"[ADB] 已拉取最新截图：{local}（{local.stat().st_size} 字节）")
    return local


def scales_from_range(raw) -> List[float]:
    if not raw or len(raw) < 2:
        return [1.0]
    lo, hi = float(raw[0]), float(raw[1])
    step = float(raw[2]) if len(raw) > 2 else 0.05
    if lo <= 0 or hi < lo or step <= 0:
        raise ValueError(f"scale_range 非法：{raw!r}")
    out, s = [], lo
    while s <= hi + 1e-6:
        out.append(round(s, 4))
        s += step
    if 1.0 not in out:
        out.append(1.0)
    return out


def match_one(image, ref: RefTemplate, scales: List[float]) -> Tuple[float, Tuple[int, int, int, int], float]:
    """返回 (score, (x, y, w, h), 使用的 scale)。score 越大越好。"""
    ih, iw = image.shape[:2]
    best_score, best_box, best_scale = -1.0, (0, 0, 0, 0), 1.0

    for s in scales:
        th = max(1, round(ref.bgr.shape[0] * s))
        tw = max(1, round(ref.bgr.shape[1] * s))
        if th > ih or tw > iw:
            continue
        templ = cv2.resize(ref.bgr, (tw, th), interpolation=cv2.INTER_AREA)
        mask = None
        if ref.mask is not None:
            mask = cv2.resize(ref.mask, (tw, th), interpolation=cv2.INTER_NEAREST)

        if mask is not None:
            # 带 mask 时只有 TM_SQDIFF / TM_CCORR_NORMED 支持；SQDIFF 对颜色差异更宽容
            res = cv2.matchTemplate(image, templ, cv2.TM_SQDIFF_NORMED, mask=mask)
            min_v, _, min_loc, _ = cv2.minMaxLoc(res)
            score = 1.0 - float(min_v)
            loc = min_loc
        else:
            res = cv2.matchTemplate(image, templ, cv2.TM_CCOEFF_NORMED)
            _, max_v, _, max_loc = cv2.minMaxLoc(res)
            score = float(max_v)
            loc = max_loc

        if score > best_score:
            best_score, best_box, best_scale = score, (loc[0], loc[1], tw, th), s

    return best_score, best_box, best_scale


def clamp_rect(rect: Tuple[int, int, int, int], pad: int, size: Tuple[int, int]) -> Tuple[int, int, int, int]:
    x, y, w, h = rect
    iw, ih = size
    x0 = max(0, x - pad)
    y0 = max(0, y - pad)
    x1 = min(iw, x + w + pad)
    y1 = min(ih, y + h + pad)
    return x0, y0, max(1, x1 - x0), max(1, y1 - y0)


def resolve_path(base: Path, value: str) -> Path:
    p = Path(value)
    return p if p.is_absolute() else (base / p).resolve()


def run(spec_path: Path, image_dir: Path, manifest_path: Path,
        allow_small: bool, allow_low: bool, dry_run: bool,
        adb_pull: bool = False, adb_serial: Optional[str] = None,
        adb_remote_dir: str = DEFAULT_ADB_REMOTE_DIR,
        shots_dir: Optional[Path] = None) -> int:
    spec = json.loads(spec_path.read_text(encoding="utf-8"))
    base = spec_path.parent

    source_value = str(spec.get("source", "")).strip()
    if adb_pull or source_value in {"adb", "adb:latest"}:
        try:
            source = adb_pull_latest(
                adb_serial,
                adb_remote_dir,
                shots_dir or DEFAULT_SHOTS_DIR,
            )
        except Exception as exc:  # noqa: BLE001
            print(f"[ERROR] {exc}", file=sys.stderr)
            return 1
    else:
        if not source_value:
            print("[ERROR] spec.source 为空（也可以写 adb:latest 自动拉图）", file=sys.stderr)
            return 1
        source = resolve_path(base, source_value)

    if not source.is_file():
        print(f"[ERROR] 截图不存在：{source}", file=sys.stderr)
        return 1

    set_key = str(spec.get("set", "720p"))
    if set_key not in SET_SIZES:
        print(f"[ERROR] 未知模板集 {set_key!r}，可选：{', '.join(SET_SIZES)}", file=sys.stderr)
        return 1

    baseline = tuple(int(v) for v in spec.get("baseline", [1280, 720]))
    baseline_set = manifest_set_key_for_baseline(baseline)
    ref_root = resolve_path(base, spec.get("reference_root", str(DEFAULT_REF_ROOT)))
    scales = scales_from_range(spec.get("scale_range"))

    cv_img = cv2.imread(str(source), cv2.IMREAD_COLOR)
    if cv_img is None:
        print(f"[ERROR] cv2 无法读取截图：{source}", file=sys.stderr)
        return 1
    pil_img = Image.open(source).convert("RGBA")
    debug = cv_img.copy()
    debug_enabled = bool(spec.get("debug_out"))

    print(f"[INFO] 截图 {source.name} = {cv_img.shape[1]}x{cv_img.shape[0]}，目标集 {set_key}")
    print(f"[INFO] 参考根目录 {ref_root}")
    print(f"[INFO] 多尺度 {scales[0]}..{scales[-1]}（{len(scales)} 档）")

    nodes = spec.get("nodes") or {}
    if not nodes:
        print("[ERROR] spec.nodes 为空", file=sys.stderr)
        return 1

    entries: Dict[str, str] = {}
    failed: List[str] = []

    for node, item in nodes.items():
        asset = str(item.get("asset", "")).strip()
        ref_rel = str(item.get("ref", "")).strip()
        if not asset or not ref_rel:
            print(f"  [ERROR] {node}: asset / ref 不能为空", file=sys.stderr)
            failed.append(node)
            continue

        ref_path = resolve_path(ref_root, ref_rel)
        if not ref_path.is_file():
            print(f"  [ERROR] {node}: 参考模板不存在 {ref_path}", file=sys.stderr)
            failed.append(node)
            continue

        try:
            ref = load_ref(ref_path)
            score, box, scale = match_one(cv_img, ref, scales)
        except Exception as exc:  # noqa: BLE001
            print(f"  [ERROR] {node}: 匹配异常 {exc}", file=sys.stderr)
            failed.append(node)
            continue

        min_score = float(item.get("min_score", 0.55))
        pad = int(item.get("pad", 12))
        rect = clamp_rect(box, pad, (cv_img.shape[1], cv_img.shape[0]))
        status = "OK" if score >= min_score else "LOW"
        print(
            f"  [{status}] {node}: ref={ref_rel} score={score:.3f} "
            f"scale={scale:.2f} box={box} -> rect={rect}"
        )

        if debug_enabled:
            x, y, w, h = rect
            color = (0, 200, 0) if score >= min_score else (0, 0, 255)
            cv2.rectangle(debug, (x, y), (x + w, y + h), color, 2)
            cv2.putText(debug, f"{node} {score:.2f}", (x, max(12, y - 6)),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 1, cv2.LINE_AA)

        if score < min_score and not allow_low:
            print(
                f"        score 低于 min_score={min_score:.2f}。"
                "换一张更干净的界面截图，或在 capture_spec 里手工指定 rect；"
                "确认可接受时加 --allow-low-score。",
                file=sys.stderr,
            )
            failed.append(node)
            continue

        try:
            template = crop_and_scale(pil_img, rect, set_key)
        except Exception as exc:  # noqa: BLE001
            print(f"  [ERROR] {node}: 裁剪失败 {exc}", file=sys.stderr)
            failed.append(node)
            continue

        if min(template.size) < MIN_SIDE and not allow_small:
            print(
                f"  [ERROR] {node}: 模板 {template.size[0]}x{template.size[1]} 短边 < {MIN_SIDE}px；"
                "把 pad 调大，或改用 capture_templates 手工裁更大的 UI 元素。",
                file=sys.stderr,
            )
            failed.append(node)
            continue

        if dry_run:
            continue

        asset_path = Path(asset)
        rel = (asset_path.parent / set_key / f"{asset_path.stem}.png").as_posix()
        out = image_dir / rel
        out.parent.mkdir(parents=True, exist_ok=True)
        template.save(out)
        entries[node] = rel

        if set_key == baseline_set:
            canonical = image_dir / asset_path.parent / f"{asset_path.stem}.png"
            canonical.parent.mkdir(parents=True, exist_ok=True)
            template.save(canonical)

        print(f"        -> {out} ({template.size[0]}x{template.size[1]})")

    if debug_enabled:
        debug_out = resolve_path(base, spec["debug_out"])
        debug_out.parent.mkdir(parents=True, exist_ok=True)
        cv2.imwrite(str(debug_out), debug)
        print(f"[OK] 调试图已保存：{debug_out}")

    if entries and not dry_run:
        update_manifest(manifest_path, set_key, entries, baseline)
        print(f"[OK] 模板集清单已更新：{manifest_path}")

    if failed:
        print(f"\n[FAIL] {len(failed)} 个节点未自动采集：{', '.join(failed)}", file=sys.stderr)
        print("       对这些节点用 capture_templates.py 手工指定 rect。", file=sys.stderr)
        return 1
    if dry_run:
        print(f"\n[OK] 全部 {len(nodes)} 个节点匹配通过（--dry-run 未写文件）")
    else:
        print(f"\n[OK] 全部 {len(nodes)} 个节点已自动采集")
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--spec", type=Path, required=True, help="自动采集 spec JSON")
    parser.add_argument("--image-dir", type=Path, default=DEFAULT_IMAGE_DIR)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--allow-small", action="store_true", help="允许短边 < 64px（仅调试）")
    parser.add_argument("--allow-low-score", action="store_true", help="匹配分低于 min_score 也写入")
    parser.add_argument("--dry-run", action="store_true", help="只打印匹配结果，不写文件")
    parser.add_argument("--adb-pull", action="store_true",
                        help="先用 adb 拉取最新虚拟屏截图（spec.source 可写 adb:latest）")
    parser.add_argument("--adb-serial", default=None, help="adb 设备序列号（多设备时指定）")
    parser.add_argument("--adb-remote-dir", default=DEFAULT_ADB_REMOTE_DIR,
                        help="手机上的截图目录")
    parser.add_argument("--shots-dir", type=Path, default=DEFAULT_SHOTS_DIR,
                        help="拉取到本地的目录（默认 maa-kotlin-ui/shots）")
    args = parser.parse_args(argv)
    return run(args.spec.resolve(), args.image_dir, args.manifest,
               args.allow_small, args.allow_low_score, args.dry_run,
               args.adb_pull, args.adb_serial, args.adb_remote_dir, args.shots_dir)


if __name__ == "__main__":
    sys.exit(main())
