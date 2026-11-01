# Muse 日历连接能力调研——PoLang 端侧日历读取与操作复刻可行性

> **调研日期**: 2026-09-27
> **调研方式**: 网络调研（Muse 官方博客/TechCrunch/上手实测文 × 多源交叉验证）+ PoLang 代码侧只读探查（工具注册/prompt 组装/定时任务/权限/iOS 桥接/PrivacyGuard 七点摸底）
> **核心目的**: 评估 PoLang 复刻「连接日历」能力的路线——重点回答：端侧读取与操作（写）日历有没有统一接口；落到 PoLang 现有 Koog + Capability 架构的最短路径
> **性质**: 外部产品调研 + 技术预研，非承诺性规划；遵循「交付即清理」约定，不需要时可直接删除
> **关联文档**: Muse 全景调研（2026-09-25 meta-muse-feature-research，已随交付清理，git 历史可查；本文只展开日历线）

---

## 1. Muse 日历能力全景

| 维度 | 事实 | 置信度 |
|---|---|---|
| 接入形态 | 手机 App 设置页「邮件和日历」**连接器（Connector）**条目；TechCrunch 确认 connectors "including email and calendars" | ✅ 多源 |
| 数据去向 | 日历数据进每用户云端 **Muse Secure VM**（浏览器+文件系统+凭据的专属云虚拟机）；敏感动作经 **Sentinel** 审批 | ✅ 官方 |
| 授权机制 | 官方未披露细节；眼镜版先例（2025-08）经 Meta AI App「应用程序连接」面板关联 **Google Calendar / Outlook**——账号级 OAuth 云端连接为主流推断 | ⚠️ 推断 |
| 读用法 | **每日简报**（偏好时间推送，拉取当日日程）、**撞档提醒**（double booked）、**计划变更提醒**、**查空档**（"生日晚餐找一晚合适的"）、周期任务按空档调整（每周一训练计划） | ✅ 官方博客（Spark 1.1，2026-07-24 起推送） |
| 写用法 | 使用指南任务示例「把这周所有会议邀请整理到日历」隐含创建事件能力；眼镜版明确支持**创建新日程**（未提修改既有日程） | ✅/单源 |
| 隐私口径 | 官方称日历数据不用于模型训练；Mac 版另走**本地权限直读**（iMessage/Notes 先例）——Meta 自己也在往端侧直读路线走 | ✅ |

**核心观察**：Muse 因编排全在云 VM，日历必须做账号级 OAuth connector + 云端副本；用户视角的「连接日历」背后是一整套云端集成。**PoLang 编排全在端侧，直接读系统日历 provider 即可——零账号绑定、零服务端改动、无云端副本**，复刻成本反而更低，且构成差异化卖点（Muse 做不到「日程不出云」）。

---

## 2. 端侧统一接口分析（核心问题）

### 2.1 平台内：有统一系统接口 ✅

两端各有**系统级统一日历 provider**，聚合设备上所有账号的日历（Google/Exchange/本地），App 只对接 provider，账号同步由各账号自带的 sync adapter 负责——**不需要逐家对接 Google Calendar API / Outlook API**。

| | Android | iOS |
|---|---|---|
| 统一接口 | **CalendarProvider**（`CalendarContract`，ContentResolver 访问） | **EventKit**（`EKEventStore`） |
| 聚合范围 | 所有同步账号（Google/Exchange/OEM 账号/`ACCOUNT_TYPE_LOCAL` 本地） | iCloud / 系统里配置的 Gmail、Outlook 等所有 EKSource |
| 读权限 | `READ_CALENDAR`（运行时权限，一次性弹窗，无照片式部分授权模型） | iOS 17+ **Full Access**（`requestFullAccessToEvents`） |
| 写权限 | `WRITE_CALENDAR`（实际读写都要，建议两权限一起请求） | iOS 17+ **Write-Only**（`requestWriteOnlyAccessToEvents`，只许创建）或 Full |
| plist/Manifest | Manifest 两行 uses-permission | `NSCalendarsFullAccessUsageDescription` / `NSCalendarsWriteOnlyAccessUsageDescription`（iOS 17 起双键，旧单键已废弃） |
| 时间范围查询 | `CalendarContract.Instances.query()`——**自动展开重复事件**为具体实例 | `predicateForEvents(withStart:end:calendars:)` + `events(matching:)`——同样自动展开 |

