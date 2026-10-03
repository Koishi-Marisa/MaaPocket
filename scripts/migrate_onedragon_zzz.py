#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Migrate ZenlessZoneZero-OneDragon screen/area recognition data into a
MaaFramework Project-Interface-V2 resource pack for MaaPocket (app-zzz).

The upstream project (OneDragon-Anything/ZenlessZoneZero-OneDragon, GPL-3.0)
is a Win32/PC automation tool.  It ships a declarative description of every game
screen:

    assets/game_data/screen_info/<screen_id>.yml

and a template bank:

    assets/template/<sub_dir>/<template_id>/{raw.png,mask.png,config.yml,features.xml}

This script reads those (through ``git`` so that a partial / sparse clone works),
and emits a MaaFramework PI v2 pack:

    <out>/interface.json
    <out>/resource/pipeline/<screen_id>.json
    <out>/resource/pipeline/tasks.json
    <out>/resource/image/<sub_dir>/<template_id>/raw.png
    <out>/locales/interface/zh_cn.json
    <out>/migration_report.json

Design notes / mapping rules derived from the upstream loader sources are
reproduced verbatim in ``migration_report.json`` under ``rules``.

Python 3.9+, standard library only.  No PyYAML, no jsonschema.
"""

from __future__ import annotations

import argparse
import datetime as _dt
import json
import os
import re
import shutil
import subprocess
import sys

# --------------------------------------------------------------------------
# Constants derived from upstream (see migration_report.json -> rules)
# --------------------------------------------------------------------------

# src/one_dragon/base/controller/pc_game_window.py:16-17
#   def __init__(self, standard_width: int = 1920, standard_height: int = 1080)
# config/project.yml:6-7
#   screen_standard_width: 1920
#   screen_standard_height: 1080
DESIGN_WIDTH = 1920
DESIGN_HEIGHT = 1080

# Display scaling for the generated controller.  Chosen so that the upstream
# 1920x1080 pixel space is reproduced 1:1 for a landscape device whose short
# side is 1080 (the common case for modern phones), which in turn means the
# template PNGs can be copied byte-for-byte without any resampling (a stdlib
# only script cannot rescale PNGs).
DISPLAY_SHORT_SIDE = 1080

TEMPLATE_RAW_FILE_NAME = "raw.png"
TEMPLATE_MASK_FILE_NAME = "mask.png"
TEMPLATE_CONFIG_FILE_NAME = "config.yml"
TEMPLATE_FEATURES_FILE_NAME = "features.xml"

IMAGE_EXTENSIONS = (".png", ".jpg", ".jpeg", ".webp", ".bmp")

# Upstream app categories, taken from the GUI view packages:
#   src/zzz_od/gui/view/{one_dragon,battle_assistant,hollow_zero,game_assistant,
#                        world_patrol,standalone,devtools}
GROUPS = [
    ("one_dragon", "一条龙"),
    ("battle_assistant", "战斗助手"),
    ("hollow_zero", "空洞零号"),
    ("game_assistant", "游戏助手"),
    ("world_patrol", "锄大地"),
    ("standalone", "独立应用"),
    ("devtools", "开发工具"),
]
DEFAULT_GROUP = "standalone"

# APP_ID -> GUI category.  Evidence:
#   gui/view/one_dragon/{zzz_one_dragon_interface,charge_plan_interface,
#                        mouse_sensitivity_checker_interface,
#                        predefined_team_interface}.py
#   gui/view/battle_assistant/battle_assistant_interface.py
#   gui/view/game_assistant/commission_assistant_interface.py
#   gui/view/hollow_zero/{lost_void_setting_interface,withered_domain_setting_interface}.py
#   gui/view/world_patrol/world_patrol_*.py
#   gui/view/devtools/{app_devtools_interface,operation_debug_interface,
#                      devtools_screenshot_helper_interface}.py
#   gui/view/standalone/zzz_standalone_app_interface.py
APP_GROUP = {
    "one_dragon": "one_dragon",
    "charge_plan": "one_dragon",
    "mouse_sensitivity_checker": "one_dragon",
    "predefined_team_checker": "one_dragon",
    "auto_battle": "battle_assistant",
    "dodge_assistant": "battle_assistant",
    "operation_debug": "devtools",
    "screenshot_helper": "devtools",
    "lost_void": "hollow_zero",
    "withered_domain": "hollow_zero",
    "commission_assistant": "game_assistant",
    "world_patrol": "world_patrol",
}

SCREEN_INFO_DIR = "assets/game_data/screen_info"
TEMPLATE_ROOT_DIR = "assets/template"
APPLICATION_DIR = "src/zzz_od/application"
MERGED_SCREEN_FILE = "_od_merged.yml"


# --------------------------------------------------------------------------
# Minimal YAML reader
# --------------------------------------------------------------------------
# PyYAML is not available in the target environment and must not be a runtime
# dependency.  The upstream files use a narrow, regular subset of YAML:
#   block maps, block sequences (both at the parent key's indent, and nested),
#   flow sequences `[a, b, c]`, single/double quoted and plain scalars,
#   `null`, booleans, ints, floats, and `''` for the empty string.


class YamlError(Exception):
    pass


def _strip_comment(text):
    out = []
    quote = None
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if quote is not None:
            out.append(ch)
            if ch == quote:
                if quote == "'" and i + 1 < n and text[i + 1] == "'":
                    out.append("'")
                    i += 2
                    continue
                quote = None
            i += 1
            continue
        if ch in ("'", '"'):
            quote = ch
            out.append(ch)
            i += 1
            continue
        if ch == "#" and (not out or out[-1] in " \t"):
            break
        out.append(ch)
        i += 1
    return "".join(out)


_KEY_RE = re.compile(
    r"^(?:'([^']*)'|\"([^\"]*)\"|([^:\s][^:]*?))\s*:(?:\s|$)"
)


def _looks_like_key(text):
    return _KEY_RE.match(text) is not None


def _split_key(text):
    """Split `key: rest` respecting quotes.  Returns (key, rest) or (None, None)."""
    quote = None
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if quote is not None:
            if ch == quote:
                if quote == "'" and i + 1 < n and text[i + 1] == "'":
                    i += 2
                    continue
                quote = None
            i += 1
            continue
        if ch in ("'", '"'):
            quote = ch
            i += 1
            continue
        if ch == ":" and (i + 1 >= n or text[i + 1] in " \t"):
            return text[:i], text[i + 1:]
        i += 1
    return None, None


def _unquote(text):
    text = text.strip()
    if len(text) >= 2 and text[0] == text[-1] and text[0] in ("'", '"'):
        body = text[1:-1]
        if text[0] == "'":
            return body.replace("''", "'")
        return (
            body.replace('\\"', '"')
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
        )
    return text


_INT_RE = re.compile(r"^[+-]?\d+$")
_FLOAT_RE = re.compile(r"^[+-]?(?:\d+\.\d*|\.\d+|\d+)(?:[eE][+-]?\d+)?$")
_NULLS = {"", "~", "null", "Null", "NULL"}


def _split_flow(text):
    """Split a flow sequence body on top-level commas."""
    parts = []
    depth = 0
    quote = None
    cur = []
    for ch in text:
        if quote is not None:
            cur.append(ch)
            if ch == quote:
                quote = None
            continue
        if ch in ("'", '"'):
            quote = ch
            cur.append(ch)
            continue
        if ch in "[{":
            depth += 1
        elif ch in "]}":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(cur))
            cur = []
            continue
        cur.append(ch)
    tail = "".join(cur)
    if tail.strip() != "":
        parts.append(tail)
    return parts


def _parse_scalar(text):
    text = text.strip()
    if text in _NULLS:
        return None
    if text.startswith("[") and text.endswith("]"):
        body = text[1:-1].strip()
        if body == "":
            return []
        return [_parse_scalar(p) for p in _split_flow(body)]
    if text.startswith("{") and text.endswith("}"):
        body = text[1:-1].strip()
        if body == "":
            return {}
        out = {}
        for pair in _split_flow(body):
            key, rest = _split_key(pair.strip())
            if key is None:
                raise YamlError("bad flow mapping entry: %r" % pair)
            out[_unquote(key)] = _parse_scalar(rest if rest is not None else "")
        return out
    if len(text) >= 2 and text[0] == text[-1] and text[0] in ("'", '"'):
        return _unquote(text)
    low = text.lower()
    if low == "true":
        return True
    if low == "false":
        return False
    if _INT_RE.match(text):
        return int(text)
    if _FLOAT_RE.match(text):
        return float(text)
    return text


def _tokenize(text):
    """Return a list of (indent, content) with comments/blank lines removed.

    A sequence entry that carries an inline mapping/sequence
    (`- key: value`, `- - 120`) is split into a bare `-` line and a synthetic
    line at indent+2.  This matches the 2-space continuation style used by
    every upstream screen file and lets the recursive parser stay symmetric.
    """
    out = []
    for raw in text.splitlines():
        if raw.strip() == "" or raw.lstrip().startswith("#"):
            continue
        stripped = raw.strip()
        if stripped in ("---", "..."):
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        content = _strip_comment(stripped).rstrip()
        if content.strip() == "":
            continue
        if content == "-" or content.startswith("- "):
            rest = content[1:].strip()
            if rest != "" and (_looks_like_key(rest) or rest == "-" or rest.startswith("- ")):
                out.append((indent, "-"))
                out.append((indent + 2, rest))
                continue
        out.append((indent, content))
    return out


def _parse_block(lines, i, indent):
    if lines[i][1] == "-" or lines[i][1].startswith("- "):
        return _parse_seq(lines, i, indent)
    return _parse_map(lines, i, indent)


def _parse_map(lines, i, indent):
    out = {}
    n = len(lines)
    while i < n:
        ind, content = lines[i]
        if ind < indent:
            break
        if ind > indent:
            raise YamlError("unexpected indent %d (expected %d): %r" % (ind, indent, content))
        if content == "-" or content.startswith("- "):
            break
        key, rest = _split_key(content)
        if key is None:
            raise YamlError("expected 'key: value', got %r" % content)
        key = _unquote(key)
        rest = (rest or "").strip()
        if rest == "":
            j = i + 1
            if j < n and (
                lines[j][0] > indent
                or (lines[j][0] == indent and (lines[j][1] == "-" or lines[j][1].startswith("- ")))
            ):
                out[key], i = _parse_block(lines, j, lines[j][0])
            else:
                out[key] = None
                i = j
        else:
            out[key] = _parse_scalar(rest)
            i += 1
    return out, i


def _parse_seq(lines, i, indent):
    out = []
    n = len(lines)
    while i < n:
        ind, content = lines[i]
        if ind != indent or not (content == "-" or content.startswith("- ")):
            break
        rest = content[1:].strip()
        if rest == "":
            j = i + 1
            if j < n and lines[j][0] > indent:
                value, i = _parse_block(lines, j, lines[j][0])
            else:
                value = None
                i = j
            out.append(value)
            continue
        out.append(_parse_scalar(rest))
        i += 1
    return out, i


def parse_yaml(text):
    lines = _tokenize(text)
    if not lines:
        return None
    value, idx = _parse_block(lines, 0, lines[0][0])
    if idx != len(lines):
        raise YamlError("trailing content at line index %d: %r" % (idx, lines[idx]))
    return value


# --------------------------------------------------------------------------
# Minimal JSON-Schema validator (subset used by the MaaFramework schemas)
# --------------------------------------------------------------------------

_JSON_TYPES = {
    "object": dict,
    "array": list,
    "string": str,
    "boolean": bool,
    "null": type(None),
}


def _is_type(value, name):
    if name == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if name == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    if name == "boolean":
        return isinstance(value, bool)
    py = _JSON_TYPES.get(name)
    if py is None:
        return True
    if py is not dict and isinstance(value, dict):
        return py is dict
    return isinstance(value, py)


class SchemaValidator(object):
    """A pragmatic JSON-Schema draft 2020-12 subset validator.

    Supports: $ref (local pointers), type, const, enum, required, properties,
    patternProperties, additionalProperties, unevaluatedProperties, items,
    minItems, maxItems, minimum, maximum, anyOf, oneOf, allOf, not,
    if/then/else, dependentSchemas.
    """

    MAX_ERRORS = 40

    def __init__(self, root):
        self.root = root
        self.errors = []

    # -- reference resolution -------------------------------------------
    def _resolve(self, ref):
        if not ref.startswith("#"):
            raise ValueError("only local $ref supported: %r" % ref)
        node = self.root
        pointer = ref[1:]
        if pointer in ("", "/"):
            return node
        if not pointer.startswith("/"):
            raise ValueError("bad $ref: %r" % ref)
        for raw in pointer[1:].split("/"):
            token = raw.replace("~1", "/").replace("~0", "~")
            if isinstance(node, list):
                node = node[int(token)]
            else:
                node = node[token]
        return node

    # -- helpers --------------------------------------------------------
    def _declared_props(self, schema, instance, seen):
        if not isinstance(schema, dict):
            return set()
        key = id(schema)
        if key in seen:
            return set()
        seen.add(key)
        names = set()
        for k in schema.get("properties", {}) or {}:
            names.add(k)
        for k in schema.get("patternProperties", {}) or {}:
            names.add(k)
        if "$ref" in schema:
            names |= self._declared_props(self._resolve(schema["$ref"]), instance, seen)
        for sub in schema.get("allOf", []) or []:
            names |= self._declared_props(sub, instance, seen)
        for branch in ("anyOf", "oneOf"):
            for sub in schema.get(branch, []) or []:
                names |= self._declared_props(sub, instance, seen)
        if "if" in schema:
            if self._check(instance, schema["if"]):
                names |= self._declared_props(schema.get("then", {}), instance, seen)
            else:
                names |= self._declared_props(schema.get("else", {}), instance, seen)
        return names

    # -- core -----------------------------------------------------------
    def _check(self, instance, schema):
        """Return True when `instance` validates against `schema`."""
        saved = self.errors
        self.errors = []
        try:
            self._validate(instance, schema, "$")
            return not self.errors
        finally:
            self.errors = saved

    def _add(self, path, message):
        self.errors.append("%s: %s" % (path, message))

    def _validate(self, instance, schema, path):
        if len(self.errors) >= self.MAX_ERRORS:
            return
        if schema is True or schema == {}:
            return
        if schema is False:
            self._add(path, "schema is false")
            return
        if not isinstance(schema, dict):
            return

        if "$ref" in schema:
            self._validate(instance, self._resolve(schema["$ref"]), path)
            rest = {k: v for k, v in schema.items() if k != "$ref"}
            if rest:
                self._validate(instance, rest, path)

        if "type" in schema:
            types = schema["type"]
            if isinstance(types, str):
                types = [types]
            if not any(_is_type(instance, t) for t in types):
                self._add(path, "expected type %s, got %s" % (types, type(instance).__name__))
                return

        if "const" in schema and instance != schema["const"]:
            self._add(path, "expected const %r, got %r" % (schema["const"], instance))

        if "enum" in schema and instance not in schema["enum"]:
            self._add(path, "value %r not in enum %r" % (instance, schema["enum"]))

        if "minimum" in schema and isinstance(instance, (int, float)) and not isinstance(instance, bool):
            if instance < schema["minimum"]:
                self._add(path, "%r < minimum %r" % (instance, schema["minimum"]))
        if "maximum" in schema and isinstance(instance, (int, float)) and not isinstance(instance, bool):
            if instance > schema["maximum"]:
                self._add(path, "%r > maximum %r" % (instance, schema["maximum"]))

        for sub in schema.get("allOf", []) or []:
            self._validate(instance, sub, path)

        if "anyOf" in schema:
            if not any(self._check(instance, sub) for sub in schema["anyOf"]):
                self._add(path, "does not match any of the anyOf branches")
        if "oneOf" in schema:
            hits = sum(1 for sub in schema["oneOf"] if self._check(instance, sub))
            if hits != 1:
                self._add(path, "matched %d oneOf branches (expected 1)" % hits)
        if "not" in schema:
            if self._check(instance, schema["not"]):
                self._add(path, "matched a schema it must not match")

        if "if" in schema:
            if self._check(instance, schema["if"]):
                if "then" in schema:
                    self._validate(instance, schema["then"], path)
            elif "else" in schema:
                self._validate(instance, schema["else"], path)

        if isinstance(instance, dict):
            self._validate_object(instance, schema, path)
        elif isinstance(instance, list):
            self._validate_array(instance, schema, path)

    def _validate_object(self, instance, schema, path):
        props = schema.get("properties", {}) or {}
        patterns = schema.get("patternProperties", {}) or {}

        for name in schema.get("required", []) or []:
            if name not in instance:
                self._add(path, "missing required property %r" % name)

        dependents = schema.get("dependentSchemas", {}) or {}
        for name, sub in dependents.items():
            if name in instance:
                self._validate(instance, sub, path)

        for key, value in instance.items():
            matched = False
            if key in props:
                matched = True
                self._validate(value, props[key], "%s.%s" % (path, key))
            for pattern, sub in patterns.items():
                if re.search(pattern, key):
                    matched = True
                    self._validate(value, sub, "%s.%s" % (path, key))
            if matched:
                continue
            extra = schema.get("additionalProperties", True)
            if extra is False and "unevaluatedProperties" not in schema:
                self._add(path, "additional property %r is not allowed" % key)
            elif isinstance(extra, dict):
                self._validate(value, extra, "%s.%s" % (path, key))

        if schema.get("unevaluatedProperties") is False:
            allowed = self._declared_props(schema, instance, set())
            for key in instance:
                if key in allowed:
                    continue
                if key in props:
                    continue
                if any(re.search(p, key) for p in patterns):
                    continue
                self._add(path, "unevaluated property %r is not allowed" % key)

    def _validate_array(self, instance, schema, path):
        if "minItems" in schema and len(instance) < schema["minItems"]:
            self._add(path, "expected at least %d items" % schema["minItems"])
        if "maxItems" in schema and len(instance) > schema["maxItems"]:
            self._add(path, "expected at most %d items" % schema["maxItems"])
        items = schema.get("items")
        if isinstance(items, dict):
            for idx, item in enumerate(instance):
                self._validate(item, items, "%s[%d]" % (path, idx))
        elif isinstance(items, list):
            for idx, sub in enumerate(items):
                if idx < len(instance):
                    self._validate(instance[idx], sub, "%s[%d]" % (path, idx))

    def validate(self, instance):
        self.errors = []
        self._validate(instance, self.root, "$")
        return list(self.errors)


# --------------------------------------------------------------------------
# Git-backed source access
# --------------------------------------------------------------------------


class GitSource(object):
    def __init__(self, path):
        self.path = os.path.abspath(path)
        if not os.path.isdir(os.path.join(self.path, ".git")):
            raise SystemExit("--source is not a git checkout: %s" % self.path)
        self._files = None

    def _git(self, args, binary=False):
        proc = subprocess.run(
            ["git", "-C", self.path] + args,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        if proc.returncode != 0:
            raise RuntimeError(
                "git %s failed (%d): %s"
                % (" ".join(args), proc.returncode, proc.stderr.decode("utf-8", "replace").strip())
            )
        return proc.stdout if binary else proc.stdout.decode("utf-8", "replace")

    def head(self):
        return self._git(["rev-parse", "HEAD"]).strip()

    def files(self):
        if self._files is None:
            raw = self._git(["ls-files", "-z"], binary=True)
            self._files = sorted(p for p in raw.decode("utf-8", "replace").split("\0") if p)
        return self._files

    def under(self, prefix):
        prefix = prefix.rstrip("/") + "/"
        return [p for p in self.files() if p.startswith(prefix)]

    def show(self, path):
        return self._git(["show", "HEAD:%s" % path])

    def show_bytes(self, path):
        return self._git(["show", "HEAD:%s" % path], binary=True)

    def grep(self, pattern, path_prefix):
        raw = self._git(
            ["grep", "-n", "-I", "-E", pattern, "HEAD", "--", path_prefix]
        )
        out = []
        for line in raw.splitlines():
            parts = line.split(":", 3)
            if len(parts) == 4:
                out.append((parts[1], int(parts[2]), parts[3]))
        return out


# --------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------

_SAFE_RE = re.compile(r"[\\/:*?\"<>|\s]+")


def slugify(name):
    text = _SAFE_RE.sub("_", (name or "").strip())
    text = text.strip("_")
    return text or "area"


_PASCAL_RE = re.compile(r"[^0-9a-zA-Z]+")


def pascal_case(name):
    parts = [p for p in _PASCAL_RE.split(name or "") if p]
    if not parts:
        return "App"
    return "".join(p[:1].upper() + p[1:] for p in parts)


def as_int(value, default=0):
    if isinstance(value, bool):
        return default
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        return int(value)
    if isinstance(value, str) and _INT_RE.match(value.strip()):
        return int(value.strip())
    return default


def as_float(value, default):
    if isinstance(value, bool):
        return default
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        try:
            return float(value.strip())
        except ValueError:
            return default
    return default


def to_roi(pc_rect):
    """Convert an upstream `pc_rect` ([x1,y1,x2,y2], 1920x1080 space) to [x,y,w,h]."""
    if not isinstance(pc_rect, list) or len(pc_rect) != 4:
        return [0, 0, 0, 0], False
    x1 = max(0, min(DESIGN_WIDTH, as_int(pc_rect[0])))
    y1 = max(0, min(DESIGN_HEIGHT, as_int(pc_rect[1])))
    x2 = max(0, min(DESIGN_WIDTH, as_int(pc_rect[2])))
    y2 = max(0, min(DESIGN_HEIGHT, as_int(pc_rect[3])))
    if x2 <= x1 or y2 <= y1:
        return [x1, y1, max(1, x2 - x1), max(1, y2 - y1)], False
    return [x1, y1, x2 - x1, y2 - y1], True


def has_underscore_dir(rel_path):
    for part in rel_path.replace("\\", "/").split("/"):
        if part.startswith("_"):
            return True
    return False


def write_json(path, obj):
    payload = json.dumps(obj, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    changed = True
    if os.path.isfile(path):
        try:
            with open(path, "r", encoding="utf-8") as handle:
                changed = handle.read() != payload
        except (OSError, UnicodeDecodeError):
            changed = True
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(payload)
    return changed, len(payload.encode("utf-8"))


def write_bytes(path, data):
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    changed = True
    if os.path.isfile(path):
        try:
            with open(path, "rb") as handle:
                changed = handle.read() != data
        except OSError:
            changed = True
    with open(path, "wb") as handle:
        handle.write(data)
    return changed, len(data)


# --------------------------------------------------------------------------
# Upstream data loading
# --------------------------------------------------------------------------


def load_screens(source, only_screens):
    """Return (screens, notes). Each screen is a dict with file/id/name/app/areas."""
    notes = {"parse_errors": [], "duplicate_ids": [], "duplicate_names": [], "files": []}
    paths = [
        p
        for p in source.under(SCREEN_INFO_DIR)
        if p.endswith(".yml") and os.path.basename(p) != MERGED_SCREEN_FILE
    ]
    paths.sort()
    screens = []
    seen_ids = {}
    seen_names = {}
    for path in paths:
        text = source.show(path)
        try:
            data = parse_yaml(text)
        except YamlError as exc:
            notes["parse_errors"].append({"file": path, "error": str(exc)})
            continue
        if not isinstance(data, dict):
            notes["parse_errors"].append({"file": path, "error": "top level is not a mapping"})
            continue
        screen_id = data.get("screen_id") or os.path.basename(path)[:-4]
        if only_screens and screen_id not in only_screens:
            continue
        screen_name = data.get("screen_name") or screen_id
        if screen_id in seen_ids:
            notes["duplicate_ids"].append({"screen_id": screen_id, "file": path, "kept": seen_ids[screen_id]})
            continue
        if screen_name in seen_names:
            notes["duplicate_names"].append(
                {"screen_name": screen_name, "file": path, "kept": seen_names[screen_name]}
            )
            continue
        seen_ids[screen_id] = path
        seen_names[screen_name] = path
        areas = data.get("area_list") or []
        if not isinstance(areas, list):
            notes["parse_errors"].append({"file": path, "error": "area_list is not a list"})
            areas = []
        screens.append(
            {
                "file": path,
                "screen_id": str(screen_id),
                "screen_name": str(screen_name),
                "app_id": str(data.get("app_id") or ""),
                "pc_alt": bool(data.get("pc_alt") or False),
                "areas": [a for a in areas if isinstance(a, dict)],
            }
        )
        notes["files"].append(path)
    return screens, notes


_APP_ID_RE = re.compile(r"APP_ID\s*(?::[^=]+)?=\s*(['\"])(.*?)\1")
_APP_NAME_RE = re.compile(r"APP_NAME\s*(?::[^=]+)?=\s*(['\"])(.*?)\1")


def load_apps(source):
    """Scrape APP_ID / APP_NAME out of src/zzz_od/application/**/*.py."""
    try:
        # git grep uses POSIX ERE, which has no \s
        hits = source.grep(r"^[[:space:]]*APP_(ID|NAME)[[:space:]]*[:=]", APPLICATION_DIR)
    except RuntimeError:
        hits = []
    by_file = {}
    for path, _lineno, content in hits:
        entry = by_file.setdefault(path, {})
        match = _APP_ID_RE.search(content)
        if match and "APP_ID" in content.split("=")[0]:
            entry["app_id"] = match.group(2)
            continue
        match = _APP_NAME_RE.search(content)
        if match and "APP_NAME" in content.split("=")[0]:
            entry["app_name"] = match.group(2)
    apps = {}
    for path, entry in by_file.items():
        app_id = entry.get("app_id")
        if not app_id:
            continue
        rel = path[len(APPLICATION_DIR) + 1 :] if path.startswith(APPLICATION_DIR + "/") else path
        package = rel.split("/")[0]
        record = apps.setdefault(
            app_id,
            {"app_id": app_id, "app_name": entry.get("app_name") or app_id, "files": [], "package": package},
        )
        record["files"].append(path)
        if entry.get("app_name") and record["app_name"] == app_id:
            record["app_name"] = entry["app_name"]
    return apps


# Upstream splits its click call sites between src/zzz_od (73 files) and
# src/one_dragon (3 files), so the evidence scan covers all of `src`.
CLICK_SITE_ROOT = "src"
_CLICK_CALL_RE = re.compile(r"(?:\w+_)?click_area\s*\(")
_READ_CALL_RE = re.compile(r"(?:\w+_)?find_area\s*\(|(?:\w+_)?get_area\s*\(")
_STRING_LITERAL_RE = re.compile(r"'([^'\n]*)'|\"([^\"\n]*)\"")


def _iter_call_arguments(text, call_re):
    """Yield the raw argument text of every `...click_area(` call in `text`.

    Parens inside string literals are not tracked (the upstream call sites do
    not contain any), so this is a best-effort balanced scan.
    """
    for match in call_re.finditer(text):
        depth = 1
        i = match.end()
        j = i
        while j < len(text) and depth > 0:
            ch = text[j]
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        yield text[i:j]


def _call_literals(text, call_re):
    """Yield (raw_args, [string literals without {} placeholders]) per call."""
    for args in _iter_call_arguments(text, call_re):
        literals = [
            (m.group(1) if m.group(1) is not None else m.group(2))
            for m in _STRING_LITERAL_RE.finditer(args)
        ]
        yield args, [v for v in literals if v and "{" not in v and "}" not in v]


def _classify(literals, screen_names, area_names):
    """Split one argument list into (screen context, area context)."""
    screens = [v for v in literals if v in screen_names]
    areas = [v for v in literals if v in area_names]
    return screens, areas


def scrape_click_sites(source, screens):
    """Derive click / read-only evidence for every area from upstream call sites.

    Upstream `screen_info` YAML has NO click flag: whether an area is a click
    target is decided by the calling code, and nothing in the screen data marks
    it. `ScreenUtils.find_and_click_area|click_area` (and the
    `round_by_find_and_click_area` / `round_by_click_area` wrappers) are where an
    area is actually clicked; `ScreenUtils.find_area` / `ScreenLoader.get_area`
    are where an area is only *read* (OCR crop, template search region, branch
    condition). Scraping both gives positive evidence in either direction.

    Limitation: when the area name is passed through a local variable
    (e.g. `first_area = '对话框确认'` then `...find_and_click_area(shot, '大世界',
    first_area)`) the argument list holds no area literal. Such names are caught
    one level weaker by `literals` (the literal does appear in the file), and
    f-string names (`f'宣传员-{idx}'`) cannot be resolved at all.
    """
    screen_names = set()
    area_names = set()
    for screen in screens:
        screen_names.add(screen["screen_name"])
        for area in screen["areas"]:
            if area.get("area_name"):
                area_names.add(area["area_name"])

    pairs = set()
    bare = set()
    loose = set()
    dynamic = set()
    read_pairs = set()
    read_bare = set()
    literals = set()
    call_sites = 0
    read_sites = 0
    files = set()
    try:
        hits = source.grep(r"click_area\(|find_area\(|get_area\(", CLICK_SITE_ROOT)
    except RuntimeError:
        hits = []
    for path in sorted({p for p, _ln, _c in hits}):
        files.add(path)
        try:
            text = source.show(path)
        except RuntimeError:
            continue
        for match in _STRING_LITERAL_RE.finditer(text):
            value = match.group(1) if match.group(1) is not None else match.group(2)
            if value:
                literals.add(value)
        for args, args_literals in _call_literals(text, _CLICK_CALL_RE):
            call_sites += 1
            for m in _STRING_LITERAL_RE.finditer(args):
                value = m.group(1) if m.group(1) is not None else m.group(2)
                if value and ("{" in value or "}" in value):
                    dynamic.add(value)
            screens_ctx, areas_ctx = _classify(args_literals, screen_names, area_names)
            if screens_ctx and areas_ctx:
                for s in screens_ctx:
                    for a in areas_ctx:
                        pairs.add((s, a))
            elif areas_ctx:
                for a in areas_ctx:
                    bare.add(a)
            elif len(args_literals) >= 2:
                loose.add((args_literals[0], args_literals[1]))
        for _args, args_literals in _call_literals(text, _READ_CALL_RE):
            read_sites += 1
            screens_ctx, areas_ctx = _classify(args_literals, screen_names, area_names)
            if screens_ctx and areas_ctx:
                for s in screens_ctx:
                    for a in areas_ctx:
                        read_pairs.add((s, a))
            elif areas_ctx:
                for a in areas_ctx:
                    read_bare.add(a)
    return {
        "root": CLICK_SITE_ROOT,
        "files": sorted(files),
        "call_sites": call_sites,
        "read_call_sites": read_sites,
        "pairs": pairs,
        "bare": bare,
        "read_pairs": read_pairs,
        "read_bare": read_bare,
        "literals": literals,
        "loose": sorted(loose),
        "dynamic_area_names": sorted(dynamic),
        "screen_names_known": len(screen_names),
        "area_names_known": len(area_names),
    }


def click_evidence(area_name, screen_name, goto, id_mark, sites):
    """Return (action, evidence) for one area, preferring positive evidence.

    Precedence, strongest first:
      1. the area is clicked by name somewhere            -> Click
      2. the area declares `goto_list` (upstream: 交互后)  -> Click
      3. the area is only ever read (find_area/get_area)  -> DoNothing
      4. the area is a screen fingerprint (`id_mark`)     -> DoNothing
      5. the name appears as a source literal but not in a
         resolvable click call (variable-mediated click)  -> Click
      6. no evidence at all                               -> DoNothing
    """
    if (screen_name, area_name) in sites["pairs"]:
        return "Click", "click-call-site"
    if area_name in sites["bare"]:
        return "Click", "click-call-site-bare-name"
    if goto:
        return "Click", "goto_list"
    if (screen_name, area_name) in sites["read_pairs"] or area_name in sites["read_bare"]:
        return "DoNothing", "read-only-area-usage"
    if id_mark:
        return "DoNothing", "id_mark-fingerprint-only"
    if area_name in sites["literals"]:
        return "Click", "literal-ambiguous-use"
    return "DoNothing", "unreferenced"


# --------------------------------------------------------------------------
# Pipeline generation
# --------------------------------------------------------------------------


class Migration(object):
    def __init__(self, args):
        self.args = args
        self.source = GitSource(args.source)
        self.out = os.path.abspath(args.out)
        self.only_screens = set()
        if args.only_screens:
            self.only_screens = {s.strip() for s in args.only_screens.split(",") if s.strip()}
        self.head = self.source.head()
        self.click_sites = None
        self.files_written = []
        self.bytes_written = 0
        self.stats = {
            "screens": 0,
            "areas_total": 0,
            "areas_converted": 0,
            "areas_skipped": 0,
            "area_click": 0,
            "area_donothing": 0,
            "nodes": 0,
            "templates_missing": 0,
            "templates_copied": 0,
            "template_files_copied": 0,
            "unresolved_goto": 0,
            "tasks": 0,
            "files_changed": 0,
            "files_unchanged": 0,
        }
        self.report = {
            "generator": "MaaPocket/scripts/migrate_onedragon_zzz.py",
            "generated_at": _dt.datetime.now(_dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "source": {
                "path": self.source.path,
                "git_head": self.head,
                "upstream": "OneDragon-Anything/ZenlessZoneZero-OneDragon",
                "license": "GPL-3.0",
            },
            "arguments": {
                "out": self.out,
                "schemas": os.path.abspath(args.schemas) if args.schemas else None,
                "only_screens": sorted(self.only_screens),
                "skip_templates": bool(args.skip_templates),
                "force": bool(args.force),
            },
            "design": {
                "reference_width": DESIGN_WIDTH,
                "reference_height": DESIGN_HEIGHT,
                "display_short_side": DISPLAY_SHORT_SIDE,
                "roi_rule": "roi = [x1, y1, x2 - x1, y2 - y1] from pc_rect [x1, y1, x2, y2]",
                "template_rule": (
                    "<work_dir>/assets/template/<template_sub_dir>/<template_id>/raw.png"
                    " (mask.png / config.yml / features.xml optional)"
                ),
            },
            "rules": {},
            "screens": [],
            "templates": [],
            "tasks": [],
            "unresolved_goto": [],
            "notes": {},
            "validation": {},
        }

    # -- output scaffolding ---------------------------------------------
    def prepare_out(self):
        if os.path.isdir(self.out):
            if self.args.force:
                parent = os.path.dirname(self.out)
                if not parent or parent == self.out or os.path.isdir(os.path.join(self.out, ".git")):
                    raise SystemExit("refusing to wipe %s" % self.out)
                shutil.rmtree(self.out)
        os.makedirs(self.out, exist_ok=True)

    def _record(self, rel_path, changed, size):
        self.files_written.append(rel_path)
        self.bytes_written += size
        if changed:
            self.stats["files_changed"] += 1
        else:
            self.stats["files_unchanged"] += 1

    def emit_json(self, rel_path, obj):
        path = os.path.join(self.out, rel_path)
        changed, size = write_json(path, obj)
        self._record(rel_path, changed, size)
        return path

    def emit_bytes(self, rel_path, data):
        path = os.path.join(self.out, rel_path)
        changed, size = write_bytes(path, data)
        self._record(rel_path, changed, size)
        return path

    # -- area -> node ----------------------------------------------------
    def build_area(self, screen, area, used_names, color_nodes):
        area_name = str(area.get("area_name") or "area")
        slug = slugify(area_name)
        node_name = "%s__%s" % (screen["screen_id"], slug)
        base = node_name
        counter = 2
        while node_name in used_names:
            node_name = "%s__%d" % (base, counter)
            counter += 1
        used_names.add(node_name)

        text = area.get("text")
        text = "" if text is None else str(text)
        sub_dir = area.get("template_sub_dir")
        sub_dir = "" if sub_dir is None else str(sub_dir)
        template_id = area.get("template_id")
        template_id = "" if template_id is None else str(template_id)

        lcs_percent = as_float(area.get("lcs_percent"), 0.5)
        template_threshold = as_float(area.get("template_match_threshold"), 0.7)
        roi, roi_ok = to_roi(area.get("pc_rect"))

        goto = area.get("goto_list") or []
        if not isinstance(goto, list):
            goto = []
        goto = [str(g) for g in goto if g]

        has_text = len(text) > 0
        has_template = len(template_id) > 0

        # For an area whose only recogniser is a template we may still be able
        # to point at it even when the file is absent (we record that as a miss).
        template_rel = None
        template_entry = None
        if has_template:
            template_rel = template_id if not sub_dir else "%s/%s" % (sub_dir, template_id)
            template_entry = self.template_index.get(template_rel)

        warnings = []
        if not roi_ok:
            warnings.append(
                "degenerate or out-of-range pc_rect %r -> roi %r (width/height forced >= 1)"
                % (area.get("pc_rect"), roi)
            )
        ocr_branch = None
        branches = []
        kind = "direct"
        if has_text:
            ocr_branch = {
                "type": "OCR",
                "param": {
                    "expected": [re.escape(text)],
                    "roi": list(roi),
                    "threshold": lcs_percent,
                },
            }
            branches.append(ocr_branch)
            kind = "ocr"
        if has_template:
            file_name = TEMPLATE_RAW_FILE_NAME
            if template_entry is not None and template_entry["reference_file"]:
                file_name = template_entry["reference_file"]
            if template_entry is None:
                warnings.append("template directory not found in source: %s" % template_rel)
            elif not template_entry["reference_file"]:
                warnings.append("template directory has no image: %s" % template_rel)
            branches.append(
                {
                    "type": "TemplateMatch",
                    "param": {
                        "template": ["%s/%s" % (template_rel, file_name)],
                        "roi": list(roi),
                        "threshold": template_threshold,
                    },
                }
            )
            kind = "both" if has_text else "template"

        if len(branches) == 0:
            recognition = {"type": "DirectHit", "param": {"roi": list(roi)}}
        elif len(branches) == 1:
            recognition = branches[0]
        else:
            # Upstream ScreenMatch.find_area_with_detail inspects `is_text_area`
            # first, so the text recogniser keeps priority inside the Or.
            recognition = {"type": "Or", "param": {"any_of": branches}}

        # color_range => a ColorMatch helper node referenced by `color_filter`.
        # `color_filter` is an OCR-only field, so it must go on the OCR branch.
        color_range = area.get("color_range")
        color_node_name = None
        color_usable = (
            ocr_branch is not None
            and isinstance(color_range, list)
            and len(color_range) == 2
            and all(isinstance(c, list) and len(c) == 3 for c in color_range)
        )
        if color_range and not color_usable:
            # Upstream consumes color_range only inside OcrService._apply_color_filter,
            # so a color_range on an area with no OCR text is dead data (there is
            # exactly one such area upstream: compendium / 资源栏).
            warnings.append(
                "color_range present but unusable (needs OCR text + [[b,g,r],[b,g,r]]): %r"
                % (color_range,)
            )
        if color_usable:
            color_node_name = node_name + "__color"
            color_nodes[color_node_name] = {
                "recognition": {
                    "type": "ColorMatch",
                    "param": {
                        "method": 4,
                        "roi": list(roi),
                        # upstream is cv2.inRange on a BGR image; MaaFramework
                        # converts with cv::COLOR_BGR2RGB (method 4) first, so
                        # the RGB triples are the reverse of upstream's BGR ones
                        "lower": [as_int(v) for v in reversed(color_range[0])],
                        "upper": [as_int(v) for v in reversed(color_range[1])],
                    },
                },
                "action": {"type": "DoNothing"},
            }
            ocr_branch["param"]["color_filter"] = color_node_name

        # An area is only "skipped" when we could not produce a usable
        # recogniser at all: no OCR text and no resolvable template image.
        # Upstream areas with no text and no template are pure click targets
        # (ScreenUtils.find_and_click_area clicks area.center), so DirectHit on
        # the roi is the faithful representation and counts as converted.
        recognition_available = bool(has_text or not has_template or template_entry is not None)
        if not has_text and has_template and template_entry is not None \
                and not template_entry["reference_file"]:
            recognition_available = False

        # Click vs identify-only. The screen data has no click flag at all, so
        # the decision is derived from the upstream call sites (see
        # scrape_click_sites) plus the declarative `goto_list` metadata.
        action_type, evidence = click_evidence(
            area_name,
            screen["screen_name"],
            goto,
            bool(area.get("id_mark")),
            self.click_sites,
        )
        action = {"type": action_type}

        if action["type"] == "Click":
            self.stats["area_click"] += 1
        else:
            self.stats["area_donothing"] += 1

        node = {"recognition": recognition, "action": action}
        if goto:
            node["next"] = list(goto)

        return {
            "node_name": node_name,
            "node": node,
            "record": {
                "area_name": area_name,
                "node": node_name,
                "pc_rect": area.get("pc_rect"),
                "roi": roi,
                "roi_valid": roi_ok,
                "kind": kind,
                "id_mark": bool(area.get("id_mark")),
                "action": action["type"],
                "click_evidence": evidence,
                "lcs_percent": lcs_percent,
                # MaaFramework OCR `threshold` is a model-confidence threshold,
                # not an LCS ratio; lcs_percent is copied in as documented proxy.
                "ocr_threshold_needs_retune": bool(has_text and lcs_percent != 0.3),
                "template_match_threshold": template_threshold,
                "template_sub_dir": sub_dir,
                "template_id": template_id,
                "template_path": template_rel,
                "template_found": template_entry is not None if has_template else None,
                "color_range": color_range,
                "color_filter_node": color_node_name,
                "goto_list": goto,
                "next": list(goto),
                "warnings": warnings,
                "recognition_available": recognition_available,
                "skipped": not recognition_available,
                "skip_reason": "; ".join(warnings) if warnings else None,
            },
        }

    # -- templates -------------------------------------------------------
    def scan_templates(self, screens):
        """Return (index, records) for every template referenced by a screen."""
        referenced = []
        seen = set()
        for screen in screens:
            for area in screen["areas"]:
                sub_dir = area.get("template_sub_dir")
                sub_dir = "" if sub_dir is None else str(sub_dir)
                template_id = area.get("template_id")
                template_id = "" if template_id is None else str(template_id)
                if not template_id:
                    continue
                rel = template_id if not sub_dir else "%s/%s" % (sub_dir, template_id)
                if rel in seen:
                    continue
                seen.add(rel)
                referenced.append(rel)
        referenced.sort()

        index = {}
        records = []
        for rel in referenced:
            dir_path = "%s/%s" % (TEMPLATE_ROOT_DIR, rel)
            files = self.source.under(dir_path)
            record = {
                "template": rel,
                "source_dir": dir_path,
                "files": files,
                "exists": bool(files),
                "raw": any(os.path.basename(f) == TEMPLATE_RAW_FILE_NAME for f in files),
                "mask": any(os.path.basename(f) == TEMPLATE_MASK_FILE_NAME for f in files),
                "config": any(os.path.basename(f) == TEMPLATE_CONFIG_FILE_NAME for f in files),
                "features": any(os.path.basename(f) == TEMPLATE_FEATURES_FILE_NAME for f in files),
                "output_dir": "resource/image/%s" % rel,
                "copied": [],
                "reference_file": None,
                "skipped": False,
                "skip_reason": None,
            }
            if has_underscore_dir(rel):
                record["skipped"] = True
                record["skip_reason"] = "directory name starts with '_' (unsupported by MaaFramework packaging)"
            elif record["raw"]:
                record["reference_file"] = TEMPLATE_RAW_FILE_NAME
            elif record["mask"]:
                record["reference_file"] = TEMPLATE_MASK_FILE_NAME
                record["skip_reason"] = "raw.png missing, falling back to mask.png"
            else:
                record["skip_reason"] = "no image file in template directory"
            index[rel] = record
            records.append(record)
        return index, records

    def copy_templates(self, records):
        if self.args.skip_templates:
            for record in records:
                record["skip_reason"] = record["skip_reason"] or "--skip-templates"
            return
        for record in records:
            if record["skipped"] or not record["exists"]:
                continue
            if record["reference_file"] is None:
                continue
            copied_any = False
            for src in record["files"]:
                base = os.path.basename(src)
                if not base.lower().endswith(IMAGE_EXTENSIONS):
                    continue
                if base not in (TEMPLATE_RAW_FILE_NAME, TEMPLATE_MASK_FILE_NAME):
                    # Upstream template dirs are supposed to hold exactly one
                    # raw.png (+ optional mask.png); anything else is noise.
                    continue
                data = self.source.show_bytes(src)
                self.emit_bytes("resource/image/%s/%s" % (record["template"], base), data)
                record["copied"].append(base)
                copied_any = True
                self.stats["template_files_copied"] += 1
            if copied_any:
                self.stats["templates_copied"] += 1
            else:
                record["skip_reason"] = "no copyable image file"
                self.stats["templates_missing"] += 1

    # -- screens ---------------------------------------------------------
    def build_screens(self, screens):
        name_to_id = {}
        for screen in screens:
            name_to_id[screen["screen_name"]] = screen["screen_id"]
            name_to_id.setdefault(screen["screen_id"], screen["screen_id"])

        self.click_sites = scrape_click_sites(self.source, screens)
        self.template_index, template_records = self.scan_templates(screens)
        self.copy_templates(template_records)
        self.report["templates"] = template_records

        unresolved = []
        for screen in screens:
            used_names = set()
            color_nodes = {}
            nodes = {}
            area_records = []
            for area in screen["areas"]:
                built = self.build_area(screen, area, used_names, color_nodes)
                nodes[built["node_name"]] = built["node"]
                area_records.append(built["record"])

            # ---- resolve goto_list -> `next` ---------------------------
            missing_targets = []
            for record in area_records:
                resolved = []
                for target in record["goto_list"]:
                    target_id = name_to_id.get(target)
                    if target_id is None:
                        missing_targets.append(target)
                        continue
                    if target_id not in resolved:
                        resolved.append(target_id)
                if resolved:
                    nodes[record["node"]]["next"] = resolved
                    record["next"] = resolved
                elif record["goto_list"]:
                    nodes[record["node"]].pop("next", None)
                    record["next"] = []
                    record["skip_reason"] = "goto_list could not be resolved"

            # ---- screen recognition node -------------------------------
            id_mark = [r for r in area_records if r["id_mark"]]
            if id_mark:
                match_mode = "id_mark"
                source_records = id_mark
            elif area_records:
                match_mode = "all_areas_fallback"
                source_records = area_records
            else:
                match_mode = "no_areas"
                source_records = []
            any_of = [nodes[r["node"]]["recognition"] for r in source_records]
            if any_of:
                screen_recognition = {"type": "Or", "param": {"any_of": any_of}}
            else:
                screen_recognition = {"type": "DirectHit", "param": {"roi": [0, 0, 0, 0]}}
            screen_node = {
                "recognition": screen_recognition,
                "action": {"type": "DoNothing"},
            }
            # Navigation wiring: a screen node's `next` is its own area nodes in
            # source order. MaaFramework's `next` semantics ("按顺序识别 next 中的
            # 每个节点，只执行第一个识别到的") mean only the first *matching* area
            # is acted on, then execution continues from that area's own `next`
            # (which is the converted goto_list). Without these edges the area
            # nodes would be unreachable since upstream declares no edge from a
            # screen to its own areas.
            area_next = [r["node"] for r in area_records]
            if area_next:
                screen_node["next"] = area_next

            pipeline = {screen["screen_id"]: screen_node}
            pipeline.update(nodes)
            pipeline.update(color_nodes)

            converted = sum(1 for r in area_records if not r["skipped"])
            skipped = len(area_records) - converted
            self.stats["areas_total"] += len(area_records)
            self.stats["areas_converted"] += converted
            self.stats["areas_skipped"] += skipped
            self.stats["nodes"] += len(pipeline)
            self.stats["unresolved_goto"] += len(set(missing_targets))

            self.emit_json("resource/pipeline/%s.json" % screen["screen_id"], pipeline)
            self.stats["screens"] += 1

            for target in sorted(set(missing_targets)):
                unresolved.append({"screen_id": screen["screen_id"], "target": target})

            self.report["screens"].append(
                {
                    "screen_id": screen["screen_id"],
                    "screen_name": screen["screen_name"],
                    "app_id": screen["app_id"],
                    "pc_alt": screen["pc_alt"],
                    "source_file": screen["file"],
                    "pipeline_file": "resource/pipeline/%s.json" % screen["screen_id"],
                    "match_mode": match_mode,
                    "id_mark_areas": len(id_mark),
                    "areas_total": len(area_records),
                    "areas_converted": converted,
                    "areas_skipped": skipped,
                    "nodes_emitted": len(pipeline),
                    "areas": area_records,
                }
            )
        self.report["unresolved_goto"] = unresolved

    # -- tasks -----------------------------------------------------------
    def build_tasks(self, apps, screens):
        by_app = {}
        for screen in screens:
            by_app.setdefault(screen["app_id"], []).append(screen["screen_id"])
        for value in by_app.values():
            value.sort()

        screens_by_id = {s["screen_id"]: s for s in screens}
        app_group_by_id = {}
        tasks = []
        task_nodes = {}
        seen_entries = set()

        for app_id in sorted(apps):
            app = apps[app_id]
            group = APP_GROUP.get(app_id, DEFAULT_GROUP)
            entry = "Task%s" % pascal_case(app_id)
            if entry in seen_entries:
                entry = "%s_%s" % (entry, len(seen_entries))
            seen_entries.add(entry)
            owned = [sid for sid in by_app.get(app_id, []) if sid in screens_by_id]
            global_screens = sorted(s for s in by_app.get("", []) if s in screens_by_id)
            task_nodes[entry] = {
                "recognition": {"type": "DirectHit", "param": {"roi": [0, 0, 0, 0]}},
                "action": {"type": "DoNothing"},
            }
            # `next: []` would be an empty (no-op) edge list; omit the key instead
            # so the stub is honest about having no route. The report records
            # which tasks are declaration-only.
            if owned:
                task_nodes[entry]["next"] = list(owned)
            confidence = "navigation-stub-package-known" if owned else "taxonomy-only-no-screens"
            app_group_by_id[app_id] = group
            tasks.append(
                {
                    "name": entry,
                    "entry": entry,
                    "app_id": app_id,
                    "app_name": app["app_name"],
                    "package": app["package"],
                    "group": group,
                    "screens": owned,
                    "global_screens_available": len(global_screens),
                    "confidence": confidence,
                    "source_files": app["files"],
                }
            )
        self.stats["tasks"] = len(tasks)
        self.emit_json("resource/pipeline/tasks.json", task_nodes)
        self.report["tasks"] = tasks
        self.report["global_screens"] = sorted(by_app.get("", []))
        return tasks

    # -- interface + locale ---------------------------------------------
    def build_interface(self, tasks):
        interface = {
            "interface_version": 2,
            "name": "MaaPocketZZZ",
            "label": "$interface.label",
            "version": "0.1.0",
            "description": "$interface.description",
            "welcome": "$interface.welcome",
            "license": "GPL-3.0 (derived from ZenlessZoneZero-OneDragon)",
            "controller": [
                {
                    "name": "Android",
                    "label": "$controller.Android.label",
                    "type": "Adb",
                    "display_short_side": DISPLAY_SHORT_SIDE,
                    "attach_resource_path": ["./resource"],
                }
            ],
            "resource": [{"name": "国服", "path": ["./resource"]}],
            "group": [{"name": g, "label": "$group.%s.label" % g} for g, _ in GROUPS],
            "task": [
                {
                    "name": t["name"],
                    "label": "$task.%s.label" % t["name"],
                    "entry": t["entry"],
                    "group": [t["group"]],
                    "description": "$task.%s.description" % t["name"],
                }
                for t in tasks
            ],
            # `option` is an OBJECT map (title "配置项定义": "为一个对象映射"),
            # not an array -- unlike `global_option` and `import`.
            "option": {},
            "agent": [],
            "import": [],
            "languages": {"zh_cn": "locales/interface/zh_cn.json"},
        }
        self.emit_json("interface.json", interface)

        locale = {
            "interface": {
                "label": "绝区零",
                "description": "由 ZenlessZoneZero-OneDragon 迁移的《绝区零》Android 自动化资源包",
                "welcome": "本资源包只迁移了画面识别与区域数据；所有 roi 与模板图来自 PC 端，需按 RECAPTURE.md 重新采集。",
            },
            "controller": {"Android": {"label": "Android 设备"}},
            "group": {g: {"label": label} for g, label in GROUPS},
            "task": {},
        }
        for task in tasks:
            locale["task"][task["name"]] = {
                "label": task["app_name"],
                "description": "上游应用 %s（包 %s，app_id=%s）；迁移置信度：%s"
                % (task["app_name"], task["package"], task["app_id"], task["confidence"]),
            }
        self.emit_json("locales/interface/zh_cn.json", locale)
        return interface

    # -- report ----------------------------------------------------------
    def build_rules(self):
        self.report["rules"] = {
            "template_resolution": {
                "rule": "assets/template/<template_sub_dir>/<template_id>/raw.png",
                "evidence": [
                    "src/one_dragon/base/screen/template_info.py: "
                    "TEMPLATE_RAW_FILE_NAME = 'raw.png'",
                    "src/one_dragon/base/screen/template_info.py: "
                    "get_template_dir_path(sub_dir, template_id) -> os.path.join(template_root, sub_dir, template_id)",
                    "src/one_dragon/base/screen/template_info.py: "
                    "get_template_raw_path(...) -> os.path.join(get_template_dir_path(...), TEMPLATE_RAW_FILE_NAME)",
                ],
                "one_image_per_directory": True,
                "note": (
                    "TemplateLoader walks exactly two levels (assets/template/<g1>/<g2>/) and "
                    "cache-keys on '<sub_dir>:<template_id>'; the directory - not the file - is the "
                    "template identity, and it is expected to contain raw.png (+ optional mask.png)."
                ),
            },
            "mask_representability": {
                "maaframework_fields": ["green_mask"],
                "polygon_representable": False,
                "note": (
                    "config.yml point_list + template_shape + auto_mask generate a polygon mask.png "
                    "(cv2.fillPoly/circle/rectangle) and crop raw.png to its bounding box. "
                    "MaaFramework exposes only `green_mask` (paint excluded pixels RGB(0,255,0)); "
                    "there is no arbitrary/alpha mask field, so polygon masks cannot be represented."
                ),
            },
            "pc_rect_to_roi": {
                "rule": "roi = [x1, y1, x2 - x1, y2 - y1]",
                "upstream_rect_semantics": "Rect(x1, y1, x2, y2), absolute pixels, top-left origin",
                "reference_resolution": [DESIGN_WIDTH, DESIGN_HEIGHT],
                "evidence": [
                    "src/one_dragon/base/controller/pc_game_window.py:16-21: "
                    "def __init__(self, standard_width: int = 1920, standard_height: int = 1080)",
                    "config/project.yml:6-7: screen_standard_width: 1920 / screen_standard_height: 1080",
                    "src/zzz_od/context/zzz_context.py: passes screen_standard_width/height into ZPcController",
                ],
                "scale_factor": 1.0,
                "caveat": (
                    "MaaFramework scales the screenshot so its SHORT side equals display_short_side. "
                    "With display_short_side = 1080 a 1080-tall landscape phone maps 1:1 vertically; "
                    "horizontally the PC 16:9 layout only matches on a 16:9 device."
                ),
            },
            "goto_list": {
                "rule": "next = [target screens' screen_id recognition nodes]",
                "upstream_semantics": "list[str] of screen NAMES reachable after interacting with the area",
                "evidence": [
                    "src/one_dragon/base/screen/screen_area.py: "
                    "self.goto_list: list[str] = [] if goto_list is None else goto_list  # 交互后 可能会跳转的画面名称列表",
                    "src/one_dragon/base/screen/screen_loader.py: init_screen_route() adds a graph edge "
                    "from_screen --(area)--> every name in goto_list, then runs Floyd-Warshall",
                ],
                "maaframework_semantics": (
                    "next: 按顺序识别 next 中的每个节点，只执行第一个识别到的。"
                ),
            },
            "click_vs_donothing": {
                "rule": (
                    "precedence: (1) area clicked by name in upstream source -> Click; "
                    "(2) goto_list non-empty -> Click; (3) area only ever read via find_area/get_area -> "
                    "DoNothing; (4) id_mark true -> DoNothing; (5) area name appears as a source literal "
                    "but not inside a resolvable click call -> Click; (6) no evidence -> DoNothing"
                ),
                "why": (
                    "the screen YAML has no click flag of any kind, so click intent had to come from the "
                    "calling code: ScreenUtils.find_and_click_area/click_area is where an area is clicked, "
                    "find_area / ScreenLoader.get_area is where it is only read (OCR crop, template search "
                    "window, branch condition)."
                ),
                "evidence": [
                    "src/one_dragon/base/screen/screen_area.py: "
                    "self.id_mark: bool = id_mark  # 是否用于画面的唯一标识",
                    "src/one_dragon/base/screen/screen_utils.py: is_target_screen() only reads id_mark areas",
                    "src/one_dragon/base/screen/screen_match.py: find_area_with_detail() checks is_text_area first, then is_template_area",
                    "src/one_dragon/base/screen/screen_utils.py: find_and_click_area() clicks ANY area, "
                    "so the data itself cannot express 'do not click'",
                    "src/zzz_od/application/email_app/email_app.py:46: "
                    "self.round_by_find_and_click_area(self.last_screenshot, '邮件', '全部领取', ...)",
                    "src/zzz_od/operation/compendium/area_patrol.py:243: "
                    "area = ctx.screen_loader.get_area('区域巡防', '剩余电量')  # read-only OCR crop",
                ],
                "scrape": {
                    "root": CLICK_SITE_ROOT,
                    "patterns": {
                        "click": "(?:\\w+_)?click_area\\s*\\(",
                        "read": "(?:\\w+_)?find_area\\s*\\(|(?:\\w+_)?get_area\\s*\\(",
                    },
                    "limitation": (
                        "an area name passed through a local variable "
                        "(first_area = '对话框确认' -> find_and_click_area(shot, '大世界', first_area)) is "
                        "invisible to the click scan; such names fall through to the "
                        "'literal-ambiguous-use' label. f-string names (f'宣传员-{idx}') cannot be "
                        "resolved at all and are listed under dynamic_area_names."
                    ),
                },
                "note": "the upstream data has no explicit click flag; intent was inferred from the above.",
            },
            "screen_to_area_navigation": {
                "rule": "screen node.next = that screen's area node names, in source order",
                "why": (
                    "upstream declares no edge from a screen to its own areas; without this the area "
                    "nodes would be unreachable. MaaFramework picks the first MATCHING entry of `next` "
                    "and then continues from that node's own next, which reproduces upstream's "
                    "'find the area, click it, follow its goto_list' behaviour."
                ),
                "caveat": (
                    "upstream's goto graph contains cycles (e.g. menu -> email -> menu); MaaFramework "
                    "loops until the node timeout/max_hit is reached. Set max_hit/timeout per task."
                ),
            },
            "ocr": {
                "expected_rule": "expected = [re.escape(text)] (literal substring match)",
                "threshold_rule": "threshold = lcs_percent",
                "caveat": (
                    "MaaFramework's OCR `threshold` is a MODEL CONFIDENCE threshold (default 0.3), "
                    "not an LCS similarity ratio. lcs_percent values are copied into it as a best-effort "
                    "approximation and MUST be re-tuned during recapture."
                ),
            },
            "template_match": {
                "threshold_rule": "threshold = template_match_threshold (default 0.7)",
                "template_path": "resource/image/<template_sub_dir>/<template_id>/raw.png "
                "(MaaFramework template paths are relative to the `image` folder)",
            },
            "color_range": {
                "rule": "ColorMatch helper node (method 4 = RGB) + OCR `color_filter`",
                "evidence": [
                    "src/one_dragon/base/matcher/ocr/ocr_service.py:80-96: "
                    "_apply_color_filter() -> cv2.inRange(image, np.array(color_range[0]), np.array(color_range[1]))",
                ],
                "channel_order": (
                    "upstream lower/upper are BGR triples because cv2 works on BGR; MaaFramework "
                    "method 4 converts BGR->RGB first, so the triples are reversed."
                ),
            },
            "unrepresented_upstream_fields": [
                {
                    "field": "lcs_percent",
                    "reason": "no LCS filter in MaaFramework; copied into OCR threshold as an approximation",
                },
                {
                    "field": "point_list / template_shape / auto_mask (config.yml)",
                    "reason": "polygon masks have no MaaFramework representation",
                },
                {
                    "field": "id_mark",
                    "reason": "used to derive the screen recognition node and the Click/DoNothing split",
                },
                {
                    "field": "pc_alt",
                    "reason": "upstream-only flag marking an alternate PC-layout screen; recorded but unused",
                },
                {
                    "field": "gamepad_key",
                    "reason": "PC gamepad binding; no Android equivalent",
                },
                {
                    "field": "features.xml",
                    "reason": "OpenCV feature cache used by TemplateLoader; MaaFramework re-extracts internally",
                },
            ],
        }

    def build_validation(self):
        if not self.args.schemas:
            self.report["validation"] = {"skipped": "no --schemas given"}
            return []
        pipeline_schema_path = os.path.join(self.args.schemas, "pipeline.schema.json")
        interface_schema_path = os.path.join(self.args.schemas, "interface.schema.json")
        results = {"pipeline": {}, "interface": {}}
        errors = []
        with open(pipeline_schema_path, "r", encoding="utf-8") as handle:
            pipeline_schema = json.load(handle)
        pipeline_validator = SchemaValidator(pipeline_schema)
        for rel in self.files_written:
            if not rel.startswith("resource/pipeline/"):
                continue
            with open(os.path.join(self.out, rel), "r", encoding="utf-8") as handle:
                data = json.load(handle)
            found = pipeline_validator.validate(data)
            results["pipeline"][rel] = found
            for message in found:
                errors.append({"file": rel, "error": message})
        if os.path.isfile(interface_schema_path):
            with open(interface_schema_path, "r", encoding="utf-8") as handle:
                interface_schema = json.load(handle)
            with open(os.path.join(self.out, "interface.json"), "r", encoding="utf-8") as handle:
                interface = json.load(handle)
            found = SchemaValidator(interface_schema).validate(interface)
            results["interface"]["interface.json"] = found
            for message in found:
                errors.append({"file": "interface.json", "error": message})
        self.report["validation"] = {
            "schemas_dir": os.path.abspath(self.args.schemas),
            "files_checked": len(results["pipeline"]) + len(results["interface"]),
            "errors": errors,
            "results": results,
        }
        return errors

    # -- driver ----------------------------------------------------------
    def run(self):
        self.prepare_out()

        screens, notes = load_screens(self.source, self.only_screens)
        apps = load_apps(self.source)
        self.report["notes"]["screen_loading"] = {
            "files_read": len(notes["files"]),
            "parse_errors": notes["parse_errors"],
            "duplicate_ids": notes["duplicate_ids"],
            "duplicate_names": notes["duplicate_names"],
            "only_screens_filter": sorted(self.only_screens),
        }
        self.report["notes"]["apps"] = apps

        self.build_screens(screens)
        tasks = self.build_tasks(apps, screens)
        self.build_interface(tasks)
        self.build_rules()

        self.stats["files_written"] = len(self.files_written)
        self.stats["bytes_written"] = self.bytes_written
        if self.click_sites is not None:
            evidence_counts = {}
            for screen_report in self.report["screens"]:
                for record in screen_report["areas"]:
                    key = record["click_evidence"]
                    evidence_counts[key] = evidence_counts.get(key, 0) + 1
            self.stats["click_evidence"] = evidence_counts
            self.report["notes"]["click_sites"] = {
                "root": self.click_sites["root"],
                "files_scanned": len(self.click_sites["files"]),
                "click_call_sites": self.click_sites["call_sites"],
                "read_call_sites": self.click_sites["read_call_sites"],
                "click_pairs": len(self.click_sites["pairs"]),
                "click_bare_names": len(self.click_sites["bare"]),
                "read_pairs": len(self.click_sites["read_pairs"]),
                "read_bare_names": len(self.click_sites["read_bare"]),
                "source_literals": len(self.click_sites["literals"]),
                "unresolved_screen_area_literals": self.click_sites["loose"],
                "dynamic_area_names": self.click_sites["dynamic_area_names"],
            }
        self.report["stats"] = self.stats
        self.report["files"] = sorted(self.files_written)

        validation_errors = self.build_validation()

        # migration_report.json is written last so it can record everything.
        path = os.path.join(self.out, "migration_report.json")
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(json.dumps(self.report, ensure_ascii=False, indent=2) + "\n")

        print("screens      : %d" % self.stats["screens"])
        print("areas        : %d total / %d converted / %d skipped"
              % (self.stats["areas_total"], self.stats["areas_converted"], self.stats["areas_skipped"]))
        print("template refs: %d (%d dirs copied, %d image files)"
              % (len(self.report["templates"]), self.stats["templates_copied"],
                 self.stats["template_files_copied"]))
        print("tasks        : %d" % self.stats["tasks"])
        print("files        : %d emitted (%d changed, %d already up to date, %d bytes)"
              % (len(self.files_written), self.stats["files_changed"],
                 self.stats["files_unchanged"], self.bytes_written))
        print("out          : %s" % self.out)

        if notes["parse_errors"]:
            for entry in notes["parse_errors"]:
                print("YAML parse error: %s: %s" % (entry["file"], entry["error"]), file=sys.stderr)
            return 1
        if validation_errors:
            for entry in validation_errors[:20]:
                print("schema error: %s: %s" % (entry["file"], entry["error"]), file=sys.stderr)
            return 1
        return 0


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def build_parser():
    parser = argparse.ArgumentParser(
        prog="migrate_onedragon_zzz.py",
        description=(
            "Migrate ZenlessZoneZero-OneDragon screen/area data into a "
            "MaaFramework Project-Interface-V2 resource pack."
        ),
    )
    parser.add_argument("--source", required=True, help="path to the OneDragon git checkout")
    parser.add_argument("--out", required=True, help="PI pack root (the folder holding interface.json)")
    parser.add_argument("--schemas", default=None, help="folder holding pipeline.schema.json / interface.schema.json")
    parser.add_argument("--force", action="store_true", help="wipe --out before writing")
    parser.add_argument("--only-screens", default="", help="comma separated screen_id allow-list")
    parser.add_argument("--skip-templates", action="store_true", help="do not copy template images")
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    return Migration(args).run()


if __name__ == "__main__":
    sys.exit(main())
