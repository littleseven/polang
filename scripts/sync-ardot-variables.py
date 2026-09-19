#!/usr/bin/env python3
"""
Design token 画布双向同步（design-tokens.json ↔ Ardot 画布变量）。

教义（2026-08-19 双向化起，取代旧「Ardot 只是活体预览层」单向观）：
  - design-tokens.json = SSOT：代码侧唯一事实来源，codegen（gen-design-tokens.py）只读它。
  - Ardot 画布变量（集合 "PoLang Tokens"，modes Dark/Light）= 辅助精修面：设计稿上调值后
    经 --pull 回流 SSOT，再由生成器分发到双端代码与画布。
  - 方向显式、不自动合并：--push（JSON→画布，默认）以 JSON 覆盖画布同名变量；
    --pull（画布→JSON）回写 SSOT 并自动重跑 gen-design-tokens.py 刷新生成物；
    --check（漂移门禁）只读比对两侧（值 + mode + scope），有漂移 exit 1。
  - 冲突语义：两侧同键不同值不存在自动裁决，--check 的漂移清单就是「显式选方向」的决策输入。

前置：Ardot 桌面客户端已启动并打开目标 .ardot 文件（本地 MCP 在 127.0.0.1:50501）。
注意：MCP 工具的 apply_variables/fetch_variables 只接受内联 JSON 对象；payload 有 300+ 变量，
经 agent 工具调用手抄易错，故本脚本直连本地 MCP HTTP 端点，文件内容原样上送/取回。

用法：
  python3 scripts/sync-ardot-variables.py                 # push：JSON→画布（默认，原行为）
  python3 scripts/sync-ardot-variables.py --check         # 漂移门禁：不一致 exit 1
  python3 scripts/sync-ardot-variables.py --pull          # 画布→JSON 回流 SSOT + 重跑生成器
  python3 scripts/sync-ardot-variables.py --pull --prune  # 同上，并删除 JSON 有而画布无的键
  可选：--payload PATH（push 输入，默认 build/design-tokens/ardot-variables.json）
        --endpoint URL（默认 http://127.0.0.1:50501/api/v1/mcp）

逆变换对齐：--pull/--check 的 JSON↔画布映射精确镜像 gen-design-tokens.gen_ardot_payload
（scheme/* 双 mode、color|statusColor/*、typography/role/field、其余 group/flatten 拍平名）。
"""

import argparse
import importlib.util
import json
import re
import subprocess
import sys
import urllib.request
from datetime import date
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
GEN_SCRIPT = PROJECT_ROOT / "scripts" / "gen-design-tokens.py"
TOKENS_JSON = PROJECT_ROOT / "shared/src/commonMain/resources/design-tokens.json"
SET_NAME = "PoLang Tokens"
MODES = ("Dark", "Light")

DEFAULT_ENDPOINT = "http://127.0.0.1:50501/api/v1/mcp"
DEFAULT_PAYLOAD = "build/design-tokens/ardot-variables.json"

# 变量 kind（JSON 侧语义）→ Ardot type
KIND_TO_TYPE = {"number": "FLOAT", "color": "COLOR", "weight": "STRING"}


# ── MCP 直连 ──────────────────────────────────────────────────────────────────

def rpc(endpoint, method, params=None, rid=1, sid=None):
    body = {"jsonrpc": "2.0", "id": rid, "method": method}
    if params is not None:
        body["params"] = params
    headers = {"Content-Type": "application/json",
               "Accept": "application/json, text/event-stream"}
    if sid:
        headers["Mcp-Session-Id"] = sid
    req = urllib.request.Request(endpoint, data=json.dumps(body).encode(), headers=headers)
    resp = urllib.request.urlopen(req, timeout=120)
    sid_out = resp.headers.get("Mcp-Session-Id", sid)
    raw = resp.read().decode()
    for line in raw.splitlines():
        if line.startswith("data:"):
            return json.loads(line[5:].strip()), sid_out
    return (json.loads(raw) if raw else {}), sid_out


