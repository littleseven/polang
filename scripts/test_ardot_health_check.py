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


def test_fingerprint_handles_mixed_fill_types_in_sibling_ties():
    """回归: 兄弟节点同 type+size、fills 混合 SOLID literal 与 IMAGE 哨兵时,
    fingerprint 的 sorted(kids) 不得在 RGB int 元组与 str 哨兵间比较(str < int TypeError)"""
    kernel = hc.load_kernel()
    var_rgb = hc.load_var_rgb(VARS, kernel)
    f = node("70:1", "gallery/grid", children=[
        node("70:2", "solid_bg", fills=[solid(0.5, 0.5, 0.5)], ntype="RECTANGLE"),
        node("70:3", "image_bg", fills=[{"type": "IMAGE"}], ntype="RECTANGLE"),
    ])
    fp = hc.fingerprint(f, var_rgb, kernel)  # 修复前此处抛 TypeError
    kids = fp[4]
    assert len(kids) == 2 and kids[0] != kids[1], kids
    assert list(kids) == sorted(kids, key=str), kids  # 两子树指纹可排序比较


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
