# Ardot Design Ops 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 ardot-design-ops 体系：健康检查脚本 + 组件目录 + 编排 Skill + 首批组件收编，保障 Light/Dark 全域可切与设计资产不腐败。

**Architecture:** 三层 + 编排：`design-tokens.json` SSOT（已有）→ `components.json` 组件目录（新）→ `ardot-health-check.py` 检测（新，经 importlib 复用 `ardot-theme-audit.py` 内核）→ `skills/ardot-design-ops/SKILL.md` 编排三场景工作流（新）。

**Tech Stack:** Python 3（stdlib only，importlib/json/argparse）、bash、Ardot 本地 MCP（127.0.0.1:50501）。

**Spec:** `docs/superpowers/specs/2026-09-19-ardot-design-ops-design.md`

## Global Constraints

- Dark = 唯一正稿；Light 经帧级 variableModes 钉定预览，**不建物理副本**；变量集 `PoLang Tokens` modeMap `2:0`=Dark / `79:1`=Light，勿调换顺序。
- 测试无 pytest 依赖：standalone runner（惯例见 `scripts/completeness/test_match.py` 末尾）。
- 画布操作**全程单会话串行**，严禁并发会话/并行子代理操作同一 ardot 文件；`batch_edit` ≤25 ops 分批；非当前页节点操作必带 `fileUrl`（`:` 编码为 `%3A`）。
- 脚本头部注释写明用法与输入 JSONL 结构（项目脚本惯例，见 `ardot-theme-audit.py:1-8`）。
- 提交粒度：每个 Task 一个 commit；工作区已存在不属于本任务的未提交改动（docs-site 图片等），只 `git add` 本任务文件。
- 执行前按 `superpowers:using-git-worktrees` 建隔离工作区（`.worktrees/` 下），fix/feat 只落专用分支。

---

### Task 1: `scripts/ardot-health-check.py` — 健康检查脚本（TDD）

**Files:**
- Create: `scripts/ardot-health-check.py`
- Test: `scripts/test_ardot_health_check.py`

**Interfaces:**
- Consumes: `scripts/ardot-theme-audit.py`（经 importlib 加载，用其 `to_rgb` / `classify_paints` / `walk` / `collect_children_ids` / `dist`）；batch_read JSONL（每行一个节点 dict，含嵌套 `children`，首行为元信息）；fetch_variables JSONL（首行元信息，COLOR 变量 `valuesByMode["2:0"]` 为 Dark 值）。
- Produces: `main(argv=None) -> int`（0=健康，1=有发现，2=输入错误）；`load_catalog(path) -> list[dict]`；`load_var_rgb(vars_path, kernel, mode="2:0") -> dict[varId, rgb]`；`fingerprint(nd, var_rgb, kernel) -> tuple`；`find_duplicates(roots, var_rgb, kernel, min_nodes, ignore) -> list[dict]`；`find_boolean_gaps(roots, kernel) -> list[str]`；`check_literals(roots, kernel) -> list[dict]`；`check_catalog(comps, node_index, kernel) -> list[dict]`。其中 `roots` = `[(root_node, label), ...]`。Task 2 的 catalog 测试、Task 4 的画布收编均依赖这些签名。

**设计要点（实现前必读）：**
- `ardot-theme-audit.py` 文件名含连字符不可直接 import，用 importlib 按路径加载。
- 重复子树指纹：忽略 `id`/`name`/文本内容，保留 `type`、四舍五入尺寸、fills/strokes 解析后 RGB（绑定变量经 `var_rgb` 解析为 Dark 值，IMAGE 记 `("IMAGE",)` 哨兵，GRADIENT 记 `("GRADIENT", stops)`）、子树指纹元组。组件实例（`type=="instance"` 或含 `componentId`）整棵跳过不参与检测。已知噪音：Components 页演示帧可能入重复报告，用 `--ignore` 子串过滤。
- 布尔检测规则：帧子树内存在名字含 `sysbar-dark` 的节点但无 `sysbar-light` → 漏适配（即 reorg spec 中 floating_tab 未适配的形态）。
- 目录漂移：登记 `nodeId` 不在画布 → `missing`；`tokenBound: true` 但子树有 literal → `literal=N`。

- [ ] **Step 1: 写失败测试**

`scripts/test_ardot_health_check.py`：

