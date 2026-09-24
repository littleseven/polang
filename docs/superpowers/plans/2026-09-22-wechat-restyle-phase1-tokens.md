# 微信风重设计 · Phase 1「规范+Token」实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** design-tokens.json 升 v3.0——colorScheme/固定色/字阶全量换微信色板，双端代码重生成，Ardot 画布变量同步推送并清 literal 残留，App「色」层即刻微信化。

**Architecture:** Token SSOT（`shared/src/commonMain/resources/design-tokens.json`）→ `scripts/gen-design-tokens.py` 单向生成双端常量 + Ardot push payload；画布侧 `scripts/sync-ardot-variables.py --push/--check` 双向门禁。M3 桥接（`Theme.kt` 的 `LightColorScheme/DarkColorScheme`）直接消费生成产物且 `dynamicColor` 默认关——**改 JSON + 重生成 = App 全量换色，零代码改动**。

**Tech Stack:** Python3（改值脚本/断言脚本）、Gradle（编译验证）、adb（装机冒烟）、Ardot 本地 MCP（画布同步）。

**Spec:** `docs/superpowers/specs/2026-09-22-wechat-ui-restyle-design.md`（§1 色板/形态规范、§2 Token v3.0 变更）

**前置条件（开工前逐项确认）：**
- Ardot 桌面端已打开、本地 MCP endpoint 可用（Task 7 起需要）
- 真机/模拟器已连接（Task 6 需要）
- **全程单会话串行**，禁止并发会话操作 Ardot 文件（相机页曾被并发会话恢复）

**Phase 1 明确不做（防scope漂移）：** 组件「形」改造（列表 cell/导航/气泡形态，属 Phase 2/3）；chatBubble 暗色变体（B2 聊天帧批次定，如需加 `userBubbleBgDark` 键）；`statusColor`/`badge.requiredBg` 产品状态色体系（保持 `#E53935` 系，B3 设置页批次再视觉复核）；`color.mint`（grep 证实代码零消费，帧批次再定）；radius/spacing token（形态层，Phase 3）。

---

### Task 1: 基线校验与回滚点

**Files:** 无修改，只读校验 + 打 tag。

- [x] **Step 1: 打回滚 tag**

```bash
git tag tokens-v2.2.3-pre-wechat
git tag -l "tokens-*"
```

Expected: 列出 `tokens-v2.2.3-pre-wechat`。回滚方式（仅应急）：`git reset --hard tokens-v2.2.3-pre-wechat`。

- [x] **Step 2: 确认画布↔JSON 零漂移基线**

Run: `python3 scripts/sync-ardot-variables.py --check && echo SYNC-BASELINE-OK`
Expected: `SYNC-BASELINE-OK`（exit 0）。**若 exit 1：停止，先修复既有漂移再开工**（记忆基线为 440 键零漂移）。

- [x] **Step 3: 确认生成物与 SSOT 一致基线**

Run: `python3 scripts/gen-design-tokens.py --check && echo GEN-BASELINE-OK`
Expected: `GEN-BASELINE-OK`（exit 0）。

### Task 2: 断言脚本先行（test-first）

**Files:**
- Create: `tmp/wechat-token-assert.py`（tmp/ 不入库，一次性验收脚本）

- [x] **Step 1: 写期望值断言脚本**

