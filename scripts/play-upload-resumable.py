#!/usr/bin/env python3
#
# play-upload-resumable.py - Google Play 上传 fallback（AAB 分块续传 + listing 图像上传）
#
# 背景：直连网络下 GPP（JVM Google API 客户端）上传 60MB AAB 会在 ~1 分钟处被掐断
# （"Unexpected end of file from server"）。AAB 用 Play Developer API 的 resumable
# upload 协议：8MB 分块 + 断点查询续传，单块失败自动从重传点继续，专治长连接不稳。
#
# listing 图像（--images 模式）：GPP publishListing 对 graphics 目录无 diff 全量上传，
# zh-CN 33MB 累计易在同一网络下卡死；图像单文件 ≤3MB，普通 media upload 秒级完成，
# 本模式逐张上传 + 远端 sha256 比对跳过未变更图像，只传增量。
#
# listing 全量同步（--listing 模式）：文本（title/short/full/video）+ 全局详情
# （defaultLanguage/contactEmail/contactWebsite）+ 图像，全部走本脚本单一 edit 一次 commit——
# GPP publishListing 在直连网络下连 OAuth token 都可能超时时的完整替代通道。
#
# 用法:
#   export POLANG_PLAY_SERVICE_ACCOUNT_JSON=/path/to/service-account.json
#   ./scripts/play-upload-resumable.py --aab androidApp/build/outputs/bundle/release/polang-release.aab
#   ./scripts/play-upload-resumable.py --aab <path> --track alpha --status draft
#   ./scripts/play-upload-resumable.py --images androidApp/src/main/play/listings   # 仅图像
#   ./scripts/play-upload-resumable.py --listing androidApp/src/main/play/listings  # 文本+详情+图像
#   ./scripts/play-upload-resumable.py --notes production   # 给已上传版本回填 release notes
#
# AAB 模式仅做上传 + 挂轨道 + commit；listing 模式默认只做新增/更新，不删除远端图像
# （防误删线上素材）。显式传 --prune-types phoneScreenshots,featureGraphic 可在本次
# edit 内先清空指定 imageType 的远端图像再重传本地全集（槽位重排/删图用；edit 未
# commit 前自动过期，不会留下半删状态）。
#

import argparse
import base64
import json
import os
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

OAUTH_TOKEN_URL = "https://oauth2.googleapis.com/token"
API_BASE = "https://androidpublisher.googleapis.com/androidpublisher/v3"
UPLOAD_BASE = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
CHUNK_SIZE = 8 * 1024 * 1024  # 8MB
CHUNK_MAX_RETRIES = 10


def log(msg):
    print(f"[play-upload] {msg}", flush=True)


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def get_access_token(sa_json_path: str) -> str:
    with open(sa_json_path) as f:
        sa = json.load(f)
    now = int(time.time())
    header = b64url(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = b64url(json.dumps({
        "iss": sa["client_email"],
        "scope": SCOPE,
        "aud": OAUTH_TOKEN_URL,
        "iat": now,
        "exp": now + 3600,
    }).encode())
    signing_input = f"{header}.{claims}".encode("ascii")

    with tempfile.NamedTemporaryFile("w", suffix=".pem", delete=False) as kf:
        kf.write(sa["private_key"])
        key_path = kf.name
    try:
        sig = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", key_path],
            input=signing_input, capture_output=True, check=True,
        ).stdout
    finally:
        os.unlink(key_path)

    jwt = f"{header}.{claims}.{b64url(sig)}"
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": jwt,
    }).encode()
    with urllib.request.urlopen(urllib.request.Request(OAUTH_TOKEN_URL, data=body),
                                timeout=60) as resp:
        return json.load(resp)["access_token"]


def api_request(method: str, url: str, token: str, payload=None, extra_headers=None):
    headers = {"Authorization": f"Bearer {token}"}
    if extra_headers:
        headers.update(extra_headers)
    data = None
    if payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=90) as resp:
            text = resp.read().decode()
            return json.loads(text) if text else {}, dict(resp.headers)
    except urllib.error.HTTPError as e:
        body = e.read().decode(errors="replace")
        raise RuntimeError(f"HTTP {e.code} {method} {url}\n响应体: {body}") from e


