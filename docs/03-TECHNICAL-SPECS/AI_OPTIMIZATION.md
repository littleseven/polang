# AI 一键图片优化

> **文档类型**：产品 + 技术方案 + 参数标准  
> **针对能力**：AI 一键优化（AI One-Click Image Optimization）  
> **最后更新**：2026-08-03  
> **维护者**：项目开发者  
>
> **历史合并说明**：本文档由 `AI_ONE_CLICK_OPTIMIZATION_PROPOSAL.md` 与 `AI_OPTIMIZE_PARAMETER_STANDARD.md` 合并而成。参数预设以 `AI_OPTIMIZE_PARAMETER_STANDARD.md` 为准，已合并重复内容。

---

## 1. 核心结论

AI 一键优化是新路线下最值得投入的 P0 能力之一：

- **用户价值清晰**："帮我让这张照片更好看"是相册/编辑场景的高频、低学习成本诉求
- **技术基础已具备**：大美丽美颜管线、`EditRecipe` 非破坏性编辑、远程 OpenAI 协议推理均已落地（~~ML Kit 图像识别~~、~~本地 Qwen3.5-2B 多模态理解~~两项基础已移除：ML Kit 打标链路删除、端侧文本 LLM 下线）
- **可行性高**：优先做「本地规则 + 远程增强」的混合方案，MVP 可在 2-3 周内验证核心体验
- **与新产品路线契合**：入口自然（相册查看器/编辑器），不依赖聊天首页假设，也不强依赖 IM 远程

**推荐策略**：
- **MVP（Phase 1）**：本地快速优化为主，覆盖 80% 常见场景，端到端 < 1s
- **Phase 2**：引入远程视觉模型做「智能推荐」，处理复杂/模糊场景，首次使用需用户授权
- **Phase 3**：对话式微调、批量优化、个性化学习

**不做的事情**：
- 不做「AI 换脸/重绘」等生成式修改（超出美颜/滤镜/调节范围）
- 不做「自动保存覆盖原图」（必须是非破坏性编辑）
- 不做「无授权云端处理」（遵循新隐私红线）

---

## 2. 产品定义

### 2.1 一句话描述

用户一键触发，系统自动识别照片场景并应用最优美颜 + 滤镜 + 调节参数，生成更好看的新照片。

### 2.2 解决什么问题

| 用户痛点 | 当前做法 | AI 一键优化后 |
|----------|----------|---------------|
| 不知道参数怎么调 | 手动滑块试错 | 一键给出推荐，可在此基础上微调 |
| 修图步骤繁琐 | 打开编辑 → 调美颜 → 调滤镜 → 保存 | 一键完成，直接进入对比/保存 |
| 不同场景参数差异大 | 凭感觉调 | 基于场景（人像/风景/美食/文档）自动匹配 |
| 批量修图累 | 一张张手动调 | 选中多图后批量应用同一套优化逻辑 |

### 2.3 入口与交互流程

| 入口 | 优先级 | 说明 |
|------|--------|------|
| **媒体查看器工具栏** | P0 | 查看单图时顶部工具栏「AI 优化」按钮，最自然的一键入口 |
| **编辑器内「AI 一键优化」** | P0 | 编辑页顶部/BeautySelector 面板中的「AI 优化」按钮 |
| **相册多选批量操作** | P1 | 长按多选后底部操作栏「AI 优化」 |
| **AI 对话命令** | P1 | "帮我优化这张照片"，结果以图片消息返回 |
| **IM 远程控制** | P2 | 实验线，与 IM 远程控制同步推进 |

### 2.4 单图优化流程

![AI 优化用户流](assets/diagrams/ai-optimize-ux-flow.png)

---

## 3. 技术方案

### 3.1 总体架构：本地优先 + 云端增强

![AI 优化双引擎](assets/diagrams/ai-optimize-dual-engine.png)

> **2026-08-06 更新**：Fast 路径现已接入抽卡闭环（best-of-N + NIMA 评分守卫），见 §11.5。

### 3.2 Fast 路径（本地，默认）

**为什么优先本地**：
- 符合新隐私红线「敏感数据优先本地」
- 速度最快，用户体验最好
- 不依赖网络，离线可用
- 80% 常见场景可用规则覆盖

**场景识别输入**：

