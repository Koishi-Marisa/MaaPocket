#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把"上游资源工程 checkout"拼装成某个 app 模块的 `assets/pi/` 树。

MaaPocket 的三个 app 各自吃一份 PI-V2 资源包（`interface.json` + `tasks/` + `resource*/` ...），
这些包由 `scripts/migrate_*.py` 从上游工程（March7thAssistant / ZenlessZoneZero-OneDragon / MaaEnd）
迁出来，本脚本负责把它们按 app 的约定**组装并落盘**，同时留下可追溯的 `pi_pack.json`。

用法：
    python scripts/build_pi_pack.py \\
        --source build/pi-src/endfield \\
        --out app-endfield/src/main/assets/pi \\
        --game endfield

    python scripts/build_pi_pack.py --source ... --out ... --game hsr \\
        --overlay packs/hsr-device --overlay packs/hsr-zh \\
        --interface packs/hsr-zh/interface.json --clean

层级优先级（后者覆盖前者）：
    1. `--source`
    2. 各 `--overlay`，**按命令行给出顺序**，越靠后越优先
    3. `--interface`

几点刻意的设计：
  * `--source` 走 include 白名单（见 DEFAULT_INCLUDE）；`--overlay` **不走**白名单 —— overlay 的
    常见形态是"只补 image/ 和 pipeline/ 的补丁包"，白名单会把它们全滤掉。overlay 只受"永远剔除"
    与 `--exclude` 约束。
  * 永远剔除 `.git` / `node_modules` / `.venv` / `__pycache__` / `*.pyc` / `.github` / `tests` / `.cursor*`，
    三个来源都适用。
  * **硬规则**：MaaFramework 的 Android 打包逻辑无法处理下划线开头的目录名
    （上游 MaaEnd 的 AGENTS.md 明确写了这条）。只要最终会被写出去的路径里有任何一层目录
    以 `_` 开头，脚本就在**动盘之前**报错退出（exit 2），并点名出问题的路径。
  * 重跑是幂等的：如果 `--out` 里已有上一个 `pi_pack.json`，它会列出、这次不再产出的文件会被删掉
    （只删它自己记过的名字，绝不手扫目录）。

