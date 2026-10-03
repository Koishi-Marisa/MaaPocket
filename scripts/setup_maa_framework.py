#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 GitHub Release 取 MaaFramework 的 Android 产物，铺成 MaaPocket 需要的样子。

它做三件事：
  1. 按 ABI 下载 `MAA-android-<arch>-<tag>.zip`（或直接用 --zip 指定的本地包），
     缓存到 `<root>/.maafw/cache/`，并校验 zip 完整性 + 记录 sha256。
  2. 把压缩包 `bin/` 下**白名单内**的 `.so` 平铺进 `<dest>/<abi>/`（默认
     `core/src/main/jniLibs/<abi>/`），其余一律不落盘（排除原因见下方 EXCLUDED_SO 的注释）。
  3. 把 `include/**` 与两张 schema 展开进 `--include-dir`（默认 `.maafw/<tag>/include/`），
     供 agent 构建（`-I`）与 PI 包构建脚本（`--schemas`）使用。

另写 `<root>/.maafw/version.json`：记录 tag、每个 ABI 的 zip sha256、以及本次展开出的完整文件清单。
重跑是幂等的：内容没变就跳过展开，上一个 manifest 里记录过、这次不再产出的文件会被清掉。

用法：
    python scripts/setup_maa_framework.py                        # tag/abi 都读 gradle.properties
    python scripts/setup_maa_framework.py --tag v5.14.2 --abi arm64-v8a,x86_64
    python scripts/setup_maa_framework.py --zip /path/MAA-android-aarch64-v5.14.2.zip --abi arm64-v8a

纯标准库，Python 3.9+。
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import re
import shutil
import sys
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path
from typing import Dict, List, Optional, Tuple

# Windows 控制台下 python 默认用 ANSI 代码页，路径里的中文会 print 崩；显式改 UTF-8。
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
        except (AttributeError, ValueError):  # pragma: no cover - 极少见
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

GITHUB_REPO = "MaaXYZ/MaaFramework"
RELEASE_URL = "https://github.com/{repo}/releases/download/{tag}/{name}"
USER_AGENT = "MaaPocket-SetupMaaFramework/1"

# jniLibs 子目录 <- release 产物名里的 arch 关键字
ABI_TO_ARCH = {
    "arm64-v8a": "aarch64",
    "x86_64": "x86_64",
}

# 需要落进 <dest>/<abi>/ 的 .so —— **白名单**，只有这几个名字会被拷贝。
#
# 为什么是白名单而不是黑名单：release 里混着桌面用的东西（见下面 EXCLUDED_SO），
# 黑名单一旦漏掉一个新加的桌面库，就会把几十 MB 无用的 .so 打进 APK。
KEEP_SO = [
    "libMaaFramework.so",              # 核心：pipeline / 识别 / 任务调度
    "libMaaUtils.so",                  # 核心依赖：字符串/图像缓冲等工具
    "libMaaAndroidNativeControlUnit.so",  # Android 原生控制器（截图 + 注入），on-device 必需
    "libMaaCustomControlUnit.so",      # 自定义控制器（宿主 App 用 JNI 回调实现）
    "libMaaAdbControlUnit.so",         # ADB 控制器（真机/云游戏走 adb 时要用）
    "libMaaRecordControlUnit.so",      # 录制控制器：把一次运行录下来做回放调试
    "libMaaReplayControlUnit.so",      # 回放控制器：离线复跑录制，CI/回归很有用
    "libMaaAgentClient.so",            # agent-client：宿主进程侧
    "libMaaAgentServer.so",            # agent-server：agent 子进程侧（go-service 需要）
    # 注意：**不能**丢 libMaaToolkit.so。桌面上它确实是「枚举 adb 设备 / 遍历窗口 / 读写 GUI 配置」
    # 的工具箱，Android 用不到那些功能；但 maa-framework-go（MaaEnd 的 go-service agent 用的
    # Go binding）在 `internal/native/native.go` 的 `Initialize()` 里**无条件**依次 open
    # libMaaFramework / libMaaToolkit / libMaaAgentServer / libMaaAgentClient 四个库并对每个
    # 导出符号做 resolve 预检，少一个就直接 `LibraryLoadError` 退出。
    # 实测消耗：上游 v5.14.2 Android zip 里 libMaaToolkit.so 是 5,228,776 B（约 5 MB），代价可接受。
    "libMaaToolkit.so",                # maa-framework-go 的 Initialize() 硬依赖
    "libonnxruntime.so",               # NeuralNetworkDetect / 部分 OCR 后端
    "libopencv_world4.so",             # 模板匹配、颜色匹配、图像处理
    # 注意：**也不能丢 libfastdeploy_ppocr.so**。它的名字听起来像纯桌面件（FastDeploy 的 PPOCR
    # 后端），但 `libMaaFramework.so` 的动态段里它是**硬 DT_NEEDED**：
    #   NEEDED = [libfastdeploy_ppocr.so, libonnxruntime.so, libMaaUtils.so, libopencv_world4.so, ...]
    # 而 `libMaaFramework.so` 自己 RPATH/RUNPATH 都是空，Android linker 对 DT_NEEDED 是**急加载**，
    # 少一个就直接 `dlopen failed: library "libfastdeploy_ppocr.so" not found`，
    # 并且报的是**被依赖方**的名字，很容易误判成「框架缺件」。
    # 实测（`_research/elf_deps.py` 解 CI 产出 APK）确认上游没有把它做成可选 dlopen。
    # 代价：上游 v5.14.2 Android zip 里 22,986,073 B（约 23 MB），可接受。
    "libfastdeploy_ppocr.so",          # libMaaFramework.so 的硬 DT_NEEDED，不是桌面专属
]