def create_edit(package: str, token: str) -> str:
    body, _ = api_request("POST", f"{API_BASE}/applications/{package}/edits", token, payload={})
    return body["id"]


def start_resumable_session(package: str, edit_id: str, token: str, total_size: int) -> str:
    url = f"{UPLOAD_BASE}/applications/{package}/edits/{edit_id}/bundles?uploadType=resumable"
    req = urllib.request.Request(url, data=b"", method="POST", headers={
        "Authorization": f"Bearer {token}",
        "X-Upload-Content-Type": "application/octet-stream",
        "X-Upload-Content-Length": str(total_size),
        "Content-Length": "0",
    })
    with urllib.request.urlopen(req) as resp:
        return resp.headers["Location"]


def query_resume_offset(session_uri: str, total_size: int) -> int:
    """询问服务器已收到的字节数，返回下一块的起始偏移。"""
    req = urllib.request.Request(session_uri, data=b"", method="PUT", headers={
        "Content-Length": "0",
        "Content-Range": f"bytes */{total_size}",
    })
    try:
        with urllib.request.urlopen(req) as resp:
            # 200/201 说明其实已经传完了
            return total_size
    except urllib.error.HTTPError as e:
        if e.code == 308:
            range_header = e.headers.get("Range")
            if range_header:  # "bytes=0-N"
                return int(range_header.split("-")[1]) + 1
            return 0
        raise


def upload_chunk(session_uri: str, data: bytes, start: int, total_size: int) -> bool:
    """上传一个分块。返回 True 表示服务器已确认（含最终块）。连接中断抛异常由上层重试。"""
    end = start + len(data) - 1
    req = urllib.request.Request(session_uri, data=data, method="PUT", headers={
        "Content-Type": "application/octet-stream",
        "Content-Range": f"bytes {start}-{end}/{total_size}",
    })
    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            return resp.status in (200, 201)
    except urllib.error.HTTPError as e:
        if e.code == 308:  # Resume Incomplete：中间块的正常确认
            return False
        raise


def resumable_upload(session_uri: str, aab_path: str) -> None:
    total = os.path.getsize(aab_path)
    log(f"文件大小 {total / 1024 / 1024:.1f}MB，分块 {CHUNK_SIZE // 1024 // 1024}MB")
    offset = 0
    with open(aab_path, "rb") as f:
        while offset < total:
            retries = 0
            while True:
                try:
                    f.seek(offset)
                    chunk = f.read(min(CHUNK_SIZE, total - offset))
                    upload_chunk(session_uri, chunk, offset, total)
                    offset += len(chunk)
                    log(f"进度 {offset / total * 100:.0f}%（{offset / 1024 / 1024:.1f}MB / {total / 1024 / 1024:.1f}MB）")
                    break
                except (urllib.error.URLError, ConnectionError, TimeoutError, OSError) as e:
                    retries += 1
                    if retries > CHUNK_MAX_RETRIES:
                        raise RuntimeError(f"分块偏移 {offset} 重试 {CHUNK_MAX_RETRIES} 次仍失败: {e}")
                    wait = min(2 ** retries, 30)
                    log(f"连接中断（{e}），{wait}s 后从断点查询续传（第 {retries} 次）")
                    time.sleep(wait)
                    offset = query_resume_offset(session_uri, total)
                    log(f"断点位置: {offset / 1024 / 1024:.1f}MB")


def assign_track(package: str, edit_id: str, token: str, track: str,
                 version_code: int, status: str, user_fraction: str = "") -> None:
    url = f"{API_BASE}/applications/{package}/edits/{edit_id}/tracks/{track}"
    release = {
        "versionCodes": [str(version_code)],
        "status": status,
    }
    # userFraction 仅 inProgress/halted 合法；completed 收尾时严禁携带（1.0 会被拒）
    if user_fraction and status in ("inProgress", "halted"):
        release["userFraction"] = float(user_fraction)
    payload = {"track": track, "releases": [release]}
    api_request("PUT", url, token, payload=payload)


