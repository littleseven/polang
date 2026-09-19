# Ardot Light/Dark 全域可切 + 页帧结构重组 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (inline) to implement this plan task-by-task. **禁止 subagent 并行执行**——Ardot 文件曾被并发会话恢复事故破坏（记忆库红线），全程单会话串行。Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ardot 设计稿在编辑器内可原生切换 Light/Dark 预览（帧级 variableModes），同时页/帧结构域对齐重组（116→113 顶层节点/11 页）。

**Architecture:** Dark 正稿 + literal 色全量改绑 PoLang Tokens 双模变量 + 图片布尔双图层（sysbar 方案推广到 floating_tab）；结构重组用 batch_edit D/M 操作 + 新建 Components 页；每批次后 export-ardot-snapshot.py 快照入库作回滚点。

**Tech Stack:** Ardot MCP (batch_read/batch_edit/apply_variables/export_nodes)、Python3 分析脚本、ffmpeg 像素采样、git。

**Spec:** `docs/superpowers/specs/2026-09-19-ardot-lightdark-reorg-design.md`

---

## 全局操作红线（每个任务都适用）

1. **mcp__ardot__* 调用必须省略 fileUrl**（带示例 ID 会假报 NO_ADAPTER）。
2. **batch_read 的 variableModes/fills 等属性必须显式传 properties** 才会返回。
3. **batch_edit 每批 ≤25 操作**；操作行内不写注释；跨批次不复用 binding 名。
4. **绝不并行/并发调用 Ardot MCP**（单会话串行；一次一个 batch_edit）。
5. 每个结构批次完成后立即快照 + git commit（回滚点）。
6. 快照导出后 `git status` 核对，**手工 git rm 陈旧 PNG**（导出脚本不清理）。

## 目标页账（终态验收基准）

| 页 | 帧数 | 帧清单（行主序） |
|---|---|---|
| Camera (6:2) | 7 | idle, panel_beauty_face, panel_ratio, panel_grid, panel_filter, panel_pro, focusing |
| Gallery (103:1) | 10 | grid, search, search_no_result, scanning, sort_menu, selection, info, empty, tag_control*, tag_stage_sheet* |
| Chat (111:319) | 7 | empty, empty-v2-guest, guest-nudge-sheet, conversation, sidebar, cleanup_confirm, cleanup_done |
| People (171:1) | 2 | grid, detail |
| Organize (267:21) | 10 | hub, category_grid, swipe/review, swipe/done, organize/cleaned, dedup/scanning, dedup/results, dedup/group_detail, dedup/keep_rules, dedup/cleaned |
| Memories (297:1) | 2 | feed, detail |
| Editor (118:104) | 4 | concept_a_hypic, concept_a_adjust, concept_a_crop, concept_a_beauty |
| Settings (108:1) | 11 | main_list, account, local_models, remote_models, add_remote_provider, provider_config, sandbox, developer, dialog_language, dialog_stage, dialog_theme |
| IconSet (132:2) | 1 | icon/spec_sheet |
| Components (新建) | 12 | component/floating_tab, component/status_bar, component/system_nav_bar, status_bar/Dark演示, system_nav_bar/Dark演示, component/provider_row, component/model_row, cast/mom, cast/dad, cast/alex, cast/lin, cast/chris |
| Play Store Assets (152:1) | 47 | 原有 35 帧布局不动 + 新增 2 行 store01 源帧×12 |

## 节点 ID 速查（迁移/删除清单）

**删除**: `118:105` editor/current_crop, `118:165` editor/current_adjust, `297:87` memory/bottombar

**迁 Store 页**: `300:29` gallery/grid-store01, `306:197` gallery/grid-store01-zhTW, `300:539` search/store01, `300:651` chat/store01-welcome, `301:13` chat/store01-conversation, `301:96` chat/store01-insight, `300:215` people/grid-store01, `300:723` people/detail-store01, `300:782` memory/store01-feed, `385:1` settings/privacy-store01-zh, `385:32` settings/privacy-store01-en, `385:63` settings/privacy-store01-tw

**迁 Components 页**: `385:95` floating_tab, `385:106` status_bar, `385:107` system_nav_bar, `386:43` status_bar/Dark演示, `386:44` system_nav_bar/Dark演示, `166:17` provider_row, `167:17` model_row, `300:1048` cast/mom, `300:1049` cast/dad, `300:1050` cast/alex, `300:1051` cast/lin, `300:1052` cast/chris

