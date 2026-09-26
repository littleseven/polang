# ADR-007: 端侧自然语言相册搜索 — CV 标签 + LLM 混合架构

> **状态**: 已全面实施 / 已补充 LLM 意图标准化  
> **日期**: 2026-06-30  
> **最后更新**: 2026-08-03  
> **决策**: RD  
> **依赖**: ADR-005（本地/远程推理协议分离，LLM 解析层复用 Agent Runtime）
>
> **实现详情见**: [`docs/03-TECHNICAL-SPECS/GALLERY_SEARCH.md`](../../03-TECHNICAL-SPECS/GALLERY_SEARCH.md)（本 ADR 保留决策背景，具体链路以该文档为唯一事实来源）

---

> ## ⛔ 状态更新（2026-08-03）：ML Kit Image Labeling 已移除，决策 2.2 被 VLM 打标取代
>
> 本 ADR 的**决策 2.2「使用 ML Kit Image Labeling 而非 CLIP」已被取代**：ML Kit Image Labeling 已从依赖与源码中整体移除，TAG 打标改走端侧 VLM 3-Pass 流水线（Qwen3-VL-2B + Florence-2，人脸检测 → DBSCAN 聚类 → 图像打标），详见 [`docs/03-TECHNICAL-SPECS/TAG_GENERATION.md`](../../03-TECHNICAL-SPECS/TAG_GENERATION.md)。
> 同时，2026-08-02 端侧文本 LLM 移除后，§2.4 中 `LOCAL mode → LocalLlmEngine` 推理链路（含 `LocalCommandParser`）已废止，`AiAgentMode` 仅剩 OFF/REMOTE/FEISHU，搜索命令统一走远程 tool_calls。
> 下文中 ML Kit Image Labeling 索引管线与 LOCAL 链路描述**仅为历史记录**，保留用于理解决策脉络。

## 1. 背景与问题陈述

### 1.1 产品需求

PoLang 已从相机转向智能相册（ADR-005 产品重心迁移），需要支持自然语言搜索照片：

| 查询类型 | 示例 | 所需能力 |
|---------|------|---------|
| 时间 | "去年夏天的照片" | 时间语义解析 |
| 物体/场景 | "猫""海滩""食物" | 图像内容理解 |
| 文字 | "包含'会议'的截图" | OCR 文字索引 |
| 地点 | "在上海拍的照片" | GPS + 逆地理编码 |
| 人名 | "我和妈妈的合照" | 人脸聚类 |
| 组合 | "去年在上海拍的猫" | LLM 多条件推理 |

### 1.2 当前状态

相册搜索功能为空骨架：
- `GalleryCapability.search_media` 命令已定义但仅打印日志
- Room DB `media_assets` 表无文本/标签字段
- ML Kit OCR 已集成但结果从不存储
- Agent Runtime 已有 LLM（本地 Qwen3.5-2B + 远程 DeepSeek）
- 没有任何图像标注/分类模型

### 1.3 约束

- **个人开发精力有限**：不能引入需要大量调优的自研模型
- **隐私优先 (PRIVACY)**：所有图像处理必须端侧完成，不上传任何照片数据
- **性能 (PERF)**：搜索响应 < 2s（规则匹配 < 100ms，LLM 路径依赖远程延迟）
- **复用优先**：尽量使用已有基础设施（Agent Runtime、ML Kit、Room DB）

---

## 2. 决策

### 2.1 总体方案：CV 标签 + LLM 语义解析双层架构

![自然语言搜索分层](assets/diagrams/adr007-search-layers.png)

### 2.2 决策 1：使用 ML Kit Image Labeling 而非 CLIP

| 维度 | ML Kit Image Labeling | Chinese-CLIP (ONNX) |
|------|----------------------|---------------------|
| **模型大小** | < 10MB（Google Play Services 内置） | ~300MB（需单独下载） |
| **推理速度** | < 100ms/张 | ~500ms+/张 |
| **标签语言** | 中文（400+ 标签） | 需中文微调版 |
| **集成难度** | 一行依赖，API 简单 | ONNX 转换 + Runtime 集成 |
| **离线可用** | ✅（端侧模型） | ✅ |
| **维护成本** | Google 维护 | 自行维护模型更新 |
| **适用场景** | 常见物体/场景分类 | 高精度语义相似度搜索 |

**决策**：选用 ML Kit Image Labeling 作为第一版图像标注方案。CLIP 作为后续迭代选项，当标签匹配无法满足用户需求时（如跨模态语义搜索）再引入。

