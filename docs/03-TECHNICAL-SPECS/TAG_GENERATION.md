# 相册自动 TAG 生成

> **版本**: 2.1  
> **状态**: 已实施  
> **最后更新**: 2026-08-03  
> **维护者**: 项目开发者  
>
> **历史合并说明**：本文档由以下 6 份文档合并而成：`AUTO_TAG_GENERATION_SPEC.md`、`TAG_DATABASE_SCHEMA.md`、`TAG_SCAN_STATE_MACHINE.md`、`TAG_GENERATION_IMPLEMENTATION_REVIEW.md`、`TAG_GENERATION_PERFORMANCE_ANALYSIS.md`、`TAG_I18N_DESIGN.md`。内容已按「总览 → 3-Pass Pipeline → 状态机 → 数据库模型 → 实现回顾 → 性能分析 → I18N」重组，并消除重复内容。
>
> **相关文档**: `GALLERY_SEARCH.md`（相册搜索 SSOT）、`ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md`

---

## 1. 概述

### 1.1 目标

为相册中每张照片自动生成多维度标签（Tag），支撑 `MediaSearchEngine` 的自然语言搜索召回：

- **人脸维度**：照片中出现了「谁」（通过人脸检测 + 人脸 Embedding + DBSCAN 聚类）
- **内容维度**：照片的场景、物体、活动（通过图像打标模型）
- **语义维度**：MobileCLIP 图像-文本对齐 embedding（用于语义搜索）
- **时间/地点维度**：已有的 EXIF 元数据

### 1.2 核心模块

| 模块 | 文件 | 职责 |
|------|------|------|
| `TagGenerationScheduler` | `domain/tag/TagGenerationScheduler.kt` | 模型加载、单张处理、DBSCAN 聚类、OpenCL 守护 |
| `TagGenerationPipeline` | `domain/tag/TagGenerationPipeline.kt` | 单张照片的 3-Pass 原子处理 |
| `TagScanOrchestrator` | `domain/tag/scan/TagScanOrchestrator.kt` | 持久化任务队列、会话状态机、暂停/恢复/取消/重试 |
| `TagScanTaskEntity` | `data/local/entity/TagScanTaskEntity.kt` | 任务持久化实体及 `TagScanPass` 枚举 |
| `TagCategory` | `domain/tag/TagCategory.kt` | 用户可见类别与 Pass 阶段映射 |
| `OpenClGuardian` | `domain/tag/OpenClGuardian.kt` | Pass 3 前 warmup 与 OpenCL → CPU 降级 |
| `MobileClipEngine` | `domain/tag/MobileClipEngine.kt` | MobileCLIP-S2 语义编码 |
| `FaceClusterEngine` | `domain/tag/FaceClusterEngine.kt` | Glint360K R100 Embedding + DBSCAN 聚类 |
| `TagNormalizer` / `ControlledVocab` | `domain/tag/TagNormalizer.kt` | Qwen 输出规范化与受控词表映射 |
| `TagGenerationService` | `service/tag/TagGenerationService.kt` | 前台 Service，驱动 Orchestrator 并暴露进度 |
| `TagGenerationControlScreen` | `features/gallery/components/TagGenerationControlScreen.kt` | 3-Pass 控制与按类别/时间范围重新生成 UI |

---

## 2. 3-Pass Pipeline

### 2.1 Pipeline 总览

![TAG 生成 3-Pass 流水线](assets/diagrams/tag-3pass-pipeline.png)

### 2.2 TAG 分类体系

| 层级 | 来源 | 存储字段 / 表 | 示例 |
|------|------|---------------|------|
| L1 人脸 | FaceDetector + FaceClusterEngine | `faceRoiResult` JSON、`face_embeddings` 表、`persons` 表、`media_assets.faceId` | `hasFace=true`, `faceCount=2`, `personId=3` |
| L2 内容 | Florence-2（默认）/ Qwen3-VL-2B（备选 tagger） | `media_assets.labels` JSON | `scene=户外`, `activity=旅行`, `tags=["风景","山"]` |
| L3 元数据 | EXIF / MediaStore / 逆地理编码 | `captureDate`, `locationName`, `source`, `latitude`/`longitude` | `time:afternoon`, `location:北京` |
| L4 ML Kit 标签（遗留） | ~~ML Kit Image Labeler~~（已移除） | `media_assets.mlKitLabels` JSON（仅历史数据） | `["Outdoor","Food","Plant"]` |
| L5 语义向量 | MobileCLIP-S2 | `media_assets.semanticEmbedding` Base64 | 512 维 L2 归一化向量 |

#### L1 人脸标签

| 标签 | 来源 | 示例值 |
|------|------|--------|
| `has_face` | ROI 检测 | `true` / `false` |
| `face_count` | ROI 检测 | `1` / `2` / `3+` |
| `person:{name}` | 人脸聚类 + 用户命名 | `person:张三` |
| `group_photo` | `face_count >= 2` | `true` |
| `selfie` | `face_count == 1` | `true` |

#### L2 内容标签（图像打标产出）

Qwen 输出经 `TagNormalizer` 映射到 `ControlledVocab` 后写入 `labels` JSON：

| 类别 | 字段 | 示例 |
|------|------|------|
| 场景 | `scene` | `户外`、`办公室`、`海边` |
| 活动 | `activity` | `旅行`、`吃饭`、`运动` |
| 物体 | `objects` | `["山","天空"]` |
| 标签 | `tags` | `["风景","旅行","户外"]` |
| 摘要 | `summary` | `一家人在公园野餐的温馨场景` |

> **语言策略**：Qwen 统一输出中文；未命中受控词表的原始词保留在 `nonStandard` 中，仍然可被 LIKE 搜索命中。

#### L3 元数据标签

| 标签 | 来源 | 示例值 |
|------|------|--------|
| `time:morning/afternoon/evening/night` | `captureDate` | `time:afternoon` |
| `season:spring/summer/autumn/winter` | `captureDate` | `season:summer` |
| `source:{source}` | `MediaAsset.source` | `source:wechat` |
| `location:{name}` | `locationName` | `location:北京` |

#### L4 ML Kit 英文标签（遗留，已停止产生新数据）

> **注意**：ML Kit Image Labeling 打标链路（`MlKitTagExtractor` / `ML_KIT_TAGGING` Pass）已整体移除，
> `mlKitLabels` / `mlKitLabelsZh` 列仅为兼容历史数据保留，新扫描不再写入。以下描述仅对存量历史数据有效。