> **实现现状（2026-08）**：Fast 路径当前**不做场景分析**——`AiOptimizeUseCase.fastOptimize()` 恒取 `Scene.GENERAL` 预设；`analyzer/` 下仅有 `Scene` 枚举，无 SceneAnalyzer 实现。场景识别实际发生在 Smart 路径（远程模型返回 `preset.scene`）。下表为原设计信号源，其中 ML Kit 两项已不可用。编辑器一键优化自 2026-08-06 起改走抽卡闭环（`optimizeWithGacha()`，见 §11.5），NIMA 模型未下载时退回此固定预设路径。

| 信号 | 来源 | 用途 |
|------|------|------|
| 人脸数量/位置/大小 | MediaPipe / MNN Face Detection（~~ML Kit Face Detection~~ 已移除） | 判断人像/自拍/合影 |
| 图像标签 Top-K | ~~ML Kit Image Labeling~~（已移除；可改用 Florence-2 打标结果或其他本地信号） | 判断风景/食物/文档/动物/建筑等 |
| EXIF 信息 | MetadataExtractor | 判断白天/夜晚/逆光、焦距 |
| 直方图/亮度统计 | GPU 管线采样 | 判断过曝/欠曝/低对比度 |
| 色彩分布 | 简单色彩直方图 | 判断偏暖/偏冷/饱和度 |

**规则引擎示例**：

```kotlin
when {
    faceCount >= 2 && faceRatio > 0.1 -> "group_portrait"
    faceCount == 1 && faceRatio > 0.3 -> "selfie"
    faceCount == 1 && faceRatio < 0.3 -> "portrait"
    labels.containsAny("Food", "Meal", "Dish") -> "food"
    labels.containsAny("Sky", "Mountain", "Sea", "Tree") -> "landscape"
    labels.containsAny("Document", "Text") -> "document"
    brightness < 40 -> "low_light"
    else -> "general"
}
```

### 3.3 Smart 路径（远程，增强）

**触发条件**：
- 用户主动点击「换一换 / 智能推荐」
- Fast 路径置信度低于阈值（如 < 0.6）
- 用户设置了「智能推荐优先」

**实现方式**：

1. **图像编码**：将原图缩放至 512px 长边，JPEG 质量 80，转 base64
2. **模型选择**：通过 OpenAI 兼容 API 调用视觉模型
   - 推荐：GPT-4o-mini（成本可控）或 Qwen-VL-Max
   - 避免依赖 DeepSeek（当前公开版本无 vision 能力）
3. **Prompt 设计**：

```
你是一位手机修图专家。请分析这张图片，并返回 JSON 推荐修图参数。

输出格式：
{
  "scene": "场景英文名",
  "scene_label_zh": "中文场景名",
  "confidence": 0.0-1.0,
  "recipe": {
    "beauty": {"smooth":0-100, "whiten":0-100, "slimFace":-50~50, "bigEye":0-100, "lipColor":0-100, "blush":0-100, "enabled":true|false},
    "filter": "none|film_gold|vivid|natural|warm|cool|leica_classic|...",
    "adjustment": {"brightness":-50~50, "contrast":-50~50, "saturation":-50~50, "warmth":-50~50}
  },
  "explanation": "一句话说明做了什么优化"
}
```

4. **隐私合规**：
   - 首次调用前弹窗告知「将上传压缩图片至云端模型，是否允许？」
   - 设置中提供「允许云端 AI 优化」开关
   - 不在云端存储用户图片

### 3.4 ~~本地 Qwen3.5-2B 多模态作为离线 fallback~~（历史方案，已失效）

> **2026-08 变更**：端侧文本 LLM（Qwen3.5-2B）已移除，本节的离线 fallback 方案不再可用。
> 且原文描述有误——`TagGenerationPipeline` Pass 3 实际使用的是 Qwen3-**VL**-2B（MNN VLM 打标，现为备选 tagger，默认 Florence-2），并非 Qwen3.5-2B 文本模型。
> 当前无网络时的实际降级行为：Fast 路径直接应用 `Scene.GENERAL` 本地预设（见 §3.2 实现现状注）。以下内容保留作历史参考。

项目已有的 `TagGenerationPipeline` Pass 3 使用 Qwen3.5-2B 做图像理解。可探索复用该路径：

- 输入：压缩图
- 输出：场景标签 + 简短描述
- 映射到本地预设

