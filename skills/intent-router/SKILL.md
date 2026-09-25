---
name: intent-router
description: |
  PoLang 项目意图路由与需求解析 Skill。将用户的自然语言需求转化为 AI 可执行的技术任务。
version: 1.2.0
created: 2026-05-03
updated: 2026-09-25
maintainer: 项目开发者
tags:
  - requirement
  - routing
  - context
  - module
  - terminology
---


# Intent Router - 意图路由与需求解析

> **定位**：PoLang 项目意图路由与需求解析，将用户的自然语言需求转化为 AI 可执行的技术任务。
> **触发时机**：用户提出需求、任务路由或需要解析自然语言到技术任务时自动启用。


## 设计目标

消除 AI Coding 流程中**需求理解模糊**导致的人工干预断点：

```
用户说:"相册加个编辑功能"
    ↓
[旧流程] AI 反问:"您是指静态图美颜编辑还是视频剪辑？"
    ↓ 人工确认
[新流程] Intent Router 自动识别 → 匹配术语表 → 加载上下文 → 直接执行
```

## 意图分类矩阵

| 用户表达 | 意图分类 | 置信度 | 自动加载上下文 |
|----------|----------|--------|----------------|
| "加个...功能" / "支持..." / "实现..." | `[Feature]` | 高 | 目标模块 AGENTS.md + FEATURES.md |
| "修复..." / "...不工作" / "...报错" / "...bug" | `[BugFix]` | 高 | 相关源码 + 日志模式 + 近期 git log |
| "优化..." / "提升...性能" / "变慢了" | `[Perf]` | 高 | 性能基准 + Profiler 配置 + 相关算法文档 |
| "重构..." / "改一下架构" / "解耦" | `[Refactor]` | 中 | 架构文档 + 接口清单 + 依赖图 |
| "调整 UI" / "按钮位置" / "颜色不对" | `[UIAdjust]` | 高 | Compose 布局 + 设计规范 + 主题配置 |
| "发版" / "打包" / "release" | `[Release]` | 高 | 版本管理流程 + CHANGELOG + 签名配置 |
| "测试..." / "验证..." / "覆盖率" | `[Test]` | 高 | 测试规范 + 现有测试 + 覆盖率报告 |
| "文档..." / "补充说明" / "规范" | `[Doc]` | 高 | 根 AGENTS.md + `docs/superpowers/README.md`（AI 协作产物 SSOT）+ 文档治理规则 |
| "回滚" / "撤销" / "回退" | `[Rollback]` | 高 | git log + 变更清单 + 影响分析 |

## 术语对齐表

### 美颜相关

| 用户口语 | 项目术语 | 对应代码 | 所属模块 |
|----------|----------|----------|----------|
| 磨皮 | 平滑 / Smooth Skin | `smoothSkinStrength` | beauty-engine |
| 美白 | 美白 / Whiten | `whitenStrength` | beauty-engine |
| 瘦脸 | 瘦脸 / Slim Face | `slimFaceStrength` | beauty-engine |
| 大眼 | 大眼 / Big Eye | `bigEyeStrength` | beauty-engine |
| 唇色 | 唇彩 / Lip Color | `lipColorStrength` | beauty-engine |
| 腮红 | 腮红 / Blush | `blushStrength` | beauty-engine |
| 滤镜 | 滤镜 / Color Filter | `colorFilter: FilterType` | beauty-engine + app |
| 风格特效 | 风格 / Style Filter | `styleFilter: StyleFilter` | beauty-engine + app |
| 调色 | 调色 / Color Adjust | `exposure/contrast/saturation/...` | beauty-engine |
| 大美丽 | BIG_BEAUTY (引擎代号) | `BeautyEngineMode.BIG_BEAUTY` | beauty-engine |
| GPU 处理 | GPU 离屏渲染 | `PhotoProcessorImpl` | beauty-engine |
| 人脸检测 | Face Detection | `FaceDetector / FaceData` | beauty-engine |
| 106 点 | 人脸关键点 (106 landmarks) | `landmarks106` | beauty-engine |
| MediaPipe | MediaPipe Face Mesh | `MediaPipe468Adapter` | beauty-engine |
| MNN | MNN 2D106 | `MnnFaceDetector` / `FaceLandmarkAdapter` | beauty-engine |

### 相机相关

