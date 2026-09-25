---
name: mnn-integration
description: |
  MNN 推理引擎接入专家。预防 AI 在接入 MNN 模型（含 LLM）时重复犯已验证过的错误，涵盖模型加载、维度类型、JNI 桥接、LLM 推理与线程安全。
version: 1.0.1
created: 2026-05-26
updated: 2026-09-25
maintainer: "[RD] 全栈工程师"
tags:
  - mnn
  - inference
  - model
  - android
  - jni
  - llm
  - opencl
  - dimension-type
---


# MNN 集成专家 (MNN Integration Expert)

> **定位**：预防 AI 在接入 MNN 模型（CV 检测 + LLM）时重复犯已验证过的错误。
> **触发时机**：接入任何新 MNN 模型、配置 OpenCL GPU、处理维度类型、JNI 桥接、LLM 推理时。

---

## 接入前必须回答的 6 个问题

| # | 问题 | 常见错误 | 验证方式 |
|---|------|---------|---------|
| 1 | **模型来源** | 直接使用未经转换的 ONNX/PyTorch 模型 | 必须通过 `MNNConvert` 转换，确认 `.mnn` 文件有效 |
| 2 | **维度类型** | 硬编码 `CAFFE` (NCHW) 而模型实际为 `TENSORFLOW` (NHWC) | 调用 `inputTensor_->getDimensionType()` 动态获取 |
| 3 | **内置归一化** | 重复归一化或漏做归一化 | 检测模型二进制中是否包含 `_minusscalar0` / `_mulscalar0` 节点 |
| 4 | **OpenCL 后端** | 未显式指定 `MNN_FORWARD_OPENCL` 或忽略 fallback | 配置 `ScheduleConfig.type`，并准备 CPU 降级路径 |
| 5 | **动态输入尺寸** | ONNX 转换后输入维度为 `-1`，未 reshape | 检查 `inputTensor_->height() <= 0` 并调用 `resizeTensor` |
| 6 | **LLM 两阶段调用** | 直接调用 `generate()` 跳过 prefill | 必须先 `response(..., 0)` 再循环 `generate(1)` |

---

## 核心接入流程

### Phase 1: 模型加载与初始化

```cpp
// 1. 创建解释器
interpreter_.reset(MNN::Interpreter::createFromFile(modelPath.c_str()));

// 2. 配置调度器
MNN::ScheduleConfig config;
config.numThread = 4;
config.type = useGpu ? MNN_FORWARD_OPENCL : MNN_FORWARD_CPU;

// 3. 创建会话
session_ = interpreter_->createSession(config);

// 4. 获取输入张量
inputTensor_ = interpreter_->getSessionInput(session_, inputName.c_str());

// 5. [关键] 处理动态输入尺寸
if (inputTensor_->height() <= 0 || inputTensor_->width() <= 0) {
    interpreter_->resizeTensor(inputTensor_, {1, 3, inputSize, inputSize});
    interpreter_->resizeSession(session_);
}
```

**检查项**：
- [ ] `createFromFile` 返回非 null
- [ ] `createSession` 返回非 null（GPU 不支持时会失败）
- [ ] 动态输入已 reshape 为固定尺寸
- [ ] 输入/输出张量 shape 已打印确认

### Phase 2: 维度类型动态适配（最关键）

```cpp
// [关键] 动态获取维度类型，禁止硬编码 CAFFE
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

inputTensor_->copyFromHostTensor(&tmpInput);
```

**输出张量同样需要动态维度类型**：
```cpp
MNN::Tensor::DimensionType outputDimType = output->getDimensionType();
MNN::Tensor tmpOutput(output, outputDimType);
output->copyToHostTensor(&tmpOutput);
```

### Phase 3: 内置归一化检测

```cpp
// 在 load() 中检测模型是否包含内置归一化节点
std::ifstream modelCheck(modelPath.c_str(), std::ios::binary);
if (modelCheck.is_open()) {
    std::string modelContent((std::istreambuf_iterator<char>(modelCheck)),
                              std::istreambuf_iterator<char>());
    hasBuiltInNormalization_ = (modelContent.find("_minusscalar0") != std::string::npos) &&
                               (modelContent.find("_mulscalar0") != std::string::npos);
    modelCheck.close();
}

// 在 detect() 中使用
float normMean = hasBuiltInNormalization_ ? 0.0f : 127.5f;
float normStd = hasBuiltInNormalization_ ? 1.0f : 128.0f;
```

### Phase 4: LLM 推理（MNN-LLM）