# 明确排除的条目 -> 原因。**每一个都写清楚，避免以后有人"顺手都拷过去"。**
EXCLUDED_SO = {
    "libc++_shared.so":
        "NDK 自带的 libc++_shared —— core/build.gradle.kts 用 -DANDROID_STL=c++_shared 让 CMake "
        "产出/链接我们自己那份；再塞一份上游 NDK 编的进 jniLibs，等于同一进程里混两套 libc++。",
    "libfastdeploy_ppocr.so.bak":
        "已废弃条目占位：原先把 libfastdeploy_ppocr.so 排在这里，理由是「Android 上 OCR 走 "
        "libonnxruntime.so + 内建模型，这个是纯桌面产物」。该判断是错的 —— 它是 "
        "libMaaFramework.so 的硬 DT_NEEDED（见 KEEP_SO 里的长注释），删掉整个 native 侧都起不来。",
    "libMaaCustomControlUnit.so.bak":
        "已废弃条目占位：原先把 libMaaToolkit.so 排在这里，理由是「桌面工具箱 Android 用不到」。"
        "该判断是错的 —— maa-framework-go 的 Initialize() 无条件加载它，见 KEEP_SO 里的长注释。",
}

# 路径级的排除（不是单个文件）：
EXCLUDED_PATHS = {
    "bin/plugins":
        "bin/plugins/** 是 MaaPluginDemo 示例插件（libMaaPluginDemo.so），不是运行时依赖。",
    "bin/MaaPiCli":
        "桌面命令行宿主（约 14 MB 的独立 ELF 可执行文件）。Android 上宿主是 APK 自己，"
        "不会被 exec，带进 APK 只会在 nativeLibraryDir 里多一份死重量。",
}

MAAF_DIR = ".maafw"
CACHE_SUBDIR = "cache"
MANIFEST_NAME = "version.json"
DEFAULT_DEST = "core/src/main/jniLibs"
DEFAULT_INCLUDE_DIR_TMPL = MAAF_DIR + "/{tag}/include"

# 少了任何一个 native 侧都起不来，展开完硬校验
REQUIRED_SO = [
    "libMaaFramework.so",
    "libMaaUtils.so",
    "libMaaAndroidNativeControlUnit.so",
    # libMaaFramework.so 的硬 DT_NEEDED，缺了 dlopen 直接失败（见 KEEP_SO 注释）
    "libfastdeploy_ppocr.so",
]

