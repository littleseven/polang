# PoLang 相册自然语言搜索技术方案

> **状态**: 已实施 / 已补充 LLM 意图标准化  
> **最后更新**: 2026-08-03  
> **维护者**: RD Agent  
> **关联代码**: `androidApp/src/main/java/com/mamba/picme/domain/search/`、`androidApp/src/main/java/com/mamba/picme/features/chat/capability/`、`shared/src/commonMain/kotlin/com/mamba/picme/agent/core/model/context/`

---

## 1. 概述

PoLang 相册支持用户用自然语言搜索本地照片，例如：

- “去年3月在室内小孩的照片”
- “海边日落”
- “包含发票的截图”
- “大美女”

相册搜索的**媒体处理**（CV 模型、向量编码、数据库查询）100% 在设备端运行，用户图片/视频文件绝不上传云端；仅文本查询经远程 LLM 做意图解析（端侧文本 LLM 已于 2026-08-02 移除），符合项目 `[PRIVACY]` 红线。

核心设计原则：

| 原则 | 说明 |
|------|------|
| **显式约束优先** | 时间、地点、人脸等有明确索引的语义段先过滤，得到候选集后再做内容匹配 |
| **多层召回融合** | 规则解析 + SQL 多维度召回 + MobileCLIP 语义召回 + 时间衰减排序 |
| **语言无关召回** | 中文 canonical TAG + 本地双语词表，英文用户搜 "cat" 也能命中中文标签 "猫" |
| **失败自动回退** | 任何一层失败或结果为空，自动回退到下一层，保证可用性 |