### 2.3 决策 2：搜索不单独建索引服务，直接扩展 Room DB

不引入独立的向量数据库或搜索引擎（如 Lucene/FTS5），而是直接在 `media_assets` 表上扩展字段：

```sql
ALTER TABLE media_assets ADD COLUMN labels TEXT;        -- JSON数组
ALTER TABLE media_assets ADD COLUMN ocrText TEXT;       -- OCR文字
ALTER TABLE media_assets ADD COLUMN latitude REAL;      -- GPS
ALTER TABLE media_assets ADD COLUMN longitude REAL;
ALTER TABLE media_assets ADD COLUMN locationName TEXT;  -- 地名
ALTER TABLE media_assets ADD COLUMN indexedAt INTEGER;  -- 索引时间
```

**理由**：
- 照片数量通常在数千到数万级别，Room SQL LIKE 查询完全够用
- 不引入额外依赖，降低维护成本
- 如需全文搜索，可在 `ocrText` 列上建 FTS5 虚拟表（Room 原生支持）

### 2.4 决策 3：搜索与 LLM 通过 Agent Runtime 的 search_media 命令集成

不另建搜索接口，而是将搜索作为 Gallery Capability 的一个命令，通过现有 Agent Runtime 路由：

![搜索指令分发流](assets/diagrams/adr007-dispatch-flow.png)

**理由**：
- 复用已有 Agent Runtime 基础设施
- LLM 对 `search_media` 的语义理解已通过 Prompt 示例增强
- 搜索结果可作为后续对话上下文（计划中）

### 2.5 决策 4：后台索引使用协程而非 WorkManager

不引入 WorkManager 依赖，使用简单的 `CoroutineScope(IO)` 后台批量处理：

```kotlin
class MediaIndexingWorker(context: Context) {
    fun start() { scope.launch { doIndex() } }
    fun cancel() { currentJob?.cancel() }
}
```

**理由**：
- 项目当前未使用 WorkManager，避免引入新依赖
- 索引任务简单（遍历未索引照片、调用 ML Kit、写 DB），不需要 WorkManager 的调度能力
- 未来如需要充电/WiFi 约束调度，可再迁移到 WorkManager

### 2.6 决策 5：LLM 直接输出结构化 SearchIntent（2026-07-20 补充）

**问题**：用户反馈“近半年小孩的照片”返回了大量 2003 年、2023 年等非半年内照片。根本原因不是数据库缺少时间索引，而是 LLM 没有把“近半年”标准化为绝对时间，后续规则解析也没有可靠兜底。

**决策**：让 LLM 在输出 `search_media` / `refine_media_search` 命令时，附带一个标准化的 `SearchIntent` 对象，由 LLM 根据当前时间把相对时间词换算为毫秒时间戳。

![SearchIntent 结构化流](assets/diagrams/adr007-intent-flow.png)

**规则保留策略**：

- `QueryParser` 继续扩展，新增“近半年/近一年/近 N 个月”规则作为兜底，防止 LLM 未输出 `intent` 或端侧小模型无法稳定生成 `intent` 时搜索范围失控。
- LLM 标准化与规则解析并行，不冲突；`SearchIntent` 非空时优先使用结构化入口，`SearchIntent` 为空时回退到 `QuerySegmenter → QueryParser`。

**影响范围**（2026-08-23 修正模块名）：

- `:shared`（commonMain，原 `runtime-core` 已于 Phase 4 整体并入）：`SearchIntent` / `TimeRange` 模型；扩展 `AgentCommand.SearchMedia` / 新增 `RefineMediaSearch`。
- `androidApp`：`ChatSearchCapability`；`ChatViewModel` 实现 `SearchIntent → StructuredFilter` 转换并调用 `MediaSearchEngine.search(filter)`。

**验收标准**：

- 本地/远程模型在 Chat 场景输出“近半年小孩的照片”时，`intent.timeRange` 落在近 6 个月内。
- 第二轮“只要近半年的”细化为 `RefineMediaSearch(constraint, intent)`，并在上一轮结果集内按时间范围过滤。
- 规则解析单测覆盖近半年/近一年/近 N 个月，作为 LLM 失败兜底。

---

## 3. 架构设计

### 3.1 模块划分

```
app/
├── data/indexing/
│   ├── MetadataExtractor.kt      # ML Kit 标签+OCR+EXIF+地名提取
│   └── MediaIndexingWorker.kt    # 后台协程批量索引
├── domain/search/
│   ├── QueryParser.kt            # 时间词/关键词规则解析
│   └── MediaSearchEngine.kt      # 两层搜索策略
├── data/model/MediaEntity.kt     # +6 元数据字段
├── data/local/MediaDao.kt        # +10 搜索查询方法
├── data/local/AppDatabase.kt     # v5→v6 migration
└── features/gallery/capability/
    └── GalleryCapability.kt      # 注入 MediaSearchEngine
```