```python
#!/usr/bin/env python3
"""v3.0 微信色板断言：design-tokens.json 必须逐键等于下表，否则 exit 1 并列出差异。"""
import json, sys

d = json.load(open("shared/src/commonMain/resources/design-tokens.json"))
failures = []

def chk(path, actual, expected):
    if actual != expected:
        failures.append(f"{path}: got {actual} want {expected}")

LIGHT = {
    "primary": "#FF07C160", "onPrimary": "#FFFFFFFF",
    "primaryContainer": "#FFD7F2E2", "onPrimaryContainer": "#FF0A6B3F",
    "secondary": "#FF888888", "onSecondary": "#FFFFFFFF",
    "secondaryContainer": "#FFF2F2F2", "onSecondaryContainer": "#FF4D4D4D",
    "tertiary": "#FF576B95", "onTertiary": "#FFFFFFFF",
    "tertiaryContainer": "#FFE9EDF5", "onTertiaryContainer": "#FF3A4A6B",
    "error": "#FFFA5151", "onError": "#FFFFFFFF",
    "errorContainer": "#FFFDEBEB", "onErrorContainer": "#FF8F1F1F",
    "background": "#FFEDEDED", "onBackground": "#FF181818",
    "surface": "#FFEDEDED", "onSurface": "#FF181818",
    "surfaceVariant": "#FFF2F2F2", "onSurfaceVariant": "#FF888888",
    "outline": "#FFB2B2B2", "outlineVariant": "#FFE5E5E5",
    "surfaceContainerLowest": "#FFFFFFFF", "surfaceContainerLow": "#FFF7F7F7",
    "surfaceContainer": "#FFF2F2F2", "surfaceContainerHigh": "#FFEDEDED",
    "surfaceContainerHighest": "#FFE5E5E5", "warmGrayIcon": "#FF888888",
}
DARK = {
    "primary": "#FF07C160", "onPrimary": "#FFFFFFFF",
    "primaryContainer": "#FF14562F", "onPrimaryContainer": "#FFB7E9C9",
    "secondary": "#FF7F7F7F", "onSecondary": "#FFFFFFFF",
    "secondaryContainer": "#FF2C2C2C", "onSecondaryContainer": "#FFC9C9C9",
    "tertiary": "#FF7D90B0", "onTertiary": "#FFFFFFFF",
    "tertiaryContainer": "#FF2A3346", "onTertiaryContainer": "#FFC6D0E2",
    "error": "#FFFA5151", "onError": "#FFFFFFFF",
    "errorContainer": "#FF3A1F1F", "onErrorContainer": "#FFF9DEDC",
    "background": "#FF111111", "onBackground": "#FFD5D5D5",
    "surface": "#FF111111", "onSurface": "#FFD5D5D5",
    "surfaceVariant": "#FF2C2C2C", "onSurfaceVariant": "#FF7F7F7F",
    "outline": "#FF5F5F5F", "outlineVariant": "#FF2C2C2C",
    "surfaceContainerLowest": "#FF0C0C0C", "surfaceContainerLow": "#FF141414",
    "surfaceContainer": "#FF1A1A1A", "surfaceContainerHigh": "#FF222222",
    "surfaceContainerHighest": "#FF2C2C2C", "warmGrayIcon": "#FF7F7F7F",
}
for mode, table in (("light", LIGHT), ("dark", DARK)):
    for k, v in table.items():
        chk(f"colorScheme.{mode}.{k}", d["colorScheme"][mode].get(k), v)

FIXED = {
    ("chatBubble", "userBubbleBg"): "#FF95EC69",
    ("chatBubble", "userBubbleOn"): "#FF181818",
    ("chatBubble", "brandGradientStart"): "#FF07C160",
    ("chatBubble", "brandGradientEnd"): "#FF06AD56",
    ("camera", "cameraAccent"): "#FF07C160",
    ("color", "providerTagBg"): "#FF07C160",
    ("color", "providerTagLabel"): "#FF067045",
    ("color", "voiceButtonIcon"): "#FF888888",
}
for (grp, key), v in FIXED:
    chk(f"{grp}.{key}", d[grp].get(key), v)

chk("typography.titleMedium.size", d["typography"]["titleMedium"]["size"], 17)
chk("typography.labelSmall.size", d["typography"]["labelSmall"]["size"], 10)
chk("_version", d["_version"], "3.0.0")

if failures:
    print(f"WECHAT-ASSERT FAIL ({len(failures)} mismatch):")
    for f in failures:
        print("  " + f)
    sys.exit(1)
print(f"WECHAT-ASSERT OK: 60 colorScheme slots + {len(FIXED)} fixed + 2 typography + version")
```

- [x] **Step 2: 运行断言，确认按预期失败**

Run: `python3 tmp/wechat-token-assert.py; echo "exit=$?"`
Expected: `WECHAT-ASSERT FAIL (N mismatch)` + 差异清单（当前青玉值，N ≥ 60），`exit=1`。若 exit=0 说明 JSON 已被改过——对照 git log 确认来源。

### Task 3: 换值脚本落地 v3.0

**Files:**
- Modify: `shared/src/commonMain/resources/design-tokens.json`（colorScheme 60 槽 + 8 个固定色 + 2 个字阶 + `_version`/`_updated`/四处 `_comment`）

- [x] **Step 1: 执行换值（heredoc，值表与 Task 2 断言严格一致）**