def mcp_connect(endpoint):
    """initialize + initialized 通知 → session id。"""
    _, sid = rpc(endpoint, "initialize", {
        "protocolVersion": "2024-11-05",
        "capabilities": {},
        "clientInfo": {"name": "sync-ardot-variables", "version": "2.0"},
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
        pass  # notification 无响应属正常
    return sid


# fetch_variables 大结果缓存路径正则（允许文件名含空格，
# 如 ".../fetch_variables-PoLang Tokens_5-xxxx.jsonl"，不跨行）
CACHE_PATH_RE = r"(/[^\n\"']*\.ardot/reads/[^\n\"']+?\.jsonl)"


def parse_fetch_variables(text):
    """fetch_variables 响应文本 → 内联响应同构 dict。

    MCP 大结果（当前 947 变量约 225KB 即触发）不落内联 JSON，响应文本只给
    $TMPDIR/.ardot/reads/*.jsonl 路径（缓存路径正则回退，同 scripts/ardot-scan.py）：
    JSONL 首行 _meta，随后每个集合一行 {"_variableSet": {...}}，其后该集合变量逐行
    （带 setId/collection 字段，集合头恒在其变量之前）。在此重组为
    {"success": True, "data": {"variableSets": [...]}}，与内联响应结构一致。
    """
    m = re.search(CACHE_PATH_RE, text)
    if not m:
        return json.loads(text)
    sets, by_id = [], {}
    with open(m.group(1), encoding="utf-8") as f:
        for ln in f:
            row = json.loads(ln)
            if "_meta" in row:
                continue
            if "_variableSet" in row:
                s = dict(row["_variableSet"])
                s["variables"] = []
                sets.append(s)
                by_id[s.get("id")] = s
            else:
                s = by_id.get(row.get("setId"))
                if s is not None:
                    s["variables"].append(row)
    return {"success": True, "data": {"variableSets": sets}}


def fetch_canvas(endpoint):
    """取画布 "PoLang Tokens" 集合 → {变量全名: {type, scopes, valuesByMode(键=mode 名)}}。

    mode 归一：集合 modes 列表给出 id→name（如 2:0→Dark、79:1→Light），
    valuesByMode 键在此统一从 id 翻译成 name。
    """
    sid = mcp_connect(endpoint)
    result, _ = rpc(endpoint, "tools/call", {
        "name": "fetch_variables",
        "arguments": {},
    }, rid=2, sid=sid)
    text = result["result"]["content"][0]["text"]
    data = parse_fetch_variables(text)
    if not data.get("success"):
        print(f"❌ fetch_variables 失败: {text}")
        sys.exit(1)
    sets = data["data"]["variableSets"]
    target = next((s for s in sets if s.get("name") == SET_NAME), None)
    if target is None:
        print(f"❌ 画布上未找到变量集合 {SET_NAME!r}（现有集合: {[s.get('name') for s in sets]}）")
        sys.exit(1)
    id2name = {m["id"]: m["name"] for m in target.get("modes", [])}
    unknown = set(id2name.values()) - set(MODES)
    if unknown:
        print(f"❌ 集合 modes 含非预期名 {sorted(unknown)}（期望 {MODES}），拒绝同步")
        sys.exit(1)
    canvas = {}
    for v in target.get("variables", []):
        canvas[v["name"]] = {
            "type": v.get("type"),
            "scopes": v.get("scopes"),
            "valuesByMode": {id2name.get(k, k): x for k, x in v.get("valuesByMode", {}).items()},
        }
    return canvas


# ── 归一化（float32 精度 / 颜色往返）───────────────────────────────────────────

def norm_number(v):
    """数值归一：round(v,4) 去尾零（画布 float32 的 1.600000023841858 → 1.6）；整数值转 int。"""
    n = round(float(v), 4)
    i = int(n)
    return i if n == i else n


def norm_color(c):
    return {ch: round(float(c[ch]), 4) for ch in ("r", "g", "b", "a")}


def color_to_hex(c):
    """画布 rgb 0-1 浮点 → #AARRGGBB 大写（8bit 通道，与 JSON 书写一致）。"""
    def ch(x):
        return max(0, min(255, round(float(x) * 255)))
    return f"#{ch(c['a']):02X}{ch(c['r']):02X}{ch(c['g']):02X}{ch(c['b']):02X}"


def canvas_single_value(cv):
    """单值变量取画布值 → (raw, warning)。Dark/Light 分叉时报警并取 Dark（Ardot 默认 mode）。"""
    vbm = cv["valuesByMode"]
    dark, light = vbm.get("Dark"), vbm.get("Light")
    if dark is None and light is None:
        return None, "画布无任何 mode 值，跳过"
    if dark is not None and light is not None and dark != light:
        return dark, f"画布 Dark/Light 值分叉（Dark={dark} / Light={light}），取 Dark"
    return (dark if dark is not None else light), None


# ── 正向索引（与 gen_ardot_payload 一一对应）──────────────────────────────────

def load_gen():
    """importlib 加载 gen-design-tokens.py（文件名带连字符，不可直接 import）。"""
    spec = importlib.util.spec_from_file_location("gen_design_tokens", GEN_SCRIPT)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def flatten_paths(gen, group, data):
    """与 gen.flatten 同构（复用其 classify 保证语义一致），额外携带 JSON 内取值路径。

    返回 [(name, kind, value, path)]；kind 为 classify 原值（size/int/ms/float/color/weight/array）。
    """
    out = []
    for key, value in data.items():
        kind, *rest = gen.classify(group, key, value)
        if kind == "nested":
            for child_key, child_value in rest[0].items():
                if child_key.startswith("_"):
                    continue
                ck = key + child_key[0].upper() + child_key[1:]
                ckind, *crest = gen.classify(group, ck, child_value)
                if ckind in ("size", "int", "ms", "float", "color", "weight"):
                    out.append((ck, ckind, crest[0], [key, child_key]))
        elif kind in ("size", "int", "ms", "float", "color", "weight", "array"):
            out.append((key, kind, rest[0] if rest else None, [key]))
    return out


def build_setters(gen, tokens):
    """画布变量全名 → JSON 写回目标（group + 组内路径 + kind），镜像 gen_ardot_payload 正向。

    - scheme/<key>：modes=True，Dark/Light 双值分别写 colorScheme.dark/.light
    - color/<key>、statusColor/<key>：组内同名键
    - typography/<role>/<field>：size/lineHeight/letterSpacing(FLOAT) + weight(STRING)
    - 其余 group/name：flatten 拍平名（嵌套 dict 已展开为路径）
    """
    setters = {}
    for key in tokens["colorScheme"]["light"]:
        setters[f"scheme/{key}"] = {"kind": "color", "group": "colorScheme", "path": [key], "modes": True}
    for group in ("color", "statusColor"):
        for key in tokens[group]:
            if key.startswith("_"):
                continue
            setters[f"{group}/{key}"] = {"kind": "color", "group": group, "path": [key], "modes": False}
    for group, data in tokens.items():
        if group.startswith("_") or group in ("color", "statusColor", "colorScheme", "typography"):
            continue
        for name, kind, _value, path in flatten_paths(gen, group, data):
            if kind in ("size", "int", "ms", "float"):
                setters[f"{group}/{name}"] = {"kind": "number", "group": group, "path": path, "modes": False}
            elif kind == "color":
                setters[f"{group}/{name}"] = {"kind": "color", "group": group, "path": path, "modes": False}
            elif kind == "weight":
                setters[f"{group}/{name}"] = {"kind": "weight", "group": group, "path": path, "modes": False}
    for role, spec in tokens["typography"].items():
        if role.startswith("_"):
            continue
        for field in ("size", "lineHeight", "letterSpacing"):
            setters[f"typography/{role}/{field}"] = {
                "kind": "number", "group": "typography", "path": [role, field], "modes": False}
        setters[f"typography/{role}/weight"] = {
            "kind": "weight", "group": "typography", "path": [role, "weight"], "modes": False}
    return setters


def json_get(tokens, group, path):
    node = tokens[group]
    for p in path:
        node = node[p]
    return node


def json_set(tokens, group, path, value):
    node = tokens[group]
    for p in path[:-1]:
        node = node.setdefault(p, {})
    node[path[-1]] = value


def values_equal(gen, kind, old, new_raw):
    """JSON 旧值 vs 画布新值（归一后比较，吸收 float32 精度差）。"""
    if kind == "number":
        return isinstance(old, (int, float)) and not isinstance(old, bool) \
            and norm_number(old) == norm_number(new_raw)
    if kind == "color":
        return norm_color(gen.ardot_color(old)) == norm_color(new_raw)
    return old == new_raw  # weight 字符串


# ── --push（默认，原行为）─────────────────────────────────────────────────────

def do_push(endpoint, payload_path):
    payload = json.load(open(payload_path, encoding="utf-8"))
    sid = mcp_connect(endpoint)
    result, _ = rpc(endpoint, "tools/call", {
        "name": "apply_variables",
        "arguments": {"variables": payload},
    }, rid=2, sid=sid)
    text = result["result"]["content"][0]["text"]
    data = json.loads(text)
    if not data.get("success"):
        print(f"❌ apply_variables 失败: {text}")
        sys.exit(1)
    d = data["data"]
    print(f"✅ Ardot 变量同步完成: created={d['created']} updated={d['updated']} deleted={d['deleted']}")
    print("提示：在 Ardot 画布确认预览后，可用 capture_screenshot 截图留档（PR 视觉 diff）。")


# ── --pull（画布→JSON 回流 SSOT）──────────────────────────────────────────────

def canvas_value_of_type(cname, cv, warnings):
    """按画布 type 归一取单值 → (value or None)。COLOR→hex / FLOAT→number / STRING→原样。"""
    raw, warn = canvas_single_value(cv)
    if warn:
        warnings.append(f"{cname}: {warn}")
    if raw is None:
        return None
    if cv["type"] == "COLOR":
        return color_to_hex(raw)
    if cv["type"] == "FLOAT":
        return norm_number(raw)
    return raw


def insert_new_key(tokens, cname, cv, warnings):
    """画布有、JSON 无的键 → 归入对应域。返回 JSON 点位描述（失败返回 None）。"""
    parts = cname.split("/")
    domain, rest = parts[0], parts[1:]
    if not rest:
        warnings.append(f"NEW 键 {cname} 路径不足两段，无法归域，跳过")
        return None
    if domain == "typography":
        if len(rest) != 2 or cv["type"] not in ("FLOAT", "STRING"):
            warnings.append(f"NEW 键 {cname} 不符合 typography/role/field 形态，跳过")
            return None
        role, field = rest
        value = canvas_value_of_type(cname, cv, warnings)
        if value is None:
            return None
        tokens["typography"].setdefault(role, {})[field] = value
        return f"typography.{role}.{field}"
    if domain == "scheme":
        if cv["type"] != "COLOR":
            warnings.append(f"NEW 键 {cname} 非 COLOR，无法入 colorScheme，跳过")
            return None
        key = "/".join(rest)
        for mode in MODES:
            raw = cv["valuesByMode"].get(mode)
            if raw is None:
                warnings.append(f"{cname}: 画布缺 {mode} 值")
                continue
            tokens["colorScheme"][mode.lower()][key] = color_to_hex(raw)
        return f"colorScheme.dark/.light.{key}"
    # 通用域：group/key 平铺（含画布新造的整个 group）
    if cv["type"] not in KIND_TO_TYPE.values():
        warnings.append(f"NEW 键 {cname} 类型 {cv['type']} 未知，跳过")
        return None
    key = "/".join(rest)
    value = canvas_value_of_type(cname, cv, warnings)
    if value is None:
        return None
    tokens.setdefault(domain, {})[key] = value
    return f"{domain}.{key}"


def delete_setter(tokens, st):
    """--prune：按 setter 路径删 JSON 键；嵌套父 dict 清空后一并删除。

    scheme/* 特殊：值实际住在 colorScheme.dark/.light 两个子 dict，需双侧删。
    """
    group, path = st["group"], st["path"]
    if st["modes"]:  # scheme/*
        key = path[0]
        for mode in MODES:
            tokens["colorScheme"][mode.lower()].pop(key, None)
        return
    node = tokens[group]
    for p in path[:-1]:
        node = node.get(p) if isinstance(node, dict) else None
        if node is None:
            return
    if isinstance(node, dict):
        node.pop(path[-1], None)
    if len(path) >= 2:
        parent = tokens[group]
        for p in path[:-2]:
            parent = parent[p]
        sub = parent.get(path[-2])
        if isinstance(sub, dict) and not [k for k in sub if not k.startswith("_")]:
            del parent[path[-2]]


def do_pull(endpoint, prune):
    gen = load_gen()
    tokens = json.loads(TOKENS_JSON.read_text(encoding="utf-8"))
    canvas = fetch_canvas(endpoint)
    setters = build_setters(gen, tokens)

    changed, new_keys, missing, type_mismatch, warnings = [], [], [], [], []

    for cname in sorted(canvas):
        cv = canvas[cname]
        if cname not in setters:
            continue  # NEW 键统一在后面处理（此时 setters 已建好）
        st = setters[cname]
        if cv["type"] != KIND_TO_TYPE[st["kind"]]:
            type_mismatch.append(f"{cname}: JSON 侧 {KIND_TO_TYPE[st['kind']]} vs 画布 {cv['type']}，跳过")
            continue
        if st["modes"]:  # scheme/*：Dark/Light 分别写 colorScheme.dark/.light
            key = st["path"][0]
            for mode in MODES:
                raw = cv["valuesByMode"].get(mode)
                if raw is None:
                    warnings.append(f"{cname}: 画布缺 {mode} 值")
                    continue
                new_hex = color_to_hex(raw)
                sub = tokens["colorScheme"][mode.lower()]
                if key in sub and values_equal(gen, "color", sub[key], raw):
                    continue
                old_hex = sub.get(key, "<无>")
                sub[key] = new_hex
                changed.append(f"  {cname}[{mode}]: {old_hex} → {new_hex}")
        else:
            raw, warn = canvas_single_value(cv)
            if warn:
                warnings.append(f"{cname}: {warn}")
            if raw is None:
                continue
            old = json_get(tokens, st["group"], st["path"])
            if values_equal(gen, st["kind"], old, raw):
                continue
            if st["kind"] == "number":
                value = norm_number(raw)
            elif st["kind"] == "color":
                value = color_to_hex(raw)
            else:
                value = raw
            json_set(tokens, st["group"], st["path"], value)
            changed.append(f"  {cname}: {old} → {value}")

    missing = [name for name in sorted(setters) if name not in canvas]
    for cname in [name for name in sorted(canvas) if name not in setters]:
        spot = insert_new_key(tokens, cname, canvas[cname], warnings)
        if spot:
            new_keys.append(f"  {cname} → {spot}")

    pruned = []
    if prune and missing:
        for name in missing:
            delete_setter(tokens, setters[name])
            pruned.append(f"  {name}（JSON 点位 {setters[name]['group']}.{'.'.join(setters[name]['path'])}）")
        # 整组变量被清空（只剩 _ 前缀键）的顶级 group 一并删除
        for group in [g for g in tokens if not g.startswith("_")]:
            sub = tokens[group]
            if isinstance(sub, dict) and not [k for k in sub if not k.startswith("_")]:
                if group == "colorScheme":
                    continue  # 结构组（dark/light 子 dict），不按此规则删
                del tokens[group]
                pruned.append(f"  （组级清理）{group}")

    tokens["_updated"] = date.today().isoformat()
    TOKENS_JSON.write_text(json.dumps(tokens, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    try:
        shown = TOKENS_JSON.relative_to(PROJECT_ROOT)
    except ValueError:
        shown = TOKENS_JSON
    print(f"✅ 已回写 {shown}（_updated={tokens['_updated']}）")

    print(f"\n值变更 OLD→NEW（{len(changed)}）:")
    print("\n".join(changed) if changed else "  （无）")
    print(f"\nNEW（画布有 JSON 无，已归入对应域）（{len(new_keys)}）:")
    print("\n".join(new_keys) if new_keys else "  （无）")
    print(f"\nMISSING（JSON 有画布无，保留未动；确认后 --pull --prune 才删）（{len(missing)}）:")
    if missing:
        print("\n".join(f"  {n}" for n in missing))
    else:
        print("  （无）")
    if pruned:
        print(f"\nPRUNED（--prune 已从 JSON 删除）（{len(pruned)}）:")
        print("\n".join(pruned))
    if type_mismatch:
        print(f"\n类型不匹配（跳过）（{len(type_mismatch)}）:")
        print("\n".join(f"  {t}" for t in type_mismatch))
    if warnings:
        print(f"\n警告（{len(warnings)}）:")
        print("\n".join(f"  {w}" for w in warnings))

    # 回流必须落到代码侧生成物才算数
    print("\n重跑 gen-design-tokens.py 刷新生成物 …")
    ret = subprocess.run([sys.executable, str(GEN_SCRIPT)])
    if ret.returncode != 0:
        print("❌ gen-design-tokens.py 失败，生成物未刷新（JSON 已回写）")
        sys.exit(1)
    print("✅ 生成物已刷新，回流完成")


# ── --check（只读漂移门禁）────────────────────────────────────────────────────

def norm_payload_value(vtype, v):
    if vtype == "COLOR":
        return norm_color(v)
    if isinstance(v, float):
        return norm_number(v)
    return v


def fmt_side(vtype, v):
    if vtype == "COLOR":
        return color_to_hex(v)
    if vtype == "FLOAT":
        return repr(norm_number(v))
    return repr(v)


def do_check(endpoint):
    gen = load_gen()
    tokens = json.loads(TOKENS_JSON.read_text(encoding="utf-8"))
    payload = gen.gen_ardot_payload(tokens)["PoLang Tokens"]["variables"]
    canvas = fetch_canvas(endpoint)

    d_value, d_scope, d_type, d_mode, missing, news = [], [], [], [], [], []

    for name in sorted(set(payload) & set(canvas)):
        p, c = payload[name], canvas[name]
        if p["type"] != c["type"]:
            d_type.append(f"  {name}: JSON={p['type']} vs 画布={c['type']}")
            continue
        if "valuesByMode" in p:  # scheme/*：逐 mode 比对
            for mode, pv in p["valuesByMode"].items():
                if mode not in c["valuesByMode"]:
                    d_mode.append(f"  {name}: 画布缺 {mode} 值")
                    continue
                cv = c["valuesByMode"][mode]
                if norm_payload_value(p["type"], pv) != norm_payload_value(c["type"], cv):
                    d_value.append(f"  {name}[{mode}]: JSON={fmt_side(p['type'], pv)} "
                                   f"vs 画布={fmt_side(c['type'], cv)}")
        else:  # 单值：画布两 mode 应一致且等于 JSON
            pv = norm_payload_value(p["type"], p["value"])
            dark, light = c["valuesByMode"].get("Dark"), c["valuesByMode"].get("Light")
            if dark is None and light is None:
                d_mode.append(f"  {name}: 画布无任何 mode 值")
            elif dark != light:
                d_value.append(f"  {name}: 画布两 mode 分叉 Dark={fmt_side(c['type'], dark)} "
                               f"Light={fmt_side(c['type'], light)}（JSON={fmt_side(p['type'], p['value'])}）")
            else:
                cv = norm_payload_value(c["type"], dark if dark is not None else light)
                if pv != cv:
                    d_value.append(f"  {name}: JSON={fmt_side(p['type'], p['value'])} "
                                   f"vs 画布={fmt_side(c['type'], dark)}")
        if p.get("scopes") != c.get("scopes"):
            d_scope.append(f"  {name}: JSON={p.get('scopes')} vs 画布={c.get('scopes')}")

    missing = [name for name in sorted(set(payload) - set(canvas))]
    news = [name for name in sorted(set(canvas) - set(payload))]

    total_drift = len(d_value) + len(d_scope) + len(d_type) + len(d_mode) + len(missing) + len(news)
    print(f"漂移门禁：JSON payload {len(payload)} 个变量 vs 画布 {len(canvas)} 个变量\n")
    print("漏斗：")
    print(f"  JSON payload 变量          {len(payload)}")
    print(f"  ├─ 画布存在（比对成功）      {len(set(payload) & set(canvas))}")
    print(f"  │   ├─ 值漂移               {len(d_value)}")
    print(f"  │   ├─ scope 漂移           {len(d_scope)}")
    print(f"  │   ├─ 类型漂移             {len(d_type)}")
    print(f"  │   └─ mode 缺失/分叉       {len(d_mode)}")
    print(f"  └─ 画布缺失（MISSING→push） {len(missing)}")
    print(f"  画布独有（NEW→--pull）      {len(news)}")
    if d_value:
        print(f"\n值漂移（{len(d_value)}）:")
        print("\n".join(d_value))
    if d_scope:
        print(f"\nscope 漂移（{len(d_scope)}）:")
        print("\n".join(d_scope))
    if d_type:
        print(f"\n类型漂移（{len(d_type)}）:")
        print("\n".join(d_type))
    if d_mode:
        print(f"\nmode 问题（{len(d_mode)}）:")
        print("\n".join(d_mode))
    if missing:
        print(f"\nMISSING（JSON 有画布无；--push 或 --pull --prune 二选一）（{len(missing)}）:")
        print("\n".join(f"  {n}" for n in missing))
    if news:
        print(f"\nNEW（画布有 JSON 无；--pull 回流）（{len(news)}）:")
        print("\n".join(f"  {n}" for n in news))

    if total_drift:
        print(f"\n❌ 检出漂移 {total_drift} 项（显式选方向：--push 以 JSON 为准 / --pull 以画布为准）")
        sys.exit(1)
    print("\n✅ JSON 与画布一致（值 + mode + scope）")


def main():
    ap = argparse.ArgumentParser(description="design-tokens.json ↔ Ardot 画布变量双向同步")
    ap.add_argument("--payload", default=DEFAULT_PAYLOAD, help="push 输入 payload 文件路径")
    ap.add_argument("--endpoint", default=DEFAULT_ENDPOINT, help="Ardot 本地 MCP 端点")
    ap.add_argument("--pull", action="store_true", help="画布→JSON 回流 SSOT（并重跑 gen-design-tokens.py）")
    ap.add_argument("--check", action="store_true", help="只读漂移门禁，不一致 exit 1")
    ap.add_argument("--prune", action="store_true", help="配合 --pull：删除 JSON 有而画布无的键")
    args = ap.parse_args()

    if args.check and args.pull:
        print("❌ --check 与 --pull 互斥（check 只读不写）")
        sys.exit(2)
    if args.check:
        do_check(args.endpoint)
    elif args.pull:
        do_pull(args.endpoint, args.prune)
    else:
        do_push(args.endpoint, args.payload)


if __name__ == "__main__":
    main()
