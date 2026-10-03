#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Migrate March7thAssistant's screen-navigation graph + task taxonomy into a
MaaFramework Project-Interface-V2 (PI-V2) resource pack.

Upstream: https://github.com/moesnow/March7thAssistant   (GPL-3.0)
Source of truth: ``assets/config/screens.json`` -- a JSON *list* of 56 screen
objects with 110 action-edges, each edge carrying a list of raw Python
expressions that are ``eval()``-ed by ``module/screen/screen.py``.

What this script produces (all strict JSON, no comments, no trailing commas)::

    <out>/interface.json                      PI-V2 manifest
    <out>/migration_report.json               provenance + census + graph + task map
    <out>/resource/pipeline/screen/<id>.json   one file per screen
    <out>/resource/pipeline/screen/_index.json screen-graph index node
    <out>/resource/pipeline/task/<id>.json     one task-entry node per feature
    <out>/resource/image/**.png                templates (verbatim copies)
    <out>/resource/locale/zh_cn.json           zh-CN string table

Design decisions that are *not* obvious from the brief are documented inline
(see the ``DECISION:`` comments) and echoed into ``migration_report.json``.

Python 3.9+, standard library only.

Usage::

    python migrate_march7th.py --source refs/March7thAssistant \\
        --out MaaPocket/app-hsr/src/main/assets/pi \\
        --schemas _research/maafw_dev/tools [--force] [--include-unsupported]
"""

from __future__ import annotations

import argparse
import ast
import hashlib
import json
import operator
import os
import re
import shutil
import struct
import subprocess
import sys

# --------------------------------------------------------------------------- #
# Constants
# --------------------------------------------------------------------------- #

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MAA_POCKET_DIR = os.path.dirname(SCRIPT_DIR)          # .../MaaPocket
REPO_DIR = os.path.dirname(MAA_POCKET_DIR)            # .../二游自动化

DEFAULT_SOURCE = os.path.join(REPO_DIR, "refs", "March7thAssistant")
DEFAULT_OUT = os.path.join(MAA_POCKET_DIR, "app-hsr", "src", "main", "assets", "pi")
SCHEMA_DIR_CANDIDATES = (
    os.path.join(REPO_DIR, "_research", "maafw_dev", "tools"),
    os.path.join(MAA_POCKET_DIR, ".maafw", "v5.14.2", "include", "schemas"),
    os.path.join(REPO_DIR, "_research", "maafw", "tools"),
)

SCREENS_CONFIG = "assets/config/screens.json"
IMAGES_DIR = "assets/images"
IMAGE_PREFIX = IMAGES_DIR + "/"

# DECISION: the pack's design resolution is the *source* pixel space, 1920x1080.
# March7thAssistant captures the PC client at 1920x1080, every normalised crop
# in screens.json is expressed as ``n/1920`` / ``n/1080``, and the 58 screen
# templates are small RGB(A) crops of that 1920x1080 frame.  Keeping the pack in
# the same coordinate space means every ``roi`` we emit is exact and the copied
# templates stay dimensionally consistent with the recognition space.
# It is wired up as ``display_long_side: 1920`` on the Adb controller
# (``display_short_side`` and ``display_long_side`` are documented as mutually
# exclusive).  This is a PLACEHOLDER geometry: a phone whose screenshot is not
# 16:9 will scale differently, and every roi + template must be re-derived.
DESIGN_W = 1920
DESIGN_H = 1080

SCREEN_THRESHOLD = 0.8        # brief asks for "around 0.8"; upstream uses 0.88
MASKED_THRESHOLD = 0.7        # SQDIFF-with-mask scores are not portable
DEFAULT_RATE_LIMIT = 1000
DEFAULT_TIMEOUT = 20000

RESOURCE_NAME = "国服"
CONTROLLER_NAME = "Android"

# --------------------------------------------------------------------------- #
# Android virtual key codes  (Adb controller -> android.view.KeyEvent constants)
# --------------------------------------------------------------------------- #

ANDROID_KEYCODES = {}
for _i, _c in enumerate("ABCDEFGHIJKLMNOPQRSTUVWXYZ"):
    ANDROID_KEYCODES[_c.lower()] = 29 + _i            # KEYCODE_A = 29 .. KEYCODE_Z = 54
for _d in range(10):
    ANDROID_KEYCODES[str(_d)] = 7 + _d                # KEYCODE_0 = 7 .. KEYCODE_9 = 16
for _f in range(1, 13):
    ANDROID_KEYCODES["f%d" % _f] = 130 + _f           # KEYCODE_F1 = 131 .. KEYCODE_F12 = 142
ANDROID_KEYCODES.update({
    "esc": 111, "escape": 111,
    "enter": 66, "return": 66,
    "space": 62, "spacebar": 62,
    "tab": 61, "backspace": 67, "delete": 112,
    "up": 19, "down": 20, "left": 21, "right": 22,
    "shift": 59, "ctrl": 113, "control": 113, "alt": 57,
    "home": 3, "back": 4, "menu": 82,
})

# --------------------------------------------------------------------------- #
# Small helpers
# --------------------------------------------------------------------------- #


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def dump_json(path, obj, sort_keys=False):
    """Write strict JSON as UTF-8 with LF newlines.  Returns (bytes, sha256)."""
    text = json.dumps(obj, ensure_ascii=False, indent=2, sort_keys=sort_keys)
    data = (text + "\n").encode("utf-8")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(data)
    return len(data), sha256_bytes(data)


def write_bytes(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(data)


def run_git(source, *args):
    proc = subprocess.run(
        ["git", "-C", source] + list(args),
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    if proc.returncode != 0:
        return None
    return proc.stdout


def strip_jsonc(text):
    """Tolerate JSONC in *inputs* (``//``, ``/* */``, trailing commas).

    Our own output is always strict JSON; this only exists so that a schema or
    config file carrying comments can still be loaded.
    """
    out = []
    i, n = 0, len(text)
    in_str = False
    while i < n:
        ch = text[i]
        if in_str:
            out.append(ch)
            if ch == "\\" and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if ch == '"':
                in_str = False
            i += 1
            continue
        if ch == '"':
            in_str = True
            out.append(ch)
            i += 1
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
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
    cleaned = "".join(out)
    # remove trailing commas before a closing brace / bracket
    return re.sub(r",(\s*[}\]])", r"\1", cleaned)


def load_json_loose(data):
    if isinstance(data, (bytes, bytearray)):
        text = data.decode("utf-8-sig")
    else:
        text = data
    try:
        return json.loads(text)
    except ValueError:
        return json.loads(strip_jsonc(text))


# --------------------------------------------------------------------------- #
# Expression language  (Deliverable 1)
# --------------------------------------------------------------------------- #

_CALL_RE = re.compile(r"^\s*([A-Za-z_][A-Za-z0-9_.]*)\s*\((.*)\)\s*$", re.S)
_CFG_GET_VALUE_RE = re.compile(
    r"^cfg\.get_value\(\s*(['\"])(?P<section>.*?)\1\s*,\s*(['\"])(?P<key>.*?)\3\s*\)$"
)
_KWARG_RE = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.+)$", re.S)


def split_args(src):
    """Split a call's argument list on top-level commas, respecting nesting and quotes."""
    out, buf, depth, quote = [], [], 0, None
    i = 0
    while i < len(src):
        ch = src[i]
        if quote is not None:
            buf.append(ch)
            if ch == "\\" and i + 1 < len(src):
                buf.append(src[i + 1])
                i += 2
                continue
            if ch == quote:
                quote = None
            i += 1
            continue
        if ch in "'\"":
            quote = ch
            buf.append(ch)
            i += 1
            continue
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            out.append("".join(buf).strip())
            buf = []
            i += 1
            continue
        buf.append(ch)
        i += 1
    tail = "".join(buf).strip()
    if tail:
        out.append(tail)
    return out


def parse_str_literal(raw):
    s = raw.strip()
    if len(s) >= 2 and s[0] in "'\"" and s[-1] == s[0]:
        body = s[1:-1]
        return body.replace("\\\\", "\\").replace("\\'", "'").replace('\\"', '"')
    return None


def parse_number(raw):
    s = raw.strip()
    if not s:
        return None
    if re.fullmatch(r"[+-]?\d+", s):
        return int(s)
    try:
        return float(s)
    except ValueError:
        return eval_arithmetic(s)


# screens.json is full of normalised arithmetic, e.g. ``crop=(1264.0 / 1920, ...)``
# and ``(1846 / 1920, 253 / 1080, 43 / 1920, 37 / 1080)``.  We therefore need to
# evaluate simple arithmetic instead of just reading literals.  No ``**`` / ``%``
# in the whitelist, so there is no way to write an expression that explodes.
_NUM_BINOPS = {
    ast.Add: operator.add,
    ast.Sub: operator.sub,
    ast.Mult: operator.mul,
    ast.Div: operator.truediv,
}
_NUM_UNARYOPS = {ast.UAdd: operator.pos, ast.USub: operator.neg}
_NUM_CHARS_RE = re.compile(r"^[0-9eE+\-*/().\s]+$")


def eval_arithmetic(expr):
    """Evaluate a digits-and-operators-only arithmetic expression, or None."""
    s = expr.strip()
    if not s or not _NUM_CHARS_RE.match(s):
        return None
    try:
        tree = ast.parse(s, mode="eval")
    except SyntaxError:
        return None

    def ev(node):
        if isinstance(node, ast.Expression):
            return ev(node.body)
        if isinstance(node, ast.Constant) and isinstance(node.value, (int, float)) \
                and not isinstance(node.value, bool):
            return node.value
        if isinstance(node, ast.BinOp) and type(node.op) in _NUM_BINOPS:
            return _NUM_BINOPS[type(node.op)](ev(node.left), ev(node.right))
        if isinstance(node, ast.UnaryOp) and type(node.op) in _NUM_UNARYOPS:
            return _NUM_UNARYOPS[type(node.op)](ev(node.operand))
        raise ValueError("unsupported expression node %s" % type(node).__name__)

    try:
        value = ev(tree)
    except Exception:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return value


