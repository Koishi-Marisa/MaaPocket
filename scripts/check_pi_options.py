#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""递归展开 PI-V2 `interface.json` 的 `import`，报告合并后的 option / task 可见性。

这个脚本是 `MaaPocket/core/src/main/java/com/maapocket/core/pi/PiRepository.kt` 里
`readAndMerge` + `mergeInto` 的 **Python 复刻**，用途是"不进 Android 也能回答
'UI 上到底能不能看到可调项'"。合并语义严格对齐 Kotlin 侧：

    merged = fold(self) { acc, imported -> mergeInto(acc, imported) }

    mergeInto(base, imported) = base.copy(
        task       = dedupeByName(imported.task + base.task)   # 同名 base 胜出
        group      = dedupeByName(imported.group + base.group)
        setting    = dedupeByName(imported.setting + base.setting)
        option     = imported.option + base.option             # Map 合并, base 覆盖同名
        languages  = imported.languages + base.languages
        globalOption = (imported.globalOption + base.globalOption).distinct()
        agent      = base.agent ?: imported.agent              # 不做列表合并
        pretask    = base.pretask ?: imported.pretask          # 不做列表合并
        preset     = imported.preset + base.preset
    )

`import` 路径相对 **import 它的那个文件** 所在目录解析；该路径不存在时回退到 pack 根。
深度上限 8，canonical path 查环 —— 与 Kotlin 侧一致。

用法：
    python scripts/check_pi_options.py --root app-endfield/src/main/assets/pi
    python scripts/check_pi_options.py --root app-zzz/src/main/assets/pi --json out.json

纯标准库，Python 3.9+。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from typing import Any, Dict, List, Optional, Tuple

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

for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(line_buffering=True)  # type: ignore[union-attr]
    except (AttributeError, ValueError):  # pragma: no cover
        pass


MAX_IMPORT_DEPTH = 8

# 参与合并的顶层键 —— 与 mergeInto 一一对应。
LIST_MERGE_BY_NAME = ("task", "group", "setting")
MAP_MERGE = ("option", "languages")
LIST_MERGE_DISTINCT = ("global_option",)
# Kotlin 的 `base.agent ?: imported.agent` 语义：本文件没写才继承被导入的。
FIRST_WINS_SCALAR = ("agent", "pretask")
LIST_MERGE_CONCAT = ("preset",)


# --------------------------------------------------------------------------------------
# JSONC
# --------------------------------------------------------------------------------------

def strip_jsonc(text: str) -> str:
    """去掉 `//`、`/* */` 注释与尾随逗号（扫描器式，不会误伤字符串里的斜杠）。"""
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


def load_jsonc(path: str) -> Any:
    with open(path, "r", encoding="utf-8-sig") as fh:
        raw = fh.read()
    try:
        return json.loads(raw)
    except ValueError:
        return json.loads(strip_jsonc(raw))


# --------------------------------------------------------------------------------------
# 合并（PiRepository.mergeInto 的 Python 复刻）
# --------------------------------------------------------------------------------------

def dedupe_by_name(all_items: List[Any], base_items: List[Any]) -> List[Any]:
    """`imported.name + base.name` 去重，同名 base 胜出，保序。"""
    out: Dict[str, Any] = {}
    for item in list(all_items) + list(base_items):
        if isinstance(item, dict) and isinstance(item.get("name"), str):
            out[item["name"]] = item
    return list(out.values())


def merge_into(base: Dict[str, Any], imported: Dict[str, Any]) -> Dict[str, Any]:
    merged = dict(base)
    for key in LIST_MERGE_BY_NAME:
        imp = imported.get(key)
        bas = base.get(key)
        if not isinstance(imp, list) and not isinstance(bas, list):
            continue
        merged[key] = dedupe_by_name(
            imp if isinstance(imp, list) else [],
            bas if isinstance(bas, list) else [],
        )
    for key in MAP_MERGE:
        imp = imported.get(key)
        bas = base.get(key)
        if not isinstance(imp, dict) and not isinstance(bas, dict):
            continue
        combined: Dict[str, Any] = {}
        combined.update(imp if isinstance(imp, dict) else {})
        combined.update(bas if isinstance(bas, dict) else {})
        merged[key] = combined
    for key in LIST_MERGE_DISTINCT:
        imp = imported.get(key)
        bas = base.get(key)
        if not isinstance(imp, list) and not isinstance(bas, list):
            continue
        seen: List[Any] = []
        for item in (imp if isinstance(imp, list) else []) + (bas if isinstance(bas, list) else []):
            if item not in seen:
                seen.append(item)
        merged[key] = seen
    for key in FIRST_WINS_SCALAR:
        if base.get(key) is None and key in imported:
            merged[key] = imported[key]
        elif key not in base and key in imported:
            merged[key] = imported[key]
    for key in LIST_MERGE_CONCAT:
        imp = imported.get(key)
        bas = base.get(key)
        if not isinstance(imp, list) and not isinstance(bas, list):
            continue
        merged[key] = (imp if isinstance(imp, list) else []) + (bas if isinstance(bas, list) else [])
    return merged