### 3.2 数据流

![索引与检索闭环](assets/diagrams/adr007-index-search-loop.png)

### 3.3 LLM Prompt 集成

`search_media` / `refine_media_search` 在 System Prompt 中的描述（已更新为标准化的 `SearchIntent`）：

```
- chat_gallery_search: search_media(params.query, params.intent?)
  search_media: 自然语言搜索照片。query 必填，保留用户原话；当查询含时间/地点/人物/人脸等
  可结构化条件时，必须在 params.intent 中输出标准化条件：
    - intent.time_range: {start_ms: 开始时间戳, end_ms: 结束时间戳}
    - intent.keywords: 场景/物体/标签词数组
    - intent.location_keywords: 地点词数组
    - intent.ocr_keywords: 图片中文字词数组
    - intent.person_name: 具体人物名
    - intent.has_faces: true/false
  例："近半年小孩的照片" ->
      {"method":"search_media","params":{"query":"近半年小孩的照片",
       "intent":{"time_range":{"start_ms":1735689600000,"end_ms":1751327999999},
                 "keywords":["小孩"],"has_faces":true}}}

- refine_media_search(params.constraint, params.intent?): 在上一轮搜索结果中追加/收窄条件。
  例："只要近半年的" ->
      {"method":"refine_media_search","params":{"constraint":"只要近半年的",
       "intent":{"time_range":{"start_ms":1735689600000,"end_ms":1751327999999}}}}
```

---

## 4. 实施状态

| 阶段 | 内容 | 状态 |
|------|------|------|
| Phase 1 | DB 扩展 (v6 migration) + ML Kit 依赖 | ✅ 已完成 |
| Phase 2 | 人脸 Embedding + DBSCAN 聚类 + Qwen 标签 + MobileCLIP 语义编码 | ✅ 已完成 |
| Phase 3 | QueryParser + QuerySegmenter + ExplicitFirstSearchPipeline + MediaSearchEngine | ✅ 已完成 |
| Phase 4 | Prompt 增强 + `search_media` Agent 命令 | ✅ 已完成 |
| Phase 5 | Gallery 搜索 UI（搜索框 + 结果网格 + 选择/删除/分享） | ✅ 已完成 |
| Phase 6 | MobileCLIP 语义召回集成 | ✅ 已完成 |
| Phase 7 | LLM 意图标准化 + Chat 多轮细化搜索（search_media / refine_media_search 携带 SearchIntent） | ✅ 已完成 |
| Phase 8 | 语音搜索集成（KWS→ASR→搜索） | ⏳ 待启动 |

---

## 5. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| ML Kit 标签覆盖不足 | 中 | 中 | Layer 2 LLM 解析兜底；未来引入 CLIP |
| 大批量索引时 ML Kit 限流 | 低 | 低 | 分批处理（20 张/批），ML Kit 无请求限制 |
| OCR 误识别导致搜索结果噪音 | 中 | 低 | 搜索时标签匹配权重 > OCR 匹配 |
| 地名依赖 Geocoder 可用性 | 低 | 中 | Geocoder 失败时用 GPS 坐标作为 fallback |
| LLM 不识别 search_media 命令 | 低 | 高 | Prompt 中已加入示例；规则匹配优先于 LLM |
| LLM 时间标准化错误（如把"近半年"解释为去年） | 中 | 高 | Prompt 中提供当前时间 now= 并给出换算示例；QueryParser 新增近 N 个月规则兜底 |
| 首次索引耗时过长 | 中 | 低 | 分批处理 + 后台执行 + indexedAt 断点续扫 |

---

## 6. 相关文档

- `docs/03-TECHNICAL-SPECS/GALLERY_SEARCH.md` — **相册搜索完整实现链路（SSOT）**
- `docs/02-ARCHITECTURE/ADR/ADR-005-local-remote-inference-split.md` — LLM 推理协议分离
- `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` — Agent 运行时架构（search_media 路由）
- `docs/01-PRODUCT/FEATURES.md` — 智能相册产品需求
- `androidApp/src/main/java/com/mamba/picme/domain/search/` — 搜索引擎实现
- `androidApp/src/main/java/com/mamba/picme/domain/tag/` — TAG 生成与语义编码实现
