# polang AI Agent 系统：唯一事实来源 (SSOT)

> **版本**：2.4（仓库重组版）  
> **状态**：生效中  
> **最后更新**：2026-08-09  
> **维护者**：项目开发者  
> **历史合并说明**：本文档由根目录 `AGENTS.md` 与 `agents/README.md` 合并而成。
>
> 2026-08-03 更新：移除已退役的 CO/PM/RD/CR/QA 角色协作管线（`agents/*_agent.md` 已删；该管线从未被 kimi 实际调度，kimi 改用全局子代理）。本文档聚焦**架构原则、全局红线、文档治理与工具脚本**，不再定义强制角色编排流程。
>
> 2026-08-07 更新：仓库重组 Phase 3——项目改名 polang（原 langchain4android），`app/` → `androidApp/`（`:app` → `:androidApp`），引擎模块迁入 `engines/`（`:beauty-api`/`:beauty-engine`/`:mnn-core`/`:sentencepiece` → `:engines:*`），Gradle `rootProject.name` = "polang"。
>
> 2026-09-25 更新：§7 索引登记当日三篇 chat 体系方案——ADR-014（富内容渲染，已定稿且 Android 落地）、意图路由契约 spec、工程师任务卡 spec（两篇均在途，实施时按各自 §8/§测试决策原子同步文档）。

> 本文档为**顶层治理文档**，定义 Agent First 的研发规范。
>
> **polang** 是 PoLang（破浪相册）应用的 Monorepo：Android 应用为主体，KMP 跨端改造进行中。

---

## 1. 项目背景：Agent First 三重实验

polang 是一个元实验（meta-experiment），同时探索三个层次：

| 层次 | 实验对象 | 核心问题 |
|------|----------|----------|
| **基础库** | LangChain4j 风格 Android Agent 基础库（原 `:agent-core` fork，已删除） | LangChain4j 风格 API 能否在 Android 高效运行？（结论：自维护 vendored fork 不可持续——冻结上游、死重多、0 测试；2026-08 全面迁移至 JetBrains Koog，fork 模块已删除） |
| **运行时** | PoLang Agent 编排层（`:shared` KMP 模块 + `:androidApp` 组合根） | LLM 能否成为应用的中枢神经系统？ |
| **服务端** | PoLang Server（`server/` Ktor 后端） | AI 网关、账号体系、管理后台能否支撑端侧 Agent？ |
| **架构层** | Agent First 客户端框架 | 什么样的架构让 Agent 最高效？ |
| **流程层** | Agent First 研发流程 | Agent 如何通过编排 Tools 完成开发？ |

**核心假设**：当基础设施原子化为 Tools 层后，Agent 可以从「辅助工具」进化为「主导力量」。

---

## 2. Agent First 的代码架构原则

polang 的所有代码遵循以下原则，确保 Agent 能高效理解、修改、验证：

### 2.1 显式优于隐式（Explicit > Implicit）

```kotlin
// ❌ 隐式依赖：AI 需要全局搜索理解生命周期
object BeautyEngine {
    fun getInstance() = instance
}

// ✅ 显式注入：构造函数即文档
class CameraViewModel(
    private val beautyEngine: BeautyEngine,
    private val agentUseCase: AiAgentUseCase,
    private val settingsRepository: SettingsRepository
) : ViewModel()
```

**收益**：通过构造函数签名，AI 即可理解组件协作关系，无需跨文件搜索。

### 2.2 枚举优于条件（Exhaustive > Conditional）

```kotlin
// ❌ 布尔标志组合爆炸
class CameraState(
    val isLoading: Boolean,
    val hasError: Boolean,
    val isPreviewing: Boolean
)

// ✅ 枚举所有合法状态
sealed interface CameraState {
    data object Initializing : CameraState
    data class Previewing(val settings: BeautySettings) : CameraState
    data class Error(val reason: String) : CameraState
}
```

**收益**：状态空间显式编码，AI 可枚举所有边界情况，不会遗漏。

### 2.3 自描述优于注释（Self-Describing > Commented）

