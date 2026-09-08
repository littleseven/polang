#!/usr/bin/env python3
"""
把 Ardot 桌面端当前打开的设计稿快照导出为可入库的本地文件（git 管理）。

定位：Ardot 云端文档无法导出可编辑源文件（导出仅 PNG/JPG/SVG/PDF 渲染格式，
.fig 只支持导入），本脚本是「设计稿本地化」的替代形态：
  - structure.json  全部顶层帧的节点树结构 dump（文本、可 diff，近似源文件）
  - <frame>.png     每帧 2x 渲染快照（视觉存档，走 export_nodes）
  - manifest.json   文件/帧清单 + 哈希 + 导出时间

云端画布仍是编辑工作区；改完画布重跑本脚本，diff 入库即可审查变更。
变量不导出——SSOT 是 design-tokens.json（见 DESIGN_TOKENS_SPEC.md）。

前置：Ardot 桌面客户端已启动并打开目标文件（本地 MCP 在 127.0.0.1:50501）。
坑：export_nodes 从脚本新建会话调用时必须显式传 fileUrl（否则 NO_ADAPTER），
     返回纯文本 "nodeId → path"，且实际落盘会多一层 {fileId}/ 子目录——均已处理。

用法：python3 scripts/export-ardot-snapshot.py [--out DIR] [--endpoint URL] [--scale N]
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import time
import urllib.request

DEFAULT_ENDPOINT = "http://127.0.0.1:50501/api/v1/mcp"
DEFAULT_OUT = "docs/08-UI-SPECS/screens/refs/ardot"
PNG_BATCH = 5  # export_nodes PNG 每批上限（工具约束）
EXPORT_LINE = re.compile(r"^\s*(\S+)\s*→\s*(\S+)\s*$")


def rpc(endpoint, method, params=None, rid=1, sid=None):
    body = {"jsonrpc": "2.0", "id": rid, "method": method}
    if params is not None:
        body["params"] = params
    headers = {"Content-Type": "application/json",
               "Accept": "application/json, text/event-stream"}
    if sid:
        headers["Mcp-Session-Id"] = sid
    req = urllib.request.Request(endpoint, data=json.dumps(body).encode(), headers=headers)
    resp = urllib.request.urlopen(req, timeout=300)
    sid_out = resp.headers.get("Mcp-Session-Id", sid)
    raw = resp.read().decode()
    for line in raw.splitlines():
        if line.startswith("data:"):
            return json.loads(line[5:].strip()), sid_out
    return (json.loads(raw) if raw else {}), sid_out


def call_tool(endpoint, sid, name, arguments, rid):
    result, _ = rpc(endpoint, "tools/call", {"name": name, "arguments": arguments},
                    rid=rid, sid=sid)
    payload = result.get("result", {})
    if payload.get("isError"):
        raise RuntimeError(f"{name} 错误: {payload.get('content', [{}])[0].get('text', '')[:300]}")
    content = payload.get("content", [{}])[0].get("text", "")
    try:
        data = json.loads(content)
    except ValueError:
        return {"_raw": content}  # 部分工具（export_nodes）返回纯文本
    if not data.get("success", True):
        raise RuntimeError(f"{name} 失败: {content[:500]}")
    return data.get("data", data)


def frame_slug(name):
    return re.sub(r"[^A-Za-z0-9._-]+", "-", name.strip()).strip("-")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--endpoint", default=DEFAULT_ENDPOINT)
    ap.add_argument("--scale", type=int, default=1,
                    help="渲染倍率；默认 1x 控制仓库体积，偶需高清审查时传 2")
    ap.add_argument("--pages", default=None,
                    help="逗号分隔页面ID列表，绕过 fetch_editor_state 的 pageList"
                         "（新会话偶发只返回已激活页；页面名以 batch_read 结果为准）")
    args = ap.parse_args()
    out = os.path.abspath(args.out).rstrip("/")  # outputDir 须绝对路径（客户端按自身 cwd 解析相对路径）

    _, sid = rpc(args.endpoint, "initialize", {
        "protocolVersion": "2024-11-05",
        "capabilities": {},
        "clientInfo": {"name": "export-ardot-snapshot", "version": "1.0"},
    })
    notify = {"jsonrpc": "2.0", "method": "notifications/initialized"}
    req = urllib.request.Request(args.endpoint, data=json.dumps(notify).encode(), headers={
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        **({"Mcp-Session-Id": sid} if sid else {}),
    })
    try:
        urllib.request.urlopen(req, timeout=30)
    except Exception:
        pass  # notification 无响应属正常

    # 1. 文件信息（fileUrl 供 export_nodes 绑定）+ 全部页面顶层帧清单
    #    （2026-08-16 起多页聚合：不再只导「当前页」，遍历 pageList，新增页面自动包含）
    info = call_tool(args.endpoint, sid, "fetch_file_info", {}, rid=10)
    file_url = info["fileUrl"]
    state = call_tool(args.endpoint, sid, "fetch_editor_state", {}, rid=11)
    rid = 11
    if args.pages:
        page_refs = [{"id": p.strip(), "name": p.strip()} for p in args.pages.split(",") if p.strip()]
    else:
        page_refs = state.get("pageList", [state["currentPage"]])
    pages = []
    for pg in page_refs:
        rid += 1
        pg_data = call_tool(args.endpoint, sid, "batch_read",
                            {"nodeIds": [pg["id"]], "readDepth": 1}, rid=rid)
        pg_node = pg_data["nodes"][0] if pg_data.get("nodes") else {}
        frames = [child for child in pg_node.get("children", [])
                  if isinstance(child, dict) and child.get("type") == "FRAME"]
        if frames:
            pages.append({"id": pg["id"], "name": pg_node.get("name", pg["name"]), "frames": frames})
    if not pages:
        raise SystemExit("❌ 所有页面均没有顶层帧")
    total = sum(len(pg["frames"]) for pg in pages)
    print(f"📄 {info['fileName']}（{info['fileId']}）共 {len(pages)} 页 {total} 帧："
          + "，".join(f"「{pg['name']}」{len(pg['frames'])} 帧" for pg in pages))

    # 2. 逐帧结构 dump（readDepth 取深，保证叶子可 diff）
    structures = {}
    rid = 20
    for pg in pages:
        for fr in pg["frames"]:
            rid += 1
            structures[fr["id"]] = call_tool(args.endpoint, sid, "batch_read",
                                             {"nodeIds": [fr["id"]], "readDepth": 12}, rid=rid)
    structure_doc = {
        "source": "ardot-canvas",
        "file": {"id": info["fileId"], "name": info["fileName"], "url": file_url},
        "pages": [{"id": pg["id"], "name": pg["name"],
                   "frames": {fr["id"]: {"name": fr["name"], "tree": structures[fr["id"]]}
                              for fr in pg["frames"]}} for pg in pages],
    }
    structure_text = json.dumps(structure_doc, ensure_ascii=False,
                                indent=2, sort_keys=True) + "\n"

    # 3. PNG 渲染快照（分批；显式 fileUrl；解析纯文本 "nodeId → path"）
    all_frames = [fr for pg in pages for fr in pg["frames"]]
    png_paths = {}
    for i in range(0, len(all_frames), PNG_BATCH):
        rid += 1
        batch = all_frames[i:i + PNG_BATCH]
        data = call_tool(args.endpoint, sid, "export_nodes", {
            "nodeIds": [fr["id"] for fr in batch],
            "outputDir": out,
            "format": "png",
            "scale": args.scale,
            "fileUrl": file_url,
        }, rid=rid)
        for line in data.get("_raw", "").splitlines():
            m = EXPORT_LINE.match(line)
            if m:
                png_paths[m.group(1)] = m.group(2)

    # 4. 落盘：语义命名 PNG + structure.json + manifest.json
    manifest_pages = []
    for pg in pages:
        manifest_frames = []
        for fr in pg["frames"]:
            slug = frame_slug(fr["name"])
            src = png_paths.get(fr["id"])
            dst = f"{out}/{slug}.png"
            if src:
                shutil.copyfile(src, dst)
            manifest_frames.append({"id": fr["id"], "name": fr["name"], "png": f"{slug}.png"})
        manifest_pages.append({"id": pg["id"], "name": pg["name"], "frames": manifest_frames})

    with open(f"{out}/structure.json", "w", encoding="utf-8") as f:
        f.write(structure_text)

    manifest = {
        "exportedAt": time.strftime("%Y-%m-%dT%H:%M:%S+08:00"),
        "endpoint": args.endpoint,
        "file": {"id": info["fileId"], "name": info["fileName"], "url": file_url,
                 "permission": info.get("permission", "")},
        "pages": manifest_pages,
        "structureSha256": hashlib.sha256(structure_text.encode()).hexdigest(),
        "scale": args.scale,
        "note": "云端画布为编辑工作区；本目录快照由 export-ardot-snapshot.py 生成（多页聚合，2026-08-16 起），重跑覆盖。"
                "变量 SSOT=shared/src/commonMain/resources/design-tokens.json，不在本快照内。",
    }
    with open(f"{out}/manifest.json", "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2, sort_keys=True)
        f.write("\n")

    # 客户端原始导出目录（{fileId}/ 时间戳文件）已复制为语义命名，清掉防入盘
    raw_dir = f"{out}/{info['fileId']}"
    if os.path.isdir(raw_dir):
        shutil.rmtree(raw_dir)

    missing = [mfr["name"] for mfr in manifest_frames if mfr["id"] not in png_paths]
    print(f"✅ 快照导出完成 → {out}/")
    for mfr in manifest_frames:
        mark = " " if mfr["id"] in png_paths else "⚠️缺"
        print(f"  {mark} {mfr['png']}  ({mfr['name']})")
    print(f"   structure.json  sha256={manifest['structureSha256'][:12]}…")
    if missing:
        raise SystemExit(f"❌ {len(missing)} 帧 PNG 缺失: {missing}")
    print("提示：git diff docs/08-UI-SPECS/screens/refs/ardot/structure.json 可审查画布结构变更。")


if __name__ == "__main__":
    main()