| 用户口语 | 项目术语 | 对应代码 | 所属模块 |
|----------|----------|----------|----------|
| 拍照 | 拍摄 / Capture | `ImageCapture.takePicture()` | camera (app) |
| 预览 | 相机预览 / Preview | `CameraPreview` | camera (app) |
| 前后摄像头 | Lens Facing | `lensFacing: 0(前置)/1(后置)` | camera (app) |
| 快门 | 快门 / Shutter | `CAPTURE_MODE_MINIMIZE_LATENCY` | camera (app) |
| 夜景 | 夜景模式 / Night | `SceneMode.NIGHT` | camera (app) |
| 月亮模式 | 月亮模式 / Moon | `SceneMode.MOON` | camera (app) |
| 画幅 | 画幅比例 / Aspect Ratio | `ratio: 4_3/16_9/full` | camera (app) |
| 曝光 | 曝光补偿 / Exposure | `exposureCompensation` | camera (app) |
| 变焦 | 缩放 / Zoom | `zoomRatio` | camera (app) |
| 黑屏 | 预览黑屏 / Black Screen | `GL_ERROR / FBO 状态异常` | beauty-engine |
| FPS | 帧率 / Frame Rate | `fps` (调试浮层) | camera + beauty-engine |

### 相册相关

| 用户口语 | 项目术语 | 对应代码 | 所属模块 |
|----------|----------|----------|----------|
| 相册 | 图库 / Gallery | `GalleryScreen` | gallery (app) |
| 照片编辑 | 静态图美颜编辑 | `PhotoEditState / MediaPager` | gallery (app) |
| 批量选择 | 多选模式 / Multi-select | `selectionMode` | gallery (app) |
| 批量删除 | 批量删除 | `batchDelete()` | gallery (app) |
| 提取文字 | OCR / Text Recognition | `OcrProcessor` | gallery (app) |
| MediaStore | 媒体库 API | `MediaStore.Images.Media` | data (app) |

### 架构相关

| 用户口语 | 项目术语 | 对应代码/文件 |
|----------|----------|---------------|
| Clean Architecture | 分层架构 | `domain/ data/ features/` |
| MVVM | MVVM 模式 | `ViewModel + UiState + Screen` |
| 依赖注入 | DI / Hilt | `@HiltViewModel @Inject` |
| 状态管理 | State Management | `Sealed Class + StateFlow` |
| 协程 | Coroutine | `viewModelScope.launch` |
| Compose | Jetpack Compose | `@Composable` |
| Room | 本地数据库 | `@Entity @Dao @Database` |
| DataStore | 偏好设置存储 | `DataStore<Preferences>` |
| ML Kit | Google ML Kit | `com.google.mlkit` |
| ONNX | ONNX Runtime | `onnxruntime-android` |
| OpenGL | OpenGL ES | `GLES20 / GLES30` |
| EGL | EGL 上下文 | `EGL14 / EGLConfig` |
| FBO | 帧缓冲对象 | `Framebuffer / FBO` |
| Shader | 着色器 | `.glsl / .frag / .vert` |
| VAO/VBO | 顶点数组/缓冲对象 | `VertexArray / VertexBuffer` |
| 纹理 | Texture | `GLES20.glTexImage2D` |
| NDC | 归一化设备坐标 | `[-1,1] 坐标系` |
| UV 坐标 | 纹理坐标 | `[0,1] 坐标系` |

### Agent 相关

| 用户口语 | 项目术语 | 对应代码 | 所属模块 |
|----------|----------|----------|----------|
| 语音控制 | 语音命令 / Voice Command | `VoiceCommandCoordinator`（`features/camera/voice/`） | camera (app) |
| 智能助手 | AI Agent / Agent Panel | `GlobalAgentPanel` | app |
| 拍张照 | 拍照指令 / Capture | `AiAgentCommand.CapturePhoto` | androidApp `domain/model` |
| 换滤镜 | 切换滤镜 / SwitchFilter | `AiAgentCommand.SwitchFilter` | androidApp `domain/model` |
| 美颜参数 | 美颜调整 / AdjustBeauty | `AiAgentCommand.AdjustBeauty` | androidApp `domain/model` |
| 场景模式 | 场景切换 / SwitchScene | `AiAgentCommand.SwitchScene` | androidApp `domain/model` |
| 语音唤醒 | 唤醒词 / Wake Word | `KeywordSpotterEngine`（shared）/ `KwakeWordKwsEngine`（app） | shared + camera |
| 推理模式 | 推理模式 / Inference Mode | shared `AiAgentConfig.AiAgentMode`（OFF/REMOTE/FEISHU）；androidApp `UserPreferences.AiAgentMode`（OFF/LOCAL/REMOTE，LOCAL 为遗留兜底）——**两套枚举勿混** | shared + androidApp |
| 本地模型 | 端侧 VLM / On-device VLM | `LocalLlmEngine / MnnLlmClient` | shared(androidMain) |
| 远程模型 | 远程 LLM / Remote LLM | `RemoteChatEngine / KoogReActAgent` | shared |
| Capability | 能力接口 | `Capability` | shared `agent/core/runtime` |