```kotlin
// ❌ 注释与代码可能脱节
// 调节美颜参数
fun adjust(params: Map<String, Int>) // AI 不知道有哪些参数

// ✅ 类型系统即文档
data class BeautyParameters(
    val smooth: IntRange = 0..100,
    val whiten: IntRange = 0..100,
    val slimFace: IntRange = -50..50
)
fun adjust(params: BeautyParameters) // 类型即契约
```

**收益**：类型系统强制一致性，AI 可靠类型推导而非易腐烂的注释。

### 2.4 结构化可观测性（Structured Observability）

```kotlin
// ❌ 纯文本日志，需正则解析
Log.d("Camera", "Agent parsed: $input -> $intent")

// ✅ 结构化事件，AI 可直接消费
data class AgentCommandParsedEvent(
    val rawInput: String,
    val parsedIntent: Intent,
    val confidence: Float,
    val timestamp: Long
) : LogEvent

Logger.log(AgentCommandParsedEvent(...))
```

**收益**：结构化日志可被 AI 消费，实现自我诊断和自我改进。

> **实现状态（2026-07-26）**：结构化可观测性已有首个落地件——Agent 终端运行感知层三件套（`polang_llm_log.db` 的 `llm_call_log` 推理层 / `tool_call_log` 行动层 / `js_run_log` 端侧 JS 沙盒执行层），事件模型引擎无关、可被 AI 消费（详见 `docs/03-TECHNICAL-SPECS/JS_ENGINE_TECH_SPEC.md` §10 运行可观测性；原设计稿已随交付清理）。其余模块仍以 `PoLang:` 前缀标签 + `Log.d/w/e` 为主，结构化事件（如 `AgentCommandParsedEvent`）尚未在全局范围强制要求，是后续 Phase 3 的推进方向。

---

## 3. 工具与自动化

基础设施原子化为 **Tools**，供 AI 编排调用，并通过脚本形成闭环验证。

### 3.1 Tools 层

| Tool | 功能 | 输入 | 输出 | 状态 |
|------|------|------|------|------|
| `CompileTool` | 代码编译检查 | 源码变更 | 编译结果/错误日志 | 🔄 脚本实现 (`./gradlew`) |
| `InstallTool` | 安装到设备 | APK | 安装状态 | 🔄 脚本实现 (`adb install`) |
| `ScreenshotTool` | 自动截屏 | 设备连接 | 截图文件 | 🔄 脚本实现 (`adb screencap`) |
| `LogAnalysisTool` | 结构化日志分析 | Logcat | 结构化事件 | 📋 设计愿景 |
| `DocSyncTool` | 文档同步检查 | Git diff | 需更新文档列表 | 📋 设计愿景 |
| `ScreenshotDiffTool` | UI 回归检测 | 截图对比 | Diff 报告 | 🔄 脚本实现 (`screenshot-diff.py`) |
| `PerfBaselineTool` | 性能基线对比 | 性能指标 | 对比报告 | 📋 设计愿景 |

> **实现状态（2026-07）**：`CompileTool`、`InstallTool`、`ScreenshotTool` 已通过 Gradle 脚本和 adb 命令落地；`ScreenshotDiffTool` 已有 `scripts/screenshot-diff.py` 实现；`LogAnalysisTool`、`DocSyncTool`、`PerfBaselineTool` 仍为设计愿景，待 Phase 3 基础设施完善。

**关键转变**：从「人类操作脚本」到「AI 编排 Tools」。

### 3.2 自动化脚本

| 脚本 | 用途 |
|------|------|
| `./scripts/ai-gate.sh` | 代码质量门禁 |
| `./scripts/auto-dev-loop.sh` | 编译→安装→启动→截屏→日志 |
| `./scripts/impact-analyzer.sh` | 变更影响分析 |
| `./scripts/doc-sync-guardian.sh` | 文档同步检查 |
| `./scripts/test-generator.py` | 基于 public 方法生成测试骨架 |
| `./scripts/screenshot-diff.py` | UI 回归检测 |
| `./scripts/play-publish.sh` | Google Play 自动发布（GPP 封装：上传 AAB / 同步文案 / 轨道晋升），手册见 `docs/05-DEVELOPMENT/GOOGLE_PLAY_RELEASE_AUTOMATION.md` |