```cpp
// JNI 桥接中的标准两阶段调用模式
std::ostringstream oss;
{
    std::lock_guard<std::mutex> lock(g_llm_mutex);
    // Phase 1: prefill
    llm->response(promptStr, &oss, nullptr, 0);

    // Phase 2: 逐 token 生成
    int current_size = 0;
    while (current_size < maxNewTokens) {
        llm->generate(1);
        current_size++;
        if (llm->stoped()) break;
    }
}
```

**LLM 关键约束**：
- 必须加互斥锁保护（`std::mutex`），MNN-LLM 非线程安全
- `response(..., 0)` 的最后一个参数必须为 `0`（表示不生成，只做 prefill）
- 使用 `ChatMessages` 支持 system prompt + user prompt 多轮对话
- 模型目录需包含 `config.json` + `llm.mnn`（或 `llm.mnn.weight`）

---

## CMake 配置要点

```cmake
# MNN 头文件
include_directories(${MNN_ROOT_DIR}/include)

# MNN 核心库
add_library(mnn SHARED IMPORTED)
set_target_properties(mnn PROPERTIES IMPORTED_LOCATION ${MNN_LIB_DIR}/libMNN.so)

# MNN OpenCL GPU 后端（可选）：libMNN_CL.so 提供 OpenCL 后端
# 检测到则加入链接列表，未检测到自动回落 CPU-only
# set(MNN_CL_SO_PATH "${MNN_LIB_DIR}/libMNN_CL.so")

# libllm.so / libMNN_Express.so：统一构建下符号已并入 libMNN.so，不再单独链接
# （仅当检测到独立 .so 时按需处理，见 engines/agent-native/src/main/cpp/CMakeLists.txt）

# 链接（实际 target 名为 agent_native，非 picme_native）
target_link_libraries(agent_native mnn ${OPENCL_LIBS} ...)
```

> 真实参考实现：`engines/agent-native/src/main/cpp/CMakeLists.txt`（target `agent_native`）；运行时 .so 在 `engines/mnn-core/src/main/jniLibs/arm64-v8a/`（`libMNN.so` + `libOpenCL.so`）。

**ABI 要求**：MNN 库必须与 `ANDROID_ABI` 匹配（arm64-v8a / armeabi-v7a）。

---

## 常见错误模式清单

### ❌ 错误模式 A：硬编码维度类型

```cpp
// ❌ 错误：硬编码 CAFFE，但模型实际为 TENSORFLOW
MNN::Tensor tmpInput(inputTensor_, MNN::Tensor::DimensionType::CAFFE);
inputData[c * totalPixels + i] = val;  // NCHW 填充

// ✅ 正确：动态获取维度类型
MNN::Tensor::DimensionType inputDimType = inputTensor_->getDimensionType();
MNN::Tensor tmpInput(inputTensor_, inputDimType);
bool isNCHW = (inputDimType == MNN::Tensor::DimensionType::CAFFE);
if (isNCHW) {
    inputData[c * totalPixels + i] = val;
} else {
    inputData[i * 3 + c] = val;
}
```

**症状**：输出完全错误但稳定，RGB 通道完全错位。

### ❌ 错误模式 B：忽略动态输入尺寸

```cpp
// ❌ 错误：未处理 ONNX 转换后的动态维度
// inputTensor_->height() == -1, width() == -1

// ✅ 正确：reshape 为固定尺寸
if (inputTensor_->height() <= 0 || inputTensor_->width() <= 0) {
    interpreter_->resizeTensor(inputTensor_, {1, 3, inputSize, inputSize});
    interpreter_->resizeSession(session_);
}
```

**症状**：`copyFromHostTensor` 时崩溃或 shape 不匹配。

### ❌ 错误模式 C：重复归一化

```cpp
// ❌ 错误：模型有内置归一化，又做外部归一化
float normMean = 127.5f;  // 错误！模型已有 _minusscalar0
float normStd = 128.0f;

// ✅ 正确：检测内置归一化节点
float normMean = hasBuiltInNormalization_ ? 0.0f : 127.5f;
float normStd = hasBuiltInNormalization_ ? 1.0f : 128.0f;
```

**症状**：输出接近正确但有偏差，或完全饱和。

### ❌ 错误模式 D：LLM 未做 prefill

```cpp
// ❌ 错误：直接 generate，跳过 prefill
llm->generate(1);  // 输出无意义

// ✅ 正确：两阶段调用
llm->response(promptStr, &oss, nullptr, 0);  // prefill
while (!llm->stoped()) {
    llm->generate(1);  // decode
}
```

**症状**：LLM 输出乱码或重复无意义字符。

### ❌ 错误模式 E：OpenCL 初始化失败未处理

```cpp
// ❌ 错误：假设 OpenCL 一定可用
config.type = MNN_FORWARD_OPENCL;
session_ = interpreter_->createSession(config);  // 可能返回 null

// ✅ 正确：检查 session 创建结果，准备降级
session_ = interpreter_->createSession(config);
if (!session_) {
    config.type = MNN_FORWARD_CPU;
    session_ = interpreter_->createSession(config);
}
```

