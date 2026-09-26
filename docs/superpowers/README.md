# docs/superpowers/ — AI 协作产物唯一事实来源（SSOT）

> **版本**：1.0
> **生效**：2026-08-01
> **适用工具**：Claude Code · Kimi · AndroidStudio Qwen 插件 · OpenCode（四工具共同遵守）

---

## 1. 为什么需要这个目录

本项目同时使用四个 AI 编码工具，每个工具自带的「superpowers / planning」插件**默认把 plan / spec 写到各自私有目录**（如 `~/.claude/plans/`、`.omo/plans/`），导致：

- 同一个功能的 design 分散在多处，跨工具不可见
- Plan 与 spec 对不上号
- 代码评审、CR 审计无法定位权威文档

**解决方案**：把所有**可分享的协作产物**统一收到本目录（`docs/superpowers/`），入库、版本化、四工具读写同一份。各工具的**私有状态**（会话缓存、brainstorm mockup）仍留在点目录，保持 gitignore。

---

## 2. 目录结构

```
docs/superpowers/
├── README.md            ← 本文件（SSOT 声明）
├── plans/               ← 执行计划（work plans）
│   └── YYYY-MM-DD-<slug>.md
├── specs/               ← 设计规格（design specs）
│   └── YYYY-MM-DD-<slug>-design.md
└── *.md                 ← 阶段性汇总文档（如 nightly-*.md、*-summary.md）
```

> `decisions/`（ADR 风格跨工具决策记录）为可选扩展，目前未启用，需要时新建即可。

### 生命周期（2026-08-22 起：交付即清理，git 历史即归档）

- **本目录只保留两类文档**：① 在途工作的 spec/plan；② 仍被活跃引用的设计 SSOT。已交付 feature 的 spec/plan **随交付定期清理删除**（不建 archived/ 目录，git 历史永久可查：`git log --all -- <path>` / `git show <rev>:<path>`）。
- **清理纪律**：删除前把仍有长期价值的事实沉淀进三层活文档（`PRODUCT.md` / `FEATURES.md` / 模块 `AGENTS.md` / `*_TECH_SPEC.md`）；活文档中的引用同步改为「已随交付清理，git 历史可查」，不留悬空链接（与 `docs/01-PRODUCT/IOS_DOC_INDEX.md` §2 同款约定）。
- 设计稿内容与代码冲突时以代码为准；本目录文档不是长期事实源，长期事实源是三层文档体系。

---

## 3. 命名规范（强制）

| 类型 | 格式 | 示例 |
|------|------|------|
| **Plan** | `YYYY-MM-DD-<kebab-case-slug>.md` | `2026-08-01-ai-engineer-diag-merge.md` |
| **Spec** | `YYYY-MM-DD-<kebab-case-slug>-design.md` | `2026-07-22-js-engine-jsbridge-design.md` |
| **汇总** | `YYYY-MM-DD-<topic>-summary.md` 或 `nightly-YYYY-MM-DD.md` | `2026-07-20-batch-mlkit-on-demand-summary.md` |

规则：
- 日期取**创建日**，不随后续修改变更
- slug 用英文小写 + 连字符，简洁表意（如 `chat-memory-passive-injection`）
- Spec 文件名一律以 `-design.md` 结尾，便于程序化识别
- 同一主题的 plan 与 spec 通过 slug 对应（如 plan `2026-07-20-batch-mlkit` ↔ spec `2026-07-20-batch-mlkit-on-demand-summary-design.md`）

---

## 4. 四工具写入约定（关键）

| 工具 | 默认位置 | **本项目要求** |
|------|----------|----------------|
| **Claude Code**（superpowers 插件） | `~/.claude/plans/` | ❌ 禁止；plan/spec 一律写 `docs/superpowers/{plans,specs}/` |
| **OpenCode**（ulw-plan / Momus） | `.omo/plans/` | ✅ 已做软链 `.omo/plans → ../docs/superpowers/plans`，写入自动落到公共目录 |
| **Kimi** | 无固定位置 | 直接写 `docs/superpowers/{plans,specs}/` |
| **AndroidStudio Qwen 插件** | 无固定位置 | 直接写 `docs/superpowers/{plans,specs}/` |

