#!/usr/bin/env bash
# Ardot 帧级主题预览切换：把指定帧（及其子树）切到 Light/Dark/Auto 模式预览。
#
# 原理：画布颜色几乎全部绑 PoLang Tokens 变量（scheme/* 双 mode），
# 帧根挂 variableModes override 即整树换主题——零复制、零改色、可随时还原。
# ⚠️ 仅影响该帧的变量解析（预览用）；refs 快照/双端代码不受影响。
#
# 用法：
#   scripts/ardot-preview-mode.sh <frameId|pageId> <light|dark|auto> [--lang en|zh|auto] [--shot out.png]
#   页根 id（manifest pages[].id，如 103:1/111:319）= 整页逐帧切换（PAGE 节点无 variableModes，
#   引擎不支持页级 override；2026-09-13 起脚本按 manifest 帧清单展开逐帧写入）
#   scripts/ardot-preview-mode.sh 105:45 dark --lang zh --shot /tmp/x.png  # 深色+中文预览
#   scripts/ardot-preview-mode.sh <frameId> light   # 切浅色预览
#   scripts/ardot-preview-mode.sh <frameId> dark    # 切深色预览
#   scripts/ardot-preview-mode.sh <frameId> auto    # 还原主题（写回默认 Dark）；--lang auto 同理写回 English
#   scripts/ardot-preview-mode.sh <frameId> light --shot /tmp/x.png  # 切换并截图
#   scripts/ardot-preview-mode.sh 267:21 light --lang zh   # 整页(Organize)浅色+中文
#   scripts/ardot-preview-mode.sh 267:21 auto --lang auto  # 整页还原 Dark+English
#
# 常用帧 id（docs/08-UI-SPECS/screens/refs/ardot/manifest.json 可查全量）：
#   settings/main_list=108:94  gallery/grid=105:45  chat/conversation=111:383
#   editor/concept_a_hypic=118:243  camera/idle=118:1146
set -euo pipefail

# --lang 可出现在任意位置：先摘出，剩余参数再按 frame mode --shot 顺序解析
LANG_MODE=""
args=()
while [ $# -gt 0 ]; do
  case "$1" in
    --lang) LANG_MODE="${2:?--lang 需 en|zh|auto}"; shift 2 ;;
    *) args+=("$1"); shift ;;
  esac
done
# bash 3.2 下空数组 + set -u 会报 unbound variable，用 ${a[@]+...} 惯用法兜底
set -- ${args[@]+"${args[@]}"}

FRAME="${1:?用法: ardot-preview-mode.sh <frameId> <light|dark|auto> [--lang en|zh|auto] [--shot out.png]}"
MODE="${2:?light|dark|auto}"
shift 2 || true
SHOT=""
if [ "${1:-}" = "--shot" ]; then SHOT="${2:?--shot 需要输出路径}"; fi

# 页根 = 整页模式：PAGE 节点无 variableModes（Task 8 实证），改为按 manifest 展开该页全部帧逐帧写入。
# FRAME_IDS 为待写帧 id 空格串（单帧时即原参）；非页根原样透传。--shot 页模式下取该页首帧截图。
MANIFEST="$(cd "$(dirname "$0")/.." && pwd)/docs/08-UI-SPECS/screens/refs/ardot/manifest.json"
FRAME_IDS="$(/usr/bin/python3 - "$FRAME" "$MANIFEST" <<'GUARD'
import json, re, sys
frame, manifest = sys.argv[1], sys.argv[2]
pages = []
try:
    with open(manifest) as fh:
        pages = json.load(fh).get('pages', [])
except Exception:
    pass
page = next((p for p in pages if str(p.get('id')) == frame), None)
if page is not None:
    ids = [str(f.get('id')) for f in (page.get('frames') or []) if f.get('id')]
    print(' '.join(ids) if ids else frame)
elif not pages and re.fullmatch(r'\d+:1', frame):
    # manifest 不可读：无法展开页帧清单，保守降级为单帧（页根会被引擎忽略）
    print(frame)
else:
    print(frame)