class ImportResolver:
    def __init__(self, root: str) -> None:
        self.root = os.path.abspath(root)
        self.visited: List[str] = []
        self.errors: List[Dict[str, str]] = []
        # 每个文件的"自带"贡献（未合并），用于回答"谁提供了这个 option/task"。
        self.per_file: Dict[str, Dict[str, Any]] = {}

    def _resolve(self, base_dir: str, rel: str) -> str:
        cleaned = rel[2:] if rel.startswith("./") else rel
        from_base = os.path.join(base_dir, cleaned)
        if os.path.isfile(from_base):
            return from_base
        return os.path.join(self.root, cleaned)

    def load(self, file_path: str, depth: int = 0) -> Dict[str, Any]:
        key = os.path.realpath(file_path)
        if depth > MAX_IMPORT_DEPTH:
            self.errors.append({"file": file_path, "error": "import 嵌套超过 %d 层" % MAX_IMPORT_DEPTH})
            return {}
        if key in self.visited:
            self.errors.append({"file": file_path, "error": "import 出现环（已跳过）"})
            return {}
        if not os.path.isfile(file_path):
            self.errors.append({"file": file_path, "error": "import 指向的文件不存在"})
            return {}
        self.visited.append(key)

        try:
            self_doc = load_jsonc(file_path)
        except ValueError as exc:
            self.errors.append({"file": file_path, "error": "JSON 解析失败: %s" % exc})
            return {}
        if not isinstance(self_doc, dict):
            self.errors.append({"file": file_path, "error": "顶层不是对象"})
            return {}

        self.per_file[key] = {
            "path": file_path,
            "options": sorted((self_doc.get("option") or {}).keys())
            if isinstance(self_doc.get("option"), dict)
            else [],
            "tasks": [t.get("name") for t in (self_doc.get("task") or []) if isinstance(t, dict)]
            if isinstance(self_doc.get("task"), list)
            else [],
            "import": [x for x in (self_doc.get("import") or []) if isinstance(x, str)]
            if isinstance(self_doc.get("import"), list)
            else [],
        }

        merged: Dict[str, Any] = {}
        base_dir = os.path.dirname(os.path.abspath(file_path))
        for rel in self_doc.get("import") or []:
            if not isinstance(rel, str):
                continue
            child = self.load(self._resolve(base_dir, rel), depth + 1)
            merged = merge_into(merged, child) if merged else child
        return merge_into(self_doc, merged) if merged else self_doc


# --------------------------------------------------------------------------------------
# 报告
# --------------------------------------------------------------------------------------

