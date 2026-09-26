# 用户任务协议（User Task Protocol）+ 任务中心双 Tab——产品与架构设计 Spec

> **日期**: 2026-09-26
> **来源**: 用户新想法——「用户任务（区别于工程师任务）：耗时比较久的例如扫描、下载、整理等，都是用户任务类型」
> **上游**: `2026-09-25-engineer-task-card-design.md`（工程师任务卡 + 任务中心页，US-12~16 已落地）、`2026-09-26-html-card-two-tier-design.md`（HTML 卡双形态，§7/§15 与本 spec 划界，见 §8）
> **关键决策**（用户 2026-09-26 逐项确认）：统一生命周期（观察+控制）/ 首批接 TAG 扫描 + 模型下载 / 任务中心双 Tab / 混合注册表（元数据落 Room + 进度走内存）/ 未接入体系不出现
> **状态**: 已定稿，待实施（M1）

---

## 1. 问题陈述

仓库中已存在 5 套平行的「长耗时任务」体系，各自为政，用户没有全局视图：

| 体系 | 进度机制 | 进程死亡恢复 | 进程外可见性 |
|---|---|---|---|
| TAG 扫描（`TagScanOrchestrator`） | StateFlow + Room 队列 + ETA，7 态会话 | ✅ Room 恢复 | FGS 通知 + 多页 UI |
| 模型下载（`LlmModelDownloadManager`） | StateFlow 字节流，并发多任务，6 态 | ❌（仅文件级续传） | FGS 通知 + 模型中心 |
| 去重扫描（`DedupScanner`） | 冷 Flow 阶段事件 | ❌ 退页即断 | ❌ 仅屏内 |
| 美学打分（`AestheticScoreWorker`） | StateFlow 计数 | ❌ | 仅控制页卡片 |
| 人脸重聚类（REEMBED） | Logcat | ❌ | ❌ |

痛点：**看不见**（扫描在跑、下载在跑，用户要分别去整理页/模型中心才能发现）与**管不住**（无统一暂停/恢复/取消入口）并存。

## 2. 术语边界（强制）

| 术语 | 定义 | 归属 |
|---|---|---|
| **工程师任务（Engineer Task）** | AI 工程师模式的代码任务，状态机 `EngineerTaskReducer`，卡片在 chat 消息流 | 9-25 spec；渲染层被 9-26 HTML 卡 spec 修订 |
| **用户任务（User Task）** | 扫描/下载/整理等 app 级长耗时后台操作，本 spec 定义的统一协议 | 本 spec |
| **任务中心（Task Center）** | 统一承载两类任务的页面，双 Tab：「工程师任务 \| 后台任务」 | 两 spec 共建 |

文档与代码中「任务」一词单独出现时必须有语境限定；协议层类名统一 `UserTask*` 前缀，与 `EngineerTask*` 严格分开。

## 3. 方案概述

抽象统一任务协议（`UserTask` 模型 + 声明式能力动词集 + `UserTaskRegistry` 注册表），各体系经**适配器**接入；任务中心页扩展为双 Tab，后台任务 Tab 提供统一观察与控制。

五项核心决策（用户逐项确认）：

1. **统一生命周期（观察 + 控制）**：不做只读聚合，统一动词 PAUSE / RESUME / CANCEL / RETRY 直接操控体系。
2. **首批接入 = TAG 扫描 + 模型下载**：两者动词与生命周期保障最成熟，协议在低成本案例上验证收敛；去重扫描 / 美学打分 / 人脸重聚类的收编（需先补各自的基础设施）属后续里程碑（§13）。
3. **任务中心双 Tab**：一个入口看所有「app 正在替我干的事」，顶栏角标合并计数。
4. **混合注册表**：任务「身份」落 Room（可寻址、重启可见），实时进度走内存（避免高频 IO）。
5. **未接入体系不出现**：任务中心语义 = 「出现在这里的任务都可控」；不为去重/美学/重聚类写只读占位。