**当前限制**：
- 2B 模型输出 JSON 稳定性有限
- 推理速度约 1-3s（取决于设备）
- 功耗/发热较高

**建议**：不作为默认路径，仅作为无网络且用户未禁用云端时的降级提示："当前无法使用智能推荐，已应用本地优化"。

---

## 4. 参数标准与预设规范

### 4.1 参数体系选择

**不存在强制统一的二进制标准**，但移动端修图领域有两套事实上的「用户心智」：

| 体系 | 代表产品 | 特点 | 适用参数 |
|------|----------|------|----------|
| **Apple Photos 体系** | iOS 相册、VSCO、Lightroom Mobile | 以 `-100 ~ +100` 或 `0 ~ 100` 为中心对称滑杆，0 为「无效果」 | 曝光、亮度、对比度、饱和度、色温、色调 |
| **国产美颜体系** | 小米相机、美图秀秀、轻颜相机 | 以 `0 ~ 100` 表示强度，50 或 0 为「自然/关闭」 | 磨皮、美白、瘦脸、大眼、唇色、腮红 |

**PoLang 的选择**：
- **美颜参数**：对齐国产美颜体系，`0 ~ 100` 为强度，`0` 为关闭；瘦脸 `-50 ~ +50`，负值丰脸。
- **调色参数**：尽量对齐 Apple Photos 用户心智，但保留当前内部映射：
  - `brightness / exposure / tint`：`-100 ~ +100`，`0` 为原图。
  - `contrast / saturation`：`0 ~ 200`，`50/100` 为原图（与 Apple 的 `-100 ~ +100` 等价，只是零点偏移）。
  - `temperature`：`2000K ~ 8000K`，`5000K` 为原图（与 Apple Warmth 方向一致，但使用 Kelvin 标度）。

> 这样选择的原因是：底层大美丽 Shader 已使用 `0~4` 对比度、`0~2` 饱和度映射，当前 `0~200` UI 范围可直接整除映射，改动最小；同时用户在滑杆上看到的「50 = 原图对比度、100 = 原图饱和度」与常见修图 App 的百分比概念接近。

### 4.2 参数语义、范围与滑杆映射

#### 美颜（Beauty）

| 参数 | UI 范围 | 默认值 | 语义 | 引擎归一化 | 参考来源 |
|------|---------|--------|------|------------|----------|
| `smoothing` 磨皮 | `0 ~ 100` | `0` | 0=关闭，100=最强磨皮 | `smoothing / 100` → `0.0 ~ 1.0` | 小米/美图 |
| `whitening` 美白 | `0 ~ 100` | `0` | 0=关闭，100=最强美白 | `whitening / 100` → `0.0 ~ 1.0` | 小米/美图 |
| `slimFace` 瘦脸 | `-50 ~ +50` | `0` | 负值=丰脸，正值=瘦脸 | `slimFace / 50 * 1.35` → `-1.0 ~ 1.0` | 小米/美图 |
| `bigEyes` 大眼 | `0 ~ 100` | `0` | 0=关闭，100=最大放大 | `bigEyes / 100` → `0.0 ~ 1.0` | 小米/美图 |
| `lipColor` 唇色 | `0 ~ 100` | `0` | 0=关闭，100=最强唇色 | `lipColor / 100` → `0.0 ~ 1.0` | 小米/美图 |
| `blush` 腮红 | `0 ~ 100` | `0` | 0=关闭，100=最强腮红 | `blush / 100` → `0.0 ~ 1.0` | 小米/美图 |
| `eyebrow` 眉毛 | `0 ~ 100` | `0` | 0=关闭，100=最深 | `eyebrow / 100` → `0.0 ~ 1.0` | 小米/美图 |
| `bodyEnhancement` 美体 | `-30 ~ +30` | `0` | 负值=压缩，正值=拉伸上半身 | `strength / 30 * 0.15` → 拉伸系数 | 国产美体 |
| `legExtension` 长腿 | `0 ~ 50` | `0` | 0=关闭，50=最大拉伸 | `strength / 50 * 0.15` → 拉伸系数 | 国产美体 |

#### 调色（Adjustment）