纯标准库，Python 3.9+。
"""

from __future__ import annotations

import argparse
import datetime
import fnmatch
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

# Windows 控制台用 ANSI 代码页，路径里的中文会 print 崩；显式改 UTF-8。
if sys.platform == "win32":
    for _stream_name in ("stdout", "stderr"):
        _stream = getattr(sys, _stream_name, None)
        if _stream is None or not hasattr(_stream, "buffer"):
            continue
        try:
            import io

            setattr(
                sys,
                _stream_name,
                io.TextIOWrapper(_stream.buffer, encoding="utf-8", errors="replace"),
            )
        except (AttributeError, ValueError):  # pragma: no cover
            pass

# stdout 被重定向到管道/文件时默认是块缓冲，会和 stderr 的输出错序（CI 日志里很难读）。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(line_buffering=True)  # type: ignore[union-attr]
    except (AttributeError, ValueError):  # pragma: no cover
        pass


# --------------------------------------------------------------------------------------
# 常量
# --------------------------------------------------------------------------------------

MANIFEST_NAME = "pi_pack.json"
GAMES = ("hsr", "zzz", "endfield")

# 默认 include 白名单：跟 MaaFwApp 打包器认的那套目录约定对齐。
DEFAULT_INCLUDE = [
    "interface.json",
    "tasks/**",
    "resource/**",
    "resource_*/**",
    "data/**",
    "locales/**",
    "CONTACT",
    "LICENSE",
]

# 永远不进包的目录名（精确匹配）
ALWAYS_STRIP_DIRS = frozenset({".git", "node_modules", ".venv", "__pycache__", ".github", "tests"})
# 永远不进包的目录名（glob）
ALWAYS_STRIP_DIR_GLOBS = (".cursor*",)
# 永远不进包的文件名（glob）
ALWAYS_STRIP_FILE_GLOBS = ("*.pyc",)

UNDERSCORE_EXIT_CODE = 2


# --------------------------------------------------------------------------------------
# 小工具
# --------------------------------------------------------------------------------------

def project_root() -> Path:
    return Path(__file__).resolve().parent.parent


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def now_iso() -> str:
    return (
        datetime.datetime.now(datetime.timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z")
    )


def mb(n: int) -> str:
    return "%.2f MB" % (n / (1024.0 * 1024.0))


def strip_jsonc(text: str) -> str:
    """去掉 // 与 /* */ 注释以及尾随逗号。

    上游的 pipeline / interface JSON 经常是 JSONC（MaaEnd 就带注释和尾随逗号），
    直接用 json.loads 会炸；这里做一个够用的宽松化预处理。
    """
    out: List[str] = []
    i = 0
    n = len(text)
    in_str = False
    escaped = False
    while i < n:
        ch = text[i]
        if in_str:
            out.append(ch)
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_str = False
            i += 1
            continue
        if ch == '"':
            in_str = True
            out.append(ch)
            i += 1
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] not in "\r\n":
                i += 1
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
            continue
        out.append(ch)
        i += 1
    return re.sub(r",(\s*[}\]])", r"\1", "".join(out))


def load_json_loose(path: Path) -> Optional[dict]:
    try:
        raw = path.read_text(encoding="utf-8-sig")
    except OSError:
        return None
    for candidate in (raw, strip_jsonc(raw)):
        try:
            data = json.loads(candidate)
        except ValueError:
            continue
        return data if isinstance(data, dict) else None
    return None


def safe_join(root: Path, rel: str) -> Path:
    """把 rel 拼到 root 下，并保证结果没跑出 root（防 manifest 被塞了 ../）。"""
    candidate = (root / rel).resolve()
    root_resolved = root.resolve()
    if candidate != root_resolved and root_resolved not in candidate.parents:
        raise ValueError("路径越界: %s" % rel)
    return candidate


def is_stripped_dir(name: str) -> bool:
    if name in ALWAYS_STRIP_DIRS:
        return True
    return any(fnmatch.fnmatchcase(name, g) for g in ALWAYS_STRIP_DIR_GLOBS)


def is_stripped_file(name: str) -> bool:
    return any(fnmatch.fnmatchcase(name, g) for g in ALWAYS_STRIP_FILE_GLOBS)


# --------------------------------------------------------------------------------------
# 收集
# --------------------------------------------------------------------------------------

def walk_layer(
    root: Path,
    include_globs: Optional[Sequence[str]],
    exclude_globs: Sequence[str],
    exclude_dir_globs: Sequence[str] = (),
) -> Dict[str, Path]:
    """走一遍 root，返回 {相对 posix 路径: 绝对路径}。

    include_globs 为 None 表示"整棵子树都要"（overlay 用）。
    匹配统一用 `fnmatchcase`（大小写敏感），保证 Windows 本地与 Linux CI 结果一致；
    注意 fnmatch 里 `*` 天然能跨 `/`，所以 `tasks/**`、`resource_*/**` 就是"整棵子树"的意思。

    `exclude_dir_globs` 单独作用于**任意层级的目录名**（不是相对路径）。这是排除下划线目录
    的正道：`--exclude '_*/**'` 只能命中顶层的 `_dir`，命不中 `resource/_dir/`，而
    `--exclude-dir '_*'` 在任意深度都能命中。
    """
    found: Dict[str, Path] = {}
    root = root.resolve()
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(
            d
            for d in dirnames
            if not is_stripped_dir(d)
            and not any(fnmatch.fnmatchcase(d, g) for g in exclude_dir_globs)
        )
        here = Path(dirpath)
        for filename in sorted(filenames):
            if is_stripped_file(filename):
                continue
            abs_path = here / filename
            if abs_path.is_symlink() and not abs_path.exists():
                continue  # 断链的符号链接
            if dirpath == str(root):
                rel = filename
            else:
                rel = here.relative_to(root).as_posix() + "/" + filename
            if include_globs is not None and not any(
                fnmatch.fnmatchcase(rel, g) for g in include_globs
            ):
                continue
            if any(fnmatch.fnmatchcase(rel, g) for g in exclude_globs):
                continue
            found[rel] = abs_path
    return found


def offending_underscore_paths(rels: Iterable[str]) -> Dict[str, List[str]]:
    """找出一层目录名以 `_` 开头的相对路径 -> 具体是哪些目录层。"""
    bad: Dict[str, List[str]] = {}
    for rel in rels:
        parts = rel.split("/")
        hits = [p for p in parts[:-1] if p.startswith("_")]
        if hits:
            bad[rel] = hits
    return bad


def git_info(source: Path) -> Tuple[Optional[str], Optional[str]]:
    """返回 (HEAD sha, describe)。git 不存在/不是仓库时给 (None, None)。"""

    def run(args: Sequence[str]) -> Optional[str]:
        try:
            proc = subprocess.run(
                ["git", "-C", str(source)] + list(args),
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                check=False,
            )
        except (OSError, ValueError):
            return None
        if proc.returncode != 0:
            return None
        text = proc.stdout.decode("utf-8", "replace").strip()
        return text or None

    return run(["rev-parse", "HEAD"]), run(["describe", "--tags", "--always", "--dirty"])


def prune_empty_dirs(root: Path) -> None:
    for dirpath, _dirnames, _filenames in os.walk(root, topdown=False):
        current = Path(dirpath)
        if current == root:
            continue
        try:
            if not any(current.iterdir()):
                current.rmdir()
        except OSError:
            pass


# --------------------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="组装 app 模块的 assets/pi 资源包",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--source", required=True, help="上游 PI-V2 资源工程目录（migrate 的 --out）")
    parser.add_argument("--out", required=True, help="目标 assets/pi 目录")
    parser.add_argument("--game", required=True, choices=GAMES, help="该包属于哪个 app")
    parser.add_argument("--overlay", action="append", default=[], metavar="DIR",
                        help="覆盖层目录，可重复；按给出顺序应用，越靠后越优先")
    parser.add_argument("--interface", default=None, metavar="PATH",
                        help="指定一个 interface.json 装上，覆盖 source/overlay 里的那份")
    parser.add_argument("--clean", action="store_true", help="落盘前先删掉 --out")
    parser.add_argument("--include", action="append", default=[], metavar="GLOB",
                        help="替换默认 include 白名单（可重复），只作用于 --source")
    parser.add_argument("--exclude", action="append", default=[], metavar="GLOB",
                        help="在默认剔除规则之上，再排除匹配的相对路径（可重复，source/overlay 都适用）")
    parser.add_argument("--exclude-dir", action="append", default=[], metavar="GLOB",
                        help="在任意层级排除目录名匹配 GLOB 的整个目录（可重复）。"
                             "排除下划线目录请用 --exclude-dir '_*'")
    return parser


def main(argv: Optional[List[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    root = project_root()

    source = Path(args.source)
    if not source.is_absolute():
        source = (root / source).resolve()
    out = Path(args.out)
    if not out.is_absolute():
        out = (root / out).resolve()
    overlays = []
    for raw in args.overlay:
        ov = Path(raw)
        if not ov.is_absolute():
            ov = (root / ov).resolve()
        overlays.append(ov)
    interface_override: Optional[Path] = None
    if args.interface:
        interface_override = Path(args.interface)
        if not interface_override.is_absolute():
            interface_override = (root / interface_override).resolve()

    include_globs = list(args.include) if args.include else list(DEFAULT_INCLUDE)
    exclude_globs = list(args.exclude)
    exclude_dir_globs = list(args.exclude_dir)

    print("=" * 72)
    print("MaaPocket :: build PI pack")
    print("=" * 72)
    print("  game     : %s" % args.game)
    print("  source   : %s" % source)
    print("  out      : %s" % out)
    print("  overlays : %s" % (", ".join(str(o) for o in overlays) if overlays else "(none)"))
    print("  interface: %s" % (interface_override if interface_override else "(from source/overlay)"))
    print("  include  : %s" % ", ".join(include_globs))
    print("  exclude  : %s" % (", ".join(exclude_globs) if exclude_globs else "(none)"))
    print("  excl-dir : %s" % (", ".join(exclude_dir_globs) if exclude_dir_globs else "(none)"))

    # ---- 基本校验 --------------------------------------------------------------------
    if not source.is_dir():
        raise SystemExit("[FATAL] --source 不是目录: %s" % source)
    for ov in overlays:
        if not ov.is_dir():
            raise SystemExit("[FATAL] --overlay 不是目录: %s" % ov)
    if interface_override is not None and not interface_override.is_file():
        raise SystemExit("[FATAL] --interface 不是文件: %s" % interface_override)

    # 别把自己删了 / 别把 source 拷进 source 里
    if out == source or source in out.parents:
        raise SystemExit("[FATAL] --out 落在 --source 里面了，会自我递归: %s ⊂ %s" % (out, source))
    if out in source.parents:
        raise SystemExit("[FATAL] --source 落在 --out 里面了，--clean 会连它一起删: %s" % source)
    for ov in overlays:
        if out == ov or ov in out.parents:
            raise SystemExit("[FATAL] --out 落在 --overlay 里面了: %s ⊂ %s" % (out, ov))

    # ---- 1. 收集 ---------------------------------------------------------------------
    print("\n[1/5] 收集文件")
    plan: Dict[str, Tuple[int, Path]] = {}  # rel -> (layer, 绝对路径)
    layer_names = ["source"] + ["overlay#%d" % i for i in range(1, len(overlays) + 1)]

    source_files = walk_layer(source, include_globs, exclude_globs, exclude_dir_globs)
    for rel, abs_path in source_files.items():
        plan[rel] = (0, abs_path)
    print("  source   : %d 个文件" % len(source_files))

    for idx, ov in enumerate(overlays, start=1):
        ov_files = walk_layer(ov, None, exclude_globs, exclude_dir_globs)
        shadowed = sum(1 for rel in ov_files if rel in plan)
        for rel, abs_path in ov_files.items():
            plan[rel] = (idx, abs_path)
        print("  overlay#%d: %d 个文件（覆盖 %d 个）" % (idx, len(ov_files), shadowed))

    if interface_override is not None:
        plan["interface.json"] = (len(overlays) + 1, interface_override)
        print("  interface: 强制装上 %s" % interface_override.name)

    if not plan:
        raise SystemExit(
            "[FATAL] 一个文件都没收集到。检查 --source 的内容和 include 白名单：%s" % ", ".join(include_globs)
        )

    # ---- 2. 硬规则：下划线开头的目录名 -------------------------------------------------
    print("\n[2/5] 检查下划线目录名")
    bad = offending_underscore_paths(plan)
    if bad:
        print("", file=sys.stderr)
        print("[FATAL] MaaFramework 的 Android 打包逻辑无法处理下划线开头的目录名。", file=sys.stderr)
        print("        下面这些路径会被写进 %s，已中止（没有动任何文件）：" % out, file=sys.stderr)
        shown = list(sorted(bad.items()))[:30]
        for rel, comps in shown:
            print("          - %s    (下划线目录: %s)" % (rel, ", ".join(comps)), file=sys.stderr)
        if len(bad) > len(shown):
            print("          ... 还有 %d 个" % (len(bad) - len(shown)), file=sys.stderr)
        print("        处理办法（任选其一）：", file=sys.stderr)
        print("          * 在上游把目录改名，去掉 '_' 前缀；", file=sys.stderr)
        print("          * 用 --exclude-dir '_*' 在任意层级排除所有下划线目录；", file=sys.stderr)
        print("          * 迁移时不要产出这些目录。", file=sys.stderr)
        return UNDERSCORE_EXIT_CODE
    print("  OK（没有被包含的下划线目录）")

    # ---- 3. 落盘 ---------------------------------------------------------------------
    print("\n[3/5] 落盘")
    previous: Optional[dict] = None
    old_manifest = out / MANIFEST_NAME
    if old_manifest.is_file():
        previous = load_json_loose(old_manifest)

    if args.clean and out.exists():
        print("  --clean: 删除 %s" % out)
        shutil.rmtree(out)
        previous = None
    out.mkdir(parents=True, exist_ok=True)

    stale: List[str] = []
    if previous and isinstance(previous.get("files"), list):
        keep = set(plan)
        for entry in previous["files"]:
            rel = entry.get("path") if isinstance(entry, dict) else None
            if not isinstance(rel, str) or rel in keep or rel == MANIFEST_NAME:
                continue
            try:
                target = safe_join(out, rel)
            except ValueError:
                continue
            if target.is_file():
                target.unlink()
                stale.append(rel)
        if stale:
            print("  [stale] 清掉上个 manifest 里 %d 个这次不再产出的文件" % len(stale))
        prune_empty_dirs(out)

    written: List[dict] = []
    total = 0
    for rel in sorted(plan):
        _layer, abs_path = plan[rel]
        target = out / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(abs_path, target)
        size = target.stat().st_size
        total += size
        written.append({"path": rel, "size": size, "layer": layer_names[_layer] if _layer < len(layer_names) else "?"})

    # ---- 4. interface 元信息 ---------------------------------------------------------
    print("\n[4/5] interface.json")
    installed_interface = out / "interface.json"
    if not installed_interface.is_file():
        raise SystemExit(
            "[FATAL] 组装结果里没有 interface.json —— 这不像一个 PI-V2 资源包。\n"
            "        请确认 --source 指对了目录，或者用 --interface 显式指定一份。"
        )
    data = load_json_loose(installed_interface)
    interface_version = None
    interface_schema_version = None
    if data is None:
        print("  [warn] interface.json 解析失败（可能不是合法 JSON/JSONC），version 记为 null")
    else:
        raw_version = data.get("version")
        interface_version = raw_version if isinstance(raw_version, str) else None
        raw_iv = data.get("interface_version")
        interface_schema_version = raw_iv if isinstance(raw_iv, (int, str)) else None
    interface_sha = sha256_of(installed_interface)
    print("  version=%s interface_version=%s sha256=%s"
          % (interface_version, interface_schema_version, interface_sha[:16] + "..."))

    # ---- 5. manifest -----------------------------------------------------------------
    print("\n[5/5] 写 %s" % MANIFEST_NAME)
    head, describe = git_info(source)
    manifest = {
        "schema": 1,
        "generated_by": "scripts/build_pi_pack.py",
        "generated_at": now_iso(),
        "game": args.game,
        "source": str(source),
        "source_git_head": head,
        "source_git_describe": describe,
        "interface_version": interface_version,
        "interface_schema_version": interface_schema_version,
        "interface_sha256": interface_sha,
        "file_count": len(written),
        "total_size": total,
        "overlays": [str(o) for o in overlays],
        "interface_override": str(interface_override) if interface_override else None,
        "include_globs": include_globs,
        "exclude_globs": exclude_globs,
        "exclude_dir_globs": exclude_dir_globs,
        "stale_removed": sorted(stale),
        "clean": bool(args.clean),
        "files": written,
    }
    old_manifest.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print("  %s" % old_manifest)

    print("\n" + "=" * 72)
    print("完成: %s  ->  %s" % (args.game, out))
    print("=" * 72)
    print("  文件数 : %d" % len(written))
    print("  总大小 : %d B (%s)" % (total, mb(total)))
    print("  git    : %s" % (head or "(不是 git 仓库 / 没有 git)"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
