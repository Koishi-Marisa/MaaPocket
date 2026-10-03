#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PI-V2 资源包自检（HSR）。

只读。检查 `app-hsr/src/main/assets/pi`（或 --pi 指定的目录）：

1. 目录下所有 ``*.json`` 都能 ``json.load``；
2. ``interface.json`` 里每个 ``task.option`` 的名字都能在顶层 ``option`` 定义里找到；
3. 每个 option 的 ``default_case`` 都在自己的 ``cases`` 里，且每个 case 都有 ``pipeline_override``；
4. 把每个 ``resource`` 的 ``path`` 按顺序合并成节点命名空间后，
   每条 ``pipeline_override`` 覆盖的节点名必须真实存在（否则该 override 是空操作，
   属于「编造的开关」）；
5. override 里出现的字段必须是 MaaFramework ``Node`` 的合法字段；
6. 目录树里没有以 ``_`` 开头的**目录**（build_pi_pack 会因此退出码 2）；
7. 所有 JSON 都是 UTF-8 且没有 BOM。

用法::

    python scripts/check_hsr_pi.py [--pi app-hsr/src/main/assets/pi]

退出码 0 = 全部通过，1 = 有失败项。
"""

from __future__ import annotations

import argparse
import json
import os
import sys

# MaaFramework pipeline.schema.json 的 $defs.Node 顶层字段（v5.14.2）。
# 注意：schema 用的是 `action` 变体 + `unevaluatedProperties: false`，所以像
# `package`（StartApp/StopApp）、`key`（ClickKey）、`template`（TemplateMatch）这些
# 动作私有字段也是合法的 Node 字段。为了让检查不依赖手写的长列表，下面同时把
# 「已加载资源里任何节点用过的字段」也算作合法（本包的 pipeline 是扁平 v1 风格，
# 动作私有字段在基线节点里都已经出现过）。
NODE_FIELDS = {
    "action", "anchor", "attach", "enabled", "focus", "interrupt", "inverse",
    "is_sub", "max_hit", "next", "on_error", "post_delay", "post_wait_freezes",
    "pre_delay", "pre_wait_freezes", "rate_limit", "recognition", "repeat",
    "repeat_delay", "repeat_wait_freezes", "timeout",
}

DEFAULT_PI = os.path.join("app-hsr", "src", "main", "assets", "pi")


def merge_pipeline(pi_dir, rel_paths):
    """按顺序加载若干资源目录下的 pipeline/*.json，返回 {节点名: 节点}。"""
    merged = {}
    for rel in rel_paths:
        base = os.path.normpath(os.path.join(pi_dir, rel, "pipeline"))
        if not os.path.isdir(base):
            continue
        for root, _dirs, files in os.walk(base):
            for fname in files:
                if not fname.endswith(".json"):
                    continue
                path = os.path.join(root, fname)
                with open(path, "r", encoding="utf-8") as fh:
                    obj = json.load(fh)
                for node_name, node in obj.items():
                    merged[node_name] = node
    return merged


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pi", default=DEFAULT_PI, help="pi 目录（默认 %s）" % DEFAULT_PI)
    args = parser.parse_args(argv)
    pi = os.path.abspath(args.pi)

    failures = []
    checks = []

    def ok(msg):
        checks.append(("OK", msg))

    def fail(msg):
        failures.append(msg)
        checks.append(("FAIL", msg))

    # -- 1 + 7. every json parses, is UTF-8 without BOM ---------------------- #
    json_files = []
    for root, _dirs, files in os.walk(pi):
        for fname in files:
            if fname.endswith(".json"):
                json_files.append(os.path.join(root, fname))
    json_files.sort()
    bad_parse, bad_bom, bad_encoding = [], [], []
    for path in json_files:
        rel = os.path.relpath(path, pi).replace("\\", "/")
        with open(path, "rb") as fh:
            raw = fh.read()
        if raw.startswith(b"\xef\xbb\xbf"):
            bad_bom.append(rel)
            continue
        try:
            json.loads(raw.decode("utf-8"))
        except UnicodeDecodeError as exc:
            bad_encoding.append("%s (%s)" % (rel, exc))
        except ValueError as exc:
            bad_parse.append("%s (%s)" % (rel, exc))
    if bad_parse or bad_bom or bad_encoding:
        fail("JSON 解析/BOM 问题: parse=%s bom=%s encoding=%s"
             % (bad_parse, bad_bom, bad_encoding))
    else:
        ok("全部 %d 个 *.json 可 json.load，UTF-8 无 BOM" % len(json_files))

    iface_path = os.path.join(pi, "interface.json")
    with open(iface_path, "r", encoding="utf-8") as fh:
        iface = json.load(fh)

    # -- 2. task.option 引用必须存在 ---------------------------------------- #
    options = iface.get("option") or {}
    unknown_refs = []
    for task in iface.get("task", []):
        for name in task.get("option", []) or []:
            if name not in options:
                unknown_refs.append("%s -> %s" % (task.get("name"), name))
    if unknown_refs:
        fail("task.option 引用了未定义的 option: %s" % unknown_refs)
    else:
        ok("全部 task.option 引用（%d 处）都能在顶层 option 里找到"
           % sum(len(t.get("option", []) or []) for t in iface.get("task", [])))

    # -- 3. option 内部自洽 -------------------------------------------------- #
    option_problems = []
    for name, definition in options.items():
        cases = definition.get("cases") or []
        case_names = [c.get("name") for c in cases]
        if not cases:
            option_problems.append("%s 没有 cases" % name)
        default_case = definition.get("default_case")
        if default_case is not None and default_case not in case_names:
            option_problems.append("%s 的 default_case %s 不在 %s"
                                   % (name, default_case, case_names))
        if definition.get("type") == "switch" and len(cases) != 2:
            option_problems.append("%s 是 switch 但 case 数 != 2" % name)
        for case in cases:
            if "pipeline_override" not in case:
                option_problems.append("%s/%s 没有 pipeline_override"
                                       % (name, case.get("name")))
    if option_problems:
        fail("option 定义问题: %s" % option_problems)
    else:
        ok("全部 %d 个 option 自洽（case/default_case/switch 约束）" % len(options))

    # -- 4 + 5. pipeline_override 的落点必须真实存在且字段合法 -------------- #
    resources = iface.get("resource") or []
    namespaces = {}
    for resource in resources:
        namespaces[resource["name"]] = merge_pipeline(pi, resource.get("path", []))
    if not namespaces:
        fail("interface.json 没有任何 resource")
    else:
        ok("资源层: %s"
           % ", ".join("%s[%s]" % (r["name"], " + ".join(r.get("path", [])))
                       for r in resources))

    override_problems = []
    override_total = 0
    for resource in resources:
        ns = namespaces[resource["name"]]
        allowed_fields = set(NODE_FIELDS)
        for node in ns.values():
            allowed_fields |= set(node.keys())
        for name, definition in options.items():
            constrained = definition.get("resource")
            if constrained and resource["name"] not in constrained:
                continue
            for case in definition.get("cases") or []:
                for node_name, fields in (case.get("pipeline_override") or {}).items():
                    override_total += 1
                    if node_name not in ns:
                        override_problems.append(
                            "resource=%s option=%s case=%s 覆盖了不存在的节点 %s"
                            % (resource["name"], name, case.get("name"), node_name))
                        continue
                    bad = [k for k in fields if k not in allowed_fields]
                    if bad:
                        override_problems.append(
                            "resource=%s option=%s case=%s 节点 %s 的字段不是合法 Node 字段: %s"
                            % (resource["name"], name, case.get("name"),
                               node_name, bad))
                    for ref in fields.get("next", []) or []:
                        if ref not in ns:
                            override_problems.append(
                                "resource=%s option=%s case=%s 的 %s.next 指向不存在的节点 %s"
                                % (resource["name"], name, case.get("name"),
                                   node_name, ref))
    if override_problems:
        fail("pipeline_override 落点问题: %s" % override_problems)
    else:
        ok("全部 pipeline_override 落点（%d 条，覆盖 %d 个资源层）都指向真实节点、"
           "字段都是合法 Node 字段" % (override_total, len(resources)))

    # -- 6. 下划线目录 ------------------------------------------------------ #
    underscore = []
    for root, dirs, _files in os.walk(pi):
        for d in dirs:
            if d.startswith("_"):
                underscore.append(os.path.relpath(os.path.join(root, d), pi)
                                  .replace("\\", "/"))
    if underscore:
        fail("存在下划线开头的目录: %s" % underscore)
    else:
        ok("没有被包含的下划线目录")

    # -- report ------------------------------------------------------------- #
    print("=" * 72)
    print("  PI 目录: %s" % pi)
    print("=" * 72)
    for status, msg in checks:
        print("  [%s] %s" % (status, msg))
    print("-" * 72)
    print("  checks: %d, failures: %d" % (len(checks), len(failures)))
    if failures:
        for msg in failures:
            print("  !! %s" % msg)
        print("  结果: FAIL")
        return 1
    print("  结果: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