> **闭环验证习惯**：代码改动后走「编译 → 安装 → 测试 → 日志」闭环（`auto-dev-loop.sh`）；失败时基于日志定位根因再修，单任务自动重试最多 2 次，不盲目堆尝试。

**收益**：标准化工具消除人工操作的不确定性，AI 可编排完成复杂验证。

### 3.3 Token 优化

- 推进消息简短，聚焦增量信息，不重复已知上下文。
- 用 `TodoWrite` 追踪任务进度，替代长篇文字汇报。

### 3.4 工作区隔离（强制）

- 任何代码改动任务开工前，**必须先建隔离工作区**：检测当前是否已在 worktree；不在则在 `.worktrees/` 下创建独立 worktree + 专用分支（遵循 `using-git-worktrees` skill），征得用户同意后动工
- **禁止**在承载未提交改动或不相干特性分支的当前工作区直接改代码
- 提交前确认分支归属：fix/feat 只落到自己的专用分支，绝不混入其他特性分支的历史
- 工作区已存在不属于本任务的未提交改动时，只 `git add` 本任务相关文件，其余保持不动

### 3.5 AI 工具模型分工（逻辑档位）

强/弱是相对的——本项目用「**逻辑档位 + 各工具自绑物理模型**」解耦：角色只声明逻辑档，物理模型各工具自配，换模型只改绑定、不动角色。

| 逻辑档 | 含义 | Claude Code 绑定 | kimi-code 绑定 |
|--------|------|------------------|----------------|
| **STRONG** | 复杂推理:架构/评审/调试/规划 | glm-5.2(Fable 档) | K3(primary) |
| **WEAK** | 便宜 fan-out:搜索/探索/梳理/摘要 | glm-5.1(Haiku 档) | glm-5.2(secondary) |

> 「强模型」在两工具指代**不同物理模型**(CC=glm-5.2、kimi=K3),但逻辑语义一致:都指「该工具能用的最强模型」。两套机制不同(CC 用 frontmatter 别名(`fable`)、kimi 用派发 `model=primary/secondary`),语义统一。

**角色 → 档位是各工具的策略,可以不同**:

| 角色 | CC | kimi | 说明 |
|------|----|------|------|
| 架构/规划 | STRONG(`.claude/agents/planner.md`) | STRONG(K3) | 一致 |
| 强推理兜底 | STRONG(`reasoner.md`) | STRONG(K3 主循环) | 一致 |
| 根因调试 | STRONG(`debugger.md`) | STRONG(K3 主循环) | 一致 |
| 代码评审 | STRONG(`reviewer.md`,`fable`) | **WEAK**(glm-5.2,`review.md`) | kimi 故意用 GLM 审 K3 → **跨模型交叉验证** |
| 搜索/探索 | WEAK(内置 Explore,glm-5.1) | WEAK(内置 explore) | 一致 |

> 这正体现了抽象的价值:同一「评审」角色,CC 给最强档,kimi 却故意用弱档做交叉验证——**角色→档是策略,随工具而定**,而档→模型的绑定各管各。
>
> **CC 自定义 agent 用 `model: fable` 表达 STRONG 档**。fable 别名经实测映射正确(`ANTHROPIC_DEFAULT_FABLE_MODEL_NAME=glm-5.2`;主循环 `model:fable` 实跑 glm-5.2,**无静默回落**)。**但注意:本环境 frontmatter `model:` 字段(及 Agent 工具 `model` 参数)对子代理实际是空操作**——实测显式传 `model:haiku` 仍跑 glm-5.2、历史 85 个子代理 LLM 调用全为 glm-5.2 无一 glm-5.1,子代理恒继承主会话模型(当前 fable→glm-5.2;含上表 WEAK 档的内置 Explore 实际亦然)。故 frontmatter 写 `fable` 仅作语义/未来兼容占位,实际档位由主会话 `model:fable` 决定(已配,自动跟 Fable 重映射)。kimi 原生用 `model=primary/secondary` 逻辑档,无需显式 id。

---

## 4. 文档体系（AI 可解析）

polang 的文档设计为**机器可读、交叉引用完整**，AI 可直接解析为执行计划。