| 参数 | UI 范围 | 默认值 | 语义 | 引擎归一化 | 参考来源 |
|------|---------|--------|------|------------|----------|
| `brightness` 亮度 | `-100 ~ +100` | `0` | 整体明暗偏移 | `/ 100` → `-1.0 ~ +1.0` | Apple Photos |
| `exposure` 曝光 | `-100 ~ +100` | `0` | 模拟曝光补偿 | `coerceIn(-10, +10)` → Shader 曝光 | Apple Photos |
| `contrast` 对比度 | `0 ~ 200` | `50` | 50=原图，0=最低，200=最高 | `/ 50` → `0.0 ~ 4.0` | 等价 Apple `-100 ~ +100` |
| `saturation` 饱和度 | `0 ~ 200` | `100` | 100=原图，0=灰度，200=最高 | `/ 100` → `0.0 ~ 2.0` | 等价 Apple `-100 ~ +100` |
| `temperature` 色温 | `2000K ~ 8000K` | `5000K` | 低=冷（蓝），高=暖（黄） | `(T-5000)/3000` → `-1.0 ~ +1.0` | Apple Warmth + Kelvin |
| `tint` 色调 | `-100 ~ +100` | `0` | 绿↔品红偏移 | `/ 100` → `-1.0 ~ +1.0` | Apple Photos |
| `vignette` 暗角 | `0 ~ 100` | `0` | 0=关闭，100=最强 | 待 Phase 2 实现 | Apple Photos |

#### 滤镜（Filter）

| 滤镜 | 类型 | 推荐场景 | 说明 |
|------|------|----------|------|
| `NONE` | 无 | 通用 | 直通 |
| `LEICA_CLASSIC` | 色调矩阵 | 人像、街拍 | 降低蓝绿通道，模拟德味 |
| `LEICA_VIBRANT` | 饱和度 | 风景、美食 | 饱和度 +30% |
| `LEICA_BW` | 饱和度=0 | 人文、建筑 | 黑白 |
| `FILM_GOLD` | 色调矩阵 | 人像、日落 | 暖黄胶片感 |
| `FILM_FUJI` | 色调矩阵 | 风景、绿植 | 偏青绿胶片感 |
| `VINTAGE` | 色调矩阵 | 复古主题 | 褪色、暖调 |
| `COOL` | 色调矩阵 | 雪景、夜景 | 增强蓝色 |
| `WARM` | 色调矩阵 | 美食、日落 | 增强红黄 |

### 4.3 推荐预设值（MVP）

> 所有预设以「自然、克制、可二次微调」为原则，避免一键后过曝、过磨、过饱和。

#### 自拍（SELFIE）

```json
{
  "beauty": { "smoothing": 35, "whitening": 25, "slimFace": 10, "bigEyes": 15, "lipColor": 25, "blush": 10, "eyebrow": 10 },
  "filter": { "colorFilter": "NONE", "styleFilter": "NONE" },
  "adjustment": { "brightness": 5, "exposure": 0, "contrast": 52, "saturation": 102, "temperature": 5200, "tint": 2 }
}
```

#### 人像（PORTRAIT）

```json
{
  "beauty": { "smoothing": 25, "whitening": 20, "slimFace": 5, "bigEyes": 10, "lipColor": 20, "blush": 8, "eyebrow": 8 },
  "filter": { "colorFilter": "FILM_GOLD", "styleFilter": "NONE" },
  "adjustment": { "brightness": 3, "exposure": 0, "contrast": 53, "saturation": 103, "temperature": 5300, "tint": 2 }
}
```

#### 合影（GROUP）

```json
{
  "beauty": { "smoothing": 20, "whitening": 15, "slimFace": 0, "bigEyes": 0, "lipColor": 10, "blush": 5, "eyebrow": 5 },
  "filter": { "colorFilter": "NONE", "styleFilter": "NONE" },
  "adjustment": { "brightness": 3, "exposure": 0, "contrast": 52, "saturation": 102, "temperature": 5200, "tint": 1 }
}
```

#### 美食（FOOD）

```json
{
  "beauty": { "enabled": false },
  "filter": { "colorFilter": "LEICA_VIBRANT", "styleFilter": "NONE" },
  "adjustment": { "brightness": 2, "exposure": 0, "contrast": 55, "saturation": 110, "temperature": 5400, "tint": 3 }
}
```

#### 风景（LANDSCAPE）

```json
{
  "beauty": { "enabled": false },
  "filter": { "colorFilter": "LEICA_VIBRANT", "styleFilter": "NONE" },
  "adjustment": { "brightness": 0, "exposure": 0, "contrast": 58, "saturation": 110, "temperature": 5000, "tint": 0 }
}
```

