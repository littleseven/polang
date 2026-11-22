# Agent 对话式清理 + 存储空间叙事 + 后台预分析——产品与架构设计 Spec

> **日期**: 2026-11-22
> **来源**: 用户需求——「相册整理和图片管理是较强诉求，借鉴其他 app 深入来做」；定向为清理整理增长包（2026-11-19）的三项延期（Agent 对话式清理 / 存储空间叙事 / 充电时后台预分析）+ 与任务体系相结合
> **上游**: `2026-09-26-user-task-protocol-design.md`（UserTask 协议 M1，§13 延期项 M2/M3 与「用户任务与 Agent 联动」由本 spec 立项）、`2026-08-25-album-dedup-design.md`（去重 2.0）、`2026-09-26-approval-three-element-card-design.md`（审批三要素卡）、ADR-015（意图路由）、ADR-016（Chat parts 宪法）、`docs/superpowers/plans/2026-11-19-cleanup-organize-growth-pack.md`（增长包，已交付）
> **关键决策**（用户 2026-11-22 逐项确认）：Agent 端到端执行但删除必须人确认 / 视频只列大小不分析不处理 / 不做重编码压缩 / 建议面只收「冗余」不评判「好坏」（人脸老照片保护）/ 新增 App 来源清理维度（用户可调校重要度）/ 预分析触发 = 充电+闲置 + 手动 + 每日闲时，pHash 重活带 FGS 通知
> **状态**: 已定稿，待实施（分期见 §12）

---

## 1. 问题陈述

整理中心（去重 2.0 + 增长包）已把「用户自己点」做到完整：三级重复尺度、保留规则、滑动整理、按月回顾、战报分享。但对照相册整理类产品的强诉求，仍缺三层：

1. **Agent 缺位**：清理全靠用户自己逛 hub。产品定位句是「自然语言委托 → 后台任务执行 → 完成回联」，但清理这条线「委托」和「回联」两头都不存在——用户对 Chat 说「帮我清理相册」只会得到文字建议。
2. **看不见空间**：没有「空间都去哪了」的叙事。用户不知道视频占了多少、微信转存图占了多少、哪些是可安全清理的冗余。
3. **结果不现成**：去重扫描、整理管线都是进页才算，重活（pHash）要用户干等。充电/闲置时的预分析基础设施缺失（user-task spec §13 M2 已点名：去重扫描收编需先补 FGS/进程外生命周期）。

## 2. 目标 / 非目标 / 反目标

**目标**

- 用户可以对 Agent 说一句话完成清理闭环：**委托 → 方案 → 批准 → 执行（任务可见可控）→ 回联战报**。
- 整理中心能回答「空间去哪了」：按类目 + 按来源 App 的双视图账本，每条账都带可执行建议。
- 任何时刻打开整理中心或问 Agent，分析结果是现成的（预分析基础设施）。

**非目标（本期不做）**

- 视频内容分析、视频压缩、任何形式的重编码压缩（用户明确：留相册就是要原图；视频只进账本列大小）。
- 低质量/低评分照片的删除建议（见 §3 哲学）；真压缩管线。
- 游戏化、云端分析、iOS 实现（Android 定稿后走 /ios-follow）。

**反目标（做成了也算失败）**

- Agent 未经用户明确批准删除/修改任何媒体文件。
- 建议清单里出现「机器觉得差但用户可能有感情」的照片（模糊老照片、低分人像）。
- 预分析打扰用户（发热、耗电、打断前台）。

## 3. 核心哲学：Agent 只建议「冗余」，不评判「好坏」

用户 2026-11-22 定调：**冗余是客观的，好坏是主观的**。清理建议面只收录「多出来的」和「过期失效的」：

| 进建议面（冗余，机器可判定） | 不进建议面（价值主观，机器无权评判） |
|---|---|
| 完全重复（L1，字节相同，确认没必要多张） | 低质量/低评分照片（模糊、欠曝——尤其是有人脸的老照片，情感价值最高） |
| 视觉重复（L2，按既有保留规则留一） | 独一份的任何照片，无论画质 |
| 过期截图（默认 ≥1 年，阈值可调） | 人脸照片（默认保护——新类目保护规则详见 §15 D4，基于人物聚类信号而非裸 `hasFace`） |
| App 转存噪音（社交 App 接收图，来源维度见 §6） | 相机原片（DCIM，默认保护） |
| 大文件照片（只列出引导用户自己看，不预选删除） | 视频（只进账本，不出处理建议） |

