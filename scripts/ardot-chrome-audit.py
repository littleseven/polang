#!/usr/bin/env python3
"""ardot-chrome-audit.py — 状态栏/虚拟键铬件一致性审计（可 --fix 修复）。

背景（2026-09-21 修复定案）：
  - component/status_bar(385:106) / component/system_nav_bar(385:107) 内含
    bar_dark / bar_light 双图层。引擎限制：①组件级 visible 绑定主题布尔
    （sysbar-dark/sysbar-light）会在写入时被冻结成静态值且引发跨实例漂移；
    ②bar_light 图层 IMAGE fill 会被引擎剥空（空壳化）。
  - 因此唯一稳定机制 = **实例级显式覆盖**：`U("<instId>;<图层id>", {visible: …})`
    （分号子路径，glyph id 跨拷贝稳定：status=386:39/40，nav=386:41/42）。
    DARK = bar_dark 可见；LIGHT = bar_dark 隐藏（bar_light 空壳透明，浅色
    内容透出即为浅色铬件，localmodels 三帧实证）。

用法：
  python3 scripts/ardot-chrome-audit.py            # 只审计：渲染采样 vs 期望模式
  python3 scripts/ardot-chrome-audit.py --fix      # 审计 + 对不一致实例重写显式覆盖
  python3 scripts/ardot-chrome-audit.py --endpoint http://127.0.0.1:50501/api/v1/mcp

期望模式表（LIGHT_FRAMES）：默认全 DARK；localmodels-store01* 三帧为 LIGHT。
新增浅色帧时在此登记。
"""
import argparse
import glob
import json
import os
import re
import sys
import tempfile
import urllib.request

ENDPOINT = "http://127.0.0.1:50501/api/v1/mcp"
FILE_ID = "715061534788814"
# 期望 LIGHT 铬件的帧名（其余全 DARK）
LIGHT_FRAMES = {
    "settings/localmodels-store01-zh",
    "settings/localmodels-store01-en",
    "settings/localmodels-store01-tw",
}
STATUS_LAYERS = ("386:39", "386:40")   # bar_dark, bar_light
NAV_LAYERS = ("386:41", "386:42")