- ML Kit `ImageLabeler` 输出英文标签，按置信度过滤后写入 `mlKitLabels` JSON 数组。
- 不随存储翻译，避免引入额外延迟与翻译错误。
- 跨语言搜索由 `QuerySegmenter` / `MediaSearchEngine` 的 LLM 语义解析层处理，或依赖独立翻译映射（见 [I18N 章节](#7-国际化i18n)）。

### 2.3 Pass 详细设计

#### Pass 1：人脸检测 + Embedding + MobileCLIP 语义编码

**输入**: 照片 URI  
**输出**: `Stage1WithEmbeddingsResult`

```kotlin
suspend fun stage1WithEmbeddings(
    uri: String,
    lensFacing: Int,
    mediaId: Long
): Stage1WithEmbeddingsResult
```

**处理步骤**:

1. 加载 Bitmap（最长边限制，避免 OOM）
2. 人脸 ROI 检测 → `Stage1Result`（`hasFace` / `faceCount` / `roiRects`）
3. 对每张人脸：
   - 用 106 关键点做仿射对齐 → 112×112 ROI
   - `FaceClusterEngine.extractFeature()` → 512 维 Glint360K R100 embedding
   - 写入 `face_embeddings` 表
4. **`MobileClipEngine.encodeImage()` → 512 维语义向量 → Base64**
   - **无论是否检测到人脸，都会执行 MobileCLIP 语义编码**，确保风景、文档、静物等无人脸照片也能被语义搜索召回。
5. 写入 `media_assets.faceRoiResult` / `semanticEmbedding` / `hasFace`

#### Pass 2：DBSCAN 全局聚类

- 读取所有未分配 `personId` 的 `face_embeddings`
- 余弦距离 + DBSCAN 参数：`DBSCAN_EPS`、`DBSCAN_MIN_PTS`
- 生成 `persons` 记录，更新 `face_embeddings.personId` 与 `media_assets.faceId`
- 对仅含一张照片的人脸直接新建单簇

**人物命名与全量重聚类保名**：
- 用户在 **TAG 生成控制页** 可为每个人物簇输入名称，写入 `persons.name`
- 全量重跑 Pass 2 时，系统会在清表前捕获已命名人物的 centroid 快照（`NamedPersonSnapshot`）
- 新簇质心与旧快照做余弦相似度匹配，阈值 `NAME_PRESERVE_MIN_SIMILARITY = 0.65`
- 匹配成功则复用原 `personId` 与 `name`，避免重扫描后用户命名丢失
- 每个旧人物最多被复用一次，未匹配到的新簇将创建新的匿名人物

#### 人物封面选择：NIMA + eDifFIQA 美学打分

人物聚类（Pass 2）产出人物簇后，系统通过端侧美学打分为每个人物挑选封面图（`persons.coverMediaId`）：

- **模型与打分**（`domain/aesthetic/`）：
  - `NimaScorer`：NIMA 美学评分（模型 `nima-aesthetic-onnx`，输出 1..10 分）
  - `EdiffiqaScorer`：eDifFIQA 人脸质量评分（模型 `ediffiqa-face-quality-onnx`，输出约 0..1 分）
  - 两者均为 ONNX Runtime 推理，优先 **NNAPI** 加速（GPU/DSP），失败兜底 CPU；`OrtSession` 跨调用复用，避免重复建会话（NNAPI 编译昂贵）
  - 打分结果持久化到 `media_assets.aestheticScore` / `faceQualityScore`（DB v19，见 §5.4），由 `AestheticScoreWorker` 批量回填
- **加权选择**（`CoverSelector`）：人脸质量为主、美学为辅的加权组合，取最高分者作为封面：

```kotlin
// 美学归一 (a-1)/9 ∈ [0,1]；人脸质量 q clamp 到 [0,1]
combinedScore = W_FACE * q + W_AESTHETIC * aNorm
// W_FACE = 0.6, W_AESTHETIC = 0.4（可调常量）
// 仅人脸质量 → 用 q；仅美学 → 用 aNorm；都无 → 回退旧逻辑（firstOrNull）
```

- 该机制替代了原先简单取第一张照片作为人物封面的策略，优先选择人脸清晰且构图美观的照片。

#### Pass 3：图像打标（图像内容理解）

**模型**: tagger 可切换（默认 Florence-2 ORT；备选 Qwen3-VL-2B MNN-LLM VLM；SmolVLM/LFM2 已下线移除）  
**输入**: 照片 URI + `faceRoiResult` 人脸上下文  
**输出**: `QwenTags` → `TagNormalizer` → `UnifiedTagResult` JSON

**Prompt 约束**:
- 要求输出 JSON：`scene` / `activity` / `objects` / `tags` / `summary`
- 所有字段使用中文（专有名词除外）
- `tags` 3-5 个中文关键词

**OpenCL 守护**:
- `OpenClGuardian.warmup()` 在 Pass 3 推理前执行
- 单次推理带超时；连续失败/超时后标记设备降级 CPU，黑名单持久化到 DataStore

#### Pass 4：MOBILE_CLIP_ENCODING（保留兼容）

- `TagScanPass.MOBILE_CLIP_ENCODING` 保留枚举值
- 常规扫描已将 MobileCLIP 编码内联合并到 Pass 1，避免重复解码
- 仅用于：
  - 兼容历史任务记录
  - 单独对旧数据补全语义 embedding 的场景

### 2.4 类别到 Pass 映射

| TagCategory | 映射 Pass |
|-------------|-----------|
| `FACE` | `FACE_DETECTION` + `DBSCAN` |
| `SCENE` / `ACTIVITY` / `OBJECTS` / `TAGS` / `SUMMARY` | `IMAGE_TAGGING` |

> 原 `ML_KIT_LABELS` 类别与 `ML_KIT_TAGGING` Pass 已随 ML Kit 打标链路移除。

### 2.5 OpenCL 超时与 CPU 降级

```kotlin
class OpenClGuardian(
    context: Context,
    engine: LocalLlmEngine,
    prefs: UserSettingsRepository
)
```

- **Warmup**: Pass 3 开始前执行一次短推理，确认 OpenCL 可用
- **超时**: 单次 Qwen 推理带超时，防止 OpenCL 挂起
- **降级**: 连续失败/超时后标记设备降级 CPU，黑名单写入 DataStore
- **恢复**: 用户可在设置中手动重置，或每周自动尝试一次
- **模型加载**: `TagGenerationScheduler.ensureModelLoaded()` 按 Guardian 策略自动选择 OpenCL / CPU

---

## 3. 状态机

### 3.1 设计目标

`TagScanOrchestrator` 通过有限状态机管理 3-Pass TAG 扫描会话。状态机需要满足：

- **可观测**：UI 可实时感知当前阶段
- **可中断**：支持暂停、恢复、取消
- **可恢复**：Service 重建或进程被杀后，任务状态持久化在 `tag_scan_tasks` 表
- **终态明确**：取消后进入 `CANCELLED`，不再允许恢复

### 3.2 状态定义

```kotlin
enum class ScanSessionState {
    IDLE,        // 空闲，无活跃会话
    RUNNING,     // 正在执行任务
    PAUSING,     // 已收到暂停指令，等待当前任务结束
    PAUSED,      // 已暂停，可恢复
    CANCELLING,  // 已收到取消指令，正在终止
    CANCELLED,   // 已取消，终态
    COMPLETED    // 全部完成，终态
}
```

### 3.3 状态流转

```
IDLE --(scheduleAutoScan/scheduleRegenerate/schedulePass/resume)--> RUNNING
RUNNING --(pause)--> PAUSING --(当前任务结束)--> PAUSED
PAUSED --(resume)--> RUNNING
RUNNING/PAUSING/PAUSED --(cancel)--> CANCELLING --(当前任务被中断/任务全部标记为 CANCELLED)--> CANCELLED
RUNNING --(队列中无剩余任务)--> COMPLETED
RUNNING --(执行异常，非取消)--> PAUSED
```

### 3.4 关键行为说明

| 当前状态 | 用户操作 | 实际动作 | 下一状态 |
|---|---|---|---|
| IDLE / PAUSED | 启动扫描 | `createTasks()` + `startSession()` | RUNNING |
| RUNNING | 暂停 | `pauseSession()` 把 PENDING/RUNNING 任务改为 PAUSED | PAUSING |
| PAUSING | — | `pollNextPendingBySession()` 返回 null，当前任务结束后 | PAUSED |
| PAUSED | 恢复 | `resumeSession()` 把 PAUSED 任务改回 PENDING，重新启动协程 | RUNNING |
| RUNNING / PAUSING / PAUSED | 取消 | `cancelSession()` 把任务改 CANCELLED，取消 `currentJob` | CANCELLING |
| CANCELLING | — | `runSession()` 捕获 `CancellationException` 或轮询发现无 PENDING 任务 | CANCELLED |
| RUNNING | — | 所有任务完成 | COMPLETED |
| RUNNING | 执行异常 | `runSession()` catch Exception，状态置为 | PAUSED |

### 3.5 防御性规则

- **重复暂停/取消无效**：`pause()` / `cancel()` 内部会检查当前状态，若已处于目标状态或终态则直接返回。
- **取消后不可恢复**：`resume()` 检查到 `CANCELLED` / `CANCELLING` 时拒绝恢复。
- **活跃会话唯一**：`startSession()` 通过 `sessionMutex` 保证同一时刻只有一个 `currentJob` 在运行。

### 3.6 与数据库任务状态的映射

`tag_scan_tasks.status` 是持久化状态，`ScanSessionState` 是会话级运行时状态。

| 运行时状态 | 对应任务状态 |
|---|---|
| RUNNING | 存在 `RUNNING` 任务 |
| PAUSING / PAUSED | 存在 `PAUSED` 任务，无 `RUNNING/PENDING` |
| CANCELLING / CANCELLED | 存在 `CANCELLED` 任务，无 `RUNNING/PENDING/PAUSED` |
| COMPLETED | 全部任务为 `COMPLETED` |

### 3.7 UI 反馈

`TagGenerationControlScreen` 根据 `ScanSessionState` 显示：

- `RUNNING`：显示进度条、「暂停」「取消」按钮
- `PAUSING`：显示进度条、禁用态「暂停中...」和可用的「取消」按钮
- `CANCELLING`：显示进度条和「取消中 · 等待当前任务结束」，隐藏所有操作按钮
- `PAUSED`：显示「恢复」「取消」按钮
- `CANCELLED`：显示红色「已取消」卡片，隐藏操作按钮，允许重新开始扫描
- `COMPLETED`：显示完成提示并刷新统计

### 3.8 线程模型

![TAG 扫描线程模型](assets/diagrams/tag-thread-model.png)

**关键原则**：
- 控制线程与任务线程必须分离。
- JNI/OpenCL 阻塞只阻塞 `tag-worker`，`tag-control` 仍可立即响应取消、更新 `_progress` 为 `CANCELLED`。
- `cancel()` 会立即把状态置为 `CANCELLED`，任务线程即使仍在运行，其结果也会被 `ensureActive()` 丢弃。

---

## 4. 队列编排与生命周期

### 4.1 TagScanOrchestrator

负责将一次扫描拆分为可持久化的原子任务，支持：

- `scheduleAutoScan(policy)` — 自动增量扫描
- `scheduleRegenerate(mediaIds, categories, mode)` — 对指定媒体重新生成指定类别
- `scheduleRegenerateByQuery(query, categories, mode)` — 按时间范围查询批量生成
- `schedulePass(pass, query, mode, policy)` — 执行单个 Pass 阶段
- `pause()` / `resume()` / `cancel()` — 会话生命周期控制

### 4.2 任务持久化

```kotlin
@Entity(tableName = "tag_scan_tasks")
data class TagScanTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val mediaId: Long,
    val pass: TagScanPass,                 // FACE_DETECTION / DBSCAN / IMAGE_TAGGING / MOBILE_CLIP_ENCODING（legacy，仅兼容历史任务/单独重编码）
    val tagCategories: String? = null,     // 目标类别 JSON 数组
    val status: TagScanTaskStatus = PENDING,
    val priority: Int = 0,
    val attemptCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val scheduledAt: Long? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val errorMessage: String? = null
)
```

### 4.3 增量去重策略

- 通过 `media_assets.lastTagScanPasses` JSON 判断已覆盖的 Pass
- `ScanQueuePolicy.skipRecentlyTaggedMs` 控制避重窗口（默认 24h）
- `ScanQueuePolicy.order` 支持 `OLDEST_FIRST` / `NEWEST_FIRST`
- 启动时自动将异常中断的 `RUNNING` 任务重置为 `PENDING`
- **Pass 1 / Pass 3 选片与计数仅限照片（`type = 'PHOTO'`）**：人脸检测与图像打标都依赖
  `loadBitmap` 解码图片，视频会被 MIME 拦截返回 `null`，结果列（`faceRoiResult` / `labels`）
  永远写不进去。若不过滤，视频会被永久计入“待 Pass 1” → 计数器永不归零、增量扫描无限
  重选同一批视频（“永远扫不完” bug 的根因）。涉及 `MediaDao.getMediaWithoutFaceRoiCount` /
  `getMediaWithoutFaceRoiIds` / `getMediaForIncrementalScan*Projection`，以及
  `TagScanOrchestrator.isPassMissing`（视频直接判“不缺失”）与 `schedulePass` 的 FULL 路径
  （`filterPhotosOnly`）。
- **照片解码失败写哨兵**：`executeFaceDetection` 在 `loadBitmap` 返回 `null`（损坏文件 /
  本机不支持的格式 / URI 失效）且媒体为照片时，向 `faceRoiResult` 写入
  `{"hasFace":false,"faceCount":0,…,"decodeError":true}` 哨兵，使“`faceRoiResult IS NULL`”
  口径收敛，避免这批照片被增量 Pass 1 无限重选；FULL 全量重扫 `resetAllFaceData()` 会清空
  该列以备将来重试。视频不写哨兵（已由选片层排除）。

---

## 5. 数据库模型

当前数据库版本：**19**（Room `@Database(version = 19)`，`AppDatabase.kt`）。完整迁移历史见 [§5.4](#54-数据库版本迁移历史)。

### 5.1 核心表结构

#### media_assets（媒体资产主表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | Long (PK) | 自增主键，媒体唯一标识 |
| `uri` | String | Content URI，指向系统媒体库 |
| `type` | MediaType | 类型：PHOTO / VIDEO（Room 按 enum 名以文本存储；Pass 1/3 选片仅 PHOTO） |
| `captureDate` | Long | 拍摄时间戳（毫秒） |
| `fileName` | String | 文件名 |
| `duration` | Long? | 视频时长（图片为 null） |
| `hasFace` | Boolean | 是否检测到人脸（Pass 1 产出） |
| `faceId` | String? | 人物簇 ID（Pass 2 聚类后填充） |
| `source` | String? | 来源标识 |
| `labels` | String? | **TAG 结果 JSON**（Pass 3 产出） |
| `mlKitLabels` | String? | **ML Kit 英文标签 JSON 数组**（遗留字段：写入源 ML Kit 打标链路已移除，仅为历史数据兼容保留，不再产生新数据） |
| `mlKitLabelsZh` | String? | **ML Kit 中文标签 JSON 数组**（遗留字段：同上，仅历史数据） |
| `ocrText` | String? | OCR 提取的文字 |
| `latitude` | Double? | GPS 纬度 |
| `longitude` | Double? | GPS 经度 |
| `locationName` | String? | 逆地理编码地名 |
| `indexedAt` | Long? | 索引完成时间戳（null=未索引） |
| `faceRoiResult` | String? | **人脸 ROI 检测 JSON**（Pass 1 产出） |
| `semanticEmbedding` | String? | **MobileCLIP 语义 embedding**（512 维 FloatArray 的 Base64，Pass 1 内联产出） |
| `lastTagScanAt` | Long? | 最近一次 TAG 扫描成功时间戳 |
| `lastTagScanPasses` | String? | 已完成的 Pass 阶段 JSON，如 `{"1":ts,"2":ts,"3":ts}` |

#### tags（标签词表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `tagId` | Long (PK) | 自增主键 |
| `name` | String | 标签名称（唯一索引） |
| `category` | String | 类别：scene / animal / object / food / person / other |

#### media_tag_cross_ref（媒体-标签关联表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `mediaId` | Long (PK, FK) | 关联 media_assets.id |
| `tagId` | Long (PK, FK) | 关联 tags.tagId |
| `confidence` | Float? | ML Kit 置信度（TAG 流程中未使用） |

#### persons（人物聚类表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `personId` | Long (PK) | 自增主键，人物唯一标识 |
| `name` | String? | 人物名称（用户可编辑） |
| `coverMediaId` | Long? | 封面媒体 ID |
| `faceCount` | Int | 该人物关联的人脸数量 |
| `createdAt` | Long | 创建时间戳 |
| `updatedAt` | Long | 更新时间戳 |

#### face_embeddings（人脸 Embedding 表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `embeddingId` | Long (PK) | 自增主键 |
| `mediaId` | Long | 关联 media_assets.id |
| `personId` | Long? | 关联 persons.personId（聚类后填充） |
| `embedding` | ByteArray | 512 维 FloatArray 的序列化字节 |
| `createdAt` | Long | 创建时间戳 |

#### tag_scan_tasks（扫描任务队列表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | Long (PK) | 自增主键 |
| `sessionId` | String | 扫描会话 ID |
| `mediaId` | Long | 关联媒体 ID |
| `pass` | TagScanPass | 阶段：FACE_DETECTION / DBSCAN / IMAGE_TAGGING / MOBILE_CLIP_ENCODING（legacy） |
| `tagCategories` | String? | 目标 TAG 类别 JSON 数组，null=全部 |
| `status` | TagScanTaskStatus | 状态：PENDING / RUNNING / PAUSED / COMPLETED / FAILED / CANCELLED |
| `priority` | Int | 优先级（数值越小越优先） |
| `attemptCount` | Int | 已尝试次数 |
| `createdAt` | Long | 创建时间戳 |
| `scheduledAt` | Long? | 计划执行时间（失败重试退避） |
| `startedAt` | Long? | 开始执行时间 |
| `completedAt` | Long? | 完成时间 |
| `errorMessage` | String? | 失败原因 |

### 5.2 JSON 数据格式

#### `labels` 字段格式（Pass 3 产出）

```json
{
  "face": {
    "count": 2,
    "selfie": false,
    "groupPhoto": true,
    "personIds": [1, 3]
  },
  "scene": "户外公园",
  "activity": "野餐",
  "objects": ["毯子", "食物", "篮子"],
  "tags": ["春天", "家庭", "休闲"],
  "qwenSummary": "一家人在公园野餐的温馨场景"
}
```

#### `faceRoiResult` 字段格式（Pass 1 产出）

```json
{
  "hasFace": true,
  "faceCount": 2,
  "isSelfie": false,
  "isGroupPhoto": true
}
```

> **注意**：2026-06-27 优化后，106 点 landmarks 不再存储（RetinaFace bbox 已足够）。

#### `semanticEmbedding` 字段格式

MobileCLIP-S2 生成的 512 维语义 embedding，存储为 **FloatArray 的 Base64 字符串**。

- 模型：MobileCLIP-S2（vision model ~397MB，ONNX Runtime 后端，支持 fp32/fp16）
- 维度：512
- 归一化：L2 归一化
- 用途：自然语言图片搜索（余弦相似度匹配）

#### `mlKitLabels` 字段格式（遗留，原 Pass 5 产出）

> **注意**：ML Kit Image Labeler 打标链路已移除，该字段仅为历史数据兼容保留，不再产生新数据。

ML Kit Image Labeler 输出的英文标签，按置信度过滤后存储为 JSON 数组：

```json
["Outdoor", "Food", "Plant"]
```

#### `lastTagScanPasses` 字段格式

记录各 Pass 的完成时间戳，用于增量扫描去重：

```json
{"1": 1750992000000, "2": 1750992100000, "3": 1750992200000}
```

| Key | 含义 |
|-----|------|
| `"1"` | Pass 1（人脸检测 + MobileCLIP 内联合并）完成时间 |
| `"2"` | Pass 2（DBSCAN 聚类）完成时间 |
| `"3"` | Pass 3（图像标签）完成时间 |
| `"4"` | Pass 4（MobileCLIP 单独重编码，保留兼容）完成时间 |
| `"5"` | 历史遗留：原 Pass 5（ML Kit 英文标签）完成时间；写入源已移除，仅存在于旧数据 |

### 5.3 3-Pass 数据流转

![三阶段数据落库明细](assets/diagrams/tag-pass-db-writes.png)

### 5.4 数据库版本迁移历史

| 版本 | 变更内容 |
|------|----------|
| 2 → 3 | 新增 `tag_scan_tasks` 表；`media_assets` 新增 `lastTagScanAt` / `lastTagScanPasses` |
| 3 → 4 | `media_assets` 新增 `semanticEmbedding`（MobileCLIP 语义向量） |
| 4 → 5 | 空迁移（修复设备上版本已升到 5 的问题） |
| 5 → 6 | `media_assets` 新增 `mlKitLabels`（ML Kit 英文标签 JSON）；`TagScanPass` 新增 `ML_KIT_TAGGING`（该枚举值后续已随 ML Kit 打标链路移除） |
| 6 → 7 | `media_assets` 新增 `mlKitLabelsZh`（ML Kit 中文标签 JSON） |
| 7 → 8 | 性能优化：`media_assets` 新增 `captureDate` / `hasFace` 索引（Room 标准命名） |
| 8 → 9 | 新增 `photo_edit_recipes` 表（非破坏性编辑配方） |
| 9 → 10 | 新增 `media_feedback` 表（搜索结果点赞/点踩反馈） |
| 10 → 11 | 清理 ML Kit 图像标注遗留数据 |
| 11 → 12 | `media_assets` 新增 `labelsEn` / `labelsZh`（英文打标 + 双字段汉化） |
| 12 → 13 | 人物关系图谱（`person_relations`）+ 通用事实记忆库（`memory_facts`） |
| 13 → 14 | `person_relations` 新增 `customLabel` 列（两层关系模型） |
| 14 → 15 | 新增 `chat_image_cache` 表（chat 编辑/优化结果图私有缓存） |
| 15 → 16 | `TagScanPass.QWEN_TAGGING` 重命名为 `IMAGE_TAGGING`（改写历史任务行，避免枚举反序列化崩溃） |
| 16 → 17 | `media_assets` 新增 `city` 列（逆地理编码城市，按城市分组） |
| 17 → 18 | `media_assets` 新增 `faceFocusY`（人脸纵向聚焦点，列表缩略图对齐） |
| 18 → 19 | `media_assets` 新增 `aestheticScore` / `faceQualityScore`（NIMA 美学分 / eDifFIQA 人脸质量分，用于人物封面选择） |

---

## 6. 实现回顾

### 6.1 总体判断

**方向基本合理，但 Pass 3 的生成式标签体系存在「用大炮打蚊子」问题。**

使用端侧多模态生成模型（Qwen 2B 级别）进行相册图像打标，在**隐私合规**和**中文复杂场景理解**上是成立的；但在**效率、标签一致性、成本控制**上不是最优解。当前 3-Pass 架构本身设计良好，但 Pass 3 的生成式标签体系存在多项可优化空间。

> **一句话建议**：保留 Qwen 做「摘要 + 复杂语义 + OCR」，把「标签分类」交给更快、更可控的 CLIP/分类体系；同时把当前扁平词表升级为「受控层级标签体系」。

### 6.2 架构层面的优点

| 方面 | 评价 |
|------|------|
| **3-Pass 分离** | 人脸检测 / Embedding / 聚类 / 图像打标解耦合理，便于独立调度、失败重试、增量更新。 |
| **持久化任务队列** | `TagScanOrchestrator` 将扫描拆分为原子任务，支持暂停 / 恢复 / 取消 / 重试，适合长时间后台 Service。 |
| **OpenCL Guardian** | 带 warmup、超时、黑名单降级，是端侧 GPU 推理的务实做法。 |
| **混合召回设计** | 打标标签 + MobileCLIP 语义向量 + 人脸聚类，搜索侧有丰富信号可用（历史曾含 ML Kit 英文标签，已移除）。 |
| **隐私优先** | 全部本地推理，符合项目 `[PRIVACY]` 红线。 |
| **Category 重新生成** | `TagCategory` 按类别触发不同 Pass，用户可单独重标人脸 / 场景 / 物体等，体验友好。 |

### 6.3 模型选择的核心问题

> **决策（2026-07-26）**：打标 tagger 已收敛为 **Florence-2-base（ONNX INT8，~260MB，默认首选）**，
> 备选 **Qwen3-VL-2B-Instruct-MNN（`qwen3_vl_2b`）**。SmolVLM-500M、LFM2-VL 已下线移除。
> 选择见 `TaggerModelSelector`（首选 `florence2_base` → 未下载回退 `qwen3_vl_2b`）。
> 下文「用 2B 做标签抽取是过度设计」的批评即由此而来——结构化标签抽取改走 Florence-2，
> 不再用 2B 生成模型打标；以下为历史背景，保留作参考。

#### 命名与版本一致性

打标 tagger 模型 key 已统一（见上文决策）：

```kotlin
// TagGenerationScheduler.kt — tagger 模型由 TaggerModelSelector 驱动
// 首选 florence2_base，未下载时回退 qwen3_vl_2b
```

> **注意**：历史代码中存在 `qwen3_5_2b` 模型 key，该 key 对应的端侧**文本** LLM（Qwen3.5-2B 聊天/指令模型）已于 2026-08 移除（`AiAgentMode.LOCAL`、相机本地 Agent 链路全部删除）。本文 Pass 3 的 MNN VLM 打标链路（Qwen3-VL-2B / Florence-2）不受影响——VLM tagger 使用独立的 `qwen3_vl_2b` 模型 key。

#### 用 2B 生成模型做「标签抽取」是过度设计

当前 Qwen 的任务是：看一张图 → 输出固定 JSON（scene / activity / objects / tags / summary）。该任务本质上是**结构化图像分类 / image captioning**，而非自由对话。用 2B 生成模型做这件事的代价：

- **显存 / 模型体积**：INT4 后约 ~1GB，加载慢，不及时卸载会持续占用数 GB 内存。
- **推理延迟**：CPU 2–8s / 张，GPU 约 1s / 张，是打标流程的绝对瓶颈（占 87% 总时间）。
- **输出不稳定**：需要 JSON 提取、容错、规范化等大量后处理。

对比更轻量的方案：

| 方案 | 单张延迟 | 标签一致性 | 适合场景 |
|------|---------|-----------|---------|
| **MobileCLIP / Chinese-CLIP 零 shot** | 5–100ms | 极高 | 从受控词表选标签 |
| **Tiny image captioner（如 LightCap 类）** | <50ms | 中 | 生成 summary |
| **Qwen2.5-VL 2B** | 1–8s | 中 | 复杂场景理解、OCR、summary |

**结论**：Qwen 应专注于其擅长的「复杂中文理解 + 摘要 + OCR 场景」，而不是把所有标签都交给它自由生成。

### 6.4 打标体系的详细问题

#### 标签字段存在冗余和重叠

当前 `QwenTags` 输出 5 个字段：`scene`、`activity`、`objects`、`tags`、`summary`。

问题：
- **`objects` 与 `tags` 高度重叠**：`推车`、`婴儿` 同时出现在两个列表里，搜索侧无法区分权重。
- **人脸相关标签让 Qwen 推断是浪费**：`单人 / 双人 / 多人 / 合影 / 自拍` 完全可以通过 `faceCount` 和镜头方向计算得到，无需 LLM 重复判断。
- **`scene` 和 `activity` 本质上也是标签**：用户搜「公园」或「散步」都应召回，字段拆分对搜索价值有限。
- **缺少层级关系**：例如 `车 → 汽车 → SUV`、`食物 → 蛋糕 → 生日蛋糕`，当前是扁平列表，无法做上位词模糊召回。

#### 受控词表「大而全」但利用率低

`controlled_vocab.json` 约 **700+ 词**，分布在 10 个类别。但 Qwen 每张照片仅输出 5–8 个 `tags`，意味着大量词表词永远不会被命中。

问题：
- 词表维护成本高，新增 / 删减缺乏反馈闭环。
- `objects` 类别包含 `口红`、`粉底`、`眼影` 等细粒度物体，Qwen 在 512px 输入上很难识别。
- 同义词映射覆盖有限且存在不对称：例如 `帅哥` → `男性`，但搜索时用户更可能输入「男」而非「男性」。

#### `TagNormalizer` 的匹配策略有风险

当前归一化流程：精确匹配 → 包含匹配 → 编辑距离 ≤ 1 → 同义词映射。

**编辑距离 ≤ 1 在中文上非常危险**：
- `猫` ↔ `狗`：编辑距离 1，语义完全不同。
- `山` ↔ `出`、`江` ↔ `河`：容易误匹配。
- 对长度 2–3 的中文词尤其敏感。

**包含匹配同样有风险**：
- `沙滩` 包含 `沙`、`滩`，可能错误映射。
- `T恤` 包含 `T` 或 `恤`。

**建议**：中文归一化优先使用**同义词表 + 精确匹配**，慎用编辑距离；如需模糊匹配，使用基于 embedding 的语义相似度（MobileCLIP 文本编码）替代 Levenshtein。

#### JSON 输出解析过于脆弱

```kotlin
private fun extractJson(text: String): String? {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    return if (start != -1 && end > start) {
        text.substring(start, end + 1)
    } else null
}
```

如果模型输出包含 markdown code block 或多个 `{}`，提取会失败。当前仅记录 `nonStandard`，没有重试机制。

**建议**：
- Prompt 中强制要求纯 JSON 输出，禁止 markdown code block。
- 增加 JSON 修复 / 重试：提取失败后尝试截断到第一个合法 JSON，或让模型重新输出。
- 增加 schema 校验（字段类型、数组长度等）。

#### 缺少质量评估和反馈闭环

当前没有：
- 标签置信度分数。
- 标签覆盖率统计（哪些词表词从未被命中）。
- 人工 / 半自动反馈修正机制。
- 端到端搜索召回率测试。

这导致标签质量不可知，只能凭手感调整 prompt。

### 6.5 优先级建议

#### P0：立即实施（低风险高收益）

1. **~~统一 `QWEN_MAX_TOKENS` 为 64–128~~（已失效）**：当前值已为 `QWEN_MAX_TOKENS = 256`（`TagGenerationPipeline.kt:79`），注释明确「输出 JSON 需要 256 tokens 才能完整闭合」，进一步下调会截断 JSON，该优化项不再适用。
2. **修复中文归一化风险**：移除或严格限制 `TagNormalizer` 的编辑距离匹配，改用同义词表 + 语义相似度。
3. **统一模型命名**：在代码和文档中明确模型真实版本。
4. **改进 JSON 提取**：支持 markdown code block 包裹、schema 校验、失败重试。

#### P1：近期实施（架构改进）

5. **把「标签分类」从 Qwen 迁移到 MobileCLIP / Chinese-CLIP 零 shot**：
   - 用受控词表作为候选标签集合。
   - 图像编码与每个标签文本编码计算相似度，取 Top-K。
   - 延迟从秒级降到毫秒级，一致性大幅提高。
   - Qwen 仅负责 `summary`、OCR 场景、复杂活动推断。
6. **合并 `objects` 进 `tags`**，或建立层级标签关系；移除 Qwen 对 `单人 / 双人 / 合影` 等可由 `faceCount` 计算的标签要求。
7. **加入照片去重**：Pass 1 计算 dHash，Pass 3 优先复用已有标签。

#### P2：中长期

8. **建立标签质量评估 pipeline**：抽样评估召回率，统计词表命中率。
9. **DBSCAN 加速**：人脸 embedding 超过阈值后引入近似最近邻。
10. **标签层级与推理**：建立「上位词 → 下位词」映射，使搜索「食物」能召回「蛋糕」。

### 6.6 实现评分

| 维度 | 评分 | 说明 |
|------|------|------|
| **隐私合规** | ⭐⭐⭐⭐⭐ | 全端侧，符合 `[PRIVACY]` 红线。 |
| **架构设计** | ⭐⭐⭐⭐☆ | 3-Pass + 队列 + 降级设计合理。 |
| **标签体系** | ⭐⭐⭐☆☆ | 字段冗余、词表过大、归一化有风险。 |
| **模型选择** | ⭐⭐⭐☆☆ | Qwen 做纯标签分类是过度设计（已收敛为 Florence-2 默认 + Qwen3-VL-2B 备选）。 |
| **效率** | ⭐⭐☆☆☆ | Pass 3 是瓶颈，去重 / 复用 / GPU 加速都有优化空间。 |
| **可维护性** | ⭐⭐⭐☆☆ | 文档较全，但代码与文档存在不一致。 |

---

## 7. 性能分析

> **历史基线说明**：本章写于 Qwen 2B 为主力 tagger 的时期，耗时数字为当时的历史测量/估算，仅供方法参考。当前默认 tagger 已切换为 Florence-2（ORT 独立路径），Qwen3-VL-2B 为备选；文中常量已按现状校正（Pass 3 冷却 `DEFAULT_PASS3_COOLDOWN_MS = 800L`、`QWEN_MAX_TOKENS = 256`）。

### 7.1 当前性能基线（9000 张）

```
Pass 1 (人脸 ROI + 106关键点 + Glint360K R100 Embedding)  →  Pass 2 (DBSCAN 全局聚类)  →  Pass 3 (图像打标)
  ~10-50ms + ~20-80ms + ~30-60ms                                 ~2-5s (一次)                  ~2-8s/张
```

执行模型：**单线程 Foreground Service**，专用单线程调度器 `singleThreadDispatcher` + 3000ms/500ms 两级节流。

### 7.2 各阶段耗时估算

| 阶段 | 单张耗时 | 9000 张总耗时 | 占比 |
|------|---------|-------------|------|
| **Pass 1** 人脸检测 + Embedding | ~170ms (推理) + 500ms (节流) = ~670ms | **~100 min** | 13% |
| **Pass 2** DBSCAN 全局聚类 | 一次执行 ~2-5s | **~3s** | <1% |
| **Pass 3** 图像打标 | ~4000ms (推理) + 500ms (节流) = ~4500ms | **~11.25 hrs** | 87% |
| **合计** | | **~13 hrs** | |

### 7.3 三大核心瓶颈

#### 瓶颈 1：Pass 3 恒速冷却 `DEFAULT_PASS3_COOLDOWN_MS = 800L`

当前实现中，Pass 1 的固定节流**已移除**（仅 `guardCheck()` 返回 PAUSE 时按 `getThrottleMs()` 退避，默认 1000ms）；Pass 3 每张照片处理完毕后仍执行 `delay(getPass3CooldownMs())`：

```kotlin
// TagGenerationScheduler.kt:111
private const val DEFAULT_PASS3_COOLDOWN_MS = 800L
```

Pass 3 实际推理约数秒，800ms 冷却用于散热窗口与 FG Service 存活（见 §7.4），占比远小于早期 500ms 硬节流对 Pass 1 的拖累（当时 Pass 1 推理仅 ~70-150ms，被 500ms delay 拖成 ~670ms）。

#### 瓶颈 2：Pass 3 Qwen 推理是物理上限

Qwen3-VL-2B 多模态推理使用 `engineMutex` 串行保护，每张约 2-8s（CPU 模式）。9000 张的理论下限 (2s/张) 即 **5 小时**。

#### 瓶颈 3：Bitmap 双重解码

Pass 1 以 640px 加载，Pass 3 又加载 512px。两次 `ContentResolver.openInputStream()` + `BitmapFactory.decodeStream()`，每张浪费 20-50ms。

### 7.4 节流机制详解

#### 两层节流架构

![电池/热守卫与节流](assets/diagrams/tag-guard-throttle.png)

#### 恒速冷却 `DEFAULT_PASS3_COOLDOWN_MS = 800L` 的作用

| 作用 | 说明 |
|------|------|
| **热预防** | 即使"允许"状态，800ms 间隙让 SoC 有散热窗口，避免快速升温到 MODERATE/SEVERE |
| **降低平均功耗** | 将"连续满载"变为"爆发+休息"模式，降低时间平均功率 (TAP) |
| **Foreground Service 存活** | Android 对持续高功耗的 FG Service 会限频甚至杀进程，800ms break 降低被回收概率 |
| **减轻 IO 竞争** | 每次 delay 让 Room WAL checkpoint、ContentResolver 缓存有机会完成 |
| **进度更新节流** | 避免每张照片都触发 StateFlow 更新→UI 重组（每 800ms 一次已足够） |

#### 移除冷却的潜在害处

**热失控 → 性能反而下降**：移除 800ms 间隙后，CPU/GPU 持续满负荷运行，SoC 温度持续上升 → 触发强制降频，整体速度反而降低 50%+。

**FG Service 被系统回收**：Android 12+ 的 Battery Optimization 对前台 Service 有严格的功耗监测。连续 2 分钟 CPU 占用 > 50% 的 FG Service 会被标记为异常，系统可能杀死 Service、强制降低进程优先级或限制后台运行时间。

**数据库写入压力**：Pass 1 每次循环写入 `face_embeddings` 和更新 `faceRoiResult`，Room 的 WAL 机制在连续写入时会有 checkpoint 开销。无间隙的连续写入会导致 WAL 文件快速增长、偶发写入延迟尖峰。

### 7.5 优化方案

#### P0：立即实施（低风险，高收益）

**1. ~~移除 Pass 1 的 THROTTLE_MS~~（已完成）**

- Pass 1 的固定节流已移除；当前仅 `guardCheck()` 返回 PAUSE 时按 `getThrottleMs()`（默认 1000ms）退避。

**2. ~~减少 Qwen maxTokens 从 128 → 64~~（已失效）**

- 当前 `QWEN_MAX_TOKENS = 256`（`TagGenerationPipeline.kt:79`），注释明确「输出 JSON 需要 256 tokens 才能完整闭合」。下调会截断 JSON、破坏解析，该优化项不再适用。
- Pass 3 提速的现实路径：默认 tagger 已切换为 Florence-2（ORT，非生成式 JSON 解码），Qwen3-VL-2B 仅作备选；Qwen 路径的提速依赖 OpenCL GPU 加速（见下）而非压缩输出长度。

**3. 降低 Pass 3 的冷却时长（当前 `DEFAULT_PASS3_COOLDOWN_MS = 800L`）**

- **改动**：`getPass3CooldownMs()` 由 800ms 降到 100–300ms，或仅在 `guardCheck()` 返回 PAUSE 时才增加延迟。
- **收益**：按 9000 张估算，800→300ms 可省约 75 分钟。
- **风险**：低。数百 ms 间隙足够让 SoC 散热和 DB checkpoint 完成。

#### P1：中等投入

**4. 启用 OpenCL GPU 加速**

- **改动**：`TagGenerationScheduler.ensureModelLoaded()` 中传入 `useOpencl=true`。
- **收益**：Qwen 推理从 ~2.5s → ~1s，Pass 3 从 **7 小时 → 3.5 小时**（+3.5 hrs）。
- **风险**：部分设备不支持 OpenCL；OpenCL kernel 首次编译耗时较多；GPU 推理功耗更高。

**5. 照片去重跳过 Pass 3**

- **方案**：
  1. Pass 1 时计算 dHash（差异哈希，64-bit，~1ms/张）
  2. 存储到 `media_assets.perceptual_hash` 字段
  3. Pass 3 前查询：`SELECT labels FROM media_assets WHERE perceptual_hash = ? AND labels IS NOT NULL LIMIT 1`
  4. 若命中则复用标签，跳过 Qwen 推理
- **收益**：假设 40% 重复，Pass 3 从 **7 小时 → 4.2 小时**（+2.8 hrs）。

#### P2：长远优化

**6. 合并 Bitmap 解码**：Pass 1 的 640px Bitmap 缩放至 512px 供 Pass 3 复用，避免二次 ContentResolver 调用。收益 ~5 分钟（边际收益）。

**7. 批量 DB 写入**：Pass 1 现在每条 embedding 单独 insert，可以批量 50 条/事务。收益 Pass 1 缩短 ~2-3 分钟。

### 7.6 优化效果对比

> 下表为历史基线（Qwen 主打标时期）的估算。其中「maxTokens=64」行已不适用（当前 `QWEN_MAX_TOKENS = 256` 为 JSON 闭合所需），「移除 P1 节流」已落地。

| 策略 | Pass 1 | Pass 3 | **总计** | 累计降幅 |
|------|--------|--------|---------|---------|
| **历史基线** | ~100 min | ~11.25 hrs | **~13 hrs** | — |
| + 移除 P1 节流（已落地） | **~25 min** | ~11.25 hrs | **~11.7 hrs** | 10% |
| ~~+ maxTokens=64~~（已失效，当前 256 为 JSON 闭合所需） | — | — | — | — |
| + P3 冷却 800→100ms | ~25 min | **~10.5 hrs** | **~10.9 hrs** | 16% |
| + OpenCL GPU | ~25 min | **~3.3 hrs** | **~3.7 hrs** | 72% |
| + 照片去重 40% | ~27 min | **~2 hrs** | **~2.5 hrs** | 81% |
| **最优组合** | **~25 min** | **~1.5 hrs** | **~2 hrs** | 85% |

### 7.7 Pass 2 DBSCAN 性能评估

当前 DBSCAN 是朴素 O(n²) 实现：

```kotlin
for (i in 0 until n)          // n = 所有 face embedding 数量
    for (j in 0 until n)      // O(n²) 全量比较
        cosineDistance(a, b)  // 512 维浮点运算
```

即便 3000 张有人脸（~3000 embeddings），复杂度为 **3000²/2 × 512 ≈ 2.3B FLOPs**，手机 CPU 约 1-2 GFLOPS → **~2s**。

结论：**非瓶颈，不需要优化**。但如果后续 embedding 数量 > 50000，建议引入 KD-tree / Ball-tree 或近似最近邻。

### 7.8 最终结论

1. **TAG 生成瓶颈的本质**：不是代码效率问题，而是 Qwen 模型 2-8s/张的物理推理延迟。
2. **最大单点收益（历史）**：Pass 1 固定节流已移除；`maxTokens=64` 方案已失效（当前 `QWEN_MAX_TOKENS = 256` 为 JSON 闭合所需），当前 Pass 3 提速主要依赖 Florence-2 默认 tagger 与 OpenCL GPU 加速。
3. **节流可以移除但不能完全放弃**：Pass 1 安全，Pass 3 建议保留 100ms 最小间隙防止热积累。
4. **终极方案**：去重 + GPU 加速 + 智能节流，可将 13 小时压缩到 **2-3 小时**。

---

## 8. 国际化（I18N）

### 8.1 背景与约束

当前 PoLang 的 TAG 体系存在以下与国际化相关的痛点：

| 痛点 | 根因 | 影响 |
|------|------|------|
| 英文界面搜索无结果 | `media_assets.labels` 中存储的是中文 JSON（如 `["猫","户外"]`），`LIKE '%cat%'` 无法命中 | 英文用户无法通过自然语言检索照片 |
| TAG 重生成成本高 | 三阶段生成涉及 InsightFace 人脸检测、Glint360K R100 Embedding、DBSCAN 聚类、Qwen 视觉推理，全量重跑耗时耗电 | 不能因为切换语言就要求用户重新扫描所有照片 |
| Prompt 与提示词中文硬编码 | `TagGenerationPipeline.stage3SystemPrompt`、`AutoTagCapability` 描述、`MediaSearchEngine` 的 LLM prompt、搜索停用词/城市词均为中文 | Agent 在英文界面仍用中文与用户交互 |
| UI 直接展示中文 TAG | `MediaPager` 等详情页把 `labels` 原样显示 | 英文用户看到 “男/女/户外” 体验割裂 |

红线约束：

- **[PRIVACY]** 敏感数据必须本地处理。TAG 本身可能包含人物、地点等隐私信息，因此翻译/映射必须走本地词表，不能调用云端翻译 API。
- **[I18N]** 禁止硬编码，五语同步。
- **[PERF]** 不能因语言切换导致明显搜索性能退化。

### 8.2 设计原则

1. **Canonical 不变**：已生成的中文 TAG 作为事实来源，切换语言不触发重写。
2. **薄本地化层**：语言差异在查询和展示时注入，存储层保持中文为主。
3. **隐私优先**：翻译/同义词扩展走本地双语词表。
4. **渐进式**：先让英文能搜、能看；再让新 TAG 按语言生成；最后重构为语言无关概念模型。

### 8.3 总体架构

![TAG 双语检索架构](assets/diagrams/tag-bilingual-search.png)

### 8.4 双语词表与翻译器

新增 `domain/tag/i18n/TagTranslator.kt`：

```kotlin
class TagTranslator(private val vocab: BilingualVocab) {

    /** 把数据库里的中文标签翻译成当前界面语言（用于展示） */
    fun display(chineseTag: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.ENGLISH -> vocab.zhToEn[chineseTag] ?: chineseTag
        else -> chineseTag
    }

    /**
     * 把用户输入的查询词扩展为数据库中可能存在的形式。
     * 例如英文 "cat" -> ["cat", "猫"]，这样既能命中未来英文标签，也能命中现有中文标签。
     */
    fun expandForSearch(query: String, uiLang: AppLanguage): Set<String> {
        val result = linkedSetOf(query)
        when (uiLang) {
            AppLanguage.ENGLISH -> {
                vocab.enToZh[query.lowercase()]?.let { result += it }
                vocab.enSynonyms[query.lowercase()]?.let { syn ->
                    vocab.enToZh[syn]?.let { result += it }
                }
            }
            else -> {
                vocab.zhToEn[query]?.let { result += it }
            }
        }
        return result
    }
}
```

新增 asset `androidApp/src/main/assets/tag_translations.json`：

```json
{
  "zh_to_en": {
    "猫": "cat",
    "狗": "dog",
    "男性": "male",
    "女性": "female",
    "户外": "outdoor",
    "室内": "indoor"
  },
  "en_synonyms": {
    "kitten": "cat",
    "puppy": "dog",
    "guy": "male"
  }
}
```

> 词表从 `controlled_vocab.json` 的 10 个类别导出，核心 TAG 约 600 个。可通过 `scripts/generate_tag_translations.py` 批量生成初稿，再人工校对高频/敏感词。

### 8.5 搜索层改造

在 `MediaSearchEngine.executeFilter` 中，对每个 keyword 先通过 `TagTranslator.expandForSearch` 扩展候选词集合，再分别查询：

```kotlin
val uiLang = settingsRepository.getAppLanguageBlocking()
for (keyword in filter.keywords) {
    val candidates = tagTranslator.expandForSearch(keyword, uiLang)
    for (candidate in candidates) {
        tagDao?.searchByExactTag(candidate)?.let { ... }
        mediaDao.searchByLabel(candidate).let { ... }
    }
}
```

若高频搜索性能吃紧，可在 `MediaDao` 增加多关键词 OR 查询，减少 SQL 往返。

### 8.6 UI 展示改造

在 `features/gallery/components/MediaPager.kt` 等展示处，对 `scene`、`activity`、`objects`、`tags`、`qwenSummary` 经过 `TagTranslator.display(...)` 映射后再显示。未命中词表时回退原中文。

### 8.7 Prompt 本地化

抽象 `TagPromptProvider`：

```kotlin
interface TagPromptProvider {
    fun systemPrompt(lang: AppLanguage): String
    fun userPrompt(faceContext: FaceRoiPersist?, lang: AppLanguage): String
}
```

- 中文：保持现有 prompt。
- 英文：要求模型输出英文标签，示例使用英文受控词表。

`TagGenerationPipeline` 注入 `UserSettingsRepository`，根据当前 `AppLanguage` 选择 prompt。这样：

- 中文用户继续生成中文 TAG。
- 英文用户的新照片生成英文 TAG。
- 老照片中文 TAG 由 `TagTranslator` 兜底兼容。

### 8.8 Phase 1.5：搜索翻译链路增强

#### 问题背景

Phase 0/1 解决了**英文用户搜索中文标签**的问题，但存在以下缺口：

| 缺口 | 根因 | 影响 |
|------|------|------|
| 中文搜索无法命中 ML Kit 英文标签 | `mlKitLabels` 列存英文（如 `["Cat","Outdoor"]`），中文查询 `LIKE '%猫%'` 无法命中 | 中文用户搜索丢召回 |
| 词表覆盖不全 | `tag_translations.json` 仅 534 条 zh→en，未命中时无回退 | 长尾中文词搜索丢召回 |
| 中文同义词无法扩展 | `ControlledVocab.reverseSynonyms` 仅在标签生成时使用，搜索链路未接入 | "猫咪"搜不到"猫" |
| 显式约束管道跳过翻译 | `ExplicitFirstSearchPipeline` 无 TagTranslator | "去年在室内猫" 内容词未翻译 |

#### ML Kit 标签中文翻译（静态映射，已失效）

> **注意**：ML Kit Image Labeling 打标链路（`MlKitTagExtractor`）已移除，`mlKitLabels` / `mlKitLabelsZh` 不再产生新数据，仅为历史数据兼容保留。以下流程仅对存量历史数据有效。

ML Kit Image Labeling API 使用固定的 ~400 个英文标签。创建静态中英文映射表 `assets/mlkit_labels_zh.json`，在索引时同时存储中文翻译。

**数据流**：

![ML Kit 标签中英双存](assets/diagrams/mlkit-zh-translate.png)

**DB Migration 6→7：** 新增 `media_assets.mlKitLabelsZh TEXT` 列。

**搜索时：** 所有搜索路径同时查询 `mlKitLabels` 和 `mlKitLabelsZh`，确保中英文查询都能命中。

#### TagTranslator 翻译分层策略

![中文查询扩展四级回退](assets/diagrams/zh-query-expansion.png)

> **Tokenizer 说明**：OPUS-MT 的编解码依赖 `:engines:sentencepiece` 模块加载的 `source.spm` / `target.spm`，`tokenizer.json` 仅用于 Hugging Face token ID 与 SentencePiece piece 之间的映射。详见 `ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md`。

#### ChineseQueryTranslator CLIP 扩展增强

![CLIP 查询扩展](assets/diagrams/clip-query-expansion.png)

### 8.9 实施路线图

| 阶段 | 目标 | 改动范围 | 是否重生成 | 状态 |
|------|------|----------|------------|------|
| **Phase 0** | 英文能搜、能看 | `TagTranslator` + `tag_translations.json`；改造 `MediaSearchEngine`、`QueryBuilder`、`MediaPager` | 否 | ✅ 已完成 |
| **Phase 1** | 新照片按语言生成 | `TagPromptProvider` / `DefaultTagPromptProvider`；按 `AppLanguage` 切换 prompt | 仅新照片 | ✅ 已完成 |
| **Phase 1.5** | 中文搜索召回 ML Kit 英文标签 + 中文同义词扩展 | `mlKitLabelsZh` 列、`MlKitLabelTranslator`、`TagTranslator` OPUS-MT 回退 + ControlledVocab 同义词 | 全量重扫 ML Kit（仅新增列） | ✅ 已完成 (2026-07-01) |
| **Phase 2** | 结构化多语言 TAG | `TagConcept`/`TagConceptTranslation` 表；迁移 `TagEntity`/`MediaTagCrossRef` | 否（仅迁移） | 📋 待规划 |
| **Phase 3** | 搜索体验升级 | 接入 FTS5 / 前缀索引；支持英文同义词、拼写容错 | 否 | 📋 待规划 |

### 8.10 长期架构：语言无关 TAG 概念模型

若后续支持更多语言，中文 canonical 将不再是最佳选择。建议第二阶段引入：

```kotlin
@Entity(tableName = "tag_concepts")
data class TagConcept(
    @PrimaryKey val conceptId: String, // 稳定 key，如 "animal.cat"
    val category: String
)

@Entity(tableName = "tag_concept_translations", primaryKeys = ["conceptId", "locale"])
data class TagConceptTranslation(
    val conceptId: String,
    val locale: String,          // "en", "zh", ...
    val displayName: String,
    val synonyms: String         // JSON 数组
)
```

- `TagEntity` 改为 `tag_concepts` 的 localized view。
- `MediaTagCrossRef` 关联 `conceptId`。
- 生成时先把 Qwen 输出归一化到 concept，再按当前 locale 取 displayName。
- 搜索时把用户输入映射到 concept，再 join cross ref。

该改动需要一次 DB migration 和历史数据迁移，但新增语言时无需重跑视觉模型。

### 8.11 验收标准

- [x] 英文界面下搜索 "cat" 能命中标签为 "猫" 的照片（通过 `TagTranslator.expandForSearch`）。
- [x] 英文界面下照片详情页的标签展示为英文（通过 `TagTranslator.display`）。
- [x] 切换为中文后，搜索/展示恢复为中文。
- [x] 不触发全量 TAG 重新生成即可生效。
- [x] Prompt 与 Agent Capability 描述支持英文。
- [x] 中文搜索能命中 ML Kit 英文标签（通过 `mlKitLabelsZh` 列 + `MlKitLabelTranslator`；写入源已移除，仅对历史存量数据有效）。
- [x] 中文同义词搜索扩展（"美女"→"女性"，"猫咪"→"猫"，通过 `ControlledVocab.reverseSynonyms`）。
- [x] 词表未命中时 OPUS-MT NMT 模型翻译回退（通过 `TagTranslator` MT fallback）。
- [x] 显式约束管道支持跨语言搜索（通过 `ExplicitFirstSearchPipeline` 注入 `TagTranslator`）。
- [x] CLIP 语义搜索使用 ControlledVocab 动态扩展候选英文短语。

---

## 9. 风险与 Mitigation

| 风险 | 影响 | Mitigation |
|------|------|------------|
| Qwen 推理耗时过长 | 全量扫描慢 | 后台 Service + 可暂停/取消 + 批次冷却 |
| OpenCL 兼容性问题 | 部分设备挂起 | `OpenClGuardian` warmup + 超时降级 CPU + DataStore 黑名单 |
| 人脸聚类误判 | 同一人分成多簇 | 支持用户手动合并/重命名；DBSCAN 参数调优 |
| 标签质量不可靠 | 搜索召回差 | 受控词表规范化 + 只生成场景级粗粒度标签 + 用户可触发重生成 |
| 电量消耗 | 后台扫描费电 | 仅充电+Wi-Fi 时自动全量扫描；运行时电池/热状态守卫 |
| ML Kit 英文标签与中文 Query 语言不一致（历史遗留，已停止产生新数据） | 中文搜不到英文标签 | `QuerySegmenter` / LLM 语义解析层负责跨语言桥接；`mlKitLabelsZh` 静态翻译兜底（仅存量历史数据） |
| 编辑距离中文误匹配 | 标签归一化错误 | 优先同义词表 + 精确匹配；用 MobileCLIP 语义相似度替代 Levenshtein |

---

## 10. 关键接口定义

### 10.1 TagGenerationScheduler（简化）

```kotlin
class TagGenerationScheduler(
    context: Context,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    guard: suspend () -> GuardResult = { GuardResult.ALLOW }
) {
    val isScanning: StateFlow<Boolean>
    val progress: StateFlow<TagScanProgress?>
    val lastScanMessage: StateFlow<String?>

    suspend fun processSingle(uri: String, mediaId: Long)
    suspend fun ensureModelLoaded(): Boolean

    enum class GuardResult { ALLOW, PAUSE, ABORT }
}
```

> 旧版 `scanAll()` / `scanIncremental()` / `scanPass1/2/3()` 已标记 `@Deprecated`，请使用 `TagScanOrchestrator` 替代。

### 10.2 TagScanOrchestrator（简化）

```kotlin
class TagScanOrchestrator(
    context: Context,
    scheduler: TagGenerationScheduler
) {
    val progress: StateFlow<TagScanSessionProgress?>

    suspend fun scheduleAutoScan(policy: ScanQueuePolicy = ScanQueuePolicy()): String
    suspend fun scheduleRegenerate(mediaIds, categories, mode, policy): String
    suspend fun scheduleRegenerateByQuery(query, categories, mode): String
    suspend fun schedulePass(pass, query, mode, policy): String
    fun pause()
    fun resume()
    fun cancel()
}
```

---

## 11. 后续迭代方向

| 方向 | 说明 |
|------|------|
| 标签质量提升 | 优化 Qwen Prompt、扩充 `ControlledVocab`、引入用户反馈修正 |
| 人物簇管理 | UI 支持合并、拆分、忽略误检簇 |
| ML Kit 跨语言（已失效） | ML Kit 打标链路已移除，该方向随之下线 |
| 语义召回增强 | 尝试更大 MobileCLIP 变体或端侧多模态模型 |
| 自动化测试 | 增加端到端 Tag 生成回归测试与性能基线 |
| 标签分类迁移 | 将「标签分类」从 Qwen 迁移到 MobileCLIP / Chinese-CLIP 零 shot |
| 层级标签体系 | 建立「上位词 → 下位词」映射，提升搜索召回 |