### 4.1 文档层级

```
PRODUCT.md (What: 目标与约束)
    ↓ 引用
docs/01-PRODUCT/FEATURES.md (How: 交互与体验)
    ↓ 引用
模块 AGENTS.md (Implementation: 实现约束)
    ↓ 反向链接
代码实现
```

### 4.2 任务标记规范 `[agent-task]`

AI 可直接解析 Spec 中的任务标记，生成执行计划：

```markdown
### 调节美颜参数 [agent-task:beauty-001]
- **Scope**: `domain/agent/capability/ImageEditCapability.kt`
- **Expected Change**:
  1. 实现 Capability 接口
  2. 注册到 CapabilityRegistry
  3. 添加单元测试
- **Priority**: P0
- **Acceptance**: AC-P0-1
```

**收益**：需求→任务→代码的转换自动化，减少信息损耗。

---

## 5. 全局红线（不可突破）

| 红线 | 定义 | 验证方式 |
|------|------|----------|
| **[PRIVACY]** | 禁止向远程大模型/推理服务器上传用户图片/视频文件（媒体处理 100% 端侧）；文本/元数据/相册摘要可走远程推理；飞书/Telegram 等用户自配置通道回传媒体不在此列（ADR-008） | 网络抓包（远程推理请求体无图片/视频）、权限清单扫描 |
| **[PERF]** | 交互 < 100ms，快门 < 50ms | 性能测试、人工体感 |
| **[I18N]** | 禁止硬编码，五语同步（EN/zh-CN/zh-TW/ES/FR） | 资源文件检查 |
| **[DOC-SYNC]** | 代码变更必须同步文档 | CI 文档检查 |
| **[AGENT-FIRST]** | 新代码必须遵循 Agent First 原则 | 代码审查 |
| **[PARITY]** | 双端 UI 一致性：信息层级/布局结构/功能默认/文案状态/无障碍语义零容差一致。新页面 Android 定稿后必须固化 spec；iOS 实现必须读 spec 不读 Android 源码；后续修改走三同步（spec + 双端代码 + token） | Spec 完整性检查、截图比对、gap analysis。详见 `docs/08-UI-SPECS/PARITY_MASTER_PLAN.md` |

> **版本优先级原则（2026-08-09 用户定，iOS 首个版本完成前有效）**：**功能 > UI > 性能**。资源分配与排期按此序——功能缺位优先补，UI 对齐/美观其次，性能实测与优化最后（发版门前再集中过 [PERF] 红线）。例外：崩溃、数据错误、明显卡顿到不可用的缺陷属「功能可用性」范畴，不按性能往后排。

---

## 6. 研究问题与度量

### 6.1 待验证的假设

1. **AI 可处理代码规模上限**：当前项目以 Demo 工程（Kotlin）+ shared KMP 模块为主（agent-core Java fork、runtime-core 均已于 2026-08 删除），上限是多少？
2. **AI 重构能力**：AI 能否主导跨模块架构重构？
3. **自动修复成功率**：AI 自动修复编译/运行时错误的成功率？
4. **文档驱动开发的效率**：相比传统流程，AI 协作的效率提升？
5. **Tools 扩展性**：新 Tools 能否被 Agent 自动发现和集成？

### 6.2 度量指标

| 指标 | 当前基线 | 目标 |
|------|----------|------|
| 自动修复成功率 | 待收集 | > 70% |
| 文档→代码一致性 | 待评估 | > 95% |
| AI 生成代码占比 | 待评估 | > 60% |
| 人工介入频次 | 待评估 | < 20% |

> **实现状态（2026-07）**：以上度量指标目前均为手动统计或待收集状态。自动化采集代码尚未落地（如自动修复成功率统计脚本、文档一致性 CI 检查工具等），是后续 Phase 3 的基础设施建设重点。

---

## 7. 文档索引