```python
# scripts/test_ardot_health_check.py
# ardot-health-check TDD; env 无 pytest → standalone runner(python3 scripts/test_ardot_health_check.py)
import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import importlib.util

_spec = importlib.util.spec_from_file_location(
    "ardot_health_check",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "ardot-health-check.py"))
hc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(hc)


def solid(r, g, b):
    return {"type": "SOLID", "color": {"r": r, "g": g, "b": b}}


def node(nid, name, children=None, fills=None, w=100, h=40, ntype="FRAME", **extra):
    d = {"id": nid, "name": name, "type": ntype, "width": w, "height": h}
    if fills is not None:
        d["fills"] = fills
    if children:
        d["children"] = children
    d.update(extra)
    return d


def write_jsonl(rows):
    fd, path = tempfile.mkstemp(suffix=".jsonl")
    with os.fdopen(fd, "w") as f:
        f.write(json.dumps({"_meta": "fixture"}) + "\n")
        for r in rows:
            f.write(json.dumps(r) + "\n")
    return path


VARS = write_jsonl([
    {"id": "1:1", "name": "scheme/surface", "type": "COLOR",
     "valuesByMode": {"2:0": {"r": 0.03, "g": 0.08, "b": 0.06}, "79:1": {"r": 0.95, "g": 0.98, "b": 0.96}}},
])


def card(nid):
    """4 节点同构卡片: FRAME[RECT(bound fill), TEXT, TEXT]"""
    return node(nid, "card", children=[
        node(nid + ".1", "bg", fills=["$1:1"], ntype="RECTANGLE"),
        node(nid + ".2", "title", ntype="TEXT"),
        node(nid + ".3", "subtitle", ntype="TEXT"),
    ])


def load_roots(jsonl_paths):
    return hc.load_roots(jsonl_paths, hc.load_kernel())


def test_load_var_rgb_parses_dark_mode():
    kernel = hc.load_kernel()
    var_rgb = hc.load_var_rgb(VARS, kernel)
    assert var_rgb == {"1:1": (8, 20, 15)}, var_rgb  # 0.03*255=8, 0.08*255=20, 0.06*255=15


def test_check_literals_finds_unbound_solid_and_skips_bound():
    kernel = hc.load_kernel()
    f = node("10:1", "gallery/grid", children=[
        node("10:2", "chip", fills=[solid(1, 1, 1)]),          # literal ×1
        node("10:3", "tab", fills=["$1:1"]),                   # bound, 不算
        node("10:4", "photo", fills=[{"type": "IMAGE"}]),      # 图片, 不算
    ])
    jf = write_jsonl([f])
    findings = hc.check_literals(load_roots([jf]), kernel)
    assert len(findings) == 1 and findings[0]["id"] == "10:2", findings


def test_find_duplicates_groups_isomorphic_subtrees():
    jf = write_jsonl([
        node("20:1", "gallery/grid", children=[card("20:2")]),
        node("21:1", "search/result", children=[card("21:2")]),
        node("22:1", "unique/frame", children=[node("22:2", "solo")]),
    ])
    kernel = hc.load_kernel()
    var_rgb = hc.load_var_rgb(VARS, kernel)
    dups = hc.find_duplicates(load_roots([jf]), var_rgb, kernel, min_nodes=4, ignore=[])
    assert len(dups) == 1 and len(dups[0]["locations"]) == 2, dups


def test_find_duplicates_skips_component_instances():
    inst = card("30:2")
    inst["componentId"] = "c-1"
    inst2 = card("31:2")
    inst2["type"] = "INSTANCE"
    jf = write_jsonl([
        node("30:1", "page/a", children=[inst]),
        node("31:1", "page/b", children=[inst2]),
    ])
    kernel = hc.load_kernel()
    var_rgb = hc.load_var_rgb(VARS, kernel)
    dups = hc.find_duplicates(load_roots([jf]), var_rgb, kernel, min_nodes=4, ignore=[])
    assert dups == [], dups


def test_find_boolean_gaps_flags_dark_without_light():
    kernel = hc.load_kernel()
    bad = node("40:1", "camera/idle", children=[node("40:2", "sysbar-dark")])
    good = node("41:1", "chat/empty", children=[
        node("41:2", "sysbar-dark"), node("41:3", "sysbar-light", visible=False)])
    jf = write_jsonl([bad, good])
    gaps = hc.find_boolean_gaps(load_roots([jf]), kernel)
    assert gaps == ["camera/idle"], gaps


def test_check_catalog_missing_and_literal_drift():
    kernel = hc.load_kernel()
    comp = node("50:1", "component/top_bar", children=[node("50:2", "bg", fills=[solid(0, 0, 0)])])
    jf = write_jsonl([comp])
    comps = [
        {"name": "component/top_bar", "nodeId": "50:1", "page": "Components", "purpose": "x",
         "darkPng": "d.png", "lightPng": "l.png", "tokenBound": True, "variants": [], "rules": "r"},
        {"name": "component/ghost", "nodeId": "99:99", "page": "Components", "purpose": "x",
         "darkPng": "d.png", "lightPng": "l.png", "tokenBound": True, "variants": [], "rules": "r"},
    ]
    drift = hc.check_catalog(comps, hc.build_node_index(load_roots([jf])), kernel)
    issues = {(d["nodeId"], d["issue"].split("=")[0]) for d in drift}
    assert ("99:99", "missing") in issues and ("50:1", "literal") in issues, drift


def test_load_catalog_validates_required_fields():
    bad = write_jsonl([{"name": "x"}])  # 不是合法 catalog 结构
    try:
        hc.load_catalog(bad)
        assert False, "应抛 ValueError"
    except ValueError:
        pass


def test_main_exit_code_clean_vs_findings():
    out = tempfile.mkdtemp()
    cat = tempfile.mktemp(suffix=".json")
    json.dump({"version": 1, "updated": "2026-09-19", "components": []}, open(cat, "w"))
    clean = write_jsonl([node("60:1", "ok/frame", children=[node("60:2", "bg", fills=["$1:1"])])])
    assert hc.main(["--jsonl", clean, "--vars", VARS, "--components", cat, "--out-dir", out]) == 0
    dirty = write_jsonl([node("61:1", "bad/frame", children=[node("61:2", "bg", fills=[solid(1, 1, 1)])])])
    assert hc.main(["--jsonl", dirty, "--vars", VARS, "--components", cat, "--out-dir", out]) == 1


if __name__ == "__main__":
    import traceback
    _tests = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    _failed = 0
    for _t in _tests:
        try:
            _t()
            print(f"PASS {_t.__name__}")
        except Exception:
            _failed += 1
            print(f"FAIL {_t.__name__}")
            traceback.print_exc()
    print(f"\n{len(_tests) - _failed}/{len(_tests)} passed")
    raise SystemExit(1 if _failed else 0)
```