**边界**：统一发生在**协议层**，不发生在引擎层——两套引擎内部零侵入（不加 WorkManager、不改 FGS、不改通知渠道），适配器只做翻译。

## 4. 统一任务模型

```kotlin
data class UserTask(
    val id: String,                            // 体系前缀寻址："tagscan:main" / "download:<modelId>"
    val kind: UserTaskKind,                    // TAG_SCAN / MODEL_DOWNLOAD（枚举即扩展点，收编即加值）
    val titleKey: String,                      // 静态文案 key（UI 按 kind 取 string resource，不入库；见 §7 i18n）
    val displayName: String?,                  // 体系自带名字（如下载的模型名），原样展示，不参与 i18n
    val status: UserTaskStatus,                // 见下
    val progress: Float?,                      // 0..1；无法量化时 null（渲染不定进度条）
    val progressText: String?,                 // "512/2048" / "12.3 MB / 45 MB"（格式由适配器产出，UI 不拼）
    val etaMs: Long?,                          // 可空，各体系有就给（TAG 有，下载无）
    val errorSummary: String?,
    val supportedActions: Set<UserTaskAction>, // 声明式能力集（按状态推导，见 §4.3）
    val destination: UserTaskDestination,      // 点击卡片跳转：扫描控制页 / 模型中心
    val updatedAt: Long,
)

enum class UserTaskStatus { PENDING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }
enum class UserTaskAction { PAUSE, RESUME, CANCEL, RETRY }
sealed interface UserTaskDestination { data object TagScanControl : …; data object ModelCenter : … }
```

### 4.1 状态映射（适配器纯函数，可单测钉死）

TAG 扫描会话 7 态 → 6 态：

| `ScanSessionState` | `UserTaskStatus` | 说明 |
|---|---|---|
| IDLE | （无任务，不发射） | 空闲时任务中心不显示 |
| RUNNING / PAUSING / CANCELLING | RUNNING | 过渡态按进行态呈现（CANCELLING 落定后进 CANCELLED） |
| PAUSED | PAUSED | |
| COMPLETED | COMPLETED | 任务级失败（failed>0）不进 FAILED，以 errorSummary「N 张失败」附在 COMPLETED 上 |
| CANCELLED | CANCELLED | |

模型下载 6 态 → 6 态：PENDING→PENDING、DOWNLOADING→RUNNING、PAUSED→PAUSED、COMPLETED→COMPLETED、FAILED→FAILED、CANCELLED→CANCELLED（1:1）。

### 4.2 能力集推导（声明式）

| status | TAG 扫描 | 模型下载 |
|---|---|---|
| PENDING | {CANCEL} | {CANCEL} |
| RUNNING | {PAUSE, CANCEL} | {PAUSE, CANCEL} |
| PAUSED | {RESUME, CANCEL} | {RESUME, CANCEL} |
| FAILED | {RETRY}（retryFailed） | {RETRY}（重新下载） |
| COMPLETED / CANCELLED | {} | {} |

卡片按 `supportedActions` 渲染按钮——能力由体系声明，UI 无 if-else 业务逻辑。

## 5. UserTaskRegistry（混合注册表）

组合根单例（`AppContainer` 创建，平台实现唯一直构点），三件套：

1. **Room `user_task` 表（身份，SSOT）**：`id` PK / `kind` / `displayName` / `status` / `errorSummary` / `destination` / `updatedAt` / `completedAt?`。**只在状态迁移时写入**（进度刷新不落库）。静态文案（titleKey）不入库——遵守 [I18N] 红线，用户可见静态文案一律走 string resource。
2. **内存进度流**：`StateFlow<Map<String, TaskProgressSnapshot>>`（progress / progressText / etaMs / supportedActions），高频刷新只走内存。
3. **对 UI 暴露单一合并流** `tasks: Flow<List<UserTask>>`（Room 元数据 ⋈ 内存进度）。UI 依赖这一个显式接口，不感知背后体系（Agent First：显式优于隐式）。

**历史语义**（与工程师 Tab 对齐）：COMPLETED / FAILED / CANCELLED 进历史区，封顶 50 条，超出清最旧。

