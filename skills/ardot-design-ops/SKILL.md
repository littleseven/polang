---
name: ardot-design-ops
description: Ardot 设计稿资产治理——新建页面/帧、token 样式变更、健康审计三场景编排,保障 Light/Dark 全域可切与组件化不腐败。Use when creating/modifying Ardot frames or components, syncing design tokens to canvas, exporting snapshots, or auditing design health (ardot, 设计稿, Light/Dark, 组件化).
version: 1.1.0
created: 2026-09-19
updated: 2026-09-20
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

> 落盘方式:MCP 大结果自动写 `$TMPDIR/.ardot/reads/*.jsonl`,响应文本给路径;封装可参考 `tmp/ardot-health/baseline_scan.py` 模式或 `scripts/sync-ardot-variables.py` 的 `mcp_connect`。

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

> 健康检查须带 `--ignore-file docs/08-UI-SPECS/screens/refs/ardot/health-ignore.txt`;豁免清单是「待收编/待决策」台账,增量收编时逐行移除,退出码归零才算过。
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
| I 绑定字符串自带尾分号 | 覆写路径加 ";" 后绑定失配 | 覆写时原样使用,勿追加分号 |
| potentialIssues 静默跳过 | batch_edit 部分 op 未生效无报错 | 响应逐条核对 potentialIssues,中断即逐 op 回读 |
| 实例不可写 variableModes | 对实例钉 Light 报错 | 钉宿主帧或主组件,勿钉实例 |
| 绝对定位宿主帧 I 忽略 x/y | 实例插入后位置错乱(flex 父) | I 显式带 layoutPositioning:ABSOLUTE |
| 隐形损坏子实例 Copy 丢弃 | mainComponent=None 的子实例复制后消失 | Copy 前 batch_read 查 mainComponent,损坏者先修 |
| 复扫漏页致 dup 假消失 | health-check JSONL 未含全 11 页 | 11 页全量重读,缺一页结论无效 |

## 相关文件

- [设计 spec](docs/superpowers/specs/2026-09-19-ardot-design-ops-design.md) - 本 Skill 的设计 SSOT
- [组件目录](docs/08-UI-SPECS/screens/refs/ardot/components.json) - 组件 SSOT
- [DESIGN_TOKENS_SPEC](docs/03-TECHNICAL-SPECS/DESIGN_TOKENS_SPEC.md) - token codegen 规范
- [ARDOT_MCP](.kimi-code/ARDOT_MCP.md) - MCP 工具速查与坑
- [ui-parity-guard](skills/ui-parity-guard/SKILL.md) - 双端一致性守卫(代码侧)
- [豁免清单](docs/08-UI-SPECS/screens/refs/ardot/health-ignore.txt) - 待收编/待决策台账

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0.0 | 2026-09-19 | 初始版本(三场景 + 入库标准 + 陷阱表) |
| 1.1.0 | 2026-09-20 | Task 4 实战坑回填(陷阱表 +6 行/落盘方式/ignore-file 门禁/豁免清单索引) |