GUARD
)"
# --shot 的截图目标：页模式取首帧（页级整页截图引擎不支持）
SHOT_FRAME="${FRAME_IDS%% *}"
# 页模式必须带 fileUrl?node_id=<页id>（裸调用按编辑器当前页解析节点，跨页全 not found）
PAGE_FILEURL=""
if [ "$FRAME_IDS" != "$FRAME" ]; then
  FILE_ID="$(/usr/bin/python3 -c "import json;print(json.load(open('$MANIFEST'))['file']['id'])" 2>/dev/null || true)"
  if [ -n "$FILE_ID" ]; then
    PAGE_FILEURL="cocraft://localhost/file/${FILE_ID}?node_id=$(printf '%s' "$FRAME" | sed 's/:/%3A/')"
  fi
fi

SET_ID="2:2"; DARK_ID="2:0"; LIGHT_ID="79:1"; ENDPOINT="http://127.0.0.1:50501/api/v1/mcp"
# UI Language 变量集（探针实测 id，台账: docs/08-UI-SPECS/screens/lang/probe-record.md §1；--lang 未传时不触碰该 override）
LANG_SET_ID="182:133"
LANG_EN_ID="182:132"
LANG_ZH_ID="182:134"

entries=""
case "$MODE" in
  light) entries="{\"variableSetId\":\"$SET_ID\",\"modeId\":\"$LIGHT_ID\"}" ;;
  dark)  entries="{\"variableSetId\":\"$SET_ID\",\"modeId\":\"$DARK_ID\"}" ;;
  # null/[] 是静默 no-op（probe-record §5 实证），还原=显式写回默认 Dark
  auto)  entries="{\"variableSetId\":\"$SET_ID\",\"modeId\":\"$DARK_ID\"}" ;;
  *) echo "模式须为 light|dark|auto"; exit 2 ;;
esac
case "${LANG_MODE:-}" in
  en)  entries="${entries:+$entries,}{\"variableSetId\":\"$LANG_SET_ID\",\"modeId\":\"$LANG_EN_ID\"}" ;;
  zh)  entries="${entries:+$entries,}{\"variableSetId\":\"$LANG_SET_ID\",\"modeId\":\"$LANG_ZH_ID\"}" ;;
  # --lang auto 还原=写回 English 默认 mode（同上，null 无效）
  auto) entries="${entries:+$entries,}{\"variableSetId\":\"$LANG_SET_ID\",\"modeId\":\"$LANG_EN_ID\"}" ;;
  "")  : ;;  # 未传 --lang：不触碰 lang override
  *) echo "--lang 须为 en|zh|auto"; exit 2 ;;
esac
MODE_JSON="[$entries]"

/usr/bin/python3 - "$FRAME_IDS" "$MODE_JSON" "$ENDPOINT" "$SHOT" "$SHOT_FRAME" "$PAGE_FILEURL" <<'PYEOF'
import glob, json, os, sys, time, urllib.request
frame_ids, mode_json, endpoint, shot, shot_frame, page_fileurl = (
    sys.argv[1].split(), sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5], sys.argv[6])

# SSE 解析对齐 ardot-lang-driver.rpc 健壮版：
# 1) 每次调用独立递增 id（initialize=1、batch_edit=2、screenshot=3…），响应按 id 回显匹配——
#    固定 id:1 时通知行/错配响应会被当 tools/call 结果（OK 判定永假 + 偶发 operations:[] 空应用的共同根源）；
# 2) 遍历所有 data: 行，只认带 result/error 顶层键且 id 匹配的那条，跳过 endpoint/event 通知行；
# 3) 传输失败（适配器闪断）30/60/90s 重试。
_rpc_seq = 0

def rpc(method, params, retry=(30, 60, 90)):
    global _rpc_seq
    _rpc_seq += 1
    rid = _rpc_seq
    for attempt, wait in enumerate([0] + list(retry)):
        if wait:
            time.sleep(wait)
        body = {"jsonrpc":"2.0","id":rid,"method":method,**({"params":params} if params else {})}
        req = urllib.request.Request(endpoint, data=json.dumps(body).encode(),
            headers={'Content-Type':'application/json','Accept':'application/json, text/event-stream'})
        try:
            raw = urllib.request.urlopen(req, timeout=120).read().decode()
        except Exception as e:
            print(f"rpc {method} attempt{attempt} transport-fail {e}", file=sys.stderr)
            continue
        parsed = []
        for line in raw.splitlines():
            if line.startswith('data:'):
                try:
                    parsed.append(json.loads(line[5:]))
                except ValueError:
                    pass
        response = next((x for x in parsed if ('result' in x or 'error' in x)
                         and ('id' not in x or str(x.get('id')) == str(rid))), None)
        if response is None:
            print(f"rpc {method} attempt{attempt} no-response-line: {raw[:200]}", file=sys.stderr)
            continue
        if response.get('error'):
            print(f"rpc {method} MCP error: {json.dumps(response['error'])[:400]}", file=sys.stderr)
            sys.exit(1)
        if response.get('result', {}).get('isError'):
            print(f"rpc {method} isError: {json.dumps(response)[:400]}", file=sys.stderr)
            sys.exit(1)
        return response
    print(f"rpc {method} exhausted retries", file=sys.stderr)
    sys.exit(1)