- [ ] **Step 2: 跑测试确认全部失败**

Run: `python3 scripts/test_ardot_health_check.py`
Expected: ImportError/AttributeError（`ardot-health-check.py` 尚不存在或函数未定义）

- [ ] **Step 3: 实现 `scripts/ardot-health-check.py`**

```python
#!/usr/bin/env python3
"""Ardot 设计健康检查: 一次 batch_read JSONL 全量扫描, 四项检测 + 报告 + 退出码。
用法:
  ardot-health-check.py --jsonl <batch_read.jsonl>... --vars <fetch_variables.jsonl> \
      --components docs/08-UI-SPECS/screens/refs/ardot/components.json \
      [--out-dir tmp/ardot-health] [--min-dup-nodes 4] [--ignore 子串]... [--ignore-file 清单.txt]
豁免: --ignore 子串与 --ignore-file(每行一个子串, 空行与 # 开头行跳过)合并生效;
  path 命中任一豁免子串的节点, 从检测 1/2/3 的判定中排除(检测 4 目录漂移不受豁免影响)。
  豁免计数写入 health.json 的 "ignored"(口径: literal=被豁免 findings 数,
  boolean_gaps=被豁免帧数, duplicate_groups=locations 全部落在豁免路径的重复组数)。
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


def check_literals(roots, kernel, ignore=(), ignored=None):
    """literal findings; path 命中豁免子串的 finding 排除并计数(经 ignored dict 回传)"""
    findings, exempt = [], 0
    for root, label in roots:
        acc = []
        kernel.walk(root, label, acc)
        for path, nd in acc:
            hit = any(s in path for s in ignore)
            for kind in ("fills", "strokes"):
                _b, _i, lits = kernel.classify_paints(nd.get(kind))
                for _p, rgb in lits:
                    if hit:
                        exempt += 1
                        continue
                    findings.append({"frame": label, "id": nd.get("id"), "path": path,
                                     "kind": kind, "rgb": list(rgb)})
    if ignored is not None:
        ignored["literal"] = exempt
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


def find_duplicates(roots, var_rgb, kernel, min_nodes, ignore, ignored=None):
    """重复组; 命中豁免子串的子树单独入 ex_groups, locations≥2 的豁免组数经 ignored 回传"""
    groups, ex_groups = {}, {}

    def visit(nd, path, record=True):
        if _is_instance(nd):
            return
        target = ex_groups if any(s in path for s in ignore) else groups
        acc = []
        kernel.walk(nd, path, acc)
        if record and len(acc) >= min_nodes:
            fp = repr(fingerprint(nd, var_rgb, kernel))
            g = target.setdefault(fp, {"locations": [], "nodes": len(acc)})
            g["locations"].append(path)
        for c in (nd.get("children") or []):
            if isinstance(c, dict):
                visit(c, path + "/" + str(c.get("name", c.get("id"))))

    for root, label in roots:
        visit(root, label, record=False)  # 顶层帧自身不入组: 整屏同构属正常, 目标是内部重复子树
    dups = [g for g in groups.values() if len(g["locations"]) >= 2]
    if ignored is not None:
        ignored["duplicate_groups"] = sum(1 for g in ex_groups.values() if len(g["locations"]) >= 2)
    return sorted(dups, key=lambda g: -g["nodes"])


def find_boolean_gaps(roots, kernel, ignore=(), ignored=None):
    """缺 sysbar-light 的帧 label; 帧 label 命中豁免子串的排除并计数(经 ignored dict 回传)"""
    gaps, exempt = [], 0
    for root, label in roots:
        acc = []
        kernel.walk(root, label, acc)
        names = {str(nd.get("name", "")) for _p, nd in acc}
        if any("sysbar-dark" in n for n in names) and not any("sysbar-light" in n for n in names):
            if any(s in label for s in ignore):
                exempt += 1
            else:
                gaps.append(label)
    if ignored is not None:
        ignored["boolean_gaps"] = exempt
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
    ap.add_argument("--ignore-file", default=None,
                    help="豁免清单文件: 每行一个 path 子串, 空行与 # 开头行跳过")
    a = ap.parse_args(argv)
    os.makedirs(a.out_dir, exist_ok=True)

    ignore = list(a.ignore)
    if a.ignore_file:
        try:
            with open(a.ignore_file) as f:
                ignore += [ln.strip() for ln in f
                           if ln.strip() and not ln.strip().startswith("#")]
        except OSError as e:
            print(f"ignore-file 错误: {e}")
            return 2

    kernel = load_kernel()
    try:
        comps = load_catalog(a.components)
    except (ValueError, KeyError, json.JSONDecodeError) as e:
        print(f"catalog 错误: {e}")
        return 2
    roots = load_roots(a.jsonl, kernel)
    var_rgb = load_var_rgb(a.vars, kernel)

    ignored = {"literal": 0, "boolean_gaps": 0, "duplicate_groups": 0}
    report = {
        "literal": check_literals(roots, kernel, ignore, ignored),
        "duplicates": find_duplicates(roots, var_rgb, kernel, a.min_dup_nodes, ignore, ignored),
        "boolean_gaps": find_boolean_gaps(roots, kernel, ignore, ignored),
        "catalog_drift": check_catalog(comps, build_node_index(roots), kernel),
    }
    unhealthy = any(report[k] for k in report)
    report["ignored"] = ignored
    json.dump(report, open(os.path.join(a.out_dir, "health.json"), "w"), ensure_ascii=False, indent=1)
    with open(os.path.join(a.out_dir, "health.md"), "w") as md:
        md.write(f"| 检测项 | 发现数 |\n|---|---|\n"
                 f"| literal 泄漏 | {len(report['literal'])} |\n"
                 f"| 组件化违规(重复组) | {len(report['duplicates'])} |\n"
                 f"| 布尔图层漏适配 | {len(report['boolean_gaps'])} |\n"
                 f"| 目录漂移 | {len(report['catalog_drift'])} |\n"
                 f"| 豁免(literal/dup组/boolean帧) | {ignored['literal']}/"
                 f"{ignored['duplicate_groups']}/{ignored['boolean_gaps']} |\n")
    print(f"literal={len(report['literal'])} dup_groups={len(report['duplicates'])} "
          f"boolean_gaps={len(report['boolean_gaps'])} drift={len(report['catalog_drift'])} "
          f"ignored={ignored['literal']}/{ignored['boolean_gaps']}/{ignored['duplicate_groups']} "
          f"-> {a.out_dir}/health.(json|md) {'UNHEALTHY' if unhealthy else 'OK'}")
    return 1 if unhealthy else 0


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 4: 跑测试确认全部通过**

Run: `python3 scripts/test_ardot_health_check.py`
Expected: `8/8 passed`

- [ ] **Step 5: Commit**

```bash
git add scripts/ardot-health-check.py scripts/test_ardot_health_check.py
git commit -m "feat(tools): ardot-health-check 四项检测——literal/组件化违规/布尔漏适配/目录漂移"
```

---

### Task 2: `components.json` 组件目录（含已有 5 件登记）

**Files:**
- Create: `docs/08-UI-SPECS/screens/refs/ardot/components.json`
- Test: `scripts/test_ardot_health_check.py`（追加 1 个测试）

**Interfaces:**
- Consumes: Task 1 的 `load_catalog` / `check_catalog`；reorg spec 已登记的组件 id（floating_tab=385:95, status_bar=385:106, system_nav_bar=385:107, provider_row=166:17, model_row=167:17，均在 Components 页）。
- Produces: Task 4 收编新组件时向同文件追加条目；预览 PNG 路径约定 `components/<name>-dark.png` / `components/<name>-light.png`（相对 `refs/ardot/`），PNG 文件在 Task 4 导出。

- [ ] **Step 1: 写失败测试（真实 catalog 走 load_catalog 校验）**

在 `scripts/test_ardot_health_check.py` 的 `if __name__` 之前追加：

```python
def test_repo_components_json_is_valid_catalog():
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                        "docs/08-UI-SPECS/screens/refs/ardot/components.json")
    comps = hc.load_catalog(os.path.abspath(path))
    assert len(comps) >= 5
    assert all(c["page"] == "Components" for c in comps)
