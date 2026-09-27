# 产品文档去冗余设计（结构保留 · SSOT 归位）

> **日期**：2026-09-27
> **状态**：v2（已经对抗式审查修订：吸收 2 BLOCKER + 4 MAJOR + 6 MINOR）
> **交付即清理**：本 spec 为在途工作文档，交付后随 commit 删除（docs/superpowers 惯例）。

## 1. 背景与问题

产品文档层共 7 个文件（~2100 行），存在四组核实过的冗余：

| # | 冗余 | 重复位置 | 证据 |
|---|------|----------|------|
| A | 指标数字**四写** | PRODUCT §2.2 红线表 + §7.2 技术性能表 + NFR_SPEC §2/§3（最全）+ **FEATURES §7.2 性能要求表 & §3.5 数字段**（v2 补） | 冷启动/快门/参数跟手等数字多处出现；**口径三变体**：§2.2「首字 <1s」、§7.2「红线 <2s 目标 <1s」、FEATURES:523「首字 <1.5s」 |
| B | 能力清单双写 | PRODUCT §2.1 核心能力叙事（73 行）vs §5.1 已验证表（38 行表体） | 对话式编辑/抠图/证件照/任务中心/JS 沙盒等同一批功能、同样 ✅ 与代码引用写两遍 |
| C | iOS 状态多写 | DOC_INDEX §3 快照 + §2 删除档案 + TASK_STATUS 规模锚点 + REFERENCE §1.1/§1.3 规模数字（数字本体一致，仅 Metal/metal 大小写差异） | 规模数字（185 文件/41808 行…）多处重复；REFERENCE §1.4 版本漂移注记与 TASK_STATUS §1 #10 双写 |
| D | 冻结线三层展开 | 相机：PRODUCT §2.1+§6.3（19 行）+FEATURES §4（81 行）；IM：PRODUCT §2.1+§6.7（22 行）+FEATURES §5（114 行） | 两条线均已冻结 2 个月+ |

**用户决策**：文件结构全部保留（不合并、不删文件），仅消除内容冗余；冻结线压缩为状态卡（~15-20 行/线，细节 git 历史可查）。

## 2. 目标结构：SSOT 职责划界

| 文件 | 唯一职责 | 行数预期 |
|------|----------|----------|
| `PRODUCT.md` | 定位/路线图 + 能力×状态表（§5，唯一状态出处）+ 体验红线值（§2.2，与 NFR 对齐） | 550 → ~380 |
| `docs/01-PRODUCT/FEATURES.md` | 活跃线交互规范 + 冻结线状态卡（数字一律指向 NFR） | 857 → ~640 |
| `docs/01-PRODUCT/NFR_SPEC.md` | **全部工程指标数字**（红线+目标+测量方法+验收工具，吸收所有迁移行） | 126 → ~160 |
| `docs/03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md` | IM 线全部验收红线（承接 FEATURES §5.6 迁移） | +6 行左右 |
| `docs/01-PRODUCT/IOS_PRODUCT_REFERENCE.md` | iOS 模块现状/双端对照（§5 = 模块状态 SSOT；规模数字改引用） | 270 → ~250 |
| `docs/01-PRODUCT/IOS_TASK_STATUS.md` | iOS 缺口看板 + 规模锚点数字唯一出处 | 微调 §4 注记 |
| `docs/01-PRODUCT/IOS_DOC_INDEX.md` | 纯前门索引 | 45 → ~18 |
| `docs/01-PRODUCT/SETUP_GUIDE.md` | 不动 | — |

总量预期：~2100 → ~1550 行（约 -25%）。

## 3. 逐文件改造

### 3.1 PRODUCT.md（四处手术）