#### 夜景/暗光（LOW_LIGHT）

```json
{
  "beauty": { "smoothing": 15, "whitening": 10 },
  "filter": { "colorFilter": "WARM", "styleFilter": "NONE" },
  "adjustment": { "brightness": 12, "exposure": 5, "contrast": 55, "saturation": 100, "temperature": 5600, "tint": 3 }
}
```

#### 文档（DOCUMENT）

```json
{
  "beauty": { "enabled": false },
  "filter": { "colorFilter": "NONE", "styleFilter": "NONE" },
  "adjustment": { "brightness": 8, "exposure": 0, "contrast": 60, "saturation": 95, "temperature": 5000, "tint": 0 }
}
```

#### 通用（GENERAL）

```json
{
  "beauty": { "smoothing": 15, "whitening": 10 },
  "filter": { "colorFilter": "NONE", "styleFilter": "NONE" },
  "adjustment": { "brightness": 2, "exposure": 0, "contrast": 52, "saturation": 100, "temperature": 5000, "tint": 0 }
}
```

---

## 5. 数据模型与 Capability 接口

### 5.1 Capability 注册

```kotlin
Capability(
    name = "ai_optimize",
    description = "分析照片场景并自动推荐美颜/滤镜/调节参数，支持一键优化",
    schema = AiOptimizeCapabilitySchema
)
```

### 5.2 输入模型

```kotlin
data class AiOptimizeRequest(
    val imageUri: String,
    val mode: OptimizeMode = OptimizeMode.FAST,
    val source: OptimizeSource = OptimizeSource.GALLERY_VIEWER,
    val allowCloud: Boolean = false
)

enum class OptimizeMode { FAST, SMART }
enum class OptimizeSource { GALLERY_VIEWER, EDITOR, CHAT, BATCH, IM_REMOTE }
```

### 5.3 输出模型

```kotlin
data class AiOptimizeResult(
    val scene: Scene,
    val confidence: Float,
    val preset: OptimizePreset,
    val explanation: String,
    val usedCloud: Boolean,
    val processingTimeMs: Long
)
```

### 5.4 核心类设计

```kotlin
class AiOptimizeCapability(
    private val sceneAnalyzer: SceneAnalyzer,
    private val presetRepository: PresetRepository,
    private val smartEngine: SmartOptimizeEngine? = null,
    private val consentManager: CloudOptimizeConsentManager
) : Capability {

    override val name: String = "ai_optimize"
    override val description: String = "分析照片场景并自动推荐美颜/滤镜/调节参数"

    override suspend fun execute(request: CapabilityRequest): CapabilityResponse {
        val optimizeRequest = request.parseAs<AiOptimizeRequest>()

        return try {
            when (optimizeRequest.mode) {
                OptimizeMode.FAST -> fastOptimize(optimizeRequest)
                OptimizeMode.SMART -> smartOptimize(optimizeRequest)
            }
        } catch (e: Exception) {
            Logger.e("AiOptimize", "Optimization failed", e)
            fallbackFastOptimize(optimizeRequest)
        }
    }

    private suspend fun fastOptimize(request: AiOptimizeRequest): CapabilityResponse {
        val startTime = SystemClock.elapsedRealtime()
        val analysis = sceneAnalyzer.analyze(request.imageUri)
        val preset = presetRepository.getPreset(analysis.scene)
        val elapsed = SystemClock.elapsedRealtime() - startTime

        val result = AiOptimizeResult(
            scene = analysis.scene,
            confidence = analysis.confidence,
            preset = preset,
            explanation = buildExplanation(analysis.scene),
            usedCloud = false,
            processingTimeMs = elapsed
        )

        return CapabilityResponse.Success(result)
    }

    private suspend fun smartOptimize(request: AiOptimizeRequest): CapabilityResponse {
        if (!consentManager.isCloudOptimizeAllowed()) {
            return CapabilityResponse.NeedConsent("cloud_optimize")
        }
        if (smartEngine == null) {
            return fastOptimize(request)
        }
        return smartEngine.optimize(request)
    }

    private fun fallbackFastOptimize(request: AiOptimizeRequest): CapabilityResponse {
        val preset = presetRepository.getPreset(Scene.GENERAL)
        return CapabilityResponse.Success(
            AiOptimizeResult(
                scene = Scene.GENERAL,
                confidence = 0.5f,
                preset = preset,
                explanation = context.getString(R.string.ai_optimize_fallback_explanation),
                usedCloud = false,
                processingTimeMs = 0
            )
        )
    }
}
```

