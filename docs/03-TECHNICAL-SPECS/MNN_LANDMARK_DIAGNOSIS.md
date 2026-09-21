# MNN Landmark 检测路径系统性诊断与修复指南

> **历史文档（归档）**：本文档记录 MNN Landmark 检测路径（2d106det）的对齐排查过程，**仅作诊断方法论参考，不再随代码更新**（2026-09-20 标注）。ONNX Runtime 已于 2026-05 完全移除，文中引用的 `InsightFace2D106Detector.kt` 等历史类已删除（git 历史可查）。
> 现役架构以 [`FACE_DETECTION_ENGINE_ARCHITECTURE.md`](./FACE_DETECTION_ENGINE_ARCHITECTURE.md) 与 [`FACE_LANDMARKS.md`](./FACE_LANDMARKS.md) 为准。
> 适用场景：MNN 引擎关键点抖动/漂移诊断、新引擎接入时的对齐验证方法论参考。

---

## 1. 问题现象

| 路径 | 表现 |
|------|------|
| 基准引擎 | 106 点关键点稳定、位置准确、帧间一致 |
| MNN (问题) | 关键点完全错误、位置漂移、输出不稳定 |

**初步判断**：非模型本身问题，而是推理链路中某层处理逻辑与基准引擎不一致。

---

## 2. 系统性诊断方法论（五层框架）

参照 ROI 检测路径修复经验，建立五层诊断框架：

```
Layer 5: 点序映射层    → MnnLandmarkAdapter.kt (FULL_REMAP)
Layer 4: Kotlin 变换层 → MnnLandmarkDetector.kt (parseLandmarks)
Layer 3: 坐标变换层    → prepareInputBitmap / buildLooseFaceCrop (Matrix)
Layer 2: 输出读取层    → mnn_face_detector.cpp (detect, copyToHostTensor)
Layer 1: 输入预处理层  → mnn_face_detector.cpp (detect, 归一化/布局)
```

**诊断原则**：自上而下建立对比测试，自下而上逐层定位问题。

---

## 3. 诊断步骤详解

### Step 1: 建立对比测试（并行输出）

在 `FaceDetectorManager` 中同时调用 MNN 和基准引擎路径，输出每点坐标差异：

```kotlin
// 伪代码：对比测试框架
fun compareMnnVsBaseline(bitmap, roi): DiffReport {
    val mnn = mnnLandmarkDetector.detect(bitmap, roi)
    val baseline = baselineDetector.detect(bitmap, roi)
    return calculateDiff(mnn, baseline) // 逐点计算 |mnn - baseline|
}
```

**关键指标**：
- 平均差异 (avgDiff)
- 最大差异 (maxDiff) 及对应点索引
- 帧间稳定性 (连续 10 帧的标准差)

### Step 2: 定位问题层级

通过对比测试日志，判断问题所在层级：

| 层级 | 判断方法 | 本次问题 |
|------|----------|----------|
| Layer 1 输入预处理 | 对比输入张量前 10 像素值 | **维度类型错误** |
| Layer 2 输出读取 | 对比原始模型输出 (未变换前) | 修复 Layer 1 后一致 |
| Layer 3 坐标变换 | 对比 transformMatrix / inverseMatrix | 一致 |
| Layer 4 坐标解析 | 对比 [-1,1] → 像素 → 归一化逻辑 | 一致 |
| Layer 5 点序映射 | 对比 FULL_REMAP 映射表 | 一致 |

### Step 3: 关键比对维度

#### 3.1 输入预处理一致性

| 维度 | 基准引擎 | MNN (修复前) | MNN (修复后) |
|------|----------|--------------|--------------|
| INPUT_SIZE | 192 | 128 | **192** |
| 归一化方式 | mean=0, std=1 (内置归一化) | pixel/255.0 | **mean=0, std=1** |
| 数据布局 | CHW | CHW (硬编码) | **动态适配** |
| 维度类型 | NCHW | NCHW (硬编码 CAFFE) | **动态获取** |
| Letterbox | Kotlin 层 crop 到 192x192 | C++ 层重复 letterbox | **直接归一化** |

