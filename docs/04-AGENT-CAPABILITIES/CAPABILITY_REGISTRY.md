# PoLang Agent Capability 注册表

> **边界声明（Boundary Statement）**
> - 本文档定义所有 Agent Capability 的注册表、命令映射、执行逻辑、新增 Capability 实现指南以及生命周期规范。
> - 架构设计以 [`../02-ARCHITECTURE/AGENT_ARCHITECTURE.md`](../02-ARCHITECTURE/AGENT_ARCHITECTURE.md) 为准。
> - 交互规范以 [`../01-PRODUCT/FEATURES.md`](../01-PRODUCT/FEATURES.md) 为准。

**模块定位**: Agent 能力注册表、命令映射、实现指南与生命周期规范  
**主要维护者**: 项目开发者  
**阅读对象**: 项目开发者、AI Agent  
**版本**: 1.4  
**最后更新**: 2026-08-03  

---

## 📋 目录

1. [Capability 概览](#capability-概览)
2. [CameraCapability](#2-cameracapability)
3. [GalleryCapability](#3-gallerycapability)
4. [ChatSearchCapability](#4-chatsearchcapability)
5. [SettingsCapability](#5-settingscapability)
6. [NavigationCapability](#6-navigationcapability)
7. [SystemCapability](#7-systemcapability)
8. [AutoTagCapability](#8-autotagcapability)
9. [AiOptimizeCapability](#9-aioptimizecapability)
10. [RemoteControlCapability](#10-remotecontrolcapability)
11. [BeautyCapability（非 Agent 编排）](#11-beautycapability非-agent-编排)
12. [PersonRelationCapability](#12-personrelationcapability)
13. [MemoryCapability](#13-memorycapability)
14. [ImageEditCapability](#14-imageeditcapability)
15. [附录 A：新增 Capability 指南](#附录-a新增-capability-指南)
16. [附录 B：Capability 生命周期规范](#附录-bcapability-生命周期规范)

---

## 1. Capability 概览

| Capability | name | 活跃场景 | 命令数 | 状态 | 生命周期 |
|------------|------|----------|--------|------|----------|
| **CameraCapability** | `camera` | CAMERA | 12 | ✅ 已落地 | 页面级（CameraScreen） |
| **GalleryCapability** | `gallery` | GALLERY | 7 | ✅ 已落地 | 应用级单例 + 页面 delegate |
| **ChatSearchCapability** | `chat_gallery_search` | CHAT | 5 | ✅ 已落地 | 应用级单例 + `ChatViewModel` delegate |
| **SettingsCapability** | `settings` | SETTINGS | 5 | ✅ 已落地 | 应用级单例 + 页面 delegate |
| **NavigationCapability** | `navigation` | ALL | 2 | ✅ 已落地 | Activity 级（MainActivity） |
| **SystemCapability** | `system` | ALL | 2 | ✅ 已落地 | Activity/Service 级 |
| **AutoTagCapability** | `auto_tag` | GALLERY | 4 | ⚠️ 代码存在但未注册 | 应用级（实际生效路径为 CHAT 场景的 ChatStartTagScanCapability） |
| **AiOptimizeCapability** | `ai_optimize` | GALLERY, CHAT | 1 | ✅ 已落地 | 应用级 |
| **PersonRelationCapability** | `person_relation` | CHAT | 3 | ✅ 已落地 | 应用级（AppContainer 注入 PersonRepository） |
| **MemoryCapability** | `memory_facts` | CHAT | 3 | ✅ 已落地 | 应用级（AppContainer 注入 MemoryRepository） |
| **ChatGallerySummaryCapability** | `chat_gallery_summary` | CHAT | 1 | ✅ 已落地 | 应用级单例 + `ChatViewModel` delegate |
| **ChatStartTagScanCapability** | `chat_start_tag_scan` | CHAT | 1 | ✅ 已落地 | 应用级单例 + `ChatViewModel` delegate |
| **ChatRunScriptCapability** | `chat_run_script` | CHAT | 3 | ✅ 已落地 | 应用级单例 + `ChatViewModel` delegate（端侧 QuickJS 沙箱，命令 `run_gallery_script` / `draw_chart` / `render_html`） |
| **ChatMediaWriteCapability** | `chat_media_write` | CHAT | 3 | ✅ 已落地 | 应用级单例 + `ChatViewModel` delegate（CHAT 场景媒体写执行汇聚点：`delete_media` / `favorite_media` / `select_media`） |
| **ImageEditCapability** | `image_edit` | CHAT | 1 | ✅ 已落地 | 应用级（AppContainer 注入 ChatEditProcessor / ChatEditStateHolder，对话式图片编辑 `edit_image`） |
| **RemoteControlCapability** | `remote_control` | ALL | 0 | ⚠️ 代码存在但未注册 | 应用级单例，不走 AgentCommand 路由；IM 远程控制实际走 RemoteChannel 多通道路径（未 Capability 化） |
| **BeautyCapability** | — | — | — | ✅ 已落地 | 测试/程序化 API，不注册到 Agent 编排 |

> **变更说明（2026-07-06）**：
> - 新增 `AutoTagCapability`、`AiOptimizeCapability`、`RemoteControlCapability`
> - 移除 `AccessibilityCapability`（当前代码库中不存在对应实现）
> - 移除 `EditCapability`（编辑页独立路由的 Capability，非 CHAT 场景；注意不要与后来新增的 `ImageEditCapability` 混淆——后者是 CHAT 场景对话式编辑、已注册生效，见 2026-08-03 变更说明）
> - `CameraCapability` 命令从 11 个增加到 12 个（新增 `delay`）
>
> **变更说明（2026-07-20）**：
> - 新增 `ChatSearchCapability`，支持 Chat 场景自然语言相册搜索与多轮细化（`search_media` / `refine_media_search` 携带 `SearchIntent`）
> - `GalleryCapability.search_media` 参数扩展为 `query: String, intent: SearchIntent?`
>
> **变更说明（2026-07-26）**：
> - 新增 `PersonRelationCapability`（人物关系声明/遗忘，`remember_person_relation` / `forget_person_relation`）
> - 新增 `MemoryCapability`（事实记忆，`remember_fact` / `forget_fact` / `recall_memory`）
> - 两者均为应用级、构造注入仓库（`PersonRepository` / `MemoryRepository`），同时服务聊天工具直调与 JS `capability.dispatch` 写通路；remember/forget 系在 `CommandRisk` 登记为 `REVERSIBLE_WRITE`，`recall_memory` 为 `READ_ONLY`
>
> **变更说明（2026-07-27）**：
> - 补登 CHAT 场景此前漏列的 4 个 Capability：`ChatGallerySummaryCapability`（`get_gallery_summary`）、`ChatStartTagScanCapability`（`start_tag_scan`）、`ChatRunScriptCapability`（`run_gallery_script` / `draw_chart`，端侧 QuickJS 沙箱）、`ChatMediaWriteCapability`（CHAT 媒体写执行汇聚点）
> - 新增「1.2 JS 沙箱能力表面」：登记 `gallery.* / media.* / face.* / tag.*` JSBridge handler 与 AgentCommand 的映射关系（仅 `run_gallery_script` 内部可见，详见 `AGENT_ARCHITECTURE.md` §2.4 路由策略）
>
> **变更说明（2026-07-29）**：
> - 修复「盘点相册返回暂不支持」：`GlobalCapabilityHost.clear()` 改为 `clear(expected)` 守卫——Activity recreate 时旧 composition 的 `onDispose` 可能晚于新宿主的 `set()` 执行，无条件 clear 会把全局宿主覆盖成空 stub，导致 Compose 注册的 CHAT Capability 在本进程内全部不可见（`findCapabilityForCommand` 落空 → METHOD_NOT_FOUND）
> - 上述 4 个 chat 单例 Capability 同步在 `PoLangApplication.initializeCapabilities()` 注册到全局 `CapabilityRegistry` 兜底（与 `ChatSearchCapability` 既有先例一致）；可用性仍由 delegate 绑定状态决定，行为与 host 路径一致
>
> **变更说明（2026-07-29，Phase 3 单轨收敛）**：
> - `CapabilityHost` / `ComposeCapabilityHost` / `LocalCapabilityHost` / `GlobalCapabilityHost` 全部退役删除，`CapabilityRegistry` 成为**唯一注册表**：`findCapabilityForCommand` / `getCapabilitiesForCurrentScene` 只查本地 registry，不再经 Compose 宿主静态桥（竞态根因随之根除，Phase 0 的 `clear(expected)` 守卫一并移除）
> - 注册收口：应用级 Capability 全部在 `PoLangApplication.initializeCapabilities()` 启动期注册；`SettingsCapability` 补注册（此前从未注册，chat 的 `change_theme`/`change_language`/`toggle_setting` 等工具实际为死能力）
> - 页面级 `CameraCapability` 改为随 CameraScreen `DisposableEffect` 在全局 registry register/unregister（`CapabilityRegistry.unregister` / `AgentOrchestrator.unregisterCapability` 新增），实例仍页面级持有相机状态
> - ChatScreen/CameraScreen 的 `RegisterCapability` 调用与 MainActivity 根宿主全部移除；`AiAgentUseCase.registerCameraCapability`（从未被调用）删除
>
> **变更说明（2026-08-03，文档与实现对齐修正）**：
> - 补登 `ImageEditCapability`（`image_edit`，CHAT 场景对话式图片编辑，`edit_image` 命令）：在 `PoLangApplication.initializeCapabilities()` 注册（`container.imageEditCapability`），由 `ChatToolService.editImage()` @Tool 暴露，**已在 §1 表与 §1.1 CHAT 场景映射中补列，并新增 §14 章节**。它与 2026-07-06 移除的 `EditCapability`（编辑页独立路由方向）不是同一个能力，不跳编辑页
> - 登记 `adjust_image` 工具表面：`ChatToolService.adjustImage()` @Tool（brightness / contrast / saturation / temperature 显式参数调整）是 CHAT 场景真实可用工具，但为 **inline handler，不经 CapabilityRegistry 分发**，无对应 AgentCommand/Capability，故不列入能力表，详见 §14.3
> - `PersonRelationCapability` 命令数 2 → 3：补登 `query_person_relation`（`ChatToolService.list_person_relations` @Tool 分发 `AgentCommand.QueryPersonRelation`）
> - `AutoTagCapability` 状态由「✅ 已落地」更正为「⚠️ 代码存在但未注册」：全工程无 `registerCapability` 调用点，其命令在 GALLERY 场景实际不可达；实际生效路径为 CHAT 场景的 `ChatStartTagScanCapability`（`start_tag_scan`）
> - `RemoteControlCapability` 状态由「✅ 已落地」更正为「⚠️ 代码存在但未注册」：IM 远程控制实际走 RemoteChannel 多通道路径（2026-07-27 重新激活），未按 Capability 化设计落地

### 1.1 场景 - 能力映射

| 场景 | 可用 Capability |
|------|-----------------|
| `CAMERA` | CameraCapability, NavigationCapability, SystemCapability |
| `GALLERY` | GalleryCapability, AiOptimizeCapability, NavigationCapability, SystemCapability |
| `SETTINGS` | SettingsCapability, NavigationCapability, SystemCapability |
| `CHAT` | ChatSearchCapability, ChatGallerySummaryCapability, ChatStartTagScanCapability, ChatRunScriptCapability, ChatMediaWriteCapability, ImageEditCapability, AiOptimizeCapability, PersonRelationCapability, MemoryCapability, NavigationCapability, SystemCapability |
| `DEBUG` | NavigationCapability, SystemCapability |
| `UNKNOWN` | NavigationCapability, SystemCapability |

> **注**：`AutoTagCapability`（代码存在但未注册，见 §8）与 `RemoteControlCapability`（代码存在但未注册，IM 远程控制走 RemoteChannel 多通道路径，见 §10）均未注册到 `CapabilityRegistry`，不参与任何场景的实际分发，故未列入上表。

### 1.2 JS 沙箱能力表面（`gallery.*` handler ↔ AgentCommand 映射）

> 路由定位（详见 `AGENT_ARCHITECTURE.md` §2.4）：JS 沙箱**不是与 tool_call 平级的第二条链路**，而是 `run_gallery_script` 工具的执行体。JS 内通过 `await bridge.callAsync(name, args)` 调用两类 handler：
> - **只读 handler**（下表 A）：直连 `QueryGalleryMediaUseCase` 等，**绕开 CapabilityRegistry**，属独立"能力表面"。无对应 AgentCommand，仅服务于盘点/统计。
> - **写 handler**（下表 B，统一入口 `capability.dispatch`）：回环进 `CapabilityRegistry`，复用 AgentCommand/Capability，经 Tier A 应用内确认（见 §2.4.5）。

**表 A — 只读 handler（`bridge.callAsync`，不进注册表，数据不出端）**

| Handler | 参数 | 返回 | 对应 AgentCommand | 说明 |
|---------|------|------|-------------------|------|
| `gallery.summary` | `{}` | 聚合统计对象 | ≈ `get_gallery_summary` | totalPhotos/videos/media、hasFaceCount、personClusterCount、labeled/unlabeled… |
| `gallery.query` | `{label?,ocr?,location?,fromMs?,toMs?,hasFace?,person?,limit?}` | `{ids,total}` | ≈ `search_media`（结构化版） | 多维 AND 过滤（person=已命名人物名）；ids 截断到 limit，total 为真实数 |
| `gallery.tags` | `{}` | `{标签:照片数}`（top 50） | — | 全局标签分布 |
| `gallery.timeline` | `{fromMs?,toMs?,bucketMs?}` | `{桶起始时间戳:照片数}` | — | 按时间分桶（默认月） |
| `gallery.intersect` | `{idsA,idsB,op}` | `{ids,total}` | — | 集合交/并/差，用于多次 query 交叉 |
| `gallery.stats_by_tag` | `{label?,hasFace?,fromMs?,toMs?}` | `{标签:照片数}` | — | 条件过滤后的标签分布 |
| `gallery.stats_by_city` | `{topN?}` | `{城市:照片数}` | — | 按城市分组的媒体计数分布 |
| `media.meta` | `id` | 单张元数据 | — | 不含路径/GPS/OCR/向量；含 city/aestheticScore/faceQualityScore |
| `media.batch_meta` | `[ids]`（上限 50） | `[{...}]` | — | 批量元数据 |
| `face.cluster` | `{topN?}` | 聚类盘点 | — | 不含 embedding 原始数据 |
| `tag.audit` | `{topN?}` | 打标覆盖审计 | — | 词表外标签分布 |
| `tag.scan_status` | `{}` | 扫描会话状态快照 | — | 只读查询（active/state/进度），绝不触发扫描 |

**表 B — 写 handler（`bridge.callAsync('capability.dispatch', {method, params})` → 进注册表）**

| `method` | `params` | 映射 AgentCommand | 目标 Capability | `CommandRisk` |
|----------|----------|-------------------|-----------------|---------------|
| `delete_media` | `{ids:[...]}` | `DeleteMedia` | ChatMediaWriteCapability | DESTRUCTIVE |
| `favorite_media` | `{id, favorite}` | `FavoriteMedia` | ChatMediaWriteCapability | REVERSIBLE_WRITE |
| `select_media` | `{id, selected}` | `SelectMedia` | ChatMediaWriteCapability | REVERSIBLE_WRITE |
| `remember_fact` | `{content, category?}` | `RememberFact`(source=JS_DISPATCH) | MemoryCapability | REVERSIBLE_WRITE |
| `forget_fact` | `{fact_id?|query?}` | `ForgetFact` | MemoryCapability | REVERSIBLE_WRITE |
| `remember_person_relation` | `{name, relation}` | `RememberPersonRelation` | PersonRelationCapability | REVERSIBLE_WRITE |
| `forget_person_relation` | `{name}` | `ForgetPersonRelation` | PersonRelationCapability | REVERSIBLE_WRITE |
| `get_gallery_summary` | `{}` | `GetGallerySummary` | ChatGallerySummaryCapability | READ_ONLY（直通） |
| `recall_memory` | `{query}` | `RecallMemory` | MemoryCapability | READ_ONLY（直通） |
| `query_person_relation` | `{name?}` | `QueryPersonRelation` | PersonRelationCapability | READ_ONLY（直通） |

> **维护约定**：新增 JS handler 时必须同步本表。写 handler 另需同步两处——`CommandRisk.ofMethod` 分级 + `CapabilityDispatchHandler.buildCommand` 白名单（漏登前者会被默认 READ_ONLY 直通，漏登后者 JS 调不通）。唯一注册点：`GalleryScriptHandlers.registerGalleryHandlers`（只读）+ `ChatViewModel` 注入的 `CapabilityDispatchHandler`（写）。

---

## 2. CameraCapability

**职责**: 相机控制、美颜调节、滤镜切换、拍摄模式管理、延迟拍照  
**活跃场景**: `CAMERA`  
**文件**: `androidApp/src/main/java/com/mamba/picme/features/camera/capability/CameraCapability.kt`  
**状态**: ✅ 已落地

### 2.1 支持命令

| 命令 | 参数 | 描述 | 示例 |
|------|------|------|------|
| `capture` | - | 拍照 | "拍照" |
| `toggle_recording` | - | 开始/停止录像 | "开始录像"/"停止录像" |
| `flip_camera` | - | 翻转摄像头 | "翻转镜头" |
| `adjust_zoom` | `factor: Float` | 变焦调节 | "放大两倍" → 2.0x |
| `adjust_exposure` | `offset: Int` | 曝光调节 (+/-) | "调亮一点" → +2 |
| `switch_mode` | `mode: String` | 切换拍摄模式 | "夜景模式" |
| `adjust_beauty` | `type: String, value: Int` | 调节美颜参数 | "磨皮 50" |
| `switch_filter` | `filterType: String` | 切换滤镜 | "冷调滤镜" |
| `switch_style` | `styleType: String` | 切换风格特效 | "卡通风格" |
| `switch_scene` | `scene: String` | 切换场景模式 | "人像场景" |
| `switch_ratio` | `ratio: String` | 切换画幅比例 | "16:9" |
| `delay` | `delay_ms: Int` | 延迟执行（可组合其他命令） | "3秒后拍照" |

### 2.2 美颜参数范围

| 参数 | 范围 | 默认值 |
|------|------|--------|
| 磨皮 | 0-100 | 35 |
| 美白 | 0-100 | 25 |
| 瘦脸 | -50~+50 | 0 |
| 大眼 | 0-100 | 20 |
| 唇色 | 0-100 | 40 |
| 腮红 | 0-100 | 20 |
| 眉毛 | 0-100 | 15 |

### 2.3 生命周期

- **页面级**：由 `CameraScreen` 创建和持有
- `CameraScreen Enter → CameraCapability() 创建 → 注册到全局 CapabilityRegistry`
- `CameraScreen Exit → 从 CapabilityRegistry 注销 → CameraCapability 被 GC 回收`

---

## 3. GalleryCapability

**职责**: 相册查看、删除、分享、搜索、批量选择、收藏  
**活跃场景**: `GALLERY`  
**文件**: `androidApp/src/main/java/com/mamba/picme/features/gallery/capability/GalleryCapability.kt`  
**状态**: ✅ 已落地

### 3.1 支持命令

| 命令 | 参数 | 描述 | 示例 |
|------|------|------|------|
| `view_media` | `media_id: String?` | 查看照片/视频 | "看这张照片" |
| `delete_media` | `media_ids: List<String>` | 删除照片/视频 | "删除这张" |
| `share_media` | `media_ids: List<String>` | 分享照片/视频 | "分享这张" |
| `favorite_media` | `media_id: String, favorite: Boolean` | 收藏/取消收藏 | "收藏这张" |
| `search_media` | `query: String`, `intent: SearchIntent?` | 搜索照片（Chat 场景优先使用 `intent` 做标准化） | "找昨天的照片" |
| `select_media` | `media_id: String, selected: Boolean` | 批量选择 | "多选这张" |
| `switch_view_mode` | `mode: String` | 切换视图模式 | "网格视图" |

### 3.2 生命周期

- **应用级单例 + 页面 delegate**：在 `Application.onCreate()` 中注册一次
- 相册页面激活时绑定 delegate，离开时解绑
- 支持跨页面指令排队：页面再次激活时执行待处理命令

---

## 4. ChatSearchCapability

**职责**: 在 Chat 对话页提供相册自然语言搜索与多轮细化能力  
**活跃场景**: `CHAT`  
**文件**: `androidApp/src/main/java/com/mamba/picme/features/chat/capability/ChatSearchCapability.kt`  
**状态**: ✅ 已落地

### 4.1 支持命令

| 命令 | 参数 | 描述 | 示例 |
|------|------|------|------|
| `search_media` | `query: String`, `intent: SearchIntent?` | 搜索相册照片 | "近半年小孩的照片" |
| `refine_media_search` | `constraint: String`, `intent: SearchIntent?` | 在上一轮结果内细化 | "只要近半年的" |
| `feedback` | `target: String`, `action: String` | 记录用户对某张图片的反馈 | "第三张不错" |
| `more` | `target: String` | 基于指定图片推荐相似照片 | "再来点这种" |
| `exclude` | `constraint: String` | 在后续搜索中排除某类约束 | "不要夜景" |

### 4.2 关键设计

- 通过 `WeakReference<Delegate>` 绑定到 `ChatViewModel`，避免页面销毁后内存泄漏。
- `SearchIntent` 由远程 LLM 生成，`ChatViewModel` 负责将其转换为 `StructuredFilter` 后调用 `MediaSearchEngine.search(filter)`。
- `refine_media_search` 在上轮结果集的 ID 集合内执行 in-set 过滤，避免全库重搜破坏多轮收敛。

### 4.3 生命周期

- **应用级单例**：通过 `ChatSearchCapability.getInstance()` 持有
- `ChatViewModel.init` 中绑定 delegate；`onCleared()` 中解绑
- 页面未激活时命令返回 `CAPABILITY_UNAVAILABLE`

---

## 5. SettingsCapability

**职责**: 主题切换、语言设置、模型管理、人脸引擎切换、调试选项  
**活跃场景**: `SETTINGS`  
**文件**: `androidApp/src/main/java/com/mamba/picme/features/settings/capability/SettingsCapability.kt`  
**状态**: ✅ 已落地

### 4.1 支持命令

| 命令 | 参数 | 描述 | 示例 |
|------|------|------|------|
| `change_theme` | `theme: String` | 切换主题（light/dark/system） | "深色模式" |
| `change_language` | `language: String` | 切换语言（zh/en） | "英文界面" |
| `download_model` | `model_id: String` | 下载 AI 模型 | "下载美颜模型" |
| `switch_face_engine` | `engine: String` | 切换人脸引擎（mediapipe/mnn/custom） | "用 MediaPipe" |
| `toggle_setting` | `key: String, enabled: Boolean` | 开关设置项 | "开启调试模式" |

### 4.2 生命周期

- **应用级单例 + 页面 delegate**
- 设置页面激活时绑定 delegate，离开时解绑

---

## 6. NavigationCapability

**职责**: 页面切换、返回上一页  
**活跃场景**: `ALL` (所有场景)  
**文件**: `androidApp/src/main/java/com/mamba/picme/domain/agent/capability/NavigationCapability.kt`  
**状态**: ✅ 已落地

### 5.1 支持命令

| 命令 | 参数 | 描述 | 示例 |
|------|------|------|------|
| `navigate_to` | `destination: String` | 切换到指定页面 | "去相册"/"打开设置" |
| `go_back` | - | 返回上一页 | "返回"/"回去" |

### 5.2 页面映射

| 意图关键词 | 目标页面 |
|-----------|---------|
| "相机", "拍照" | `camera` |
| "相册", "照片", "gallery" | `gallery` |
| "设置", "设定" | `settings` |
| "聊天", "对话" | `chat` |
| "调试" | `debug` |
| "模型中心" | `model_center` |

### 5.3 生命周期

- **Activity 级**：由 `MainActivity` 创建和持有
- 同时在 `MainActivity` 中通过 `AgentOrchestrator.registerCapability()` 注册到全局 `CapabilityRegistry`

---

## 7. SystemCapability

**职责**: 启动其他应用、打开系统设置  
**活跃场景**: `ALL` (所有场景)  
**文件**: `androidApp/src/main/java/com/mamba/picme/domain/agent/capability/SystemCapability.kt`  
**状态**: ✅ 已落地

### 6.1 支持命令

| 命令 | 参数 | 描述 | 示例 |
|------|------|------|------|
| `launch_app` | `package_name: String?`, `app_name: String?` | 启动应用 | "打开微信" |
| `open_system_settings` | `setting: String` | 打开系统设置 | "打开WiFi设置" |

### 6.2 生命周期

- 在 `MainActivity` 和 `FloatingChatBubbleService` 中创建并注册
- 构造函数注入 `Context`

---

## 8. AutoTagCapability

**职责**: 将标签系统作为 Agent 可编排的 Capability 暴露，支持触发全量标签扫描、查询照片标签、获取进度、取消扫描  
**活跃场景**: `GALLERY`  
**文件**: `androidApp/src/main/java/com/mamba/picme/domain/agent/capability/AutoTagCapability.kt`  
**状态**: ⚠️ 代码存在但未注册

> **注意（2026-08-03 核实）**：全工程无任何 `registerCapability(AutoTagCapability...)` 调用点，本 Capability 未注册到 `CapabilityRegistry`，其命令（`scan_all_tags` 等）在 GALLERY 场景运行时会 `METHOD_NOT_FOUND`。且其实现仍使用过时的执行模型（依赖 `AgentCommand.Unknown.raw` 文本匹配，非标准 sealed class 分发）。实际生效的标签扫描路径为 CHAT 场景的 `ChatStartTagScanCapability`（`start_tag_scan`）。本章节保留仅作历史参考。

### 7.1 支持命令

| 命令 | 参数 | 描述 | 示例 |
|------|------|------|------|
| `scan_all_tags` | - | 触发全量标签扫描 | "扫描所有照片标签" |
| `get_photo_tags` | `photo_id: Long` | 查询指定照片的标签 | "查看这张照片的标签" |
| `get_tag_progress` | - | 获取当前扫描进度 | "标签扫描进度" |
| `cancel_tag_scan` | - | 取消当前扫描 | "取消标签扫描" |

### 7.2 生命周期

- **应用级**：委托给 `TagScanOrchestrator` 执行
- 所有扫描统一走 orchestrator，确保与 UI 控制页进度同源

---

## 9. AiOptimizeCapability

**职责**: AI 一键优化图片，分析照片场景并自动推荐美颜、滤镜、调节参数  
**活跃场景**: `GALLERY`, `CHAT`  
**文件**: `androidApp/src/main/java/com/mamba/picme/domain/agent/capability/optimize/AiOptimizeCapability.kt`  
**状态**: ✅ 已落地

### 8.1 支持命令

| 命令 | 参数 | 描述 | 示例 |
|------|------|------|------|
| `ai_optimize` | `image_uri: String`, `mode: String?` | AI 一键优化图片 | "优化这张照片" |

参数说明：
- `image_uri`: 待优化图片的本地文件 URI（必填）
- `mode`: `fast`（本地场景分析 + 本地预设，默认）或 `smart`（云端视觉模型推荐，需用户授权）

### 8.2 生命周期

- **应用级**：在 `Application.onCreate()` 中注册
- 实际优化逻辑委托给 `AiOptimizeUseCase`

---

## 10. RemoteControlCapability

**职责**: IM 远程控制：管理设备绑定与远程命令执行状态  
**活跃场景**: `ALL`  
**文件**: `androidApp/src/main/java/com/mamba/picme/domain/agent/capability/RemoteControlCapability.kt`  
**状态**: ⚠️ 代码存在但未注册；IM 远程控制实际走 RemoteChannel 多通道路径（未 Capability 化）

> **注意（2026-08-03 核实）**：代码中无任何 `registerCapability` 调用点，本 Capability 实际未注册到 `CapabilityRegistry`（其 KDoc 声称的注册关系不存在）。IM 远程控制线本身已于 2026-07-27 重新激活（RemoteChannel 多通道：飞书 + Telegram，详见 `IM_REMOTE_CONTROL_TECH_SPEC.md`），但未按本文的 Capability 化设计落地。本章节保留仅作历史参考。

### 9.1 支持命令

**无 AgentCommand 路由命令**。`RemoteControlCapability` 不通过 `AgentCommand` 密封类分发命令；所有管理操作通过公开 API 由 `RemoteCommandDispatcher` 直接调用。

`execute()` 始终返回 `METHOD_NOT_FOUND`。

### 9.2 公开管理 API

| API | 说明 |
|-----|------|
| `updateBinding(token, relayUrl, userId, deviceName)` | 更新设备绑定状态 |
| `clearBinding()` | 清除设备绑定 |
| `setAutoConfirm(enabled)` | 设置自动确认模式 |
| `buildStatusString()` | 构建设备状态描述 |

### 9.3 生命周期

- **应用级单例（设计意图，实际未生效）**：原设计为在 `Application.onCreate()` 中创建注册，但代码中无对应注册调用，IM 远程控制线冻结后不再生效
- 进程结束时 `onDestroy()` 清理状态

---

## 11. BeautyCapability（非 Agent 编排）

**职责**: 提供美颜调节的标准化程序化能力，支持生产代码和测试直接调用  
**文件**: `androidApp/src/main/java/com/mamba/picme/capability/BeautyCapability.kt`  
**状态**: ✅ 已落地

### 10.1 支持操作

| 操作 | 参数 | 描述 |
|------|------|------|
| `adjustSmoothing` | `smoothness: Float` | 调整磨皮 |
| `adjustWhitening` | `whitening: Float` | 调整美白 |
| `adjustSlimFace` | `slimFace: Float` | 调整瘦脸 |
| `adjustBigEyes` | `bigEyes: Float` | 调整大眼 |
| `applyAllEffects` | `settings: BeautySettings` | 应用所有美颜效果 |
| `batchTest` | `paramSets: List<BeautySettings>` | 批量测试多组参数 |

### 10.2 说明

- `BeautyCapability` **不注册到 `CapabilityRegistry`**，不作为 Agent 可编排能力
- 供测试引擎、自动化测试或业务代码直接调用
- 若未来需要 Agent 控制美颜参数，应通过 `CameraCapability.adjust_beauty` 命令

---

## 12. PersonRelationCapability

**职责**: 在 Chat 中声明/遗忘人物与「我」的关系（"小宝是我女儿"），声明后支持称谓/合照搜索  
**活跃场景**: `CHAT`  
**文件**: `androidApp/src/main/java/com/mamba/picme/features/chat/capability/PersonRelationCapability.kt`  
**状态**: ✅ 已落地

### 12.1 支持命令

| 命令 | 参数 | 风险级 | 描述 | 示例 |
|------|------|--------|------|------|
| `remember_person_relation` | `name: String, relation: String` | REVERSIBLE_WRITE | 声明人物关系（幂等覆盖=纠错，customLabel 同步覆盖）；relation 归一顺序：谓词枚举名（DAUGHTER 等）→ 中文称谓（女儿 等，归一后存**具体谓词**）→ 原话存 `customLabel`、谓词记 OTHER（不再报错，"大宝是我发小"可用） | "记住小宝是我女儿" |
| `forget_person_relation` | `name: String` | REVERSIBLE_WRITE | 遗忘与某人物的全部关系（幂等） | "忘掉小宝的关系" |
| `query_person_relation` | `name: String?` | READ_ONLY | 查询已记住的人物关系：name 留空返回全部指向「我」的关系，指定人物名只查该人物；`ChatToolService` 对应 `list_person_relations` 工具 | "看一下我的人物关系" / "小宝和我什么关系" |

### 12.2 关键设计

- 构造注入 `PersonRepository`（数据收口在 `domain/person`，不直调 DAO）；"我"端取 `persons.is_self` 全局唯一标记，未标记返回引导性 Error（先去人物分组打开"这是我"）。
- 人名未解析（`persons.name` LIKE 无命中）返回引导性 Error："还没有叫「X」的人物，请先在相册人物分组里给 TA 命名"——LLM 须如实转告，不得假装已记住。
- **谓词枚举**（两层关系模型的粗谓词层）：SPOUSE / PARTNER / SON / DAUGHTER / CHILD / FATHER / MOTHER / PARENT / ELDER_BROTHER / ELDER_SISTER / YOUNGER_BROTHER / YOUNGER_SISTER / SIBLING / GRANDFATHER / GRANDMOTHER / GRANDPARENT / GRANDCHILD / OTHER_FAMILY / FRIEND / CLASSMATE / COLLEAGUE / OTHER；其中 CHILD/PARENT/SIBLING/GRANDPARENT 为"未指定桶"。归一失败的原话走 `customLabel` 自定义称呼（用户语言层），展示与查询解析优先于谓词。
- **确认文本文案规则**：称谓归一到谓词时用谓词中文标签（"已记住：小宝是你的女儿"）；回退 customLabel 时用用户原话（"已记住：大宝是你的发小"）。
- 关系图谱同时供查询侧使用：`PersonQueryResolver` 按 customLabel 精确 → 人名 → 称谓（谓词族扩展：具体称谓含同族未指定桶、泛化称谓含整族）解到 personId，`MediaSearchEngine` 据此走共现查询（"我和小宝的合照""我和二儿子的合照"）。
- Pass 2 全量重聚时关系随 `NamedPersonSnapshot` 导出（按两端名字+isSelf），重聚后按名解析写回（`RelationSnapshotRestorer` 纯函数，含 customLabel）。

### 12.3 生命周期

- **应用级**：`PoLangApplication.initializeCapabilities()` 注册（`AppContainer.personRelationCapability` lazy 单例），CHAT 场景常驻可用。

---

## 13. MemoryCapability

**职责**: 通用事实记忆（"帮我记住…"）的写入 / 检索 / 遗忘  
**活跃场景**: `CHAT`  
**文件**: `androidApp/src/main/java/com/mamba/picme/features/chat/capability/MemoryCapability.kt`  
**状态**: ✅ 已落地

### 13.1 支持命令

| 命令 | 参数 | 风险级 | 描述 | 示例 |
|------|------|--------|------|------|
| `remember_fact` | `content: String, category: String?, source: String` | REVERSIBLE_WRITE | 记住一条事实（落 `memory_facts`，source 区分 CHAT_TOOL / JS_DISPATCH） | "帮我记住小宝对花粉过敏" |
| `forget_fact` | `factId: Long?, query: String?` | REVERSIBLE_WRITE | 按 factId 精确删，或按 query 唯一匹配删（多候选返回列表不删） | "忘掉花粉过敏那条" |
| `recall_memory` | `query: String` | READ_ONLY | LIKE 检索事实（返回含 factId 的列表，供 forget 精确删除）；空串返回全部 | "我对什么过敏？" |

### 13.2 关键设计

- 构造注入 `MemoryRepository`；同一 Capability 同时服务聊天工具直调（不弹确认，LLM 文本回复即确认）与 JS `capability.dispatch`（REVERSIBLE_WRITE 自动走弹窗确认），两通路语义一致、确认强度不同。
- 管理界面：设置页「AI 记忆」（`MemoryFactsScreen`）查看/编辑/删除/清空，与 recall 结果实时一致（Room Flow）。
- v1 召回为 LIKE（无 FTS）；事实内容可经 recall 进入远程上下文属设计内行为（最小必要、按需取回，无常驻注入）。

### 13.3 生命周期

- **应用级**：`PoLangApplication.initializeCapabilities()` 注册（`AppContainer.memoryCapability` lazy 单例），CHAT 场景常驻可用。

---

## 14. ImageEditCapability

**职责**: CHAT 场景对话式图片编辑：根据自然语言指令对照片进行美颜、调色、滤镜等编辑，支持多轮 delta 调整  
**活跃场景**: `CHAT`  
**文件**: `androidApp/src/main/java/com/mamba/picme/domain/agent/capability/ImageEditCapability.kt`  
**状态**: ✅ 已落地

### 14.1 支持命令

| 命令 | 参数 | 描述 | 示例 |
|------|------|------|------|
| `edit_image` | `params: EditParams`（结构化编辑意图）、`image_uri: String?`（可选，留空用会话最近图片） | 对话式图片编辑（美颜/滤镜/调色），后台渲染完成后把结果图发到聊天中，**绝不跳转编辑页** | "磨皮 30" / "再亮一点" / "换胶片风" |

### 14.2 关键设计

- 构造函数显式注入 `Context` / `ChatEditProcessor` / `ChatEditStateHolder`；编辑状态保存在 `ChatEditStateHolder`，按 `AgentContext.memorySessionId` 隔离，支持同一会话内多轮 delta 调整（`ChatEditRecipeBuilder` 在当前 `EditRecipe` 上叠加增量）。
- 目标图片解析顺序：命令携带 `image_uri` → 会话当前 recipe 的 sourceUri → `AgentContext.lastUserImageUri`；三者皆空返回 INVALID_PARAMS 引导。
- 未支持意图（消除物体 / 局部美颜）由 parser 或 LLM 在 `explanation` 中携带 `[unsupported:erase]` / `[unsupported:local_beauty]` 标记，直接返回友好文本，不进入渲染流程。
- 与 2026-07-06 移除的 `EditCapability`（编辑页独立路由方向）**不是同一个能力**：本能力面向 CHAT 场景对话式编辑，不跳编辑页。

### 14.3 关联工具表面：`adjust_image`（inline，不进注册表）

`ChatToolService.adjustImage()` @Tool 暴露 `adjust_image` 工具（brightness / contrast / saturation / temperature 显式参数调整），由 `adjustImageHandler` inline 处理，**不经 `CapabilityRegistry` 分发，无对应 AgentCommand/Capability**。LLM 侧约定：显式数值调整走 `adjust_image`，其余编辑意图（含多轮 delta、滤镜/美颜）走 `edit_image`。

### 14.4 生命周期

- **应用级**：`PoLangApplication.initializeCapabilities()` 注册（`AppContainer.imageEditCapability` lazy 单例），CHAT 场景常驻可用；由 `ChatToolService.editImage()` @Tool 暴露给远程 chat agent。

---

## 附录 A：新增 Capability 指南

### 1. 新增 Capability 流程

#### 步骤 1: 定义 Capability 接口实现

```kotlin
class YourNewCapability : Capability {

    // 1. 定义能力标识
    override val name = "your_feature"
    override val description = "功能的简短描述，用于 System Prompt"

    // 2. 声明活跃场景
    override fun activeScenes() = listOf(
        SceneManager.Scene.YOUR_SCENE
    )

    // 3. 列出支持的命令
    override fun supportedCommands() = listOf(
        "command_1",
        "command_2",
        "text_reply"
    )

    // 4. 实现命令执行逻辑
    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        return when (command) {
            is AgentCommand.YourCommand -> {
                // 执行具体逻辑
                performYourAction(command)
                Result.success(AgentAction.Success(command))
            }

            is AgentCommand.TextReply -> {
                Result.success(AgentAction.Text(command.message))
            }

            else -> {
                Result.success(AgentAction.Error("不支持的命令：${command::class.simpleName}"))
            }
        }
    }

    private fun performYourAction(cmd: AgentCommand.YourCommand): AgentAction {
        // TODO: 实现你的业务逻辑
        // 注意：不要直接依赖 UI 层，使用回调注入
    }
}
```

#### 步骤 2: 注册到 CapabilityRegistry

```kotlin
// 在 Application 或 ViewModel 初始化时
val capabilityRegistry = CapabilityRegistry().apply {
    register(YourNewCapability())
    // 确保 NavigationCapability 始终注册
    register(NavigationCapability(onNavigate = {...}, onBack = {...}))
}
```

#### 步骤 3: 扩展 AgentCommand

```kotlin
sealed class AgentCommand {
    // ... 现有命令

    // 新增命令
    data class YourCommand(val param1: String, val param2: Int) : AgentCommand()
}
```

#### 步骤 4: 更新 PromptBuilder

```kotlin
class PromptBuilder(private val sceneManager: SceneManager) {

    fun buildSystemPrompt(
        capabilities: List<Capability>,
        context: AgentContext
    ): String {
        return buildString {
            appendLine(basePrompt)
            appendLine()
            appendLine("当前页面：${sceneManager.currentScene.value.name}")
            appendLine()
            appendLine("可用功能:")

            capabilities.forEach { cap ->
                appendLine("- ${cap.name}: ${cap.description}")
                cap.supportedCommands().forEach { cmd ->
                    appendLine("  • $cmd")
                }
            }
        }
    }
}
```

#### 步骤 5: 自然语言 → 命令的映射（架构说明）

**当前架构不设关键词映射器**（历史上曾有 `NaturalLanguageMapper` 式示例，为愿景形态，从未落地）。自然语言到 `AgentCommand` 的结构化由远程 LLM 的 tool_calls 一步完成：LLM 按 `@Tool` schema 输出类型化参数，`@Tool` 方法一行薄封装构造 `AgentCommand`。需要本地化兜底时，使用 `:shared` commonMain `agent/core/intent/IntentGuard` 的确定性守卫（误拒回退 / 模糊跳转拦截），勿新增关键词解析器。

### 2. Capability 接口详解

#### `name: String`

**用途**: 能力的唯一标识符  
**要求**: 小写字母 + 下划线，无空格  
**示例**: `"camera"`, `"gallery"`, `"your_feature"`

#### `description: String`

**用途**: 用于 System Prompt 的自描述  
**要求**: 简洁明了，不超过 50 字  
**示例**: `"相机控制：拍照、录像、美颜、滤镜"`

#### `activeScenes(): List<SceneManager.Scene>`

**用途**: 声明该能力在哪些场景可用  
**要求**: 必须返回非空列表  
**示例**:
```kotlin
override fun activeScenes() = listOf(
    SceneManager.Scene.CAMERA,
    SceneManager.Scene.DEBUG
)
```

#### `supportedCommands(): List<String>`

**用途**: 列出所有支持的命令名称  
**要求**: 必须包含 `"text_reply"`  
**示例**:
```kotlin
override fun supportedCommands() = listOf(
    "perform_action",
    "cancel_action",
    "text_reply"
)
```

#### `execute(...): Result<AgentAction>`

**用途**: 执行解析后的命令  
**参数**:
- `command`: 解析后的结构化命令
- `context`: 全局上下文（对话历史、用户信息等）
- `pageContext`: 页面特定上下文（如当前选中的照片）

**返回值**:
- `Result.success(AgentAction.Success)` - 执行成功
- `Result.success(AgentAction.Error(reason))` - 执行失败
- `Result.success(AgentAction.Text(message))` - 文本回复

### 3. 命令解析器扩展

#### 扩展 Sealed Class

```kotlin
sealed class AgentCommand {
    data class YourCommand(
        val param1: String,
        val param2: Int,
        val optionalParam: String? = null
    ) : AgentCommand()
}
```

#### JSON 解析规则

**必须使用 `kotlinx.serialization`，禁止正则**:

```kotlin
import kotlinx.serialization.json.*

fun parseJsonResponse(jsonString: String): List<AgentCommand> {
    return Json.decodeFromString<List<YourCommand>>(jsonString)
        .map { AgentCommand.YourCommand(it.param1, it.param2) }
}
```

#### 错误处理

```kotlin
override suspend fun execute(
    command: AgentCommand,
    context: AgentContext,
    pageContext: PageContext?
): Result<AgentAction> {
    return try {
        when (command) {
            is AgentCommand.YourCommand -> {
                // 参数验证
                if (command.param2 < 0 || command.param2 > 100) {
                    return Result.success(AgentAction.Error("param2 必须在 0-100 范围"))
                }

                // 执行逻辑
                performAction(command)
                Result.success(AgentAction.Success(command))
            }

            else -> Result.success(AgentAction.Error("不支持的命令"))
        }
    } catch (e: Exception) {
        Log.e("YourCapability", "执行失败", e)
        Result.success(AgentAction.Error("执行异常：${e.message}"))
    }
}
```

### 4. 页面上下文集成

#### 定义 PageContext

```kotlin
sealed class PageContext {
    data class YourContext(
        val currentData: YourData?,
        val selectedItems: List<YourItem>,
        val extraInfo: Map<String, Any>?
    ) : PageContext()

    object None : PageContext()
}
```

#### 获取 PageContext

```kotlin
override suspend fun execute(
    command: AgentCommand,
    context: AgentContext,
    pageContext: PageContext?
): Result<AgentAction> {
    val yourContext = pageContext as? PageContext.YourContext

    return when (command) {
        is AgentCommand.YourCommand -> {
            val data = command.param1?.let { findDataById(it) }
                ?: yourContext?.currentData

            data?.let { performAction(it, command) }
            Result.success(AgentAction.Success(command))
        }

        else -> Result.success(AgentAction.Error("不支持的命令"))
    }
}
```

#### UI 层提供 Context

```kotlin
@Composable
fun YourScreen(
    viewModel: YourViewModel
) {
    val pageContext by viewModel.pageContext.collectAsState()

    GlobalAgentPanel(
        orchestrator = agentOrchestrator,
        pageContextProvider = { pageContext }
    )
}
```

### 5. 测试与验证

#### 单元测试

```kotlin
class YourCapabilityTest {

    private lateinit var capability: YourCapability

    @Before
    fun setup() {
        capability = YourCapability(
            onPerformAction = { param1, param2 ->
                // Mock 逻辑
            }
        )
    }

    @Test
    fun `test valid command executes successfully`() {
        val command = AgentCommand.YourCommand("valid", 50)
        val context = AgentContext()
        val pageContext = PageContext.None

        val result = capability.execute(command, context, pageContext)

        assert(result.getOrNull() is AgentAction.Success)
    }

    @Test
    fun `test invalid parameter returns error`() {
        val command = AgentCommand.YourCommand("valid", 150) // Out of range
        val context = AgentContext()
        val pageContext = PageContext.None

        val result = capability.execute(command, context, pageContext)

        assert(result.getOrNull() is AgentAction.Error)
        assert(result.getOrNull()?.message?.contains("范围") == true)
    }

    @Test
    fun `test text_reply returns message`() {
        val command = AgentCommand.TextReply("Hello")
        val context = AgentContext()
        val pageContext = PageContext.None

        val result = capability.execute(command, context, pageContext)

        assert(result.getOrNull() is AgentAction.Text)
        assert((result.getOrNull() as AgentAction.Text).message == "Hello")
    }
}
```

#### 集成测试

```kotlin
class YourCapabilityIntegrationTest {

    @Test
    fun `test end-to-end command flow`() = runTest {
        // 1. 模拟用户输入
        val userInput = "执行你的功能，参数 1 为 test，参数 2 为 75"

        // 2. 构建 Orchestrator
        val orchestrator = AgentOrchestrator(
            llmEngine = mockLlmEngine,
            capabilityRegistry = registry
        )

        // 3. 执行解析与执行
        val result = orchestrator.processUserInput(userInput, createContext())

        // 4. 验证结果
        assertTrue(result.isSuccess)
        verify(mockService).performAction("test", 75)
    }
}
```

#### QA 验收清单

- [ ] 命令能被 LLM 正确解析
- [ ] 参数验证生效
- [ ] 错误信息友好
- [ ] 文本回复符合预期
- [ ] 页面上下文正确传递
- [ ] 多场景切换不崩溃
- [ ] 性能达标（执行耗时 < 100ms）

### 6. 常见陷阱

#### ❌ 陷阱 1: 硬编码 System Prompt

**错误**:
```kotlin
class YourCapability : Capability {
    private val systemPrompt = """
        你是 PoLang 助手...
        可用功能：your_feature
        • command_1
    """.trimIndent()
}
```

**正确**:
```kotlin
class PromptBuilder(private val sceneManager: SceneManager) {
    fun buildSystemPrompt(capabilities: List<Capability>): String {
        // 动态构建，支持插件化
    }
}
```

#### ❌ 陷阱 2: 直接依赖 UI 层

**错误**:
```kotlin
class YourCapability : Capability {
    override suspend fun execute(..., pageContext: PageContext?) {
        // ❌ 直接调用 UI 方法
        uiController.updateView(data)
    }
}
```

**正确**:
```kotlin
class YourCapability(
    private val onUpdateView: ((Data) -> Unit)? = null
) : Capability {
    override suspend fun execute(...) {
        // ✅ 通过回调注入
        onUpdateView?.invoke(data)
    }
}
```

#### ❌ 陷阱 3: 使用正则解析 JSON

**错误**:
```kotlin
fun parseJson(json: String): YourCommand {
    val param1 = json Regex "\"param1\": \"([^\"]+)\"" groupValues[1]
    // ❌ 无法处理嵌套/转义
}
```

**正确**:
```kotlin
fun parseJson(json: String): YourCommand {
    return Json.decodeFromString(json)
    // ✅ 类型安全，支持复杂结构
}
```

#### ❌ 陷阱 4: 忘记注册 Command 映射

**错误**:
```kotlin
// 新增 YourCommand 后未更新 CapabilityRegistry
class CapabilityRegistry {
    fun mapCommand(name: String): AgentCommand? {
        return when (name) {
            "command_1" -> ExistingCommand()
            // ❌ 遗漏 YourCommand
        }
    }
}
```

**正确**:
```kotlin
class CapabilityRegistry {
    fun mapCommand(name: String): AgentCommand? {
        return when (name) {
            "command_1" -> ExistingCommand()
            "your_command" -> YourCommand() // ✅ 同步更新
        }
    }
}
```

#### ❌ 陷阱 5: 忽略线程安全

**错误**:
```kotlin
class YourCapability : Capability {
    private var counter = 0 // ❌ 非线程安全

    override suspend fun execute(...) {
        counter++ // 并发修改
    }
}
```

**正确**:
```kotlin
class YourCapability : Capability {
    private val counter = AtomicInteger(0) // ✅ 原子操作

    override suspend fun execute(...) {
        counter.incrementAndGet()
    }
}
```

### 7. Checklist

#### 代码审查清单

- [ ] Capability 接口实现完整
- [ ] `name` / `description` 清晰准确
- [ ] `activeScenes()` 正确声明
- [ ] `supportedCommands()` 包含所有命令
- [ ] `execute()` 处理所有命令分支
- [ ] 参数验证与错误处理完善
- [ ] 不使用正则解析 JSON
- [ ] 不直接依赖 UI 层
- [ ] 已注册到 CapabilityRegistry
- [ ] 已更新 PromptBuilder
- [ ] 单元测试覆盖核心路径
- [ ] 日志规范（`PoLang:YourCapability`）

#### 文档同步清单

- [ ] 更新 `CAPABILITY_REGISTRY.md` 添加新能力
- [ ] 更新 `COMMAND_REFERENCE.md` 添加命令示例
- [ ] 更新 `FEATURES.md`（如有交互变更）
- [ ] 添加反向链接注释（`// Spec: ...`）

---

## 附录 B：Capability 生命周期规范

> **状态**: 草案  
> **创建**: 2026-06-06  
> **更新**: 2026-06-06  
> **作者**: 项目开发者  
> **评审**: 项目开发者

### 1. 设计目标

| 目标 | 优先级 | 说明 |
|------|--------|------|
| **零内存泄漏** | P0 | Capability 不得持有 Activity/Fragment/Screen 的强引用 |
| **生命周期对齐** | P0 | Capability 的生命周期必须与页面生命周期严格对齐 |
| **组合优于单例** | P0 | 优先使用依赖注入和组合，避免全局单例 |
| **跨页面命令** | P1 | 支持从任意页面发送命令到目标页面 |
| **低功耗** | P1 | 避免后台轮询和无效状态检查 |

### 2. 当前架构问题

#### 2.1 问题清单

```
┌──────────────────────────────────────────────────────────────┐
│  问题 1: 单例持有页面引用（内存泄漏风险）                       │
├──────────────────────────────────────────────────────────────┤
│  CameraCapability.getInstance() ──► WeakReference<Delegate>  │
│  ▲ 问题: WeakReference 只能缓解，不能根治                      │
│  ▲ 问题: 匿名 Delegate 实现隐式持有 CameraScreen 的闭包变量     │
│  ▲ 问题: 单例生命周期 > Activity 生命周期                      │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│  问题 2: DisposableEffect 时序竞争（delegate 绑定后立即解绑）   │
├──────────────────────────────────────────────────────────────┤
│  CameraScreen 重组 ──► DisposableEffect.onDispose()          │
│  ▲ 问题: Compose 重组频繁，onDispose 被过早调用                │
│  ▲ 问题: 导航动画期间，旧页面 DisposableEffect 先 dispose      │
│  ▲ 问题: 新页面 DisposableEffect 后 enter，存在时间窗口        │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│  问题 3: Application 级注册僵化（无法动态扩展）                 │
├──────────────────────────────────────────────────────────────┤
│  Application.onCreate() ──► registry.register(capability)    │
│  ▲ 问题: 注册后无法注销，无法热插拔 Capability                 │
│  ▲ 问题: 所有 Capability 常驻内存，增加基础内存占用              │
│  ▲ 问题: 单元测试需要清理全局状态，增加测试复杂度                │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│  问题 4: SceneManager 与 Compose 生命周期脱节                   │
├──────────────────────────────────────────────────────────────┤
│  MainActivity 设置 scene ──► SceneManager.transitionTo()     │
│  CameraScreen DisposableEffect ──► bindDelegate()            │
│  ▲ 问题: 两个系统独立运行，存在状态不一致窗口                    │
│  ▲ 问题: SceneManager 引用计数复杂，容易出错                   │
└──────────────────────────────────────────────────────────────┘
```

#### 2.2 内存泄漏路径分析

```kotlin
// 当前代码（泄漏路径）
class CameraCapability : BaseCapability() {
    companion object {
        private var instance: CameraCapability? = null  // 静态引用，永不释放
        fun getInstance() = instance!!
    }

    private var delegateRef: WeakReference<Delegate>? = null
}

// CameraScreen.kt
DisposableEffect(Unit) {
    val cameraCapability = CameraCapability.getInstance()  // 获取单例
    cameraCapability.bindDelegate(object : CameraCapability.Delegate {
        override fun onSwitchRatio(ratio: String) {
            aspectRatio = ratio  // 匿名类隐式持有 CameraScreen 的 aspectRatio
        }
        // ... 其他方法同样持有 CameraScreen 的状态引用
    })
    // 即使 WeakReference 被清理，单例仍然存活，且匿名类的类加载器引用链复杂
}
```

### 3. 新架构设计

#### 3.1 核心原则

##### 原则 1: 页面级 Capability（Page-Scoped Capability）

```kotlin
// ✅ 新设计: Capability 随页面创建和销毁
@Composable
fun CameraScreen(
    viewModel: MediaViewModel,
    // Capability 通过参数注入，而非全局单例
    cameraCapability: CameraCapability = remember { CameraCapability() }
) {
    // CameraCapability 直接持有状态，无需 delegate 模式
    DisposableEffect(cameraCapability) {
        // 注册到全局 CapabilityRegistry（唯一注册表，2026-07-29 单轨收敛）
        val orchestrator = AgentOrchestrator.getInstance(context.applicationContext)
        orchestrator.registerCapability(cameraCapability)
        onDispose { orchestrator.unregisterCapability(cameraCapability) }
    }
}
```

##### 原则 2: 组合优于单例（Composition over Singleton）

```kotlin
// ❌ 旧设计: 单例访问
val registry = CapabilityRegistry.getInstance()
registry.register(CameraCapability.getInstance())

// ✅ 新设计: 依赖注入 + 启动期收口注册
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setContent {
            val navigationCapability = remember { NavigationCapability(navController) }
            LaunchedEffect(navigationCapability) {
                orchestrator.registerCapability(navigationCapability)
            }
            NavHost(...) { ... }
        }
    }
}
```

##### 原则 3: 状态内聚（State Cohesion）

```kotlin
// ❌ 旧设计: 状态分散在 Screen 和 Capability 之间
class CameraScreen {
    var aspectRatio by remember { mutableIntStateOf(AspectRatio.RATIO_FULL) }
    // Capability 通过 delegate 回调修改 Screen 状态
}

// ✅ 新设计: Capability 持有自己的状态
class CameraCapability {
    var aspectRatio by mutableIntStateOf(AspectRatio.RATIO_FULL)
        private set

    fun switchRatio(ratio: String) {
        aspectRatio = parseRatio(ratio)
    }
}
```

#### 3.2 架构分层

```
┌─────────────────────────────────────────────────────────────────┐
│  Layer 1: Application（全局配置，无状态）                          │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  - LogModuleConfig 默认值                                │   │
│  │  - BeautyEngine 全局初始化（仅一次）                      │   │
│  │  - 不持有任何 Capability 实例                             │   │
│  └─────────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────────┤
│  Layer 2: Activity（导航级 Capability）                           │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  NavigationCapability ── 绑定 NavController              │   │
│  │  - 生命周期: Activity.onCreate() ~ Activity.onDestroy()  │   │
│  │  - 作用域: 所有页面共享同一个 NavigationCapability        │   │
│  └─────────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────────┤
│  Layer 3: Screen（页面级 Capability）                             │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  CameraCapability ── 绑定 Camera 状态                    │   │
│  │  - 生命周期: Screen Enter ~ Screen Exit                  │   │
│  │  - 作用域: 仅当前 CameraScreen                           │   │
│  │  - 状态: aspectRatio, lensFacing, beautySettings...      │   │
│  └─────────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────────┤
│  Layer 4: ViewModel（业务逻辑，跨配置变更存活）                     │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  MediaViewModel ── 媒体数据管理                           │   │
│  │  - 生命周期: Activity 配置变更存活                         │   │
│  │  - 不持有 Capability 引用（通过回调通信）                   │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

#### 3.3 Capability 生命周期分类

| 类型 | 生命周期 | 典型示例 | 创建位置 | 销毁位置 |
|------|----------|----------|----------|----------|
| **应用级** | Application | `BeautyEngine` | `Application.onCreate()` | 永不销毁 |
| **活动级** | Activity | `NavigationCapability` | `Activity.onCreate()` | `Activity.onDestroy()` |
| **页面级** | Screen | `CameraCapability` | `Screen 首次重组` | `Screen 从组合树移除` |
| **用例级** | UseCase | `AiAgentUseCase` | `需要时创建` | `不再使用时释放` |

### 4. 详细设计

> ⚠️ **2026-07-29 单轨收敛**：`CapabilityHost` / `LocalCapabilityHost` / `ComposeCapabilityHost`
> 已退役删除，`CapabilityRegistry` 是唯一注册表（应用级启动期注册、页面级随 Screen
> register/unregister）。下文 §4.1 与 §5 迁移路径中的 CapabilityHost 内容保留为历史设计参考，
> 现行注册方式见 §1 生命周期表、`PoLangApplication.initializeCapabilities()` 与 §4.2/§4.3 示例。

#### 4.1 CapabilityHost（Capability 容器）——已退役（历史参考）

```kotlin
/**
 * Capability 宿主
 *
 * 管理当前作用域内所有 Capability 的注册和查询。
 * 支持层级查找：如果当前宿主找不到，会委托给父宿主。
 */
class CapabilityHost(
    private val parent: CapabilityHost? = null
) {
    private val capabilities = mutableMapOf<String, Capability>()

    fun register(capability: Capability) {
        capabilities[capability.name] = capability
    }

    fun unregister(capability: Capability) {
        capabilities.remove(capability.name)
    }

    fun find(name: String): Capability? {
        return capabilities[name] ?: parent?.find(name)
    }

    fun findForScene(scene: SceneManager.Scene): List<Capability> {
        return capabilities.values.filter {
            it.activeScenes().contains(scene) || it.activeScenes().isEmpty()
        }
    }
}

// Compose 集成
val LocalCapabilityHost = compositionLocalOf<CapabilityHost> {
    error("CapabilityHost not provided")
}

@Composable
fun rememberCapabilityHost(vararg capabilities: Capability): CapabilityHost {
    val parent = LocalCapabilityHost.current
    return remember(capabilities) {
        CapabilityHost(parent).apply {
            capabilities.forEach { register(it) }
        }
    }
}
```

#### 4.2 页面级 CameraCapability

```kotlin
/**
 * 相机控制 Capability（页面级）
 *
 * 由 CameraScreen 创建和持有，Screen 销毁时自动释放。
 * 不再使用 delegate 模式，状态直接内聚在 Capability 中。
 */
class CameraCapability : BaseCapability() {
    override val name: String = "camera"
    override val description: String = "控制相机拍摄、美颜参数、滤镜..."

    // 状态直接内聚在 Capability 中
    var aspectRatio by mutableIntStateOf(AspectRatio.RATIO_FULL)
        private set
    var lensFacing by mutableIntStateOf(CameraSelector.LENS_FACING_BACK)
        private set
    var beautySettings by mutableStateOf(BeautySettings(enabled = false))
        private set

    // 命令执行直接修改内部状态
    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        return when (command) {
            is AgentCommand.SwitchRatio -> {
                aspectRatio = parseRatio(command.ratio)
                Result.success(AgentAction.Success(...))
            }
            // ... 其他命令
        }
    }

    override fun isAvailable(): Boolean = true  // 页面级 Capability 只要存在就可用
}
```

#### 4.3 Screen 与 Capability 的绑定

```kotlin
@Composable
fun CameraScreen(
    viewModel: MediaViewModel,
    onNavigateToGallery: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    // 创建页面级 Capability
    val cameraCapability = remember { CameraCapability() }

    // 注册到全局 CapabilityRegistry（唯一注册表，Compose CapabilityHost 已退役）
    val orchestrator = remember { AgentOrchestrator.getInstance(context.applicationContext) }
    DisposableEffect(cameraCapability) {
        orchestrator.registerCapability(cameraCapability)
        onDispose { orchestrator.unregisterCapability(cameraCapability) }
    }

    // 将 Capability 的状态绑定到 UI
    val aspectRatio = cameraCapability.aspectRatio
    val lensFacing = cameraCapability.lensFacing

    // UI 使用 Capability 状态
    CameraPreviewContent(
        aspectRatio = aspectRatio,
        lensFacing = lensFacing,
        // ...
    )
}
```

#### 4.4 跨页面命令处理

```kotlin
/**
 * 跨页面命令由 NavigationCapability 统一处理
 *
 * 导航到目标页面后，目标页面的 Capability 自然可用。
 * 无需复杂的排队和轮询机制。
 */
class NavigationCapability(
    private val navController: NavController
) : BaseCapability() {
    override val name: String = "navigation"

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        return when (command) {
            is AgentCommand.NavigateTo -> {
                // 导航到目标页面
                navController.navigate(command.destination)
                // 导航完成后，目标页面的 Capability 会自动接管后续命令
                Result.success(AgentAction.Success(...))
            }
            // ...
        }
    }
}
```

### 5. 迁移路径

#### 5.1 阶段 1: 引入 CapabilityHost（向后兼容）

```kotlin
// 1. 添加 CapabilityHost 和 CompositionLocal
// 2. 修改 CapabilityRegistry 支持从 CapabilityHost 查询
// 3. CameraScreen 同时注册到单例和 CapabilityHost
class CapabilityRegistry {
    fun dispatch(command: AgentCommand, context: AgentContext): Result<AgentAction> {
        // 优先从 CapabilityHost 查找
        val host = LocalCapabilityHost.currentOrNull
        val capability = host?.findForCommand(command)
            ?: findCapabilityForCommand(command)
        // ...
    }
}
```

#### 5.2 阶段 2: 移除单例（破坏性变更）

```kotlin
// 1. 移除 CameraCapability.getInstance()
// 2. 移除 Application 中的 initializeCapabilities()
// 3. MainActivity 创建 NavigationCapability 并注入
// 4. 各 Screen 创建自己的 Capability
```

#### 5.3 阶段 3: 清理废弃代码

```kotlin
// 1. 移除 SceneManager 的引用计数机制
// 2. 移除 CapabilityRegistry 的命令队列
// 3. 移除所有 WeakReference delegate 模式
```

### 6. 内存影响评估

| 指标 | 旧架构 | 新架构 | 变化 |
|------|--------|--------|------|
| 常驻 Capability 数 | 4（永不释放） | 1（Navigation） | -75% |
| CameraCapability 内存占用 | 常驻 | 仅在相机页 | 按需分配 |
| 匿名 Delegate 实例 | 1/页面（泄漏风险） | 0 | 完全消除 |
| 命令队列轮询 | 500ms 间隔 | 无 | 节省 CPU |
| SceneManager 引用计数 | 复杂 | 简单 | 降低复杂度 |

### 7. 红线合规检查

| 红线 | 合规状态 | 说明 |
|------|----------|------|
| [PRIVACY] | ✅ | 无变更 |
| [PERF] | ✅ | 减少常驻内存和后台轮询 |
| [I18N] | ✅ | 无变更 |
| [DOC-SYNC] | ✅ | 本文档同步架构变更 |
| [AGENT-FIRST] | ✅ | 显式生命周期、枚举状态、自描述类型 |

---

> **参考文档**:
> - [AGENTS.md](../../AGENTS.md) — Agent First 架构原则
> - [AGENT_ARCHITECTURE.md](../02-ARCHITECTURE/AGENT_ARCHITECTURE.md) — Agent 架构设计
> - [COMMAND_REFERENCE.md](./COMMAND_REFERENCE.md) — 命令语法参考