SCHEMA_MEMBERS = [
    "tools/pipeline.schema.json",
    "tools/interface.schema.json",
]


# --------------------------------------------------------------------------------------
# 小工具
# --------------------------------------------------------------------------------------

def project_root() -> Path:
    """MaaPocket/ 根目录（scripts/ 的上一级）。"""
    return Path(__file__).resolve().parent.parent


def read_gradle_property(root: Path, key: str, default: Optional[str] = None) -> Optional[str]:
    """只认 `key=value` 形式的 gradle.properties 行（# 或 ! 开头是注释）。"""
    props = root / "gradle.properties"
    if not props.is_file():
        return default
    for raw in props.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or line.startswith("!"):
            continue
        if "=" not in line:
            continue
        k, v = line.split("=", 1)
        if k.strip() == key:
            return v.strip()
    return default


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def rel_posix(root: Path, path: Path) -> str:
    """相对项目根的 posix 路径；落在根之外（例如临时 --dest）时退化为绝对路径。"""
    resolved = path.resolve()
    try:
        return resolved.relative_to(root.resolve()).as_posix()
    except ValueError:
        return resolved.as_posix()


def mb(n: int) -> str:
    return "%.1f MB" % (n / (1024.0 * 1024.0))


# --------------------------------------------------------------------------------------
# 解析 / 下载 / 校验
# --------------------------------------------------------------------------------------

def resolve_abis(raw_values: List[str], root: Path) -> List[str]:
    """--abi 可以给多次、也可以逗号分隔；不给就读 gradle.properties 的 maapocket.abis。"""
    raw: List[str] = []
    for value in raw_values:
        raw.extend(part.strip() for part in value.split(","))
    if not raw:
        fallback = read_gradle_property(root, "maapocket.abis", "arm64-v8a") or "arm64-v8a"
        raw = [part.strip() for part in fallback.split(",")]
    out: List[str] = []
    for abi in raw:
        if not abi:
            continue
        if abi == "all":
            for known in ABI_TO_ARCH:
                if known not in out:
                    out.append(known)
            continue
        if abi not in ABI_TO_ARCH:
            raise SystemExit(
                "[FATAL] 不认识的 ABI: %r（支持: %s）"
                % (abi, ", ".join(sorted(ABI_TO_ARCH)))
            )
        if abi not in out:
            out.append(abi)
    if not out:
        raise SystemExit("[FATAL] 没有解析出任何 ABI")
    return out


def zip_name_for(tag: str, abi: str) -> str:
    return "MAA-android-%s-%s.zip" % (ABI_TO_ARCH[abi], tag)


def abi_from_zip_name(name: str) -> Optional[str]:
    """MAA-android-aarch64-v5.14.2.zip -> arm64-v8a"""
    for abi, arch in ABI_TO_ARCH.items():
        if "android-%s" % arch in name:
            return abi
    return None


def verify_zip(path: Path) -> None:
    """完整性校验：能打开 + 每个成员的 CRC 都对得上。坏包直接抛，绝不半信半疑地用。"""
    if not path.is_file():
        raise SystemExit("[FATAL] zip 不存在: %s" % path)
    size = path.stat().st_size
    if size == 0:
        raise SystemExit("[FATAL] zip 是空文件: %s" % path)
    try:
        with zipfile.ZipFile(path) as zf:
            bad = zf.testzip()
    except zipfile.BadZipFile as exc:
        raise SystemExit("[FATAL] 不是合法 zip: %s (%s)" % (path, exc))
    if bad is not None:
        raise SystemExit("[FATAL] zip 内容损坏，首个坏成员是 %s: %s" % (bad, path))
    print("  [verify] %s  CRC OK  (%s)" % (path.name, mb(size)))


