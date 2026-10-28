#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MaaFramework Android 预编译库下载与部署脚本喵～

用法：
    python scripts/setup_maaframework.py                    # 下载最新 release 并部署
    python scripts/setup_maaframework.py --tag v2.0.0       # 下载指定 tag
    python scripts/setup_maaframework.py --skip-download     # 仅从缓存部署

它会从 MaaFramework GitHub Release 下载 MAA-android-aarch64.zip，
把 bin/*.so（兼容旧版 lib/*.so）解压到 app/src/main/jniLibs/arm64-v8a/，
把 include/ 解压到 app/src/main/cpp/include/ 供 JNI/JNA 绑定使用喵。
"""

import argparse
import html
import re
import shutil
import sys
import urllib.error
import urllib.request
import zipfile
from pathlib import Path
from urllib.parse import quote, unquote, urljoin, urlparse

DEFAULT_GITHUB_REPO = "MaaXYZ/MaaFramework"
RELEASE_LATEST_TEMPLATE = "https://github.com/{repo}/releases/latest"
RELEASE_TAG_TEMPLATE = "https://github.com/{repo}/releases/tag/{tag}"
EXPANDED_ASSETS_TEMPLATE = "https://github.com/{repo}/releases/expanded_assets/{tag}"
USER_AGENT = "MAAFW-Naruto-Setup"
DOWNLOAD_LINK_RE = re.compile(r'href="([^"]*releases/download/[^"]+)"')
TITLE_TAG_RE = re.compile(r"<title>Release\s+([^<\s]+)", re.IGNORECASE)

# release job 生成的 asset 名称关键字
ASSET_KEYWORD = "MAA-android-aarch64"

PROJECT_ROOT = Path(__file__).resolve().parent.parent
CACHE_DIR = PROJECT_ROOT / "cache"
JNI_DIR = PROJECT_ROOT / "app" / "src" / "main" / "jniLibs" / "arm64-v8a"
INCLUDE_DIR = PROJECT_ROOT / "app" / "src" / "main" / "cpp" / "include"
VERSION_FILE = PROJECT_ROOT / ".maaframework-version"

def parse_release_html(html_text: str, page_url: str):
    release_tag = None
    tag_match = re.search(r"/releases/tag/([^/?#]+)", page_url)
    if tag_match:
        release_tag = unquote(tag_match.group(1))
    if not release_tag:
        title_match = TITLE_TAG_RE.search(html_text)
        if title_match:
            release_tag = unquote(title_match.group(1))
    if not release_tag:
        print("[ERROR] 无法从 release HTML 解析版本号。", file=sys.stderr)
        sys.exit(1)

    assets = []
    for href in DOWNLOAD_LINK_RE.findall(html_text):
        asset_url = urljoin(page_url, html.unescape(href))
        asset_path = urlparse(asset_url).path
        if not asset_path.endswith(".zip"):
            continue
        asset_name = Path(unquote(asset_path)).name
        if not asset_name:
            continue
        asset = {
            "name": asset_name,
            "browser_download_url": asset_url,
        }
        if asset not in assets:
            assets.append(asset)

    return release_tag, assets


def fetch_html(url: str) -> tuple[str, str]:
    req = urllib.request.Request(
        url,
        headers={
            "Accept": "text/html,application/xhtml+xml",
            "User-Agent": USER_AGENT,
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.read().decode("utf-8", "replace"), resp.geturl()
    except urllib.error.HTTPError as e:
        print(
            f"[ERROR] GitHub release 页面请求失败: HTTP {e.code} {e.reason}",
            file=sys.stderr,
        )
        sys.exit(1)
    except urllib.error.URLError as e:
        print(f"[ERROR] 无法连接 GitHub release 页面: {e.reason}", file=sys.stderr)
        sys.exit(1)


def download_file(url: str, dest: Path):
    print(f"  [DOWNLOAD] {dest.name}")
    req = urllib.request.Request(url)
    req.add_header("Accept", "application/octet-stream")
    req.add_header("User-Agent", "MAAFW-Naruto-Setup")
    temp_dest = dest.with_name(dest.name + ".part")
    if temp_dest.exists():
        temp_dest.unlink()
    try:
        with urllib.request.urlopen(req, timeout=600) as resp:
            total = int(resp.headers.get("Content-Length", 0))
            downloaded = 0
            with open(temp_dest, "wb") as f:
                while True:
                    chunk = resp.read(1024 * 1024)
                    if not chunk:
                        break
                    f.write(chunk)
                    downloaded += len(chunk)
                    if total > 0:
                        pct = downloaded * 100 // total
                        mb = downloaded / (1024 * 1024)
                        total_mb = total / (1024 * 1024)
                        print(
                            f"\r    {mb:.1f}/{total_mb:.1f} MB ({pct}%)",
                            end="",
                            flush=True,
                        )
            print()
        temp_dest.replace(dest)
    finally:
        if temp_dest.exists():
            temp_dest.unlink()


def get_release_assets(repo: str, tag: str = None):
    if tag:
        url = RELEASE_TAG_TEMPLATE.format(repo=repo, tag=quote(tag, safe=""))
    else:
        url = RELEASE_LATEST_TEMPLATE.format(repo=repo)
    print(f"[FETCH] 获取 release 信息: {url}")
    html_text, final_url = fetch_html(url)

    tag_name, assets = parse_release_html(html_text, final_url)
    if not assets:
        assets_url = EXPANDED_ASSETS_TEMPLATE.format(
            repo=repo, tag=quote(tag_name, safe="")
        )
        assets_html, _ = fetch_html(assets_url)
        _, assets = parse_release_html(assets_html, final_url)
    if not assets:
        print("[ERROR] release 里没有找到 zip 资产。", file=sys.stderr)
        sys.exit(1)
    print(f"  Tag: {tag_name}")
    return tag_name, assets


def find_android_asset(assets: list):
    for asset in assets:
        name = asset.get("name", "")
        if ASSET_KEYWORD in name and name.endswith(".zip"):
            return asset
    return None


def clean_deploy_dirs():
    if JNI_DIR.exists():
        # 清理上一版预编译库，避免上游删除/更名的依赖残留在 APK 中。
        for f in JNI_DIR.iterdir():
            if f.suffix == ".so":
                f.unlink()
                print(f"    [DELETE] {f.name}")
    else:
        JNI_DIR.mkdir(parents=True, exist_ok=True)

    if INCLUDE_DIR.exists():
        shutil.rmtree(INCLUDE_DIR)
    INCLUDE_DIR.mkdir(parents=True, exist_ok=True)


def is_shared_library_member(member: str) -> bool:
    """识别 Release 中的直属预编译库。

    当前版本使用 bin/*.so，旧版可能使用 lib/*.so。只接受目录直属
    文件，避免把 bin/plugins/*.so 等非运行时库误打包进 APK。
    """
    parts = Path(member).parts
    return (
        len(parts) >= 2
        and parts[-2] in {"bin", "lib"}
        and parts[-1].endswith(".so")
    )


def extract_and_deploy(zip_path: Path, version: str):
    print(f"  [EXTRACT] {zip_path.name}")
    with zipfile.ZipFile(zip_path, "r") as zf:
        members = zf.namelist()

        so_members = [m for m in members if is_shared_library_member(m)]
        include_members = [
            m
            for m in members
            if "include" in Path(m).parts and not m.endswith("/")
        ]
        if not so_members or not include_members:
            print(
                "[ERROR] 压缩包中缺少 bin/*.so（或 lib/*.so）或 include/*，"
                "未修改现有部署。",
                file=sys.stderr,
            )
            sys.exit(1)
        bad_member = zf.testzip()
        if bad_member:
            print(f"[ERROR] 压缩包校验失败: {bad_member}", file=sys.stderr)
            sys.exit(1)

        clean_deploy_dirs()

        # 统计
        so_count = 0
        inc_count = 0

        for member in members:
            # 跳过目录
            if member.endswith("/"):
                continue
            parts = Path(member).parts

            # bin/*.so（兼容旧版 lib/*.so）-> jniLibs
            if is_shared_library_member(member):
                name = Path(member).name
                dest = JNI_DIR / name
                with zf.open(member) as src:
                    dest.write_bytes(src.read())
                so_count += 1
                continue

            # include/* -> cpp/include
            if "include" in parts:
                inc_idx = list(parts).index("include")
                rel_parts = parts[inc_idx + 1 :]
                if not rel_parts:
                    continue
                dest = INCLUDE_DIR / Path(*rel_parts)
                dest.parent.mkdir(parents=True, exist_ok=True)
                with zf.open(member) as src:
                    dest.write_bytes(src.read())
                inc_count += 1
                continue

            # 其他文件（resource、sample、docs 等）暂时不需要，跳过

    VERSION_FILE.write_text(version + "\n", encoding="utf-8")
    print(f"  [VERSION] 写入 {VERSION_FILE.name}: {version}")
    print(f"\n  so 文件: {so_count} 个, include 文件: {inc_count} 个")


def main():
    parser = argparse.ArgumentParser(
        description="下载并部署 MaaFramework Android 预编译库"
    )
    parser.add_argument(
        "--repo", "-r", default=DEFAULT_GITHUB_REPO, help="GitHub 仓库 (owner/repo)"
    )
    parser.add_argument("--tag", "-t", help="指定 release tag（默认 latest）")
    parser.add_argument(
        "--skip-download", "-s", action="store_true", help="跳过下载，仅用缓存"
    )
    args = parser.parse_args()

    CACHE_DIR.mkdir(parents=True, exist_ok=True)

    print("=" * 60)
    print("==> MaaFramework Android 预编译库下载部署")
    print("=" * 60)

    version = None
    zip_path = None

    if not args.skip_download:
        version, assets = get_release_assets(args.repo, args.tag)
        asset = find_android_asset(assets)
        if not asset:
            print(f"[ERROR] 没找到 Android aarch64 构建产物，关键词：{ASSET_KEYWORD}")
            print("[HINT] MaaFramework 的 release asset 名称类似：")
            print("       MAA-android-aarch64-v2.0.0.zip")
            sys.exit(1)

        print(
            f"\n[INFO] 找到产物: {asset['name']} ({asset.get('size', 0) / 1024 / 1024:.1f} MB)"
        )
        zip_path = CACHE_DIR / asset["name"]
        if zip_path.exists():
            print("  [CACHE] 已存在，跳过下载")
        else:
            download_file(asset["browser_download_url"], zip_path)
    else:
        print("[SKIP] 跳过下载，使用缓存")
        zips = [
            path
            for path in CACHE_DIR.glob("*.zip")
            if ASSET_KEYWORD.lower() in path.name.lower()
        ]
        if args.tag:
            zips = [path for path in zips if path.name.endswith(f"-{args.tag}.zip")]
        if not zips:
            detail = f"版本 {args.tag} 的 " if args.tag else ""
            print(f"[ERROR] 缓存里没有{detail}zip，先跑一次下载")
            sys.exit(1)
        zip_path = max(zips, key=lambda path: path.stat().st_mtime)
        name_match = re.search(
            r"-(v?\d+(?:\.\d+)+(?:[-.][0-9A-Za-z.]+)?)\.zip$", zip_path.name
        )
        version = args.tag or (name_match.group(1) if name_match else "unknown")
        print(f"  [CACHE] 选择: {zip_path.name}")

    print("\n[DEPLOY] 部署到项目...")
    extract_and_deploy(zip_path, version)

    print("\n" + "=" * 60)
    print("[DONE] 部署完成喵～")
    print("=" * 60)
    print(f"  jniLibs: {JNI_DIR}")
    print(f"  include: {INCLUDE_DIR}")
    if JNI_DIR.exists():
        so_files = sorted(f.name for f in JNI_DIR.iterdir() if f.suffix == ".so")
        print(f"  so 列表: {', '.join(so_files)}")


if __name__ == "__main__":
    main()