### 5.5 与编辑器集成

```kotlin
class PhotoEditorViewModel(
    private val aiOptimizeCapability: AiOptimizeCapability,
    private val recipeApplier: RecipeApplier,
    private val history: EditHistory
) : ViewModel() {

    fun onAiOptimizeClick() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAiOptimizing = true)

            val request = AiOptimizeRequest(
                imageUri = currentImageUri,
                mode = OptimizeMode.FAST,
                source = OptimizeSource.EDITOR
            )

            when (val response = aiOptimizeCapability.execute(CapabilityRequest(request))) {
                is CapabilityResponse.Success -> {
                    val result = response.data as AiOptimizeResult
                    val newRecipe = OptimizeRecipeMapper.toEditRecipe(
                        preset = result.preset,
                        baseRecipe = history.current
                    )
                    history.push(newRecipe)
                    renderPreview()
                }
                is CapabilityResponse.NeedConsent -> {
                    _events.emit(EditorEvent.ShowCloudConsentDialog)
                }
                is CapabilityResponse.Error -> {
                    _events.emit(EditorEvent.ShowError(response.message))
                }
            }

            _uiState.value = _uiState.value.copy(isAiOptimizing = false)
        }
    }
}
```

### 5.6 Agent Tool Spec

```json
{
  "type": "function",
  "function": {
    "name": "ai_optimize",
    "description": "分析照片场景并自动推荐美颜、滤镜、调节参数，一键优化图片。",
    "parameters": {
      "type": "object",
      "properties": {
        "image_uri": {
          "type": "string",
          "description": "待优化图片的本地文件 URI"
        },
        "mode": {
          "type": "string",
          "enum": ["fast", "smart"],
          "description": "优化模式：fast 为本地快速优化（默认），smart 为云端智能推荐"
        }
      },
      "required": ["image_uri"],
      "additionalProperties": false
    }
  }
}
```

---

## 6. 错误处理与降级策略

| 错误场景 | 处理策略 | 用户反馈 |
|----------|----------|----------|
| 本地分析超时 | 使用 GENERAL preset | "已应用通用优化" |
| ML Kit 不可用 | 使用 GENERAL preset | "已应用通用优化" |
| 远程模型超时 | fallback 到 FAST 路径 | "网络较慢，已使用本地优化" |
| 远程模型返回非法 JSON | fallback 到 FAST 路径 | "智能推荐失败，已使用本地优化" |
| 用户未授权云端 | 返回 NeedConsent | 显示授权弹窗 |
| 图片加载失败 | 返回 Error | "无法加载图片，请重试" |
| GPU 处理失败 | 返回 Error | "处理失败，请手动编辑" |

---

## 7. 性能要求

| 路径 | P50 | P95 | 测量点 |
|------|-----|-----|--------|
| Fast 场景分析 | < 200ms | < 400ms | 从点击到返回 SceneAnalysis |
| Fast 端到端 | < 1s | < 1.5s | 从点击到显示推荐卡片 |
| 媒体查看器应用保存 | < 2s | < 3s | 从点击「应用」到进入对比模式 |
| Smart 端到端 | < 2s | < 4s | 从点击「换一换」到返回新推荐 |
| 批量处理单张 | < 1.5s | < 2.5s | 多选批量模式下单张平均 |

---

## 8. 隐私与安全

- **Fast 路径**：所有分析在本地完成，敏感数据不出设备
- **Smart 路径**：
  - 图片缩放至最长边 512px，JPEG 质量 80
  - 通过 HTTPS 发送到用户配置的模型服务商
  - 不在 PoLang 任何服务端存储图片
  - 用户可在设置中随时关闭云端 AI 优化
- **批量处理**：强制使用 Fast 路径，不上传任何图片

---

## 9. 验收指标

| 指标 | MVP 目标 | 测量方式 |
|------|----------|----------|
| AI 优化按钮点击率 | > 30%（进入查看器/编辑器的用户） | 埋点 |
| 应用率（点击后保存） | > 60% | 埋点 |
| 微调率（点击后进入编辑器调整） | < 40% | 埋点 |
| 本地路径处理时间 | < 1s（P50） | 日志 |
| 场景识别准确率 | > 80%（5 个核心场景） | 人工标注样本 |
| 崩溃率 | 0 | Crashlytics |