def download(url: str, dest: Path, attempts: int = 3) -> None:
    """下载到 dest（先写 .part 再改名，避免中断留下半截包被当成缓存命中）。

    urllib 默认装配了 HTTPRedirectHandler，GitHub Release 到 objects.githubusercontent.com
    的 302 会自动跟随，这里不需要额外处理。
    """
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_name(dest.name + ".part")
    last_error: Optional[BaseException] = None
    for attempt in range(1, attempts + 1):
        try:
            print("  [download] %s" % url)
            req = urllib.request.Request(
                url, headers={"User-Agent": USER_AGENT, "Accept": "application/octet-stream"}
            )
            with urllib.request.urlopen(req, timeout=180) as resp, open(tmp, "wb") as out:
                total = int(resp.headers.get("Content-Length") or 0)
                done = 0
                last_tick = 0.0
                while True:
                    chunk = resp.read(1 << 20)
                    if not chunk:
                        break
                    out.write(chunk)
                    done += len(chunk)
                    now = time.time()
                    if now - last_tick >= 0.5:
                        last_tick = now
                        if total > 0:
                            print(
                                "\r    %s / %s (%d%%)"
                                % (mb(done), mb(total), done * 100 // total),
                                end="",
                                flush=True,
                            )
                        else:
                            print("\r    %s" % mb(done), end="", flush=True)
                print()
            tmp.replace(dest)
            return
        except (urllib.error.URLError, urllib.error.HTTPError, OSError, TimeoutError) as exc:
            last_error = exc
            if tmp.exists():
                tmp.unlink()
            print("\n  [warn] 下载失败（第 %d/%d 次）: %s" % (attempt, attempts, exc))
            if attempt < attempts:
                time.sleep(2 * attempt)
    raise SystemExit(
        "[FATAL] 下载失败（已重试 %d 次）\n"
        "        尝试过的 URL: %s\n"
        "        最后错误    : %s\n"
        "        排查提示    : tag 是否写错（--tag）、该 ABI 是否确实有产物、"
        "能否直连 github.com；可用 --zip <本地 zip> 绕过下载。" % (attempts, url, last_error)
    )


# --------------------------------------------------------------------------------------
# 展开
# --------------------------------------------------------------------------------------

def bin_so_members(zf: zipfile.ZipFile) -> Dict[str, zipfile.ZipInfo]:
    """只取 `bin/` 这一层（不含 bin/plugins/ 这类子目录）的文件成员，按文件名索引。"""
    out: Dict[str, zipfile.ZipInfo] = {}
    for info in zf.infolist():
        if info.is_dir():
            continue
        parts = info.filename.split("/")
        if len(parts) == 2 and parts[0] == "bin":
            out[parts[1]] = info
    return out


def extract_jnilibs(zip_path: Path, abi: str, dest_dir: Path) -> List[str]:
    """把白名单里的 .so 平铺进 dest_dir，返回实际写出的文件名（按 KEEP_SO 顺序）。"""
    dest_dir.mkdir(parents=True, exist_ok=True)
    written: List[str] = []
    missing: List[str] = []
    with zipfile.ZipFile(zip_path) as zf:
        members = bin_so_members(zf)
        for name in KEEP_SO:
            info = members.get(name)
            if info is None:
                missing.append(name)
                continue
            target = dest_dir / name
            with zf.open(info) as src, open(target, "wb") as out:
                shutil.copyfileobj(src, out)
            written.append(name)
    if missing:
        print("  [warn] 该 release 的 bin/ 里没有这些白名单库: %s" % ", ".join(missing))
    # 这些是"没有它 native 侧起不来"的，缺了就一定是我们铺错了，直接失败而不是继续跑
    for required in REQUIRED_SO:
        if required not in written:
            raise SystemExit(
                "[FATAL] 缺少必需库 %s（来自 %s）；确认该 tag 是否是 Android 产物"
                % (required, zip_path.name)
            )
    return written


def extract_includes(zip_path: Path, include_dir: Path) -> List[str]:
    """展开头文件与 schema。

    落盘布局（刻意剥掉压缩包里的 `include/` 前缀，让 --include-dir 本身就是一个可用的 -I 根）：
        <include-dir>/MaaFramework/MaaAPI.h ...   <- 压缩包 include/** 去掉前缀
        <include-dir>/schemas/pipeline.schema.json
        <include-dir>/schemas/interface.schema.json
    """
    written: List[str] = []
    include_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path) as zf:
        for info in zf.infolist():
            if info.is_dir():
                continue
            name = info.filename
            if name.startswith("include/"):
                rel = name[len("include/"):]
            elif name in SCHEMA_MEMBERS:
                rel = "schemas/" + name.rsplit("/", 1)[-1]
            else:
                continue
            if not rel:
                continue
            target = include_dir / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            with zf.open(info) as src, open(target, "wb") as out:
                shutil.copyfileobj(src, out)
            written.append(rel)
    if not written:
        raise SystemExit("[FATAL] %s 里没有 include/** 也没有 schema" % zip_path.name)
    return sorted(written)


