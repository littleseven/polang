# 产品文档去冗余设计（结构保留 · SSOT 归位）

> **日期**：2026-09-27
> **状态**：已确认（用户拍板：文件结构保留，只消除冗余；冻结线压状态卡）
> **交付即清理**：本 spec 为在途工作文档，交付后随 commit 删除（docs/superpowers 惯例）。

## 1. 背景与问题

产品文档层共 7 个文件（~2100 行），存在四组核实过的冗余：

| # | 冗余 | 重复位置 | 证据 |
|---|------|----------|------|
| A | 指标数字三写 | PRODUCT §2.2 红线表 + §7.2 技术性能表 + NFR_SPEC §2/§3（最全） | 冷启动/快门/参数跟手/帧同步数字三处出现；**口径已漂移**：§2.2 写「首字 <1s」、§7.2 写「红线 <2s 目标 <1s」 |
| B | 能力清单双写 | PRODUCT §2.1 核心能力叙事（73 行）vs §5.1 已验证表（60 行） | 对话式编辑/抠图/证件照/任务中心/JS 沙盒等同一批功能、同样 ✅ 与代码引用写两遍 |
| C | iOS 状态三写 | IOS_DOC_INDEX §3 快照 + §2 删除档案 + IOS_TASK_STATUS 规模锚点 + IOS_PRODUCT_REFERENCE §5 对照表 | 规模数字（185 文件/41808 行…）在 DOC_INDEX §3 与 TASK_STATUS 头部一字不差 |
| D | 冻结线三层展开 | 相机：PRODUCT §2.1+§6.3+FEATURES §4（115 行）；IM：PRODUCT §2.1+§6.7+FEATURES §5（116 行） | 两条线均已冻结 2 个月+ |

**用户决策**：文件结构全部保留（不合并、不删文件），仅消除内容冗余；冻结线压缩为状态卡（~15-20 行/线，细节 git 历史可查）。

## 2. 目标结构：SSOT 职责划界

| 文件 | 唯一职责 | 行数预期 |
|------|----------|----------|
| `PRODUCT.md` | 定位/路线图 + 能力×状态表（§5，唯一状态出处）+ 体验红线值（§2.2，与 NFR 对齐） | 550 → ~380 |
| `docs/01-PRODUCT/FEATURES.md` | 活跃线交互规范 + 冻结线状态卡 | 857 → ~650 |
| `docs/01-PRODUCT/NFR_SPEC.md` | **全部工程指标数字**（红线+目标+测量方法+验收工具） | 126 → ~145 |
| `docs/01-PRODUCT/IOS_PRODUCT_REFERENCE.md` | iOS 模块现状/双端对照（§5 = 模块状态 SSOT） | 不动 |
| `docs/01-PRODUCT/IOS_TASK_STATUS.md` | iOS 缺口看板 + 规模锚点数字唯一出处 | 微调 §4 注记 |
| `docs/01-PRODUCT/IOS_DOC_INDEX.md` | 纯前门索引 | 45 → ~18 |
| `docs/01-PRODUCT/SETUP_GUIDE.md` | 不动 | — |

总量预期：~2100 → ~1550 行（约 -25%）。

## 3. 逐文件改造

### 3.1 PRODUCT.md（四处手术）

1. **§7.2 技术性能表删除 → 引用块指向 NFR_SPEC**。删除前先迁移：§7.2 中 NFR §2 缺失的 5 行指标（LLM 首 token 延迟 / 端到端命令执行 / 对话数据库查询 / 远程模型切换延迟 / 应用包体积）**迁入 NFR_SPEC §2**，补齐测量方法/验收工具列。首字延迟口径统一为 NFR 表述（红线 <2s / 目标 <1s），消除漂移。
2. **§2.2 体验红线**：保留 5 行现行有效红线（Agent 响应 / 交互反馈 / 对话首字 / 快门 / 隐私保护）；IM 实验线 2 行（IM 命令响应 <3s / IM 图片处理 <5s）迁入 `IM_REMOTE_CONTROL_TECH_SPEC.md` 验收节；红线数字与 NFR 对齐。
3. **§2.1 剥离状态与代码引用**：删去 ✅ 标记与类名/文件名引用（`ChatEditProcessor` 等），回归纯产品形态叙事；能力状态与代码证据只在 §5.1 表维护。
4. **§6.3 / §6.4 / §6.7 冻结线路线图段**各收紧至 8-10 行，口径与 FEATURES 状态卡一致，细节指向对应 tech spec。

### 3.2 FEATURES.md（冻结线压状态卡）

- **§4 智能相机**（115 行 → ~18 行状态卡）：定位 / 冻结决策（2026-08-16）与生效条件 / 保留职责（实时渲染试验场 + 编辑流内容源）/ 冻结前收尾待办（Android 场景面板移除）/ 指引（PRODUCT §6.3 · `BEAUTY_ENGINE_TECH_SPEC.md` · git 历史）。
- **§5 IM 远程控制**（116 行 → ~18 行状态卡）：同构，细节指向 `IM_REMOTE_CONTROL_TECH_SPEC.md`。
- 其余章节（§1/§2/§3/§6/§7/附录）原样；被外部引用的锚点（§1、§2.2、§2.7、§3.6）不动。

### 3.3 NFR_SPEC.md（升格数字 SSOT）

- §2 吸收 PRODUCT §7.2 独有 5 行（同 3.1-1）。
- 头部加口径声明：本文件为工程指标数字唯一出处；PRODUCT §2.2 红线值与本表对齐。

### 3.4 iOS 三件套

- **IOS_DOC_INDEX.md**：删 §2（2026-08-10 删除档案清单，git 可查）与 §3（状态快照，与 TASK_STATUS 规模锚点、REFERENCE §5 重复），保留头部定位 + §1 活文档表。
- **IOS_TASK_STATUS.md**：正文不动；§4 注记改为「规模锚点唯一出处=本文件，DOC_INDEX 已纯索引化」。
- **IOS_PRODUCT_REFERENCE.md**：不动。

## 4. 引用修复（实测爆炸半径）

- `androidApp/src/main/java/com/mamba/picme/domain/agent/capability/AGENTS.md:7` 的过时锚点 `FEATURES.md#5-im-远程控制融合入口` → 改指压缩后 §5 状态卡锚点。
- PRODUCT.md 内部指向 §7.2 的引用（如有）→ 改指 NFR_SPEC。
- `skills/intent-router` 引用「§4 智能相机」：章节标题保持不变，引用存活，无需改。
- `scripts/check_doc_sync.py` SYNC_FILES / doc-sync-guardian skill：三层体系未动，无需改。
- docs-site：`sync-docs.sh` rsync 产物，部署时自动跟随。

## 5. 验证与交付

- 验证：`python3 scripts/check_doc_sync.py` 通过；全库 grep 无对已删小节（§7.2 表、DOC_INDEX §2/§3）的悬空引用；四组冗余数字各仅存一处。
- 交付：单 commit（docs-only）；本 spec 随交付删除。
- 后续维护约定：新增指标只写 NFR_SPEC；能力状态只改 PRODUCT §5；iOS 状态只改 TASK_STATUS/REFERENCE。