# ---- release notes 回填（--notes 模式） ----

DEFAULT_NOTES_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "androidApp", "src", "main", "play", "release-notes",
)


def upload_release_notes(package: str, notes_dir: str, token: str, track: str,
                         version_code: int = 0) -> None:
    """给 track 上现存 release 补挂 release notes（--notes 模式）。

    GPP 4.1.1 的 publishListing 只传 title/short/full/video（ListingDetail 枚举实证），
    notes 只随 PublishBundle/Promote 走 JVM 通道——直连网络下 GPP 连 OAuth token 都可能
    超时的场景，用本模式在独立 edit 内给已上传的版本回填 notes。
    读取 notes_dir/<language>/<track>.txt，语言目录有几个就挂几种（≤500 字符/语言）。
    """
    notes = []
    for lang in sorted(os.listdir(notes_dir)):
        path = os.path.join(notes_dir, lang, f"{track}.txt")
        if not os.path.isfile(path):
            continue
        with open(path, encoding="utf-8") as f:
            text = f.read().strip()
        if not text:
            continue
        if len(text) > 500:
            raise RuntimeError(f"{lang} notes 超 500 字符（{len(text)}）")
        notes.append({"language": lang, "text": text})
    if not notes:
        raise RuntimeError(f"{notes_dir} 下未找到任何 <language>/{track}.txt")

    edit_id = create_edit(package, token)
    url = f"{API_BASE}/applications/{package}/edits/{edit_id}/tracks/{track}"
    body, _ = api_request("GET", url, token)
    releases = body.get("releases", [])
    if not releases:
        raise RuntimeError(f"track {track} 上没有任何 release")
    if not version_code:
        version_code = max(int(vc) for r in releases for vc in r.get("versionCodes", []))
    target = next((r for r in releases
                   if str(version_code) in [str(vc) for vc in r.get("versionCodes", [])]), None)
    if target is None:
        raise RuntimeError(f"track {track} 上没有 versionCode={version_code} 的 release")
    target["releaseNotes"] = notes
    api_request("PUT", url, token, payload={"track": track, "releases": releases})
    commit_edit(package, edit_id, token)
    log(f"✅ notes 已回填: v{version_code} @ {track}（{', '.join(n['language'] for n in notes)}）")


def commit_edit(package: str, edit_id: str, token: str) -> None:
    api_request("POST", f"{API_BASE}/applications/{package}/edits/{edit_id}:commit", token)


# ---- listing 图像上传（--images 模式） ----

# GPP graphics 目录名 → Play API imageType
IMAGE_TYPE_MAP = {
    "feature-graphic": "featureGraphic",
    "icon": "icon",
    "phone-screenshots": "phoneScreenshots",
    "tablet-screenshots": "sevenInchScreenshots",
    "large-tablet-screenshots": "tenInchScreenshots",
    "tv-banner": "tvBanner",
    "tv-screenshots": "tvScreenshots",
    "wear-screenshots": "wearScreenshots",
}
IMAGE_UPLOAD_MAX_RETRIES = 8


def list_remote_images(package: str, edit_id: str, token: str,
                       lang: str, image_type: str) -> list:
    """远端该 language/imageType 下已有图像列表（含 id/sha256）。"""
    url = f"{API_BASE}/applications/{package}/edits/{edit_id}/listings/{lang}/{image_type}"
    try:
        body, _ = api_request("GET", url, token)
    except RuntimeError as e:
        if "HTTP 404" in str(e):
            return []  # 该 language/imageType 远端尚无任何图像
        raise
    return [img for img in body.get("images", []) if "id" in img]


def list_remote_image_hashes(package: str, edit_id: str, token: str,
                             lang: str, image_type: str) -> set:
    """远端该 language/imageType 下已有图像的 sha256 集合（用于跳过未变更图像）。"""
    return {img["sha256"] for img in
            list_remote_images(package, edit_id, token, lang, image_type)
            if "sha256" in img}


