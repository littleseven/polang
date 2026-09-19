#!/usr/bin/env python3
"""Ardot 主题审计: 扫描 batch_read JSONL(readDepth=-1), 输出 literal 色清单 + token 绑定健康度 + 改绑建议。
用法:
  ardot-theme-audit.py --jsonl <batch_read.jsonl>... --vars <fetch_variables.jsonl> \
      [--out-dir tmp/ardot-audit] [--threshold 24]
输入 JSONL 来自 mcp__ardot__batch_read(省略 fileUrl, properties 含 fills/strokes)。
JSONL 结构: 每行一个节点(含嵌套子树 dict); 顶层帧 = 未被任何 children 引用的行。
paint 形态: 绑定="$<varId>" 字符串 / literal={"type":"SOLID","color":{r,g,b}} / IMAGE dict / GRADIENT stops。"""
import argparse
import json
import math
import os
import re
from collections import Counter

HEX_RE = re.compile(r"#[0-9A-Fa-f]{6,8}")


def parse_hex(s):
    m = HEX_RE.search(str(s))
    if not m:
        return None
    h = m.group(0)
    return tuple(int(h[i:i + 2], 16) for i in range(0, 6, 2))


def to_rgb(v):
    """{r,g,b} 0-1 浮点 / hex 字符串 / 含 r/g/b 的字符串 -> (r,g,b) 0-255; 失败 None"""
    if isinstance(v, dict):
        try:
            return tuple(round(float(v.get(c, 0)) * 255) for c in ("r", "g", "b"))
        except Exception:
            return None
    rgb = parse_hex(v)
    if rgb:
        return rgb
    m = re.search(r"[rR]':\s*([0-9.]+).*?[gG]':\s*([0-9.]+).*?[bB]':\s*([0-9.]+)", str(v), re.S)
    if m:
        try:
            return tuple(round(float(x) * 255) for x in m.groups())
        except Exception:
            return None
    return None


def dist(a, b):
    return math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)))


def collect_children_ids(nd, ids):
    for c in (nd.get("children") or []):
        if isinstance(c, dict):
            ids.add(c.get("id"))
            collect_children_ids(c, ids)


def walk(nd, path, acc):
    acc.append((path, nd))
    for c in (nd.get("children") or []):
        if isinstance(c, dict):
            walk(c, path + "/" + str(c.get("name", c.get("id"))), acc)


def classify_paints(v):
    """-> (bound_count, images, literals): paint 级 + 渐变 stop 级分类"""
    if v is None:
        return 0, [], []
    bound, imgs, lits = 0, [], []
    for p in (v if isinstance(v, list) else [v]):
        if isinstance(p, str):
            if p.startswith("$"):
                bound += 1
            continue
        if not isinstance(p, dict):
            continue
        if p.get("boundVariableId"):
            bound += 1
            continue
        t = p.get("type")
        if t == "IMAGE":
            imgs.append(p)
            continue
        if t == "GRADIENT":
            for st in (p.get("stops") or []):
                if st.get("boundVariableId"):
                    bound += 1
                elif to_rgb(st.get("color")) is not None:
                    lits.append((st, to_rgb(st.get("color"))))
            continue
        rgb = to_rgb(p.get("color"))
        if rgb is not None:
            lits.append((p, rgb))
    return bound, imgs, lits


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jsonl", nargs="+", required=True)
    ap.add_argument("--vars", required=True)
    ap.add_argument("--out-dir", default="tmp/ardot-audit")
    ap.add_argument("--threshold", type=int, default=24)
    a = ap.parse_args()
    os.makedirs(a.out_dir, exist_ok=True)

    # token 库: COLOR 变量的 Dark 模式值 -> var id (用于 literal 就近匹配)
    dark_vals = {}
    for ln in open(a.vars).read().strip().split("\n")[1:]:
        n = json.loads(ln)
        if str(n.get("id", "")).startswith("_") or n.get("type") != "COLOR":
            continue
        rgb = to_rgb((n.get("valuesByMode") or {}).get("2:0"))  # 2:0 = Dark
        if rgb:
            dark_vals[n["id"]] = (n["name"], rgb)

    report, newvars = {}, Counter()
    for f in a.jsonl:
        lines = open(f).read().strip().split("\n")
        nodes, refd = [], set()
        for ln in lines[1:]:
            try:
                n = json.loads(ln)
            except Exception:
                continue
            if str(n.get("id", "")).startswith("_"):
                continue
            nodes.append(n)
            collect_children_ids(n, refd)
        for root in [n for n in nodes if n["id"] not in refd]:
            acc = []
            walk(root, root.get("name", "?"), acc)
            fr = {"id": root["id"], "name": root.get("name"), "nodes": len(acc),
                  "bound": 0, "literal": [], "images": []}
            for path, nd in acc:
                for kind in ("fills", "strokes"):
                    bound, imgs, lits = classify_paints(nd.get(kind))
                    fr["bound"] += bound
                    for _p in imgs:
                        fr["images"].append({"id": nd.get("id"), "path": path, "kind": kind})
                    for p, rgb in lits:
                        if kind == "fills" and isinstance(p, dict) and p.get("type") == "GRADIENT":
                            continue  # 渐变 stop 已并入下方统一处理
                        fr["literal"].append({"id": nd.get("id"), "path": path, "kind": kind,
                                              "rgb": list(rgb)})
            report[root["id"]] = fr

    # 就近匹配建议
    for fid, fr in report.items():
        for lit in fr["literal"]:
            lit_rgb = tuple(lit["rgb"])
            best, bd = None, 1e9
            for vid, (name, rgb) in dark_vals.items():
                d = dist(lit_rgb, rgb)
                if d < bd:
                    best, bd = (vid, name), d
            if best and bd <= a.threshold:
                lit["bind_to"], lit["dist"] = best, round(bd, 1)
            else:
                key = "rgb" + str(tuple(lit["rgb"]))
                newvars[key] += 1
                lit["new_var_needed"] = True
    out = {"frames": report, "unmatched_colors": dict(newvars)}
    json.dump(out, open(os.path.join(a.out_dir, "audit.json"), "w"), ensure_ascii=False, indent=1)
    with open(os.path.join(a.out_dir, "audit.md"), "w") as md:
        md.write("| frame | nodes | bound | literal | images | 需新增色 |\n|---|---|---|---|---|---|\n")
        for fid, fr in sorted(report.items(), key=lambda kv: -len(kv[1]["literal"])):
            nv = sum(1 for l in fr["literal"] if l.get("new_var_needed"))
            md.write(f"| {fr['name']} | {fr['nodes']} | {fr['bound']} | {len(fr['literal'])} | "
                     f"{len(fr['images'])} | {nv} |\n")
    print(f"frames={len(report)} total_literal={sum(len(f['literal']) for f in report.values())} "
          f"unmatched={sum(newvars.values())} -> {a.out_dir}/audit.(json|md)")


if __name__ == "__main__":
    main()