---

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                      用户输入（自然语言）                         │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  Layer 0: LLM 意图标准化（Chat 场景优先）                         │
│  search_media / refine_media_search 携带 SearchIntent             │
│  近半年/去年/上个月 → TimeRange {startMs, endMs}                  │
│  小孩/海边/上海/自拍 → keywords / locationKeywords / hasFaces     │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼ (Gallery 搜索框入口)
┌─────────────────────────────────────────────────────────────────┐
│  Layer 0.5: QuerySegmenter 语义分段                              │
│  时间 / 地点 / 人物 / 物体 / 场景 / 活动 / OCR / 未知             │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  Layer 1: QueryParser 规则解析                                   │
│  去年/今年/夏天/本周/五月/近半年 → TimeRange                      │
│  北京/室内/海边 → locationKeywords                                │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  Layer 2: ExplicitFirstSearchPipeline 结构化召回                 │
│  显式约束（时间/地点/人脸）先取交集 → candidateIds                │
│  内容关键词在候选集内匹配 labels / mlKitLabels / OCR / 文件名      │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  Layer 2.5: SemanticSearchEngine MobileCLIP 语义召回             │
│  中文查询 → ChineseQueryTranslator → 英文 embedding               │
│  与 candidateIds 内的 image embedding 计算余弦相似度               │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  Layer 3: MediaSearchEngine 融合排序                             │
│  SQL 召回分 + 语义相似度分 + 时间衰减 → 最终列表                   │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  UI: GalleryScreen / Chat 消息卡片                               │
│  长按选择、批量删除/分享、删除后自动刷新搜索结果                   │
└─────────────────────────────────────────────────────────────────┘
```

**双入口说明**：

- **Chat 对话入口**：用户输入先由本地/远程 LLM 解析为 `AgentCommand.SearchMedia(query, intent)`。若 `intent` 非空，直接构造 `StructuredFilter` 调用 `MediaSearchEngine.search(filter)`，跳过分词/规则解析；若 LLM 未输出 `intent`，则回退到 `Layer 0.5 ~ Layer 3` 的规则路径。
- **Gallery 搜索框入口**：不经过 LLM，直接走 `QuerySegmenter → QueryParser → ExplicitFirstSearchPipeline → SemanticSearchEngine → MediaSearchEngine`。

---

## 3. 离线索引层

搜索的准确性依赖后台为每张照片构建的多维度索引。

### 3.1 扫描管道（TagScanOrchestrator + TagGenerationScheduler）

当前任务队列包含 3 个活跃 Pass（另保留 1 个 legacy 枚举值）：

| Pass | 名称 | 产出 | 说明 |
|------|------|------|------|
| 1 | `FACE_DETECTION` | `hasFace`、`faceRoiResult`、`face_embeddings`、`semanticEmbedding` | 人脸 ROI + Glint360K R100 512 维 embedding + MobileCLIP 语义编码（同一张 faceBitmap 完成） |
| 2 | `DBSCAN` | `persons`、`faceId` | 全局人脸聚类，单图多脸按 embedding 分别入簇 |
| 3 | `IMAGE_TAGGING` | `media_assets.labels`（中文 JSON） | 端侧 VLM 打标：Florence-2-base（默认，ORT 独立路径）/ Qwen3-VL-2B（MNN），按 `taggerModelKey` 分流，输出场景/活动/物体/标签/摘要 |
| — | `MOBILE_CLIP_ENCODING`（legacy） | `semanticEmbedding` | **保留枚举值用于兼容历史任务/单独重编码**，常规扫描已在 Pass 1 内完成 |

> 注（2026-08-03）：原 Pass 5 `ML_KIT_TAGGING`（ML Kit Image Labeler）已移除；`media_assets.mlKitLabels` 列仅为历史数据兼容保留，新扫描不再写入。

### 3.2 数据模型（AppDatabase v19）

数据库版本：`19`（`androidApp/src/main/java/com/mamba/picme/data/local/AppDatabase.kt`，以源码 `version` 为准）。

核心表：

- `media_assets`：媒体主表，含 `labels`、`mlKitLabels`、`ocrText`、`locationName`、`semanticEmbedding`、`faceRoiResult`、`lastTagScanPasses`。
- `tags` / `media_tag_cross_ref`：规范化标签词表与媒体-标签多对多关系。
- `ocr_words` / `ocr_word_occurrences`：OCR 文字倒排索引。
- `persons` / `face_embeddings`：人物聚类与 512 维人脸特征向量。
- `location_hierarchy` / `media_locations`：层级地理信息。
- `tag_scan_tasks`：扫描任务队列，支持暂停/恢复/取消/失败重试。

### 3.3 TAG 国际化

- 存储以**中文 canonical** 为主（Qwen 输出中文标签）。
- ~~ML Kit 输出英文标签~~（ML Kit Image Labeler 已移除）：历史英文标签仍存于 `mlKitLabels` 列，与中文标签不混用；新扫描不再产生。
- 运行时通过 `TagTranslator` + `assets/tag_translations.json` 实现：
  - **展示翻译**：中文 TAG → 英文界面显示。
  - **搜索扩展**：英文 query → 中文候选，跨语言召回。
- MobileCLIP 语义搜索通过 `ChineseQueryTranslator` 把中文查询扩展为英文 embedding 候选（词表 + OPUS-MT fallback + 硬编码扩展表）。

---

## 4. 在线查询层

### 4.0 LLM 意图标准化（新增）

`shared/src/commonMain/kotlin/com/mamba/picme/agent/core/model/context/SearchIntent.kt`

在 Chat 场景下，搜索不再依赖纯规则解析。LLM 在解析 `search_media` / `refine_media_search` 命令时，同时输出一个标准化的 `SearchIntent`：

```kotlin
data class SearchIntent(
    val query: String,
    val timeRange: TimeRange? = null,
    val keywords: List<String> = emptyList(),
    val ocrKeywords: List<String> = emptyList(),
    val locationKeywords: List<String> = emptyList(),
    val personName: String? = null,
    val hasFaces: Boolean? = null
)