def delete_remote_images(package: str, edit_id: str, token: str,
                         lang: str, image_type: str) -> int:
    """删除远端该 language/imageType 下全部图像（--prune-types 用，随本次 edit 生效）。"""
    images = list_remote_images(package, edit_id, token, lang, image_type)
    for img in images:
        url = (f"{API_BASE}/applications/{package}/edits/{edit_id}"
               f"/listings/{lang}/{image_type}/{img['id']}")
        api_request("DELETE", url, token)
    return len(images)


def upload_image(package: str, edit_id: str, token: str,
                 lang: str, image_type: str, path: str) -> None:
    """单张图像 media upload（≤3MB 秒级完成，无需 resumable 分块）。"""
    import hashlib
    with open(path, "rb") as f:
        data = f.read()
    mime = "image/png" if path.lower().endswith(".png") else "image/jpeg"
    url = (f"{UPLOAD_BASE}/applications/{package}/edits/{edit_id}"
           f"/listings/{lang}/{image_type}?uploadType=media")
    last_err = None
    for attempt in range(1, IMAGE_UPLOAD_MAX_RETRIES + 1):
        try:
            req = urllib.request.Request(url, data=data, method="POST", headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": mime,
            })
            with urllib.request.urlopen(req, timeout=120) as resp:
                resp.read()
            return
        except (urllib.error.URLError, ConnectionError, TimeoutError, OSError,
                ssl.SSLError) as e:
            last_err = e
            wait = min(2 ** attempt, 30)
            log(f"  上传中断（{e}），{wait}s 后重试（第 {attempt} 次）")
            time.sleep(wait)
    raise RuntimeError(f"图像上传重试 {IMAGE_UPLOAD_MAX_RETRIES} 次仍失败: {path}: {last_err}")


def sync_listing_text(package: str, edit_id: str, token: str, listings_dir: str,
                      langs=frozenset()) -> None:
    """文本 listing + 全局详情（app details）同步。listings_dir = play/listings。"""
    import glob
    play_dir = os.path.dirname(listings_dir.rstrip("/"))
    # 全局详情：play/ 根目录下的 default-language/contact-email/contact-website
    details = {}
    for fname, key in [("default-language.txt", "defaultLanguage"),
                       ("contact-email.txt", "contactEmail"),
                       ("contact-website.txt", "contactWebsite")]:
        p = os.path.join(play_dir, fname)
        if os.path.isfile(p):
            details[key] = open(p, encoding="utf-8").read().strip()
    if details:
        api_request("PUT", f"{API_BASE}/applications/{package}/edits/{edit_id}/details",
                    token, payload=details)
        log(f"  全局详情: {', '.join(details.keys())}")
    # 每语言文本 listing
    for lang_dir in sorted(glob.glob(os.path.join(listings_dir, "*"))):
        if not os.path.isdir(lang_dir):
            continue
        lang = os.path.basename(lang_dir)
        if langs and lang not in langs:
            continue
        payload = {}
        for fname, key in [("title.txt", "title"),
                           ("short-description.txt", "shortDescription"),
                           ("full-description.txt", "fullDescription"),
                           ("video.txt", "video")]:
            p = os.path.join(lang_dir, fname)
            if os.path.isfile(p):
                payload[key] = open(p, encoding="utf-8").read().strip()
        if payload:
            api_request("PUT",
                        f"{API_BASE}/applications/{package}/edits/{edit_id}/listings/{lang}",
                        token, payload=payload)
            log(f"  文本 listing: {lang}（{len(payload)} 个字段）")