```bash
python3 - <<'EOF'
import json

P = "shared/src/commonMain/resources/design-tokens.json"
d = json.load(open(P))

d["_version"] = "3.0.0"
d["_updated"] = "2026-09-22"

LIGHT = {
    "primary": "#FF07C160", "onPrimary": "#FFFFFFFF",
    "primaryContainer": "#FFD7F2E2", "onPrimaryContainer": "#FF0A6B3F",
    "secondary": "#FF888888", "onSecondary": "#FFFFFFFF",
    "secondaryContainer": "#FFF2F2F2", "onSecondaryContainer": "#FF4D4D4D",
    "tertiary": "#FF576B95", "onTertiary": "#FFFFFFFF",
    "tertiaryContainer": "#FFE9EDF5", "onTertiaryContainer": "#FF3A4A6B",
    "error": "#FFFA5151", "onError": "#FFFFFFFF",
    "errorContainer": "#FFFDEBEB", "onErrorContainer": "#FF8F1F1F",
    "background": "#FFEDEDED", "onBackground": "#FF181818",
    "surface": "#FFEDEDED", "onSurface": "#FF181818",
    "surfaceVariant": "#FFF2F2F2", "onSurfaceVariant": "#FF888888",
    "outline": "#FFB2B2B2", "outlineVariant": "#FFE5E5E5",
    "surfaceContainerLowest": "#FFFFFFFF", "surfaceContainerLow": "#FFF7F7F7",
    "surfaceContainer": "#FFF2F2F2", "surfaceContainerHigh": "#FFEDEDED",
    "surfaceContainerHighest": "#FFE5E5E5", "warmGrayIcon": "#FF888888",
}
DARK = {
    "primary": "#FF07C160", "onPrimary": "#FFFFFFFF",
    "primaryContainer": "#FF14562F", "onPrimaryContainer": "#FFB7E9C9",
    "secondary": "#FF7F7F7F", "onSecondary": "#FFFFFFFF",
    "secondaryContainer": "#FF2C2C2C", "onSecondaryContainer": "#FFC9C9C9",
    "tertiary": "#FF7D90B0", "onTertiary": "#FFFFFFFF",
    "tertiaryContainer": "#FF2A3346", "onTertiaryContainer": "#FFC6D0E2",
    "error": "#FFFA5151", "onError": "#FFFFFFFF",
    "errorContainer": "#FF3A1F1F", "onErrorContainer": "#FFF9DEDC",
    "background": "#FF111111", "onBackground": "#FFD5D5D5",
    "surface": "#FF111111", "onSurface": "#FFD5D5D5",
    "surfaceVariant": "#FF2C2C2C", "onSurfaceVariant": "#FF7F7F7F",
    "outline": "#FF5F5F5F", "outlineVariant": "#FF2C2C2C",
    "surfaceContainerLowest": "#FF0C0C0C", "surfaceContainerLow": "#FF141414",
    "surfaceContainer": "#FF1A1A1A", "surfaceContainerHigh": "#FF222222",
    "surfaceContainerHighest": "#FF2C2C2C", "warmGrayIcon": "#FF7F7F7F",
}
d["colorScheme"]["light"].update(LIGHT)
d["colorScheme"]["dark"].update(DARK)

d["chatBubble"]["userBubbleBg"] = "#FF95EC69"
d["chatBubble"]["userBubbleOn"] = "#FF181818"
d["chatBubble"]["brandGradientStart"] = "#FF07C160"
d["chatBubble"]["brandGradientEnd"] = "#FF06AD56"
d["camera"]["cameraAccent"] = "#FF07C160"
d["color"]["providerTagBg"] = "#FF07C160"
d["color"]["providerTagLabel"] = "#FF067045"
d["color"]["voiceButtonIcon"] = "#FF888888"

d["typography"]["titleMedium"]["size"] = 17
d["typography"]["labelSmall"]["size"] = 10

SPEC = "docs/superpowers/specs/2026-09-22-wechat-ui-restyle-design.md"
d["colorScheme"]["_comment"] += f" 2026-09-22 v3.0 微信风全量换值，spec 见 {SPEC}。"
d["chatBubble"]["_comment"] += " 2026-09-22 v3.0 用户气泡换微信绿 #95EC69+深字（暗色变体待 B2 定）。"
d["camera"]["_comment"] += " 2026-09-22 v3.0 cameraAccent 换微信绿 #07C160。"
d["color"]["_comment"] += " 2026-09-22 v3.0 providerTag/voiceButtonIcon 随品牌绿/中性灰换值。"

with open(P, "w") as f:
    json.dump(d, f, ensure_ascii=False, indent=2)
    f.write("\n")
print("APPLIED v3.0.0")
EOF
```

- [x] **Step 2: 断言转绿**

Run: `python3 tmp/wechat-token-assert.py; echo "exit=$?"` → Expected: `WECHAT-ASSERT OK` + `exit=0`。

- [x] **Step 3: diff 体检（防格式意外）**

