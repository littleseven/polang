---
name: mnn-landmark-diagnosis
description: |
  诊断和修复 MNN 推理引擎在人脸关键点检测中的对齐问题。
version: 1.2.1
created: 2026-05-03
updated: 2026-09-25
maintainer: "[RD] 全栈工程师"
tags:
  - mnn
  - landmark
  - inference
  - alignment
  - nchw
---


# MNN Landmark 诊断与修复 Skill

> **定位**：诊断和修复 MNN 推理引擎在人脸关键点检测中的对齐问题。
> **触发时机**：用户报告人脸关键点偏移、对齐错误或 MNN 推理结果异常时自动启用。


## 触发条件

当以下情况出现时自动应用本 Skill：
- MNN 路径关键点抖动、漂移或位置错误
- MediaPipe 稳定但 MNN 输出不一致
- 新推理引擎接入时的对齐验证
- `copyFromHostTensor` / `copyToHostTensor` 相关数据异常
- 提到 NCHW/NHWC、DimensionType、CAFFE/TENSORFLOW 布局问题

---

## 诊断检查清单

### Phase 1: 环境确认

```markdown
- [ ] 确认 MNN 和 MediaPipe 检测器均已初始化
- [ ] 确认 INPUT_SIZE 一致（MNN vs MediaPipe）
- [ ] 确认模型文件存在且非空（.mnn / .tflite）
- [ ] 确认 GPU/CPU 模式配置正确
```

### Phase 2: 分层诊断（五层框架）

```
Layer 1: 输入预处理层  → C++ detect() 方法
Layer 2: 输出读取层    → copyToHostTensor 维度类型
Layer 3: 坐标变换层    → Kotlin prepareInputBitmap
Layer 4: 坐标解析层    → parseLandmarks ([-1,1] → 像素 → 归一化)
Layer 5: 点序映射层    → MNN 原生 106 点序（无重映射表）
```

**诊断顺序**：自上而下建立对比测试 → 自下而上逐层定位

---

## 分层排查命令

### Layer 1: 输入预处理排查

**关键检查点**：
1. 维度类型是否硬编码？
```cpp
// 错误：硬编码 CAFFE
MNN::Tensor tmpInput(inputTensor_, MNN::Tensor::DimensionType::CAFFE);

// 正确：动态获取
MNN::Tensor::DimensionType inputDimType = inputTensor_->getDimensionType();
MNN::Tensor tmpInput(inputTensor_, inputDimType);
```

2. 数据布局是否匹配维度类型？
```cpp
bool isNCHW = (inputDimType == MNN::Tensor::DimensionType::CAFFE);
if (isNCHW) {
    inputData[c * totalPixels + i] = val;  // NCHW
} else {
    inputData[i * 3 + c] = val;            // NHWC
}
```

3. 归一化参数是否正确？
```cpp
// 检测内置归一化节点
hasBuiltInNormalization_ = (modelContent.find("_minusscalar0") != std::string::npos) &&
                           (modelContent.find("_mulscalar0") != std::string::npos);
float normMean = hasBuiltInNormalization_ ? 0.0f : 127.5f;
float normStd = hasBuiltInNormalization_ ? 1.0f : 128.0f;
```

**诊断日志**：
```cpp
LOGD("[Diag] Input tensor dimension type: %d (CAFFE=0, TENSORFLOW=1)",
     (int)inputTensor_->getDimensionType());
LOGD("[Diag] First 10 pixels: [%.2f,%.2f,%.2f] ...", inputData[0], inputData[1], inputData[2]);
```

### Layer 2: 输出读取排查

**关键检查点**：
```cpp
// 输出张量同样需要动态维度类型
MNN::Tensor::DimensionType outputDimType = output->getDimensionType();
MNN::Tensor tmpOutput(output, outputDimType);
output->copyToHostTensor(&tmpOutput);
```

### Layer 3: 坐标变换排查

> ONNX 检测器（`buildLooseFaceCrop`）已移除，现役仅 MNN 路径。

**关键点**：
| 项目 | MNN |
|------|-----|
| crop 方式 | `prepareInputBitmap` |
| transformMatrix | `inputScale, 0, INPUT_SIZE/2 - centerX*inputScale` |
| inverseMatrix | `transformMatrix.invert()` |

### Layer 4: 坐标解析排查

**标准公式**：
```kotlin
// [-1, 1] → INPUT_SIZE 像素坐标
val pixelX = (modelOutputX + 1f) * halfInputSize
val pixelY = (modelOutputY + 1f) * halfInputSize
// 逆变换映射回原始图像
inverseTransform.mapPoints(mappedPoint)
// 归一化到 [0, 1]
val normalizedX = mappedPoint[0] / bitmapWidth
```

### Layer 5: 点序映射排查

**验证方法**：MNN 106 关键点为原生点序输出，无重映射表（历史 ONNX `FULL_REMAP` 已随 ONNX 检测器移除）。若涉及 MediaPipe 468→106 跨源映射，见 [mediapipe-landmark-mapping](/mediapipe-landmark-mapping)（`MediaPipe468Adapter`）。

---

## 修复模板

### 修复 1: 维度类型动态适配