def sync_listing_images(package: str, edit_id: str, token: str, listings_dir: str,
                        prune_types=frozenset(), langs=frozenset()) -> tuple:
    """上传 listings_dir/<lang>/graphics/<imageTypeDir>/ 下所有有变更的图像（不含 commit）。

    默认只做新增/更新，不删除远端图像（防误删线上素材）。
    prune_types 中的 imageType 会先清空远端再重传本地全集（槽位重排用）。
    langs 非空时只处理指定 language（配合 --langs 分语言逐 edit 提交，失败只回滚当前语言）。
    """
    import glob
    import hashlib
    uploaded = skipped = deleted = 0
    for graphics_dir in sorted(glob.glob(os.path.join(listings_dir, "*", "graphics"))):
        if langs and os.path.basename(os.path.dirname(graphics_dir)) not in langs:
            continue
        lang = os.path.basename(os.path.dirname(graphics_dir))
        for type_dir in sorted(glob.glob(os.path.join(graphics_dir, "*"))):
            dir_name = os.path.basename(type_dir)
            image_type = IMAGE_TYPE_MAP.get(dir_name)
            if not image_type:
                log(f"  跳过未知图像目录: {lang}/{dir_name}")
                continue
            files = sorted(f for f in glob.glob(os.path.join(type_dir, "*"))
                           if f.lower().endswith((".png", ".jpg", ".jpeg")))
            if not files:
                continue
            if image_type in prune_types:
                n = delete_remote_images(package, edit_id, token, lang, image_type)
                if n:
                    log(f"  清空远端 {lang}/{image_type}（{n} 张，--prune-types）")
                deleted += n
                remote_hashes = set()
            else:
                remote_hashes = list_remote_image_hashes(package, edit_id, token, lang, image_type)
            for path in files:
                local_sha = hashlib.sha256(open(path, "rb").read()).hexdigest()
                if local_sha in remote_hashes:
                    skipped += 1
                    continue
                log(f"  上传 {lang}/{dir_name}/{os.path.basename(path)}")
                upload_image(package, edit_id, token, lang, image_type, path)
                uploaded += 1
    return uploaded, skipped, deleted