#### 3.2 模型输出数据读取

**核心发现**：MNN 输入张量的 `getDimensionType()` 返回 `TENSORFLOW` (NHWC=1)，但代码硬编码使用 `CAFFE` (NCHW=0)。

```cpp
// 错误代码（修复前）
MNN::Tensor tmpInput(inputTensor_, MNN::Tensor::DimensionType::CAFFE); // 硬编码！
inputData[c * totalPixels + i] = ...; // 按 NCHW 填充

// 正确代码（修复后）
MNN::Tensor::DimensionType inputDimType = inputTensor_->getDimensionType(); // 动态获取
MNN::Tensor tmpInput(inputTensor_, inputDimType);
if (inputDimType == CAFFE) {
    inputData[c * totalPixels + i] = ...; // NCHW
} else {
    inputData[i * 3 + c] = ...; // NHWC
}
```

**影响**：`copyFromHostTensor()` 时会根据维度类型进行数据重排，导致 RGB 通道完全错位，模型接收到错误的输入。

#### 3.3 坐标变换矩阵

基准引擎和 MNN 的 Kotlin 层变换矩阵构造完全一致：

```kotlin
val inputScale = INPUT_SIZE / looseSize
val transformMatrix = Matrix()
transformMatrix.setValues(floatArrayOf(
    inputScale, 0f, INPUT_SIZE / 2f - centerX * inputScale,
    0f, inputScale, INPUT_SIZE / 2f - centerY * inputScale,
    0f, 0f, 1f
))
```

#### 3.4 坐标解析逻辑

两者完全一致：
```kotlin
// [-1, 1] → INPUT_SIZE 像素坐标
mappedPoint[0] = (x + 1f) * halfInputSize
mappedPoint[1] = (y + 1f) * halfInputSize
// 逆变换矩阵映射回原始图像
crop.inverseTransform.mapPoints(mappedPoint)
// 归一化到 [0, 1]
result[i * 2] = mappedPoint[0] / bitmapWidth
```

---

## 4. 发现的具体问题及修复方案

### 问题 1: 检测器未初始化

**文件**: `FaceDetectorManager.kt`
**现象**: `mnnLandmarkDetector` 声明但从未赋值
**修复**:
```kotlin
init {
    try {
        mnnLandmarkDetector = MnnLandmarkDetector(context, requireGpu = true)
    } catch (e: Exception) {
        mnnLandmarkDetector = null
    }
}
```

### 问题 2: INPUT_SIZE 不一致

**文件**: `MnnLandmarkDetector.kt`
**现象**: MNN 用 128，基准引擎用 192
**修复**:
```kotlin
private const val INPUT_SIZE = 192  // 对齐基准引擎
```

### 问题 3: C++ 层重复预处理

**文件**: `mnn_face_detector.cpp`
**现象**: Kotlin 层已 crop 到 192x192，C++ 层又做 letterbox
**修复**: 当输入尺寸等于模型输入尺寸时，直接归一化：
```cpp
if (width == inputSize_ && height == inputSize_) {
    // 直接归一化，不做 letterbox
}
```

### 问题 4: 归一化方式不匹配

**文件**: `mnn_face_detector.cpp`
**现象**: 基准引擎检测到内置归一化节点 (mean=0, std=1)，MNN 做 pixel/255.0
**修复**: 检测模型是否包含内置归一化节点：
```cpp
std::ifstream modelCheck(modelPath.c_str(), std::ios::binary);
std::string modelContent(...);
hasBuiltInNormalization_ = (modelContent.find("_minusscalar0") != std::string::npos) &&
                           (modelContent.find("_mulscalar0") != std::string::npos);
float normMean = hasBuiltInNormalization_ ? 0.0f : 127.5f;
float normStd = hasBuiltInNormalization_ ? 1.0f : 128.0f;
```

### 问题 5: 输入张量维度类型错误（根本原因）