```cpp
// mnn_face_detector.cpp detect() 方法

// [关键修复] 使用与输入张量相同的维度类型
MNN::Tensor::DimensionType inputDimType = inputTensor_->getDimensionType();
MNN::Tensor tmpInput(inputTensor_, inputDimType);
float *inputData = tmpInput.host<float>();
bool isNCHW = (inputDimType == MNN::Tensor::DimensionType::CAFFE);

// 根据维度类型填充数据
for (int i = 0; i < totalPixels; i++) {
    for (int c = 0; c < 3; c++) {
        float val = imageData[i * 3 + c];
        if (isNCHW) {
            inputData[c * totalPixels + i] = (val - normMean) / normStd;
        } else {
            inputData[i * 3 + c] = (val - normMean) / normStd;
        }
    }
}

// [关键修复] 输出张量同样使用动态维度类型
MNN::Tensor::DimensionType outputDimType = output->getDimensionType();
MNN::Tensor tmpOutput(output, outputDimType);
output->copyToHostTensor(&tmpOutput);
```

### 修复 2: 内置归一化检测

```cpp
// 在 load() 方法中检测
std::ifstream modelCheck(modelPath.c_str(), std::ios::binary);
if (modelCheck.is_open()) {
    std::string modelContent((std::istreambuf_iterator<char>(modelCheck)),
                              std::istreambuf_iterator<char>());
    hasBuiltInNormalization_ = (modelContent.find("_minusscalar0") != std::string::npos) &&
                               (modelContent.find("_mulscalar0") != std::string::npos);
    modelCheck.close();
}

// 在 detect() 方法中使用
float normMean = hasBuiltInNormalization_ ? 0.0f : 127.5f;
float normStd = hasBuiltInNormalization_ ? 1.0f : 128.0f;
```

### 修复 3: INPUT_SIZE 对齐

```kotlin
// MnnLandmarkDetector.kt
companion object {
    private const val INPUT_SIZE = 192  // 与 MNN 2D106 检测器（MnnLandmarkDetector）保持一致
}
```

### 修复 4: 检测器初始化

```kotlin
// FaceDetectorManager.kt
init {
    try {
        mnnLandmarkDetector = MnnLandmarkDetector(context, requireGpu = true)
        Log.i(TAG, "MNN Landmark detector initialized")
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize MNN Landmark detector", e)
        mnnLandmarkDetector = null
    }
}
```

---

## 验证流程

### Step 1: 编译安装

```bash
./gradlew :engines:beauty-engine:assembleDebug
./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/polang-debug.apk
```

### Step 2: 启动对比测试

> 注：ONNX（InsightFace 2D106）检测器已从 androidApp 移除，仓库不再内置 MNN↔ONNX 并行对比埋点。若需以原始 ONNX 模型作外部基准校验 MNN 输出，需在 `MnnLandmarkDetector` / `FaceDetectorManager` 中**临时**加回并行推理与日志（自定义 tag，如 `MNN vs ONNX`），再按下方流程收集：

```bash
adb logcat -c
adb shell am start -n com.mamba.picme/.MainActivity
sleep 15
adb logcat -d | grep "MNN vs ONNX"   # 仅当已临时加回对比埋点时才有输出
```

### Step 3: 验收标准

| 指标 | 通过标准 |
|------|----------|
| 平均差异 | < 0.01 (归一化坐标) |
| 最大差异 | < 0.05 (归一化坐标) |
| 像素误差 | < 3px (@192x192) |
| 帧间稳定性 | 连续 10 帧标准差 < 0.01 |

### Step 4: Dev Loop 集成

```bash
./scripts/auto-dev-loop.sh
```

检查输出报告中的：
- 编译结果
- 安装状态
- 设备截屏（关键点覆盖层）
- PoLang 日志（检测耗时、稳定性）

---

## 快速参考

### MNN 维度类型对照表

| 枚举值 | 名称 | 布局 | 数据填充方式 |
|--------|------|------|-------------|
| 0 | CAFFE | NCHW | `inputData[c * H * W + h * W + w]` |
| 1 | TENSORFLOW | NHWC | `inputData[h * W * C + w * C + c]` |
| 2 | CAFFE_C4 | NCHW4 | 通道对齐到 4 的倍数 |

### 常见错误症状与根因

| 症状 | 根因 | 修复 |
|------|------|------|
| 输出完全错误但稳定 | 维度类型不匹配 | 动态获取维度类型 |
| 输出接近正确但有偏差 | 归一化参数错误 | 检测内置归一化节点 |
| 帧间抖动大 | 重复预处理 / 输入尺寸不一致 | 统一 INPUT_SIZE，避免重复 letterbox |
| 部分点正确部分错误 | 点序映射错误 | MNN 原生 106 点序无重映射；跨源核对 `MediaPipe468Adapter` |

---

## 附加资源

- 详细技术文档: [docs/03-TECHNICAL-SPECS/MNN_LANDMARK_DIAGNOSIS.md](docs/03-TECHNICAL-SPECS/MNN_LANDMARK_DIAGNOSIS.md)

## 相关文件

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.1.0 | 2026-05-03 | 初始版本 |
| 1.2.0 | 2026-07 | ONNX 对比排查节自注 ONNX 检测器移除 |
| 1.2.1 | 2026-09-25 | 分层框架同步 ONNX 移除现状：删 `buildLooseFaceCrop`/`FULL_REMAP` 死符号，Layer 5 改为原生 106 点序 + 跨源指向 MediaPipe468Adapter |