def remove_stale(managed_dir: Path, keep_names: List[str], what: str) -> List[str]:
    """删掉上一次 manifest 记录过、这一次不再产出的文件。只碰我们记过的名字。"""
    keep = set(keep_names)
    removed: List[str] = []
    if not managed_dir.is_dir():
        return removed
    for entry in managed_dir.iterdir():
        if entry.is_file() and entry.name not in keep:
            removed.append(entry.name)
            entry.unlink()
    if removed:
        print("  [stale] %s: 清掉 %d 个上次遗留的文件" % (what, len(removed)))
    return sorted(removed)


# --------------------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="下载/展开 MaaFramework 的 Android 产物到 jniLibs 与 include 目录",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--tag", default=None,
                        help="MaaFramework release tag，默认读 gradle.properties 的 maafw.version")
    parser.add_argument("--abi", action="append", default=[], metavar="ABI",
                        help="要处理的 ABI，可逗号分隔或重复；默认读 gradle.properties 的 maapocket.abis")
    parser.add_argument("--dest", default=DEFAULT_DEST,
                        help="jniLibs 根目录（相对项目根），.so 会落在 <dest>/<abi>/，默认 " + DEFAULT_DEST)
    parser.add_argument("--include-dir", default=None,
                        help="头文件/schema 落盘目录，默认 " + DEFAULT_INCLUDE_DIR_TMPL)
    parser.add_argument("--zip", action="append", default=[], metavar="PATH",
                        help="用本地 zip 代替下载；可给多次，每个 ABI 一个（按文件名里的 arch 归属）")
    parser.add_argument("--force", action="store_true",
                        help="忽略缓存与「内容未变则跳过」判断，全部重新下载并展开")
    parser.add_argument("--skip-jnilibs", action="store_true",
                        help="只要头文件/schema，不往 --dest 铺 .so"
                             "（prepare-packs 用：迁移只吃 schema，没必要写 200 MB 的库）")
    return parser