1. **§7.2 技术性能表删除 → 引用块指向 NFR_SPEC**。删除前先迁移：§7.2 中 NFR §2 缺失的 5 行指标（LLM 首 token 延迟 / 端到端命令执行 / 对话数据库查询 / 远程模型切换延迟 / 应用包体积）迁入 NFR §2，补齐测量方法/验收工具列。
2. **§2.2 体验红线**：保留 5 行现行有效红线（Agent 响应 / 交互反馈 / 对话首字 / 快门 / 隐私保护）；IM 实验线 2 行（命令响应 <3s / 图片处理 <5s）**直接删除**——IM spec §10 AC-IM-9/10 已有等值行，纯去重；「Agent 响应 <1.5s」「交互反馈 <100ms」两行**补录进 NFR §2**（v2：否则「NFR 唯一出处」不成立，CLAUDE.md [PERF] 的 <100ms 也无 NFR 落点）；首字口径统一为 NFR 表述（红线 <2s / 目标 <1s）。
3. **§2.1 剥离状态与代码引用**：删去 ✅ 标记与类名/文件名引用，回归纯产品形态叙事；能力状态与代码证据只在 §5.1 表维护。
4. **§6.3 / §6.4 / §6.7 冻结线路线图段**各收紧至 8-10 行。§6.3 现有 9 行功能表处置（v2 明确）：场景面板移除 → 状态卡「收尾待办」承接；相机状态记忆 → `features/camera/AGENTS.md` 与 `features/settings/AGENTS.md` 已有落点；其余随压缩进 git 历史。

### 3.2 FEATURES.md（冻结线压状态卡 + 指标引用化）

- **§4 智能相机**（81 行 → ~18 行状态卡）：定位 / 冻结决策（2026-08-16）与生效条件 / 保留职责（实时渲染试验场 + 编辑流内容源）/ 冻结前收尾待办（Android 场景面板移除）/ 指引（PRODUCT §6.3 · `BEAUTY_ENGINE_TECH_SPEC.md` · git 历史）。**v2 补**：加「回归基线」行 = `docs/08-UI-SPECS/screens/camera.yaml` §19 + BEAUTY_ENGINE_TECH_SPEC，并保留「人脸十字星交互语义」一句（全库唯一行为描述，压掉即丢失）。
- **§5 IM 远程控制**（114 行 → ~18 行状态卡）：同构，细节指向 `IM_REMOTE_CONTROL_TECH_SPEC.md`。**§5.6 四行红线处置（v2 BLOCKER 修复）**：命令响应 <3s / 图片处理 <5s 两行删（IM spec AC-IM-9/10 已有等值）；**图片回传 <2s、鉴权失败拒绝 <500ms 迁入 IM spec §10**（新增 AC-IM-11/12，全库唯一出处）；**「同时处理队列上限 5 张」（:721）迁入 IM spec §6.3**（全库唯一出处）。
- **§3.5 数字段**（Agent 首字 <1.5s / ASR 语音响应 <3s / 对话恢复 <500ms）：迁入 NFR §2（ASR/对话恢复两行为 NFR 缺失项；首字 <1.5s 口径弃用统一为 NFR），原位改引用块。
- **§7.2 性能要求表**：删除表格，改指向 NFR_SPEC 的引用块（v2 MAJOR 修复——否则删 PRODUCT §7.2 后指标仍 NFR+FEATURES 双写）。
- 其余章节（§1/§2/§3 其余/§6/§7 其余/附录）原样；被外部引用的锚点（§1、§2.2、§2.7、§3.6）不动。
- 顺手修：`:649`「与 PRODUCT.md §6.6 统一」→ §6.7（IM 线编号写错）。

### 3.3 NFR_SPEC.md（升格数字 SSOT）

- §2 吸收全部迁移行：PRODUCT §7.2 独有 5 行 + PRODUCT §2.2 的 Agent 响应/交互反馈 2 行 + FEATURES §3.5 的 ASR 语音响应/对话恢复 2 行（均补测量方法/验收工具列）。
- 头部口径声明措辞（v2 M3 修复）：「工程指标数字 SSOT = 本文件；PRODUCT §2.2 保留产品体验红线值并与本表对齐」。
- 关联文档列清理（v2 顺手）：删除死链 `PRODUCT.md#3.1`；6 处 `PRODUCT.md#2.1` 改为互指说明（§2.1 改写后原语义反转）。
- §8 更新历史推进版本（现头部 1.1/2026-08-03 与 §8 的 1.2/2026-09-20 已不一致，本次一并校正）。

### 3.4 iOS 三件套