**症状**：GPU 配置时应用启动崩溃或检测器初始化失败。

---

## 调试诊断

### 日志启用

```cpp
// MNN 推理日志
LOGD("Input tensor shape: [%d, %d, %d, %d]",
     inputTensor_->batch(), inputTensor_->channel(),
     inputTensor_->height(), inputTensor_->width());
LOGD("Input tensor dimension type: %d (CAFFE=0, TENSORFLOW=1)",
     (int)inputTensor_->getDimensionType());
LOGD("First 10 pixels: [%.2f,%.2f,%.2f] ...", inputData[0], inputData[1], inputData[2]);
```

### 性能计时

```cpp
auto tInferStart = std::chrono::high_resolution_clock::now();
interpreter_->runSession(session_);
auto tInferEnd = std::chrono::high_resolution_clock::now();
auto inferMs = std::chrono::duration_cast<std::chrono::milliseconds>(tInferEnd - tInferStart).count();
LOGI("[Perf] MNN infer: %ldms, backend=%s", inferMs, useGpu_ ? "OpenCL" : "CPU");
```

### 模型结构检查

```bash
# 检查 MNN 模型信息（使用 MNN 工具）
./MNNConvert --info model.mnn

# 检查是否包含归一化节点
strings model.mnn | grep -E "minusscalar|mulscalar"
```

---

## 与项目现有 Skill 的联动

| 场景 | 联动 Skill |
|------|-----------|
| 模型从 ONNX 转换而来 | [onnx-model-integration](/onnx-model-integration) |
| 人脸关键点对齐问题 | [mnn-landmark-diagnosis](/mnn-landmark-diagnosis) |
| OpenCL/GPU 相关问题 | [av-gl-expert](/av-gl-expert) |
| 编译错误 | [error-healer](/error-healer) |

---

## 审查清单（CR 必须检查）

- [ ] 模型是否通过 `MNNConvert` 标准转换？
- [ ] 输入张量维度类型是否动态获取（非硬编码）？
- [ ] 输出张量维度类型是否动态获取？
- [ ] 是否检测并处理了模型内置归一化节点？
- [ ] 动态输入尺寸是否已 reshape 为固定尺寸？
- [ ] OpenCL 初始化失败时是否有 CPU 降级路径？
- [ ] LLM 推理是否遵循 `response() → generate()` 两阶段模式？
- [ ] LLM 调用是否加了线程锁保护？
- [ ] JNI 字符串/数组是否正确释放（`ReleaseStringUTFChars`、`ReleaseByteArrayElements`）？
- [ ] 是否记录了输入 shape、维度类型、推理耗时等关键日志？

---

## 参考文档

- [MNN GitHub](https://github.com/alibaba/MNN)
- [MNN 文档](https://mnn-docs.readthedocs.io/)
- `docs/03-TECHNICAL-SPECS/MNN_LANDMARK_DIAGNOSIS.md` — MNN 人脸关键点对齐诊断
- `engines/beauty-engine/src/main/cpp/mnn_face_detector.cpp` — CV 推理参考实现
- `engines/agent-native/src/main/cpp/llm_jni_bridge.cpp` — LLM JNI 桥接参考实现（含 `libagent_native.so` 构建）

---

## 相关文件

- [mnn-ios-integration](/mnn-ios-integration) — iOS MNN.framework 对标（构建/embed + Precision_High 坑）

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0.1 | 2026-09-25 | GPU 后端正名 Vulkan→OpenCL（MNN_FORWARD_OPENCL/libMNN_CL.so）；CMake 对齐 agent-native 实况（target agent_native，llm/Express 符号并入 libMNN.so）；JNI 桥路径改 `engines/agent-native/` |
| 1.0.0 | 2026-05-26 | 初始版本，整合 CV 检测 + LLM 推理经验 |

---

## Skill 编写规范（维护者必读）

### 长度控制
- **SKILL.md 正文 < 500 行**。超过则拆分代码示例到 `reference.md`。
- 使用渐进式披露：核心流程在 SKILL.md，详细代码在 reference.md。

### 代码示例
- 单个代码块不超过 30 行。
- 超过 30 行的代码应移至 `reference.md`，SKILL.md 中只保留说明和链接。

### 引用规范
- 引用其他 Skill 使用相对路径：`/xxx`
- 引用项目文档使用相对路径：`docs/XXX.md`

### 版本管理
- 每次更新必须修改 `updated` 字段和「版本历史」表格。
- 重大结构调整应升级 minor 版本（1.0 → 1.1）。
- 内容重写或架构变更应升级 major 版本（1.x → 2.0）。
