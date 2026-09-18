# Capability 业务能力层

## 概述

Capability 是 PoLang 的**独立业务能力接口层**，提供标准化的能力调用协议。

**核心特性**：
- ✅ **独立运行** - 不依赖 LLM，按协议发送指令即可执行
- ✅ **类型安全** - 所有参数和返回值都有明确类型
- ✅ **可复用** - 生产和测试环境共用同一套能力接口

## 目录结构

```
androidApp/src/main/java/com/mamba/picme/capability/
├── README.md                    # 本文档
├── BeautyCapability.kt          # 美颜业务能力
└── (未来扩展)
    ├── CameraCapability.kt      # 相机控制能力
    ├── GalleryCapability.kt     # 相册管理能力
    └── CaptureCapability.kt     # 拍照能力
```

## 使用方式

### 1. 创建 Capability 实例

```kotlin
val beautyCapability = BeautyCapability(
    beautyProcessor = appContainer.imageProcessor,
    faceDetector = appContainer.faceDetector
)
```

### 2. 调用能力接口

```kotlin
// 调整磨皮参数
val result = beautyCapability.adjustSmoothing(smoothness = 80f)

if (result.success) {
    val processedBitmap = result.data
    // 处理结果...
} else {
    Log.e("Beauty", "Failed: ${result.error}")
}
```

### 3. 批量测试

```kotlin
val paramSets = listOf(
    BeautySettings(smooth = 50f),
    BeautySettings(smooth = 70f),
    BeautySettings(smooth = 90f)
)

val results = beautyCapability.batchTest(paramSets)
results.forEach { result ->
    println("Success: ${result.success}, Error: ${result.error}")
}
```

## 设计原则

### 1. 显式优于隐式

所有参数和返回值都有明确类型，无需猜测：

```kotlin
// ✅ 好：类型清晰
fun adjustSmoothing(smoothness: Float): CapabilityResult<Bitmap>

// ❌ 坏：类型模糊
fun adjust(params: Map<String, Any>)
```

### 2. 结构化输出

返回 `CapabilityResult` 包含完整的状态信息：

```kotlin
data class CapabilityResult<out T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null,
    val stackTrace: String? = null
)
```

### 3. 不自动修复

Capability **只负责执行**，不负责自动修复：

```kotlin
// ✅ 正确：仅记录错误日志
fun adjustSmoothing(...): CapabilityResult<Bitmap> = runCatching {
    // 执行逻辑
}.getOrElse { exception ->
    Log.e(TAG, "Failed: ${exception.message}", exception)
    CapabilityResult.failure(...)
}

// ❌ 错误：自动尝试修复
fun adjustSmoothing(...) {
    try {
        // 执行
    } catch (e: Exception) {
        autoFix(e)  // 不应该在这里自动修复
    }
}
```

## 与 LLM 协作流程

```
用户/LLM → 发送命令 → Capability 执行 → 返回结果 → LLM 分析
                                              ↓
                                         生成修复方案
                                              ↓
                                         人工确认/提交 PR
```

**关键点**：
- Capability **不主动调用 LLM**
- Capability **不自动修复错误**
- LLM 通过读取 `CapabilityResult.error` 和 `stackTrace` 分析原因
- LLM 生成修复代码后由人工确认或自动提交 PR

## 扩展新能力

### 步骤 1: 定义能力接口

```kotlin
class MyCapability(
    private val dependency1: Dependency1,
    private val dependency2: Dependency2
) {
    fun executeCommand(params: CommandParams): CapabilityResult<ResultType> {
        // 实现逻辑
    }
}
```

### 步骤 2: 在 DI 容器中注册

```kotlin
interface AppContainer {
    val myCapability: MyCapability
}

class AppContainerImpl : AppContainer {
    override val myCapability by lazy {
        MyCapability(dependency1, dependency2)
    }
}
```

### 步骤 3: 编写测试用例

```kotlin
class MyCapabilityTest {
    @Test
    fun testExecuteCommand() = runBlocking {
        val capability = MyCapability(mockDep1, mockDep2)
        val result = capability.executeCommand(testParams)
        
        assertTrue(result.success)
        assertEquals(expectedValue, result.data)
    }
}
```

## 注意事项

1. **线程安全** - Capability 应在调用方指定的线程中执行
2. **生命周期管理** - 依赖项的生命周期需与 Capability 对齐
3. **错误处理** - 所有异常应捕获并转换为 `CapabilityResult.failure`
4. **性能监控** - 关键操作应记录耗时指标到 `CapabilityResult.metrics`（可选）

---

**维护者**: RD Team  
**最后更新**: 2026-05-29
