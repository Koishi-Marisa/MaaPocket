#!/usr/bin/env python3
"""
下载 narutomobile 最新稳定 release，并按当前 Android assets 模式部署。

用法：
    python scripts/update_narutomobile_assets.py
    python scripts/update_narutomobile_assets.py --tag v1.2.3
    python scripts/update_narutomobile_assets.py --local-zip cache/MaaAutoNaruto.zip

默认行为：
1. 解析 https://github.com/duorua/narutomobile/releases/latest 的 HTML，
   不使用 GitHub API；latest 页面本身即最新稳定版。
2. 直接选择名称含 win/windows + x64/x86_64 的 zip，里面是通用资源。
3. 下载到项目根目录 cache/。
4. 解出 interface.json 和 resource/，覆盖部署到 app/src/main/assets/。
5. 调用 scripts/convert_multiswipe.py 把 pipeline 中的 MultiSwipe 转成
   Custom + MultiSwipeCustom；如不需要可用 --keep-multiswipe 跳过。
"""

import argparse
import html
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.error
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath
from urllib.parse import quote, unquote, urljoin, urlparse

GITHUB_REPO = "duorua/narutomobile"
PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_CACHE_DIR = PROJECT_ROOT / "cache"
DEFAULT_ASSETS_DIR = PROJECT_ROOT / "app" / "src" / "main" / "assets"
CONVERT_MULTISWIPE_SCRIPT = PROJECT_ROOT / "scripts" / "convert_multiswipe.py"

RELEASE_LATEST_TEMPLATE = "https://github.com/{repo}/releases/latest"
RELEASE_TAG_TEMPLATE = "https://github.com/{repo}/releases/tag/{tag}"
USER_AGENT = "Narutomobile-Asset-Sync"

DOWNLOAD_LINK_RE = re.compile(r'href="([^"]*releases/download/[^"]+)"')
TITLE_TAG_RE = re.compile(r"<title>Release\s+([^<\s]+)", re.IGNORECASE)

# 实测 latest asset 形如 MaaAutoNaruto-win-x86_64-<version>.zip。
# 该 win x64 release 内置通用 resource/interface.json。
WIN_X64_RE = re.compile(
    r"(?i)(?:"
    r"(?:win(?:dows)?(?:[-_. ]?)(?:x64|x86_64|amd64|64))|"
    r"(?:(?:x64|x86_64|amd64|64)(?:[-_. ]?)win(?:dows)?)"
    r").*\.zip$"
)

RESOURCE_MARKERS = (
    "resource/base/pipeline/",
    "resource/base/image/",
    "resource/base/model/",
    "resource/base/",
    "resource/pipeline/",
    "resource/image/",
    "resource/model/",
    "resource/announcement/",
)


def parse_release_html(html_text: str, page_url: str) -> tuple[str, list[tuple[str, str]]]:
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

    assets: list[tuple[str, str]] = []
    for href in DOWNLOAD_LINK_RE.findall(html_text):
        asset_url = urljoin(page_url, html.unescape(href))
        asset_path = urlparse(asset_url).path
        if not asset_path.endswith(".zip"):
            continue
        asset_name = Path(unquote(asset_path)).name
        if not asset_name:
            continue
        if (asset_url, asset_name) not in assets:
            assets.append((asset_url, asset_name))

    if not assets:
        print("[ERROR] release HTML 里没有找到 zip 资产。", file=sys.stderr)
        sys.exit(1)
    return release_tag, assets