### 2.2 写能力的平台差异（重要，直接影响接口设计）

| 能力 | Android | iOS |
|---|---|---|
| 创建事件 | ✅ insert `Events`（普通 App 即可） | ✅ `EKEvent(eventStore:)` + `save(_:span:)` |
| 修改/删除事件 | ✅ update/delete by `EVENT_ID` | ✅ 同一 save/remove API |
| **创建新日历** | ❌ **仅 sync adapter 可建日历**（`Calendars` 表 insert 须 `CALLER_IS_SYNCADAPTER`）——App 只能写入用户既有日历（选 primary/可见日历；无任何日历的设备罕见，届时只能引导） | ✅ `EKCalendar(for:.event, eventStore:)` + 本地 EKSource + `saveCalendar()`——App 可自建「PoLang」本地日历 |
| 重复事件创建 | ✅ `RRULE` + `DURATION`（重复事件不设 `DTEND`） | ✅ `recurrenceRules = [EKRecurrenceRule(...)]` |
| 重复事件改单次 | ❌ `Exceptions` 表仅 sync adapter 可写——**只能整系列修改** | ✅ `span: .thisEvent` 原生生成例外（改全部用 `.futureEvents`） |
| 与会人/提醒 | Attendees 仅 sync adapter 可写；Reminders 修改范围受限（详见官方 provider 文档） | 可读写（Full Access） |
| 免权限写路径 | ✅ `ACTION_INSERT` Intent 拉起系统日历 App 预填（零权限） | ✅ `EKEventEditViewController` 系统编辑器预填，**用户在系统 UI 点保存** |
| 系统级确认写 | ❌（Intent 预填算半确认） | ✅ EditViewController 天然是「Agent 准备 → 用户系统 UI 确认」形态 |

**结论**：写能力 iOS 强于 Android（可建日历、可改重复事件单次）；Android 的两个缺口（建日历、单次例外）都有低成本绕法（写入既有日历、整系列改或 Intent 拉起）。统一接口按两者能力交集设计即可覆盖 95% 场景，平台差异作为 capability 降级项声明。

### 2.3 跨平台：无现成 KMP 库，自建薄抽象 ⚠️→✅

- 社区无成熟的 KMP 设备日历访问库（awesome-kmp 未收录；JS 生态有 Device Calendar 插件先例，KMP 无对应物；`kotlinx-datetime`/各类 Calendar UI 库均为日期计算或展示，非系统日历数据访问）。
- PoLang 已有成熟的 expect/actual + 组合根注入模式（`MemoryContextProvider`、`IosChatGalleryCapability`、`DispatcherProvider` 等），日历是典型平台能力，**自建 commonMain 薄接口 + androidMain（ContentResolver）/ iosMain（EventKit）actual 即可**，工作量远小于引入和维护外部库。
- iOS 桥接遵循现行铁律：SKIE 形态直出 Swift（suspend→async throws）、Kotlin 侧 try/catch 收口异常不跨边界（kmp-ios-interop）。

### 2.4 统一接口草案（commonMain）