- **IOS_DOC_INDEX.md**：删 §2（删除档案清单，git 可查）与 §3（状态快照），保留头部定位 + §1 活文档表。
- **IOS_TASK_STATUS.md**：正文不动；§4 注记改写：第一 bullet（引用 DOC_INDEX §3 的同步说明）改为「DOC_INDEX 已纯索引化（2026-09-27），规模锚点唯一出处 = 本文件」。
- **IOS_PRODUCT_REFERENCE.md**（v2：「不动」改为四处小改）：
  1. §1.5 前门一致性注记删除/改写（引用了将被删除的 DOC_INDEX §3，否则断链——BLOCKER 修复）；
  2. 头部与 §1.1/§1.3 规模数字改为「见 IOS_TASK_STATUS 规模锚点」引用（落实唯一出处）；
  3. §1.4 版本号漂移注记删除（TASK_STATUS §1 #10 已登记）；
  4. §3 各模块「登记缺口」行压缩为「缺口 #N（见 TASK_STATUS §1）」编号引用。

### 3.5 IM_REMOTE_CONTROL_TECH_SPEC.md（迁移承接方）

- §10 验收新增 AC-IM-11（图片回传 < 2s）、AC-IM-12（鉴权失败拒绝 < 500ms）。
- §6.3 补「同时处理队列上限 5 张」限制。
- `:482` 相关文档表中对 FEATURES §5 的过时锚点（`#5-im-远程控制实验性融合入口---p2`）改为压缩后新锚点或文件级引用。

## 4. 引用修复清单（v2 修订后全量）

| 位置 | 问题 | 修复 |
|------|------|------|
| `androidApp/.../domain/agent/capability/AGENTS.md:7` | `FEATURES.md#5-im-远程控制融合入口` 过时锚点 | 改指压缩后 §5 状态卡锚点 |
| `docs/03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md:482` | 第二个过时锚点，正指向被压缩的 §5（v2 补） | 同上 |
| `docs/superpowers/README.md:38` | 引用「IOS_DOC_INDEX.md §2 同款约定」，§2 将删（v2 补） | 改为内联描述「交付即清理」约定，去指针 |
| `docs/00-INDEX.md:25` | DOC_INDEX 描述含「状态快照」字样（v2 补） | 去掉该字样 |
| `skills/i18n-validator/SKILL.md:102` + `.claude/commands/i18n-validator.md` 镜像 | 「FEATURES.md …（Section 4.1.1）」指向错误（术语表实在 §7.1，且 §4 压缩后更误导） | 改为 §7.1；两处同改（skill SSOT + 镜像） |
| `androidApp/.../core/designsystem/AGENTS.md:125-126` | 「Section 3 / 3.1」过时（现为 §6.1/§6.2；既有断链，顺手修） | 改为 §6.1/§6.2 |
| `skills/doc-sync-guardian/SKILL.md:96` + `.claude/commands/doc-sync-guardian.md` 镜像 | 示例句「点击快门触发三位一体反馈」取自被压缩的 §4.3 | 换为存活章节的示例句 |

不改动：`scripts/check_doc_sync.py`（SYNC_FILES 全存活；其 `check_broken_links` 跳过带锚点链接，已核实无影响）、`scripts/doc-sync-guardian.sh`、`scripts/impact-analyzer.sh`、`skills/intent-router`（§4 标题不变引用存活）；`docs-site/` 由 `sync-docs.sh` rsync 自动跟随。

## 5. 验证与交付

- 验证：`python3 scripts/check_doc_sync.py` 通过；全库 grep 无对已删小节的悬空引用（**排除** `docs/reviews/` 历史快照——死路径有意保留为既定约定——及 `docs/superpowers/` 在途文档）；指标数字单点核验：冷启动/快门/参数跟手/帧同步/首字延迟各仅在 NFR 一处带目标值定义。
- 交付：单 commit（docs-only）；本 spec 随交付删除。
- 后续维护约定：新增指标只写 NFR_SPEC；能力状态只改 PRODUCT §5；iOS 规模数字只改 TASK_STATUS；iOS 缺口只登记 TASK_STATUS §1。

## 修订记录

- v2（2026-09-27）：吸收对抗式审查发现——B1（IM 红线/队列上限唯一出处迁移）、B2（REFERENCE §1.5 断链）、M1（IM spec:482 锚点）、M2（FEATURES §3.5/§7.2 第四写 + 首字第三口径）、M3（Agent 响应/交互反馈补录 NFR + 口径声明措辞）、M4（REFERENCE 规模数字改引用）、m1-m6（行数校正、状态卡回归基线行、引用修复清单扩充、验收排除清单）。