def parse_tuple_of_numbers(raw):
    s = raw.strip()
    if not (s.startswith("(") and s.endswith(")")):
        return None
    vals = []
    for part in split_args(s[1:-1]):
        v = parse_number(part)
        if v is None:
            return None
        vals.append(v)
    return vals


def parse_expression(expr):
    """Classify one raw ``actions_list`` string.

    Returns a dict with ``ok`` (the expression was understood), ``mappable``
    (it can be expressed in MaaFramework without a resource we do not ship) and
    per-kind fields.  ``ok and mappable`` is the test for "handled"; anything
    else is recorded as unmapped.
    """
    info = {"raw": expr, "ok": False, "mappable": False,
            "kind": "unknown", "note": ""}
    m = _CALL_RE.match(expr)
    if not m:
        info["note"] = "not a single top-level call expression"
        return info
    func, argsrc = m.group(1), m.group(2)
    info["func"] = func
    raw_args = split_args(argsrc)

    if func == "time.sleep":
        if len(raw_args) != 1:
            info["note"] = "time.sleep expects exactly 1 argument"
            return info
        secs = parse_number(raw_args[0])
        if secs is None:
            info["note"] = "time.sleep argument is not a numeric literal"
            return info
        info.update(ok=True, mappable=True, kind="sleep", seconds=secs)
        return info

    if func == "auto.press_key":
        if len(raw_args) != 1:
            info["note"] = "auto.press_key expects exactly 1 argument"
            return info
        arg = raw_args[0]
        lit = parse_str_literal(arg)
        if lit is not None:
            info.update(ok=True, mappable=True, kind="press_key",
                        source="literal", key=lit)
            return info
        gm = _CFG_GET_VALUE_RE.match(arg.strip())
        if gm:
            info.update(ok=True, mappable=True, kind="press_key", source="config",
                        section=gm.group("section"), key=gm.group("key"))
            return info
        info["note"] = ("auto.press_key argument is neither a string literal "
                        "nor cfg.get_value('<section>','<key>')")
        return info

    if func == "auto.click_element":
        return _parse_click_element(info, raw_args)

    info["note"] = "unrecognised callee %r" % func
    return info


def _parse_click_element(info, raw_args):
    pos, kwargs = [], {}
    for arg in raw_args:
        m = _KWARG_RE.match(arg)
        if m and not arg.lstrip().startswith(("'", '"')):
            kwargs[m.group(1)] = m.group(2).strip()
        else:
            pos.append(arg)
    if len(pos) < 2:
        info["note"] = "auto.click_element needs at least (target, find_type)"
        return info

    find_type = parse_str_literal(pos[1])
    if find_type is None:
        info["note"] = "find_type is not a string literal"
        return info
    info["find_type"] = find_type

    thr_raw = pos[2] if len(pos) > 2 else kwargs.pop("threshold", None)
    retries_raw = pos[3] if len(pos) > 3 else kwargs.pop("max_retries", None)
    include_raw = kwargs.pop("include", None)
    crop_raw = kwargs.pop("crop", None)

    if find_type == "crop":
        rect = parse_tuple_of_numbers(pos[0])
        if rect is None or len(rect) != 4:
            info["note"] = "click_element(..., 'crop') target is not a 4-tuple"
            return info
        info.update(ok=True, mappable=True, kind="click", find_type="crop",
                    crop_rect=rect)
        return info

    if find_type == "image":
        template = parse_str_literal(pos[0])
        templates = None
        if template is None:
            inner = parse_tuple_of_numbers(pos[0])
            if inner is None and pos[0].strip().startswith(("[", "(")):
                items = split_args(pos[0].strip()[1:-1])
                templates = [parse_str_literal(x) for x in items]
                if any(t is None for t in templates):
                    templates = None
        if template is None and templates is None:
            info["note"] = "template path is not a string literal (or list of them)"
            return info
        threshold = parse_number(thr_raw) if thr_raw is not None else None
        if thr_raw is not None and threshold is None:
            info["note"] = "threshold is not a numeric literal"
            return info
        max_retries = None
        if retries_raw is not None:
            max_retries = parse_number(retries_raw)
            if max_retries is None:
                info["note"] = "max_retries is not a numeric literal"
                return info
            max_retries = int(max_retries)
        crop = None
        if crop_raw is not None:
            crop = parse_tuple_of_numbers(crop_raw)
            if crop is None or len(crop) != 4:
                info["note"] = "crop is not a 4-tuple of numbers"
                return info
        is_int = threshold is not None and float(threshold).is_integer()
        info.update(
            ok=True, mappable=True, kind="click", find_type="image",
            template=template, templates=templates,
            threshold=threshold, threshold_is_int=bool(is_int),
            max_retries=max_retries, crop=crop,
            extra_kwargs=sorted(kwargs),
        )
        return info

    if find_type == "text":
        expected = parse_str_literal(pos[0])
        if expected is None:
            info["note"] = "OCR target text is not a string literal"
            return info
        threshold = parse_number(thr_raw) if thr_raw is not None else None
        max_retries = parse_number(retries_raw) if retries_raw is not None else None
        crop = parse_tuple_of_numbers(crop_raw) if crop_raw is not None else None
        # DECISION: parsed, but deliberately NOT mapped.  The only faithful
        # MaaFramework form is `recognition: "OCR"` + `expected` + `roi`, which
        # needs the OCR model resource that this pack does not ship; and OCR of
        # Chinese UI strings is exactly the thing that changes between the PC and
        # phone clients.  These become __UNMAPPED_<n> DoNothing placeholders and
        # the intended node shape is recorded per expression in the report.
        info.update(
            ok=True, mappable=False, kind="click", find_type="text",
            expected=expected, threshold=threshold,
            max_retries=int(max_retries) if max_retries is not None else None,
            crop=crop,
            include=(include_raw is not None and include_raw.strip() == "True"),
            extra_kwargs=sorted(kwargs),
            note=("find_type='text' 需要 MaaFramework 的 OCR 识别模型资源，"
                  "本资源包未附带，且中文 UI 文本在 PC/手机客户端间会变。"),
        )
        return info

    info["note"] = "unmapped find_type %r" % find_type
    return info


def is_unmapped(info):
    """True when this expression becomes a __UNMAPPED_<n> placeholder."""
    return not (info.get("ok") and info.get("mappable"))


def canonical_form(info):
    """Collapse a parsed expression into a finite, report-friendly form string."""
    kind = info.get("kind")
    if kind == "sleep":
        return "time.sleep(<n>)"
    if kind == "press_key":
        if info.get("source") == "literal":
            return "auto.press_key('<literal>')"
        return "auto.press_key(cfg.get_value('<section>','<key>'))"
    if kind == "click":
        ft = info.get("find_type")
        if ft == "crop":
            return "auto.click_element((<x>/1920,<y>/1080,<w>/1920,<h>/1080), 'crop')"
        target = "<text>" if ft == "text" else "<path>"
        parts = ["auto.click_element(%s, '%s'" % (target, ft)]
        if info.get("threshold") is not None:
            parts.append(", <int>" if info.get("threshold_is_int") else ", <float>")
        if info.get("max_retries") is not None:
            parts.append(", <max_retries>")
        if info.get("crop") is not None:
            parts.append(", crop=(<x>/1920,<y>/1080,<w>/1920,<h>/1080)")
        if info.get("include"):
            parts.append(", include=True")
        for kw in info.get("extra_kwargs", []):
            parts.append(", %s=<%s>" % (kw, kw))
        return "".join(parts) + ")"
    return "<unparsed>"


FORM_MAPPING = {
    "time.sleep(<n>)": (
        "MANAGED", "DoNothing 节点 + post_delay = n*1000 毫秒",
        "MaaFramework 的 post_delay 就是本节点动作结束后的固定等待，语义等价。",
    ),
    "auto.press_key('<literal>')": (
        "MANAGED", "ClickKey 节点, key = Android KEYCODE_*",
        "PC 上按下的物理键在 Android 上必须换成 KeyEvent 虚拟键码（Adb 控制器的 key 参数即此）。"
        "手机客户端根本没有 ESC/热键这一层输入，映射仅是形式上的对齐，见 RECAPTURE.md。",
    ),
    "auto.press_key(cfg.get_value('<section>','<key>'))": (
        "MANAGED-WITH-LOSS", "ClickKey 节点, key = 该热键的**默认值**对应的 KEYCODE_*",
        "静态资源包读不到用户的 config.yaml，只能常量折叠成默认热键（hotkey_map=m → KEYCODE_M=41，"
        "hotkey_warp=f3 → KEYCODE_F3=133）。用户改过热键则本节点失效。",
    ),
    "auto.click_element(<path>, 'image', <float>)": (
        "MANAGED", "TemplateMatch(template, threshold) + Click, target = true",
        "上游 TM_CCOEFF_NORMED 的相关系数与 MaaFramework 的 method=5 是同一个算法，"
        "分数可近似直接搬运；上游截图阈值 0.88，本包统一用 0.8。",
    ),
    "auto.click_element(<path>, 'image', <float>, <max_retries>)": (
        "MANAGED-WITH-LOSS", "TemplateMatch + Click；max_retries 记入 attach",
        "MaaFramework 没有“重试 n 次”参数，它是靠 timeout(默认 20s) + rate_limit(默认 1s) 的识别循环实现等价重试。"
        "原值写入 attach['march7th:max_retries']，不做 1:1 换算。",
    ),
    "auto.click_element(<path>, 'image', <float>, crop=(<x>/1920,<y>/1080,<w>/1920,<h>/1080))": (
        "MANAGED", "TemplateMatch(roi=去归一化后的像素矩形) + Click",
        "crop 是归一化矩形，按 1920x1080 展开成 roi=[round(x*1920), round(y*1080), round(w*1920), round(h*1080)] 并夹在屏幕内。",
    ),
    "auto.click_element(<path>, 'image', <int>, crop=(<x>/1920,<y>/1080,<w>/1920,<h>/1080))": (
        "APPROXIMATED", "TemplateMatch(roi=同上, threshold=0.7) + Click",
        "阈值 >= 1000 说明模板带真实 alpha，上游走 cv2.TM_SQDIFF + mask，**分值越低越像**，"
        "2000000 实际是“接受任意位置”。SQDIFF 分数与 TM_CCOEFF_NORMED 不可换算，"
        "这里退化为普通的 >= 0.7 相关匹配；mask 语义在 MaaFramework 里只能用 green_mask 近似，本脚本未启用。",
    ),
    "auto.click_element(<text>, 'text'...)": (
        "UNMAPPED", "OCR 识别（recognition: \"OCR\" + expected + roi）",
        "需要 MaaFramework 的 OCR 模型资源，本资源包未附带模型，"
        "且 OCR 结果依赖手机端渲染的字号/字体，静态模板无法保证。默认降级为 DoNothing 占位节点。",
    ),
    "auto.click_element((<x>/1920,<y>/1080,<w>/1920,<h>/1080), 'crop')": (
        "MANAGED", "DirectHit + Click, target = [x, y, w, h] 像素矩形",
        "上游 find_type='crop' 分支就是“不做识别，直接点归一化矩形”，"
        "MaaFramework 的 DirectHit + Click(target=矩形) 完全等价。",
    ),
}