rpc("initialize", {"protocolVersion":"2024-11-05","capabilities":{},
    "clientInfo":{"name":"ardot-preview-mode","version":"1.2"}})

# 页模式=逐帧写入（≤25/批，批间不sleep，引擎顺序执行）；单帧即一批
for i in range(0, len(frame_ids), 25):
    chunk = frame_ids[i:i+25]
    ops = '\n'.join(f'U("{f}", {{variableModes: {mode_json}}})' for f in chunk)
    args = {"operations": ops}
    if page_fileurl:
        args["fileUrl"] = page_fileurl
    r = rpc("tools/call", {"name":"batch_edit","arguments": args})
    # 判定须基于 result.content[0].text（真实文本，已解转义一层）；
    # 对整个 response json.dumps 会把内层 JSON 二次转义成 \"success\":true，子串永不匹配（旧版 OK 永假的根因）
    content = r.get('result', {}).get('content', [])
    body = content[0].get('text', '') if content else ''
    try:
        data = json.loads(body)
    except ValueError:
        data = None
    items = None
    if isinstance(data, list):
        items = data
    elif isinstance(data, dict):
        # 实测响应形如 {"success":true,"data":{"operations":[...]}}——operations 嵌在 data 键下，须多挖一层
        probes = [data]
        inner = data.get('data')
        if isinstance(inner, dict):
            probes.append(inner)
        items = next((v for probe in probes
                      for v in (probe.get('results'), probe.get('operations'))
                      if isinstance(v, list)), None)
    if items is not None:
        fails = [x for x in items if isinstance(x, dict) and
                 (x.get('error') or x.get('success') is False or x.get('status') == 'failed')]
        ok = not fails
        detail = f"ops={len(items)} failed={len(fails)}"
        if fails:
            detail += ' ' + json.dumps(fails, ensure_ascii=False)[:400]
    else:
        ok = '"success"' in body
        detail = body[:120]
    print(("OK " if ok else "FAIL ") + detail)
    if not ok:
        sys.exit(1)
if len(frame_ids) > 1:
    print(f"PAGE-DONE frames={len(frame_ids)} batches={(len(frame_ids)+24)//25}")

if shot:
    os.makedirs('/tmp/ardot-mode-shot', exist_ok=True)
    # 记录调用前已有文件，只接受本次新生成的截图——防止误取历史残留文件
    # ⚠️ 带 fileUrl 时实际落盘多一层 {fileId}/ 子目录（2026-09-13 实证），两种路径都要扫
    stem = f"screenshot-{shot_frame.replace(':','_')}-*.png"
    pats = [f"/tmp/ardot-mode-shot/{stem}", f"/tmp/ardot-mode-shot/*/{stem}"]
    before = set(p for pat in pats for p in glob.glob(pat))
    sc_args = {"nodeIds":[shot_frame], "screenShotDir":"/tmp/ardot-mode-shot"}
    if page_fileurl:
        sc_args["fileUrl"] = page_fileurl
    rpc("tools/call", {"name":"capture_screenshot","arguments": sc_args})
    # 截图落在 /tmp/ardot-mode-shot/（带时间戳），取最新的本次新生成文件移到目标
    fresh = sorted((f for pat in pats for f in glob.glob(pat) if f not in before), key=os.path.getmtime)
    if fresh:
        os.replace(fresh[-1], shot); print("SHOT " + shot)
    else:
        print("SHOT-MISSING 检查 /tmp/ardot-mode-shot/")
        sys.exit(1)
PYEOF