### 全局红线术语

| 缩写 | 全称 | 含义 | 检查点 |
|------|------|------|--------|
| `[PRIVACY]` | Privacy | 隐私至上 | 人脸/OCR/分类必须本地处理，禁止网络权限 |
| `[PERF]` | Performance | 极致性能 | 交互 < 100ms，快门 < 50ms，GPU 处理 < 300ms |
| `[I18N]` | Internationalization | 多语言同步 | EN/CN/TW/ES/FR 五语言同步，禁止硬编码 |

## 上下文自动加载规则

```yaml
# 根据意图和模块自动读取的文件

[Feature] + camera:
  - androidApp/src/main/java/com/mamba/picme/features/camera/AGENTS.md
  - docs/01-PRODUCT/FEATURES.md (§4 智能相机——❄️ 冻结待生效，新需求先确认冻结状态)
  - androidApp/src/main/java/com/mamba/picme/features/camera/*.kt (最新修改的 3 个文件)

[Feature] + gallery:
  - androidApp/src/main/java/com/mamba/picme/features/gallery/AGENTS.md
  - docs/01-PRODUCT/FEATURES.md (§1 智能相册与图片编辑)
  - androidApp/src/main/java/com/mamba/picme/features/gallery/*.kt

[Feature] + beauty:
  - engines/beauty-engine/AGENTS.md
  - docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md
  - engines/beauty-engine/src/main/java/com/mamba/picme/beauty/**/*.kt

[Feature] + agent:
  - docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md
  - docs/04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md
  - shared/AGENTS.md（编排层模块规范）
  - androidApp/src/main/java/com/mamba/picme/domain/agent/**/*.kt
  # 注：androidApp domain/agent/capability/AGENTS.md 已冻结，仅历史设计参考

[BugFix] + 任何模块:
  - 相关模块 AGENTS.md
  - 相关源码文件
  - 最近 3 次 git log
  - 最近相关 issue/PR (如果有)

[Perf]:
  - 相关模块 AGENTS.md
  - docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md (性能章节)
  - 基准测试数据
  - Profiler 配置说明
  - [/perf-optimizer](/perf-optimizer)

[UIAdjust]:
  - 目标 Compose 文件
  - androidApp/src/main/res/values/themes.xml
  - androidApp/src/main/res/values/colors.xml
  - docs/01-PRODUCT/FEATURES.md (设计规范章节)
  - [/compose-ui-expert](/compose-ui-expert)

[Voice]:
  - androidApp/src/main/java/com/mamba/picme/features/camera/voice/ 目录下相关文件
  - docs/01-PRODUCT/FEATURES.md (语音交互章节)
  - docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md (语音命令路由)

[I18N]:
  - res/values*/strings.xml
  - [/i18n-validator](/i18n-validator)
```

## 歧义消除策略

### 自动消除（无需人工确认）

| 歧义场景 | 消除策略 |
|----------|----------|
| "编辑" → 照片编辑 vs 视频编辑 | 根据当前上下文（camera/gallery）推断 |
| "滤镜" → 颜色滤镜 vs 风格滤镜 | 默认指颜色滤镜，如用户提及"卡通/素描"则指风格 |
| "优化" → 性能优化 vs 代码优化 | 如涉及 FPS/耗时/内存则指性能，否则指代码质量 |
| "人脸" → 人脸检测 vs 人脸美颜 | 涉及"检测/识别"指检测，涉及"美/瘦/大"指美颜 |

### 需要人工确认（保守执行触发）

| 歧义场景 | 确认问题 |
|----------|----------|
| "加个新功能" 但目标模块不明确 | "您希望在哪个模块实现：相机 / 相册 / 美颜引擎？" |
| 涉及删除现有功能 | "确认删除 [功能名] 吗？这将影响 [影响范围]" |
| 跨模块大量修改 | "此变更影响 N 个模块，建议拆分为子任务，是否继续？" |
| 修改公共 API | "此修改将破坏 [N] 个调用点，是否继续？" |
| 性能优化涉及算法变更 | "算法变更需重新校准基准测试，是否进入保守执行模式？" |

## 使用示例

### 示例 1: 功能需求