### 9.1 代码侧改动清单

- [x] `PhotoProcessorImpl`：EGL 上下文失效后彻底释放 Shader/FBO/纹理，避免复用无效句柄导致黑屏。
- [x] `PhotoEditorViewModel`：为照片处理创建独立单线程调度器，避免 `Dispatchers.Default` 线程池切换导致 EGL 上下文丢失。
- [x] `RecipeApplier`：增加 GPU 输出全黑检测与 CPU 滤镜兜底，确保极端情况下不显示黑屏。
- [x] `AppContainer`：编辑器使用独立的 `PhotoProcessor` 实例，避免与相机拍照路径共享 EGL 上下文。
- [x] `optimize_presets.json`：按本节推荐值重新调整，降低美颜强度，使调色更自然。
- [x] 新增本文档作为参数标准与预设规范的唯一事实来源。

### 9.2 验收标准

- AI 优化点击后 500ms 内完成预览，且不再出现全黑画面。
- 各场景预设应用后，美颜强度可被普通用户感知但不夸张。
- 对比度/饱和度/色温/亮度滑杆默认值与文档一致。
- 后续新增参数必须在本文档中补充语义、范围、参考来源。

---

## 10. Agent Task 拆解

| Task ID | 名称 | Assignee | Priority | 依赖 |
|---------|------|----------|----------|------|
| `aio-001` | 场景分析器 SceneAnalyzer | RD | P0 | - |
| `aio-002` | 本地预设库 PresetRepository | RD | P0 | - |
| `aio-003` | AI 优化 Capability | RD | P0 | aio-001, aio-002 |
| `aio-004` | 媒体查看器 AI 优化入口 | RD | P0 | aio-003 |
| `aio-005` | 编辑器 AI 一键优化入口 | RD | P0 | aio-003 |
| `aio-006` | 优化结果保存与对比模式 | RD | P0 | aio-004, aio-005 |
| `aio-007` | 隐私授权与设置开关 | RD | P1 | aio-003 |
| `aio-008` | 远程 Smart 优化引擎 | RD | P1 | aio-007 |
| `aio-009` | AI 对话命令集成 | RD | P1 | aio-003 |
| `aio-010` | 批量 AI 优化 | RD | P1 | aio-003, aio-006 |
| `aio-011` | 验收测试与性能基准 | QA | P0 | aio-004, aio-005, aio-006 |
| `aio-012` | 架构合规审查 | CR | P0 | aio-003 完成后 |

### 10.1 [agent-task:aio-001] 场景分析器 SceneAnalyzer

> **实现现状（2026-08）**：未落地——`analyzer/` 下仅有 `Scene` 枚举，`AiOptimizeUseCase.fastOptimize()` 恒取 `Scene.GENERAL`；场景识别由 Smart 路径远程模型承担。原第 2 条依赖的 ML Kit Image Labeling 已移除。

- **Assignee**: RD
- **Scope**: `domain/agent/capability/optimize/analyzer/SceneAnalyzer.kt`
- **Expected Change**:
  1. 定义 `Scene` 枚举（selfie, portrait, group, food, landscape, low_light, document, general）
  2. ~~集成 ML Kit Image Labeling 获取 Top-K 标签~~（该依赖已移除；可改用 Florence-2 打标结果或 EXIF/直方图等本地信号替代）
  3. 复用 `FaceDetector` 获取人脸数量与占画面比例
  4. 读取 EXIF 与亮度统计作为辅助信号
  5. 实现规则引擎，将多路信号映射为 `Scene` + 置信度
  6. 添加单元测试覆盖 5 个核心场景
- **Priority**: P0
- **Acceptance**:
  - 5 个核心场景识别准确率 > 80%（人工标注 50 张测试集）
  - 单张分析时间 < 200ms（中端机）
  - 单元测试覆盖率 > 60%

### 10.2 [agent-task:aio-002] 本地预设库 PresetRepository

