---
name: rd-reflection
description: |
  RD 工程师自我进化系统。结构化复盘模板 + 累积经验资产 + 动态检查清单 + 跨 skill 联动更新。
version: 1.1.1
created: 2026-05-10
updated: 2026-09-25
maintainer: "[RD] 全栈工程师"
tags:
  - reflection
  - checklist
  - evolution
  - experience
  - learning
---


# RD 自我进化系统 (Self-Evolving Reflection)

> **定位**：不是静态文档，而是活的系统。每次任务后自动生长，下次类似任务自动预警。
> **触发时机**：任务完成后复盘、经验沉淀、检查清单更新或跨 Skill 联动时自动启用。
> **⚠️ 状态（2026-09-25 审计）**：本系统当前处于**休眠**——`EXPERIENCE_LOG.md` 最新条目停在 2026-05-10，长期未按"活的系统"节奏运转。使用前先补录近期经验或确认清单条目仍有效（部分条目可能已随仓库重组/ADR-011 失效）。

---

## 一、使用流程（Task Lifecycle）

### Phase 1: 任务启动 — 动态检查清单（Prevent）

**任何涉及现有子系统的任务，编码前 MUST 执行：**

```bash
# 自动读取当前检查清单（注意：先 cd 或用绝对路径）
cat skills/rd-reflection/CHECKLIST.md
```

系统会根据任务关键词（如 "gallery", "adb", "compose"）自动匹配相关陷阱并高亮预警。

### Phase 2: 任务执行 — 实时标记（Observe）

执行过程中，一旦遇到以下情况，立即在 `TASK_LOG.md` 中记录（该文件由 `scripts/new_task.sh` 在任务启动时创建，任务完成后归档进 `EXPERIENCE_LOG.md`，平时不存在）：
- 某处花了超过 10 分钟仍未解决
- 发现与已有 skill 文档不一致的行为
- 踩到了已知陷阱（即使检查清单已提醒）

### Phase 3: 任务结束 — 结构化复盘（Reflect）

**必须回答以下 5 个问题：**

1. **时间分布**：各阶段实际耗时 vs 预估？最大偏差在哪里？
2. **陷阱清单**：遇到了哪些坑？严重级别？是否已有 skill 覆盖？
3. **根因分析**：每个陷阱的根本原因是什么？（知识盲区 / 流程缺失 / 工具不熟）
4. **措施落地**：具体改什么？（代码 / 文档 / skill / 流程）
5. **效果验证**：如何确保下次不再踩？（检查清单更新 / 自动化测试）

**复盘输出格式**（见 §三）：
- 追加到 `EXPERIENCE_LOG.md`
- 更新 `CHECKLIST.md`
- 如有必要，联动更新相关 skill

### Phase 4: 经验发酵 — 跨 skill 联动（Evolve）

当某个陷阱被不同任务重复踩到时，自动触发：
1. 在相关 skill 的"常见陷阱"章节追加
2. 如果 skill 文档与经验冲突，标记 `⚠️ 文档漂移` 待修复
3. 生成 `EVOLUTION_REPORT.md` 月度报告

---

## 二、文件体系

```
rd-reflection/
├── SKILL.md                    # 本文件：系统定义与使用流程
├── CHECKLIST.md                # 动态检查清单：任务前自动读取
├── EXPERIENCE_LOG.md           # 累积经验日志：按时间倒序，每次任务后追加
├── EVOLUTION_REPORT.md         # 月度进化报告：自动生成常见陷阱 TOP N
├── TASK_LOG.md                 # 当前任务实时记录（临时，任务完成后归档到 EXPERIENCE_LOG）
└── scripts/
    ├── new_task.sh             # 启动新任务：读取检查清单 + 初始化 TASK_LOG
    ├── reflect.sh              # 任务复盘：引导 5 个问题 + 自动更新 CHECKLIST + EXPERIENCE_LOG
    ├── update_skill.sh         # 跨 skill 联动：将经验同步到相关 skill 的"常见陷阱"
    └── evolution_report.sh     # 生成月度进化报告
```