def mapping_row(info):
    """Return (status, mapping, notes) for one parsed expression."""
    kind = info.get("kind")
    if kind == "click" and info.get("find_type") == "text":
        return FORM_MAPPING["auto.click_element(<text>, 'text'...)"]
    if kind == "unknown":
        return ("UNMAPPED", "无 —— 表达式不是本脚本认识的三种调用之一",
                "上游用 eval() 执行任意 Python，本脚本只覆盖 screens.json 里实际出现的三种方法。")
    return FORM_MAPPING.get(canonical_form(info),
                            ("UNMAPPED", "无", "未在映射表中登记的形式。"))


# --------------------------------------------------------------------------- #
# screens.json loading
# --------------------------------------------------------------------------- #


def load_screens(source):
    """Prefer ``git show HEAD:assets/config/screens.json``; fall back to the working tree."""
    provenance = {}
    raw = run_git(source, "show", "HEAD:" + SCREENS_CONFIG)
    if raw:
        provenance["method"] = "git show HEAD:" + SCREENS_CONFIG
    else:
        path = os.path.join(source, SCREENS_CONFIG)
        if not os.path.exists(path):
            raise SystemExit("[FATAL] 找不到 %s：git show 失败且工作区也没有该文件" % SCREENS_CONFIG)
        with open(path, "rb") as fh:
            raw = fh.read()
        provenance["method"] = "working tree " + path
    provenance["bytes"] = len(raw)
    provenance["sha256"] = sha256_bytes(raw)
    data = load_json_loose(raw)
    if not isinstance(data, list):
        raise SystemExit("[FATAL] screens.json 的顶层不是 JSON 数组，实际是 %s" % type(data).__name__)
    return data, provenance


def normalise_image_paths(image_path):
    if isinstance(image_path, list):
        return list(image_path)
    return [image_path]


def template_rel(image_path):
    """``./assets/images/screen/main.png`` -> ``screen/main.png`` (relative to image/)."""
    p = image_path.lstrip("./")
    if p.startswith(IMAGE_PREFIX):
        return p[len(IMAGE_PREFIX):]
    return os.path.basename(p)


# --------------------------------------------------------------------------- #
# Node builders
# --------------------------------------------------------------------------- #


def tm_node(templates, thresholds, roi, action, action_params, nxt, attach):
    node = {"recognition": "TemplateMatch"}
    if len(templates) == 1:
        node["template"] = templates[0]
        node["threshold"] = thresholds[0]
    else:
        node["template"] = list(templates)
        node["threshold"] = list(thresholds)
    node["roi"] = list(roi)
    node["action"] = action
    if action_params:
        node.update(action_params)
    node["next"] = list(nxt)
    if attach:
        node["attach"] = attach
    return node


def dh_node(nxt, attach=None, action="DoNothing", action_params=None, post_delay=None):
    node = {"recognition": "DirectHit"}
    node["action"] = action
    if action_params:
        node.update(action_params)
    if post_delay is not None:
        node["post_delay"] = post_delay
    node["next"] = list(nxt)
    if attach:
        node["attach"] = attach
    return node


def denormalise_crop(crop):
    """Normalised (x, y, w, h) at 1920x1080 -> integer pixel roi, clamped."""
    x, y, w, h = crop
    rx = int(round(float(x) * DESIGN_W))
    ry = int(round(float(y) * DESIGN_H))
    rw = int(round(float(w) * DESIGN_W))
    rh = int(round(float(h) * DESIGN_H))
    rx = max(0, min(rx, DESIGN_W))
    ry = max(0, min(ry, DESIGN_H))
    rw = max(0, min(rw, DESIGN_W - rx))
    rh = max(0, min(rh, DESIGN_H - ry))
    return [rx, ry, rw, rh]


# --------------------------------------------------------------------------- #
# Task taxonomy  (Deliverable 2, item 4)
# --------------------------------------------------------------------------- #
# (task_id, label, group, entry_screen_id, source_module, confidence, rationale)
TASK_TABLE = [
    ("daily", "日常", "daily", "main", "tasks/daily/daily.py", "medium",
     "Daily 本身没有单一 change_to；它是调度器，把工作分给 buildtarget/fight/synthesis 等子模块"
     "（tasks/daily/daily.py:131 currency_wars_homepage、:142 divergent_main、:222 guide2 都是交接点）。"
     "入口取 main 是因为所有子任务都从主界面出发。"),
    ("buildtarget", "刷历战余响/凝滞虚影", "daily", "guide3", "tasks/daily/buildtarget.py", "high",
     "tasks/daily/buildtarget.py:545 显式 screen.change_to('guide3')。"),
    ("ember_exchange", "余烬兑换", "daily", "menu", "tasks/daily/ember_exchange.py", "high",
     "tasks/daily/ember_exchange.py:128 显式 screen.change_to('menu')；:414 回到 main。"),
    ("fight", "战斗", "daily", "main", "tasks/daily/fight.py", "high",
     "tasks/daily/fight.py:102 显式 screen.change_to('main')（:98 先切到配队界面）。"),
    ("himekotry", "姬子试用", "daily", "himeko_prepare", "tasks/daily/himekotry.py", "high",
     "tasks/daily/himekotry.py:11/22 显式 screen.change_to('himeko_prepare')。"),
    ("photo", "拍照", "daily", "camera", "tasks/daily/photo.py", "high",
     "tasks/daily/photo.py:14 显式 screen.change_to('camera')。"),
    ("redemption", "兑换码", "daily", "redemption", "tasks/daily/redemption.py", "high",
     "tasks/daily/redemption.py:197 显式 screen.change_to('redemption')。"),
    ("synthesis", "合成", "daily", "material", "tasks/daily/synthesis.py", "high",
     "tasks/daily/synthesis.py:17/50 显式 screen.change_to('material')。"),
    ("power", "清体力", "power", "guide3", "tasks/power/power.py", "high",
     "tasks/power/power.py:244 显式 screen.change_to('guide3')。"),
    ("instance", "副本", "power", "guide3", "tasks/power/instance.py", "high",
     "tasks/power/instance.py:88 显式 screen.change_to('guide3')。"),
    ("relicset", "遗器套装", "power", "bag_relicset", "tasks/power/relicset.py", "high",
     "tasks/power/relicset.py:43 显式 screen.change_to('bag_relicset')。"),
    ("weekly_relic_cleanup", "每周遗器清理", "power", "bag_relicset",
     "tasks/power/weekly_relic_cleanup.py", "high",
     "tasks/power/weekly_relic_cleanup.py:123 显式 screen.change_to('bag_relicset')。"),
    ("mail", "邮件", "reward", "mail", "tasks/reward/__init__.py", "high",
     "RewardManager 构造 Mail('邮件', …, 'mail')，第三参数即入口屏幕（tasks/reward/__init__.py）。"),
    ("assist", "支援", "reward", "visa", "tasks/reward/__init__.py", "high",
     "RewardManager 构造 Assist('支援', …, 'visa')。"),
    ("dispatch", "委托", "reward", "dispatch", "tasks/reward/__init__.py", "high",
     "RewardManager 构造 Dispatch('委托', …, 'dispatch')。"),
    ("quest", "每日实训", "reward", "guide2", "tasks/reward/__init__.py", "high",
     "RewardManager 构造 Quest('每日实训', …, 'guide2')。"),
    ("srpass", "无名勋礼", "reward", "pass1", "tasks/reward/srpass.py", "high",
     "tasks/reward/srpass.py:14 显式 screen.change_to('pass1')。"),
    ("achievement", "成就", "reward", "achievement", "tasks/reward/__init__.py", "high",
     "RewardManager 构造 Achievement('成就', …, 'achievement')。"),
    ("message", "短信", "reward", "incoming_message", "tasks/reward/__init__.py", "high",
     "RewardManager 构造 Message('短信', …, 'incoming_message')。"),
    ("currency_wars", "货币战争", "weekly", "currency_wars_homepage",
     "tasks/weekly/currency_wars.py", "high",
     "tasks/weekly/currency_wars.py:2977 check_screen('currency_wars_homepage')（:317 先 change_to('guide3')）。"),
    ("divergent_universe", "差分宇宙", "weekly", "divergent_mode_select",
     "tasks/weekly/divergent_universe.py", "high",
     "tasks/weekly/divergent_universe.py:178 显式 screen.change_to('divergent_mode_select')。"),
    ("echoofwar", "历战余响", "weekly", "guide3", "tasks/weekly/echoofwar.py", "high",
     "tasks/weekly/echoofwar.py:16 显式 screen.change_to('guide3')。"),
    ("universe", "模拟宇宙", "weekly", "divergent_main", "tasks/weekly/universe.py", "high",
     "tasks/weekly/universe.py:184 显式 screen.change_to('divergent_main')。"),
    ("apocalyptic", "末日幻影", "challenge", "guide4", "tasks/challenge/apocalyptic.py", "high",
     "tasks/challenge/apocalyptic.py:29 显式 screen.change_to('guide4')。"),
    ("memoryofchaos", "混沌回忆", "challenge", "guide4", "tasks/challenge/memoryofchaos.py", "high",
     "tasks/challenge/memoryofchaos.py:29 显式 screen.change_to('guide4')。"),
    ("memoryone", "单层忘却之庭", "challenge", "memory", "tasks/challenge/memoryone.py", "high",
     "tasks/challenge/memoryone.py:31 显式 screen.change_to('memory')。"),
    ("purefiction", "虚构叙事", "challenge", "guide4", "tasks/challenge/purefiction.py", "high",
     "tasks/challenge/purefiction.py:29 显式 screen.change_to('guide4')。"),
    ("activity", "活动", "activity", "activity", "tasks/activity/__init__.py", "high",
     "tasks/activity/__init__.py:58 与 tasks/activity/activitytemplate.py:23 都显式 screen.change_to('activity')。"),
    ("journey_highlights_notification", "旅程回顾通知", "activity", "reward_guide",
     "tasks/activity/journey_highlights_notification.py", "high",
     "tasks/activity/journey_highlights_notification.py:16 显式 screen.change_to('reward_guide')。"),
    ("team", "配队", "base", "configure_team", "tasks/base/team.py", "high",
     "tasks/base/team.py:18 显式 screen.change_to('configure_team')。"),
]

