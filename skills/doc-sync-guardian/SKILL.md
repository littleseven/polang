---
name: doc-sync-guardian
description: |
  自动维护 PRODUCT.md → docs/01-PRODUCT/FEATURES.md → 模块 AGENTS.md 三层文档体系的一致性。
version: 1.1.0
created: 2026-05-03
updated: 2026-08-03
maintainer: "[CR] 规范守护者 + [CO] 协调者"
tags:
  - documentation
  - sync
  - audit
  - agents
  - product
---


# PoLang 文档一致性守护者 (DocSync Guardian)

> **定位**：自动维护 PRODUCT.md → FEATURES.md → 模块 AGENTS.md 文档体系的一致性。
> **触发时机**：用户修改文档、检查文档同步或涉及文档一致性审计时自动启用。


## 📋 Skill 概述

本 Skill 用于在 PoLang 项目迭代过程中自动维护和检查三层文档体系的一致性，确保 `PRODUCT.md` → `docs/01-PRODUCT/FEATURES.md` → 模块 `AGENTS.md` 的单向引用链完整、准确、同步。

**核心价值**：
- 🔍 **自动检测**：识别文档与代码的不一致
- 🔄 **同步更新**：提供文档更新的标准化流程
- ✅ **一致性审计**：生成跨文档对照报告
- 📊 **变更追踪**：记录重大技术决策的文档化状态

---

## 🎯 适用场景

### 何时使用本 Skill

1. **功能开发完成后**：验证产品需求是否已正确下沉到技术和实现文档
2. **代码重构后**：检查技术文档是否需要更新以反映新架构
3. **定期维护时**：执行全量文档一致性审计（建议每周一次）
4. **Code Review 阶段**：作为 CR 检查清单的一部分
5. **版本发布前**：确保所有变更都有对应的文档记录

### 典型工作流

```
用户触发："检查文档一致性" 或 "同步更新文档"
    ↓
[CO] 确定审计范围（全量 / 指定模块）
    ↓
[CR] 执行三层文档对照检查
    ↓
[RD] 修复发现的不一致项
    ↓
[QA] 验收文档更新质量
    ↓
[CO] 生成审计报告并汇总
```

---

## 📐 三层文档架构

### 层级关系与职责边界

```
┌─────────────────────────────────────┐
│  PRODUCT.md                         │
│  What: 产品目标、验收指标、红线约束   │
│  维护者: [PM]                       │
└──────────────┬──────────────────────┘
               │ 引用
               ▼
┌─────────────────────────────────────┐
│  docs/01-PRODUCT/FEATURES.md                   │
│  How: 交互流程、体验规则、业务逻辑    │
│  维护者: [PM] + [RD]                │
└──────────────┬──────────────────────┘
               │ 指导
               ▼
┌─────────────────────────────────────┐
│  模块 AGENTS.md                     │
│  Implementation: 架构、代码、检查清单 │
│  维护者: [RD]                       │
└─────────────────────────────────────┘
```

### 内容分流原则

| 内容类型 | 应存放位置 | 示例 |
|---------|-----------|------|
| 产品愿景与使命 | `PRODUCT.md` | "成为 Android 平台最快相机" |
| 性能指标与红线 | `PRODUCT.md` | "冷启动 < 500ms" |
| 交互流程描述 | `docs/01-PRODUCT/FEATURES.md` | "点击快门触发三位一体反馈" |
| UI 视觉规范 | `docs/01-PRODUCT/FEATURES.md` | "大圆角 28dp+，毛玻璃效果" |
| 技术架构设计 | 模块 `AGENTS.md` | "Clean Architecture 分层" |
| 代码实现细节 | 模块 `AGENTS.md` | "Repository 层职责定义" |
| 专项技术方案 | `docs/*_TECH_SPEC.md` | "BEAUTY_ENGINE_TECH_SPEC.md" |
| 重大变更记录 | `docs/*_TECH_SPEC.md` | "拍照 GPU 化迁移方案" |

---

## 🔧 核心功能

### 1. 文档一致性审计 (Audit Mode)

**触发命令**：`audit-docs` 或 `检查文档一致性`

**执行步骤**：

#### Step 1: 提取 PRODUCT.md 关键指标
```bash
# 扫描 PRODUCT.md 中的量化指标
grep -E "< \d+ms|>\s\d+%|严禁|必须" PRODUCT.md
```

**检查项**：
- [ ] 所有 `[PERF]` 性能指标是否有对应技术实现？
- [ ] 所有 `[PRIVACY]` 隐私要求是否在代码中落实？
- [ ] 所有 `[I18N]` 国际化要求是否覆盖五语资源？

#### Step 2: 对照 FEATURES.md 交互规则
```bash
# 检查 FEATURES.md 是否承接了 PRODUCT.md 的所有交互要求
grep -E "交互|反馈|动效|动画" docs/01-PRODUCT/FEATURES.md
```

