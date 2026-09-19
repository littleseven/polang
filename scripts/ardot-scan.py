#!/usr/bin/env python3
"""Ardot 画布基线扫描(只读): 逐页 batch_read(readDepth=-1) + fetch_variables 落盘 JSONL。

复用 scripts/sync-ardot-variables.py 的 mcp_connect/rpc 模式(urllib 直连本地 MCP)。
大结果 batch_read/fetch_variables 由 MCP 服务端写 JSONL 到 $TMPDIR/.ardot/reads/,
响应里只给路径——本脚本用缓存路径正则回退识别并复制该文件; 小结果内联时自行序列化为
同构 JSONL(首行 _meta, 其后逐节点一行)。产物可直接喂 scripts/ardot-health-check.py。

用法:
  python3 scripts/ardot-scan.py                        # 全量 11 页 + vars -> tmp/ardot-health/
  python3 scripts/ardot-scan.py --pages People Chat    # 只扫已知清单中的指定页
  python3 scripts/ardot-scan.py --pages Smoke=6:2      # Name=nodeId 自定义页
  python3 scripts/ardot-scan.py --out-dir D --vars-out D/vars.jsonl [--skip-vars]
退出码: 0=全部成功 1=有页失败(每页重试 3 次仍败)。
前置: Ardot 桌面客户端已启动并打开 polang-ui-spec(本地 MCP 127.0.0.1:50501)。
"""
import argparse
import json
import os
import re
import shutil
import sys
import urllib.request

DEFAULT_ENDPOINT = "http://127.0.0.1:50501/api/v1/mcp"
DEFAULT_FILE_ID = "715061534788814"
DEFAULT_OUT_DIR = "tmp/ardot-health"

# 已知 11 页(页名, 页节点 id); --pages 默认全量
PAGES = [
    ("Camera", "6:2"),
    ("Gallery", "103:1"),
    ("Chat", "111:319"),
    ("Settings", "108:1"),
    ("Editor", "118:104"),
    ("IconSet", "132:2"),
    ("PlayStoreAssets", "152:1"),
    ("People", "171:1"),
    ("Organize", "267:21"),
    ("Memories", "297:1"),
    ("Components", "386:45"),
]

# 缓存路径正则回退: 允许文件名含空格(fetch_variables 缓存名带集合名, 如 "PoLang Tokens"), 不跨行
CACHE_PATH_RE = r"(/[^\n\"']*\.ardot/reads/[^\n\"']+?\.jsonl)"


def rpc(endpoint, method, params=None, rid=1, sid=None, timeout=300):
    body = {"jsonrpc": "2.0", "id": rid, "method": method}
    if params is not None:
        body["params"] = params
    headers = {"Content-Type": "application/json",
               "Accept": "application/json, text/event-stream"}
    if sid:
        headers["Mcp-Session-Id"] = sid
    req = urllib.request.Request(endpoint, data=json.dumps(body).encode(), headers=headers)
    resp = urllib.request.urlopen(req, timeout=timeout)
    sid_out = resp.headers.get("Mcp-Session-Id", sid)
    raw = resp.read().decode()
    for line in raw.splitlines():
        if line.startswith("data:"):
            return json.loads(line[5:].strip()), sid_out
    return (json.loads(raw) if raw else {}), sid_out


def mcp_connect(endpoint):
    _, sid = rpc(endpoint, "initialize", {
        "protocolVersion": "2024-11-05",
        "capabilities": {},
        "clientInfo": {"name": "ardot-scan", "version": "1.0"},
    })
    notify = {"jsonrpc": "2.0", "method": "notifications/initialized"}
    req = urllib.request.Request(endpoint, data=json.dumps(notify).encode(), headers={
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        **({"Mcp-Session-Id": sid} if sid else {}),
    })
    try:
        urllib.request.urlopen(req, timeout=30)
    except Exception:
        pass
    return sid


def call_tool(endpoint, sid, name, arguments, rid):
    result, _ = rpc(endpoint, "tools/call", {"name": name, "arguments": arguments}, rid=rid, sid=sid)
    content = result.get("result", {}).get("content", [])
    texts = [c.get("text", "") for c in content if c.get("type") == "text"]
    return "\n".join(texts), result


def file_url(file_id, page_id):
    return f"cocraft://localhost/file/{file_id}?node_id={page_id.replace(':', '%3A')}"