```kotlin
enum class CalendarPermissionState { NOT_DETERMINED, READ_ONLY, READ_WRITE, DENIED }

data class CalendarEvent(
    val eventId: String, val calendarId: String,
    val title: String, val startUtc: Instant, val endUtc: Instant,
    val isAllDay: Boolean = false, val location: String? = null,
    val notes: String? = null, val recurrenceRule: String? = null,  // RRULE 文本
)

data class CalendarEventDraft(/* title/start/end/allDay/location/notes/rrule/calendarId(默认 primary) */)

interface CalendarGateway {
    fun permissionState(): CalendarPermissionState
    fun observeCalendars(): Flow<List<CalendarInfo>>          // id/名称/账户/是否默认
    suspend fun queryEvents(startUtc: Instant, endUtc: Instant, calendarIds: List<String>? = null): List<CalendarEvent>
    suspend fun createEvent(draft: CalendarEventDraft): String   // 返回 eventId；iOS 可指定自建 PoLang 日历
    suspend fun updateEvent(eventId: String, patch: CalendarEventPatch, scope: RecurrenceScope): Boolean
    suspend fun deleteEvent(eventId: String, scope: RecurrenceScope): Boolean
}
```

设计要点：
1. **权限请求不进 gateway**——Android 运行时权限需要宿主 Activity，收口在 UI 层懒请求（仿 `GalleryPermission` 模式）；gateway 只暴露状态查询，保持 commonMain 纯净。
2. `RecurrenceScope = SINGLE | ALL`（iOS 映射 `.thisEvent/.futureEvents`；Android 的 SINGLE 无 sync adapter 时降级为 ALL 或引导，capability 层声明降级）。
3. Agent 工具层包装：`calendar_query_events` / `calendar_create_event` / `calendar_update_event` / `calendar_delete_event`，delete 挂 `CommandRisk` DESTRUCTIVE + Tier A 确认。
4. iOS 侧可把「写」默认走 `EKEventEditViewController` 系统确认形态（与 Muse Sentinel 审批范式同构），作为 Tier A 的平台级实现。

---

## 3. PoLang 接入点（代码侧已摸底）

| 接入点 | 位置 | 说明 |
|---|---|---|
| 工具注册 | `shared/.../ChatToolService.kt:102`（28 个 @Tool） | 仿 `adjustImage` 的 handler 注入模式加 calendar @Tool；iOS 需同步 `ChatToolManifest.kt:29` + `IosChatPrompt.kt:22` + golden 测试三件套（`ChatToolManifestConsistencyTest` / `ChatToolServiceInventoryTest` / `chat_system_prompt_golden.txt` 等）+ `ChatToolCapabilityCoverageTest` |
| prompt 注入 | `RemoteChatEngine.kt:84` `buildPromptSuffix` | **已注入「当前日期」**，「今日日程」是最自然挂点（不碰 golden 可走记忆快照链 `MemoryContextProvider` 供给段） |
| 定时简报 | 项目**未引入 WorkManager**（前台服务 + AlarmManager 兜底；`RecommendedModelAutoDownloader.kt:12` 明确注释） | 每日简报需周期触发器——PRODUCT.md §6.6:383 规划「每周相册简报 feed」时本就要引入 WorkManager，可合流 |
| 渲染 | `MessagePart` 8 种 part（ADR-016 M1-M3 已合） | 今日日程卡 = Text part（轻）或 HtmlCard（富，inline/fullpage 双形态已落地）；新增卡须登记 `docs/03-TECHNICAL-SPECS/CHAT_CARD_CATALOG.md` |
| 隐私 | `PrivacyGuard.kt:34` 仅做输入文本分级（坐标/人脸/OCR 关键词） | 不拦日历文本；ADR-008 允许文本/元数据走远程，`recall_memory` 同款先例；媒体红线守卫（`RemoteInferenceNoMediaUploadGuardTest`）不受影响 |
| 权限 | Manifest 无日历权限；各屏分散式 `rememberLauncherForActivityResult` | 加 `READ_CALENDAR`/`WRITE_CALENDAR` 两行 + chat 内懒请求触发点（仿 `GalleryPermission.kt:48`） |
| iOS 桥接 | iosApp 无 EventKit 代码（grep 零命中）；组合根 D7 = `IosAgentComposition` | expect/actual + iosMain 实现；Info.plist 权限键在 `project.pbxproj` INFOPLIST_KEY_* 段（现有 Camera/PhotoLibrary 先例 :1970-1972） |