| 类型 | 文档 |
|------|------|
| **顶层治理** | `AGENTS.md`（本文档） |
| **★ 双端 UI 一致性总纲** | `docs/08-UI-SPECS/PARITY_MASTER_PLAN.md`（五层防线体系 + 子文档索引） |
| **★ 双端 UI 研发流程** | `docs/08-UI-SPECS/README.md`（Vibe Coding → 固化 Spec → iOS 翻译） |
| **iOS 对等跟随编排** | `docs/superpowers/specs/2026-08-10-ios-follow-command-design.md`（/ios-follow 六阶段管线设计 SSOT + platform_differences 台账层；可执行形态 `skills/ios-follow/SKILL.md`） |
| **AI 工具配置索引** | `AI_TOOLS.md`（四工具配置位置、Skills/Plans/Specs SSOT 约定） |
| **Chat 富内容渲染（ADR-014）** | `docs/02-ARCHITECTURE/ADR/ADR-014-chat-rich-rendering-hybrid.md`（正文原生富渲染 + RICH_HTML 沙箱卡；ADR 索引 `docs/02-ARCHITECTURE/ADR/README.md`） |
| **意图路由契约与路由器（ADR-015，M1/M2 已实施）** | `docs/02-ARCHITECTURE/ADR/ADR-015-intent-routing-contract.md`（LLM 管意图、代码管策略）+ spec `docs/superpowers/specs/2026-09-25-intent-routing-contract-design.md`（M1 止血 + M2 路由器主干已落地 `feat/intent-router`；M3 分支化在途） |
| **工程师任务卡（Spec，P1+任务中心已落地；渲染层已被下一项修订）** | `docs/superpowers/specs/2026-09-25-engineer-task-card-design.md`（工程师模式任务卡 + 任务中心页，Muse 任务卡范式；P1 在场态与 US-12~16 任务中心已落地（2026-09-26），US-4~6 回联待 P2 网关改造；2026-09-26 修订：渲染层改 HTML，状态机/分期不变） |
| **HTML 卡双形态 + 任务卡 HTML 化（Spec，已定稿）** | `docs/superpowers/specs/2026-09-26-html-card-two-tier-design.md`（Inline/Fullpage 双形态 + 混合分流 + 全屏查看器；任务卡 L1 模板 HTML 化；设计稿 Ardot `HtmlCard` 页 8 帧） |
| **产品定义** | `PRODUCT.md` |
| **交互规范** | `docs/01-PRODUCT/FEATURES.md` |
| **★ AI 协作产物 SSOT** | `docs/superpowers/README.md`（Plans / Specs 唯一事实来源，四工具共同遵守） |
| **模块规范** | 各模块 `AGENTS.md`（`androidApp/`、`shared/`、`engines/beauty-api/`、`engines/beauty-engine/`、`engines/mnn-core/`、`engines/sentencepiece/`、`engines/agent-native/`、`server/` 等） |
| **技术专项** | `docs/03-TECHNICAL-SPECS/*.md` |
| **端侧推理全景** | `docs/03-TECHNICAL-SPECS/ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md`（端侧推理盘点：文本 LLM 已移除，余 VLM 打标/人脸检测/翻译等；含优化评估与多模型生命周期改造清单） |
| **IM 远程控制技术规格** | `docs/03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md`（IM 远程控制：飞书 + Telegram 多通道，2026-07-27 重新激活，低优先级实验线） |
| **AI 一键优化** | `docs/03-TECHNICAL-SPECS/AI_OPTIMIZATION.md` |
| **TAG 生成** | `docs/03-TECHNICAL-SPECS/TAG_GENERATION.md`（端侧 VLM Qwen3-VL-2B + Florence-2 打标，3-Pass 流水线） |
| **端侧 VLM 打标引擎运维** | `docs/03-TECHNICAL-SPECS/MNN_LLM_OPERATIONS.md` |
| **语音栈** | `docs/03-TECHNICAL-SPECS/VOICE_STACK.md`（含 ASR Language Model 说明） |
| **大美丽美颜引擎** | `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md`（含相机预览比例、帧同步美妆、容灾降级） |
| **人脸关键点** | `docs/03-TECHNICAL-SPECS/FACE_LANDMARKS.md` |
| **双端 UI 对齐方法论** | `docs/03-TECHNICAL-SPECS/IOS_ANDROID_UI_PARITY.md`（S5 落地方法：一致性分层 / dp≈pt 度量 / 截图+dump 地面真值 / tokens SSOT / 验证闭环） |
| **Design Token SSOT 规范** | `docs/03-TECHNICAL-SPECS/DESIGN_TOKENS_SPEC.md`（token codegen 工作流：`design-tokens.json` 唯一 SSOT → `gen-design-tokens.py` 生成双端镜像 + `--check` 门禁；Ardot/Figma 仅预览层） |
| **JS Engine** | `docs/03-TECHNICAL-SPECS/JS_ENGINE_TECH_SPEC.md`（QuickJS 沙箱 + JSBridge：run_gallery_script 取数、draw_chart 图卡、capability.dispatch 写通路） |
| **能力注册与实现** | `docs/04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md`（含实现指南与生命周期规范） |
| **开发规范** | `docs/05-DEVELOPMENT/DEVELOPMENT.md`（含代码审查与任务标记规范） |
| **本地开发环境** | `docs/05-DEVELOPMENT/LOCAL_ENVIRONMENT.md` |
| **性能基线** | `docs/06-QA/PERFORMANCE_BASELINE_REPORT.md` |
| **服务端部署** | `docs/03-TECHNICAL-SPECS/OVERSEAS_SERVER_DEPLOYMENT.md`（香港 VPS + Nginx + certbot，DNS-only 无 Cloudflare 代理） |
| **服务端实现** | `docs/03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md`（Ktor 后端：AI 网关、账号、管理后台） |
| **备份恢复** | `docs/05-DEVELOPMENT/RELEASE_PACKAGE_BACKUP_RESTORE.md`（Release 包数据备份与恢复） |
| **Google Play 发布自动化** | `docs/05-DEVELOPMENT/GOOGLE_PLAY_RELEASE_AUTOMATION.md`（GPP 4.1.1：internal 自动 + production 人工晋升；文案 SSOT `androidApp/src/main/play/listings/`） |
| **KMP 最佳实践评估** | `docs/reviews/2026-08-10-kmp-best-practices-architecture-review.md`（KMP 路线评估：方向不修正；行动项 SKIE spike / CrashKiOS / AndroidX KMP 存储收编盘点） |