TASK_SKIPPED = [
    ("tasks/daily/tasks.py", "纯配置/OCR 归并工具类（class Tasks），不含任何屏幕导航调用。"),
    ("tasks/power/character.py", "支援角色选择辅助类（class Character），不含屏幕导航调用。"),
    ("tasks/base/base.py", "抽象基类，无导航。"),
    ("tasks/base/tasks.py", "子进程启动器（class Tasks），无导航。"),
    ("tasks/base/download.py", "下载工具，无导航。"),
    ("tasks/base/fastest_mirror.py", "测速工具（class FastestMirror），无导航。"),
    ("tasks/base/genshin_starRail_fps_unlocker.py", "Windows 帧率解锁，纯桌面侧行为，Android 上无对应物。"),
    ("tasks/base/pythonchecker.py", "Python 环境自检，桌面侧行为。"),
    ("tasks/base/screen_test.py", "开发者用的屏幕切换冒烟测试（tasks/base/screen_test.py:32/42），不是玩家功能。"),
    ("tasks/game/starrailcontroller.py", "Windows 分辨率/HDR 控制（class StarRailController(LocalGameController)），不是导航，Android 上无对应物。"),
    ("tasks/tool/screenshot.py", "631 行的 PySide6 截图 GUI 工具，桌面侧。"),
    ("tasks/tool/autoplot/*", "地图自动标点开发工具（_config/_detector/_engine），依赖 PC 截图，不是玩家功能。"),
    ("tasks/version/app_update.py", "自身更新器，与本资源包无关。"),
    ("tasks/activity/activitytemplate.py", "Activity 的抽象基类，其导航已并入 activity 任务。"),
]

GROUP_TABLE = [
    ("daily", "日常"),
    ("power", "体力"),
    ("reward", "奖励"),
    ("weekly", "周常"),
    ("challenge", "挑战"),
    ("activity", "活动"),
    ("base", "基础"),
]


# --------------------------------------------------------------------------- #
# Minimal JSON Schema validator (draft 2020-12 subset)
# --------------------------------------------------------------------------- #
#
# stdlib-only, so we implement the keywords the MaaFramework schemas actually
# use.  Anything we cannot resolve (``$ref`` to a sibling file we do not have)
# is counted and reported instead of silently passing.

_TYPE_MAP = {
    "object": dict,
    "array": list,
    "string": str,
    "boolean": bool,
    "null": type(None),
}


def _json_type_ok(inst, want):
    if want == "integer":
        return isinstance(inst, int) and not isinstance(inst, bool)
    if want == "number":
        return isinstance(inst, (int, float)) and not isinstance(inst, bool)
    if want == "boolean":
        return isinstance(inst, bool)
    if want == "null":
        return inst is None
    if want in ("array", "object", "string"):
        if want == "object":
            return isinstance(inst, dict)
        if want == "array":
            return isinstance(inst, list)
        return isinstance(inst, str)
    return True


class SchemaValidator:
    MAX_DEPTH = 80

    def __init__(self, doc, base_dir, label):
        self.doc = doc
        self.base_dir = base_dir
        self.label = label
        self.errors = []
        self.unresolved_refs = set()
        self._file_cache = {}

    # -- $ref ------------------------------------------------------------- #
    def _resolve(self, ref):
        path, _, frag = ref.partition("#")
        if path:
            target_file = os.path.normpath(os.path.join(self.base_dir, path))
            if target_file not in self._file_cache:
                try:
                    with open(target_file, "r", encoding="utf-8-sig") as fh:
                        self._file_cache[target_file] = load_json_loose(fh.read())
                except Exception:
                    self._file_cache[target_file] = None
            doc = self._file_cache[target_file]
            if doc is None:
                self.unresolved_refs.add(ref)
                return None
        else:
            doc = self.doc
        if not frag or frag == "/":
            return doc
        node = doc
        for part in frag.lstrip("/").split("/"):
            part = part.replace("~1", "/").replace("~0", "~")
            if isinstance(node, dict) and part in node:
                node = node[part]
            else:
                self.unresolved_refs.add(ref)
                return None
        return node

    # -- main walk -------------------------------------------------------- #
    def check(self, inst, sch, path="$", depth=0):
        """Validate and return the set of *evaluated* property names."""
        errors, evaluated = self._check(inst, sch, path, depth)
        self.errors.extend(errors)
        return evaluated

    def _check(self, inst, sch, path, depth):
        errors, evaluated = [], set()
        if depth > self.MAX_DEPTH:
            return errors, evaluated
        if not isinstance(sch, dict):
            return errors, evaluated

        if "$ref" in sch:
            target = self._resolve(sch["$ref"])
            if target is not None:
                e, ev = self._check(inst, target, path, depth + 1)
                errors.extend(e)
                evaluated |= ev

        for sub in sch.get("allOf", []) or []:
            e, ev = self._check(inst, sub, path, depth + 1)
            errors.extend(e)
            evaluated |= ev

        for branch in ("anyOf", "oneOf"):
            if branch in sch:
                branches = sch[branch] or []
                matched_any = False
                merged = set()
                for sub in branches:
                    e, ev = self._check(inst, sub, path, depth + 1)
                    if not e:
                        matched_any = True
                        merged |= ev
                if not matched_any:
                    errors.append("%s: no %s branch matched (tried %d)"
                                  % (path, branch, len(branches)))
                evaluated |= merged

        if "not" in sch:
            e, _ = self._check(inst, sch["not"], path, depth + 1)
            if not e:
                errors.append("%s: matched a `not` schema" % path)

        if "if" in sch:
            e_if, ev_if = self._check(inst, sch["if"], path, depth + 1)
            if not e_if:
                evaluated |= ev_if
                if "then" in sch:
                    e, ev = self._check(inst, sch["then"], path, depth + 1)
                    errors.extend(e)
                    evaluated |= ev
            elif "else" in sch:
                e, ev = self._check(inst, sch["else"], path, depth + 1)
                errors.extend(e)
                evaluated |= ev

        if "dependentSchemas" in sch and isinstance(inst, dict):
            for key, sub in (sch["dependentSchemas"] or {}).items():
                if key in inst:
                    e, ev = self._check(inst, sub, path, depth + 1)
                    errors.extend(e)
                    evaluated |= ev
        if "dependentRequired" in sch and isinstance(inst, dict):
            for key, required in (sch["dependentRequired"] or {}).items():
                if key in inst:
                    for r in required:
                        if r not in inst:
                            errors.append("%s: '%s' present but required friend '%s' missing"
                                          % (path, key, r))

        if "type" in sch:
            wants = sch["type"]
            wants = wants if isinstance(wants, list) else [wants]
            if not any(_json_type_ok(inst, w) for w in wants):
                errors.append("%s: expected type %s, got %s"
                              % (path, "/".join(wants), type(inst).__name__))
                return errors, evaluated

        if "enum" in sch and inst not in sch["enum"]:
            errors.append("%s: value %r not in enum %r" % (path, inst, sch["enum"]))
        if "const" in sch and inst != sch["const"]:
            errors.append("%s: expected const %r, got %r" % (path, sch["const"], inst))

        if isinstance(inst, dict):
            props = sch.get("properties") or {}
            for key, sub in props.items():
                if key in inst:
                    evaluated.add(key)
                    e, _ = self._check(inst[key], sub, "%s.%s" % (path, key), depth + 1)
                    errors.extend(e)
            for pat, sub in (sch.get("patternProperties") or {}).items():
                try:
                    rx = re.compile(pat)
                except re.error:
                    continue
                for key in inst:
                    if rx.search(key):
                        evaluated.add(key)
                        e, _ = self._check(inst[key], sub, "%s.%s" % (path, key), depth + 1)
                        errors.extend(e)
            for key in sch.get("required", []) or []:
                if key not in inst:
                    errors.append("%s: missing required property '%s'" % (path, key))
            if "minProperties" in sch and len(inst) < sch["minProperties"]:
                errors.append("%s: fewer than %d properties" % (path, sch["minProperties"]))
            if "maxProperties" in sch and len(inst) > sch["maxProperties"]:
                errors.append("%s: more than %d properties" % (path, sch["maxProperties"]))
            if sch.get("additionalProperties", True) is False:
                for key in inst:
                    if key not in evaluated:
                        errors.append("%s: additional property '%s' not allowed" % (path, key))
            elif isinstance(sch.get("additionalProperties"), dict):
                for key in inst:
                    if key not in evaluated:
                        evaluated.add(key)
                        e, _ = self._check(inst[key], sch["additionalProperties"],
                                           "%s.%s" % (path, key), depth + 1)
                        errors.extend(e)
            if sch.get("unevaluatedProperties", True) is False:
                for key in inst:
                    if key not in evaluated:
                        errors.append("%s: unevaluated property '%s'" % (path, key))

        if isinstance(inst, list):
            if "items" in sch:
                sub = sch["items"]
                if isinstance(sub, list):
                    for idx, item in enumerate(inst):
                        s = sub[idx] if idx < len(sub) else None
                        if s is not None:
                            e, _ = self._check(item, s, "%s[%d]" % (path, idx), depth + 1)
                            errors.extend(e)
                else:
                    for idx, item in enumerate(inst):
                        e, _ = self._check(item, sub, "%s[%d]" % (path, idx), depth + 1)
                        errors.extend(e)
            if "minItems" in sch and len(inst) < sch["minItems"]:
                errors.append("%s: fewer than %d items" % (path, sch["minItems"]))
            if "maxItems" in sch and len(inst) > sch["maxItems"]:
                errors.append("%s: more than %d items" % (path, sch["maxItems"]))
            if "uniqueItems" in sch and sch["uniqueItems"]:
                seen = []
                for item in inst:
                    if item in seen:
                        errors.append("%s: duplicate item %r" % (path, item))
                        break
                    seen.append(item)

        if isinstance(inst, str):
            if "minLength" in sch and len(inst) < sch["minLength"]:
                errors.append("%s: shorter than %d chars" % (path, sch["minLength"]))
            if "maxLength" in sch and len(inst) > sch["maxLength"]:
                errors.append("%s: longer than %d chars" % (path, sch["maxLength"]))
            if "pattern" in sch:
                try:
                    if not re.search(sch["pattern"], inst):
                        errors.append("%s: does not match pattern %r" % (path, sch["pattern"]))
                except re.error:
                    pass
        if isinstance(inst, (int, float)) and not isinstance(inst, bool):
            if "minimum" in sch and inst < sch["minimum"]:
                errors.append("%s: %r < minimum %r" % (path, inst, sch["minimum"]))
            if "maximum" in sch and inst > sch["maximum"]:
                errors.append("%s: %r > maximum %r" % (path, inst, sch["maximum"]))
            if "exclusiveMinimum" in sch and inst <= sch["exclusiveMinimum"]:
                errors.append("%s: %r <= exclusiveMinimum %r" % (path, inst, sch["exclusiveMinimum"]))
            if "exclusiveMaximum" in sch and inst >= sch["exclusiveMaximum"]:
                errors.append("%s: %r >= exclusiveMaximum %r" % (path, inst, sch["exclusiveMaximum"]))
            if "multipleOf" in sch and sch["multipleOf"]:
                if abs(inst / sch["multipleOf"] - round(inst / sch["multipleOf"])) > 1e-9:
                    errors.append("%s: %r is not a multiple of %r" % (path, inst, sch["multipleOf"]))
        return errors, evaluated