**启动对账（reconciliation）**：进程重启后适配器 `start()` 时执行一次——

- TAG 扫描：orchestrator 自身 Room 队列恢复后重发会话态，注册表被动同步（既有机制，零新增）。
- 模型下载：Room 中 PENDING/RUNNING/PAUSED 但无活体 Job 的行 → 置 FAILED，errorSummary = 「进程终止，可重试」（五语资源 key），按钮 {RETRY}。这是下载体系首次获得「重启后任务可见」能力——协议接入的附带收益。

## 6. 适配器（体系与协议之间的唯一接缝）

```kotlin
interface UserTaskAdapter {
    val kind: UserTaskKind
    fun start()                                            // 订阅体系状态流 → 同步注册表（含启动对账）
    suspend fun perform(taskId: String, action: UserTaskAction)  // 统一动词 → 体系原生 API
}
```

- **TagScanTaskAdapter**：订阅 `TagScanOrchestrator.progress`（经 `TagGenerationService` 转发链，沿用既定模式）；整个扫描会话 = 1 个 `UserTask`（id 固定 `tagscan:main`）；动词转发 `StartTagScanUseCase`（pause/resume/cancel 已有协议，近乎零新代码）。
- **ModelDownloadTaskAdapter**：订阅 `LlmModelDownloadManager.downloadStates`（`Map<modelId, DownloadState>`，天然并发多任务），每个 modelId = 1 个 `UserTask`；动词转发 manager 既有 pause/resume/cancel；增量工作 = 状态迁移时写注册表 + 启动对账。
- 适配器在 `AppContainer` 创建并 `start()`；`perform` 的失败（体系拒绝/状态竞态）吞为「刷新一次快照」——状态以体系为准，UI 不乐观更新。

## 7. UI：任务中心双 Tab

- `TaskCenterScreen` 顶部加一级 Tab：**工程师任务 \| 后台任务**（五语）。
- **工程师 Tab**：现有内容原样（进行中/历史、审批动作、回锚）。
- **后台任务 Tab**：`UserTask` 卡片列表，进行中/历史双分区（历史封顶 50）。卡片 = 类型图标 + 标题（titleKey 资源或 displayName）+ 状态 chip + 进度条（`progress=null` 时不定进度）+ progressText + ETA + 按 `supportedActions` 渲染的动作按钮 + 错误摘要。点击卡片跳 `destination`。
- **卡片渲染 = 原生 Compose**：照引 HTML 卡 spec §7/§15 决策（任务中心列表项多卡并存，WebView 实例成本不划算），两 spec 边界见 §8。
- **角标合并**：Chat 顶栏任务图标角标 = 工程师进行中数 + 用户任务进行中数；点击进任务中心，默认 Tab = 工程师有活跃 → 工程师，否则后台任务，均无 → 工程师（现状兼容）。
- **i18n**：新增文案（Tab 名 / 空态 / 错误摘要 / 动作按钮若缺）五语同步（EN/zh-CN/zh-TW/ES/FR）。

## 8. 与 HTML 卡双形态 spec 的边界

| 面 | 归属 | 依据 |
|---|---|---|
| chat 气泡内工程师任务卡渲染（HTML 化、双形态） | HTML 卡 spec §7 | 状态机/持久化不变，只换渲染层 |
| 任务中心页列表项（含工程师条目与用户任务卡片） | 本 spec，原生 Compose | HTML 卡 spec §7/§15 明确不 HTML 化 |
| 「控制/审批动作原生外置」 | 两 spec 共同公理 | HTML 卡 spec 审批动作条 ≈ 本 spec 声明式动词按钮 |
| 「状态是 SSOT，渲染是产物」 | 两 spec 共同公理 | HTML 卡 spec §10 ≈ 本 spec §5 |

已知未来合并摩擦（非冲突）：H2（任务卡 HTML 化）改 chat 气泡层 `EngineerTaskCard` 渲染，与本分支改过的「查看全部」热区可能小冲突，属渲染层局部改动。