def main(argv: Optional[List[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    root = project_root()

    tag = args.tag or read_gradle_property(root, "maafw.version", "v5.14.2") or "v5.14.2"
    abis = resolve_abis(args.abi, root)

    dest_root = Path(args.dest)
    if not dest_root.is_absolute():
        dest_root = root / dest_root
    include_arg = args.include_dir or DEFAULT_INCLUDE_DIR_TMPL.format(tag=tag)
    include_dir = Path(include_arg)
    if not include_dir.is_absolute():
        include_dir = root / include_dir

    maaf_dir = root / MAAF_DIR
    cache_dir = maaf_dir / CACHE_SUBDIR
    manifest_path = maaf_dir / MANIFEST_NAME

    print("=" * 72)
    print("MaaPocket :: setup MaaFramework")
    print("=" * 72)
    print("  root        : %s" % root)
    print("  tag         : %s" % tag)
    print("  abis        : %s" % ", ".join(abis))
    print("  dest        : %s" % dest_root)
    print("  include-dir : %s" % include_dir)
    print("  force       : %s" % args.force)
    print("  skip-jnilibs: %s" % args.skip_jnilibs)

    previous: Dict[str, object] = {}
    if manifest_path.is_file():
        try:
            previous = json.loads(manifest_path.read_text(encoding="utf-8"))
        except (ValueError, OSError) as exc:
            print("  [warn] 旧 manifest 读不出来（%s），本次忽略" % exc)
            previous = {}

    # ---- 1. 决定每个 ABI 用哪个 zip -------------------------------------------------
    print("\n[1/4] 准备 release 包")
    zip_sources: Dict[str, Tuple[Path, str]] = {}  # abi -> (path, "local"|"cache")
    if args.zip:
        for raw in args.zip:
            path = Path(raw)
            if not path.is_absolute():
                path = (root / path).resolve()
            abi = abi_from_zip_name(path.name)
            if abi is None and len(abis) == 1:
                # 文件名不含 arch 关键字时，只有一个目标 ABI 就默认归它
                abi = abis[0]
            if abi is None:
                raise SystemExit(
                    "[FATAL] 无法判断 --zip %s 属于哪个 ABI（文件名里应有 android-aarch64 / "
                    "android-x86_64）。请一次给一个 ABI。\n  已知目标 ABI: %s"
                    % (path, ", ".join(abis))
                )
            if abi not in abis:
                print("  [skip] %s 属于 %s，但本次不处理该 ABI" % (path.name, abi))
                continue
            if abi in zip_sources:
                raise SystemExit("[FATAL] ABI %s 给了不止一个 --zip" % abi)
            zip_sources[abi] = (path, "local")

    for abi in abis:
        if abi in zip_sources:
            verify_zip(zip_sources[abi][0])
            print("  %-10s <- 本地 %s" % (abi, zip_sources[abi][0]))
            continue
        name = zip_name_for(tag, abi)
        cached = cache_dir / name
        if args.force:
            print("  [force] 忽略缓存，重下 %s" % name)
        elif cached.is_file() and cached.stat().st_size > 0:
            print("  %-10s <- 缓存 %s" % (abi, cached))
        else:
            url = RELEASE_URL.format(repo=GITHUB_REPO, tag=tag, name=name)
            download(url, cached)
        verify_zip(cached)
        zip_sources[abi] = (cached, "cache")

    missing_abis = [a for a in abis if a not in zip_sources]
    if missing_abis:
        tried = "\n".join(
            "        %-10s %s"
            % (a, RELEASE_URL.format(repo=GITHUB_REPO, tag=tag, name=zip_name_for(tag, a)))
            for a in missing_abis
        )
        raise SystemExit(
            "[FATAL] 这些 ABI 没拿到 zip:\n%s\n"
            "        上面的 URL 就是本脚本会去下（并缓存到 .maafw/cache/）的地址；"
            "用 --zip <本地 zip>（每个 ABI 一个）可以绕过下载。" % tried
        )

    # ---- 2. 展开 jniLibs -------------------------------------------------------------
    jni_record: Dict[str, Dict[str, object]] = {}
    output_files: List[str] = []
    all_skipped: List[str] = []
    if args.skip_jnilibs:
        # 只要头文件 / schema（迁移脚本用），不往 --dest 铺 .so。
        # 但要把上一份 manifest 的 jniLibs 记录原样带过去：那份记录是下一次
        # 真正构建时做「清理过期 .so」的唯一依据，抹掉它会让陈旧文件永远留在树里。
        print("\n[2/4] 展开 .so -> jniLibs（--skip-jnilibs：跳过）")
        prev_jni = previous.get("jniLibs")
        if isinstance(prev_jni, dict):
            jni_record = prev_jni  # type: ignore[assignment]
            inherited = sum(len(v.get("files", [])) for v in prev_jni.values() if isinstance(v, dict))
            print("  保留上次 manifest 里的 jniLibs 记录（%d 个 .so，未触碰磁盘）" % inherited)
        else:
            print("  上次 manifest 里没有 jniLibs 记录，本次留空")
    else:
        print("\n[2/4] 展开 .so -> jniLibs")
        for abi in abis:
            zip_path, source_kind = zip_sources[abi]
            digest = sha256_of(zip_path)
            dest_abi = dest_root / abi
            prev = previous.get("jniLibs", {}).get(abi) if isinstance(previous.get("jniLibs"), dict) else None
            prev_zip = previous.get("zips", {}).get(abi) if isinstance(previous.get("zips"), dict) else None
            unchanged = (
                not args.force
                and isinstance(prev, dict)
                and isinstance(prev_zip, dict)
                and prev_zip.get("sha256") == digest
                and prev.get("dir") == rel_posix(root, dest_abi)
                and all((dest_abi / str(n)).is_file() for n in prev.get("files", []))
            )
            if unchanged:
                print("  %-10s 已是最新（sha256 未变），跳过展开" % abi)
                written = [str(n) for n in prev.get("files", [])]
            else:
                written = extract_jnilibs(zip_path, abi, dest_abi)
                remove_stale(dest_abi, written, "jniLibs/" + abi)
                print("  %-10s 展开 %d 个 .so -> %s" % (abi, len(written), rel_posix(root, dest_abi)))
            jni_record[abi] = {"dir": rel_posix(root, dest_abi), "files": written}
            for name in written:
                output_files.append(rel_posix(root, dest_abi / name))
            foreign = sorted(
                f.name for f in dest_abi.iterdir() if f.is_file() and f.name not in set(KEEP_SO)
            ) if dest_abi.is_dir() else []
            if foreign:
                all_skipped.extend("%s/%s" % (abi, n) for n in foreign)

    # ---- 3. 展开头文件 / schema ------------------------------------------------------
    print("\n[3/4] 展开 include/ 与 schema")
    first_abi = abis[0]
    first_zip, _ = zip_sources[first_abi]
    prev_inc = previous.get("include_files") if isinstance(previous.get("include_files"), list) else []
    include_written = extract_includes(first_zip, include_dir)
    # include 目录是分层的，remove_stale 那套"平铺文件名"不适用，这里按 relpath 清理上次多出来的
    stale_include = []
    for old in prev_inc:
        old_path = include_dir / str(old)
        if str(old) not in include_written and old_path.is_file():
            old_path.unlink()
            stale_include.append(str(old))
    if stale_include:
        print("  [stale] include/: 清掉 %d 个上次遗留的文件" % len(stale_include))
    print("  %-10s %d 个头文件 + %d 张 schema -> %s"
          % ("include", len([f for f in include_written if not f.startswith("schemas/")]),
             len([f for f in include_written if f.startswith("schemas/")]),
             rel_posix(root, include_dir)))
    for rel in include_written:
        output_files.append(rel_posix(root, include_dir / rel))

    # ---- 4. 写 manifest --------------------------------------------------------------
    print("\n[4/4] 写 manifest")
    manifest = {
        "schema": 1,
        "generated_by": "scripts/setup_maa_framework.py",
        "generated_at": datetime.datetime.now(datetime.timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z"),
        "tag": tag,
        "abis": abis,
        "zips": {
            abi: {
                "name": zip_sources[abi][0].name,
                "sha256": sha256_of(zip_sources[abi][0]),
                "size": zip_sources[abi][0].stat().st_size,
                "source": zip_sources[abi][1],
                "origin": str(zip_sources[abi][0]),
            }
            for abi in abis
        },
        "jniLibs": jni_record,
        "include_dir": rel_posix(root, include_dir),
        "include_files": include_written,
        "files": sorted(output_files),
    }
    maaf_dir.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print("  %s (tag=%s, %d 个文件)" % (rel_posix(root, manifest_path), tag, len(output_files)))

    # ---- 汇总 ------------------------------------------------------------------------
    print("\n" + "=" * 72)
    print("完成")
    print("=" * 72)
    total = 0
    for abi in abis:
        dest_abi = dest_root / abi
        if not dest_abi.is_dir():
            print("  %s/: （--skip-jnilibs，未铺 .so）" % abi)
            continue
        files = sorted(dest_abi.glob("*.so"))
        subtotal = sum(f.stat().st_size for f in files)
        total += subtotal
        print("  %s/: %d 个 .so, %s" % (abi, len(files), mb(subtotal)))
        for f in files:
            print("      %-40s %10d" % (f.name, f.stat().st_size))
    if not args.skip_jnilibs:
        print("  合计: %s" % mb(total))
    if all_skipped:
        print("\n  [note] 以下文件不是本脚本管理的（未动它们）: %s" % ", ".join(sorted(all_skipped)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