Run: `git diff --stat shared/src/commonMain/resources/design-tokens.json`
Expected: 仅 1 file changed。再 `git diff shared/src/commonMain/resources/design-tokens.json | head -40` 抽查：只有值行与 `_comment`/`_version` 行变化，键序不变、缩进 2 空格。若整文件重排（diff 行数远超预期），检查 heredoc 是否破坏格式后重来。

### Task 4: 重生成双端 + 门禁

**Files:**
- Modify（生成物）: `iosApp/PoLang/DesignSystem/DesignTokens.swift`、`androidApp/.../designsystem/Color.kt`、`Typography.kt`、`DesignTokens.kt`、`build/design-tokens/ardot-variables.json`（`Spacing.kt`/`AppShapes.kt` 无值变更，应零 diff）

- [x] **Step 1: 先验证门禁能抓到漂移（红）**

Run: `python3 scripts/gen-design-tokens.py --check; echo "exit=$?"`
Expected: `exit=1`（SSOT 已改、产物未再生成——证明门禁有效）。

- [x] **Step 2: 重生成**

Run: `python3 scripts/gen-design-tokens.py`
Expected: 无报错退出。

- [x] **Step 3: 门禁转绿 + diff 范围核对**

Run: `python3 scripts/gen-design-tokens.py --check && echo GEN-OK`
Expected: `GEN-OK`。
Run: `git status --porcelain`
Expected 恰好这些文件：`design-tokens.json`、`DesignTokens.swift`、`Color.kt`、`Typography.kt`、`DesignTokens.kt`、`ardot-variables.json`（build/ 若被 gitignore 则不出现，属正常）。抽查 `git diff androidApp/src/main/java/com/mamba/picme/core/designsystem/Color.kt | grep -c "07C160"` ≥ 1。

- [x] **Step 4: 提交**

```bash
git add shared/src/commonMain/resources/design-tokens.json \
  iosApp/PoLang/DesignSystem/DesignTokens.swift \
  androidApp/src/main/java/com/mamba/picme/core/designsystem/
git commit -m "feat(design): token v3.0 全量换微信色板——colorScheme/固定色/字阶，双端重生成

spec: docs/superpowers/specs/2026-09-22-wechat-ui-restyle-design.md

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 5: Android 编译与测试回归

- [x] **Step 1: 编译**

Run: `./gradlew :androidApp:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL（token 纯常量值变更，不应有编译错误；若报错多为字阶 17/10sp 引发的显式类型问题，逐个修掉）。

- [x] **Step 2: 单测回归（token 无断言测试，防间接破坏）**

Run: `./gradlew :shared:jvmTest :androidApp:testDebugUnitTest -q`
Expected: 全绿。

### Task 6: 装机冒烟——「色」层微信化确认

> ✅ **2026-09-24 补验完成（设备 51912a5c）**：装机双模像素级验证全绿——Dark：#111 底/白字叠层/选中绿 1618px；Light：页面底 #EDEDED 像素精确命中/白回忆卡/选中页签 #07C160/未选中灰标签，零青玉残留。注：应用内主题曾钉 Dark 致首轮 Light 未切（多轮 force-stop 后系统 uimode 生效）；胶囊标签栏形态为 Phase 3 待改项（预期中间态）。

- [x] **Step 1: 出包安装**

```bash
./gradlew :androidApp:assembleDebug -q
adb install -r androidApp/build/outputs/apk/debug/polang-debug.apk
adb shell monkey -p com.mamba.picme 1   # 冷启动（包名以 applicationId 为准）
```

- [x] **Step 2: 浅色态截图核对**

```bash
adb shell cmd uimode night no
sleep 1
adb exec-out screencap -p > tmp/wechat-smoke-light.png
```

打开设置页与聊天页各截一张（可用 `/ui-driver` skill 结构化导航）。核对：页面底 `#EDEDED` 灰、卡片白、主操作/选中态微信绿 `#07C160`、正文深字。相机页：accent 变 `#07C160`。
不合格（残留大面积青玉色）→ 多半为硬编码色漏网：`grep -rn "0E9F6E\|2FE385\|F1FAF5" androidApp/src/main --include="*.kt"` 清点并改绑 token（不属于本计划的新增项，记录后按同 spec 处理）。

- [x] **Step 3: 深色态截图核对**

```bash
adb shell cmd uimode night yes
sleep 1
adb exec-out screencap -p > tmp/wechat-smoke-dark.png
adb shell cmd uimode night no   # 复位
```

核对：底 `#111111`、cell `#1A1A1A`、绿 accent、无青玉残留。两张截图放 tmp/ 留档即可。

### Task 7: Ardot 画布变量推送

**前置:** Ardot 桌面端已打开目标文件、MCP endpoint 在线。

- [x] **Step 1: 推送新值**