**与既有整理中心的调和**：`OrganizeCategory` 现有 `LOW_QUALITY_PORTRAITS` / `LOW_QUALITY_PHOTOS` 两类在**手工整理面**保留（用户自己逛、ValueGuard 已保护人脸老照片），但 **Agent 方案面不主动推荐**这两个类目的删除——Agent 的权威来自「只提确定无疑的建议」，一次误伤人脸老照片就会摧毁信任。

## 4. 主流程（Agent 对话式清理闭环）

```
用户在 Chat 说「帮我清理相册」/「相册太乱了」
  → 意图路由（ADR-015）识别 CLEANUP 意图
  → prepare_cleanup_plan capability：读取预分析结果（§8），增量补齐后生成清理方案
  → Chat 内方案摘要卡（各类目建议数 + 预计释放，点卡片进详情）
  → 方案详情页（原生全屏页）：按类目/来源分组、缩略图明细、可逐组排除
  → 用户点「批准清理」→ 创建 CLEANUP_EXECUTE 用户任务（任务中心可见、可取消）
  → 执行：批量系统回收站授权（一次授权框，API 30+ 30 天可恢复）
  → 完成回联：Chat 插入战报卡（释放量 + 各类目处理数，可分享）
```

**批准是唯一闸门**：批准前 Agent 不做任何写操作；批准后不再逐项追问（方案明细页即确认现场）。回收站兜底叙事写进批准 CTA 旁（「30 天内可从系统回收站恢复」），降低决策压力。

**与审批三要素卡的关系**：三要素卡（`WriteConfirmationController`，JS/TOOL_CALL 同步写确认）管 ReAct 链路上的散点删除；本 spec 的方案批准是**异步计划级审批**（用户可以慢慢看，不存在 120s 超时），两者互补不冲突。方案执行走 `DedupTrashManager`/`TrashSessionController` 既有回收站链路，不重造删除通路。

## 5. 清理方案协议（CleanupPlan）

Agent 与 UI 之间的结构化契约（domain 层，纯 Kotlin，JVM 可测；Agent First：类型即契约）：

```kotlin
data class CleanupPlan(
    val id: String,
    val createdAt: Long,
    val groups: List<CleanupPlanGroup>,     // 按类目×来源分组
    val totalReclaimBytes: Long,            // 预选项合计可释放
    val status: CleanupPlanStatus,          // DRAFT / APPROVED / EXECUTING / COMPLETED / CANCELLED
)

data class CleanupPlanGroup(
    val category: CleanupCategory,          // EXACT_DUP / VISUAL_DUP / STALE_SCREENSHOT / APP_NOISE / LARGE_PHOTO
    val sourceApp: AppSource?,              // 来源维度（§6），非来源组为 null
    val members: List<CleanupPlanMember>,   // uri + sizeBytes + captureDate + 组内角色(keeper/candidate)
    val suggested: Boolean,                 // 建议删除=true / 仅列出引导=false（LARGE_PHOTO 恒 false）
    val preselectedUris: Set<String>,       // 默认勾选（用户可在详情页逐项排除；keeper 永不入选）
)

enum class CleanupCategory { EXACT_DUP, VISUAL_DUP, STALE_SCREENSHOT, APP_NOISE, LARGE_PHOTO }
```

- 方案落 Room（新表 `cleanup_plan`），身份可寻址、进程重启可见——与 user-task spec §5「身份落 Room」同原则；明细 members 随表序列化。
- `LARGE_PHOTO` 组 `suggested=false`：进方案但默认不勾选，只是「列出来让你看看」。
- 场景相似（L3）不进方案——其语义是逐组确认，属手工整理面。
- LLM 上下文只拿**摘要数字**（各类目组数/张数/字节），方案明细（uri 列表）不进 prompt——对齐 [PRIVACY] 与既有 `MAX_OBSERVATION_IDS` 克制先例。

## 6. App 来源维度（AppSource）

用户洞察：不同 App 的资产重要度差异巨大——相机原片是资产，社交 App 转存图往往是噪音。