**检查项**：
- [ ] PRODUCT.md 中的交互描述是否在 FEATURES.md 有详细展开？
- [ ] 用户体验目标是否有对应的技术实现说明？
- [ ] 视觉风格规范是否有代码级实现指引？

#### Step 3: 验证模块 AGENTS.md 实现完整性
```bash
# 遍历所有模块 AGENTS.md
find androidApp engines -name "AGENTS.md" -exec grep -l "Product Alignment" {} \;
```

**检查项**：
- [ ] 每个模块 AGENTS.md 第 5 章是否列出对应的产品指标？
- [ ] 技术决策记录是否解释了选型原因？
- [ ] 检查清单是否覆盖了常见陷阱？

#### Step 4: 生成审计报告
```markdown
## 📊 文档一致性审计报告

**审计时间**: 2026-05-03  
**审计范围**: 全量三层文档  

### ✅ 通过项 (12/15)
1. PRODUCT.md 性能指标全部在 FEATURES.md 有承接
2. Camera 模块 AGENTS.md 完整对齐拍摄延迟要求
3. Gallery 模块 LruCache 策略符合内存优化指标

### ⚠️ 警告项 (3/15)
1. **缺失链接**: PRODUCT.md 提到"背景虚化"但 FEATURES.md 未展开交互细节
2. **过时内容**: 文档美颜链路仍按双引擎描述（GPUPixel 已移除，需改为 beauty-engine 单引擎口径）
3. **不一致**: FEATURES.md 唇色色号数量(12种)与 AGENTS.md 实现(8种)不符

### ❌ 错误项 (0/15)
无

### 📝 修复建议
1. [PM] 在 FEATURES.md Section 1.3 补充背景虚化交互说明
2. [RD] 清理 Camera AGENTS.md 第 2 章废弃引擎引用
3. [RD] 统一唇色色号实现与文档描述
```

---

### 2. 文档同步更新 (Sync Mode)

**触发命令**：`sync-docs [module_name]` 或 `同步更新文档 [模块名]`

**适用场景**：新增功能后、代码修改后、需求变更后同步更新文档。

**执行流程**：

| 阶段 | 动作 | 详情 |
|------|------|------|
| Phase 1 | 识别变更范围 | `git diff --name-only HEAD~1 HEAD` |
| Phase 2 | 确定需更新文档 | 按变更类型映射到文档层级（见下表） |
| Phase 3 | 生成更新草案 | 使用 [reference.md](reference.md) §更新草案模板 |
| Phase 4 | 执行更新并验证 | `./scripts/check-doc-consistency.sh` |

**变更类型 → 文档映射**：

| 变更类型 | 需更新文档 | 负责人 |
|---------|-----------|--------|
| 新增产品功能 | `PRODUCT.md` + `FEATURES.md` | [PM] |
| 调整交互流程 | `FEATURES.md` | [PM] |
| 修改技术实现 | 模块 `AGENTS.md` | [RD] |
| 架构重构 | 模块 `AGENTS.md` + `*_TECH_SPEC.md` | [RD] |
| 性能优化 | `PRODUCT.md` (指标) + 模块 `AGENTS.md` (方案) | [PM] + [RD] |

---

### 3. 重大变更记录 (Decision Log Mode)

**触发命令**：`log-decision "[决策标题]"` 或 `记录重大变更`

**适用场景**：
- 架构级别的技术选型（如从双引擎收敛为单引擎）
- 影响多个模块的设计调整（如 Clean Architecture 分层调整）
- 性能优化的重大方案变更（如拍照从 CPU 迁移到 GPU）

**执行流程**：

#### Step 1: 收集决策信息
```markdown
## 技术决策记录模板

**决策标题**: [简洁描述，如"拍照后处理 GPU 化"]  
**决策日期**: 2026-05-03  
**决策者**: [RD 姓名]  
**影响范围**: [模块列表，如 Camera, BeautyEngine]  

### 背景与问题
[描述为什么要做这个决策，当前存在的问题]

### 备选方案
1. **方案 A**: [描述]
   - 优点: [...]
   - 缺点: [...]
2. **方案 B**: [描述]
   - 优点: [...]
   - 缺点: [...]

### 最终决策
[选择的方案及理由]

### 实施计划
- Phase 1: [任务描述] - 预计完成时间
- Phase 2: [任务描述] - 预计完成时间

### 风险评估
- 风险 1: [描述] - 缓解措施: [...]
- 风险 2: [描述] - 缓解措施: [...]

### 验收标准
- [ ] 指标 1: [量化标准]
- [ ] 指标 2: [量化标准]

### 相关文档
- PRODUCT.md: [章节链接]
- FEATURES.md: [章节链接]
- 模块 AGENTS.md: [章节链接]
- 技术专项文档: [docs/XXX_TECH_SPEC.md]
```