---

## 4. 产品价值锚点（相册 × 日历 = Muse 做不到的乘积）

1. **每日简报合流**：今日日程 + 相册动态（"今天 3 个会，会上拍的 12 张照片我整理好了"）——正好补 P1 周报线缺的日历维度。
2. **日程驱动回忆**：「去年今日的行程 + 照片」钩子，比单纯时间轴回忆强。
3. **日程事件自动归组照片**（旅行/会议/演出时段内的照片聚类）——Muse 无相册，独有能力。
4. **差异化叙事**：端侧直读系统日历，无第三方账号绑定、无云端副本（对照 Muse 的云 VM connector 路线）。

## 5. 分期建议

| 期 | 内容 | 改动量粗估 |
|---|---|---|
| **M1 只读** | `CalendarGateway`（androidMain 先行）+ `calendar_query_events` @Tool + 今日日程注入 prompt 尾段 + 懒权限请求 | 中小：接口 + provider 查询 + 1 工具 + 权限 UX |
| **M2 每日简报** | 周期触发器（WorkManager 一次性引入）+ 简报卡（与 §6.6 周报线合流）；iOS EventKit actual 补齐 | 中 |
| **M3 写日历** | `calendar_create_event`（iOS 默认 EditViewController 系统确认形态）+ update/delete + `CommandRisk` 分级 + Tier A 确认卡（Muse Sentinel 范式外部佐证） | 中 |

**取舍点存档**：
- 日程注入 prompt = 日程文本出端（ADR-008 合规）；建议设置页给「日程进入 AI 上下文」开关，默认关。
- Android 建日历/改重复单次两个缺口按 §2.2 降级处理，不为此引入 sync adapter。
- iOS 侧无 EventKit 先例代码，M1 若要双端同发需新增 iosMain actual + plist 键 + 桥接测试；建议 M1 Android 先行、M2 补 iOS（与周报线合流时双端同发）。

---

## 附录：主要来源

**Muse 侧**：[Meta 官方博客：Meta AI Doesn't Just Think, It Acts（Spark 1.1 日历/简报，2026-07）](https://about.fb.com/news/2026/07/meta-ai-muse-spark-doesnt-just-think-it-acts/) · [TechCrunch: Everything new coming to Meta's AI agent Muse（Connect 2026）](https://techcrunch.com/2026/09/23/everything-new-coming-to-metas-ai-agent-muse) · [搜狐：Meta AI 智能眼镜日历集成（Google/Outlook）](https://m.sohu.com/a/930291491_447547/) · [人人都是产品经理：Muse 上手实测（连接器列表）](https://www.woshipm.com/user-research/6469834.html) · [SegmentFault：Muse 完整使用指南](https://segmentfault.com/a/1190000048317803) · [StudioGlobal：Muse 价格、权限与隐私](https://www.studioglobal.ai/zh-cn/discover/answers/what-is-meta-s-muse-personal-ai-agent-6aa1225508dd1e9560dfc1b3)

**平台 API**：[Android 官方：Calendar Provider 概览](https://developer.android.google.cn/guide/topics/providers/calendar-provider) · [Android Manifest.permission（READ/WRITE_CALENDAR）](https://developer.android.com/reference/android/Manifest.permission) · Apple EventKit（EKEventStore / iOS 17 Full Access 与 Write-Only 权限模型，见 Xcode 文档）

**跨端库核查**：[Awesome Kotlin Multiplatform（无设备日历访问库收录）](https://github.com/terrakok/kmp-awesome) · [CSDN：跨平台设备日历插件 Device Calendar（JS 生态先例）](https://blog.csdn.net/gitblog_00389/article/details/144079603)