**识别**（零推理成本）：按目录前缀规则映射，纯函数，不依赖任何模型。路径与大小数据当前**不在 Room**——`media_assets` 无 path/size 列，`relativePath`/`sizeBytes` 是读取时由 `OrganizeRepositoryImpl.mergeRows` 实时查 MediaStore（`RELATIVE_PATH`/`DATA`）合并的。为支撑账本「秒级纯 DB」与方案快照冻结，M1 将 `relativePath`/`sizeBytes` 持久化进 `media_assets`（Room v29 迁移 + 回填，由预分析同步维护，见 §8）。

| AppSource | 目录特征 | 默认重要度 |
|---|---|---|
| `CAMERA` | `DCIM/` | 核心资产——保护，永不进建议面 |
| `WECHAT` | `Pictures/WeChat`、`Android/media/com.tencent.mm` | 低价值候选 |
| `WHATSAPP` | `Android/media/com.whatsapp` | 低价值候选 |
| `TELEGRAM` | `Pictures/Telegram`、`Android/media/org.telegram` | 低价值候选 |
| `INSTAGRAM` / `MESSENGER` 等 | 各自 media 目录 | 低价值候选 |
| `DOWNLOAD` | `Download/` | 次级候选 |
| `SCREENSHOTS` | `Pictures/Screenshots` 等 | 由过期截图维度承接（§3） |
| `OTHER` | 兜底 | 不参与来源建议 |

- 海外市场优先覆盖 WhatsApp/Telegram/Instagram/Messenger，中文市场加微信/QQ。
- **用户可调校**（M3）：某来源可标记为「重要」（保护，不再出现在建议）或「可清理」，覆盖默认档并持久化（DataStore）；这是 Agent 越用越懂用户的记忆点。
- 账本双视图：存储叙事里同一批媒体既能按「问题类型」看，也能按「来源 App」看（「微信 2.1GB / Telegram 800MB」），两种视图共享同一份执行清单。

## 7. 存储空间叙事（StorageStory）

整理中心 hub 升级呈现「空间去哪了」：

- **总账**：照片 / 视频 / 截图 / 各来源 App 的占用字节（视频只统计，不出建议）。
- **可释放估计**：复用 `OrganizeBoard.heroReclaimBytes` 口径（互斥裁定天然去重）。
- **建议清单**：按冗余确定性排序（完全重复 > 视觉重复 > 过期截图 > App 转存 > 大文件引导）。
- **空间预测**（M3）：一句话结论（「按近 3 个月增长速度，预计 X 个月后空间不足」），数据从 Room 媒体库历史线性外推，纯函数。
- 叙事数据由预分析产出快照（§8），进页零等待；快照过期（>24h）时后台补算、先展示旧快照。

## 8. 后台预分析（PreAnalysis）

**触发**（用户已确认）：① 充电中 + 闲置（息屏）；② 手动入口（整理 hub「立即分析」）；③ 每日闲时（闹钟，默认夜间）。

**内容**（由轻到重，任一中断点可停）：

1. 媒体元数据同步：`relativePath`/`sizeBytes` 持久化回填（Room v29，§6）+ 来源映射派生计账（纯 DB 聚合，秒级）；
2. 整理管线重算（`OrganizeCategorizer.board`，依赖信号已就位）；
3. 去重增量扫描（MD5/pHash，`dedup_hash` 缓存使增量便宜；全量首扫是最重的活）。

**形态**：`PreAnalysisScheduler` + 前台服务（复用 `TagGenerationService` 模式，通知可见可停）。项目不引入 WorkManager——充电/闹钟触发用系统 BroadcastReceiver + AlarmManager，与既有 `TagScanRescheduleReceiver` 先例一致。

**与 UserTask 的关系**：去重扫描收编为用户任务（user-task spec 既定的 M2 方向，本 spec 立项）：`UserTaskKind` 增加 `DEDUP_SCAN`，补 FGS/进程外生命周期后写适配器；预分析整体是否作为一个 `PRE_ANALYSIS` 任务在任务中心可见，实施时按「出现的任务都可控」语义裁定（见 §13 开放问题）。

**打扰防线**：仅充电+闲置自动跑；温度/电量异常（低电、过热）不启动；前台 Activity 活跃时重活（pHash）让路。