#### Step 2: 确认技术专项文档位置
```bash
# 拍照 GPU 化技术方案已整合至现有文档
# 无需创建新文件，直接更新以下内容：
# - docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md (GPU 拍照章节)
# - docs/02-ARCHITECTURE/ADR/ADR-002-opengl-offscreen-unified-pipeline.md (架构决策)
```

#### Step 3: 更新三层文档的引用
```markdown
# 在 PRODUCT.md 添加引用
见 `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md` 技术方案详情

# 在 FEATURES.md 添加引用
技术实现详见 `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md`

# 在模块 AGENTS.md 添加引用
架构设计参考 `docs/02-ARCHITECTURE/ADR/ADR-002-opengl-offscreen-unified-pipeline.md`
```

#### Step 4: 提交并通知团队
```bash
git add docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md docs/02-ARCHITECTURE/ADR/ADR-002-opengl-offscreen-unified-pipeline.md PRODUCT.md docs/01-PRODUCT/FEATURES.md
git commit -m "docs: 更新拍照 GPU 化技术决策文档

- 更新 BEAUTY_ENGINE_TECH_SPEC.md GPU 拍照章节
- 同步 ADR-002 架构决策记录
- 更新 PRODUCT.md 性能指标
- 同步 FEATURES.md 用户体验目标
- 关联 Camera AGENTS.md 实现细节"
```

---

### 4. 文档精简合并 (Cleanup Mode)

**触发命令**：`cleanup-docs` 或 `精简文档`

**适用场景**：
- 发现文档内容重复或冗余
- 顶层 `AGENTS.md` 出现"细节过载"
- 历史遗留的过时文档需要清理

**执行流程**：

#### Step 1: 识别冗余内容
```bash
# 查找重复内容
grep -r "拍照后处理" docs/*.md $(find androidApp engines -name "AGENTS.md")

# 检查顶层 AGENTS.md 是否包含模块级细节
wc -l AGENTS.md  # 如果超过 500 行，可能需要瘦身
```

**检查项**：
- [ ] 同一技术点在多个文档中重复描述？
- [ ] 顶层 `AGENTS.md` 包含具体代码示例？
- [ ] 存在已废弃功能的文档但未标记？
- [ ] 文档引用链断裂（A 引用 B，但 B 已删除）？

#### Step 2: 执行内容分流
根据 AGENTS.md 文档分层规范：

```markdown
## 内容分流操作

### 从顶层 AGENTS.md 移除
❌ 删除: Camera 模块的具体实现代码
✅ 保留: 全局日志规范、导入分组规则

### 移动到模块 AGENTS.md
📦 移动: 美颜引擎技术选型 -> `app/.../camera/AGENTS.md Section 2`
📦 移动: 滤镜算法实现 -> `engines/beauty-engine/AGENTS.md Section 2`

### 移动到技术专项文档
📦 移动: MediaPipe 468→106 映射详解 -> `docs/03-TECHNICAL-SPECS/FACE_LANDMARKS.md`
📦 移动: EGL 离屏渲染架构 -> `docs/02-ARCHITECTURE/ADR/ADR-002-opengl-offscreen-unified-pipeline.md`

### 标记为废弃
🗑️ 清理: 已删除模块的路径引用（如 runtime-core）-> 彻底移除或标注"已删除，git 历史可查"
```

#### Step 3: 更新双向链接
```markdown
# 在来源文档添加指引
详见模块 AGENTS.md: `androidApp/src/main/java/com/mamba/picme/features/camera/AGENTS.md`

# 在目标文档添加回溯
产品需求来源: `PRODUCT.md Section 3.1`
交互规范来源: `docs/01-PRODUCT/FEATURES.md Section 1.3`
```

#### Step 4: 验证引用完整性
```bash
# 检查所有 markdown 链接是否有效
./scripts/check-markdown-links.sh

# 确认没有悬空引用
grep -r "已废弃" docs/*.md
```

---

## 🛠️ 自动化脚本工具

### 自动化脚本

详见 [reference.md](reference.md) §脚本工具：
- `check-doc-consistency.sh` — 三层文档一致性快速检查
- `sync-doc-template.py` — 基于 git diff 生成文档更新草案

---

## 📋 执行检查清单

### 常规审计检查清单（每周执行）

- [ ] PRODUCT.md 所有 `[PERF]` 指标在代码中有对应实现
- [ ] PRODUCT.md 所有 `[PRIVACY]` 要求通过权限审查
- [ ] PRODUCT.md 所有 `[I18N]` 文案在五语资源中存在
- [ ] FEATURES.md 交互流程有对应的 UI 实现
- [ ] 所有模块 AGENTS.md 第 5 章完整且最新
- [ ] 技术专项文档与代码实现一致
- [ ] 无悬空引用（链接指向不存在的文档）
- [ ] 无重复内容（同一技术在多处详细描述）
- [ ] 废弃文档已标记或删除
- [ ] 文档修订历史记录最新