def fetch_release_page(repo: str, tag: str | None = None) -> tuple[str, list[tuple[str, str]]]:
    if tag:
        url = RELEASE_TAG_TEMPLATE.format(repo=repo, tag=quote(tag, safe=""))
    else:
        url = RELEASE_LATEST_TEMPLATE.format(repo=repo)

    print(f"[FETCH] {url}")
    req = urllib.request.Request(
        url,
        headers={
            "Accept": "text/html,application/xhtml+xml",
            "User-Agent": USER_AGENT,
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            html_text = resp.read().decode("utf-8", "replace")
            final_url = resp.geturl()
    except urllib.error.HTTPError as e:
        print(f"[ERROR] GitHub release 页面请求失败: HTTP {e.code} {e.reason}", file=sys.stderr)
        sys.exit(1)
    except urllib.error.URLError as e:
        print(f"[ERROR] 无法连接 GitHub release 页面: {e.reason}", file=sys.stderr)
        sys.exit(1)

    return parse_release_html(html_text, final_url)


def select_asset(assets: list[tuple[str, str]], pattern: str | None = None):
    if not assets:
        print("[ERROR] release 没有可用的 zip 资产。", file=sys.stderr)
        sys.exit(1)

    if pattern:
        compiled = re.compile(pattern)
        for asset_url, asset_name in assets:
            if compiled.search(asset_name):
                return asset_url, asset_name
        print(
            f"[ERROR] 没有匹配 --asset-pattern={pattern!r} 的 asset。",
            file=sys.stderr,
        )
        print("可用的 asset:", file=sys.stderr)
        for _, asset_name in assets:
            print(f"  - {asset_name}", file=sys.stderr)
        sys.exit(1)

    for asset_url, asset_name in assets:
        if WIN_X64_RE.search(asset_name):
            return asset_url, asset_name

    print(
        "[ERROR] 没有找到名称含 win/windows + x64/x86_64/amd64 的 zip asset。",
        file=sys.stderr,
    )
    print("可用的 asset:", file=sys.stderr)
    for _, asset_name in assets:
        print(f"  - {asset_name}", file=sys.stderr)
    print("[HINT] 如果上游命名不同，可用 --asset-pattern 指定。", file=sys.stderr)
    sys.exit(1)


def download_asset(asset_name: str, asset_url: str, cache_dir: Path) -> Path:
    cache_dir.mkdir(parents=True, exist_ok=True)
    asset_name = Path(asset_name).name
    dest = cache_dir / asset_name

    if dest.exists():
        print(f"  [CACHE] 已存在，跳过下载: {dest}")
        return dest

    print(f"  [DOWNLOAD] {asset_name}")
    req = urllib.request.Request(
        asset_url,
        headers={
            "Accept": "application/octet-stream",
            "User-Agent": USER_AGENT,
        },
    )
    tmp = dest.with_name(dest.name + ".part")
    if tmp.exists():
        tmp.unlink()

    try:
        with urllib.request.urlopen(req, timeout=600) as resp, open(tmp, "wb") as out:
            total = int(resp.headers.get("Content-Length", 0))
            downloaded = 0
            while True:
                chunk = resp.read(1024 * 1024)
                if not chunk:
                    break
                out.write(chunk)
                downloaded += len(chunk)
                if total > 0:
                    pct = downloaded * 100 // total
                    print(
                        f"\r    {downloaded / 1024 / 1024:.1f}/{total / 1024 / 1024:.1f} MB ({pct}%)",
                        end="",
                        flush=True,
                    )
            print()
    except Exception as e:
        tmp.unlink(missing_ok=True)
        print(f"[ERROR] 下载失败: {e}", file=sys.stderr)
        sys.exit(1)

    tmp.replace(dest)
    return dest


def detect_archive_kind(path: Path) -> str:
    name = path.name.lower()
    if name.endswith(".zip"):
        return "zip"
    if name.endswith((".tar.gz", ".tgz", ".tar")):
        return "tar"
    print("[ERROR] 只支持 zip / tar.gz / tgz / tar。", file=sys.stderr)
    sys.exit(1)


def normalize_archive_name(name: str) -> str:
    return name.replace("\\", "/").lstrip("/").rstrip("/")


def locate_release_paths(entries) -> tuple[str, str]:
    names = []
    for entry in entries:
        name = normalize_archive_name(entry.name if hasattr(entry, "name") else entry)
        if name:
            names.append(name)

    interfaces = [n for n in names if PurePosixPath(n).name == "interface.json"]
    resource_counts: dict[str, int] = {}

    for name in names:
        for marker in RESOURCE_MARKERS:
            if marker not in name:
                continue
            resource_match = re.search(r"(?:^|/)resource/", name)
            if not resource_match:
                continue
            root = name[: resource_match.start() + len("resource")]
            resource_counts[root] = resource_counts.get(root, 0) + 1
            break

    if not interfaces:
        print("[ERROR] 压缩包内没有 interface.json。", file=sys.stderr)
        sys.exit(1)
    if not resource_counts:
        print("[ERROR] 压缩包内没有 resource/ 资源目录。", file=sys.stderr)
        sys.exit(1)

    resource_root = max(
        resource_counts,
        key=lambda root: (resource_counts[root], -len(PurePosixPath(root).parts)),
    )
    resource_parent = PurePosixPath(resource_root).parent
    preferred_interfaces = [
        p for p in interfaces if PurePosixPath(p).parent == resource_parent
    ]
    interface_path = (
        preferred_interfaces[0]
        if preferred_interfaces
        else min(interfaces, key=lambda p: len(PurePosixPath(p).parts))
    )

    return interface_path, resource_root


def copy_archive_member(archive, kind: str, entry, dest: Path) -> bool:
    try:
        if kind == "zip":
            with archive.open(entry) as src, open(dest, "wb") as out:
                shutil.copyfileobj(src, out)
        else:
            src = archive.extractfile(entry)
            if src is None:
                return False
            with src, open(dest, "wb") as out:
                shutil.copyfileobj(src, out)
        return True
    except OSError as e:
        print(f"  [WARN] 跳过文件 {entry}: {e}")
        return False


def extract_to_staging(
    archive_path: Path, staging: Path, keep_multiswipe: bool
) -> None:
    kind = detect_archive_kind(archive_path)
    if kind == "zip":
        archive = zipfile.ZipFile(archive_path, "r")
        entries = archive.infolist()
    else:
        archive = tarfile.open(archive_path, "r:*")
        entries = archive.getmembers()

    try:
        names = []
        for entry in entries:
            name = normalize_archive_name(
                entry.filename if kind == "zip" else entry.name
            )
            if name:
                names.append(name)

        interface_path, resource_root = locate_release_paths(names)
        print(f"  [ARCHIVE] interface.json: {interface_path}")
        print(f"  [ARCHIVE] resource root: {resource_root}")

        copied = 0
        for entry in entries:
            is_dir = entry.is_dir() if kind == "zip" else entry.isdir()
            if is_dir:
                continue

            name = normalize_archive_name(
                entry.filename if kind == "zip" else entry.name
            )
            if not name:
                continue

            if name == interface_path:
                dest = staging / "interface.json"
            elif name.startswith(resource_root + "/"):
                rel = name[len(resource_root) + 1 :]
                if not rel:
                    continue
                dest = staging / "resource" / Path(rel)
            else:
                continue

            dest.parent.mkdir(parents=True, exist_ok=True)
            if copy_archive_member(archive, kind, entry, dest):
                copied += 1

        if not (staging / "interface.json").is_file():
            print("[ERROR] 解压后缺少 interface.json。", file=sys.stderr)
            sys.exit(1)
        if not (staging / "resource").is_dir():
            print("[ERROR] 解压后缺少 resource/。", file=sys.stderr)
            sys.exit(1)

        normalize_resource_layout(staging / "resource")
        if not keep_multiswipe:
            run_multiswipe_converter(staging / "resource" / "base" / "pipeline")
        print(f"  [EXTRACT] 已解出 {copied} 个业务资源文件")
    finally:
        archive.close()


def normalize_resource_layout(resource_dir: Path) -> None:
    """兼容上游偶尔把 pipeline/image/model 直接放在 resource/ 下的布局。"""
    base_dir = resource_dir / "base"
    if base_dir.is_dir():
        return

    base_dir.mkdir(parents=True, exist_ok=True)
    for name in ("pipeline", "image", "model", "template", "templates"):
        src = resource_dir / name
        if src.exists():
            shutil.move(str(src), str(base_dir / name))


def run_multiswipe_converter(pipeline_dir: Path) -> None:
    if not pipeline_dir.is_dir():
        print("  [WARN] 没有 pipeline 目录，跳过 MultiSwipe 转换")
        return

    print(f"  [CONVERT] 调用 {CONVERT_MULTISWIPE_SCRIPT.name}")
    result = subprocess.run(
        [sys.executable, str(CONVERT_MULTISWIPE_SCRIPT), str(pipeline_dir)],
        check=False,
    )
    if result.returncode != 0:
        print("[ERROR] MultiSwipe 转换失败。", file=sys.stderr)
        sys.exit(result.returncode)


def deploy_staging(staging: Path, assets_dir: Path, dry_run: bool) -> None:
    iface = staging / "interface.json"
    resource = staging / "resource"
    if not iface.is_file():
        print("[ERROR] 待部署目录缺少 interface.json。", file=sys.stderr)
        sys.exit(1)
    if not resource.is_dir():
        print("[ERROR] 待部署目录缺少 resource/。", file=sys.stderr)
        sys.exit(1)

    pipeline_count = len(list((resource / "base" / "pipeline").glob("*.json")))
    if dry_run:
        print("[DRY-RUN] 不会写入 assets，已准备好以下内容：")
        print(f"  interface.json: {iface}")
        print(f"  resource/: {resource} ({pipeline_count} 个 pipeline)")
        return

    assets_dir.mkdir(parents=True, exist_ok=True)
    target_interface = assets_dir / "interface.json"
    target_resource = assets_dir / "resource"
    backup_interface = assets_dir / ".interface.json.bak"
    backup_resource = assets_dir / ".resource.bak"

    backup_interface.unlink(missing_ok=True)
    shutil.rmtree(backup_resource, ignore_errors=True)

    if target_interface.exists():
        target_interface.replace(backup_interface)
    if target_resource.exists():
        target_resource.replace(backup_resource)

    try:
        shutil.move(str(resource), str(target_resource))
        shutil.move(str(iface), str(target_interface))
    except Exception as e:
        shutil.rmtree(target_resource, ignore_errors=True)
        target_interface.unlink(missing_ok=True)
        if backup_resource.exists():
            shutil.move(str(backup_resource), str(target_resource))
        if backup_interface.exists():
            shutil.move(str(backup_interface), str(target_interface))
        print(f"[ERROR] 部署失败，已回滚: {e}", file=sys.stderr)
        sys.exit(1)

    shutil.rmtree(backup_resource, ignore_errors=True)
    backup_interface.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="下载 narutomobile 最新稳定 release 并部署到 Android assets"
    )
    parser.add_argument("--repo", default=GITHUB_REPO, help="GitHub owner/repo")
    parser.add_argument("--tag", help="指定 release tag（默认最新稳定版）")
    parser.add_argument(
        "--asset-pattern",
        help="用正则指定要下载的 release asset，例如 'resource.*\\.zip$'",
    )
    parser.add_argument(
        "--cache-dir",
        type=Path,
        default=DEFAULT_CACHE_DIR,
        help="下载缓存目录（默认项目根目录 cache/）",
    )
    parser.add_argument(
        "--assets-dir",
        type=Path,
        default=DEFAULT_ASSETS_DIR,
        help="部署目标 assets 目录",
    )
    parser.add_argument(
        "--local-zip", type=Path, help="使用本地压缩包，不抓取 GitHub release 页面"
    )
    parser.add_argument(
        "--skip-download",
        action="store_true",
        help="使用 cache/ 里已有的压缩包，不下载",
    )
    parser.add_argument(
        "--keep-multiswipe",
        action="store_true",
        help="保留上游 MultiSwipe，不调用 convert_multiswipe.py",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="下载/解压到缓存后只打印结果，不写入 assets",
    )
    args = parser.parse_args()

    cache_dir = args.cache_dir.resolve()
    assets_dir = args.assets_dir.resolve()
    release_tag = args.tag or "unknown"

    print("=" * 60)
    print("==> narutomobile 业务资源同步")
    print("=" * 60)

    if args.local_zip:
        archive_path = args.local_zip.resolve()
        if not archive_path.is_file():
            print(f"[ERROR] 本地压缩包不存在: {archive_path}", file=sys.stderr)
            sys.exit(1)
        asset_name = archive_path.name
    elif args.skip_download:
        cache_dir.mkdir(parents=True, exist_ok=True)
        zips = sorted(cache_dir.glob("*.zip"))
        if not zips:
            print("[ERROR] cache/ 里没有 zip；请先正常运行一次下载。", file=sys.stderr)
            sys.exit(1)
        archive_path = zips[-1]
        asset_name = archive_path.name
    else:
        release_tag, assets = fetch_release_page(args.repo, args.tag)
        asset_url, asset_name = select_asset(assets, args.asset_pattern)
        archive_path = download_asset(asset_name, asset_url, cache_dir)

    cache_dir.mkdir(parents=True, exist_ok=True)
    print(f"  [RELEASE] {release_tag}")
    print(f"  [ASSET] {asset_name}")
    print(f"  [CACHE] {archive_path}")

    with tempfile.TemporaryDirectory(prefix="naruto-assets-", dir=cache_dir) as tmp:
        staging = Path(tmp)
        extract_to_staging(archive_path, staging, args.keep_multiswipe)
        deploy_staging(staging, assets_dir, args.dry_run)

    print("-" * 60)
    print("[DONE]")
    print(f"  缓存: {cache_dir}")
    print(f"  interface.json: {assets_dir / 'interface.json'}")
    print(f"  resource: {assets_dir / 'resource'}")


if __name__ == "__main__":
    main()