## 9. 任务协议扩展

`shared domain/usertask/` 扩展（枚举即扩展点，收编即加值——协议原设计已为此预留）：

| 新增 | 说明 |
|---|---|
| `UserTaskKind.DEDUP_SCAN` | 去重扫描收编（M2 方向立项）；适配器订阅扫描状态流 |
| `UserTaskKind.CLEANUP_EXECUTE` | 方案批准后的执行任务；一个批准 = 一个任务；进度 = 已处理 uri 数 |
| `UserTaskDestination.ORGANIZE_HUB` / `CLEANUP_PLAN` | 点击跳转目标 |

动词集不变（PAUSE/RESUME/CANCEL/RETRY）；CLEANUP_EXECUTE 支持 CANCEL（已删的不回滚——回收站即撤销通道，取消只停后续）。

**回联通道（立项 user-task spec §13 的「用户任务与 Agent 联动」最小版）**：`UserTaskRegistry` 任务终态 → 回联处理器 → 向最近活跃 chat 会话插入战报卡消息（ADR-016 parts 新卡片类型，登记 `CHAT_CARD_CATALOG.md`；数据同源 `CleanupReportCard` 的战报模型，可分享）。不做 P2 网关依赖的通用回联框架，只做清理任务这一条。

## 10. Agent 侧改动

- **意图路由（ADR-015）**：路由器新增清理意图 deliverable（「帮我清理/相册太乱/空间不够了」类），pattern 层覆盖高频句式，LLM 层兜底。
- **Capability 注册表**：新增 `prepare_cleanup_plan`（读预分析快照 + 增量补齐 → 生成 CleanupPlan 落库 → 回摘要卡）、`execute_cleanup_plan(planId)`（校验方案状态 → 创建 CLEANUP_EXECUTE 任务）、`get_storage_story`（账本摘要查询）。注册遵循 `CAPABILITY_REGISTRY.md` 生命周期规范。
- **方案摘要卡**：端侧生成（数据由引擎算，LLM 不编数字），ADR-016 parts 新类型，渲染原生 Compose（任务中心多卡并存场景已定过原生先例）；点击进方案详情页（新 NavHost 路由 `cleanup_plan/{planId}`，原生全屏页，分组列表 + 缩略图 + 逐项排除 + 批准 CTA）。
- **红线**：方案明细 uri 不进 LLM 上下文（§5）；删除批准闸门 §4。

## 11. 红线核对

- **[PRIVACY]**：全部端侧；LLM 只接触计数与元数据摘要；无新媒体上传面。
- **[PERF]**：交互路径全部读预分析快照，进页零重算；重活只在后台触发条件下跑。
- **[I18N]**：新增文案（类目名/来源名/战报/批准 CTA/通知）五语同步；静态标题不入库（user_task 表既有约束延续到 cleanup_plan）。
- **[DOC-SYNC]**：实施时同步 `features/gallery/AGENTS.md`、`androidApp/AGENTS.md`（路由表/任务节）、`CHAT_CARD_CATALOG.md`（新卡登记）、`CAPABILITY_REGISTRY.md`、PRODUCT.md §6.6 任务线。
- **[PARITY]**：Android 定稿后走 /ios-follow，记 parity 台账。
- **[AGENT-FIRST]**：CleanupPlan/AppSource/叙事聚合全部 domain 纯函数，枚举全部合法状态。

## 12. 分期

| 期 | 范围 | 依赖 |
|---|---|---|
| **M1** | 后台预分析基础设施（触发调度 + FGS + 快照表）+ 存储叙事（总账 + 建议清单 + 双视图）+ App 来源映射默认档 + 去重扫描 FGS 化并收编 `DEDUP_SCAN` 用户任务 | 无，可立即开工 |
| **M2** | Agent 对话式清理闭环：意图路由 + CleanupPlan 协议与落库 + 三 capability + 方案摘要卡 + 方案详情审批页 + `CLEANUP_EXECUTE` 任务 + 回联战报卡 | M1 的快照与收编 |
| **M3** | 来源重要度用户调校 + 空间预测 + 截图阈值设置化 | M2 |

## 13. 开放问题（实施时裁定，标注为建造者决定）