data class TimeRange(
    val startMs: Long,
    val endMs: Long
)
```

**关键规则**：

- **时间标准化**：Prompt 明确告知 LLM 当前时间（`now=`），要求把“近半年”“去年”“上个月”“近 3 个月”等相对表达换算为毫秒时间戳。例如 `近半年小孩的照片` → `TimeRange(startMs=1735689600000, endMs=1751327999999)`。
- **与本地字段对齐**：`keywords` 对应 `labels/mlKitLabels/ocrText/fileName`；`locationKeywords` 对应 `locationName`；`hasFaces` 对应 `hasFace`；`personName` 对应人脸聚类后的 `persons`。
- **命令层传递（2026-09-26 更新，意图路由 M1）**：远程链路 `search_media` @Tool（`ChatToolService`）在 `query` 之外新增结构化参数 `person` / `fromMs` / `toMs`（必传、空串=无），端侧组装 `SearchIntent(query, timeRange, personName)` 直发 `AgentCommand.SearchMedia(query, intent)`——人物∩时间精确搜索不再依赖 `run_gallery_script` 绕行；`refine_media_search` 同样携带 `intent`（时间维 fromMs/toMs）。意图路由器（ADR-015）直执路径也把槽位（person/fromMs/toMs/label）组装为 `SearchIntent` 复用同一入口；称谓消歧在引擎层 `PersonQueryResolver` 完成（原词透传）。
- **退化策略**：当 LLM 未输出 `intent` 或所有结构化字段为空时，`SearchIntent? = null`，下游自动回退到 `QueryParser` 规则解析。

**Chat 链路**：

```
用户输入 "近半年小孩的照片"
    │
    ▼
AgentOrchestrator.streamChat()
    │
    ▼
远程 LLM（KoogChatAgent）→ AgentCommand.SearchMedia(
    query = "近半年小孩的照片",
    intent = SearchIntent(
        timeRange = TimeRange(...),   // 近半年
        keywords = ["小孩"],
        hasFaces = true
    )
)
    │
    ▼
ChatSearchCapability.execute()
    │
    ▼
ChatViewModel.onSearchMedia(query, intent)
    │
    ▼
searchIntentToStructuredFilter(intent) → StructuredFilter
    │
    ▼