**迁 Gallery 页**: `171:273` gallery/tag_control, `172:113` gallery/tag_stage_sheet

## 布局规则

- 功能页网格: `COLS=6`, `X_STEP=513` (393+120), `Y_STEP=1012` (852+160)，帧左上角对齐单元格。
- Store 页: 现有内容布局不动，store01 源帧 2 行×6 追加在现有内容 maxY+200 之下（执行时读 maxY）。
- Components 页: 同 513/1012 网格。

---

### Task 1: 基线核验

**Files:** 无新建（只读 + git）

- [ ] **Step 1.1**: `git status --porcelain docs/08-UI-SPECS/` → 期望空（快照目录干净，HEAD 16fea14d9 即基线回滚点）。若非空，先弄清来源再继续，不得盲 commit。
- [ ] **Step 1.2**: `mcp__ardot__fetch_editor_state`（无参）→ 确认 10 页连通、current page 任意。

### Task 2: 全量深扫 audit（批次 A）

**Files:**
- Create: `scripts/ardot-theme-audit.py`
- Create: `tmp/ardot-audit/`（报告输出，不入库）

- [ ] **Step 2.1**: 写审计脚本 `scripts/ardot-theme-audit.py`（完整代码如下）。

```python
#!/usr/bin/env python3
"""Ardot 主题审计: 扫描 batch_read JSONL(readDepth=-1), 输出 literal 色清单 + token 绑定健康度 + 改绑建议。
用法:
  ardot-theme-audit.py --jsonl <batch_read.jsonl>... --vars <fetch_variables.jsonl> \
      [--out-dir tmp/ardot-audit] [--threshold 24]
输入 JSONL 来自 mcp__ardot__batch_read(省略 fileUrl, properties 含 fills/strokes/effects)。"""
import argparse, json, math, os, re, sys
from collections import Counter

HEX_RE = re.compile(r"#[0-9A-Fa-f]{6,8}")
RGB_DICT_RE = re.compile(r"'r':\s*([0-9.]+).*?'g':\s*([0-9.]+).*?'b':\s*([0-9.]+)", re.S)

def parse_hex(s):
    m = HEX_RE.search(str(s))
    if not m: return None
    h = m.group(0)
    return tuple(int(h[i:i+2], 16) for i in range(0, 6, 2))

def to_rgb(v):
    """hex 字符串或 {r,g,b} 0-1 浮点字典(字符串化) -> (r,g,b) 0-255; 失败 None"""
    if isinstance(v, dict):
        try:
            return tuple(round(float(v.get(c, 0)) * 255) for c in ("r", "g", "b"))
        except Exception:
            return None
    rgb = parse_hex(v)
    if rgb: return rgb
    m = RGB_DICT_RE.search(str(v))
    if m:
        try:
            return tuple(round(float(x) * 255) for x in m.groups())
        except Exception:
            return None
    return None

def dist(a, b):
    return math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)))

def walk(node, path, acc):
    acc.append((path, node))
    for c in (node.get("children") or []):
        if isinstance(c, dict):
            walk(c, path + "/" + str(c.get("name", c.get("id"))), acc)

def paints(v):
    if v is None: return []
    return v if isinstance(v, list) else [v]

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
        if rgb: dark_vals[n["id"]] = (n["name"], rgb)
    report, ops, newvars = {}, [], Counter()
    for f in a.jsonl:
        for ln in open(f).read().strip().split("\n")[1:]:
            try: n = json.loads(ln)
            except Exception: continue
            if str(n.get("id", "")).startswith("_"): continue
            acc = []; walk(n, n.get("name", "?"), acc)
            fr = {"id": n["id"], "name": n.get("name"), "nodes": len(acc),
                  "bound": 0, "literal": [], "images": []}
            for path, nd in acc:
                for kind in ("fills", "strokes"):
                    for p in paints(nd.get(kind)):
                        s = str(p)
                        if s.startswith("$"):
                            fr["bound"] += 1; continue
                        if "IMAGE" in s or "imageHash" in s:
                            fr["images"].append({"id": nd.get("id"), "path": path, "kind": kind}); continue
                        rgb = to_rgb(p)
                        if rgb is None: continue
                        hm = HEX_RE.search(s)
                        fr["literal"].append({"id": nd.get("id"), "path": path, "kind": kind,
                                              "value": (hm.group(0).upper() if hm else "rgb" + str(tuple(rgb)))})
            report[n["id"]] = fr
    # 就近匹配建议
    for fid, fr in report.items():
        for lit in fr["literal"]:
            lit_rgb = parse_hex(lit["value"])
            if lit_rgb is None:
                lit_rgb = tuple(int(x) for x in re.findall(r"\d+", lit["value"])[:3])
            best, bd = None, 1e9
            for vid, (name, rgb) in dark_vals.items():
                d = dist(lit_rgb, rgb)
                if d < bd: best, bd = (vid, name), d
            if best and bd <= a.threshold:
                lit["bind_to"], lit["dist"] = best, round(bd, 1)
            else:
                newvars[lit["value"]] += 1
                lit["new_var_needed"] = True
    out = {"frames": report, "unmatched_colors": dict(newvars)}
    json.dump(out, open(os.path.join(a.out_dir, "audit.json"), "w"), ensure_ascii=False, indent=1)
    # markdown 摘要
    with open(os.path.join(a.out_dir, "audit.md"), "w") as md:
        md.write("| frame | nodes | bound | literal | images | 需新增色 |\n|---|---|---|---|---|---|\n")
        for fid, fr in sorted(report.items(), key=lambda kv: -len(kv[1]["literal"])):
            nv = sum(1 for l in fr["literal"] if l.get("new_var_needed"))
            md.write(f"| {fr['name']} | {fr['nodes']} | {fr['bound']} | {len(fr['literal'])} | {len(fr['images'])} | {nv} |\n")
    print(f"frames={len(report)} total_literal={sum(len(f['literal']) for f in report.values())} "
          f"unmatched={sum(newvars.values())} -> {a.out_dir}/audit.(json|md)")
if __name__ == "__main__":
    main()
```

