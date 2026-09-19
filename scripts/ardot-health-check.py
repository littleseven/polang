#!/usr/bin/env python3
"""Ardot 设计健康检查: 一次 batch_read JSONL 全量扫描, 四项检测 + 报告 + 退出码。
用法:
  ardot-health-check.py --jsonl <batch_read.jsonl>... --vars <fetch_variables.jsonl> \
      --components docs/08-UI-SPECS/screens/refs/ardot/components.json \
      [--out-dir tmp/ardot-health] [--min-dup-nodes 4] [--ignore 子串]...
检测项:
  1. literal 泄漏   — 未绑 token 的 SOLID/GRADIENT-stop 色(IMAGE 除外)
  2. 组件化违规     — ≥2 棵同构子树(节点数≥min-dup-nodes)且非组件实例
  3. 布尔图层漏适配 — 帧内含 sysbar-dark 但无 sysbar-light
  4. 目录漂移       — components.json 登记 id 缺失 / tokenBound=true 但子树有 literal
退出码: 0=健康 1=有发现 2=输入错误。
输入 JSONL 结构同 ardot-theme-audit.py(首行元信息, 节点 id 以 _ 开头为内部行跳过)。"""
import argparse
import importlib.util
import json
import os

CATALOG_KEYS = ("name", "nodeId", "page", "purpose", "darkPng", "lightPng", "tokenBound", "rules")