> **架构说明（2026-08-07 更新）**：
> - **Agent 框架为 JetBrains Koog（`ai.koog:koog-agents` 外部依赖）**：原 `:agent-core`（langchain4j 1.13.0 vendored fork）已于 2026-08-07 Phase 6 整体删除；chat/相机/飞书链路分别由 `KoogChatAgent` / `KoogReActAgent`（`shared/src/commonMain/.../inference/remote/koog/`）驱动；`ChatToolService`/`CameraToolService` 为 commonMain 纯 @Tool 类（不再实现 JVM-only 的 `reflect.ToolSet`），由 Android 组合根 `asToolsByClass()` 反射展开为 ToolDescriptor 清单 + ToolRegistry 同源注入；`RemoteControlToolService` 在 `:androidApp`，仍实现 `reflect.ToolSet`
> - **Agent 编排层在 `:shared` KMP 模块**（Kotlin Multiplatform，android/jvm/iosX64/iosArm64/iosSimulatorArm64 五 target）：`AgentOrchestrator`、`CapabilityRegistry`、`PrivacyGuard`、`SceneManager` 等引擎无关层位于 `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/`；平台实现（VLM `LocalLlmEngine`/`MnnLlmClient`、语音 `SherpaOnnxAsrEngine`/`KeywordSpotterEngine`、DataStore `KoogMessageMemoryStore`/`MemoryManager`、`DispatcherProvider` actual）在 `shared/src/androidMain/`（iOS 侧 `shared/src/iosMain/` 已实装 Phase 6.2 chat 全链路：`IosAgentComposition` 组合根、`ChatAgentBridge`、`IosChatGalleryCapability`、`IosKoogMessageMemoryStore` 等；端侧 VLM 仍为显式 stub；VLM JNI 桥 `libagent_native.so` 由 `:engines:agent-native` 构建经 AAR 传递）；Android 组合根为 `androidApp/src/main/java/com/mamba/picme/agent/AndroidAgentComposition.kt`（Application.onCreate 接线，平台实现唯一直构点，经 `AgentOrchestrator.initialize(AgentDependencies)` 注入）
> - **TAG 生成在 `:androidApp` 模块**：`TagScanOrchestrator`、`TagGenerationScheduler`、`OpenClGuardian` 位于 `androidApp/src/main/java/com/mamba/picme/domain/tag/`；`TagGenerationService` 为前台 Service；`TagGenerationControlScreen` 提供 3-Pass 控制与按类别/时间范围重新生成 UI
> - **OpenCL 超时与降级**：`OpenClGuardian` 在 Pass 3 前执行 warmup，单次推理带超时；连续失败/超时后标记设备降级为 CPU，黑名单持久化到 DataStore；`TagGenerationScheduler.ensureModelLoaded()` 自动按 Guardian 策略选择后端
> - **OpenAI 协议兼容**：经 Koog `OpenAILLMClient` 接入所有兼容 OpenAI API 的服务（DeepSeek、通义千问、OpenAI 官方、PoLang Server 网关），含 tool_calls、多轮对话；自定义模型名须在 `LLModel.capabilities` 显式声明（`Completion, Tools, OpenAIEndpoint.Completions`，🔴不加 Responses/Thinking）
> - **Anthropic 原生协议**：`RemoteProtocol.CLAUDE` 配置经 Koog `AnthropicLLMClient`（Messages 协议）接入 Anthropic 官方及兼容端点；`AnthropicClientSettings.modelVersionsMap` 须把自建 LLModel 映射为模型 id 字符串（Koog 默认版本表只认其预定义实例）；`thinking.type=disabled` 注入仅对 tokenhub/kimi/deepseek 保留，OpenAI 官方/Anthropic 不注入（未知参数 400）；分流逻辑收口在 `RemoteModelFactory.createKoogExecutor`
> - **DeepSeek 适配**：`RemoteModelFactory` 经 Koog `additionalProperties` 注入 `thinking.type=disabled`；自定义 `poLangSingleRunStrategy` 修复 Koog 1.1.1 内建策略丢「文本+tool_calls 同帧」工具调用的缺陷
> - **端侧文本 LLM 已移除（2026-08-02）**：shared 的 `AiAgentMode`（`agent/core/model/config/AiAgentConfig.kt`）仅剩 OFF/REMOTE/FEISHU（FEISHU 属远程控制模式）；androidApp 层 `UserPreferences.AiAgentMode` 为 OFF/LOCAL/REMOTE（LOCAL 为遗留离线兜底枚举值）。相机 AI 指令走远程 tool_calls（`AgentOrchestrator.processCameraInput` + `CameraToolService` 相机场域工具集，@Tool 方法已 suspend 化，Koog agent 循环内直接 `CapabilityRegistry.dispatch`，ToolCallCommandParser 已随 Phase 5 删除），chat 全远程（`ChatToolService`）；`AiAgentUseCase` 作为 Facade 兼容层（`:androidApp` 模块）内部委托 `AgentOrchestrator`；`LocalLlmEngine`（shared androidMain）仅存 `imageInference`（Qwen3-VL-2B 端侧 VLM 打标，TAG Pass3）
> - **JS Engine（QuickJS 沙箱）**：引擎无关层在 `:shared` commonMain `agent/core/js/`（JsEngine/JsValue/JsBridge/JsRuntime/NativeHandler），QuickJS 实现与应用 handler 在 `:androidApp` `features/chat/js/`（QuickJsEngine/GalleryScriptHandlers/ChartJs/CapabilityDispatchHandler）；除只读取数 handler 外，已存在 `capability.dispatch` **写通路**（CommandRisk 分级 + 用户确认 + ChatMediaWriteCapability）。详见 `docs/03-TECHNICAL-SPECS/JS_ENGINE_TECH_SPEC.md`
> - **AI 工程师模式（原诊断模式已并入）**：Chat「AI Engineer」toggle → `POST /v1/claude-chat` → chisel 隧道 → KimiClaw 云主机 Claude Code（GLM）；云主机 MCP server（`scripts/claude-tunnel/gateway/app_tools_mcp.py`）暴露 5 个 `app_*` 工具（日志/崩溃/聊天历史/运行时状态/相册摘要），tool call 经 SSE 下行 `app_tool_request` 到 App，`AppToolExecutor`（`androidApp/src/main/java/com/mamba/picme/core/agenttools/`）采集脱敏后经 `POST /v1/claude-tool-result` 回传；`claudeSid` 经 `ClaudeSidStore`（SharedPreferences）持久化，进程重建后可 `--resume` 续上下文。诊断工单链路（DiagRoute/diag-worker）已于 2026-08-01 移除。**账号白名单区分读写**：`/v1/claude-chat` 与 `/v1/claude-tool-result` 对所有已认证账号开放（只读诊断），仅 `/v1/claude-deliver` 代码交付受 `ai_engineer_whitelist` 限制；`/v1/claude-engineer/available` 返回 `{available, canDeliver}`。**用户问题上报**：Chat 顶部新增「上报问题」入口 → `POST /v1/report-issue`，服务端脱敏后自动在 `littleseven/polang` 创建 GitHub issue；管理后台「设置」页（`/admin/settings#whitelist`）承载「AI 工程师白名单」配置，「问题诊断」页（`/admin/diagnosis`）承载「用户上报问题」，原 `/admin/ai-engineer-whitelist` 已 301 重定向到设置页白名单区块。详见 `docs/superpowers/specs/2026-08-01-ai-engineer-diag-merge-design.md`
> - **服务端（`server/`）**：独立 Ktor 工程，提供 AI 网关（Channel 路由 / LLM 代理）、账号体系（邮箱注册 / Token 认证）、管理后台（Admin 视图）、推荐引擎（RuleEngine）、限流（RateLimiter）、COS 对象存储。与 Android 客户端通过 Monorepo 管理，但不纳入 Android `settings.gradle`。
> - **iOS 应用（`iosApp/`）**：SwiftUI，相机（AVFoundation + Metal 4-pass 美颜 + MediaPipe/MNN 双关键点源）、相册、Chat（Phase 6.2 已实装：经 SharedKit `ChatAgentBridge` 流式远程推理 + tool_calls UI 动作；多会话历史侧栏已落地——会话索引+每会话消息文件持久化、自动标题/重命名/删除/搜索，spec `docs/08-UI-SPECS/screens/chat.yaml` §2.5）、设置（含模型下载中心）均已落地；人物页为占位；分模块边界（相册 Swift 主导 presentation 消费 shared 领域层 / 相机纯 Swift + Metal 美颜管线；Phase 5 骨架设计稿已并入 iOS 文档体系，前门 `docs/01-PRODUCT/IOS_DOC_INDEX.md`）；双端图标统一 Material Icons Round（`Assets.xcassets/mat_*.imageset`）；SharedKit XCFramework embed 集成（Gradle 构建 → XcodeGen `project.yml` 声明 → build-shared-kit.sh 增量）

