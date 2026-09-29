# polang AI Agent 系统：唯一事实来源 (SSOT)

> **版本**：2.6（治理机制版）  
> **状态**：生效中  
> **最后更新**：2026-09-28  
> **维护者**：项目开发者  
>
> 2026-09-28 更新：新增 §4.3 活文档×过程文档「单向引用 + 沉淀管线」机制（治理总纲：系统性/结构性/简洁性/准确性 > 兼容性/过程性）；check_doc_sync.py 新增第 5/6 项检查强制执行。
> 2026-09-27 更新：文档瘦身——删除头部历史更新日志与 §7 架构说明中的模块级细节（模块结构/依赖链由根 `CLAUDE.md` 承载，实现细节由各模块 `AGENTS.md` 与技术专项文档承载）；Koog 远程协议接入坑位移至 `shared/AGENTS.md` §2；删除与 §4.1/§6.2 重复的附录 B。git 历史可查全部被删内容。

> 本文档为**顶层治理文档**，定义 Agent First 的研发规范。
>
> **polang** 是 PoLang（破浪相册）应用的 Monorepo：Android 应用为主体，KMP 跨端改造进行中。

---

## 1. 项目背景：Agent First 三重实验

polang 是一个元实验（meta-experiment），同时探索三个层次：

| 层次 | 实验对象 | 核心问题 |
|------|----------|----------|
| **基础库** | LangChain4j 风格 Android Agent 基础库（原 `:agent-core` fork，已删除） | LangChain4j 风格 API 能否在 Android 高效运行？（结论：vendored fork 不可持续——冻结上游、死重多、0 测试；2026-08 迁移至 Koog，模块已删除） |
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
| `./scripts/ota-publish.sh` | 自建 OTA 一键发包（debug 轨默认 / `--type release`）：构建 APK → 提取 versionCode → 上传 picme-server `/admin/apk/upload`（X-Admin-Token），测试机冷启动弹自更新（仅非 Play 渠道） |

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

> 同一「评审」角色，CC 给最强档、kimi 故意用弱档——**角色→档是策略，随工具而定**，档→模型的绑定各管各。
>
> **CC 子代理模型注意**：本环境 frontmatter `model:`（及 Agent 工具 `model` 参数）对子代理实际是**空操作**——子代理恒继承主会话模型（当前 fable→glm-5.2，含上表 WEAK 档的内置 Explore 亦然），frontmatter 写 `fable` 仅作语义占位，实际档位由主会话 `model:fable` 决定。kimi 原生用 `model=primary/secondary` 逻辑档。

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

### 4.3 活文档 × 快照 × 过程文档：单向引用 + 沉淀管线（2026-09-28 定）

治理总纲：**系统性、结构性、简洁性、准确性 优先于 兼容性、过程性**。三类体系——

- **活文档**（长期事实 SSOT，随代码持续维护）：`PRODUCT.md` / `FEATURES.md` / ADR / `03-TECHNICAL-SPECS` / 模块 `AGENTS.md` / `08-UI-SPECS` / `07-STANDARDS`。
- **快照**（结论性系统快照：某时点的审计/评估/调研结论，日期命名，不随代码维护）：`docs/reviews/`。被新快照取代或结论收编活文档后删除；入 main 须登记白名单。
- **过程文档**（AI 协作工作流产物，随时可删、git 即归档）：`docs/superpowers/{plans,specs}` / `docs/06-QA`（含 ios-follow 批次验收）。交付即按沉淀管线落位删除。

三条铁律：