```
用户: "相册照片加个旋转功能"

Intent Router 处理:
1. 意图分类: [Feature] + gallery
2. 术语对齐: "旋转" → `rotate()` / `Rotation` (UI 变换)
3. 上下文加载:
   - androidApp/src/main/java/com/mamba/picme/features/gallery/AGENTS.md
   - docs/01-PRODUCT/FEATURES.md §2.2 (相册交互规范)
   - MediaPager.kt (最近修改的 gallery 文件)
4. 影响推断: gallery 模块，UI 层变更，涉及 [I18N]（新增按钮文案）
5. 决策: 自动执行（单模块，无 API 破坏）
6. 输出任务: "在 Gallery/MediaPager 添加照片旋转功能"
```

### 示例 2: Bug 修复

```
用户: "拍照后保存的照片有点偏红"

Intent Router 处理:
1. 意图分类: [BugFix] + camera/beauty
2. 术语对齐: "偏红" → 色温/白平衡 / `temperature` / `tint`
3. 上下文加载:
   - engines/beauty-engine/AGENTS.md
   - docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md (调色章节)
   - PhotoProcessorImpl.kt, BeautySettings.kt
4. 影响推断: beauty-engine 模块，调色参数问题
5. 决策: 自动执行
6. 输出任务: "排查并修复拍照后处理色温偏移问题"
```

### 示例 3: 性能优化

```
用户: "相册滑动有点卡"

Intent Router 处理:
1. 意图分类: [Perf] + gallery
2. 术语对齐: "卡" → 掉帧 / jank / 滑动性能 < 120fps
3. 上下文加载:
   - androidApp/src/main/java/com/mamba/picme/features/gallery/AGENTS.md
   - GalleryScreen.kt (LazyColumn/Grid 实现)
   - 性能基线数据
4. 影响推断: gallery 模块，UI 渲染性能，[PERF] 红线
5. 决策: 保守执行（性能优化可能需要基准测试验证）
6. 输出任务: "优化相册列表滑动性能至 120fps"
   → 提示用户确认优化方向
```

## 扩展指南

### 添加新术语

直接在 SKILL.md 的「术语对齐表」中追加新行：

```markdown
## 新模块: editor
| 用户口语 | 项目术语 | 对应代码 | 所属模块 |
|----------|----------|----------|----------|
| 裁剪 | 裁剪 / Crop | `cropRect` | editor |
| 旋转 | 旋转 / Rotate | `rotationDegrees` | editor |
```

### 添加新意图类型

在 SKILL.md 的意图分类矩阵中添加新行，并定义对应的上下文加载规则。

## Chat 意图路由：两层架构（2026-09-25 更新）

**本 skill 是「需求解析层」**（AI 协作入口的意图分类与术语对齐），与**「运行时 chat 意图路由」是两层**，勿混淆：

- **运行时现状**：chat 工具分发由 Koog agent loop + `ChatToolService` 驱动；`shared` 侧仅有 `intent/IntentGuard`（意图守卫纯函数：LLM 误拒搜索回退判定、模糊跳转拦截）。尚无独立 IntentRouter。
- **演进方向（Spec，方向已认可、待评审后实施）**：`docs/superpowers/specs/2026-09-25-intent-routing-contract-design.md`——「LLM 管意图、代码管策略」：`ChatIntentContract` 契约表 + 意图路由器（置于 `AgentOrchestrator` chat 入口、`PrivacyGuard` 之后、agent loop 之前），里程碑 **M1 止血 → M2 路由器 → M3 Koog graph 分支化**（OPEN_QA/搜索/脚本等分支）。
- **联动纪律**：M2 落地时，chat 意图相关需求应先读该 spec 并同步更新本表的路由描述。

## 相关文件

- `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` - Agent 架构设计
- `docs/superpowers/specs/2026-09-25-intent-routing-contract-design.md` - 意图路由契约与路由器 spec（M1/M2/M3）
- `AGENTS.md` - 顶层治理（角色协作管线已于 2026-08-03 退役）
- `PRODUCT.md` - 产品需求规格
- `docs/01-PRODUCT/FEATURES.md` - 交互规范
- 各模块 `AGENTS.md` - 技术实现规范

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.1.0 | 2026-05-03 | 初始版本 |
| 1.2.0 | 2026-09-25 | 对齐现状：推理模式区分 shared/androidApp 两套 AiAgentMode 枚举；AiAgentCommand 归属改 `domain/model`；FEATURES.md 章节号换新（相机 §4 冻结/相册 §1）；capability AGENTS.md 标注冻结；删虚构 `AGENTS_SPEC.md`；新增「Chat 意图路由两层架构」节衔接 2026-09-25 契约 spec |