---

## 三、复盘输出模板（追加到 EXPERIENCE_LOG.md）

```markdown
## [YYYY-MM-DD] 任务标题

**关联技能**: `adb-bot`, `compose-lifecycle`, `gallery`
**严重级别**: 🔴 P0 / 🟠 P1 / 🟡 P2
**时间偏差**: 预估 X 分钟 → 实际 Y 分钟（偏差 +Z%）

### 陷阱清单

| # | 陷阱描述 | 级别 | 已有 skill 覆盖？ | 时间浪费 |
|---|----------|------|-------------------|----------|
| 1 | xxx | P0 | ❌ 无 / ⚠️ 有但未读 / ✅ 有但不够 | 25min |

### 根因分析

- **陷阱 1**: 根因是 xxx，属于 [知识盲区 / 流程缺失 / 工具不熟]

### 措施落地

| 措施 | 目标资产 | 状态 |
|------|----------|------|
| 更新 adb-bot skill 故障排除 | `skills/adb-bot/SKILL.md` | ✅ 已提交 |
| 新增 Compose 闭包捕获规范 | `gallery/AGENTS.md` | ✅ 已提交 |

### 检查清单更新

- [x] 新增：编码前必须检索相关 skill
- [x] 新增：LaunchedEffect 中禁止读取 remember 局部变量

### 一句话总结

> xxx
```

---

## 四、自我进化机制

### 进化规则 1：重复陷阱自动升级

如果同一个陷阱在 30 天内被踩到 2 次+，自动：
1. 在 `CHECKLIST.md` 中标记为 `🔴 高频陷阱`
2. 在相关 skill 的"常见陷阱"章节追加，并标记 `🚨 已确认 X 次`
3. 在 `EVOLUTION_REPORT.md` 中列入 TOP 3

### 进化规则 2：文档漂移检测

当经验与 skill 文档冲突时，标记 `⚠️ 文档漂移`：
```markdown
- [⚠️ 文档漂移] adb-bot/SKILL.md §故障排除 说"静态接收器足够"，
  但实际经验证明 GalleryScreen 必须动态注册。待修复。
```

### 进化规则 3：检查清单智能排序

`CHECKLIST.md` 中的条目按以下权重排序：
1. 高频陷阱（重复次数）
2. 高时间浪费（单次 >15 分钟）
3. 最近 7 天新增

---

## 五、与项目资产的联动矩阵

| 本系统输出 | 联动目标 | 触发条件 |
|------------|----------|----------|
| CHECKLIST.md | 任务启动时自动读取 | 每次任务 |
| EXPERIENCE_LOG.md | 季度代码审查参考 | 每季度 |
| EVOLUTION_REPORT.md | 团队技术分享素材 | 每月 |
| skill 常见陷阱更新 | 相关 skill 的 SKILL.md | 陷阱重复 2 次+ |
| 文档漂移标记 | 专项修复任务 | 发现即标记 |

---

## 六、快速命令

```bash
# 启动新任务（读取检查清单）
./scripts/new_task.sh "任务名称"

# 任务复盘（引导 5 个问题，自动更新清单和日志）
./scripts/reflect.sh

# 生成月度进化报告
./scripts/evolution_report.sh

# 将经验同步到相关 skill
./scripts/update_skill.sh adb-bot
```

---

## 七、已有经验资产（截至 2026-05-10）

见 `EXPERIENCE_LOG.md`

## 相关文件

- [TEMPLATE.md](skills/TEMPLATE.md) — Skill 编写模版
## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.1.0 | 2026-05-10 | 初始版本 |
| 1.1.1 | 2026-09-25 | 标注系统休眠现状（经验日志停在 2026-05-10）；CHECKLIST 读取路径修正；澄清 TASK_LOG.md 运行时生成 |