1. **引用单向**：活文档只引用活文档与**在册快照**，禁止指向过程目录。例外：AGENTS.md §7 / PRODUCT.md 可索引「在途活跃 spec」（以 `docs/superpowers/README.md` §6 白名单为准，交付即摘除）。提及在途工作写 spec 名/分支名，不写路径。
2. **交付沉淀管线（DoD）**：spec/plan 交付合 main 前，结论按类型落位——决策→ADR、交互→`FEATURES.md`、实现约束→模块 `AGENTS.md` / `AGENT_ARCHITECTURE.md`、技术链路→TECH_SPEC、UI→`08-UI-SPECS`、术语→GLOSSARY；落完 `git rm` 过程文档本体，不留「已随交付清理」过渡标注。值得长期参考的结论性观测/评估可落 `docs/reviews/` 快照（登记制）。
3. **门禁自动化**：`scripts/check_doc_sync.py` 第 5/6 项检查强制执行（ai-gate 每次提交跑）：活文档引用过程文档即 FAIL；`docs/reviews` 新快照入 main 即 FAIL，须显式登记白名单。

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
| **★ Chat 消息模型与渲染宪法（ADR-016，已定稿待实施；整合原 ADR-014）** | `docs/02-ARCHITECTURE/ADR/ADR-016-chat-parts-model-mainstream-alignment.md`（Vercel parts 协议 + ChatGPT AST 渲染 + 沙箱卡双形态 + tokens SSOT；**Chat 域消息模型/流式/渲染一切改动的上位约束**；原 ADR-014 富内容渲染已并入——D3 沙箱/D4 iOS/D5 样式分级编号语义不变，编号 014 永久留空）+ spec `docs/superpowers/specs/2026-09-27-chat-parts-rendering-design.md`（parts 模型/chunk 流式/工具状态机/Turn 聚合/性能顺车修复，M1~M5 分期） |
| **意图路由契约与路由器（ADR-015，M1/M2 已实施）** | `docs/02-ARCHITECTURE/ADR/ADR-015-intent-routing-contract.md`（LLM 管意图、代码管策略）+ spec `docs/superpowers/specs/2026-09-25-intent-routing-contract-design.md`（M1 止血 + M2 路由器主干已落地，M3 分支化在途） |
| **工程师任务卡（P1+任务中心已落地，渲染层改 HTML）** | `docs/superpowers/specs/2026-09-25-engineer-task-card-design.md`（任务卡 + 任务中心页，Muse 范式；US-4~6 回联待 P2 网关改造） |
| **HTML 卡双形态 + 任务卡 HTML 化（H1 已合 main）** | `docs/superpowers/specs/2026-09-26-html-card-two-tier-design.md`（Inline/Fullpage 双形态 + 混合分流 + 全屏查看器；H2 任务卡 L1 模板 HTML 化待做；设计稿 Ardot `HtmlCard` 页 8 帧） |
| **用户任务协议 + 任务中心双 Tab（M1 已落地）** | `docs/superpowers/specs/2026-09-26-user-task-protocol-design.md`（`UserTask` 协议 + 混合注册表（Room 元数据 + 内存进度）+ 任务中心双 Tab；M1 = TAG 扫描 + 模型下载，实现见 `androidApp` `domain/usertask/`；M2/M3 = 去重/美学/重聚类） |
| **任务范式定位升格** | `docs/superpowers/specs/2026-09-26-task-paradigm-positioning-design.md`（PRODUCT.md v3.1 §6.6 任务范式线 + Muse Top5 落位） |
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
| **Chat 卡片目录** | `docs/03-TECHNICAL-SPECS/CHAT_CARD_CATALOG.md`（每张卡片的协议/parts 形态/回灌/渲染三要素登记 + 端到端例子；M1/M2 parts 落地态已并入） |
| **能力注册与实现** | `docs/04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md`（含实现指南与生命周期规范） |
| **开发规范** | `docs/05-DEVELOPMENT/DEVELOPMENT.md`（含代码审查与任务标记规范） |
| **本地开发环境** | `docs/05-DEVELOPMENT/LOCAL_ENVIRONMENT.md` |
| **性能红线（NFR）** | `docs/01-PRODUCT/NFR_SPEC.md`（性能/稳定性/隐私量化指标；历史 trace 报告已随 2026-09-27 清理，git 历史可查） |
| **服务端部署** | `docs/03-TECHNICAL-SPECS/OVERSEAS_SERVER_DEPLOYMENT.md`（香港 VPS + Nginx + certbot，DNS-only 无 Cloudflare 代理） |
| **服务端实现** | `docs/03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md`（Ktor 后端：AI 网关、账号、管理后台） |
| **备份恢复** | `docs/05-DEVELOPMENT/RELEASE_PACKAGE_BACKUP_RESTORE.md`（Release 包数据备份与恢复） |
| **Google Play 发布自动化** | `docs/05-DEVELOPMENT/GOOGLE_PLAY_RELEASE_AUTOMATION.md`（GPP 4.1.1：internal 自动 + production 人工晋升；文案 SSOT `androidApp/src/main/play/listings/`） |
| **KMP 最佳实践评估** | `docs/reviews/2026-08-10-kmp-best-practices-architecture-review.md`（KMP 路线评估：方向不修正；行动项 SKIE spike / CrashKiOS / AndroidX KMP 存储收编盘点） |

> **架构速览（2026-09-27 瘦身：模块级细节下沉到各归属文档，此处只留指针）**：
> - Agent 框架 = JetBrains Koog（外部依赖）；编排层 = `:shared` KMP 模块（iOS Phase 6.2 chat 全链路已实装）。模块结构与依赖链见根 `CLAUDE.md`，组件明细见 `shared/AGENTS.md`。
> - 远程协议接入坑位（`LLModel.capabilities` 声明 / Anthropic `modelVersionsMap` / `poLangSingleRunStrategy`）→ `shared/AGENTS.md` §2。
> - 端侧文本 LLM 已移除，仅存 Qwen3-VL-2B VLM 打标；TAG 3-Pass 与 OpenCL 降级 → `docs/03-TECHNICAL-SPECS/TAG_GENERATION.md`；JS Engine → `docs/03-TECHNICAL-SPECS/JS_ENGINE_TECH_SPEC.md`。
> - AI 工程师模式（claude-chat 隧道 / 白名单读写分离 / 问题上报）→ `docs/01-PRODUCT/FEATURES.md` §2.7 + `server/AGENTS.md`。
> - 服务端 Ktor 工程 → `server/AGENTS.md`；iOS 应用 → `docs/01-PRODUCT/IOS_DOC_INDEX.md`。

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
