#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 pipeline 里所有 "action": "MultiSwipe" 节点转换为 Custom action 喵～
转换后：
  "action": "Custom",
  "custom_action": "MultiSwipeCustom",
  "custom_action_param": {"swipes": [...]}
MaaFramework 的 AndroidNative controller 官方只支持单点触摸（"native android controller only
supports single touch"），MultiSwipe 多指会被拒绝；改为 Custom action 后由引擎进程
用 injectInputEvent 注入多指针 MotionEvent，绕过该限制喵。
"""

import argparse
import json
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_PIPELINE_DIR = (
    PROJECT_ROOT / "app" / "src" / "main" / "assets" / "resource" / "base" / "pipeline"
)


# 去掉 JSON 中的 // 行注释（MaaFramework 的 pipeline 允许 jsonc 风格注释）
def strip_jsonc(text: str) -> str:
    lines = text.splitlines()
    out = []
    for line in lines:
        stripped = line.lstrip()
        if stripped.startswith("//"):
            continue
        # 行内注释：不处理（避免破坏字符串），pipeline 里注释基本是整行
        out.append(line)
    return "\n".join(out)


def convert_file(path: Path) -> int:
    raw = path.read_text(encoding="utf-8")
    try:
        data = json.loads(strip_jsonc(raw))
    except Exception as e:
        print(f"  [SKIP] {path.name} 解析失败: {e}")
        return 0

    if not isinstance(data, dict):
        return 0

    changed = 0
    for node_name, node in data.items():
        if not isinstance(node, dict):
            continue
        if node.get("action") == "MultiSwipe":
            swipes = node.get("swipes")
            if not isinstance(swipes, list):
                continue
            # 保留原 MultiSwipe 语义：转换为 Custom action
            node["action"] = "Custom"
            node["custom_action"] = "MultiSwipeCustom"
            node["custom_action_param"] = {"swipes": swipes}
            # 移除已使用的 swipes 顶层字段，避免重复（custom_action_param 里已带）
            node.pop("swipes", None)
            changed += 1
            print(f"  转换节点: {node_name} ({(len(swipes))}指)")

    if changed:
        path.write_text(
            json.dumps(data, ensure_ascii=False, indent=4) + "\n",
            encoding="utf-8",
        )
        print(f"[DONE] {path.name}: 转换 {changed} 个 MultiSwipe 节点")
    else:
        print(f"[NONE] {path.name}")
    return changed


def convert_pipeline_dir(pipeline_dir: Path) -> int:
    files = sorted(pipeline_dir.glob("*.json"))
    if not files:
        print(f"  [WARN] {pipeline_dir} 里没有 JSON pipeline。")
        return 0

    total = sum(convert_file(path) for path in files)
    print(f"\n===== 共转换 {total} 个 MultiSwipe 节点 =====")
    return total


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="把 pipeline 里的 MultiSwipe 转换为 Android Custom action"
    )
    parser.add_argument(
        "pipeline_dir",
        nargs="?",
        type=Path,
        default=DEFAULT_PIPELINE_DIR,
        help="pipeline 目录（默认 app/src/main/assets/resource/base/pipeline）",
    )
    args = parser.parse_args(argv)

    if not args.pipeline_dir.is_dir():
        parser.error(f"pipeline 目录不存在: {args.pipeline_dir}")

    convert_pipeline_dir(args.pipeline_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