MediaSearchEngine.search(filter)
```

### 4.1 QuerySegmenter 语义分段

`androidApp/src/main/java/com/mamba/picme/domain/search/QuerySegmenter.kt`

把查询切分为带类型的语义段，词典优先级：`SCENE > LOCATION > OBJECT > ACTIVITY > OCR > PERSON`。

示例：

| 查询 | 分段结果 |
|------|----------|
| 去年3月在室内小孩的照片 | `[TIME:"去年3月", LOCATION:"室内", PERSON:"小孩", UNKNOWN:"照片"]` |
| 北京公园里的小孩 | `[LOCATION:"北京", SCENE:"公园", PERSON:"小孩"]` |
| 上周发票截图 | `[TIME:"上周", OCR:"发票", UNKNOWN:"截图"]` |

### 4.2 QueryParser 规则解析

`androidApp/src/main/java/com/mamba/picme/domain/search/QueryParser.kt`

支持的时间词：

- 相对年月：`去年3月`、`今年5月`、`前年8月`
- 绝对年月：`2024年3月`
- 中文月份：`五月`、`十一月`
- 季节：`夏天`、`春天`、`秋天`、`冬天`
- 相对天/周/月：`上个月`、`本周`、`上周`、`昨天`、`今天`、`前天`
- 相对 N 个月：`近半年`、`最近半年`、`半年内`、`近一年`、`近3个月`、`近三个月`（2026-07 补齐，作为 LLM 标准化失败时的兜底）

输出 `StructuredFilter { timeRange, keywords, ocrKeywords, locationKeywords, hasFaces, personName }`。

> **注意**：规则层是 LLM 标准化的兜底，不是主路径。Chat 场景下优先以 LLM 输出的 `SearchIntent.timeRange` 为准，避免“近半年”被规则或模型错误解释为其他时间范围。

### 4.3 ExplicitFirstSearchPipeline 显式约束优先召回

`androidApp/src/main/java/com/mamba/picme/domain/search/ExplicitFirstSearchPipeline.kt`

1. **显式过滤取交集**：时间范围、地点关键词、`hasFace=1` 分别查 `MediaDao`，得到 `candidateIds`。
2. **候选集内内容检索**：在 `candidateIds` 内匹配 `labels`、`mlKitLabels`（仅历史数据，新扫描不产生）、`ocrText`、`fileName`。
3. **无显式约束时**：退化为全局内容检索。

### 4.4 SemanticSearchEngine 语义召回

`androidApp/src/main/java/com/mamba/picme/domain/search/SemanticSearchEngine.kt`

- 使用 `MobileClipEngine` + `MobileClipTokenizer` 对查询文本编码为 512 维 embedding。
- 对候选集（或全量有 `semanticEmbedding` 的媒体）计算余弦相似度，取 Top-K。
- 中文查询先经过 `ChineseQueryTranslator.expandForClip()` 得到多个英文候选，取最大相似度。
- 语义召回**忽略** `filter.keywords`，专门处理标签词表未覆盖的跨模态语义（如 “温馨的氛围”）。

### 4.5 MediaSearchEngine 融合排序

`androidApp/src/main/java/com/mamba/picme/domain/search/MediaSearchEngine.kt`

搜索入口分为两个：

#### `search(query)` — 字符串入口（Gallery 搜索框、无 LLM 标准化时）

1. 尝试 `QuerySegmenter` + `ExplicitFirstSearchPipeline`。
2. 同时调用 `SemanticSearchEngine.searchByText()` 做语义召回。
3. 将 SQL 结果与语义结果通过 `mergeAndRank()` 合并：
   - SQL 召回分 × `SQL_SCORE_WEIGHT`
   - 语义相似度分 × `SEMANTIC_SCORE_WEIGHT`
   - 时间衰减分 × `TIME_SCORE_WEIGHT`
4. 规则失败时回退到 LLM 解析；LLM 失败时回退到全字段模糊搜索。

#### `search(filter, limitToIds?)` — 结构化入口（Chat LLM 标准化后）

直接接收 `StructuredFilter`，跳过 `QueryParser` 和 `QuerySegmenter`：

1. `executeFilter(filter)` 并行查询时间范围、关键词、OCR、地点、人脸。
2. 若 `limitToIds` 非空（如多轮细化），在上一步结果集中按 ID 过滤。
3. 调用 `SemanticSearchEngine.searchByText(query, filter, topK=50)` 做语义召回。
4. `mergeAndRank()` 融合排序后返回。

**多轮细化**：`RefineMediaSearch` 通过 `limitToIds = priorResultIds` 在上一轮结果集内执行 in-set 过滤，避免“只要近半年的”这类追加条件触发全库重搜。

### 4.6 中文查询翻译（ChineseQueryTranslator）

`androidApp/src/main/java/com/mamba/picme/domain/tag/i18n/ChineseQueryTranslator.kt`

翻译分层：

1. **词表精确匹配**：`BilingualVocab.zhToEn`。
2. **OPUS-MT 模型翻译**：轻量 NMT fallback（当前 FP32 已修复但待重新验证）。
3. **硬编码扩展表**：`CLIP_QUERY_EXPANSIONS`，覆盖常见口语化查询（如 `大美女` → `beautiful woman`）。
4. **质量校验**：过滤拟声词、宗教感叹、乱码、重复单字符等异常输出。

---

## 5. UI 集成

### 5.1 搜索界面

`androidApp/src/main/java/com/mamba/picme/features/gallery/GalleryScreen.kt`

- 点击顶部栏搜索图标进入搜索模式，显示 `SearchTopBar`。
- 输入查询后调用 `MediaSearchEngine.search(query)`，结果渲染为 `MediaGrid`。
- 搜索结果网格与主相册共享同一套选择状态：
  - 长按进入选择模式。
  - 拖拽批量选择。
  - 顶部栏切换为选择模式，显示“全选 / 分享 / 删除”。
  - 全选仅针对当前搜索结果集。

### 5.2 删除后自动刷新

`MediaRepositoryImpl.deleteMediaByIds()` 在物理删除和 Room 清理完成后调用 `refreshMediaLibrary()`，触发 `viewModel.allMedia` 变化。`GalleryScreen` 监听 `allMedia` 并在搜索激活时自动重新执行当前查询，保证搜索结果与媒体库一致。

---

## 6. 性能预算与红线

| 指标 | 目标 | 说明 |
|------|------|------|
| 规则路径搜索 | < 300ms | QuerySegmenter + ExplicitFirstSearchPipeline |
| MobileCLIP 语义召回 | < 2s | 含模型初始化和候选集 embedding 解码 |
| 单张 MobileCLIP 编码 | ~50-100ms | Pass 1 内联合并，不额外增加 I/O |
| 单次 Qwen 标签推理 | ~2-8s | 仅在 Pass 3 执行 |
| 隐私 | 零云端 | 所有模型、数据库、查询均在端侧 |

---

## 7. 关键源码索引

| 模块 | 文件 | 职责 |
|------|------|------|
| 搜索入口 | `domain/search/MediaSearchEngine.kt` | 分层搜索编排、融合排序 |
| 显式约束 | `domain/search/QuerySegmenter.kt` | 查询语义分段 |
| 规则解析 | `domain/search/QueryParser.kt` | 时间词/关键词解析 |
| 显式召回 | `domain/search/ExplicitFirstSearchPipeline.kt` | 候选集交集 + 候选集内检索 |
| 语义召回 | `domain/search/SemanticSearchEngine.kt` | MobileCLIP 文本→图像搜索 |
| 中文翻译 | `domain/tag/i18n/ChineseQueryTranslator.kt` | 中文查询 → 英文 embedding 候选 |
| TAG 翻译 | `domain/tag/i18n/TagTranslator.kt` | TAG 展示翻译与搜索扩展 |
| 扫描调度 | `domain/tag/scan/TagScanOrchestrator.kt` | 3-Pass 任务队列与状态机（另含 legacy `MOBILE_CLIP_ENCODING`） |
| 单阶段执行 | `domain/tag/TagGenerationScheduler.kt` | Pass 1/2/3 原子任务（Pass 3 按 `taggerModelKey` 分流 Florence-2 / Qwen3-VL-2B） |
| 数据访问 | `data/local/MediaDao.kt` | 搜索相关 DAO 方法 |
| UI | `features/gallery/GalleryScreen.kt` | 搜索状态、结果展示、批量操作 |
| **LLM 意图模型** | `shared/.../model/context/SearchIntent.kt`（commonMain） | `SearchIntent` / `TimeRange` 定义 |
| **命令定义** | `shared/.../model/command/AgentCommands.kt`（commonMain） | `SearchMedia` / `RefineMediaSearch` 等 |
| ~~**本地 Prompt**~~ | ~~`inference/local/prompt/LocalPromptBuilder.kt`~~ | 已随端侧文本 LLM 移除（2026-08-02） |
| ~~**本地解析器**~~ | ~~`inference/local/parser/LocalCommandParser.kt`~~ | 已随端侧文本 LLM 移除（2026-08-02） |
| **远程 Prompt** | `shared/.../inference/remote/prompt/RemotePromptBuilder.kt`（commonMain） | Tool Spec 中定义 `intent` 参数 |
| ~~**远程解析器**~~ | ~~`inference/remote/parser/ToolCallCommandParser.kt`~~ | 已随 Phase 5 删除（tool_calls 由 Koog agent 循环内直接 `CapabilityRegistry.dispatch`） |
| **Chat 搜索能力** | `features/chat/capability/ChatSearchCapability.kt` | CHAT 场景搜索命令分发 |
| **Chat 执行层** | `features/chat/ChatViewModel.kt` | `SearchIntent` → `StructuredFilter` → `MediaSearchEngine.search(filter)` |

---

## 8. 相关文档

- `docs/02-ARCHITECTURE/ADR/ADR-007-natural-language-photo-search.md` — 原始架构决策
- `docs/03-TECHNICAL-SPECS/TAG_GENERATION.md` — TAG 生成管道细节
- `docs/03-TECHNICAL-SPECS/TAG_GENERATION.md` — 数据库表结构
- `docs/03-TECHNICAL-SPECS/TAG_GENERATION.md` — TAG 国际化方案
- `androidApp/src/main/java/com/mamba/picme/features/gallery/AGENTS.md` — 相册模块实现约束