```

Run: `python3 scripts/test_ardot_health_check.py`
Expected: FAIL（`FileNotFoundError` 或 `ValueError`）

- [ ] **Step 2: 创建 `docs/08-UI-SPECS/screens/refs/ardot/components.json`**

```json
{
  "version": 1,
  "updated": "2026-09-19",
  "components": [
    {
      "name": "component/floating_tab",
      "nodeId": "385:95",
      "page": "Components",
      "purpose": "全局悬浮胶囊底栏(相册/回忆/人物/设置四 Tab)",
      "darkPng": "components/floating_tab-dark.png",
      "lightPng": "components/floating_tab-light.png",
      "tokenBound": true,
      "variants": [],
      "rules": "所有主页面底栏一律用实例, 禁止手画底栏"
    },
    {
      "name": "component/status_bar",
      "nodeId": "385:106",
      "page": "Components",
      "purpose": "系统状态栏(时间/信号/电量), sysbar-dark/sysbar-light 布尔双图层自适应",
      "darkPng": "components/status_bar-dark.png",
      "lightPng": "components/status_bar-light.png",
      "tokenBound": true,
      "variants": ["sysbar-dark", "sysbar-light"],
      "rules": "每帧顶部必放; 勿改内部布尔图层名"
    },
    {
      "name": "component/system_nav_bar",
      "nodeId": "385:107",
      "page": "Components",
      "purpose": "系统导航条(手势条), 布尔双图层自适应",
      "darkPng": "components/system_nav_bar-dark.png",
      "lightPng": "components/system_nav_bar-light.png",
      "tokenBound": true,
      "variants": ["sysbar-dark", "sysbar-light"],
      "rules": "每帧底部必放; 勿改内部布尔图层名"
    },
    {
      "name": "component/provider_row",
      "nodeId": "166:17",
      "page": "Components",
      "purpose": "设置-模型中心 provider 列表行",
      "darkPng": "components/provider_row-dark.png",
      "lightPng": "components/provider_row-light.png",
      "tokenBound": true,
      "variants": [],
      "rules": "模型中心 provider 列表一律用实例"
    },
    {
      "name": "component/model_row",
      "nodeId": "167:17",
      "page": "Components",
      "purpose": "设置-模型中心单模型列表行(含下载态)",
      "darkPng": "components/model_row-dark.png",
      "lightPng": "components/model_row-light.png",
      "tokenBound": true,
      "variants": [],
      "rules": "模型列表一律用实例"
    }
  ]
}
```

- [ ] **Step 3: 跑测试确认通过**

Run: `python3 scripts/test_ardot_health_check.py`
Expected: `9/9 passed`

- [ ] **Step 4: Commit**

```bash
git add docs/08-UI-SPECS/screens/refs/ardot/components.json scripts/test_ardot_health_check.py
git commit -m "feat(design): components.json 组件目录 SSOT——登记既有 5 件组件"
```

---

### Task 3: `skills/ardot-design-ops/SKILL.md` 编排 Skill

**Files:**
- Create: `skills/ardot-design-ops/SKILL.md`
- Symlink: `~/.qoder-cn/skills/ardot-design-ops` → 项目内目录（用户级 skills 全为符号链接，惯例已核实）

**Interfaces:**
- Consumes: Task 1 脚本、Task 2 catalog、既有脚本（`ardot-theme-audit.py` / `ardot-light-verify.py` / `ardot-preview-mode.sh` / `export-ardot-snapshot.py` / `sync-ardot-variables.py`）。
- Produces: agent 操作 ardot 画布的统一入口；Task 4 按其 S1/S3 流程执行。

- [ ] **Step 1: 写 SKILL.md**

严格遵循 `skills/TEMPLATE.md` frontmatter 与结构，正文 < 500 行、单代码块 < 30 行：

```markdown
---
name: ardot-design-ops
description: Ardot 设计稿资产治理——新建页面/帧、token 样式变更、健康审计三场景编排,保障 Light/Dark 全域可切与组件化不腐败。Use when creating/modifying Ardot frames or components, syncing design tokens to canvas, exporting snapshots, or auditing design health (ardot, 设计稿, Light/Dark, 组件化).
version: 1.0.0
created: 2026-09-19
updated: 2026-09-19
maintainer: [RD] 全栈工程师
tags: [ardot, design-system, light-dark, ui-consistency]
---