**Claude Code 配置提示**：项目级 `.claude/CLAUDE.md` 已声明本约定；若使用 superpowers 插件的 `/writing-plans` 等命令，请在生成后**立即移动**到 `docs/superpowers/plans/` 并按本规范改名。

---

## 5. 公共产物 vs 工具私有状态（边界）

| 类型 | 位置 | 是否入库 | 说明 |
|------|------|----------|------|
| ✅ Plan / Spec / 设计决策 | `docs/superpowers/` | ✅ 入库 | 四工具共享 |
| 🔒 Brainstorm mockup / HTML | `.superpowers/brainstorm/` | ❌ gitignore | 工具私有 UI 草稿 |
| 🔒 会话续接状态 | `.omo/run-continuation/` | ❌ gitignore | OpenCode 会话态 |
| 🔒 个人权限缓存 | `.claude/settings.local.json` | ❌ gitignore | Claude Code 个人配置 |
| 🔒 临时 plan 草稿 | `~/.claude/plans/` | ❌ 用户级 | **建议及时迁移到公共目录** |

**判断准则**：能被另一个工具复用、能被 CR 审计、能被 git 追踪 → 放公共目录。否则留私有。

---

## 6. 索引（2026-09-26 清理后）

- **specs（10 篇，均为在途/活跃 SSOT）**：
  - `2026-08-08-face-restoration-ondevice-design.md` — 人脸修复方向（未实施，待排期）
  - `2026-08-10-ios-follow-command-design.md` — /ios-follow 六阶段管线设计 SSOT（AGENTS §7 引用）
  - `2026-08-13-ios-chat-rich-features-design.md` — iOS Chat 富交互（批次③沙盒写操作在途）
  - `2026-08-25-album-dedup-design.md` — 去重 2.0 + 主页面 Pager 结构（FEATURES/模块 AGENTS 引用其 §11）
  - `2026-09-04-model-download-scan-power-optimization-design.md` — 下载/扫描功耗优化评估稿（供排期决策）
  - `2026-09-19-ardot-design-ops-design.md` — Ardot Design Ops skill 设计 SSOT
  - `2026-09-25-engineer-task-card-design.md` — 工程师任务卡（US-4~6 回联待 P2 网关改造）
  - `2026-09-25-intent-routing-contract-design.md` — 意图路由契约（M3 分支化在途）
  - `2026-09-26-html-card-two-tier-design.md`（+ 同名 mockup.html）— HTML 卡双形态 + 任务卡 HTML 化（已定稿待实施）
  - `2026-09-26-user-task-protocol-design.md` — 用户任务协议 + 任务中心双 Tab（M2/M3 在途）
- **plans（1 篇）**：`2026-08-13-ios-chat-rich-features.md`（批次③在途，与同名 spec 配套）

---

## 7. 变更记录

| 日期 | 变更 |
|------|------|
| 2026-08-01 | 建立 SSOT 约定，统一四工具 plan/spec 写入位置；新增 `.omo/plans` 软链 |
| 2026-08-22 | 历史清理：删除 103 篇已交付 spec/plan（≤08-20 非白名单，specs 77 + plans 25 + nightly 1），仅留在途/活跃 SSOT；建立「交付即清理、git 历史即归档」生命周期约定（见 §2） |
| 2026-09-26 | 第二轮清理：删除 40 篇已交付产物（specs 18 + plans 21 + claude-tunnel-summary），保留在途/活跃 SSOT（10 specs + 1 plan，清单见 §6）；AGENTS/yaml/脚本等活跃引用同步改写为「已随交付清理，git 历史可查」 |