- **Assignee**: RD
- **Scope**: `domain/agent/capability/optimize/preset/PresetRepository.kt`
- **Expected Change**:
  1. 定义 `OptimizePreset` 数据类（包含 beauty, filter, adjustment）
  2. 在 `assets/presets/optimize_presets.json` 中配置 5 个 MVP 场景 preset
  3. 实现从 JSON 加载并缓存到内存
  4. 提供 `getPreset(scene: Scene): OptimizePreset` 接口
  5. 添加单元测试验证 preset 可正确反序列化
- **Priority**: P0
- **Acceptance**:
  - 5 个 MVP preset 可被正确加载
  - preset 参数范围符合 `BeautySettings` / `AdjustmentRecipe` 约束
  - PM + 设计师评审通过参数合理性

### 10.3 [agent-task:aio-003] AI 优化 Capability

- **Assignee**: RD
- **Scope**: `domain/agent/capability/optimize/AiOptimizeCapability.kt`
- **Expected Change**:
  1. 实现 `Capability` 接口，name = `"ai_optimize"`
  2. 定义 `AiOptimizeRequest` / `AiOptimizeResult` 数据类
  3. 组合 `SceneAnalyzer` + `PresetRepository` 实现 Fast 路径
  4. 注册到 `CapabilityRegistry`
  5. 添加错误处理：分析失败时返回降级 preset
  6. 添加单元测试
- **Priority**: P0
- **Acceptance**:
  - 可被 AgentOrchestrator 通过 tool call 调用
  - Fast 路径端到端 < 500ms
  - 失败时 graceful 降级，不崩溃

---

## 11.5 抽卡闭环（2026-08-06 落地）

AI 优化已从「一次给值」升级为「抽卡闭环」（best-of-N + NIMA 评分守卫）：

- **链路**：`CandidateSampler` 以场景预设为锚点抽 4 候选 → `CandidateRenderer` 512px 渲染 → `OptimizeScorer` NIMA 评分 + 技术护栏（高光裁剪增量 5pp、亮度漂移 15%）→ 选优
- **退化守卫**：最优候选 NIMA 分 ≤ 原图 + 0.05 时不推荐（previewedIndex=-1，保持原图预览），从机制上杜绝"越优化越差"
- **先预览后应用（2026-08-06 修订）**：抽卡后进入对比模式——最优卡在编辑器主预览区全尺寸预览（复用 2048px 预览管线，不入撤销历史），底部候选条独占（面板/底栏隐藏、undo/redo 禁用）；点卡仅切预览，「应用」才入历史 + 落库 `user`，「关闭」回退原图 + 落库 `dismiss`
- **落库语义**：每组生成时自动落一条 `auto` 记录（NIMA 建议），「应用」/「关闭」再落 `user`/`dismiss` 记录——两组叠加是有意设计，Phase 2 可比对「NIMA 建议 vs 人选」差异
- **降级链**：NIMA 模型未下载 → 退回固定预设直接应用（原行为）；批量优化不走抽卡
- 设计/计划稿已随交付清理（2026-08-06 抽卡，git 历史可查），本节为现行事实源

---

## Chat 页抽卡（2026-08-06）

chat 内 AI 优化指令同样走抽卡闭环（复用 `optimizeWithGacha`，domain 引擎零改动）：
结果以对话内候选卡条呈现（type=optimize_candidates 消息，metadata 存展示数据，
候选 preset 在 `ChatOptimizeGachaController` 进程级内存态），支持「换一组」去重重抽、
点卡全屏预览、显式「就用这张」确认——确认后全尺寸渲染并折叠为普通 agent_image 结果消息，
选中 recipe 写入 `ChatEditStateHolder` 支撑多轮 delta 续调。
NIMA 不可用 / 缩略图落盘全失败时退回原固定预设单发路径。
反馈经 `OptimizeFeedbackLogger` 落库（auto/user/dismiss），与编辑器抽卡共用 optimize_feedback 表。
（设计稿已随交付清理，git 历史可查）。

---

## 11. 相关文档索引

| 文档 | 说明 |
|------|------|
| `docs/01-PRODUCT/FEATURES.md#141-ai-一键优化` | 交互 PRD |
| `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md` | 大美丽美颜引擎 |
| `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` | Agent 架构 |
| `androidApp/src/main/java/com/mamba/picme/features/editor/AGENTS.md` | 编辑器模块规范 |
| `androidApp/src/main/java/com/mamba/picme/features/gallery/AGENTS.md` | 相册模块规范 |