---

## 8. 交付审计清单

- [ ] 代码遵循 Agent First 原则（显式、枚举、自描述、结构化）
- [ ] PRODUCT.md 已更新或保持一致
- [ ] FEATURES.md 已更新或保持一致
- [ ] 模块 AGENTS.md 已更新实现细节
- [ ] 满足 [PRIVACY]、[PERF]、[I18N] 红线
- [ ] 闭环验证（编译/安装/测试）通过
- [ ] 架构合规审查通过
- [ ] 核心验收测试通过

---

## 附录 A：工具调用速查

| 场景 | 工具 | 示例 |
|------|------|------|
| 代码修改 | `Edit` | 原子化修改 |
| 批量读取 | `Read` | 多文件并行分析 |
| 编译验证 | `Bash` | `./gradlew assembleDebug` |
| 设备操作 | `Bash` | `adb install/logcat` |
| 任务追踪 | `TodoWrite` | 任务进度维护 |
| 知识存储 | `Skill` | 关键决策记录 |

## 附录 B：快速参考

### 文档体系
```
PRODUCT.md (What)
    ↓
FEATURES.md (How)
    ↓
模块 AGENTS.md (Implementation)
    ↓
代码
```

### 关键指标
- 自动修复成功率：目标 > 70%
- 文档同步率：目标 > 95%
- 人工介入率：目标 < 20%