# Ardot Design Ops

> **定位**:ardot 画布一切增删改的统一入口——组件目录先行、零 literal、事件驱动健康检查。
> **触发时机**:新建/修改 ardot 帧或组件、token 变更同步画布、快照入库、设计健康审计。

---

## 触发条件

- 用户提到 ardot / 设计稿 / 画布 / Light 模式 / Dark 模式 / 组件化
- 要在画布新建页面、帧、组件,或批量改样式
- `design-tokens.json` 变更后需同步画布并验证
- 快照入库(`export-ardot-snapshot.py`)前后

## 核心原则

1. **Dark 唯一正稿**:Light 只经帧级 variableModes 钉定预览,永不建物理副本;`PoLang Tokens` modeMap `2:0`=Dark/`79:1`=Light 勿调换。
2. **零 literal**:一切颜色绑双模 token;literal 是 Light 崩色唯一根源。
3. **先查目录、能拼不画**:新页面先读 `docs/08-UI-SPECS/screens/refs/ardot/components.json`,≥2 次复用即组件化。
4. **事件驱动必跑 health-check**:新建/改帧后、token sync 后、快照入库前,退出码必须归零。
5. **单会话串行**:严禁并发会话/并行子代理操作同一 ardot 文件(相机页曾被并发恢复)。

