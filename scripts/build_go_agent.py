#!/usr/bin/env python3
"""把 MaaEnd 的 Go agent（`agent/go-service`）交叉编译成能装进 APK 的产物。

## 这个脚本解决什么问题

MaaEnd 的 `assets/interface.json` 声明了两个 agent：

    "agent": [
        { "child_exec": "agent/go-service" },
        { "child_exec": "agent/cpp-algo", "child_args": [] }
    ]

`agent/go-service` 是一个 Go 程序。运行期 MaaFramework 的 `MaaAgentClient` 会把它作为
**子进程**拉起，它再用 `MaaAgentServerStartUp(identifier)` + `MaaAgentServerJoin()` 把
自己注册的 custom recognition / custom action 通过 socket 交回框架（细节见
`MaaPocket/docs/agents.md`）。MaaPocket 要在 Android 上跑它，就必须自己交叉编译出
`android/arm64-v8a`、`android/x86_64` 两个 ELF——因为 MaaEnd 上游只发布桌面平台的 agent。

## 为什么产物必须叫 `lib*.so`

Android 的 APK 只会在 `lib/<abi>/` 下保留文件名匹配 `lib*.so` 的原生库；
`android:extractNativeLibs="true"` + `packaging.jniLibs.useLegacyPackaging = true`
才会让安装器把 `lib/<abi>/libXxx.so` **原样解包**到 `nativeLibraryDir`
（关闭 legacy packaging 时 `.so` 会被页对齐直接 mmap 进 APK、单个文件不再是独立可执行文件）。

所以 MaaPocket 的做法是：把一个单文件 ELF **伪装成共享库**，文件名取 `lib*.so`，
这样它就能合法地躺在 `lib/<abi>/` 里，被 `PackageManager` 完整解出来，
再由 app 用 `File(nativeLibraryDir, "libMaaEnd_go_service.so")` 拿到真实可执行文件去 `exec`。
文件名只是给打包器看的，ELF 本身仍然是 `ET_DYN` 的 PIE **可执行文件**，不是 `dlopen` 用的库。

## 为什么是 GOOS=android 而不是 GOOS=linux

`github.com/MaaXYZ/maa-framework-go/v4` 用 `github.com/ebitengine/purego` 手写 `dlopen`，
不含任何 cgo（`internal/native/native_unix.go`、`internal/native/native.go`），
因此 `CGO_ENABLED=0` 即可，不需要 Android NDK。选 `GOOS=android` 的理由：

* Go 的 build tag 规则里 `GOOS=android` **隐含满足 `//go:build linux`**
  （`src/go/build/build.go`: `if ctxt.GOOS == "android" && name == "linux" { return true }`），
  所以 `ziplineimport/*.go`（`//go:build linux`）、`pkg/parentwatch/parentwatch_other.go`
  （`//go:build !windows`）这些 POSIX 实现都会被正确选中；
* `shirou/gopsutil/v4` 的 `process/process_linux.go` 也是 `//go:build linux`，同样满足；
* `maa-framework-go` 自己的平台分支写的是 `case "linux", "android": return "libMaaFramework.so"`；
* 最要紧的是：`GOOS` 会被编译进二进制，`runtime.GOOS` 在设备上必须真的是 `android`，
  否则上游那些按 `runtime.GOOS` 分叉的代码（如 `intelarchive/showinventory.go` 选
  `cmd` / `open` / `xdg-open`）会走错分支。

`CGO_ENABLED=0` + 不许额外的 `purego` build tag：`pkg/minicv/simd_amd64.go`
（`//go:build amd64 && !purego`）在 `GOARCH=amd64` 时会带上 `simd_amd64.s` 汇编实现，
这是想要的；手动加 `purego` tag 反而会退化到纯 Go 分支。

## 产出的目录形状

    dist/agents/
      agents.json                             # 清单：每个 abi、每个 agent 的 sha256/size/ELF 头
      arm64-v8a/
        libMaaEnd_go_service.so               # 单文件 ELF（PIE, AArch64）
        bundle/go-service/                    # 运行期数据文件（agent 的 cwd 应该设在这里）
          locales/go-service/*.json
          locales/go-service/HTML/*.html
          locales/interface/*.json
      x86_64/
        ... 同上，machine = x86-64

### 为什么 bundle 放在 `bundle/go-service/`

`agent/go-service/pkg/i18n/i18n.go` 的 `resolveLocaleDir()` 会从
`os.Getwd()` 和 `filepath.Dir(os.Executable())` 出发各向上找 6 层，在每个候选根目录下
依次尝试 `locales/go-service` 和 `assets/locales/go-service`，并且要求该目录下存在
`zh_cn.json` 才算数。紧接着 `Init()` 还会合并**同级**的 `interface` 目录
（`siblingLocaleDir(dir, "interface")` = `filepath.Join(filepath.Dir(localeDir), "interface")`）。

因此把 agent 的工作目录（`workingDir`）设成 `<...>/bundle/go-service`，
`locales/go-service` 就是第一个命中的候选，`locales/interface` 也正好是它的同级目录——
两个目录一次满足。**注意 `locales/` 必须放在 bundle 根下，不要多套一层 `assets/`**，
虽然 `assets/locales/go-service` 也在候选列表里，但那样同级目录就变成了
`assets/locales/interface`，多一层没有意义。

除此之外 `agent/go-service/agent.go:19` 把 MaaFramework 的 `.so` 目录**硬编码**成
`filepath.Join(getCwd(), "maafw")`——上游代码既不读 `LD_LIBRARY_PATH` 也不读
`MAAFW_BINARY_PATH`。所以宿主要在 `<workingDir>/maafw/` 里铺好
`libMaaFramework.so` 等库，否则 `maa.Init` 直接 `log.Fatal`。这属于宿主（app）职责，
本脚本不产出。

## 幂等

清单里记着 `source.digest`（go-service 整棵树的 sha256）和 `toolchain.go_version`。
两者都没变、且已记录的产物文件都还在（size + sha256 对得上）时，该 abi 直接跳过编译，
复用旧清单条目。`--force` 无视该判断强制重编。重建某个 abi 时，上一次清单里记录在
该 abi 目录下、这次不再产出的文件会被删除（比如上游删掉了某个 locale）。

## 用法

    python scripts/build_go_agent.py --source refs/MaaEnd
    python scripts/build_go_agent.py --source upstream/MaaEnd --abi x86_64 --out dist/agents
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


def _configure_std_streams() -> None:
    """Windows 控制台默认是 GBK/cp936，直接 print 中文路径会炸 UnicodeEncodeError。"""
    if sys.platform != "win32":
        return
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(encoding="utf-8", errors="replace", line_buffering=True)


_configure_std_streams()


SCHEMA_VERSION = 1
GENERATED_BY = "scripts/build_go_agent.py"

# ABI（Android / Gradle 的叫法） -> GOARCH（Go 的叫法）。
ABI_TO_GOARCH = {
    "arm64-v8a": "arm64",
    "x86_64": "amd64",
}

# ABI -> (ELF e_machine, 人类可读名)。第 18 字节起的 2 字节小端整数。
ELF_MACHINE_BY_ABI = {
    "arm64-v8a": (0xB7, "AArch64"),
    "x86_64": (0x3E, "x86-64"),
}

# ABI -> ELF EI_CLASS。两个 64 位目标都是 ELFCLASS64。
ELF_CLASS_BY_ABI = {
    "arm64-v8a": 2,
    "x86_64": 2,
}

ELF_MAGIC = b"\x7fELF"
ELFCLASS32 = 1
ELFCLASS64 = 2
ELFDATA2LSB = 1
ET_EXEC = 2
ET_DYN = 3
ELF_TYPE_NAMES = {0: "ET_NONE", 1: "ET_REL", 2: "ET_EXEC", 3: "ET_DYN", 4: "ET_CORE"}

# 上游 go 模块根（相对 MaaEnd checkout 根）。
GO_SERVICE_REL = Path("agent") / "go-service"
GO_SERVICE_IDENTIFIER = "agent/go-service"
GO_SERVICE_ARTIFACT = "libMaaEnd_go_service.so"
BUNDLE_DIRNAME = "bundle"
BUNDLE_AGENT_DIRNAME = "go-service"

# 运行期必须跟着二进制一起走的语言资源。左侧是 MaaEnd 里的相对路径，
# 右侧是 bundle 里的相对路径（都相对于各自根目录）。
LOCALE_COPIES = (
    ("assets/locales/go-service", "locales/go-service"),
    ("assets/locales/interface", "locales/interface"),
)
# i18n.go 的 localeDirExists() 要求这个文件存在，否则整个目录被当成不存在。
LOCALE_MARKER = Path("locales") / "go-service" / "zh_cn.json"

# 遍历源码树算 digest 时跳过的目录名（都是本地/构建残留，不属于源码）。
_DIGEST_SKIP_DIRS = {"debug", "build", ".git", ".idea", ".vscode", "node_modules", "__pycache__"}

_GO_DIRECTIVE_RE = re.compile(r"^go\s+(\S+)\s*$", re.MULTILINE)
_MODULE_RE = re.compile(r"^module\s+(\S+)\s*$", re.MULTILINE)


class BuildError(RuntimeError):
    """脚本自身的可预期失败。main() 会把它转成一行 ::error:: 并 exit 1。"""


# --------------------------------------------------------------------------- #
# 基础设施
# --------------------------------------------------------------------------- #


def project_root() -> Path:
    return Path(__file__).resolve().parent.parent


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def read_gradle_property(root: Path, key: str, default: str) -> str:
    """只认顶层 `key=value`；跳过空行和 `#` / `!` 注释。"""
    path = root / "gradle.properties"
    if not path.is_file():
        return default
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        if not line or line[0] in "#!" or "=" not in line:
            continue
        name, _, value = line.partition("=")
        if name.strip() == key:
            return value.strip()
    return default


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def rel_posix(root: Path, path: Path) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return path.resolve().as_posix()


def human_mb(n: int) -> str:
    return f"{n / (1024 * 1024):.2f} MB"


def resolve_under_root(root: Path, raw: str) -> Path:
    """相对路径一律按仓库根解析，和 workflow 里 `refs/MaaEnd`、`dist/agents` 的写法一致。"""
    path = Path(raw)
    if not path.is_absolute():
        path = root / path
    return path.resolve()


def run(cmd: list[str], *, cwd: Path | None = None, env: dict[str, str] | None = None) -> str:
    """跑一个外部命令，失败时把完整命令行 + stderr 一起抛出来。"""
    merged = dict(os.environ)
    if env:
        merged.update(env)
    try:
        proc = subprocess.run(
            cmd,
            cwd=str(cwd) if cwd else None,
            env=merged,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
    except FileNotFoundError as exc:
        raise BuildError(
            f"找不到可执行文件 {cmd[0]!r}（{exc}）。用 --go 指定 Go 工具链路径，"
            f"或确认它在 PATH 上。"
        ) from exc
    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout or "").strip()
        raise BuildError(
            f"命令失败（exit {proc.returncode}）：\n  {' '.join(cmd)}\n  cwd={cwd}\n"
            f"--- 输出 ---\n{detail}"
        )
    return proc.stdout or ""


def directory_digest(root: Path, seed: str = "") -> tuple[str, int, int]:
    """对一棵目录树做稳定摘要：相对路径 + 每个文件的 sha256，按路径排序。

    返回 (hex digest, 文件数, 总字节数)。跳过 _DIGEST_SKIP_DIRS 里的目录。
    """
    digest = hashlib.sha256()
    digest.update(seed.encode("utf-8"))
    count = 0
    total = 0
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in _DIGEST_SKIP_DIRS)
        for name in sorted(filenames):
            path = Path(dirpath) / name
            rel = path.relative_to(root).as_posix()
            digest.update(rel.encode("utf-8"))
            digest.update(b"\0")
            digest.update(bytes.fromhex(sha256_of(path)))
            digest.update(b"\n")
            count += 1
            total += path.stat().st_size
    return digest.hexdigest(), count, total


# --------------------------------------------------------------------------- #
# ELF 校验（零第三方依赖：只读头 20 字节）
# --------------------------------------------------------------------------- #


def read_elf_header(path: Path) -> dict:
    """解析 ELF 前 20 字节。

    布局（全部小端，64 位目标的 e_type/e_machine 都是 2 字节，与位宽无关）：
        0..3   e_ident magic   \\x7f E L F
        4      e_ident[EI_CLASS]    1=ELFCLASS32 2=ELFCLASS64
        5      e_ident[EI_DATA]     1=2LSB 2=2MSB
        6      e_ident[EI_VERSION]  1
        16..17 e_type
        18..19 e_machine
    """
    with path.open("rb") as fh:
        head = fh.read(20)
    if len(head) < 20 or head[:4] != ELF_MAGIC:
        raise BuildError(
            f"{path} 不是 ELF 文件：magic = {head[:4]!r}（期望 {ELF_MAGIC!r}）。"
            f"如果它看起来像 shell 脚本或文本，说明 go build 根本没有把二进制写到这个位置。"
        )
    ei_class = head[4]
    ei_data = head[5]
    ei_version = head[6]
    if ei_data == 2:
        byte_order = ">"
    else:
        byte_order = "<"
    e_type, e_machine = struct.unpack_from(f"{byte_order}HH", head, 16)
    return {
        "class": ei_class,
        "class_name": {1: "ELFCLASS32", 2: "ELFCLASS64"}.get(ei_class, f"unknown({ei_class})"),
        "data": ei_data,
        "data_name": {1: "little-endian", 2: "big-endian"}.get(ei_data, f"unknown({ei_data})"),
        "ident_version": ei_version,
        "type": e_type,
        "type_name": ELF_TYPE_NAMES.get(e_type, f"unknown({e_type})"),
        "machine": e_machine,
    }


def verify_elf(path: Path, abi: str, machine_name: str) -> dict:
    """校验收出来的二进制确实是这个 ABI 的 ELF；不一致就大声失败。"""
    info = read_elf_header(path)
    want_machine, want_machine_name = ELF_MACHINE_BY_ABI[abi]
    want_class = ELF_CLASS_BY_ABI[abi]

    problems: list[str] = []
    if info["data"] != ELFDATA2LSB:
        problems.append(f"EI_DATA = {info['data']}（{info['data_name']}），期望 1（little-endian）")
    if info["class"] != want_class:
        problems.append(
            f"EI_CLASS = {info['class']}（{info['class_name']}），期望 {want_class}"
        )
    if info["machine"] != want_machine:
        problems.append(
            f"e_machine = 0x{info['machine']:02X}，期望 0x{want_machine:02X}（{want_machine_name}）"
        )
    if info["type"] == ET_EXEC:
        # Android 5.0+ 只接受 PIE。GOOS=android 时 Go 的默认 -buildmode 就是 pie
        # （src/internal/platform/supported.go: DefaultPIE -> case "android","ios": true），
        # 所以走到这里多半是有人手动塞了 -buildmode=exe，或者 go 版本行为变了。
        problems.append(
            "e_type = ET_EXEC；Android 5.0+ 拒绝执行非 PIE 可执行文件，"
            "请确认没有传 -buildmode=exe"
        )
    if problems:
        raise BuildError(
            f"{path} 的 ELF 头与目标 ABI {abi}（{machine_name}）不符：\n  - "
            + "\n  - ".join(problems)
            + f"\n实际头：class={info['class_name']} data={info['data_name']} "
            f"type={info['type_name']} machine=0x{info['machine']:02X}"
        )
    return info


# --------------------------------------------------------------------------- #
# 上游读取
# --------------------------------------------------------------------------- #


def require_go_service(source: Path) -> Path:
    go_service = source / GO_SERVICE_REL
    go_mod = go_service / "go.mod"
    if not go_mod.is_file():
        raise BuildError(
            f"没有找到 {go_mod}。--source 必须指向完整的 MaaEnd checkout"
            f"（当前传的是 {source}）。"
        )
    return go_service


def read_go_mod(go_service: Path) -> tuple[str, str]:
    """返回 (module path, go directive)。"""
    text = (go_service / "go.mod").read_text(encoding="utf-8", errors="replace")
    module = _MODULE_RE.search(text)
    go_directive = _GO_DIRECTIVE_RE.search(text)
    return (
        module.group(1) if module else "",
        go_directive.group(1) if go_directive else "",
    )


def git_head(source: Path) -> str | None:
    try:
        out = run(["git", "-C", str(source), "rev-parse", "HEAD"])
    except BuildError:
        return None
    head = out.strip()
    return head or None


def go_version(go: str) -> tuple[str, str]:
    """返回 (完整 `go version` 输出, '1.25.6' 这样的版本号)。"""
    out = run([go, "version"]).strip()
    match = re.search(r"\bgo(\d+\.\d+(?:\.\d+)?)", out)
    if not match:
        raise BuildError(f"无法从 `{go} version` 的输出里解析版本号：{out!r}")
    return out, match.group(1)


def version_tuple(text: str) -> tuple[int, ...]:
    parts: list[int] = []
    for piece in text.split("."):
        if not piece.isdigit():
            break
        parts.append(int(piece))
    return tuple(parts)


# --------------------------------------------------------------------------- #
# 构建
# --------------------------------------------------------------------------- #


def build_one(
    *,
    abi: str,
    go_service: Path,
    out_dir: Path,
    go: str,
    tags: list[str],
    force: bool,
    previous: dict | None,
    source_digest: str,
    go_version_text: str,
) -> tuple[Path, dict, bool]:
    """编译一个 abi 的 Go agent。

    返回 (目标文件路径, ELF 头信息, 是否复用了旧产物)。
    """
    goarch = ABI_TO_GOARCH[abi]
    target = out_dir / GO_SERVICE_ARTIFACT
    machine_name = ELF_MACHINE_BY_ABI[abi][1]

    if not force and previous is not None:
        if (
            previous.get("sourceDigest") == source_digest
            and previous.get("goVersion") == go_version_text
            and target.is_file()
            and target.stat().st_size == previous.get("size")
            and sha256_of(target) == previous.get("sha256")
        ):
            info = read_elf_header(target)
            print(f"  [{abi}] 复用已有产物（源码与工具链未变）：{target.name}")
            return target, info, True

    out_dir.mkdir(parents=True, exist_ok=True)
    if target.exists():
        target.unlink()

    env = {
        # GOOS=android 让 runtime.GOOS 在设备上是 "android"，同时 Go 的 build tag 规则
        # 会自动满足 `//go:build linux`，POSIX 实现照常编译进来。
        "GOOS": "android",
        "GOARCH": goarch,
        # maa-framework-go 用 purego 手写 dlopen，go-service 无任何 cgo；
        # 关掉 cgo 就不需要 Android NDK / 交叉编译器。
        "CGO_ENABLED": "0",
    }
    cmd = [go, "build", "-trimpath", "-buildvcs=false", "-ldflags=-s -w"]
    if tags:
        cmd += ["-tags", ",".join(tags)]
    cmd += ["-o", str(target), "."]

    print(f"  [{abi}] GOOS=android GOARCH={goarch} CGO_ENABLED=0")
    print(f"  [{abi}] {' '.join(cmd)}")
    run(cmd, cwd=go_service, env=env)

    if not target.is_file():
        raise BuildError(f"go build 报成功但没生成 {target}")

    info = verify_elf(target, abi, machine_name)
    print(
        f"  [{abi}] ELF 校验通过：{info['class_name']} {info['data_name']} "
        f"{info['type_name']} machine={info['machine']:#04x} ({machine_name}) "
        f"size={human_mb(target.stat().st_size)}"
    )
    return target, info, False


def copy_bundle(*, source: Path, out_dir: Path, allow_missing: bool) -> list[Path]:
    """把运行期语言资源复制成 `<out>/<abi>/bundle/go-service/locales/...`。

    布局理由见模块 docstring：agent 的 cwd 应当设成 `bundle/go-service`，
    这样 i18n 的 `resolveLocaleDir()` 第一个候选 `locales/go-service` 就能命中，
    同级的 `locales/interface` 也正好被 `Init()` 合并进来。
    """
    bundle_root = out_dir / BUNDLE_DIRNAME / BUNDLE_AGENT_DIRNAME
    copied: list[Path] = []

    for src_rel, dst_rel in LOCALE_COPIES:
        src = source / src_rel
        dst = bundle_root / dst_rel
        if not src.is_dir():
            message = (
                f"缺少上游语言资源目录 {src}。\n"
                f"  这通常意味着 MaaEnd 是 sparse / partial checkout。"
                f"本脚本需要完整 checkout（CI 用 actions/checkout@v4 默认就是完整的）。"
            )
            if allow_missing:
                print(f"  警告：{message.splitlines()[0]}（--allow-missing-locales，跳过）")
                continue
            raise BuildError(message)
        if dst.exists():
            shutil.rmtree(dst)
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(src, dst)
        for path in sorted(dst.rglob("*")):
            if path.is_file():
                copied.append(path)

    if copied and not (bundle_root / LOCALE_MARKER).is_file():
        raise BuildError(
            f"复制完成但 {bundle_root / LOCALE_MARKER} 不存在；"
            f"i18n.resolveLocaleDir() 会把整个 locale 目录判定为不存在。"
            f"检查 MaaEnd 的 assets/locales/go-service/ 内容是否完整。"
        )
    return copied


def prune_stale(*, out_root: Path, abi: str, old_files: list[str], new_files: list[str]) -> list[str]:
    """删掉「上一份清单记录在 <abi>/ 下、这次没再产出」的文件，并清掉空目录。"""
    prefix = f"{abi}/"
    removed: list[str] = []
    keep = set(new_files)
    for rel in old_files:
        if not rel.startswith(prefix) or rel in keep:
            continue
        path = out_root / rel
        if path.is_file():
            path.unlink()
            removed.append(rel)
    # 自底向上清空目录。out_root/<abi> 本身保留。
    abi_dir = out_root / abi
    if abi_dir.is_dir():
        for dirpath, dirnames, filenames in os.walk(abi_dir, topdown=False):
            directory = Path(dirpath)
            if directory == abi_dir:
                continue
            if not any(directory.iterdir()):
                directory.rmdir()
    return removed


# --------------------------------------------------------------------------- #
# 清单
# --------------------------------------------------------------------------- #


def load_previous_manifest(out_root: Path) -> dict:
    path = out_root / "agents.json"
    if not path.is_file():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as exc:
        print(f"  警告：忽略无法解析的旧清单 {path}（{exc}）")
        return {}


def write_manifest(out_root: Path, manifest: dict) -> Path:
    path = out_root / "agents.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return path


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #


def parse_abis(raw: str) -> list[str]:
    abis = [item.strip() for item in raw.split(",") if item.strip()]
    if not abis:
        raise BuildError("--abi 为空。")
    unknown = [abi for abi in abis if abi not in ABI_TO_GOARCH]
    if unknown:
        raise BuildError(
            f"不认识的 ABI：{', '.join(unknown)}。"
            f"可选：{', '.join(sorted(ABI_TO_GOARCH))}"
        )
    # 去重但保持顺序
    seen: set[str] = set()
    ordered: list[str] = []
    for abi in abis:
        if abi not in seen:
            seen.add(abi)
            ordered.append(abi)
    return ordered


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="build_go_agent.py",
        description="交叉编译 MaaEnd 的 Go agent（agent/go-service）为 Android ELF。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--source",
        required=True,
        help="MaaEnd checkout 路径（必须含 agent/go-service 与 assets/locales）。",
    )
    parser.add_argument(
        "--out",
        default="dist/agents",
        help="产物根目录，相对仓库根解析（默认 dist/agents）。",
    )
    parser.add_argument(
        "--abi",
        default=None,
        help="逗号分隔，例如 arm64-v8a,x86_64。默认读 gradle.properties 的 maapocket.abis。",
    )
    parser.add_argument("--go", default="go", help="Go 工具链可执行文件（默认 go）。")
    parser.add_argument(
        "--tags",
        default="",
        help="额外的 -tags（逗号分隔）。默认空：GOOS=android 已经隐含 linux tag。",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="无视幂等判断，强制重新编译。",
    )
    parser.add_argument(
        "--allow-missing-locales",
        action="store_true",
        help="上游 assets/locales 缺失时只警告不报错（产物会缺 UI 文案）。",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    root = project_root()

    try:
        source = resolve_under_root(root, args.source)
        if not source.is_dir():
            raise BuildError(f"--source 指向的目录不存在：{source}")
        go_service = require_go_service(source)

        raw_abi = args.abi
        if raw_abi is None:
            raw_abi = read_gradle_property(root, "maapocket.abis", "arm64-v8a")
            print(f"  abi <- gradle.properties: maapocket.abis={raw_abi}")
        abis = parse_abis(raw_abi)

        out_root = resolve_under_root(root, args.out)
        out_root.mkdir(parents=True, exist_ok=True)

        toolchain_text, toolchain_number = go_version(args.go)
        module_path, go_directive = read_go_mod(go_service)
        tags = [t.strip() for t in args.tags.split(",") if t.strip()]

        if go_directive:
            want = version_tuple(go_directive)
            have = version_tuple(toolchain_number)
            if have < want:
                print(
                    f"  警告：go.mod 要求 go {go_directive}，当前工具链是 go{toolchain_number}。"
                    f"若 GOTOOLCHAIN=auto（默认），go 会自行下载所需工具链；"
                    f"若为 local 则编译会失败。"
                )

        seed = f"{toolchain_text}\0{go_directive}\0{module_path}\0{','.join(tags)}"
        digest, file_count, total_bytes = directory_digest(go_service, seed)

        print(f"MaaEnd checkout : {source}")
        print(f"go-service      : {go_service}")
        print(f"module          : {module_path}")
        print(f"go directive    : {go_directive}")
        print(f"source digest   : {digest}")
        print(f"源码文件        : {file_count} 个 / {human_mb(total_bytes)}")
        print(f"工具链          : {toolchain_text}")
        print(f"目标 ABI        : {', '.join(abis)}")
        print(f"输出目录        : {out_root}")
        print("")

        previous_manifest = load_previous_manifest(out_root)
        previous_abis: dict = previous_manifest.get("abis") or {}

        manifest_abis: dict[str, dict] = {}
        produced: list[str] = []

        for abi in abis:
            print(f"[{abi}] 构建 {GO_SERVICE_IDENTIFIER}")
            out_dir = out_root / abi
            previous_entry = (previous_abis.get(abi) or {}).get("agents") or []
            previous_agent = previous_entry[0] if previous_entry else None

            target, elf_info, reused = build_one(
                abi=abi,
                go_service=go_service,
                out_dir=out_dir,
                go=args.go,
                tags=tags,
                force=args.force,
                previous=previous_agent,
                source_digest=digest,
                go_version_text=toolchain_text,
            )

            bundle_files = copy_bundle(
                source=source, out_dir=out_dir, allow_missing=args.allow_missing_locales
            )
            bundle_root = out_dir / BUNDLE_DIRNAME / BUNDLE_AGENT_DIRNAME
            bundle_bytes = sum(p.stat().st_size for p in bundle_files)

            abi_files = [
                rel_posix(out_root, target),
                *sorted(rel_posix(out_root, p) for p in bundle_files),
            ]

            old_files = (previous_abis.get(abi) or {}).get("files") or []
            removed = prune_stale(
                out_root=out_root, abi=abi, old_files=old_files, new_files=abi_files
            )
            for rel in removed:
                print(f"  [{abi}] 删除过期文件 {rel}")

            manifest_abis[abi] = {
                "goarch": ABI_TO_GOARCH[abi],
                "goos": "android",
                "cgoEnabled": "0",
                "agents": [
                    {
                        "identifier": GO_SERVICE_IDENTIFIER,
                        "childExec": GO_SERVICE_IDENTIFIER,
                        "childArgs": [],
                        "relativePath": rel_posix(out_root, target),
                        "size": target.stat().st_size,
                        "sha256": sha256_of(target),
                        "elf": {
                            "class": 64 if elf_info["class"] == ELFCLASS64 else 32,
                            "data": elf_info["data_name"],
                            "type": elf_info["type_name"],
                            "machine": ELF_MACHINE_BY_ABI[abi][1],
                            "machineId": elf_info["machine"],
                        },
                        "sourceDigest": digest,
                        "goVersion": toolchain_text,
                        "reused": reused,
                    }
                ],
                "bundle": {
                    "relativePath": rel_posix(out_root, bundle_root),
                    "fileCount": len(bundle_files),
                    "size": bundle_bytes,
                    # agent/go-service 用 getcwd()/maafw 找 MaaFramework 的 .so，
                    # 用 cwd 相对路径找 locales；所以工作目录必须设成这个 bundle 根。
                    "workingDir": rel_posix(out_root, bundle_root),
                },
                "files": abi_files,
            }
            produced.extend(abi_files)
            print("")

        # 没被本次请求覆盖的 abi：能原样留着就留着（比如这次只编 arm64，上次的 x86_64 还在）。
        for abi, entry in previous_abis.items():
            if abi in manifest_abis:
                continue
            files = entry.get("files") or []
            if not files or any(not (out_root / rel).is_file() for rel in files):
                print(f"[{abi}] 旧产物不完整，从清单中移除。")
                continue
            manifest_abis[abi] = entry
            produced.extend(files)

        manifest = {
            "schema": SCHEMA_VERSION,
            "generated_by": GENERATED_BY,
            "generated_at": utc_now(),
            "source": {
                "path": rel_posix(root, source),
                "goServiceDir": rel_posix(root, go_service),
                "module": module_path,
                "goDirective": go_directive,
                "digest": digest,
                "fileCount": file_count,
                "size": total_bytes,
                "gitHead": git_head(source),
            },
            "toolchain": {
                "command": args.go,
                "go_version": toolchain_text,
                "goVersionNumber": toolchain_number,
            },
            "build": {
                "goos": "android",
                "cgoEnabled": "0",
                "tags": tags,
                "trimpath": True,
                "buildvcs": False,
                "ldflags": "-s -w",
                # GOOS=android 时 Go 默认 -buildmode=pie，故产物是 ET_DYN 而不是 ET_EXEC。
                "buildmode": "pie",
            },
            "abis": {abi: manifest_abis[abi] for abi in sorted(manifest_abis)},
            "files": sorted(produced),
        }

        path = write_manifest(out_root, manifest)
        print(f"清单已写出：{path}")
        for abi in sorted(manifest_abis):
            entry = manifest_abis[abi]
            agent = entry["agents"][0]
            print(
                f"  {abi:<10} {agent['relativePath']}  "
                f"{human_mb(agent['size'])}  sha256={agent['sha256'][:16]}…"
                f"  ({'复用' if agent.get('reused') else '新编'})"
            )
        return 0

    except BuildError as exc:
        print(f"::error::{exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