- [ ] **Step 2.2**: 自测脚本（用既有 depth-1 JSONL 跑通，不追求全量）：`python3 scripts/ardot-theme-audit.py --jsonl /var/folders/qy/sn5fq48s5892tz5wq7_v0ddw0000gn/T/.ardot/reads/batch_read-171_3_23-*.jsonl --vars /var/folders/qy/sn5fq48s5892tz5wq7_v0ddw0000gn/T/.ardot/reads/fetch_variables-*.jsonl` → 期望打印 `frames=9 ...`，生成 audit.md。
- [ ] **Step 2.3**: 深扫 9 页（Camera 6:2 / Gallery 103:1 / Chat 111:319 / Settings 108:1 / Editor 118:104 / IconSet 132:2 / People 171:1 / Organize 267:21 / Memories 297:1；**Store 152:1 跳过**——固定深色不参与）。逐页 `mcp__ardot__batch_read(parentId=<页>, patterns=[{name:"."}], properties=["fills","strokes","effects","name"], readDepth=-1, searchDepth=1)`，串行 9 次调用，记录 9 个 JSONL 路径。
- [ ] **Step 2.4**: 全量跑审计：`python3 scripts/ardot-theme-audit.py --jsonl <9个JSONL> --vars <fetch_variables.jsonl>` → 读 audit.md，人工复核 unmatched 色清单。
- [ ] **Step 2.5**: 提交脚本：`git add scripts/ardot-theme-audit.py && git commit -m "feat(tools): Ardot 主题审计脚本——literal 色盘点+就近 token 匹配"`

### Task 3: 删除 3 旧帧（批次 B1）

- [ ] **Step 3.1**: 单批 batch_edit（3 个 D 操作）：`D 118:105`、`D 118:165`、`D 297:87`（按 batch_edit 文档操作语法）。
- [ ] **Step 3.2**: 回读验证：batch_read(parentId=118:104 / 297:1, patterns=[{name:"."}], properties=["name"], readDepth=0) → Editor 页剩 4 帧（concept_a×4），Memories 剩 3 帧（feed/detail/store01-feed，store01 尚未迁）。
- [ ] **Step 3.3**: 快照+提交：`python3 scripts/export-ardot-snapshot.py`（若支持 --pages 则限定受影响页）→ `git rm` 陈旧 PNG（editor-current_*.png、memory-bottombar.png）→ `git commit -m "chore(design): 删除旧代帧——editor/current×2+memory/bottombar（浮动底bar已统一组件）"`。

### Task 4: 新建 Components 页 + 12 节点迁入（批次 B2）