def save_page_jsonl(out_dir, page_name, page_id, text):
    """batch_read 响应文本 -> <out_dir>/baseline-<page>.jsonl。返回 (路径, 节点数, 来源)。"""
    out_path = os.path.join(out_dir, f"baseline-{page_name}.jsonl")
    # 大结果: 响应里给 $TMPDIR/.ardot/reads/ 路径(缓存路径正则回退)
    m = re.search(CACHE_PATH_RE, text)
    if m:
        shutil.copyfile(m.group(1), out_path)
        n = sum(1 for _ in open(out_path)) - 1
        return out_path, n, f"jsonl-cache:{m.group(1)}"
    # 内联结果: 自行序列化(首行 _meta, 其后逐节点一行)
    data = json.loads(text)
    nodes = data.get("data", data).get("nodes") or data.get("nodes") or []
    with open(out_path, "w") as f:
        f.write(json.dumps({"_meta": {"nodes": len(nodes), "page": page_id}}) + "\n")
        for nd in nodes:
            f.write(json.dumps(nd, ensure_ascii=False) + "\n")
    return out_path, len(nodes), "inline"


def save_vars_jsonl(text, vars_out):
    """fetch_variables 响应文本 -> vars_out。返回来源描述。"""
    m = re.search(CACHE_PATH_RE, text)
    if m:
        shutil.copyfile(m.group(1), vars_out)
        head = json.loads(open(vars_out).readline())
        meta = head.get("_meta", {})
        return (f"sets={meta.get('variableSets')} vars={meta.get('variables')} "
                f"-> {vars_out} (jsonl-cache)")
    data = json.loads(text)
    sets = (data.get("data") or data).get("variableSets", [])
    nvars = 0
    with open(vars_out, "w") as f:
        f.write(json.dumps({"_meta": {"sets": len(sets)}}) + "\n")
        for s in sets:
            for v in s.get("variables", []):
                v = dict(v)
                v["_set"] = s.get("name")
                f.write(json.dumps(v, ensure_ascii=False) + "\n")
                nvars += 1
    return f"sets={len(sets)} vars={nvars} -> {vars_out}"


def parse_pages(specs):
    """--pages 取值: None=全 11 页; 条目为已知页名或 Name=nodeId。"""
    if not specs:
        return list(PAGES)
    by_name = {n: pid for n, pid in PAGES}
    out = []
    for s in specs:
        if "=" in s:
            name, pid = s.split("=", 1)
            out.append((name, pid))
        elif s in by_name:
            out.append((s, by_name[s]))
        else:
            raise SystemExit(f"未知页 {s!r}(已知: {sorted(by_name)}; 或用 Name=nodeId)")
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description="Ardot 画布基线扫描(只读): batch_read + fetch_variables 落盘 JSONL")
    ap.add_argument("--out-dir", default=DEFAULT_OUT_DIR, help="页面 JSONL 输出目录")
    ap.add_argument("--pages", nargs="+", default=None,
                    help="已知页名(默认全 11 页)或 Name=nodeId 自定义页")
    ap.add_argument("--vars-out", default=None,
                    help="fetch_variables 落盘路径(默认 <out-dir>/vars.jsonl)")
    ap.add_argument("--skip-vars", action="store_true", help="跳过 fetch_variables")
    ap.add_argument("--endpoint", default=DEFAULT_ENDPOINT, help="Ardot 本地 MCP 端点")
    ap.add_argument("--file-id", default=DEFAULT_FILE_ID, help="目标 .ardot 文件 id")
    a = ap.parse_args(argv)

    pages = parse_pages(a.pages)
    os.makedirs(a.out_dir, exist_ok=True)
    vars_out = a.vars_out or os.path.join(a.out_dir, "vars.jsonl")
    if not a.skip_vars:
        os.makedirs(os.path.dirname(os.path.abspath(vars_out)), exist_ok=True)

    sid = mcp_connect(a.endpoint)
    print(f"session={sid}")
    rid = 10
    failures = []
    for name, pid in pages:
        rid += 1
        for attempt in (1, 2, 3):
            try:
                text, _raw = call_tool(a.endpoint, sid, "batch_read", {
                    "nodeIds": [pid],
                    "readDepth": -1,
                    "fileUrl": file_url(a.file_id, pid),
                }, rid=rid)
                out_path, n, src = save_page_jsonl(a.out_dir, name, pid, text)
                print(f"OK  {name:16s} {pid:8s} nodes={n:5d} via={src} -> {out_path}")
                break
            except Exception as e:
                print(f"ERR {name:16s} {pid:8s} attempt={attempt}: {e}", file=sys.stderr)
                if attempt == 3:
                    failures.append((name, pid, str(e)))
    if not a.skip_vars:
        rid += 1
        text, _ = call_tool(a.endpoint, sid, "fetch_variables", {}, rid=rid)
        print(f"OK  variables        {save_vars_jsonl(text, vars_out)}")
    if failures:
        print(f"FAILURES: {failures}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