def validate_with(schema_path, instances):
    """Validate ``instances`` (label -> object) against one schema file."""
    with open(schema_path, "r", encoding="utf-8-sig") as fh:
        doc = load_json_loose(fh.read())
    result = {"schema": schema_path, "checked": len(instances), "errors": [],
              "unresolved_refs": [], "ok": True}
    base_dir = os.path.dirname(schema_path)
    for label, inst in sorted(instances.items()):
        v = SchemaValidator(doc, base_dir, label)
        v.check(inst, doc)
        for err in v.errors:
            result["errors"].append("%s %s" % (label, err))
        result["unresolved_refs"].extend(sorted(v.unresolved_refs))
    result["unresolved_refs"] = sorted(set(result["unresolved_refs"]))
    result["ok"] = not result["errors"]
    return result


# --------------------------------------------------------------------------- #
# Image copying
# --------------------------------------------------------------------------- #


def list_source_images(source):
    raw = run_git(source, "ls-files", IMAGES_DIR)
    if raw is None:
        raise SystemExit("[FATAL] git ls-files %s 失败" % IMAGES_DIR)
    rels = [ln for ln in raw.decode("utf-8", "replace").splitlines() if ln.strip()]
    return sorted(rels)


def git_cat_file_batch(source, rels):
    """Read many blobs with ONE ``git cat-file --batch`` process (request/response)."""
    proc = subprocess.Popen(
        ["git", "-C", source, "cat-file", "--batch"],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
    )
    out = {}
    try:
        for rel in rels:
            proc.stdin.write(("HEAD:" + rel + "\n").encode("utf-8"))
            proc.stdin.flush()
            header = proc.stdout.readline()
            if not header:
                out[rel] = None
                continue
            parts = header.decode("utf-8", "replace").strip().split()
            if len(parts) < 3:
                out[rel] = None
                continue
            try:
                size = int(parts[2])
            except ValueError:
                out[rel] = None
                continue
            out[rel] = proc.stdout.read(size)
            proc.stdout.read(1)
    finally:
        try:
            proc.stdin.close()
        except Exception:
            pass
        proc.kill()
        proc.wait()
    return out


def dir_has_underscore_component(rel_path):
    parts = rel_path.replace("\\", "/").split("/")
    return any(p.startswith("_") for p in parts[:-1])


# --------------------------------------------------------------------------- #
# Main
# --------------------------------------------------------------------------- #