- [ ] **Step 4.1**: `mcp__ardot__create_new_page(name="Components")` → 记录返回 pageId（记为 CP）。
- [ ] **Step 4.2**: 批 1 batch_edit（≤25 ops，12 个 M 操作，目标坐标按 513/1012 网格）: `385:95→(0,0)` `385:106→(513,0)` `385:107→(1026,0)` `386:43→(1539,0)` `386:44→(2052,0)` `166:17→(0,1012)` `167:17→(513,1012)` `300:1048→(1026,1012)` `300:1049→(1283,1012)`（cast 240 宽用 X_STEP=257 排列: `300:1050→(1540,1012)` `300:1051→(1797,1012)` `300:1052→(2054,1012)`）。M 语法: `M('<nodeId>', '<CP>', x, y)`。
- [ ] **Step 4.3**: 回读验证 CP 页 12 顶层节点齐全；再验证来源页：Settings(108:1) 剩 14、People(171:1) 剩 4、IconSet(132:2) 剩 1。
- [ ] **Step 4.4**: 快照+提交：`git commit -m "feat(design): 新建 Components 页——三件套组件/演示帧/provider_row/model_row/cast 素材集中"`（快照若不收录非屏页，仍提交 structure 变化；陈旧 PNG 核对 git rm）。

### Task 5: store01 源帧迁入 Store 页（批次 B3）

- [ ] **Step 5.1**: batch_read(parentId=152:1, patterns=[{name:"."}], properties=["name"], readDepth=0) → 读现有内容 maxY（执行时计算）。
- [ ] **Step 5.2**: 批 batch_edit 12 个 M（2 行×6, Y0=maxY+200, Y1=Y0+1012, X=i*513）: 顺序 gallery/grid-store01, gallery/grid-store01-zhTW, search/store01, chat/store01-welcome, chat/store01-conversation, chat/store01-insight / people/grid-store01, people/detail-store01, memory/store01-feed, privacy-store01-zh, privacy-store01-en, privacy-store01-tw。
- [ ] **Step 5.3**: 回读验证：Store 页 47 顶层；Gallery 剩 8、Chat 剩 7、People 剩 2、Memories 剩 2、Settings 剩 11。
- [ ] **Step 5.4**: 快照+提交：`git commit -m "feat(design): store01 商店源帧×12 集中迁入 Play Store Assets 页"`（陈旧 PNG git rm）。

### Task 6: tag 帧回 Gallery 页 + 全页面重排（批次 B4/B5）

- [ ] **Step 6.1**: 批 batch_edit: `M 171:273 → 103:1`、`M 172:113 → 103:1`（先放临时位 (0,3000)）。
- [ ] **Step 6.2**: 按目标页账表逐页重排。每页一批 batch_edit（≤25 M ops/批），坐标 = 目标页账表顺序 × 513/1012 网格。页批顺序: Camera(7)→Gallery(10)→Chat(7)→Settings(11)→Editor(4)→Organize(10)→Memories(2)→People(2)→IconSet(1, 若已在(0,0)跳过)→Components(12, Task 4 已排, 跳过)→Store(47, 原布局+Task5 已排, 跳过)。
- [ ] **Step 6.3**: 回读验证：10 页顶层帧名集合与页账表逐一比对（写一次性 python 对照 batch_read 输出 vs 页账表，全 PASS 才继续）。
- [ ] **Step 6.4**: 快照+提交：`git commit -m "feat(design): 页帧结构重组收官——域对齐+行主序重排, 116→113 节点/11 页"`（陈旧 PNG git rm；manifest 同步核对）。

### Task 7: 绑定修复——逐页 literal→双模 token（批次 C）

> 依据 Task 2 audit.json 逐页执行；每页流程相同，下称「页面修复流程」。页顺序: Gallery→Chat→Settings→Organize→Memories→People→Camera→Editor（先易后难，重灾区殿后）。