**文件**: `mnn_face_detector.cpp`
**现象**: 硬编码 `DimensionType::CAFFE`，但模型实际为 `TENSORFLOW`
**修复**: 动态获取维度类型并适配数据布局（详见 3.2 节）

**输出张量同样需要修复**：
```cpp
MNN::Tensor::DimensionType outputDimType = output->getDimensionType();
MNN::Tensor tmpOutput(output, outputDimType);
output->copyToHostTensor(&tmpOutput);
```

---

## 5. 验证标准与结果

### 5.1 定量指标

| 指标 | 目标 | 实际结果 |
|------|------|----------|
| 单点像素误差 | < 3px | **< 0.25px** (@192x192) |
| 归一化坐标平均差异 | < 0.01 | **0.0001** |
| 归一化坐标最大差异 | < 0.05 | **0.0013** |
| 帧间稳定性 | < 0.01 | **< 0.01** |

### 5.2 验证方法

```bash
# 1. 编译安装
./gradlew :androidApp:assembleDebug && adb install -r androidApp/build/outputs/apk/debug/polang-debug.apk

# 2. 启动应用并收集日志
adb logcat -c && adb shell am start -n com.mamba.picme/.MainActivity
sleep 10 && adb logcat -d | grep "MNN vs Baseline"

# 3. 使用 auto-dev-loop 一键验证
./scripts/auto-dev-loop.sh
```

### 5.3 GPU/CPU 双模式验证

- **GPU 模式**: Vulkan 后端，推理时间 ~900ms（首次），~600ms（后续）
- **CPU 模式**: 4 线程，推理时间待测试
- **稳定性**: 连续运行 100+ 帧，无漂移、无抖动

---

## 6. 经验总结与检查清单

### 6.1 新引擎接入检查清单

```markdown
- [ ] INPUT_SIZE 与基准引擎一致
- [ ] 检测器在 Manager 中正确初始化
- [ ] 预处理逻辑（crop/letterbox/归一化）与基准一致
- [ ] 模型内置归一化节点检测与处理
- [ ] 输入张量维度类型动态获取（非硬编码）
- [ ] 输出张量维度类型动态获取
- [ ] 数据布局根据维度类型正确填充（NCHW vs NHWC）
- [ ] 坐标变换矩阵与基准一致
- [ ] 坐标解析逻辑（[-1,1] → 像素 → 归一化）与基准一致
- [ ] 点序映射表正确（如需 remap）
- [ ] 建立 MNN vs 基准引擎 并行对比测试
- [ ] 验证单点像素误差 < 3px
- [ ] 验证帧间稳定性
- [ ] 验证 GPU/CPU 双模式
```

### 6.2 关键教训

1. **维度类型是隐形杀手**：模型转换后维度类型可能从 NCHW 变为 NHWC，硬编码 CAFFE 会导致数据完全错位。
2. **内置归一化需显式检测**：不能假设所有模型都需要外部归一化，需检测 `_minusscalar0` / `_mulscalar0` 节点。
3. **分层对比是最高效的方法**：先确认输入数据一致，再确认输出数据一致，最后确认坐标变换一致，可快速定位问题层级。
4. **并行对比测试是验证金标准**：同时运行两条路径，逐点对比差异，量化修复效果。

---

## 7. 相关文件

| 文件 | 作用 |
|------|------|
| `engines/beauty-engine/src/main/cpp/mnn_face_detector.cpp` | MNN C++ 推理核心（修复主文件） |
| `engines/beauty-engine/src/main/java/.../MnnLandmarkDetector.kt` | MNN Kotlin 层（INPUT_SIZE 修复） |
| `engines/beauty-engine/src/main/java/.../FaceDetectorManager.kt` | 检测器管理（初始化修复） |
| `engines/beauty-engine/src/main/java/.../InsightFace2D106Detector.kt` | 基准实现（历史 ONNX，已移除） |
| `engines/beauty-engine/src/main/java/.../MnnLandmarkAdapter.kt` | 点序映射层 |

---

*文档版本: 1.0*
*最后更新: 2026-05-23*
*作者: PoLang AI Agent*
