# PoLang 术语词典 (Glossary)

> **边界声明（Boundary Statement）**
> - 本文档定义 PoLang 项目统一术语，确保 Spec 语义一致性。
> - 技术规范以各模块 `*_TECH_SPEC.md` 为准。
> - 坐标系标准以 [`COORDINATE_SYSTEM.md`](07-STANDARDS/COORDINATE_SYSTEM.md) 为准。

**模块定位**: 统一术语定义与禁用别名  
**主要维护者**: 项目开发者  
**阅读对象**: 项目开发者、AI Agent  
**版本**: 1.1  
**最后更新**: 2026-08-03  

---

## 📋 目录

1. [美颜引擎术语](#1-美颜引擎术语)
2. [帧同步术语](#2-帧同步术语)
3. [Agent 能力术语](#3-agent-能力术语)
4. [相机控制术语](#4-相机控制术语)
5. [性能指标术语](#5-性能指标术语)

---

## 1. 美颜引擎术语

| 术语 | 英文 | 定义 | 禁用别名 |
|------|------|------|---------|
| **大美丽** | Big Beauty | PoLang 自研 OpenGL ES + EGL 美颜引擎 | 美颜引擎、自研引擎 |
| **离屏渲染** | Offscreen Rendering | GPU 离屏纹理，用于拍照后处理 | 离线渲染、后台渲染 |
| **Provider View** | BeautyPreviewProvider | 大美丽提供的预览容器组件 | Provider、预览容器 |
| **降级** | Fallback | 引擎异常时自动回退到基础预览 | 回退、降级策略 |
| **冷却窗口** | Recovery Window | 降级后防止重复尝试的时间窗口（30s） | 冷却期、恢复窗口 |
| **Warm-up** | 预热 | 应用启动时提前初始化美颜引擎 | 预加载、初始化 |
| **EGL Context** | EGL 上下文 | OpenGL ES 图形上下文 | GL 上下文、图形上下文 |
| **Shader Pass** | Shader 渲染通道 | 单个美颜效果的 GPU 着色器处理 | Shader、着色器 |
| **Beauty Parameters** | 美颜参数 | 磨皮、美白、瘦脸等调节参数 | 美颜值、调节参数 |

### 1.1 详细定义

#### 大美丽 (Big Beauty)

**定义**: PoLang 自研的基于 OpenGL ES + EGL 的美颜引擎，提供实时预览与拍照后处理能力。

**核心特性**:
- 完全 GPU 加速，零 CPU 拷贝
- 支持多 Pass 美颜效果组合
- 离屏渲染拍照路径
- 容灾降级机制

**使用场景**:
```kotlin
// ✅ 正确
val beautyEngine = BigBeautyEngine()

// ❌ 错误（歧义）
val beautyEngine = beautyEngine  // 未明确是大美丽还是其他引擎
```

#### 离屏渲染 (Offscreen Rendering)

**定义**: 在内存中的纹理（Texture）上进行 GPU 渲染，而非直接输出到屏幕。

**用途**: 拍照后处理、滤镜合成、妆容持久化。

**示例**:
```kotlin
// 离屏渲染流程
textureFBO.bind()
renderToTexture()  // 渲染到纹理
textureFBO.unbind()
// 将纹理应用到照片
```

#### 降级 (Fallback)

**定义**: 当大美丽引擎初始化或运行时失败，自动切换到基础预览模式（无美颜）。

**触发条件**:
- EGL 初始化失败
- Shader 编译失败
- Surface 绑定超时

**恢复机制**:
- 冷却窗口：30s 内不重复尝试
- 自动重试：冷却到期后触发
- 拍照 fallback：GPU 失败时回退 CPU

---

## 2. 帧同步术语

| 术语 | 英文 | 定义 | 禁用别名 |
|------|------|------|---------|
| **帧同步** | Frame Sync | 人脸检测帧与渲染帧的时间对齐机制 | 同步系统、时序对齐 |
| **FrameId** | 全局帧标识符 | 单调递增的全局帧 ID，用于跨模块时间对齐 | 帧 ID、frame_id |
| **严格缺失** | Strict Missing | 无检测结果 N 帧后强制隐藏妆容的策略 | 缺失隐藏、严格模式 |
| **预测补偿** | Prediction Compensation | 基于运动轨迹预测人脸位置的补偿算法 | 运动预测、预测算法 |
| **妆容甩飞** | Makeup Detachment | 妆容与人脸位置不同步的分离现象 | 妆容滞后、妆容漂移 |
| **悬空残留** | Hover | 人脸出画后妆容仍停留在屏幕上的现象 | 妆容残留、妆容残留 |
| **Detection Queue** | 检测队列 | 人脸检测结果缓冲队列，深度限制 2，超时 200ms | 检测缓冲、结果队列 |
| **Match Engine** | 匹配引擎 | 查询当前渲染帧对应的人脸检测结果 | 匹配器、查询引擎 |

### 2.1 详细定义

#### 帧同步 (Frame Sync)

**定义**: 解决人脸检测频率（~30fps）与渲染频率（60fps）不一致导致的妆容不同步问题。

**核心组件**:
- `FrameId`: 全局单调递增帧标识符
- `FrameSyncManager`: 帧同步管理器
- `DetectionQueue`: 检测队列（📋 设计中，未落地 — 同步检测路径仍在 CameraFrameAnalyzer 中直接调用 detect()）
- `MatchEngine`: 匹配引擎

**工作流程**:
```
检测线程: 检测到人脸 → 携带 FrameId → 存入 DetectionQueue
渲染线程: 查询当前 FrameId → MatchEngine 匹配 → 获取对应检测结果 → 应用妆容
```

#### 严格缺失 (Strict Missing)

**定义**: 当人脸检测连续缺失 N 帧（默认 3 帧）后，强制隐藏妆容，避免"悬空残留"。

**配置**:
```kotlin
data class FrameSyncConfig(
    val syncMode: SyncMode = STRICT,  // STRICT | RELAXED
    val missingThresholdFrames: Int = 3
)
```

**行为**:
- `STRICT`: 缺失超过阈值立即隐藏
- `RELAXED`: 使用预测补偿延长可见性

#### 预测补偿 (Prediction Compensation)

**定义**: 基于最近几帧的人脸位置，通过速度外推预测当前帧的人脸位置。

**算法**:
```kotlin
fun predict(fromFrameId: FrameId, toFrameId: FrameId, maxRatio: Float): Point {
    val recentFrames = motionTracker.getRecentFrames(3)
    val velocity = calculateVelocity(recentFrames)
    val predictedOffset = velocity * (toFrameId - fromFrameId)
    
    // 位移约束：不超过上一帧位移的 150%
    return constrain(predictedOffset, maxRatio)
}
```

**约束**:
- 耗时 ≤ 1ms / 帧
- 位移约束 ≤ 150% 上一帧位移

---

## 3. Agent 能力术语

| 术语 | 英文 | 定义 | 禁用别名 |
|------|------|------|---------|
| **Agent Orchestrator** | 编排器 | Agent 运行时核心，负责 Prompt 构建、LLM 推理、命令路由 | 调度器、管理器 |
| **Capability** | 能力 | 领域功能抽象（相机控制、相册管理等） | 功能、插件 |
| **Capability Registry** | 能力注册表 | 管理所有 Capability 的注册与命令映射 | 能力列表、注册中心 |
| **Scene** | 场景 | Agent 活跃的场景（相机、相册、设置等） | 页面、状态 |
| **Page Context** | 页面上下文 | 页面特定状态数据（当前选中的照片等） | 页面数据、状态 |
| **System Prompt** | 系统提示词 | 引导 LLM 行为的预设指令 | 系统指令、提示 |
| **Function Calling** | 函数调用 | LLM 输出结构化命令的执行模式 | 指令解析、命令调用 |
| **Batch Function Calling** | 批量函数调用 | 单次 LLM 推理输出多个命令 | 批量指令、多命令 |
| **Local LLM Engine** | 本地推理引擎 | 端侧 MNN-LLM 客户端封装 | 本地模型、推理器 |
| **JS Engine** | JS 引擎 | QuickJS 沙箱 + JSBridge，支持端侧 JS 脚本执行（取数 / 图卡 / 能力调度） | JS 沙箱、脚本引擎 |
| **AI 工程师模式** | AI Engineer Mode | Chat 内嵌的远程 Claude Code 通道（chisel 隧道 → 云主机 GLM），含 5 个 app_* MCP 诊断工具 | 工程师通道、诊断模式 |

### 3.1 详细定义

#### Capability（能力）

**定义**: 封装特定领域功能的接口，包含命令定义与执行逻辑。

**核心方法**:
```kotlin
interface Capability {
    val name: String
    val description: String
    fun activeScenes(): List<Scene>
    fun supportedCommands(): List<String>
    suspend fun execute(command: AgentCommand, context: AgentContext, pageContext: PageContext?): Result<AgentAction>
}
```

**示例**:
```kotlin
class CameraCapability : Capability {
    override val name = "camera"
    override val description = "相机控制：拍照、录像、美颜、滤镜"
    
    override fun supportedCommands() = listOf(
        "capture_photo", "adjust_beauty", "switch_filter"
    )
}
```

#### Scene（场景）

**定义**: Agent 当前活跃的业务场景，决定可用 Capability 集合。

**枚举**:
```kotlin
enum class Scene {
    CAMERA,    // 相机页
    GALLERY,   // 相册页
    SETTINGS,  // 设置页
    EDITOR,    // 编辑页
    DEBUG      // 调试页
}
```

**场景切换**:
```kotlin
sceneManager.transitionTo(Scene.GALLERY)
// Agent 自动加载 GalleryCapability + NavigationCapability
```

---

## 4. 相机控制术语

| 术语 | 英文 | 定义 | 禁用别名 |
|------|------|------|---------|
| **快门反馈** | Shutter Feedback | 触感 + 音效 + 视觉三位一体反馈 | 快门提示、快门响应 |
| **人脸十字星** | Face Crosshair | 锁定人脸且设备运动时显示的辅助标记 | 对焦框、锁定标记 |
| **美颜总开关** | Beauty Master Switch | 控制美颜系统开启/关闭的主开关 | 美颜开关、美颜总控 |
| **画幅比例** | Aspect Ratio | 照片宽高比（4:3、16:9、FULL） | 比例、裁剪比例 |
| **场景模式** | Scene Mode | 拍摄场景预设（无/夜景/月亮；手动入口已下线，仅 Agent/语音指令可切换） | 模式、拍摄模式 |

### 4.1 详细定义

#### 快门反馈 (Shutter Feedback)

**定义**: 按下快门时的三位一体反馈机制，确保用户感知明确。

**组成**:
1. **触感**: 50ms 短震动
2. **音效**: 快门声同步播放
3. **视觉**: 50ms 黑场闪烁 + 按钮缩放

**实现**:
```kotlin
object ShutterFeedback {
    fun trigger() {
        vibrate(50)           // 触感
        playSound()           // 音效
        flashScreen()         // 视觉
    }
}
```

#### 美颜总开关 (Beauty Master Switch)

**定义**: 控制美颜系统是否运行的主开关，影响人脸检测是否启动。

**行为规则**:
- **默认状态**: 关闭（进入相机时人脸检测不运行，节省性能）
- **开启方式**:
  - 用户点击美颜面板开关
  - 调节任意美颜参数时自动开启（`resolveNextBeautySettings`）
- **关闭方式**:
  - 用户手动关闭开关
  - 所有美颜参数归零时自动关闭

**差异**:
- **关闭时**: 跳过人脸检测，仅支持滤镜/调色/风格特效
- **开启时**: 启用人脸检测，支持全部功能（含瘦脸/大眼/唇色/腮红）

---

## 5. 性能指标术语

| 术语 | 英文 | 定义 | 单位 | 红线 | 目标 |
|------|------|------|------|------|------|
| **冷启动** | Cold Startup | 从进程启动到首帧预览显示 | ms | ≤ 500 | ≤ 400 |
| **预览帧率** | Preview FPS | 预览画面每秒帧数 | fps | ≥ 30 | ≥ 55 |
| **单帧耗时** | Frame Processing Time | 单帧美颜处理耗时 | ms | ≤ 16 | ≤ 12 |
| **参数响应** | Parameter Latency | 参数调节到画面变化的延迟 | ms | ≤ 100 | ≤ 50 |
| **拍照后处理** | Photo Post-processing | 1080p/4K 照片 GPU 处理耗时 | ms | ≤ 300/800 | ≤ 200/600 |
| **崩溃率** | Crash Rate | 应用崩溃概率 | % | ≤ 0.1 | ≤ 0.05 |
| **ANR 率** | ANR Rate | 应用无响应概率 | % | ≤ 0.1 | ≤ 0.05 |

### 5.1 测量方法

#### 冷启动

```bash
adb shell am start -W com.mamba.picme/com.mamba.picme.ui.MainActivity
# 输出：mTotalTime: 450ms
```

#### 预览帧率

```kotlin
// 调试浮层显示
data class BeautyPerfStats(
    val fps: Float,
    val processingMs: Float,
    val nullFrames: Int
)
```

#### 单帧耗时

```kotlin
// 自定义计时器
PerformanceTracker.start("beauty_render")
renderFrame()
val duration = PerformanceTracker.end("beauty_render")  // ms
```

---

## 附录：术语使用规范

### ✅ 正确使用

```kotlin
// ✅ 明确使用术语
class BigBeautyEngine {
    fun warmUp() { /* 预热 */ }
    fun fallback(reason: String) { /* 降级 */ }
}

// ✅ 帧同步相关
val frameId = FrameId.next()
val syncStatus = frameSyncManager.query(frameId)
```

### ❌ 禁止使用

```kotlin
// ❌ 使用模糊描述
class BeautyEngine {  // 未明确是大美丽
    func init() { /* 初始化 */ }
    func retry() { /* 重试 */ }
}

// ❌ 未标注坐标系
val leftEye = landmarks[52]  // 左眼？图像左侧？被拍摄者左眼？
```

---

> **参考文档**:
> - [COORDINATE_SYSTEM.md](07-STANDARDS/COORDINATE_SYSTEM.md) — 坐标系标准
> - [NFR_SPEC.md](01-PRODUCT/NFR_SPEC.md) — 非功能性需求规格
> - [AGENT_ARCHITECTURE.md](02-ARCHITECTURE/AGENT_ARCHITECTURE.md) — Agent 架构设计