**页面修复流程（每页重复）:**
- [ ] **Step X.1**: 从 audit.json 提取该页帧的 literal 清单；`bind_to` 项生成 U 绑定操作（fills/strokes 换 `$<varId>`）；`new_var_needed` 项先 `mcp__ardot__apply_variables` 在 PoLang Tokens(2:2) 新增语义命名 COLOR 变量（Dark=原值, Light=按设计语义推 light 值——基于既有 Light 模式色阶映射: 背景 #141218 类→#F7F2FA 类、文字 #E6E1E5→#1C1B1F、次级 #CAC4D0→#49454F、品牌色不变），再生成 U 操作。
- [ ] **Step X.2**: batch_edit 分批 ≤25 U 操作执行（properties 里 fills 为数组形式——TEXT 色/渐变 stop 绑定语法照旧例）。
- [ ] **Step X.3**: Light 抽查导出: `mcp__ardot__export_nodes(nodeIds=[该页1-2帧], outputDir=tmp/ardot-audit/light-<page>, format=png)`——**注意 export 不带模式钉定时导默认 Dark**；带 Light 需先 U 该帧 variableModes={"2:2":"79:1"} 再导出再改回（省略 fileUrl）。用 ffmpeg 像素采样验证: `ffmpeg -i x.png -vf "crop=393:200:0:650,scale=1:1" -f rawvideo -pix_fmt rgb24 - | xxd` → 底部区域平均色应为浅色系而非深色。
- [ ] **Step X.4**: 快照（--pages 限该页）+ 提交 `fix(design): <页> literal→双模 token 绑定×N 处`。

- [ ] **Step 7.G**: 八页全部完成后全量复跑 Task 2 审计脚本 → 期望 8 功能页 literal=0（IMAGE/照片除外；纯黑白装饰线若设计上双模式同值, 绑到双值相同的变量）。

### Task 8: floating_tab 布尔自适应（批次 D）

- [ ] **Step 8.1**: batch_read(nodeIds=["385:95"], readDepth=-1, properties=["fills","name","visible"]) → 理解组件层级（现仅 Dark 版图层）。
- [ ] **Step 8.2**: 参照 status_bar(385:106) 双图层结构: C 拷贝现图层为 `bar_light` 副本（浅色值 fill），原层命名 `bar_dark`；U 两层 visible 绑定 `$386:37`(sysbar-dark)/`$386:38`(sysbar-light)。
- [ ] **Step 8.3**: 验证: IconSet 页无残留引用破坏 + 抽一个用 floating_tab 的帧（gallery/grid）导出 Light PNG 像素采样底部 bar 区域变浅。
- [ ] **Step 8.4**: 快照+提交 `feat(design): floating_tab 组件 Light/Dark 布尔双图层自适应（对齐 sysbar 方案）"`。

### Task 9: 双模全量导出验证（批次 E1）

- [ ] **Step 9.1**: 对 8 功能页全部帧: U 钉 variableModes={"2:2":"79:1"} → export_nodes PNG(tmp/ardot-audit/light-all/) → U 清除钉定（恢复默认 Dark）。分页分批串行。
- [ ] **Step 9.2**: python 脚本逐帧像素采样（顶部条/底部 bar/中部背景 3 区域 ffmpeg crop+scale=1:1），对照 Dark 快照 PNG: 期望 Light 版亮度显著上升且无纯黑块（<10,10,10 平均区域报警）。异常帧回 Task 7 流程修补。
- [ ] **Step 9.3**: 全 PASS 后清理 tmp/ardot-audit（不入库）。

### Task 10: 快照收口 + 文档同步（批次 E2）

- [ ] **Step 10.1**: 全量快照刷新 `python3 scripts/export-ardot-snapshot.py` → 核对 structure.json 页账=目标页账表、`git rm` 陈旧 PNG、manifest 一致。
- [ ] **Step 10.2**: 提交 `chore(design): Light/Dark 全域可切收口——快照全量刷新`。
- [ ] **Step 10.3**: 文档同步: `grep -r "tag_control\|current_crop\|store01" docs/08-UI-SPECS/*.md docs/08-UI-SPECS/screens/*.md 2>/dev/null` 核对帧名引用漂移并修正；`.kimi-code/ARDOT_MCP.md` 若引用页结构同步之。
- [ ] **Step 10.4**: 更新 spec 状态行 → `已交付(2026-09-19)`；memory 写入本次关键决策（Ardot 主题可切终态、Components 页、审计脚本用法）。
- [ ] **Step 10.5**: 终验: `git log --oneline` 核对本计划产生的提交序列完整、工作区快照目录干净。

## 验收标准（对照 spec §4）

1. Ardot 编辑器内任一功能页帧选中后切换 PoLang Tokens 模式为 Light → 全树即时变浅（无破色/黑块）。
2. 页账=目标页账表（113 顶层/11 页），帧名前缀=所在页域。
3. 八功能域 literal 色归零（audit 复跑）。
4. floating_tab/status_bar/system_nav_bar 三件套全部 Light 自适应。
5. 快照 PNG/structure.json/manifest 三一致，无陈旧 PNG 残留。