## 前置检查(每次必做)

- [ ] Ardot 桌面客户端已启动并打开 polang-ui-spec(fileId 715061534788814)
- [ ] 本地 MCP 可达:`POST http://127.0.0.1:50501/api/v1/mcp`
- [ ] 确认当前无其他会话在操作同一文件

## S1 新建页面/帧

1. 读 `components.json` 选可复用组件,拼装优先;页面帧内只允许组件实例 + 页面特有布局
2. batch_edit 建帧(≤25 ops/批;非当前页带 fileUrl,`:`→`%3A`),颜色一律绑 `PoLang Tokens` 变量
3. 自检:对该页 batch_read(readDepth=-1) + fetch_variables,跑健康检查:

```bash
python3 scripts/ardot-health-check.py --jsonl tmp/ardot-health/read.jsonl \
  --vars tmp/ardot-health/vars.jsonl \
  --components docs/08-UI-SPECS/screens/refs/ardot/components.json \
  --out-dir tmp/ardot-health
```

4. Light 验证(逐新帧):

```bash
scripts/ardot-preview-mode.sh <frameId> light --shot tmp/ardot-health/<name>-light.png
python3 scripts/ardot-light-verify.py --light-dir tmp/ardot-health \
  --dark-dir docs/08-UI-SPECS/screens/refs/ardot
scripts/ardot-preview-mode.sh <frameId> dark
```

5. health-check 退出码归零 + Light 判据通过后,快照入库(见 S3 第 3 步)

## S2 样式/token 变更

```bash
# 改 design-tokens.json 后:
python3 scripts/gen-design-tokens.py
python3 scripts/sync-ardot-variables.py --push
```

→ 抽 3 个代表帧(顶栏型/面板型/列表型)按 S1 第 4 步做双模导出验证 → health-check 归零 → 快照收口。
反向(画布调色回流):`sync-ardot-variables.py --pull` → gen → `--push` 零漂移收敛。

## S3 健康审计 / 快照入库

1. 全量 batch_read(逐页带 fileUrl, readDepth=-1) + fetch_variables → 跑 health-check
2. 按 health.json 修:literal→就近绑 token(色距≤24)或新增语义变量;重复组→收编组件;布尔漏→补 sysbar-light 图层;漂移→修 catalog 或修画布
3. 修复后重跑归零,快照收口:

```bash
python3 scripts/export-ardot-snapshot.py
ls docs/08-UI-SPECS/screens/refs/ardot/   # 核对陈旧 PNG → git rm
```

## 新组件入库标准(三过)

1. `ardot-health-check.py` 对该组件子树 literal=0
2. 钉 light 导出 PNG,`ardot-light-verify.py` 判据通过
3. 登记 `components.json` + 双模预览 PNG 入 `refs/ardot/components/`

## 常见陷阱