def build_parser():
    p = argparse.ArgumentParser(
        prog="migrate_march7th.py",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        description="把 March7thAssistant 的屏幕导航图 + 任务结构迁移成 MaaPocket 的 PI-V2 资源包。",
        epilog=(
            "注意 --include-unsupported 的语义：默认情况下无法映射的动作会变成名字以 __UNMAPPED_<n>\n"
            "结尾的 DoNothing 占位节点（保留链条完整）；加上该开关后，含有无法映射动作的**整条边**会被\n"
            "丢弃并记入报告。这是任务书要求的字面语义。\n"
        ),
    )
    p.add_argument("--source", default=DEFAULT_SOURCE,
                   help="March7thAssistant 检出目录（默认 %s）" % DEFAULT_SOURCE)
    p.add_argument("--out", default=DEFAULT_OUT,
                   help="PI-V2 资源包输出目录（默认 %s）" % DEFAULT_OUT)
    p.add_argument("--schemas", default=None,
                   help="包含 pipeline.schema.json / interface.schema.json 的目录")
    p.add_argument("--force", action="store_true",
                   help="先清空 --out 再生成")
    p.add_argument("--include-unsupported", action="store_true",
                   help="丢弃含无法映射动作的边（见 epilog）")
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)

    source = os.path.abspath(args.source)
    out = os.path.abspath(args.out)
    if not os.path.isdir(source):
        raise SystemExit("[FATAL] --source 不是目录：%s" % source)
    if os.path.normcase(out) in (os.path.normcase(REPO_DIR), os.path.normcase(source),
                                 os.path.normcase(os.path.dirname(out))):
        raise SystemExit("[FATAL] --out 太靠上（%s），拒绝写入" % out)

    report = {
        "schema_version": 1,
        "generator": os.path.basename(os.path.abspath(__file__)),
        "source": {"path": source},
        "out": out,
        "options": {
            "force": bool(args.force),
            "include_unsupported": bool(args.include_unsupported),
            "schemas": args.schemas,
        },
        "design_resolution": {"width": DESIGN_W, "height": DESIGN_H},
        "screen_threshold": SCREEN_THRESHOLD,
    }

    head = run_git(source, "rev-parse", "HEAD")
    report["source"]["git_head"] = head.decode().strip() if head else None

    # -- 1. load screens.json --------------------------------------------- #
    screens, provenance = load_screens(source)
    report["source"]["screens_json"] = provenance

    # -- 2. wipe / interface.json replacement ------------------------------ #
    iface_path = os.path.join(out, "interface.json")
    replaced_interface = None
    if os.path.isfile(iface_path):
        with open(iface_path, "rb") as fh:
            old = fh.read()
        try:
            old_obj = load_json_loose(old)
        except Exception:
            old_obj = None
        ctl = old_obj.get("controller") if isinstance(old_obj, dict) else None
        looks_like_ours = bool(
            isinstance(ctl, list) and ctl and isinstance(ctl[0], dict)
            and ctl[0].get("attach_resource_path") == ["./resource"]
        )
        replaced_interface = {
            "path": os.path.relpath(iface_path, REPO_DIR).replace("\\", "/"),
            "bytes": len(old),
            "sha256": sha256_bytes(old),
            "classification": ("previous-run-output" if looks_like_ours
                               else "placeholder-from-another-agent"),
        }
        if not looks_like_ours:
            replaced_interface["reasons"] = [
                "controller[0] 缺少 attach_resource_path（interface.schema.json 允许但 PI-V2 惯例要求）",
                "顶层存在 interface.schema.json 未定义的 `message` 键",
                "resource[0].name 是「官服」而不是本次统一使用的「国服」",
                "task 为空数组，没有任何迁移出来的任务",
            ]
    if args.force and os.path.isdir(out):
        shutil.rmtree(out)
        report["out_cleaned"] = True
    else:
        report["out_cleaned"] = False
        if os.path.isfile(iface_path):
            os.remove(iface_path)
    report["interface_json_replacement"] = replaced_interface
    # Facts about the placeholder that the task brief told us to delete.  It is
    # recorded statically because after the first successful run it no longer
    # exists on disk, so a later re-run can no longer observe it.
    report["original_placeholder_interface"] = {
        "path": "MaaPocket/app-hsr/src/main/assets/pi/interface.json",
        "observed_size_bytes": 637,
        "observed_lines": 30,
        "author": "another agent (guess, not generated by this script)",
        "defects": [
            "controller[0].type == \"Adb\" 但没有 attach_resource_path",
            "顶层 `message` 键在 interface.schema.json 中不存在",
            "task == [] —— 没有任何任务",
            "languages 指向 locales/interface/zh_cn.json，而本文档生成的是 resource/locale/zh_cn.json",
        ],
        "disposition": "deleted and replaced by this script (see interface_json_replacement)",
    }

    os.makedirs(out, exist_ok=True)

    # -- 3. census every expression --------------------------------------- #
    edge_records = []           # flat list of all 110 edges + 5 timeout lists
    expressions = []            # (raw, info, origin) for every actions_list entry
    screen_by_id = {}
    screen_order = []
    for screen in screens:
        sid = screen["id"]
        screen_by_id[sid] = screen
        screen_order.append(sid)
        for edge_idx, action in enumerate(screen.get("actions", []) or []):
            for expr in action.get("actions_list", []) or []:
                info = parse_expression(expr)
                expressions.append({"raw": expr, "info": info,
                                    "origin": {"from": sid, "edge": edge_idx,
                                               "kind": "actions_list"}})
            for expr in action.get("actions_list_on_timeout", []) or []:
                info = parse_expression(expr)
                expressions.append({"raw": expr, "info": info,
                                    "origin": {"from": sid, "edge": edge_idx,
                                               "kind": "actions_list_on_timeout"}})
            edge_records.append({
                "from": sid, "index": edge_idx,
                "target_screen": action.get("target_screen"),
                "actions_list": list(action.get("actions_list", []) or []),
                "actions_list_on_timeout": list(action.get("actions_list_on_timeout", []) or []),
            })

    # per-edge graphs
    adjacency = {}
    edges_by_pair = {}
    for screen in screens:
        sid = screen["id"]
        adjacency[sid] = [a.get("target_screen") for a in (screen.get("actions") or [])]
    for e in edge_records:
        edges_by_pair.setdefault((e["from"], e["target_screen"]), []).append(e)

    # census
    census = {}
    for item in expressions:
        form = canonical_form(item["info"]) if item["info"]["ok"] else "<unparsed>"
        entry = census.setdefault(form, {"form": form, "count": 0, "examples": [],
                                         "ok": True, "mappable": True})
        entry["count"] += 1
        if len(entry["examples"]) < 4 and item["raw"] not in entry["examples"]:
            entry["examples"].append(item["raw"])
        entry["ok"] = entry["ok"] and item["info"]["ok"]
        entry["mappable"] = entry["mappable"] and item["info"].get("mappable", False)
    for form, entry in census.items():
        rep_info = None
        for item in expressions:
            f = canonical_form(item["info"]) if item["info"]["ok"] else "<unparsed>"
            if f == form:
                rep_info = item["info"]
                break
        status, mapping, notes = mapping_row(rep_info or {"kind": "unknown"})
        entry["status"] = status
        entry["mapping"] = mapping
        entry["notes"] = notes
    report["expression_census"] = sorted(census.values(), key=lambda d: -d["count"])

    unmapped_exprs = [it for it in expressions if is_unmapped(it["info"])]
    report["unmapped_expressions"] = [
        {"raw": it["raw"], "reason": it["info"]["note"], "origin": it["origin"],
         "planned_recognition": (
             "recognition: \"OCR\" + expected=%r + roi=%r"
             % (it["info"].get("expected"),
                denormalise_crop(it["info"]["crop"]) if it["info"].get("crop") else [0, 0, 0, 0])
             if it["info"].get("find_type") == "text" else None),
         "planned_handling": (
             "edge omitted" if args.include_unsupported
             else "DoNothing 占位节点，名字以 __UNMAPPED_<n> 结尾")}
        for it in unmapped_exprs
    ]

    # -- 4. images --------------------------------------------------------- #
    image_rels = list_source_images(source)
    usable_rels, skipped_rels = [], []
    for rel in image_rels:
        inner = rel[len(IMAGE_PREFIX):] if rel.startswith(IMAGE_PREFIX) else rel
        if dir_has_underscore_component(inner):
            skipped_rels.append(rel)
        else:
            usable_rels.append(rel)
    blobs = git_cat_file_batch(source, usable_rels)
    image_root = os.path.join(out, "resource", "image")
    copied, missing, image_bytes = 0, [], 0
    for rel in usable_rels:
        data = blobs.get(rel)
        if data is None:
            missing.append(rel)
            continue
        inner = rel[len(IMAGE_PREFIX):] if rel.startswith(IMAGE_PREFIX) else rel
        write_bytes(os.path.join(image_root, inner.replace("/", os.sep)), data)
        copied += 1
        image_bytes += len(data)
    report["images"] = {
        "source_files": len(image_rels),
        "copied": copied,
        "skipped_underscore_dirs": skipped_rels,
        "missing_in_git": missing,
        "bytes": image_bytes,
    }

    # -- 5. pipeline ------------------------------------------------------- #
    pipeline_dir = os.path.join(out, "resource", "pipeline")
    pipeline_files = {}          # abs path -> object
    generated = []               # (relpath, bytes, sha256)
    unmapped_counter = [0]
    unmapped_nodes = []
    omitted_edges = []

    def add_file(path, obj):
        size, digest = dump_json(path, obj)
        generated.append({
            "path": os.path.relpath(path, out).replace("\\", "/"),
            "bytes": size, "sha256": digest,
        })
        return obj

    # 5a. per-screen files -------------------------------------------------
    edge_covered = 0
    screen_nodes = {}
    edge_first_node = {}         # (from, to, edge_index) -> first chain node name
    pending_nodes = {}           # screen id -> node dict, filled in pass 1 and
                                 # completed with the router in pass 2
    for sid in screen_order:
        screen_nodes[sid] = "Screen_%s" % sid
    for sid in screen_order:
        screen = screen_by_id[sid]
        templates = [template_rel(p) for p in normalise_image_paths(screen["image_path"])]
        thresholds = [SCREEN_THRESHOLD] * len(templates)
        node_name = "Screen_%s" % sid
        nodes = {}
        # DECISION: a screen anchor is *terminal* (next == []).  Every edge chain
        # head is DirectHit, so if the anchors chained into their outgoing edges,
        # MaaFramework's "first recognised entry in next wins" rule would fire
        # the first outgoing edge the instant the screen was entered, regardless
        # of the caller's goal (entering Screen_main would immediately press esc).
        # Navigation is therefore driven by the explicit NavTo_<id> router below.
        nodes[node_name] = tm_node(
            templates, thresholds, [0, 0, 0, 0], "DoNothing", None, [],
            {
                "march7th:kind": "screen-anchor",
                "march7th:screen_id": sid,
                "march7th:screen_name": screen.get("name"),
                "march7th:source_image_path": screen["image_path"],
                "march7th:note": ("模板是 1920x1080 PC 客户端截图的小块裁剪，"
                                  "手机上必须重新采集，见 RECAPTURE.md"),
            },
        )

        # outgoing edge chains, in file order
        actions = screen.get("actions", []) or []
        seen_pairs = {}
        for edge_idx, action in enumerate(actions):
            target = action.get("target_screen")
            actions_list = list(action.get("actions_list", []) or [])
            pair = (sid, target)
            dup = seen_pairs.get(pair, 0)
            seen_pairs[pair] = dup + 1
            suffix = "" if dup == 0 else "__e%d" % edge_idx

            exprs = [parse_expression(e) for e in actions_list]
            if any(is_unmapped(e) for e in exprs) and args.include_unsupported:
                omitted_edges.append({
                    "from": sid, "to": target, "edge_index": edge_idx,
                    "reason": "edge contains action(s) that cannot be mapped",
                    "unmappable": [e["raw"] for e in exprs if is_unmapped(e)],
                })
                continue

            names = ["%s__%s%s__%d" % (sid, target, suffix, i) for i in range(len(exprs))]
            if not names:
                # zero-action edge: an edge with an empty actions_list still
                # means "this transition exists"; emit a single DirectHit hop.
                names = ["%s__%s%s__0" % (sid, target, suffix)]
                exprs = [None]

            for i, info in enumerate(exprs):
                nxt = [names[i + 1]] if i + 1 < len(names) else [screen_nodes[target]]
                name = names[i]
                if info is None:
                    nodes[name] = dh_node(nxt, {"march7th:edge": [sid, target],
                                                "march7th:edge_index": edge_idx,
                                                "march7th:note": "上游 actions_list 为空"})
                    continue
                if is_unmapped(info):
                    unmapped_counter[0] += 1
                    name = "%s__UNMAPPED_%d" % (name, unmapped_counter[0])
                    names[i] = name
                    if i > 0:
                        prev = nodes[names[i - 1]]
                        prev["next"] = [name] if len(prev["next"]) == 1 else prev["next"]
                    nodes[name] = dh_node(
                        nxt,
                        {
                            "march7th:edge": [sid, target],
                            "march7th:edge_index": edge_idx,
                            "march7th:action_index": i,
                            "march7th:unmapped_expression": info["raw"],
                            "march7th:unmapped_reason": info["note"],
                            "march7th:planned_recognition": (
                                "OCR (recognition:\"OCR\" + expected + roi)"
                                if info.get("find_type") == "text" else None),
                            "march7th:parsed": info.get("ok", False),
                            "march7th:find_type": info.get("find_type"),
                        },
                    )
                    unmapped_nodes.append({"node": name, "expression": info["raw"],
                                           "reason": info["note"],
                                           "origin": {"from": sid, "to": target, "index": i}})
                    continue
                nodes[name] = build_action_node(info, nxt, sid, target, edge_idx, i)
            edge_first_node[(sid, target, edge_idx)] = names[0]
            edge_covered += 1

        pending_nodes[sid] = nodes

    # PASS 2 — routers.  This MUST be a separate pass over screen_order: a
    # guard for the edge <pred> -> <sid> needs edge_first_node[(pred, sid, ...)],
    # and in pass 1 that entry is only created when `pred` is itself walked.  A
    # predecessor that appears LATER in screens.json than the target would not
    # exist yet, which silently dropped 59 of the 110 guards on the first
    # implementation (found by auditing that every NavTo_*.next entry resolved
    # and that guard count == 110).
    for sid in screen_order:
        nodes = pending_nodes[sid]

        # NavTo router: goal-directed single-hop navigation.
        # DECISION: a fully general multi-hop router would need to materialise
        # ~2313 (target, source) BFS paths; that is far outside the brief's
        # one-file-per-screen layout.  NavTo_<B> covers every *single-hop* edge
        # exactly and degrades to a real screen check when the screen is already
        # the goal (Screen_<B> is a TemplateMatch anchor, so "already there"
        # succeeds and "somewhere else with no known single hop" fails honestly
        # instead of silently doing nothing).
        #
        # DECISION (ordering): `next` is "try each entry in order, run the first
        # one that is *recognised*".  The predecessor guards are TemplateMatch
        # nodes, so they must come FIRST; the DirectHit-free Screen_<B> anchor is
        # the last fallback.  Putting the anchor first would swallow everything.
        predicates = sorted({(e["from"], e["index"]) for e in edge_records
                             if e["target_screen"] == sid})
        nav_next, nav_guards, routed_preds = [], [], []
        for pred in sorted({p[0] for p in predicates}):
            first_edge = min(idx for (p, idx) in predicates if p == pred)
            chain_head = edge_first_node.get((pred, sid, first_edge))
            if chain_head is None:
                # the edge was omitted by --include-unsupported; nothing to route to
                continue
            guard = "Nav_%s_from_%s" % (sid, pred)
            nav_guards.append(guard)
            routed_preds.append(pred)
            pred_templates = [template_rel(p)
                              for p in normalise_image_paths(screen_by_id[pred]["image_path"])]
            nodes[guard] = tm_node(
                pred_templates, [SCREEN_THRESHOLD] * len(pred_templates),
                [0, 0, 0, 0], "DoNothing", None, [chain_head],
                {"march7th:kind": "nav-guard",
                 "march7th:note": "在 %s 上时，沿上游第一条 %s -> %s 边前进"
                                  % (pred, pred, sid)},
            )
            nav_next.append(guard)
        nav_next.append(screen_nodes[sid])
        nodes["NavTo_%s" % sid] = dh_node(
            nav_next,
            {"march7th:kind": "nav-router",
             "march7th:target_screen": sid,
             "march7th:predecessors": [p for (p, _i) in predicates],
             "march7th:routed_predecessors": routed_preds,
             "march7th:note": ("DirectHit 进入：先按顺序试探各前驱屏幕守卫节点"
                               "（TemplateMatch 前驱屏幕锚点），命中则沿那条上游边导航；"
                               "全部未命中则回退到 Screen_%s 锚点——若此时已经在 %s 上即成功，"
                               "否则本节点识别失败（诚实失败，不静默跳过）。只覆盖单跳。" % (sid, sid))},
        )
        add_file(os.path.join(pipeline_dir, "screen", "%s.json" % sid), nodes)
        pipeline_files[sid] = nodes

    # 5b. _index.json ------------------------------------------------------
    index_nodes = {
        "ScreenIndex": dh_node(
            [screen_nodes[s] for s in screen_order],
            {
                "march7th:kind": "screen-index",
                "march7th:note": ("按 screens.json 的文件顺序依次尝试各屏幕锚点，"
                                  "第一个识别到的即当前所在屏幕"),
                "march7th:screens": [{"id": s, "name": screen_by_id[s].get("name"),
                                      "node": screen_nodes[s]} for s in screen_order],
                "march7th:adjacency": {s: adjacency[s] for s in screen_order},
                "march7th:edge_count": len(edge_records),
            },
        )
    }
    add_file(os.path.join(pipeline_dir, "screen", "_index.json"), index_nodes)

    # 5c. task entry nodes -------------------------------------------------
    task_entries = []
    task_group_names = {g[0] for g in GROUP_TABLE}
    for (tid, label, group, entry_screen, module, confidence, rationale) in TASK_TABLE:
        if entry_screen not in screen_nodes:
            raise SystemExit("[FATAL] 任务 %s 的入口屏幕 %s 不存在" % (tid, entry_screen))
        if group not in task_group_names:
            raise SystemExit("[FATAL] 任务 %s 的 group %s 未定义" % (tid, group))
        node_name = "Task_%s" % tid
        add_file(os.path.join(pipeline_dir, "task", "%s.json" % tid), {
            node_name: dh_node(
                ["NavTo_%s" % entry_screen],
                {
                    "march7th:kind": "task-entry",
                    "march7th:task_id": tid,
                    "march7th:entry_screen": entry_screen,
                    "march7th:source_module": module,
                    "march7th:confidence": confidence,
                    "march7th:rationale": rationale,
                    "march7th:note": ("本节点只负责把游戏导航到该功能所在的界面；"
                                      "上游任务的采集/战斗循环逻辑没有迁移。"),
                },
            )
        })
        task_entries.append({
            "name": tid, "label": label, "group": group,
            "entry": node_name, "entry_screen": entry_screen,
            "source_module": module, "confidence": confidence,
            "rationale": rationale,
        })
    report["tasks"] = task_entries
    report["tasks_skipped"] = [{"module": m, "reason": r} for m, r in TASK_SKIPPED]

    # -- 5d. cross-file node-name and reference integrity ------------------- #
    # MaaFramework merges every file under pipeline/ into one flat namespace,
    # so duplicate node names and dangling `next` references are real breakage.
    node_names, name_dupes, dangling = {}, [], []
    pipeline_objects = {}
    for g in generated:
        if not g["path"].startswith("resource/pipeline/"):
            continue
        with open(os.path.join(out, g["path"]), "r", encoding="utf-8") as fh:
            obj = json.load(fh)
        pipeline_objects[g["path"]] = obj
        for name in obj:
            if name in node_names:
                name_dupes.append({"name": name,
                                   "files": [node_names[name], g["path"]]})
            else:
                node_names[name] = g["path"]
    for path, obj in pipeline_objects.items():
        for name, node in obj.items():
            for ref in node.get("next", []) or []:
                if isinstance(ref, str) and ref not in node_names:
                    dangling.append({"from": name, "file": path, "ref": ref})
            tgt = node.get("target")
            if isinstance(tgt, str) and tgt not in node_names:
                dangling.append({"from": name, "file": path, "ref": tgt})
    report["pipeline_node_names"] = {
        "total": len(node_names), "duplicates": name_dupes,
        "dangling_references": dangling,
    }
    # Guard-coverage invariant: every single-hop edge <from> -> <to> must have a
    # Nav_<to>_from_<from> guard, i.e. one guard per DISTINCT (from, to) pair.
    # (Recomputing it here caught the pass-ordering bug that had silently
    # dropped 59 of 110 guards.)
    guard_names = set(n for n in node_names if n.startswith("Nav_") and "_from_" in n)
    expected_guards = set("Nav_%s_from_%s" % (to, frm)
                          for frm, tos in adjacency.items() for to in tos)
    report["nav_guard_coverage"] = {
        "guards_emitted": len(guard_names),
        "guards_expected": len(expected_guards),
        "missing": sorted(expected_guards - guard_names),
        "unexpected": sorted(guard_names - expected_guards),
        "ok": guard_names == expected_guards,
    }
    if guard_names != expected_guards:
        print("WARNING : nav guard coverage %d/%d (missing %d)"
              % (len(guard_names), len(expected_guards),
                 len(expected_guards - guard_names)))
    locale = {
        "interface_label": "崩坏：星穹铁道",
        "interface_title": "MaaPocket · 崩坏：星穹铁道",
        "interface_description": (
            "从 March7thAssistant 迁移而来的屏幕导航图与任务结构（仅导航，不含采集/战斗循环）。"
            "模板全部是 PC 客户端截图，手机端需要重新采集。"),
        "interface_welcome": "模板尚未在手机端重新采集，识别可能直接失败。请先读 RECAPTURE.md。",
        "controller_android_label": "Android（ADB）",
        "resource_cn_label": "国服",
    }
    for gid, glabel in GROUP_TABLE:
        locale["group_%s_label" % gid] = glabel
    for t in task_entries:
        locale["task_%s_label" % t["name"]] = t["label"]
        locale["task_%s_description" % t["name"]] = t["rationale"]
    add_file(os.path.join(out, "resource", "locale", "zh_cn.json"), locale)

    # -- 7. interface.json ------------------------------------------------- #
    interface = {
        "interface_version": 2,
        "name": "MaaPocketHSR",
        "version": "0.1.0",
        "label": "崩坏：星穹铁道",
        "title": "MaaPocket · 崩坏：星穹铁道",
        "description": locale["interface_description"],
        # NOTE: no `icon` key.  `interface.schema.json` accepts a relative
        # `icon` path, but March7thAssistant ships no logo asset (git ls-files
        # finds only app/common/icon.py and an unrelated chat-bubble png), and
        # we must not point at a file that does not exist.
        "welcome": locale["interface_welcome"],
        "license": "GPL-3.0",
        "github": "https://github.com/moesnow/March7thAssistant",
        "controller": [
            {
                "name": CONTROLLER_NAME,
                "type": "Adb",
                "label": locale["controller_android_label"],
                # DECISION: design resolution == the upstream PC pixel space.
                # display_short_side / display_long_side are documented as
                # mutually exclusive, so only one may be set.
                "display_long_side": DESIGN_W,
                "attach_resource_path": ["./resource"],
            }
        ],
        "resource": [
            {"name": RESOURCE_NAME, "label": locale["resource_cn_label"],
             "path": ["./resource"]}
        ],
        "languages": {"zh_cn": "./resource/locale/zh_cn.json"},
        "group": [{"name": gid, "label": glabel, "default_expand": True}
                  for gid, glabel in GROUP_TABLE],
        "task": [
            {
                "name": t["name"],
                "label": t["label"],
                "entry": t["entry"],
                "description": t["rationale"],
                "default_check": False,
                "group": [t["group"]],
            }
            for t in task_entries
        ],
        # NOTE: interface.schema.json types `option` as an *object* (not an
        # array), so an empty list here is a validation failure.  We emit no
        # options at all rather than an empty object.
        "agent": [],
    }
    add_file(os.path.join(out, "interface.json"), interface)

    # -- 8. report --------------------------------------------------------- #
    report["counts"] = {
        "screens_in_source": len(screens),
        "screens_emitted": len(screen_order),
        "edges_in_source": len(edge_records),
        "edges_emitted": edge_covered,
        "edges_omitted": len(omitted_edges),
        "expressions_total": len(expressions),
        "expressions_mapped": len(expressions) - len(unmapped_exprs),
        "expressions_unmapped": len(unmapped_exprs),
        "expressions_unparsed": len([e for e in expressions if not e["info"]["ok"]]),
        "distinct_forms": len(census),
        "unmapped_nodes": len(unmapped_nodes),
        "tasks_emitted": len(task_entries),
        "tasks_skipped": len(TASK_SKIPPED),
        "images_copied": copied,
    }
    report["screen_graph"] = {
        "screens": [{"id": s, "name": screen_by_id[s].get("name"),
                     "node": screen_nodes[s],
                     "image_path": screen_by_id[s]["image_path"]} for s in screen_order],
        "adjacency": {s: adjacency[s] for s in screen_order},
        "edges": edge_records,
    }
    report["unmapped_nodes"] = unmapped_nodes
    report["omitted_edges"] = omitted_edges
    report["pipeline_files"] = sorted(generated, key=lambda d: d["path"])
    report["totals"] = {
        "files_written": len(generated) + 1,      # + migration_report.json itself
        "generated_bytes": sum(g["bytes"] for g in generated),
        "image_bytes": image_bytes,
    }

    # -- 9. schema validation --------------------------------------------- #
    schema_dir = args.schemas
    if schema_dir and not os.path.isdir(schema_dir):
        raise SystemExit("[FATAL] --schemas 不是目录：%s" % schema_dir)
    if not schema_dir:
        for cand in SCHEMA_DIR_CANDIDATES:
            if os.path.isfile(os.path.join(cand, "pipeline.schema.json")):
                schema_dir = cand
                break
    validation = {"schema_dir": schema_dir, "performed": False}
    if schema_dir and os.path.isfile(os.path.join(schema_dir, "pipeline.schema.json")):
        pipeline_instances = {}
        for g in generated:
            if g["path"].startswith("resource/pipeline/"):
                with open(os.path.join(out, g["path"]), "r", encoding="utf-8") as fh:
                    pipeline_instances[g["path"]] = json.load(fh)
        validation["performed"] = True
        validation["pipeline"] = validate_with(
            os.path.join(schema_dir, "pipeline.schema.json"), pipeline_instances)
        iface_schema = os.path.join(schema_dir, "interface.schema.json")
        if os.path.isfile(iface_schema):
            validation["interface"] = validate_with(iface_schema,
                                                    {"interface.json": interface})
        validation["ok"] = (
            validation["pipeline"]["ok"]
            and validation.get("interface", {"ok": True})["ok"]
        )
    else:
        validation["note"] = ("未找到 pipeline.schema.json，跳过校验。"
                              "用 --schemas 指定目录。")
    report["schema_validation"] = validation

    # strictness: no output path may contain an underscore-prefixed DIRECTORY
    underscore_dirs = []
    for root, dirs, _files in os.walk(out):
        for d in dirs:
            if d.startswith("_"):
                underscore_dirs.append(os.path.relpath(os.path.join(root, d), out)
                                       .replace("\\", "/"))
    report["underscore_dirs"] = underscore_dirs

    size, digest = dump_json(os.path.join(out, "migration_report.json"), report,
                             sort_keys=True)
    report["totals"]["report_bytes"] = size
    report["totals"]["report_sha256"] = digest

    # -- 10. console summary ---------------------------------------------- #
    print("[migrate_march7th] source   : %s @ %s" % (source, report["source"]["git_head"]))
    print("[migrate_march7th] out      : %s" % out)
    print("[migrate_march7th] screens  : %d emitted / %d in source"
          % (len(screen_order), len(screens)))
    print("[migrate_march7th] edges    : %d emitted / %d in source (%d omitted)"
          % (edge_covered, len(edge_records), len(omitted_edges)))
    print("[migrate_march7th] exprs    : %d total, %d unmapped"
          % (len(expressions), len(unmapped_exprs)))
    print("[migrate_march7th] tasks    : %d emitted, %d skipped"
          % (len(task_entries), len(TASK_SKIPPED)))
    print("[migrate_march7th] images   : %d copied (%d bytes)"
          % (copied, image_bytes))
    if validation.get("performed"):
        print("[migrate_march7th] schema   : pipeline %s, interface %s"
              % ("OK" if validation["pipeline"]["ok"] else "FAIL",
                 "OK" if validation.get("interface", {}).get("ok") else
                 ("FAIL" if "interface" in validation else "n/a")))
    else:
        print("[migrate_march7th] schema   : skipped (%s)" % validation.get("note"))
    if name_dupes:
        print("[migrate_march7th] WARNING  : %d duplicate node names" % len(name_dupes))
    if dangling:
        print("[migrate_march7th] WARNING  : %d dangling node references" % len(dangling))
    if underscore_dirs:
        print("[migrate_march7th] WARNING  : underscore dirs present: %s" % underscore_dirs)
    return 0