def sync_listing(package: str, listings_dir: str, token: str, with_text: bool,
                 prune_types=frozenset(), langs=frozenset()) -> None:
    edit_id = ""
    try:
        edit_id = create_edit(package, token)
        log(f"edit id: {edit_id}")
        if with_text:
            sync_listing_text(package, edit_id, token, listings_dir, langs=langs)
        uploaded, skipped, deleted = sync_listing_images(
            package, edit_id, token, listings_dir, prune_types, langs=langs)
        commit_edit(package, edit_id, token)
        log(f"✅ listing 同步完成（{'文本+详情+' if with_text else ''}图像）: "
            f"删除 {deleted} 张，上传 {uploaded} 张，跳过未变更 {skipped} 张")
    except Exception as e:
        log(f"❌ 失败: {e}")
        log("提示: 未 commit 的 edit 会自动过期，无需手工清理")
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser(description="Google Play 上传 fallback（AAB 续传 / listing 图像）")
    parser.add_argument("--aab", help="AAB 文件路径（--skip-upload/--images 时可省略）")
    parser.add_argument("--images", metavar="LISTINGS_DIR", default="",
                        help="listing 图像同步模式：上传 LISTINGS_DIR/<lang>/graphics/ 下有变更的图像")
    parser.add_argument("--listing", metavar="LISTINGS_DIR", default="",
                        help="listing 全量同步模式：文本+全局详情+图像（GPP publishListing 的替代通道）")
    parser.add_argument("--package", default="com.mamba.picme")
    parser.add_argument("--track", default="internal")
    parser.add_argument("--status", default="completed",
                        choices=["completed", "draft", "inProgress", "halted"])
    parser.add_argument("--user-fraction", default="",
                        help="分阶段发布比例 0-1（仅 inProgress/halted 生效）")
    parser.add_argument("--edit-id", default="",
                        help="复用已有 edit（跳过创建；配合 --skip-upload 直接挂轨道+commit）")
    parser.add_argument("--skip-upload", action="store_true",
                        help="跳过上传（AAB 已在 edit 中，用于 commit 失败后的重试）")
    parser.add_argument("--prune-types", default="",
                        help="逗号分隔的 imageType（API 名，如 phoneScreenshots,featureGraphic）："
                             "上传前先清空远端该类型图像再重传本地全集（槽位重排/删图用）")
    parser.add_argument("--langs", default="",
                        help="逗号分隔的 language（如 en-US,zh-CN）：只同步指定语言，"
                             "配合 --listing 分语言逐 edit 提交，失败只回滚当前语言")
    parser.add_argument("--notes", metavar="TRACK", default="",
                        help="给该轨道现存 release 回填 release notes"
                             "（读 play/release-notes/<lang>/<track>.txt，GPP listing 通道不含 notes）")
    parser.add_argument("--notes-dir", default=DEFAULT_NOTES_DIR,
                        help=f"release notes 根目录（默认仓库内 play/release-notes）")
    parser.add_argument("--version-code", type=int, default=0,
                        help="--notes 挂到哪个 versionCode（默认取该轨道最大值）")
    args = parser.parse_args()
    prune_types = frozenset(t.strip() for t in args.prune_types.split(",") if t.strip())
    langs = frozenset(t.strip() for t in args.langs.split(",") if t.strip())

    sa_path = os.environ.get("POLANG_PLAY_SERVICE_ACCOUNT_JSON", "")
    if not sa_path and os.environ.get("ANDROID_PUBLISHER_CREDENTIALS"):
        # CI 场景：JSON 全文在环境变量里，落临时文件
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as tf:
            tf.write(os.environ["ANDROID_PUBLISHER_CREDENTIALS"])
            sa_path = tf.name
    if not sa_path or not os.path.isfile(sa_path):
        log("错误: POLANG_PLAY_SERVICE_ACCOUNT_JSON（文件路径）或 ANDROID_PUBLISHER_CREDENTIALS（JSON 全文）未设置")
        sys.exit(1)

    if args.notes:
        if not os.path.isdir(args.notes_dir):
            log(f"错误: release notes 目录不存在: {args.notes_dir}")
            sys.exit(1)
        log("获取 access token...")
        token = get_access_token(sa_path)
        upload_release_notes(args.package, args.notes_dir, token, args.notes,
                             version_code=args.version_code)
        return

    listings_dir = args.listing or args.images
    if listings_dir:
        if not os.path.isdir(listings_dir):
            log(f"错误: listings 目录不存在: {listings_dir}")
            sys.exit(1)
        log("获取 access token...")
        token = get_access_token(sa_path)
        log(f"同步 listing: {listings_dir}（{'文本+详情+图像' if args.listing else '仅图像'}）")
        if prune_types:
            log(f"prune imageTypes: {', '.join(sorted(prune_types))}")
        if langs:
            log(f"仅同步语言: {', '.join(sorted(langs))}")
        sync_listing(args.package, listings_dir, token, with_text=bool(args.listing),
                     prune_types=prune_types, langs=langs)
        return

    if not args.skip_upload and (not args.aab or not os.path.isfile(args.aab)):
        log(f"错误: AAB 不存在: {args.aab}")
        sys.exit(1)

    log("获取 access token...")
    token = get_access_token(sa_path)

    if args.edit_id:
        edit_id = args.edit_id
        log(f"复用 edit id: {edit_id}")
    else:
        log(f"创建 edit（package={args.package}）...")
        edit_id = create_edit(args.package, token)
        log(f"edit id: {edit_id}")

    try:
        if not args.skip_upload:
            log("初始化 resumable 上传会话...")
            session_uri = start_resumable_session(
                args.package, edit_id, token, os.path.getsize(args.aab))

            resumable_upload(session_uri, args.aab)
            log("上传完成")

        # 从 edit 内的 bundle 列表取 versionCode（上传响应未保留，这里统一回查）
        bundles, _ = api_request(
            "GET", f"{API_BASE}/applications/{args.package}/edits/{edit_id}/bundles", token)
        version_codes = [b["versionCode"] for b in bundles.get("bundles", [])]
        if not version_codes:
            raise RuntimeError("上传后 edit 内未找到 bundle")
        version_code = max(version_codes)
        log(f"挂轨道: track={args.track}, versionCode={version_code}, status={args.status}")
        assign_track(args.package, edit_id, token, args.track, version_code,
                     args.status, args.user_fraction)

        log("提交 edit（触发审核）...")
        commit_edit(args.package, edit_id, token)
        log(f"✅ 发布完成: v{version_code} → {args.track} ({args.status})")
    except Exception as e:
        log(f"❌ 失败: {e}")
        log("提示: 未 commit 的 edit 会自动过期，无需手工清理")
        sys.exit(1)


if __name__ == "__main__":
    main()