def analyse(pi: Dict[str, Any]) -> Dict[str, Any]:
    options = pi.get("option") if isinstance(pi.get("option"), dict) else {}
    tasks = pi.get("task") if isinstance(pi.get("task"), list) else []

    tasks_with_option: List[Dict[str, Any]] = []
    referenced: Dict[str, List[str]] = {}
    for task in tasks:
        if not isinstance(task, dict):
            continue
        names = [n for n in (task.get("option") or []) if isinstance(n, str)]
        if not names:
            continue
        entry = {
            "task": task.get("name"),
            "option": names,
            "undefined": [n for n in names if n not in options],
        }
        tasks_with_option.append(entry)
        for n in names:
            referenced.setdefault(n, []).append(str(task.get("name")))

    # case 里嵌套引用的子 option 也算被引用。
    for name, spec in options.items():
        if not isinstance(spec, dict):
            continue
        for case in spec.get("cases") or []:
            if not isinstance(case, dict):
                continue
            for n in case.get("option") or []:
                if isinstance(n, str):
                    referenced.setdefault(n, []).append("%s/%s" % (name, case.get("name")))
        for n in spec.get("option") or []:
            if isinstance(n, str):
                referenced.setdefault(n, []).append(name)

    def case_names(spec: Any) -> List[str]:
        if not isinstance(spec, dict):
            return []
        return [str(c.get("name")) for c in (spec.get("cases") or []) if isinstance(c, dict)]

    option_table = [
        {
            "name": name,
            "default_case": spec.get("default_case") if isinstance(spec, dict) else None,
            "cases": case_names(spec),
            "kind": "select" if case_names(spec) else ("input" if isinstance(spec, dict) and "default" in spec else "unknown"),
            "label": spec.get("label") if isinstance(spec, dict) else None,
            "has_type_field": isinstance(spec, dict) and "type" in spec,
        }
        for name, spec in sorted(options.items())
    ]

    return {
        "option_definition_count": len(options),
        "option_names": sorted(options.keys()),
        "option_table": option_table,
        "task_count": len(tasks),
        "task_names": [t.get("name") for t in tasks if isinstance(t, dict)],
        "tasks_with_option_count": len(tasks_with_option),
        "tasks_with_option": tasks_with_option,
        "options_referenced_by_tasks": sorted(referenced.keys()),
        "options_defined_but_unreferenced": sorted(set(options) - set(referenced)),
        "options_referenced_but_undefined": sorted(set(referenced) - set(options)),
        "global_option": pi.get("global_option") if isinstance(pi.get("global_option"), list) else [],
        "setting_count": len(pi.get("setting") or []) if isinstance(pi.get("setting"), list) else 0,
        "group_count": len(pi.get("group") or []) if isinstance(pi.get("group"), list) else 0,
        "import_count": len(pi.get("import") or []) if isinstance(pi.get("import"), list) else 0,
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="check_pi_options.py", description=__doc__.splitlines()[0])
    parser.add_argument("--root", required=True, help="PI pack 根目录（含 interface.json）")
    parser.add_argument("--json", default=None, metavar="PATH", help="把完整报告写成 JSON")
    parser.add_argument("--quiet", action="store_true", help="只打印汇总")
    parser.add_argument(
        "--upstream",
        default=None,
        metavar="DIR",
        help="上游 git checkout；给出后逐个 import 文件对比上游 HEAD 与 pack，报告被丢弃/改名的 option 与 task",
    )
    parser.add_argument(
        "--upstream-prefix",
        default="assets",
        help="pack 根在上游仓库里对应的目录前缀（默认 assets）",
    )
    parser.add_argument(
        "--validate",
        action="store_true",
        help="额外做迁移包自检：全部 *.json 可解析且无 BOM、没有下划线开头的目录、task.option 引用的名字都已定义",
    )
    return parser


# --------------------------------------------------------------------------------------
# 上游 vs 迁移包：逐个 import 文件清点被丢弃 / 改名的 option、task
# --------------------------------------------------------------------------------------