### 功能交付检查清单（每次 PR）

- [ ] 新增功能已在 PRODUCT.md 记录目标和指标
- [ ] 交互流程已在 FEATURES.md 详细说明
- [ ] 技术实现已在模块 AGENTS.md 补充规范
- [ ] 重大技术决策已创建专项文档或更新现有文档
- [ ] 三层文档术语、指标、顺序保持一致
- [ ] 所有文档链接有效且指向正确
- [ ] I18N 文案同步更新五语资源
- [ ] 性能指标有对应的监控日志

---

## 🚨 常见问题与解决方案

### Q1: 如何判断某个内容应该放在哪层文档？

**A**: 遵循以下决策树：
```
是产品目标/验收指标/红线约束？
  ├─ 是 → PRODUCT.md
  └─ 否
      ├─ 是用户可见的交互流程/体验规则？
      │   ├─ 是 → docs/01-PRODUCT/FEATURES.md
      │   └─ 否
      │       ├─ 是代码实现/架构设计/检查清单？
      │       │   ├─ 是 → 模块 AGENTS.md
      │       │   └─ 否
      │       │       └─ 是专项技术方案/重大决策？
      │       │           └─ 是 → docs/*_TECH_SPEC.md
```

### Q2: 文档更新滞后于代码怎么办？

**A**: 
1. **预防措施**：在 PR 模板中加入"文档更新确认"勾选框
2. **自动化检查**：CI 流程集成文档一致性检查脚本
3. **定期审计**：每周执行一次全量审计
4. **责任明确**：RD 负责技术文档，PM 负责产品文档，CR 负责审查

### Q3: 如何处理历史遗留的过时文档？

**A**:
1. **标记废弃**：在文档顶部添加醒目警告
   ```markdown
   > ⚠️ **已废弃**：本文档描述的方案已被 [新方案](链接) 替代，仅保留供历史参考。
   > 最后更新时间：2025-12-01
   ```
2. **迁移内容**：将仍有价值的部分迁移到新文档
3. **归档删除**：确认无人引用后，移至 `docs/archived/` 目录
4. **更新引用**：清理其他文档中对废弃文档的引用

### Q4: 多层文档内容冲突如何解决？

**A**: 遵循单一可信源原则（SSOT）：
1. **产品指标冲突**：以 `PRODUCT.md` 为准
2. **交互规则冲突**：以 `docs/01-PRODUCT/FEATURES.md` 为准
3. **技术实现冲突**：以模块 `AGENTS.md` 为准
4. **解决流程**：
   - [CR] 识别冲突点
   - [PM/RD] 确认正确版本
   - [RD] 统一更新所有相关文档
   - [QA] 验收一致性

---

## 📚 参考文档

- [AGENTS.md](AGENTS.md) - 顶层 Agent 治理规范
- [AGENTS.md](AGENTS.md) - 顶层 Agent 治理规范
- [PRODUCT.md](PRODUCT.md) - 产品需求规格说明书
- [docs/01-PRODUCT/FEATURES.md](docs/01-PRODUCT/FEATURES.md) - 功能交互细节规范
- [Google Technical Writing](https://developers.google.com/tech-writing) - 技术文档写作指南

---

## 📝 使用示例

详见 [reference.md](reference.md) §使用示例：
- **示例 1**: 日常审计流程
- **示例 2**: 功能交付后同步文档
- **示例 3**: 记录重大技术决策

---

## 🎓 最佳实践

### 1. 文档即代码 (Docs as Code)

- ✅ 文档与代码同仓库管理，版本同步
- ✅ 使用 Markdown 格式，支持 Git Diff
- ✅ 重要变更通过 PR 流程审查
- ✅ CI 集成文档一致性检查

### 2. 增量更新优于批量重构

- ✅ 每次代码变更后立即更新相关文档
- ❌ 避免积累大量变更后再统一更新
- ✅ 小步快跑，保持文档始终可用

### 3. 双向链接保持可追溯性

- ✅ PRODUCT.md → FEATURES.md → AGENTS.md 正向引用
- ✅ AGENTS.md → FEATURES.md → PRODUCT.md 反向追溯
- ✅ 使用相对路径链接，避免硬编码绝对路径

### 4. 定期审计形成闭环

- 📅 每周执行一次全量审计
- 📅 每月进行一次文档精简合并
- 📅 每季度回顾文档体系有效性
- 📊 记录审计发现的问题和修复率

---

**Skill 版本**: 1.0  
**创建日期**: 2026-05-03  
**维护者**: [CR] 规范守护者 + [CO] 协调者


## 相关文件

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.1.0 | 2026-05-03 | 初始版本 |