# --------------------------------------------------------------------------- #
# Action -> node mapping
# --------------------------------------------------------------------------- #


def build_action_node(info, nxt, sid, target, edge_idx, action_index):
    """Turn one parsed expression into a pipeline node."""
    base_attach = {
        "march7th:edge": [sid, target],
        "march7th:edge_index": edge_idx,
        "march7th:action_index": action_index,
        "march7th:expression": info["raw"],
    }
    kind = info["kind"]

    if kind == "sleep":
        attach = dict(base_attach, **{"march7th:kind": "sleep"})
        return dh_node(nxt, attach, post_delay=int(round(info["seconds"] * 1000)))

    if kind == "press_key":
        key = (info.get("key") or "").strip().lower()
        code = ANDROID_KEYCODES.get(key)
        attach = dict(base_attach, **{
            "march7th:kind": "press_key",
            "march7th:upstream_key": info.get("key"),
        })
        if info.get("source") == "config":
            attach["march7th:config_dependency"] = {
                "section": info["section"], "key": info["key"],
                "note": "静态资源包读不到用户的 config.yaml，已常量折叠为默认热键。",
            }
        if code is None:
            # Unknown literal key: keep the chain alive but make it explicit.
            attach["march7th:unmapped_reason"] = (
                "按键 %r 没有登记 Android KeyEvent 虚拟键码" % info.get("key"))
            node = dh_node(nxt, attach, action="DoNothing")
            return node
        attach["march7th:android_keycode"] = code
        return dh_node(nxt, attach, action="ClickKey", action_params={"key": code})

    if kind == "click":
        find_type = info["find_type"]

        if find_type == "crop":
            rect = denormalise_crop(info["crop_rect"])
            attach = dict(base_attach, **{
                "march7th:kind": "click-crop",
                "march7th:normalised_rect": info["crop_rect"],
                "march7th:note": "上游 find_type='crop' 不做识别，直接点归一化矩形。",
            })
            return dh_node(nxt, attach, action="Click", action_params={"target": rect})

        if find_type == "text":
            # Never reached in the default mode (see the caller), kept for
            # --include-unsupported bookkeeping completeness.
            attach = dict(base_attach, **{
                "march7th:kind": "click-text",
                "march7th:expected": info["expected"],
                "march7th:include": info["include"],
                "march7th:unmapped_reason": "需要 OCR 模型资源，本包未附带。",
            })
            return dh_node(nxt, attach)

        # find_type == 'image'
        templates = ([template_rel(t) for t in info["templates"]]
                     if info.get("templates") else [template_rel(info["template"])])
        threshold = info.get("threshold")
        if threshold is None:
            threshold = 0.7
        threshold = float(threshold)
        if info.get("threshold_is_int") or threshold > 1.0:
            # Masked-template match upstream: TM_SQDIFF + alpha mask, lower is
            # better, 2_000_000 means "accept the best location anywhere".
            # DECISION: the score scale is not convertible to a normalised
            # correlation coefficient, so we degrade to a plain TemplateMatch at
            # a lower threshold and record the approximation.  The alpha mask
            # itself cannot be expressed (only green_mask exists, and the
            # templates' transparency is arbitrary, not keyed on pure green).
            threshold = MASKED_THRESHOLD
            masked = True
        else:
            masked = False
        roi = denormalise_crop(info["crop"]) if info.get("crop") else [0, 0, 0, 0]
        attach = dict(base_attach, **{
            "march7th:kind": "click-image",
            "march7th:upstream_threshold": info.get("threshold"),
            "march7th:masked_template": masked,
        })
        if info.get("max_retries") is not None:
            attach["march7th:max_retries"] = info["max_retries"]
        if info.get("crop") is not None:
            attach["march7th:normalised_crop"] = info["crop"]
        if masked:
            attach["march7th:approximation"] = (
                "上游对带真实 alpha 的模板使用 cv2.TM_SQDIFF + mask（分数越低越像，"
                "阈值 2000000 等于“接受任意位置”）。这里退化为普通 TM_CCOEFF_NORMED 且阈值降到 0.7。")
        if info.get("extra_kwargs"):
            attach["march7th:extra_kwargs"] = info["extra_kwargs"]
        node = tm_node(templates, [threshold] * len(templates), roi, "Click",
                       {"target": True}, nxt, attach)
        if info.get("max_retries") is not None:
            # MaaFramework retries the recognition inside [rate_limit, timeout].
            node["rate_limit"] = DEFAULT_RATE_LIMIT
            node["timeout"] = max(DEFAULT_TIMEOUT,
                                  int(info["max_retries"]) * DEFAULT_RATE_LIMIT)
        return node

    # unknown kind: caller handles unmapped nodes, but be defensive
    attach = dict(base_attach, **{"march7th:unmapped_reason": info.get("note", "")})
    return dh_node(nxt, attach)


if __name__ == "__main__":
    sys.exit(main())