def git_show(repo: str, spec: str) -> Optional[str]:
    import subprocess

    proc = subprocess.run(
        ["git", "-C", repo, "show", spec],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if proc.returncode != 0:
        return None
    return proc.stdout.decode("utf-8", "replace")


def _option_names(doc: Any) -> List[str]:
    opt = doc.get("option") if isinstance(doc, dict) else None
    if not isinstance(opt, dict):
        return []
    return sorted(str(k) for k in opt.keys())


def _task_option_map(doc: Any) -> Dict[str, List[str]]:
    """task 名 -> 它声明的 option 列表。"""
    out: Dict[str, List[str]] = {}
    tasks = doc.get("task") if isinstance(doc, dict) else None
    if not isinstance(tasks, list):
        return out
    for task in tasks:
        if not isinstance(task, dict):
            continue
        name = task.get("name")
        if not isinstance(name, str):
            continue
        out[name] = [n for n in (task.get("option") or []) if isinstance(n, str)]
    return out


def diff_against_upstream(
    pack_root: str, import_files: List[str], upstream_dir: str, upstream_prefix: str
) -> Dict[str, Any]:
    files: List[Dict[str, Any]] = []
    missing_in_pack: List[str] = []
    missing_in_upstream: List[str] = []
    dropped: List[Dict[str, Any]] = []
    added: List[Dict[str, Any]] = []
    task_option_changes: List[Dict[str, Any]] = []

    for abs_path in import_files:
        rel = os.path.relpath(abs_path, pack_root).replace("\\", "/")
        up_rel = "%s/%s" % (upstream_prefix.strip("/"), rel) if upstream_prefix else rel
        text = git_show(upstream_dir, "HEAD:%s" % up_rel)
        if text is None:
            missing_in_upstream.append(rel)
            continue
        try:
            up_doc = json.loads(text)
        except ValueError:
            up_doc = json.loads(strip_jsonc(text))
        pack_doc = load_jsonc(abs_path)

        up_opts = _option_names(up_doc)
        pack_opts = _option_names(pack_doc)
        only_up = sorted(set(up_opts) - set(pack_opts))
        only_pack = sorted(set(pack_opts) - set(up_opts))
        if only_up:
            dropped.append({"file": rel, "options": only_up})
        if only_pack:
            added.append({"file": rel, "options": only_pack})

        up_tasks = _task_option_map(up_doc)
        pack_tasks = _task_option_map(pack_doc)
        for name in sorted(set(up_tasks) & set(pack_tasks)):
            if up_tasks[name] != pack_tasks[name]:
                task_option_changes.append(
                    {"file": rel, "task": name, "upstream": up_tasks[name], "pack": pack_tasks[name]}
                )

        files.append(
            {
                "file": rel,
                "upstream_path": up_rel,
                "upstream_options": up_opts,
                "pack_options": pack_opts,
                "options_dropped": only_up,
                "options_added": only_pack,
                "upstream_tasks": sorted(up_tasks),
                "pack_tasks": sorted(pack_tasks),
                "tasks_dropped": sorted(set(up_tasks) - set(pack_tasks)),
                "tasks_added": sorted(set(pack_tasks) - set(up_tasks)),
            }
        )

    return {
        "upstream_dir": os.path.abspath(upstream_dir),
        "upstream_prefix": upstream_prefix,
        "files_compared": len(files),
        "files_missing_in_upstream": missing_in_upstream,
        "files_missing_in_pack": missing_in_pack,
        "options_dropped": dropped,
        "options_added": added,
        "task_option_changes": task_option_changes,
        "files": files,
    }


# --------------------------------------------------------------------------------------
# 迁移包自检：全部 *.json 可解析（UTF-8 无 BOM）+ 没有下划线开头的目录
# --------------------------------------------------------------------------------------

def validate_tree(pack_root: str) -> Dict[str, Any]:
    unparsable: List[Dict[str, str]] = []
    with_bom: List[str] = []
    underscore_dirs: List[str] = []
    json_count = 0
    file_count = 0

    for dirpath, dirnames, filenames in os.walk(pack_root):
        rel_dir = os.path.relpath(dirpath, pack_root).replace("\\", "/")
        for name in sorted(dirnames):
            # MaaFramework 的 Android 打包逻辑处理不了下划线开头的目录名。
            if name.startswith("_"):
                underscore_dirs.append("%s/%s" % (rel_dir, name) if rel_dir != "." else name)
        for name in sorted(filenames):
            file_count += 1
            rel = "%s/%s" % (rel_dir, name) if rel_dir != "." else name
            if name.lower().endswith(".json"):
                json_count += 1
                try:
                    load_jsonc(os.path.join(dirpath, name))
                except ValueError as exc:
                    unparsable.append({"file": rel, "error": str(exc)})
            with open(os.path.join(dirpath, name), "rb") as fh:
                if fh.read(3) == b"\xef\xbb\xbf":
                    with_bom.append(rel)

    return {
        "root": os.path.abspath(pack_root),
        "files_scanned": file_count,
        "json_scanned": json_count,
        "json_unparsable": unparsable,
        "json_with_bom": sorted(with_bom),
        "underscore_dirs": sorted(underscore_dirs),
    }


def main(argv: Optional[List[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    entry = os.path.join(args.root, "interface.json")
    if not os.path.isfile(entry):
        raise SystemExit("[FATAL] 找不到 %s" % entry)

    resolver = ImportResolver(args.root)
    merged = resolver.load(entry)
    report = analyse(merged)
    report["root"] = os.path.abspath(args.root)
    report["import_files_visited"] = [f["path"] for f in resolver.per_file.values()]
    report["per_file_contribution"] = sorted(resolver.per_file.values(), key=lambda f: f["path"])
    report["import_errors"] = resolver.errors

    print("=" * 72)
    print("PI option 可见性报告: %s" % report["root"])
    print("=" * 72)
    print("  import 文件数(含根)   : %d" % len(report["import_files_visited"]))
    print("  合并后 option 定义数  : %d" % report["option_definition_count"])
    print("  合并后 task 数        : %d" % report["task_count"])
    print("  带 option 的 task 数  : %d" % report["tasks_with_option_count"])
    print("  global_option         : %s" % (", ".join(report["global_option"]) or "(空)"))
    print("  setting 数            : %d" % report["setting_count"])
    print("")

    if report["option_names"]:
        print("-- option 定义清单 (%d) --" % len(report["option_names"]))
        for item in report["option_table"]:
            print("  %-40s cases=%-3d default=%-10s kind=%s"
                  % (item["name"], len(item["cases"]), str(item["default_case"]), item["kind"]))
        print("")

    if report["tasks_with_option"]:
        print("-- 带 option 的 task (%d) --" % len(report["tasks_with_option"]))
        for item in report["tasks_with_option"]:
            mark = "  <-- 未定义!" if item["undefined"] else ""
            print("  %-32s -> %s%s" % (item["task"], ", ".join(item["option"]), mark))
        print("")
    else:
        print("-- 带 option 的 task: 0 个（UI 上不会有任何可调项）--")
        print("")

    if report["options_defined_but_unreferenced"]:
        print("-- 定义了但没有任何 task/case 引用的 option (%d) --"
              % len(report["options_defined_but_unreferenced"]))
        for name in report["options_defined_but_unreferenced"]:
            print("  %s" % name)
        print("")

    if report["options_referenced_but_undefined"]:
        print("-- 被引用但未定义的 option (%d)  <-- 这是 bug --" % len(report["options_referenced_but_undefined"]))
        for name in report["options_referenced_but_undefined"]:
            print("  %-40s <- %s" % (name, ", ".join(report.get("_refs", {}).get(name, []))))
        print("")

    if report["import_errors"]:
        print("-- import 错误 (%d) --" % len(report["import_errors"]))
        for err in report["import_errors"]:
            print("  %s: %s" % (err["file"], err["error"]))
        print("")

    if not args.quiet:
        print("-- 每个 import 文件自带的 option/task --")
        for item in report["per_file_contribution"]:
            if not item["options"] and not item["tasks"]:
                continue
            print("  %s" % os.path.relpath(item["path"], args.root))
            if item["options"]:
                print("       options: %s" % ", ".join(item["options"]))
            if item["tasks"]:
                print("       tasks  : %s" % ", ".join(str(t) for t in item["tasks"]))
        print("")

    if args.upstream:
        diff = diff_against_upstream(
            args.root,
            report["import_files_visited"],
            args.upstream,
            args.upstream_prefix,
        )
        report["upstream_diff"] = diff
        print("-- 上游 vs 迁移包（逐 import 文件）--")
        print("  比较文件数            : %d" % diff["files_compared"])
        print("  上游没有对应文件      : %d" % len(diff["files_missing_in_upstream"]))
        print("  被丢弃的 option 定义  : %d" % sum(len(x["options"]) for x in diff["options_dropped"]))
        print("  新增的 option 定义    : %d" % sum(len(x["options"]) for x in diff["options_added"]))
        print("  task.option 发生变化  : %d" % len(diff["task_option_changes"]))
        for item in diff["options_dropped"]:
            print("  [丢弃] %s: %s" % (item["file"], ", ".join(item["options"])))
        for item in diff["task_option_changes"]:
            print("  [改]   %s / %s: upstream=%s pack=%s"
                  % (item["file"], item["task"], item["upstream"], item["pack"]))
        print("")

    if args.json:
        with open(args.json, "w", encoding="utf-8", newline="\n") as fh:
            json.dump(report, fh, indent=2, ensure_ascii=False, sort_keys=False)
            fh.write("\n")
        print("报告已写出: %s" % os.path.abspath(args.json))

    failures = 0
    if args.validate:
        tree = validate_tree(args.root)
        report["tree_check"] = tree
        print("-- 迁移包自检 --")
        print("  扫描文件              : %d（其中 *.json %d）" % (tree["files_scanned"], tree["json_scanned"]))
        print("  JSON 不可解析         : %d" % len(tree["json_unparsable"]))
        for item in tree["json_unparsable"]:
            print("      [坏] %s: %s" % (item["file"], item["error"]))
        print("  带 UTF-8 BOM 的文件   : %d" % len(tree["json_with_bom"]))
        for rel in tree["json_with_bom"]:
            print("      [BOM] %s" % rel)
        print("  下划线开头目录        : %d" % len(tree["underscore_dirs"]))
        for rel in tree["underscore_dirs"]:
            print("      [目录] %s" % rel)
        undefined = report["options_referenced_but_undefined"]
        print("  task.option 引用未定义: %d" % len(undefined))
        for name in undefined:
            print("      [未定义] %s" % name)
        print("  import 错误           : %d" % len(report["import_errors"]))
        print("")
        failures = (
            len(tree["json_unparsable"])
            + len(tree["json_with_bom"])
            + len(tree["underscore_dirs"])
            + len(undefined)
            + len(report["import_errors"])
        )
        print("自检结论: %s" % ("全部通过" if failures == 0 else "有 %d 项失败" % failures))
        if args.json:
            with open(args.json, "w", encoding="utf-8", newline="\n") as fh:
                json.dump(report, fh, indent=2, ensure_ascii=False, sort_keys=False)
                fh.write("\n")

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
