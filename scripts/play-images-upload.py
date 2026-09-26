#!/usr/bin/env python3
"""Play listing 图片三语重传：SOCKS 桥 + SA JWT + prune 逐语言重传 + sha 回读。

用法: python3 play_images_upload.py [--dry-run]
清单源: androidApp/src/main/play/listings/<lang>/graphics/
通道: socks5h://127.0.0.1:51081 (须先可达, 脚本自检)
"""
import argparse
import base64
import hashlib
import json
import os
import subprocess
import sys
import time
import http.client as httpclient
import urllib.request

import socks
import socket
socks.set_default_proxy(socks.SOCKS5, "127.0.0.1", 51081)
socket.socket = socks.socksocket

PKG = "com.mamba.picme"
LANGS = ["en-US", "zh-CN", "zh-TW"]
TYPES = [("PHONE_SCREENSHOTS", "phone-screenshots", "phoneScreenshots"),
         ("FEATURE_GRAPHIC", "feature-graphic", "featureGraphic")]
BASE = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
UPLOAD_BASE = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications"
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
LISTINGS = os.path.join(ROOT, "androidApp/src/main/play/listings")


class Retryable(Exception):
    pass


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def http(req, body=None, token=None):
    last = None
    for i in range(4):
        try:
            return http_once(req, body, token)
        except Retryable as e:
            last = e
            log(f"  网络抖动重试 {i+1}/3: {e}")
            time.sleep(3 + 4 * i)
    raise last


def http_once(req, body=None, token=None):
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, data=body, timeout=120) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")[:300]
        raise RuntimeError(f"HTTP {e.code} {req.get_method()} {req.full_url[:140]}\n  {detail}") from e
    except (ConnectionError, httpclient.RemoteDisconnected, socket.timeout, OSError) as e:
        raise Retryable(f"{type(e).__name__}: {e}") from e


def b64url(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def get_token(sa):
    """SA JSON -> RS256 JWT -> OAuth token (openssl 签名, 免 google 依赖)。"""
    now = int(time.time())
    header = b64url(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = b64url(json.dumps({
        "iss": sa["client_email"],
        "scope": "https://www.googleapis.com/auth/androidpublisher",
        "aud": sa["token_uri"],
        "iat": now, "exp": now + 3000,
    }).encode())
    import tempfile
    with tempfile.NamedTemporaryFile("w", suffix=".pem", delete=False) as f:
        f.write(sa["private_key"]); keyf = f.name
    try:
        sig = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", keyf],
            input=f"{header}.{claims}".encode(), capture_output=True, check=True).stdout
    finally:
        os.unlink(keyf)
    jwt = f"{header}.{claims}.{b64url(sig)}"
    body = ("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=" + jwt).encode()
    req = urllib.request.Request(sa["token_uri"], data=body,
                                 headers={"Content-Type": "application/x-www-form-urlencoded"})
    _, resp = http(req)
    return resp["access_token"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    # 桥自检
    ip = urllib.request.urlopen("https://api.ipify.org", timeout=20).read().decode()
    log(f"桥出口 IP: {ip}")

    sa_path = os.environ.get("POLANG_PLAY_SERVICE_ACCOUNT_JSON")
    if not sa_path or not os.path.isfile(sa_path):
        log("缺少 POLANG_PLAY_SERVICE_ACCOUNT_JSON"); return 1
    sa = json.load(open(sa_path))
    token = get_token(sa)
    log("OAuth token 获取成功")

    req = urllib.request.Request(f"{BASE}/{PKG}/edits", data=b"{}", method="POST",
                                 headers={"Content-Type": "application/json"})
    st, edit = http(req, token=token)
    edit_id = edit["id"]
    log(f"edit {edit_id} 创建")

    total = ok = 0
    for lang in LANGS:
        for api_type, dir_name, path_type in TYPES:
            # 1) prune: 删光该类型旧图 (403 超 8 张的既定配方)
            req = urllib.request.Request(
                f"{BASE}/{PKG}/edits/{edit_id}/listings/{lang}/{path_type}",
                method="DELETE")
            st, resp = http(req, token=token)
            deleted = len(resp.get("deleted", [])) if isinstance(resp, dict) else "?"
            log(f"prune {lang}/{api_type}: 删 {deleted} 张")

            # 2) 上传本地图
            folder = os.path.join(LISTINGS, lang, "graphics", dir_name)
            files = sorted(os.listdir(folder)) if os.path.isdir(folder) else []
            files = [f for f in files if f.endswith(".png")]
            if not files:
                raise RuntimeError(f"目录无 PNG: {folder} (ROOT={ROOT})——路径算错,拒绝空传")
            for name in files:
                if not name.endswith(".png"):
                    continue
                path = os.path.join(folder, name)
                total += 1
                if args.dry_run:
                    log(f"  (dry) {lang}/{api_type}/{name}"); ok += 1; continue
                with open(path, "rb") as f:
                    data = f.read()
                req = urllib.request.Request(
                    f"{UPLOAD_BASE}/{PKG}/edits/{edit_id}/listings/{lang}/{path_type}",
                    data=data, method="POST", headers={"Content-Type": "image/png"})
                st, resp = http(req, token=token)
                sha1 = resp.get("image", {}).get("sha1", "")
                local = hashlib.sha1(data).hexdigest()
                mark = "OK " if sha1 == local else "SHA-MISMATCH"
                if sha1 == local:
                    ok += 1
                log(f"  {mark} {lang}/{name} remote={sha1[:10]} local={local[:10]}")

    if args.dry_run:
        log(f"dry-run 结束: {total} 张待传"); return 0

    # 3) 回读复核
    verified = 0
    for lang in LANGS:
        for api_type, dir_name, path_type in TYPES:
            req = urllib.request.Request(
                f"{BASE}/{PKG}/edits/{edit_id}/listings/{lang}/{path_type}")
            st, resp = http(req, token=token)
            n = len(resp.get("images", []))
            verified += n
            log(f"回读 {lang}/{api_type}: {n} 张")

    # 4) commit
    req = urllib.request.Request(f"{BASE}/{PKG}/edits/{edit_id}:commit", method="POST",
                                 headers={"Content-Type": "application/json"})
    st, resp = http(req, b"{}", token=token)
    log(f"edit commit: {st}; 上传 {ok}/{total}, 回读合计 {verified}")
    return 0 if ok == total and verified == total else 2


if __name__ == "__main__":
    sys.exit(main())