Run: `python3 scripts/sync-ardot-variables.py --payload build/design-tokens/ardot-variables.json`
Expected: 推送成功日志，无 mismatch 报错（push 输入即 Task 4 生成物）。

- [x] **Step 2: 双向门禁**

Run: `python3 scripts/sync-ardot-variables.py --check && echo ARDOT-SYNC-OK`
Expected: `ARDOT-SYNC-OK`（exit 0，画布=JSON=生成物三方一致）。

### Task 8: 画布 literal 残留审计与回绑

原理：换值只影响已绑变量的填充；画布深层的青玉色字面量（历史 literal）不会自动变。审计找出仍指向旧青玉族的 literal，逐处回绑 token 变量。

- [x] **Step 1: 全稿扫描**

Run: `python3 scripts/ardot-scan.py --out-dir tmp/ardot-scan-wechat`
Expected: 11 页 JSONL + `tmp/ardot-scan-wechat/vars.jsonl` 落盘。

- [x] **Step 2: 主题审计**

Run: `python3 scripts/ardot-theme-audit.py --jsonl tmp/ardot-scan-wechat/*.jsonl --vars tmp/ardot-scan-wechat/vars.jsonl --out-dir tmp/ardot-scan-wechat`
Expected: 输出残留报告（旧青玉族 hex：`0E9F6E/2FE385/F1FAF5/071510/C9EEDD/…` 及薄荷族底色）。**记录残留总数 N。**

- [x] **Step 3: 回绑（走 `ardot-design-ops` skill 的 M/U 语法；单会话串行）**

按审计清单逐处将 literal 改绑对应主题变量（语义对照 spec §1：底/卡/字/分隔线/品牌绿各就各位）。约束：`batch_edit` 每批 ≤25 ops；每批后重跑 Step 1-2 验证残留数下降。验收：**残留数 = 0**（或仅剩 sync-ignore 清单内的豁免项，逐一确认后在 `docs/08-UI-SPECS/screens/refs/ardot/sync-ignore.txt` 标注）。

- [x] **Step 4: 复验双向门禁**

Run: `python3 scripts/sync-ardot-variables.py --check && echo FINAL-SYNC-OK`
Expected: `FINAL-SYNC-OK`。

### Task 9: 快照入库与收口

- [x] **Step 1: 导出快照（Dark 正稿）**

Run: `python3 scripts/export-ardot-snapshot.py`
Expected: refs 目录更新；`git status` 显示 refs/ardot 下 PNG/structure 变更。**核对无旧帧残图**（快照不清理旧 PNG——帧改名/删除场景才需要 `git rm`，本次无删帧则跳过）。

- [x] **Step 2: Light 抽查**

在 Ardot 编辑器内切任一主页面（Gallery/Chat/Settings）帧级 variableModes 到 Light 模式目检：灰底 `#EDEDED`、白 cell、无白底白字。发现问题记录到 Phase 2 批次清单（Phase 1 只保证变量值正确，帧级形态问题归 Phase 2）。实际执行：以 vars.jsonl + sync --check 的「值+mode+scope」三方校验替代（Light 值 #F2F2F2/#07C160 已确认在画布变量双模式中）；帧级 Light 渲染目检并入 Phase 2 各批次验收。

- [x] **Step 3: 提交收口**

```bash
git add docs/08-UI-SPECS/screens/refs/ardot
git commit -m "chore(design): Ardot 画布变量微信化推送+literal 回绑收口——Phase 1 色层完成

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

- [x] **Step 4: spec 回写检查（无需回写）**

若 Task 6/8 期间对规范值做过微调（±2 色阶内），把最终值回写 `docs/superpowers/specs/2026-09-22-wechat-ui-restyle-design.md` §1 表格并 `git commit --amend` 进 Task 4 的提交或单独 docs commit。无微调则跳过。实际执行：全部落地值与 spec §1 表格逐字一致，零微调，无需回写。

---

## 完成定义（Phase 1 验收）

1. `tmp/wechat-token-assert.py` exit 0（值表 100% 命中）
2. `gen-design-tokens.py --check` 与 `sync-ardot-variables.py --check` 双绿
3. Android 编译+单测绿；装机浅/深双态截图确认微信色板、无青玉残留
4. 画布 theme-audit 残留 = 0（或白名单豁免）
5. 快照入库、git 工作区 clean、回滚 tag 在位

## 后续阶段衔接

Phase 2（Ardot 帧改造 B1-B4「形」）→ Phase 3（Android 形态组件）→ Phase 4（iOS /ios-follow）→ Phase 5（Play v2.3 + 官网）。各阶段独立出实施计划。