| 陷阱 | 症状 | 修复 |
|------|------|------|
| 跨页 M 移动孤儿化 | 移动后节点消失 | 用已验证 M 挂载套路;batch_edit ≤25 ops 分批 |
| batch_edit 超时(>110s) | 无响应但可能已应用 | 先 batch_read 核实再重试,勿盲目重发 |
| 非当前页节点 not found | batch_read/edit 报错 | 带 fileUrl,`:` 编码 `%3A` |
| AI 生成残留覆盖层 | 帧显示异常 | 查 `frozen_render_snapshot` 全屏图片层 |
| 实例尺寸改不动 | U 数字静默空转 | 先 U(null) 解绑;U(svg) 对尺寸无效 |
| 快照残留旧帧 PNG | git status 有幽灵文件 | 提交前 ls 核对 + git rm |
| export 冷缓存空白 | 导出 PNG 全白 | 预热重试 |
| Light 误判 | 视觉模型把 Dark 帧报成浅色 | 只用 ardot-light-verify.py 像素采样判据 |
| 并发会话互相覆盖 | 改动被恢复 | 全程单会话串行 |

## 相关文件

- [设计 spec](docs/superpowers/specs/2026-09-19-ardot-design-ops-design.md) - 本 Skill 的设计 SSOT
- [组件目录](docs/08-UI-SPECS/screens/refs/ardot/components.json) - 组件 SSOT
- [DESIGN_TOKENS_SPEC](docs/03-TECHNICAL-SPECS/DESIGN_TOKENS_SPEC.md) - token codegen 规范
- [ARDOT_MCP](.kimi-code/ARDOT_MCP.md) - MCP 工具速查与坑
- [ui-parity-guard](skills/ui-parity-guard/SKILL.md) - 双端一致性守卫(代码侧)

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0.0 | 2026-09-19 | 初始版本(三场景 + 入库标准 + 陷阱表) |
```

- [ ] **Step 2: 建用户级符号链接并验证**

```bash
ln -s /Users/guoshuai/AndroidStudioProjects/polang/skills/ardot-design-ops \
  /Users/guoshuai/.qoder-cn/skills/ardot-design-ops