## 9. 错误处理与边界场景

1. **动作失败**（体系拒绝/竞态）：`perform` 不乐观更新，失败后触发一次快照刷新，状态以体系为准；不弹 toast 打扰（控制动词失败的默认反馈 = 状态没变化）。
2. **下载进程死亡**：启动对账置 FAILED + RETRY（§5），文件级断点续传由 manager 既有机制承接。
3. **扫描 FGS 超时（Android 14 dataSync 6h）**：沿用既有 `TagScanRescheduleReceiver` 闹钟续跑，注册表被动同步，无新增逻辑。
4. **两体系并发跑**：互不影响；TAG 扫描内部已有与美学打分的互斥逻辑，维持原样。
5. **注册表写库失败**：进度内存流不受影响，UI 仅丢重启可见性（降级不致命），记 warning 日志。

## 10. 红线核对

- **[PRIVACY]**：无新增网络面，用户任务全端侧，天然满足。
- **[PERF]**：进度高频刷新只走内存；Room 写仅限状态迁移 + 对账；合并流对 UI 输出节流沿用各体系既有的进度节流（下载 500ms/1MB）。
- **[I18N]**：静态文案不入库（titleKey 机制）；新增文案五语同步。
- **[DOC-SYNC]**：实施时同步 `androidApp/AGENTS.md`（§2 路由表 TaskCenter 行 + §3 架构说明）、根 AGENTS.md §7 索引。
- **[PARITY]**：M1 Android 定稿后走 /ios-follow；记入 parity 台账。

## 11. iOS 同构

M1 为 Android-only。iOS 跟随走 /ios-follow 排期：iOS 侧 TAG 扫描/模型下载体系形态不同（端侧 VLM 为 stub），具体映射在实施时按平台差异台账裁定，不在本 spec 预设。

## 12. 测试决策

- **纯函数单测（JVM）**：两张状态映射表全分支（§4.1）、能力集推导（§4.2）、进度/ETA 换算。沿用 `TaskCenterPartition` 先例——逻辑抽纯函数，UI 零逻辑。
- **Registry 测试**：Room 内存库测状态迁移写入 / 历史封顶 50 / 启动对账（下载 FAILED 化）；fake 适配器测「Room ⋈ 内存进度」合并流语义。
- **ViewModel 测试**：`TaskCenterViewModel` 扩展双 Tab 数据源后的分区/空态。
- **回归**：TAG 扫描与下载既有单测全绿（适配器不改引擎，回归面应接近零）；`EngineerTaskSidTest` 等任务中心既有测试不动。
- **真机冒烟（M1 验收）**：① 扫描中在任务中心暂停/恢复/取消；② 下载中在任务中心暂停/恢复，杀进程重启后任务以 FAILED+RETRY 可见；③ 角标合并计数与默认 Tab 落位。

## 13. 明确不做

- 去重扫描 / 美学打分 / 人脸重聚类的接入（M2/M3 各自补基础设施后收编）；
- 只读占位任务（未接入体系不出现在任务中心）；
- 引入 WorkManager / 统一通知封装（现有 FGS 模式够用，独立决策另行）；
- 引擎内部改造（FGS、通知渠道、扫描互斥逻辑全部不动）；
- 用户任务与 Agent 联动（如「扫描完成通知 chat」——回联类需求，与工程师任务 US-4~6 同属后续）；
- iOS 实现（/ios-follow 排期）。

## 14. 实施分期

| 期 | 范围 | 前置 |
|---|---|---|
| **M1（本 spec）** | `UserTask` 协议 + `user_task` Room 表 + `UserTaskRegistry` + 启动对账 + TAG/下载两适配器 + 任务中心双 Tab + 角标合并 + 五语 | 无，可立即开工 |
| **M2** | 去重扫描收编（先补 FGS/进程外生命周期，再写适配器） | M1 协议验证 |
| **M3** | 美学打分、人脸重聚类收编（补动词与进度通道） | M2 |