- 预分析整体是否作为 `PRE_ANALYSIS` 用户任务出现在任务中心（倾向：只收编 DEDUP_SCAN，预分析调度本身不出卡，避免任务中心噪音）。
- 方案摘要卡的具体视觉形态（HTML 卡 vs 原生 Compose 新消息类型——倾向原生，与任务中心卡先例一致）。
- 回联战报卡插入「最近活跃会话」的具体选取规则（倾向：发起清理的会话；会话已删则静默只留任务中心历史）。

## 14. 明确不做

- 视频分析/压缩/处理建议；任何重编码压缩。
- 低质量/低评分照片的 Agent 侧删除建议（手工面保留现状）。
- 场景相似（L3）进 Agent 方案（逐组确认语义，留手工面）。
- 游戏化、通用回联框架（P2 网关依赖）、WorkManager 引入、云端分析、iOS 实现。
- 未经批准的任何自动删除（包括「智能全选后自动执行」）。

## 15. 建造者检查决议（2026-11-22，设计评审后补录）

按 superpowers 建造者检查（builder check）回填，D1~D8 均为设计方裁定：

- **D1 执行模型**：批准后优先走静默回收站通路（`MANAGE_MEDIA` 持权，既有 `TrashBackend.trySilentTrash` + `TrashGuidanceGate` 引导面）分批执行（批 50，批间更新进度、CANCEL 停后续批）；未持权降级为单次 `createTrashRequest` 系统一次性批量授权——该降级下任务进度为不定态、CANCEL 仅授权前有效。方案详情页对未持权用户展示「开启删除不再询问」引导（复用既有设置项，无新权限面）。
- **D2 类目互斥裁定**：一张媒体只进一个桶，裁定顺序 `EXACT_DUP > VISUAL_DUP > STALE_SCREENSHOT > APP_NOISE > LARGE_PHOTO`（与 `CategoryArbiter` 哲学一致，防字节重复计数）。VISUAL_DUP 组遵守 dedup 既有 `autoPreselected` 语义：截图/文档视觉组进方案但默认不预选。
- **D3 扫描服务化**：去重扫描抽取为 Service 持有的会话单例（仿 `TagGenerationService.sessionProgress` + `TagScanOrchestrator`），`DedupViewModel` 改为观察会话——一个引擎、口径同源；明确否决「页内扫描 + 预分析扫描」双引擎并行。M1 范围含此重构。
- **D4 新类目保护规则**：`APP_NOISE`/`STALE_SCREENSHOT` 成员保护信号 = `isFavorite` ∨ 属于已命名/「我」人物聚类 ∨ `personPhotoCount` 达阈值（SCARCE_PERSON 语义）——**不用裸 `hasFace`**（梗图常含人脸，裸信号会掏空社交 App 建议面）。`OLD_PHOTO`（≥5 年）单独不构成 `APP_NOISE` 保护，仅与人脸聚类信号叠加时生效。**尊重用户既往决策**：dedup 组 `userOverride=true` 的 keeper 永不预选；SwipeReview KEEP 抑制历史（30 天）内的 uri 不进任何建议组。
- **D5 无快照冷启动**：`prepare_cleanup_plan` 先用免扫描信号（过期截图/来源账本/大文件）即时出方案；dedup 快照缺失/过旧时自动启动 `DEDUP_SCAN` 用户任务并答复「重复照片分析已在后台开始，完成后我会汇报」——复用 §9 回联通道，扫描完成后更新方案并回联。
- **D6 元数据持久化**：Room v29 迁移——`media_assets` 加 `relativePath`/`sizeBytes` 列并回填，由预分析同步维护（§6）；账本与来源映射此后为纯 DB 聚合。
- **D7 触发实现**：`ACTION_POWER_CONNECTED`（manifest 可注册）+ 闲置/温度/电量复核后延迟启动；每日 `AlarmManager` 闲时闹钟（fire 时复核条件，不满足静默跳过）；HyperOS 类 ROM 复用既有 `BackgroundScanGuard` 白名单引导。
- **D8 叙事口径**：存储叙事的「可释放估计」只含五个 `CleanupCategory`（不含低质量两类，与 §3 一致）；整理 hub Hero 维持既有六类目口径。两处数字含义不同属预期（Agent 面 vs 手工面），叙事卡注明口径。