ls -la /Users/guoshuai/.qoder-cn/skills/ardot-design-ops/SKILL.md
```

Expected: 符号链接存在且 SKILL.md 可读

- [ ] **Step 3: Commit**

```bash
git add skills/ardot-design-ops/SKILL.md
git commit -m "feat(skill): ardot-design-ops——三场景编排+组件入库标准+陷阱表"
```

---

### Task 4: 首批组件收编（画布操作，Ardot 客户端在线）

**前置依赖:** Task 1/2/3 完成；Ardot 客户端打开 polang-ui-spec；**全程单会话串行**。

**Files:**
- Modify: `docs/08-UI-SPECS/screens/refs/ardot/components.json`（追加条目）
- Create: `docs/08-UI-SPECS/screens/refs/ardot/components/*.png`（双模预览）
- Modify: `docs/08-UI-SPECS/screens/refs/ardot/`（快照刷新）

**Interfaces:**
- Consumes: `scripts/ardot-health-check.py`（基线与归零判定）、`scripts/ardot-preview-mode.sh`、`scripts/ardot-light-verify.py`、`scripts/export-ardot-snapshot.py`、MCP `batch_read`/`batch_edit`/`export_nodes`/`fetch_variables`。
- Produces: Components 页新增主组件（以指纹实测为准，候选：top_bar / bottom_sheet / primary_button / card / chip / badge / dialog / empty_state / search_bar / slider）；各域帧内同构子树替换为实例。

- [ ] **Step 1: 基线扫描**

逐页 batch_read（readDepth=-1，带 fileUrl）存 `tmp/ardot-health/baseline-<page>.jsonl` + fetch_variables 存 `vars.jsonl`，跑：

```bash
python3 scripts/ardot-health-check.py --jsonl tmp/ardot-health/baseline-*.jsonl \
  --vars tmp/ardot-health/vars.jsonl \
  --components docs/08-UI-SPECS/screens/refs/ardot/components.json \
  --out-dir tmp/ardot-health/baseline
```

Expected: `health.json` 中 `duplicates` 按节点数降序即收编优先级清单；存档基线备查。

- [ ] **Step 2: 逐件收编（每件一个完整子循环，按基线报告优先级）**

对每件候选组件重复以下子循环（子循环内任何一步失败先修复再推进，不带病进入下一件）：

1. 从 `health.json` duplicates 取一组同构子树，确认语义命名（如 `component/top_bar`）
2. batch_edit：在 Components 页以代表实例创建主组件（Copy 代表帧 → M 挂载到 Components 页 → 转组件），≤25 ops/批
3. 主组件零 literal 修绑（literal 经 `ardot-theme-audit.py` 就近匹配，色距 ≤24 绑既有变量，否则新增语义变量并走 S2 同步链路）
4. 各出现处同构子树替换为组件实例（Replace/Copy 实例 + 删除旧子树；每处替换后 batch_read 核实）
5. 入库三过：health-check 对该组件 literal=0 → preview-mode 钉 light 导出 → light-verify 通过 → 还原 dark
6. export_nodes 导出双模预览 PNG 到 `docs/08-UI-SPECS/screens/refs/ardot/components/`
7. 追加 `components.json` 条目（结构同 Task 2 既有条目）
8. `python3 scripts/export-ardot-snapshot.py` 刷新快照（该件收编的回滚点）

- [ ] **Step 3: 全量归零验证**

重跑 Step 1 的 health-check 命令（out-dir 换 `tmp/ardot-health/final`）。

Expected: 退出码 0；`duplicates` 仅剩 Components 页演示帧等白名单噪音（用 `--ignore` 过滤后为零）；`boolean_gaps` 为零。

- [ ] **Step 4: 快照收口与提交**

```bash
ls docs/08-UI-SPECS/screens/refs/ardot/   # 核对陈旧 PNG
git add docs/08-UI-SPECS/screens/refs/ardot/
git status --short -- docs/08-UI-SPECS/screens/refs/ardot/   # 确认无幽灵文件, 有则 git rm
git commit -m "feat(design): 首批组件收编——重复子树组件化+实例替换+catalog 登记"
```

---

### Task 5: 文档同步与收口

**Files:**
- Modify: `docs/08-UI-SPECS/README.md`
- Modify: `.kimi-code/ARDOT_MCP.md`

**Interfaces:**
- Consumes: Task 1–4 全部产物。
- Produces: 文档体系与产物一致（[DOC-SYNC] 红线）。

- [ ] **Step 1: 更新 `docs/08-UI-SPECS/README.md`**

在 ardot 相关章节追加两行索引（锚点位置：README 中提到 `refs/ardot` 快照管线的段落）：

```markdown
- 组件目录 SSOT:`refs/ardot/components.json`(新组件入库标准见 `skills/ardot-design-ops/SKILL.md`)
- 设计健康检查:`scripts/ardot-health-check.py`(literal/组件化违规/布尔漏适配/目录漂移,事件驱动必跑)
```

- [ ] **Step 2: 更新 `.kimi-code/ARDOT_MCP.md`**

在「工具调用组合」表后追加一行，并在「Token 同步工作流」节末追加一段：

```markdown
| 设计健康审计 | `scripts/ardot-health-check.py`(batch_read JSONL + fetch_variables + components.json) |
```

```markdown
## 设计资产治理（2026-09-19 起）

组件目录 SSOT = `docs/08-UI-SPECS/screens/refs/ardot/components.json`;新建页面/改帧/token sync/快照入库前必跑
`scripts/ardot-health-check.py` 归零。完整工作流见 `skills/ardot-design-ops/SKILL.md`。
```

- [ ] **Step 3: 验证文档链接**

```bash
ls docs/08-UI-SPECS/screens/refs/ardot/components.json skills/ardot-design-ops/SKILL.md scripts/ardot-health-check.py
python3 scripts/test_ardot_health_check.py
```

Expected: 三个路径均存在；测试 `9/9 passed`

- [ ] **Step 4: Commit**

```bash
git add docs/08-UI-SPECS/README.md .kimi-code/ARDOT_MCP.md
git commit -m "docs(design): ardot-design-ops 索引同步——README+ARDOT_MCP 注册健康检查与组件目录"
```

---

## Self-Review 记录

- **Spec 覆盖**:spec §2 三层 → Task 1（检测层）/Task 2（目录层）/Task 3（编排层）;§3 入库标准 → Task 3「三过」+ Task 4 子循环；§4 四项检测 → Task 1 全部实现；§5 三场景 → Task 3 S1/S2/S3;§6 坑清单 → Task 3 陷阱表；§7 交付物 → Task 1–5 全覆盖（用户级镜像 = Task 3 Step 2 符号链接）。
- **类型一致性**:`roots` 结构 `[(node, label)]` 在 `load_roots`/`build_node_index`/`check_literals`/`find_duplicates`/`find_boolean_gaps` 间一致；测试 fixture 中 `card()` 子树恰 4 节点与 `min_nodes=4` 匹配；`load_var_rgb` 返回值与 `paint_rgbs`/`find_duplicates` 消费一致。
- **已知留白（有意）**:Task 4 逐件收编的 MCP op 细节无法离线给出确切 payload，以基线报告驱动；组件实例标记（`componentId` vs `type=="instance"`）已做双兜底，若实测字段不同需调整 `_is_instance`。