def rpc(body, sid=None, endpoint=ENDPOINT, timeout=600):
    headers = {"Content-Type": "application/json",
               "Accept": "application/json, text/event-stream"}
    if sid:
        headers["Mcp-Session-Id"] = sid
    req = urllib.request.Request(endpoint, data=json.dumps(body).encode(),
                                 headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        sid = resp.headers.get("Mcp-Session-Id")
        raw = resp.read().decode()
    for line in raw.splitlines():
        if line.startswith("data:"):
            try:
                obj = json.loads(line[5:].strip())
            except json.JSONDecodeError:
                continue
            if "result" in obj or "error" in obj:
                return obj, sid
    return {"raw": raw[:500]}, sid


class Canvas:
    def __init__(self, endpoint):
        init, sid = rpc({"jsonrpc": "2.0", "id": 0, "method": "initialize",
                         "params": {"protocolVersion": "2024-11-05",
                                    "capabilities": {},
                                    "clientInfo": {"name": "chrome-audit",
                                                   "version": "1"}}},
                        endpoint=endpoint)
        if "error" in init:
            raise SystemExit(f"init 失败: {init['error']}")
        self.sid = sid
        self.endpoint = endpoint
        rpc({"jsonrpc": "2.0", "method": "notifications/initialized",
             "params": {}}, sid, endpoint)

    def call(self, tool, args, rid):
        res, _ = rpc({"jsonrpc": "2.0", "id": rid, "method": "tools/call",
                      "params": {"name": tool, "arguments": args}},
                     self.sid, self.endpoint)
        if "error" in res:
            return "ERR:" + json.dumps(res["error"])[:200]
        return res.get("result", {}).get("content", [{}])[0].get("text", "")

    def read(self, node_ids, page, depth=2):
        args = {"nodeIds": node_ids, "readDepth": depth,
                "fileUrl": f"cocraft://localhost/file/{FILE_ID}?node_id={page.replace(':', '%3A')}"}
        text = self.call("batch_read", args, 1)
        if text.startswith("{"):
            try:
                return json.loads(text).get("data", {}).get("nodes", [])
            except json.JSONDecodeError:
                pass
        m = re.search(r"(/[^\n\"']*\.ardot/reads/[^\n\"']+?\.jsonl)", text)
        if not m:
            return []
        rows = [json.loads(l) for l in open(m.group(1)) if l.strip()]
        byid = {r.get("id"): r for r in rows}
        return [byid[i] for i in node_ids if i in byid]


def collect_instances(cv):
    """全画布扫 status_bar/system_nav_bar 实例 → [{page,frame,frameName,inst,kind}]"""
    state = json.loads(cv.call("fetch_editor_state", {}, 2))
    out = []
    for p in state.get("data", {}).get("pageList", []):
        pid = p["id"]
        for n in cv.read([pid], pid, depth=2):
            for fr in n.get("children") or []:
                if fr.get("type") != "FRAME":
                    continue
                for c in fr.get("children") or []:
                    if c.get("type") == "INSTANCE" and (
                            c.get("name") in ("status_bar", "system_nav_bar",
                                              "floating_tab", "InputBarOuter")
                            or "input_bar" in str(c.get("name", "")).lower()):
                        out.append({"page": pid, "frame": fr["id"],
                                    "frameName": fr.get("name"),
                                    "inst": c["id"], "kind": c.get("name")})
    return out


def classify(png_path, kind):
    """采样渲染 PNG 的铬件带/内容主题带 → DARK/LIGHT/MID

    kind: status_bar (y0-33) / system_nav_bar (y801-852) / content (y38-70 顶栏背景带，
    主题判别用——照片内容区无法判主题，顶栏背景是 token 表面色)
    """
    from PIL import Image
    import numpy as np
    im = Image.open(png_path).convert("L")
    w, h = im.size
    if kind == "status_bar":
        box = (0, 0, w, int(33 * h / 852))
    elif kind == "system_nav_bar":
        box = (0, int(801 * h / 852), w, h)
    elif kind == "floating_tab":   # 300x56 @ (46,734)
        box = (int(0.13 * w), int(0.868 * h), int(0.86 * w), int(0.922 * h))
    elif kind == "InputBarOuter":  # 输入卡 y690-770 全宽
        box = (0, int(0.81 * h), w, int(0.905 * h))
    else:  # content
        box = (0, int(38 * h / 852), w, int(70 * h / 852))
    v = float(np.asarray(im.crop(box), dtype=float).mean())
    return ("DARK" if v < 75 else "LIGHT" if v > 165 else f"MID({v:.0f})"), v


def audit(cv, fix=False):
    insts = collect_instances(cv)
    by_frame = {}
    for i in insts:
        key = (i["page"], i["frame"], i["frameName"])
        by_frame.setdefault(key, []).append(i["kind"])
    print(f"含铬件帧: {len(by_frame)}（实例 {len(insts)}）")
    outdir = tempfile.mkdtemp(prefix="chrome-audit-")
    bad = []
    for n, ((page, fid, fname), kinds) in enumerate(sorted(by_frame.items())):
        od = os.path.join(outdir, fid.replace(":", "_"))
        cv.call("export_nodes",
                {"nodeIds": [fid], "outputDir": od, "scale": 1,
                 "fileUrl": f"cocraft://localhost/file/{FILE_ID}?node_id={page.replace(':', '%3A')}"},
                100 + n)
        files = glob.glob(od + "/**/*.png", recursive=True)
        if not files:
            print(f"  EXPORT-FAIL {fname}")
            bad.append((page, fid, fname, [], [], "export-fail"))
            continue
        expect = "LIGHT" if fname in LIGHT_FRAMES else "DARK"
        wrong = []
        for kind in kinds + ["content"]:   # 铬件 + 内容主题（盲区补检）
            got, _ = classify(files[0], kind)
            if got != expect:
                wrong.append((kind, got))
        mark = "OK " if not wrong else "BAD"
        if wrong:
            print(f"  {mark} {fname}: 期望{expect} 实际 {wrong}")
            bad.append((page, fid, fname,
                        [i for i in insts if i["frame"] == fid],
                        [k for k, _ in wrong], expect))
    if not bad:
        print(f"✅ 全部 {len(by_frame)} 帧铬件一致")
        return 0
    if fix:
        fixed = 0
        for page, fid, fname, frame_insts, wrong_kinds, expect in bad:
            if not frame_insts:
                continue
            ops = []
            # 内容主题错 → 帧级主题钉定（2:2 集；Dark=2:0 / Light=79:1）
            if any(k == "content" for k in wrong_kinds):
                mode_id = "2:0" if expect == "DARK" else "79:1"
                ops.append(f'U("{fid}", {{variableModes: [{{variableSetId: "2:2", modeId: "{mode_id}"}}]}})')
            for it in frame_insts:
                if it["kind"] == "floating_tab":
                    # 悬浮导航错色 = 字面量残留，回填母版 token
                    ops.append(f'U("{it["inst"]}", {{fills: ["$2:156"]}})')
                    continue
                if it["kind"] == "InputBarOuter":
                    # 输入框错色 = 实例内部覆盖，分号子路径回填母版五件套 token
                    for lyr, tok in (("387:2", "$2:154"), ("387:3", "$2:152"),
                                     ("387:8", "$2:155"), ("387:9", "$2:155"),
                                     ("387:7", "$2:130")):
                        ops.append(f'U("{it["inst"]};{lyr}", {{fills: ["{tok}"]}})')
                    continue
                layers = STATUS_LAYERS if it["kind"] == "status_bar" else NAV_LAYERS
                dark_vis = "true" if expect == "DARK" else "false"
                ops.append(f'U("{it["inst"]};{layers[0]}", {{visible: {dark_vis}}})')
                ops.append(f'U("{it["inst"]};{layers[1]}", {{visible: false}})')
            for c in range(0, len(ops), 24):
                chunk = ops[c:c + 24]
                t = cv.call("batch_edit",
                            {"operations": "\n".join(chunk),
                             "fileUrl": f"cocraft://localhost/file/{FILE_ID}?node_id={page.replace(':', '%3A')}"},
                            200 + fixed)
                ok = t.count('"type":"Update"')
                print(f"  fix {fname}: {ok}/{len(chunk)} ops")
                fixed += 1
        print(f"已对 {len(bad)} 帧写入显式覆盖；请重跑本脚本（不带 --fix）复核")
        return 1
    print(f"❌ {len(bad)} 帧不一致（--fix 可修复）")
    return 1


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--fix", action="store_true", help="对不一致帧写显式覆盖")
    ap.add_argument("--endpoint", default=ENDPOINT)
    args = ap.parse_args()
    cv = Canvas(args.endpoint)
    sys.exit(audit(cv, fix=args.fix))


if __name__ == "__main__":
    main()