def load_kernel():
    """按路径加载 ardot-theme-audit.py(文件名含连字符不可直接 import)"""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ardot-theme-audit.py")
    spec = importlib.util.spec_from_file_location("ardot_theme_audit", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def load_roots(jsonl_paths, kernel):
    """JSONL -> [(root_node, label)]; 顶层帧 = 未被任何 children 引用的行"""
    nodes, refd = [], set()
    for f in jsonl_paths:
        for ln in open(f).read().strip().split("\n")[1:]:
            try:
                n = json.loads(ln)
            except Exception:
                continue
            if str(n.get("id", "")).startswith("_"):
                continue
            nodes.append(n)
            kernel.collect_children_ids(n, refd)
    return [(n, n.get("name", n["id"])) for n in nodes if n["id"] not in refd]


def build_node_index(roots):
    idx = {}

    def visit(nd):
        idx[nd.get("id")] = nd
        for c in (nd.get("children") or []):
            if isinstance(c, dict):
                visit(c)

    for root, _label in roots:
        visit(root)
    return idx


def load_var_rgb(vars_path, kernel, mode="2:0"):
    """COLOR 变量 id -> Dark 模式 rgb(0-255 元组)"""
    out = {}
    for ln in open(vars_path).read().strip().split("\n")[1:]:
        n = json.loads(ln)
        if str(n.get("id", "")).startswith("_") or n.get("type") != "COLOR":
            continue
        rgb = kernel.to_rgb((n.get("valuesByMode") or {}).get(mode))
        if rgb:
            out[n["id"]] = rgb
    return out


def load_catalog(path):
    data = json.load(open(path))
    comps = data["components"] if isinstance(data, dict) else data
    if not isinstance(comps, list):
        raise ValueError("catalog 须为 components 数组")
    for c in comps:
        if not isinstance(c, dict):
            raise ValueError("catalog 条目须为对象")
        for key in CATALOG_KEYS:
            if key not in c:
                raise ValueError(f"catalog 条目缺字段 {key}: {c.get('name')}")
    ids = [c["nodeId"] for c in comps]
    if len(ids) != len(set(ids)):
        raise ValueError("catalog nodeId 重复")
    return comps


def check_literals(roots, kernel):
    findings = []
    for root, label in roots:
        acc = []
        kernel.walk(root, label, acc)
        for path, nd in acc:
            for kind in ("fills", "strokes"):
                _b, _i, lits = kernel.classify_paints(nd.get(kind))
                for _p, rgb in lits:
                    findings.append({"frame": label, "id": nd.get("id"), "path": path,
                                     "kind": kind, "rgb": list(rgb)})
    return findings


def paint_rgbs(v, var_rgb, kernel):
    """paint 列表 -> 排序后的可哈希色元组; 绑定经 var_rgb 解析, IMAGE/GRADIENT 记哨兵"""
    out = []
    for p in (v if isinstance(v, list) else [v]):
        if isinstance(p, str):
            if p.startswith("$") and p[1:] in var_rgb:
                out.append(var_rgb[p[1:]])
            continue
        if not isinstance(p, dict):
            continue
        vid = p.get("boundVariableId")
        if vid and vid in var_rgb:
            out.append(var_rgb[vid])
            continue
        if p.get("type") == "IMAGE":
            out.append(("IMAGE",))
            continue
        if p.get("type") == "GRADIENT":
            stops = []
            for st in (p.get("stops") or []):
                svid = st.get("boundVariableId")
                rgb = var_rgb.get(svid) if svid else kernel.to_rgb(st.get("color"))
                if rgb:
                    stops.append(rgb)
            out.append(("GRADIENT", tuple(sorted(stops))))
            continue
        rgb = kernel.to_rgb(p.get("color"))
        if rgb:
            out.append(rgb)
    return sorted(out, key=str)


def fingerprint(nd, var_rgb, kernel):
    """结构指纹: 忽略 id/name/文本, 保留 type/尺寸/色/子树"""
    kids = tuple(sorted(
        (fingerprint(c, var_rgb, kernel)
         for c in (nd.get("children") or []) if isinstance(c, dict)),
        key=str))
    try:
        size = (round(float(nd.get("width") or 0)), round(float(nd.get("height") or 0)))
    except (TypeError, ValueError):
        size = (0, 0)
    return (str(nd.get("type", "")), size,
            tuple(paint_rgbs(nd.get("fills"), var_rgb, kernel)),
            tuple(paint_rgbs(nd.get("strokes"), var_rgb, kernel)), kids)


def _is_instance(nd):
    return str(nd.get("type", "")).lower() == "instance" or bool(nd.get("componentId"))


def find_duplicates(roots, var_rgb, kernel, min_nodes, ignore):
    groups = {}

    def visit(nd, path, record=True):
        if _is_instance(nd) or any(s in path for s in ignore):
            return
        acc = []
        kernel.walk(nd, path, acc)
        if record and len(acc) >= min_nodes:
            fp = repr(fingerprint(nd, var_rgb, kernel))
            g = groups.setdefault(fp, {"locations": [], "nodes": len(acc)})
            g["locations"].append(path)
        for c in (nd.get("children") or []):
            if isinstance(c, dict):
                visit(c, path + "/" + str(c.get("name", c.get("id"))))

    for root, label in roots:
        visit(root, label, record=False)  # 顶层帧自身不入组: 整屏同构属正常, 目标是内部重复子树
    dups = [g for g in groups.values() if len(g["locations"]) >= 2]
    return sorted(dups, key=lambda g: -g["nodes"])


def find_boolean_gaps(roots, kernel):
    gaps = []
    for root, label in roots:
        acc = []
        kernel.walk(root, label, acc)
        names = {str(nd.get("name", "")) for _p, nd in acc}
        if any("sysbar-dark" in n for n in names) and not any("sysbar-light" in n for n in names):
            gaps.append(label)
    return sorted(gaps)


def check_catalog(comps, node_index, kernel):
    drift = []
    for c in comps:
        nd = node_index.get(c["nodeId"])
        if nd is None:
            drift.append({"name": c["name"], "nodeId": c["nodeId"], "issue": "missing"})
            continue
        acc = []
        kernel.walk(nd, c["name"], acc)
        lit = 0
        for _p, n in acc:
            for kind in ("fills", "strokes"):
                _b, _i, lits = kernel.classify_paints(n.get(kind))
                lit += len(lits)
        if lit > 0 and c.get("tokenBound"):
            drift.append({"name": c["name"], "nodeId": c["nodeId"], "issue": f"literal={lit}"})
    return drift


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--jsonl", nargs="+", required=True)
    ap.add_argument("--vars", required=True)
    ap.add_argument("--components", required=True)
    ap.add_argument("--out-dir", default="tmp/ardot-health")
    ap.add_argument("--min-dup-nodes", type=int, default=4)
    ap.add_argument("--ignore", action="append", default=[])
    a = ap.parse_args(argv)
    os.makedirs(a.out_dir, exist_ok=True)

    kernel = load_kernel()
    try:
        comps = load_catalog(a.components)
    except (ValueError, KeyError, json.JSONDecodeError) as e:
        print(f"catalog 错误: {e}")
        return 2
    roots = load_roots(a.jsonl, kernel)
    var_rgb = load_var_rgb(a.vars, kernel)

    report = {
        "literal": check_literals(roots, kernel),
        "duplicates": find_duplicates(roots, var_rgb, kernel, a.min_dup_nodes, a.ignore),
        "boolean_gaps": find_boolean_gaps(roots, kernel),
        "catalog_drift": check_catalog(comps, build_node_index(roots), kernel),
    }
    unhealthy = any(report[k] for k in report)
    json.dump(report, open(os.path.join(a.out_dir, "health.json"), "w"), ensure_ascii=False, indent=1)
    with open(os.path.join(a.out_dir, "health.md"), "w") as md:
        md.write(f"| 检测项 | 发现数 |\n|---|---|\n"
                 f"| literal 泄漏 | {len(report['literal'])} |\n"
                 f"| 组件化违规(重复组) | {len(report['duplicates'])} |\n"
                 f"| 布尔图层漏适配 | {len(report['boolean_gaps'])} |\n"
                 f"| 目录漂移 | {len(report['catalog_drift'])} |\n")
    print(f"literal={len(report['literal'])} dup_groups={len(report['duplicates'])} "
          f"boolean_gaps={len(report['boolean_gaps'])} drift={len(report['catalog_drift'])} "
          f"-> {a.out_dir}/health.(json|md) {'UNHEALTHY' if unhealthy else 'OK'}")
    return 1 if unhealthy else 0


if __name__ == "__main__":
    raise SystemExit(main())
