# PoLang 开发、代码审查与任务标记规范（Development, CR & Task Markup）

> **版本**: 1.2  
> **状态**: 生效中  
> **最后更新**: 2026-08-03  
> **维护者**: 项目开发者  
> **上级文档**: 根目录 `AGENTS.md`（Agent First 治理）  
> **范围**: 本文档统一包含 PoLang 开发工作流、代码审查检查清单与 `[agent-task]` 任务标记规范，是研发流程的唯一事实来源。

---

## 目录

1. [双螺旋演进工作流（Spec ↔ Code Co-Evolution）](#1-双螺旋演进工作流-spec--code-co-evolution)
2. [反向链接注释规范（Spec Traceability）](#2-反向链接注释规范-spec-traceability)
3. [CI 检查规则](#3-ci-检查规则)
4. [代码审查检查清单](#4-代码审查检查清单)
5. [任务标记规范](#5-任务标记规范)
6. [术语词典（Glossary）](#6-术语词典-glossary)
7. [更新历史](#7-更新历史)

---

## 1. 双螺旋演进工作流（Spec ↔ Code Co-Evolution）

### 1.1 核心原则

Spec 驱动开发（Spec-Driven Development, SDD）要求**文档与代码始终保持同步**，但在实践中允许"探索-固化"的双向演进：

![Spec ↔ Code 双螺旋](assets/diagrams/dev-spec-code-loop.png)

### 1.2 探索-固化规则

| 阶段 | 规则 | 责任人 |
|------|------|--------|
| **探索期** | 当实现中发现 Spec 不明确时，允许先行探索代码实现，**最多 1 个 Commit** | RD |
| **固化期** | 探索完成后，必须在**同一 PR / Commit** 中更新对应 Spec 文档 | RD |
| **审查期** | CR 审查时同时审查代码 + 文档变更，文档缺失 = **一票否决** | CR |
| **验收期** | QA 验收时验证代码行为与 Spec 验收条件（AC）一致 | QA |

### 1.3 红线（Hard Limits）

- **[NEVER]** 禁止"先合并代码，后补文档"
- **[NEVER]** 禁止文档更新与代码实现分离提交（必须同一 PR / Commit）
- **[MUST]** 代码修改了 `engines/beauty-engine/` 内部实现，必须同步修改 `engines/beauty-engine/AGENTS.md`（或 PR 描述中说明原因）
- **[MUST]** 代码修改了 `engines/beauty-api/` 公开接口，必须同步修改 `engines/beauty-engine/AGENTS.md` + `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md` + 通知 App 层适配
- **[MUST]** 新增功能必须在 `PRODUCT.md` / `FEATURES.md` 中有对应需求描述
- **[MUST]** 修改相册搜索/TAG 生成相关代码，必须同步更新 `docs/03-TECHNICAL-SPECS/GALLERY_SEARCH.md` 或 `docs/03-TECHNICAL-SPECS/TAG_GENERATION.md`
- **[MUST]** 新增/修改 AI Capability 必须同步更新 `docs/04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md` 与 `COMMAND_REFERENCE.md`

---

## 2. 反向链接注释规范（Spec Traceability）

### 2.1 目的

在关键接口和实现代码中嵌入 Spec 引用，实现：
- 需求变更时快速定位受影响代码
- 代码审查时快速追溯设计意图
- 自动化工具生成 Traceability Matrix

### 2.2 注释格式

```kotlin
// Spec: <文档路径>#<章节/锚点>
// Implements: <AC-ID>（如 AC-P0-3）
// Related: <关联文档路径>#<章节>
// ChangeLog: <日期> <变更描述>（可选）
```

### 2.3 必须添加反向链接的位置

| 位置 | 示例 | 说明 |
|------|------|------|
| 公开 API 接口 | `BeautyPreviewProvider` | 关联 `engines/beauty-engine/AGENTS.md` 接口定义 |
| 核心算法实现 | `FrameSyncManager` | 关联 `BEAUTY_ENGINE_TECH_SPEC.md` 帧同步章节 |
| 架构边界类 | `api/` vs `internal/` 边界 | 关联架构约束 |
| 性能关键路径 | `CameraPreviewRenderer.render()` | 关联 `NFR_SPEC.md` 指标 |
| 降级/容灾逻辑 | `onGlWarmUpFallback()` | 关联 `BEAUTY_ENGINE_TECH_SPEC.md` 容灾降级章节 |
| 搜索召回逻辑 | `ExplicitFirstSearchPipeline` | 关联 `GALLERY_SEARCH.md` |
| TAG 生成阶段 | `TagGenerationPipeline` | 关联 `TAG_GENERATION.md` |

### 2.4 完整示例

```kotlin
// Spec: docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md#帧同步
// Implements: AC-P0-3
// Related: engines/beauty-engine/AGENTS.md
// ChangeLog: 2026-06-30 新增 missingThresholdFrames 字段
class FrameSyncManager(
    private val config: FrameSyncConfig = FrameSyncConfig.DEFAULT
) {
    // ...
}
```

### 2.5 Traceability Matrix

维护可追溯性矩阵（Traceability Matrix），记录需求 → 代码 → 测试的映射关系：

| 需求 ID | 需求描述 | 实现文件 | 测试文件 | 验收条件 |
|---------|---------|---------|---------|---------|
| FR-5 | 严格缺失处理 | `FrameSyncManager.kt` | `FrameSyncMissingTest.kt` | AC-P0-3 |
| FR-1 | FrameId 体系 | `FrameId.kt` | `FrameIdTest.kt` | AC-P0-1 |
| FR-Search-1 | 自然语言相册搜索 | `MediaSearchEngine.kt` | `SearchIntegrationTest.kt` | AC-Search-1 |

---

## 3. CI 检查规则

### 3.1 文档同步检查（Doc Sync Check）

在 CI 流水线中加入轻量级脚本（概念实现，当前以本地脚本为主）：

```yaml
# .github/workflows/doc-sync-check.yml（参考）
doc-sync-check:
  script:
    # 检查 PR 中修改了 beauty-engine 内部实现时是否同步修改了 AGENTS.md
    - python scripts/check_doc_sync.py \
        --code-path engines/beauty-engine/src/main/java/ \
        --doc-path engines/beauty-engine/AGENTS.md
    
    # 检查 PR 中修改了 api/ 接口时是否同步修改了技术文档
    - python scripts/check_doc_sync.py \
        --code-path engines/beauty-api/src/main/java/ \
        --doc-path docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md
    
    # 检查文档间内部链接有效性（⏳ 脚本未落地，设计愿景）
    - python scripts/check_doc_links.py \
        --root docs/
    
    # 检查 AGENTS.md 中提到的模块在代码中是否存在（⏳ 脚本未落地，设计愿景）
    - python scripts/check_spec_completeness.py \
        --spec engines/beauty-engine/AGENTS.md \
        --src engines/beauty-engine/src/
```

> **当前落地状态**：`scripts/check_doc_links.py` 等工具处于设计/局部脚本阶段，日常以 RD/CR 人工检查 + 自动化链接扫描为主。

### 3.2 架构合规检查（Architecture Compliance）

使用 detekt / ktlint 自动检查代码风格与基础架构红线：

```bash
# 代码风格
./gradlew ktlintCheck

# 静态分析
./gradlew detekt

# 组合检查（CI 推荐）
./gradlew :androidApp:compileDebugKotlin :androidApp:testDebugUnitTest ktlintCheck detekt
```

### 3.3 性能基线检查（Performance Baseline）

```bash
# 启动时间基准测试（需真机/模拟器）
adb shell am start -W com.mamba.picme/.MainActivity

# 与历史基线对比（退化 > 5% 需关注）
# python scripts/compare_perf_baseline.py \
#     --current benchmark-results.json \
#     --baseline perf-baseline.json \
#     --threshold 5%
```

---

## 4. 代码审查检查清单

### 4.1 CR 检查清单

#### 文档同步

- [ ] 代码变更是否同步更新了对应 Spec 文档？
- [ ] 新增接口是否补充了 `api/` 文档说明？
- [ ] 新增验收条件是否关联了 `[agent-task]` 标记？
- [ ] 反向链接注释是否正确（`// Spec: ...`）？

#### 架构合规

- [ ] `api/` 包是否引入了 `egl/` 依赖？
- [ ] App 层是否直接实例化了 `egl/` 内部类？
- [ ] 新增公开 API 是否补充了默认值与向后兼容处理？
- [ ] Domain 层是否纯净（无 UI 依赖）？

#### 性能

- [ ] 单帧处理耗时是否 ≤ 16ms？
- [ ] 参数变化时是否仅更新 uniform 而未重新编译 Shader？
- [ ] 预测补偿耗时是否 < 0.5ms / 帧？
- [ ] 内存占用是否符合 NFR 要求？

#### 资源管理

- [ ] `release()` 是否完整释放了 EGL / GL / Surface / Thread 资源？
- [ ] 新增资源是否考虑了生命周期和异常路径？
- [ ] 是否存在资源泄漏风险（未关闭的 Stream/Connection）？

#### 测试覆盖

- [ ] 新增功能是否补充了单元测试？
- [ ] 边界条件是否覆盖？
- [ ] 性能退化是否通过基准测试验证？
- [ ] 核心模块覆盖率 ≥ 70%？

#### 日志与可观测性

- [ ] 是否使用了正确的日志标签（`PoLang:ModuleName`）？
- [ ] 关键路径是否输出了结构化日志？
- [ ] 敏感信息（用户数据、模型路径）是否脱敏？

#### 国际化 (I18N)

- [ ] 新增文案是否覆盖 EN / zh-CN / zh-TW？
- [ ] 禁止硬编码用户可见字符串？
- [ ] 复数/性别等语言特性是否考虑？

### 4.2 一票否决项

以下任一情况，CR 必须 **Request Changes**：

#### 文档不同步

- 代码修改了实现但未更新对应 Spec
- 新增 API 无文档说明
- `[agent-task]` 标记缺失或错误

#### 架构越界

- `api/` 依赖 `egl/`
- App 直接实例化 `egl/` 类
- Domain 层引入 UI 依赖

#### 性能退化

- 单帧处理耗时 > 20ms
- 帧率下降 > 5%
- 内存占用超出 NFR 红线

#### 资源泄漏

- `release()` 未覆盖新增资源
- 未关闭的 Stream/Connection
- 静态集合无限增长（无清理机制）

#### 无测试覆盖

- 新增功能无单元测试
- 核心逻辑无集成测试
- 边界条件未覆盖

#### I18N 缺失

- 新增文案未覆盖五语
- 硬编码用户可见字符串

### 4.3 架构合规检查

#### ArchUnit 规则

```kotlin
// api/ 包不依赖 egl/
@ArchTest
val `api package should not depend on egl package` = noClasses()
    .that().resideInAPackage("..api..")
    .should().dependOnClassesThat().resideInAPackage("..egl..")

// App 层不直接实例化 egl/ 内部类
@ArchTest
val `app should not instantiate egl classes directly` = noClasses()
    .that().resideInAPackage("..features..")
    .should().callConstructorWhere(
        target(owner(resideInAPackage("..egl..")))
    )
```

#### detekt 规则

```kotlin
// 禁止在构造函数中启动渲染线程
class NoRenderThreadInConstructor : Rule() {
    override fun visitClass(klass: KtClass) {
        if (klass.hasAnnotation("GlThread") || klass.name?.contains("Renderer") == true) {
            // 检查构造函数中是否启动 Thread
        }
    }
}
```

#### 手动检查项

- [ ] 查看 import 列表，确认无违规依赖
- [ ] 检查构造函数，确认无线程启动
- [ ] 验证包结构符合 Clean Architecture

### 4.4 性能基线检查

#### CI 检查脚本

```yaml
perf-baseline-check:
  script:
    # 启动时间基准测试
    - ./gradlew benchmark:coldStartupBenchmark
    
    # 单帧处理耗时基准测试
    - ./gradlew benchmark:frameProcessingBenchmark
    
    # 与基线对比，退化 > 5% 则失败（⏳ 脚本未落地，设计愿景）
    - python scripts/compare_perf_baseline.py \
        --current benchmark-results.json \
        --baseline perf-baseline.json \
        --threshold 5%
```

#### 关键指标

| 指标 | 红线 | 目标 | 测量方法 |
|------|------|------|----------|
| 冷启动 → 首帧预览 | ≤ 500ms | ≤ 400ms | `adb shell am start -W` |
| 预览帧率（高端机） | ≥ 30fps | ≥ 55fps | `BeautyPerfStats.fps` |
| 单帧处理耗时 | ≤ 16ms | ≤ 12ms | Systrace / 自定义计时 |
| 参数响应延迟 | ≤ 100ms | ≤ 50ms | 人工体感 + 高速摄像 |
| 拍照后处理（1080p） | ≤ 300ms | ≤ 200ms | `PhotoProcessorImpl` 耗时 |

#### 调试工具

- **Systrace**: 分析主线程耗时
- **Android Profiler**: 监控 CPU/内存
- **自定义计时器**: `PerformanceTracker.start("tag")` / `.end()`
- **调试浮层**: 实时显示 FPS/耗时

### 4.5 文档同步检查

#### CI 检查脚本

```yaml
doc-sync-check:
  script:
    # 检查 PR 中修改了 render/ 实现时是否同步修改了 AGENTS.md
    - python scripts/check_doc_sync.py \
        --code-path engines/beauty-engine/src/main/java/com/mamba/picme/beauty/render/ \
        --doc-path engines/beauty-engine/AGENTS.md
    
    # 检查 PR 中修改了 api/ 接口时是否同步修改了技术文档
    - python scripts/check_doc_sync.py \
        --code-path engines/beauty-api/src/main/java/com/mamba/picme/beauty/api/ \
        --doc-path docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md
    
    # 检查文档间内部链接有效性（⏳ 脚本未落地，设计愿景）
    - python scripts/check_doc_links.py \
        --root docs/
    
    # 检查 AGENTS.md 中提到的模块在代码中是否存在（⏳ 脚本未落地，设计愿景）
    - python scripts/check_spec_completeness.py \
        --spec engines/beauty-engine/AGENTS.md \
        --src engines/beauty-engine/src/
```

#### 文档更新检查项

- [ ] `FEATURES.md` 已更新（如有交互变更）
- [ ] `*_TECH_SPEC.md` 已更新（如实现细节变更）
- [ ] `CAPABILITY_REGISTRY.md` 已更新（如新增 Capability）
- [ ] `COMMAND_REFERENCE.md` 已更新（如新增命令）
- [ ] 反向链接注释已添加（`// Spec: ...`）

### 4.6 CR 评论模板

#### ✅ 通过

```markdown
## CR 结果：✅ Approved

### 亮点
- 代码结构清晰，命名规范
- 单元测试覆盖完善
- 性能优化到位

### 建议（非阻塞）
- 可考虑提取公共逻辑为扩展函数
- 日志标签可更统一
```

#### ❌ 需要修改

```markdown
## CR 结果：❌ Request Changes

### 阻塞问题

#### 1. 文档不同步
- **位置**: `engines/beauty-engine/src/main/java/com/mamba/picme/beauty/render/CameraPreviewRenderer.kt`
- **问题**: 修改了 `render()` 实现但未更新 `AGENTS.md`
- **修复**: 同步更新 `engines/beauty-engine/AGENTS.md#render-pipeline` 章节

#### 2. 性能退化
- **位置**: `FrameSyncManager.query()`
- **问题**: 新增锁导致单帧耗时从 12ms → 18ms
- **修复**: 改用 `ConcurrentHashMap` 替代 synchronized

#### 3. I18N 缺失
- **位置**: `strings.xml`
- **问题**: 新增文案 "美颜总开关" 未覆盖英文和繁体
- **修复**: 补充五语翻译
```

---

## 5. 任务标记规范

### 5.1 目的

本文档定义 `[agent-task]` 结构化标记规范，用于在 Spec 文档（`PRODUCT.md`、`PRD-*.md`、`FEATURES.md`）中直接嵌入**可执行、可追踪、可验证**的任务描述。外层编排脚本可自动解析此类标记，生成标准化 Task JSON，直接驱动 RD/QA Agent 执行，实现"需求变更 → 开发任务"的自动转换。

### 5.2 标记格式

#### 基本语法

```markdown
### <功能标题> [agent-task:<task_id>]
- **Assignee**: <RD | QA | CR | PM>
- **Scope**: `<文件路径1>`, `<文件路径2>`
- **Expected Change**:
  1. <具体变更描述>
  2. <具体变更描述>
- **DependsOn**: <task_id_1>, <task_id_2>
- **EstimatedEffort**: <Xd | Xh | Xw>
- **Priority**: <P0 | P1 | P2>
- **Acceptance**: <AC-P0-X | AC-P1-X>
```

#### 字段说明

| 字段 | 必填 | 格式 | 说明 |
|------|------|------|------|
| `task_id` | ✅ | `[a-z0-9-]+` | 全局唯一任务标识，如 `fsm-001`、`beauty-042` |
| `Assignee` | ✅ | `RD` / `QA` / `CR` / `PM` | 负责执行的角色 |
| `Scope` | ✅ | 逗号分隔的文件路径 | 预期修改的代码文件或模块 |
| `Expected Change` | ✅ | 有序列表 | 具体的代码变更预期，RD Agent 据此执行 |
| `DependsOn` | ❌ | 逗号分隔的 task_id | 前置依赖任务 |
| `EstimatedEffort` | ❌ | `Xd` / `Xh` / `Xw` | 预估工作量（天/小时/周）|
| `Priority` | ✅ | `P0` / `P1` / `P2` | 与验收标准对齐 |
| `Acceptance` | ✅ | `AC-P0-X` / `AC-P1-X` | 关联的验收条件 ID |

#### 完整示例

```markdown
### FR-5：严格缺失处理 [agent-task:fsm-005]
- **Assignee**: RD
- **Scope**: `engines/beauty-engine/src/main/java/com/mamba/picme/beauty/internal/framesync/FrameSyncManager.kt`, `engines/beauty-engine/src/main/java/com/mamba/picme/beauty/render/CameraPreviewRenderer.kt`
- **Expected Change**:
  1. 在 `FrameSyncConfig` 中新增 `missingThresholdFrames: Int = 3` 字段
  2. 修改 `FrameSyncManager.query()`，当 `syncMode = STRICT` 且帧差 > `missingThresholdFrames` 时返回 `SyncStatus.MISSING`
  3. 在 `CameraPreviewRenderer.applySyncResultToRenderer()` 中，当 `syncStatus = MISSING` 时设置 `uHasFace = 0f`
  4. 更新 `BeautyPerfStats`，增加 `framesSinceDetection` 字段
- **DependsOn**: fsm-001, fsm-002
- **EstimatedEffort**: 2d
- **Priority**: P0
- **Acceptance**: AC-P0-3
```

### 5.3 使用位置

#### 允许使用 `[agent-task]` 的文档

| 文档 | 用途 |
|------|------|
| `PRODUCT.md` | 记录高层功能需求对应的开发任务 |
| `docs/01-PRODUCT/FEATURES.md` | 记录具体功能需求的开发任务 |
| `docs/01-PRODUCT/FEATURES.md` | 记录交互变更对应的 UI/UX 任务 |
| `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md` | 记录技术实现任务 |

#### 禁止使用 `[agent-task]` 的位置

- `AGENTS.md`（模块级实现规范，不应包含任务分配）
- `README.md`（对外文档）
- 代码注释（使用反向链接注释规范，见第 2 节）

### 5.4 自动化解析规则

#### 解析脚本输入

> **注意**：`scripts/parse_kimi_tasks.py` 尚未落地（设计中）。以下为预期接口，待实现后生效。

```bash
# ⏳ 设计中，脚本尚未创建
# python scripts/parse_agent_tasks.py \
#   --input docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md \
#   --output tasks/beauty-engine-tasks.json
```

#### 输出 JSON 格式

```json
{
  "source": "docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md",
  "extracted_at": "2026-05-14T10:00:00Z",
  "tasks": [
    {
      "task_id": "fsm-005",
      "title": "FR-5：严格缺失处理",
      "assignee": "RD",
      "scope": [
        "engines/beauty-engine/src/main/java/com/mamba/picme/beauty/internal/framesync/FrameSyncManager.kt",
        "engines/beauty-engine/src/main/java/com/mamba/picme/beauty/render/CameraPreviewRenderer.kt"
      ],
      "expected_change": [
        "在 FrameSyncConfig 中新增 missingThresholdFrames: Int = 3 字段",
        "修改 FrameSyncManager.query()...",
        "在 CameraPreviewRenderer.applySyncResultToRenderer()...",
        "更新 BeautyPerfStats..."
      ],
      "depends_on": ["fsm-001", "fsm-002"],
      "estimated_effort": "2d",
      "priority": "P0",
      "acceptance": "AC-P0-3",
      "status": "pending"
    }
  ]
}
```

#### 驱动执行流程

![Agent 任务编排蓝图](assets/diagrams/dev-agent-orchestration.png)

### 5.5 任务状态流转

任务状态由自动化系统维护，不存储在 Spec 文档中：

| 状态 | 说明 |
|------|------|
| `pending` | 待分配，未开始 |
| `in_progress` | RD Agent 已认领，执行中 |
| `review` | 代码已完成，待 CR 审查 |
| `testing` | CR 通过，待 QA 验收 |
| `done` | QA 验收通过，任务完成 |
| `blocked` | 被依赖任务阻塞 |
| `failed` | 执行失败，需人工介入 |

### 5.6 约束与红线

- **[MUST]** 每个 `[agent-task]` 必须关联至少一个 `Acceptance` ID（`AC-P0-X` 或 `AC-P1-X`）
- **[MUST]** `task_id` 全局唯一，格式为 `<模块缩写>-<三位数字>`
- **[MUST]** `Scope` 中的文件路径必须真实存在于代码库中
- **[NEVER]** 禁止在 `[agent-task]` 中描述实现细节（如具体算法），实现细节应留在 `AGENTS.md`
- **[NEVER]** 禁止将 `[agent-task]` 嵌入代码注释中

### 5.7 示例：帧同步美妆系统任务集

```markdown
## 6. 版本规划（含 [agent-task]）

### Phase 1：基础设施（1~2 周）

#### FrameId 体系 [agent-task:fsm-001]
- **Assignee**: RD
- **Scope**: `engines/beauty-api/src/main/java/com/mamba/picme/beauty/api/FrameId.kt`
- **Expected Change**:
  1. 创建 `@JvmInline value class FrameId(val value: Long)`
  2. 实现 `AtomicLong` 计数器，`next()` 方法
  3. 定义 `INVALID = FrameId(0L)`
- **EstimatedEffort**: 4h
- **Priority**: P0
- **Acceptance**: AC-P0-1

#### FrameSyncManager 骨架 [agent-task:fsm-002]
- **Assignee**: RD
- **Scope**: `engines/beauty-engine/src/main/java/com/mamba/picme/beauty/internal/framesync/FrameSyncManager.kt`
- **Expected Change**:
  1. 创建 `FrameSyncManager` 单例类
  2. 实现 `ResultStore`（`ConcurrentHashMap<FrameId, DetectionResult>`）
  3. 实现 `MatchEngine`：精确匹配 → 历史回退 → 预测补偿 → 缺失隐藏
  4. 实现 `query(currentFrameId)` 公共 API
- **DependsOn**: fsm-001
- **EstimatedEffort**: 2d
- **Priority**: P0
- **Acceptance**: AC-P0-1, AC-P0-2

#### DetectionQueue 改造 [agent-task:fsm-003]

> **⚠️ 审计备注（2026-06）**：DetectionQueue 未落地（`DetectionQueue.kt` 不存在）。此 [agent-task] 标记的目标文件路径无效。当前使用同步检测路径。如需实施异步检测改造，应先创建 DetectionQueue.kt 再更新此任务标记。

- **Assignee**: RD
- **Scope**: `engines/beauty-engine/src/main/java/com/mamba/picme/beauty/internal/framesync/DetectionQueue.kt`（⏳ 设计中，未落地）
- **Expected Change**:
  1. 创建 `DetectionQueue` 类，深度限制 2，超时 200ms
  2. 改造人脸检测线程为消费队列模式
  3. 检测结果携带 `FrameId` 存入 `FrameSyncManager`
- **DependsOn**: fsm-002
- **EstimatedEffort**: 1d
- **Priority**: P0
- **Acceptance**: AC-P0-1

### Phase 2：时序对齐与严格缺失（1~2 周）

#### 渲染管线集成 [agent-task:fsm-004]
- **Assignee**: RD
- **Scope**: `engines/beauty-engine/src/main/java/com/mamba/picme/beauty/render/CameraPreviewRenderer.kt`, `engines/beauty-engine/src/main/java/com/mamba/picme/beauty/render/BeautyRenderer.kt`
- **Expected Change**:
  1. `CameraPreviewRenderer` 渲染循环中调用 `FrameSyncManager.query(currentFrameId)`
  2. `BeautyRenderer` 新增 `updateSyncedFacePoints106()` + `setHasFace()`
  3. `FaceMakeupPass` 新增 `updateFaceLandmarksSynced()` 入口
- **DependsOn**: fsm-002, fsm-003
- **EstimatedEffort**: 2d
- **Priority**: P0
- **Acceptance**: AC-P0-2, AC-P0-3

#### 严格缺失处理 [agent-task:fsm-005]
- **Assignee**: RD
- **Scope**: `engines/beauty-engine/src/main/java/com/mamba/picme/beauty/internal/framesync/FrameSyncManager.kt`, `engines/beauty-engine/src/main/java/com/mamba/picme/beauty/render/CameraPreviewRenderer.kt`
- **Expected Change**:
  1. `FrameSyncConfig` 新增 `missingThresholdFrames: Int = 3`
  2. `query()` 严格模式：帧差 > 阈值时返回 `MISSING`
  3. `applySyncResultToRenderer()`：`MISSING` 时 `uHasFace = 0`
- **DependsOn**: fsm-004
- **EstimatedEffort**: 1d
- **Priority**: P0
- **Acceptance**: AC-P0-3

#### 调试指标接入 [agent-task:fsm-006]
- **Assignee**: RD
- **Scope**: `engines/beauty-engine/src/main/java/com/mamba/picme/beauty/api/BeautyPerfStats.kt`, `androidApp/src/main/java/com/mamba/picme/features/camera/CameraPreviewContent.kt`（性能浮层实现于此，原 `debug/PerfOverlay.kt` 已不存在）
- **Expected Change**:
  1. `BeautyPerfStats` 增加 `detectionLatencyMs` / `syncStatus` / `predictedOffsetPx` / `framesSinceDetection`
  2. 调试浮层展示新增指标
- **DependsOn**: fsm-004
- **EstimatedEffort**: 1d
- **Priority**: P1
- **Acceptance**: AC-P1-2

### Phase 3：预测补偿与录制专项（1~2 周）

#### MotionTracker 速度外推 [agent-task:fsm-007]
- **Assignee**: RD
- **Scope**: `engines/beauty-engine/src/main/java/com/mamba/picme/beauty/internal/framesync/MotionTracker.kt`
- **Expected Change**:
  1. 创建 `MotionTracker` 类，保留最近 3 帧历史
  2. 实现 `predict(fromFrameId, toFrameId, maxRatio)` 速度外推
  3. 位移约束：不超过上一帧位移的 150%
- **DependsOn**: fsm-002
- **EstimatedEffort**: 2d
- **Priority**: P1
- **Acceptance**: AC-P1-1

#### 录制场景帧同步验证 [agent-task:fsm-008]
- **Assignee**: QA
- **Scope**: `androidApp/src/androidTest/java/com/mamba/picme/camera/VideoRecordingSyncTest.kt`
- **Expected Change**:
  1. 编写录制快转头测试用例
  2. 编写录制人脸出画入画测试用例
  3. 逐帧分析录制视频，验证妆容偏差
- **DependsOn**: fsm-004, fsm-005
- **EstimatedEffort**: 2d
- **Priority**: P0
- **Acceptance**: AC-P0-5
```

---

## 6. 术语词典（Glossary）

维护统一的术语定义，确保 Spec 语义一致性：

| 术语 | 英文 | 定义 | 禁用别名 |
|------|------|------|---------|
| 大美丽 | Big Beauty | PoLang 自研 OpenGL ES + EGL 美颜引擎 | 美颜引擎、自研引擎 |
| 帧同步 | Frame Sync | 人脸检测帧与渲染帧的时间对齐机制 | 同步系统、时序对齐 |
| 妆容甩飞 | Makeup Detachment | 妆容与人脸位置不同步的分离现象 | 妆容滞后、妆容漂移 |
| 悬空残留 | Hover | 人脸出画后妆容仍停留在屏幕上的现象 | 妆容残留 |
| 严格缺失 | Strict Missing | 无检测结果 N 帧后强制隐藏妆容的策略 | 缺失隐藏、严格模式 |
| 预测补偿 | Prediction Compensation | 基于运动轨迹预测人脸位置的补偿算法 | 运动预测、预测算法 |
| FaceId / FrameId | FrameId | 全局单调递增帧标识符 | 帧ID、frame_id |
| 零拷贝 | Zero Copy | GPU 管线中禁止 CPU-GPU 数据传输 | 无拷贝、直通 |
| 降级 | Fallback | 引擎异常时自动回退到基础预览 | 回退、降级策略 |
| 库化 | Library-ization | 将引擎模块演进为独立发布库 | 模块化、独立库 |
| TAG 生成 | Tag Generation | 本地 3-Pass 照片标签生成管道 | 打标、标签扫描 |
| 语义召回 | Semantic Recall | MobileCLIP 文本-图像相似度召回 | CLIP 搜索 |
| 显式召回 | Explicit Recall | 基于结构化字段（时间/地点/人脸/TAG）的 SQL 召回 | 规则召回 |

---

## 7. 更新历史

| 版本 | 日期 | 变更 | 作者 |
|------|------|------|------|
| 1.0 | 2026-05-14 | 初版，定义双螺旋演进工作流、反向链接规范、CI 检查规则 | PM |
| 1.1 | 2026-06-30 | 更新文档引用（GALLERY_SEARCH / AUTO_TAG / BEAUTY_ENGINE_TECH_SPEC），补充搜索/TAG 红线、I18N/隐私否决项、当前 CI 命令 | CO |
| 1.2 | 2026-07-08 | 合并 `CODE_REVIEW_CHECKLIST.md` 与 `TASK_MARKUP_SPEC.md`，统一为研发流程唯一事实来源；更新目录与元信息 | CO |
