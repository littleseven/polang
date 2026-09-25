---
name: mnn-llm-android
description: |
  MNN 端侧 VLM（Qwen3-VL-2B）Android 推理专家：模型下载/加载、JNI 桥接、图像打标推理（TAG Pass 3）、空响应与加载失败诊断。Use when working with on-device VLM inference, Qwen3-VL tagging, empty response debugging, or model loading failures on Android.
version: 2.0.0
created: 2026-05-26
updated: 2026-09-25
maintainer: "[RD] 全栈工程师"
tags:
  - mnn
  - vlm
  - android
  - jni
  - qwen
  - local-inference
  - image-tagging
  - model-loading
---


# MNN 端侧 VLM Android 推理专家

> **定位**：预防 AI 在 MNN 端侧 VLM（图像打标）推理中重复犯已验证过的错误。
> **触发时机**：VLM 打标返回空、模型加载失败、JNI 桥接问题、Qwen3-VL 适配时。
> **⚠️ 范围变更（2026-08-02）**：端侧**文本** LLM 已整体移除——chat/相机 AI 指令全走远程 tool_calls（Koog，见 [kmp-ios-interop] 及 `shared` AGENTS.md）；本 skill 仅覆盖**端侧 VLM 图像推理**（TAG Pass 3 打标）。`MnnLlmClient.generate()/generateWithHistory()/generateStream()` 等纯文本方法是残留死代码，勿新增调用。

---

## 触发条件

- 用户提到 "MNN-LLM"、"端侧 VLM"、"Qwen3-VL"、"TAG 打标"、"模型加载"
- 出现 `Empty LLM response` / VLM 打标结果为空
- 出现 `Model not found` 或 `createLLM failed`
- 需要实现或调试 VLM JNI 桥接代码
- 需要下载或管理 MNN 格式模型文件

---

## 现役调用链（TAG Pass 3）

```
TagGenerationPipeline / OpenClGuardian（androidApp domain/tag/）
  → LocalLlmEngine.imageInference / imageInferenceWithTimeout   （shared androidMain，ImageInferenceEngine 实现）
    → MnnLlmClient.generateWithImage / generateWithImageTimeout
      → JNI: nativeGenerateWithImage[Timeout]（engines/agent-native/src/main/cpp/llm_jni_bridge.cpp）
        → C++: llm->response(prompt + vision_embed, &oss, end_with, 0)  // prefill
          → llm->generate(1) 循环                                        // decode
```

关键事实：
- **模型注册表**：`androidApp/src/main/res/raw/llm_models.json`——现役 VLM 仅 `qwen3_vl_2b`（原 `qwen3-0-6b` 等文本模型已下架）
- **默认加载键**：`MnnLlmClient.load(modelKey = "qwen3_vl_2b", useOpencl)`；OpenCL 由 `OpenClGuardian` 决策（warmup 超时/连续失败 → 降级 CPU + DataStore 黑名单）
- **JNI 桥**：`engines/agent-native/src/main/cpp/llm_jni_bridge.cpp`（`libagent_native.so`，经 AAR 传递到 androidApp）；日志 tag `PoLang:LlmJNI`
- **图像降采样**：`LocalLlmEngine` 入口按最长边 ≤1024 计算 inSampleSize（`VLM_IMAGE_MAX_PX`），模型内部再 resize 到视觉编码器尺寸——外部不要预先 resize 到模型输入尺寸
- **线程模型**：`LocalLlmEngine` 所有模型操作（load/unload/trimMemory/generate）统一在专用单线程执行 + `Mutex`；JNI 内另有 `std::mutex`（MNN-LLM 非线程安全）

---

## 核心原则

1. **两阶段调用是铁律**：`response(..., 0)` prefill + 循环 `generate(1)` decode（JNI 桥内已实现）
2. **不要手动调用 `generate_init()`**：`response()` 内部自动调用
3. **小模型 `maxNewTokens` 必须限制**：打标任务保持 128-256，过大导致空响应
4. **图像路径走 `generateWithImage*`**：纯文本 `response(prompt)` 不含视觉嵌入，VLM 场景禁用
5. **ChatMessages API 兼容性差**：优先使用纯文本 prompt + 图像参数组合

---

## 模型加载流程

### 1. 模型文件结构

```
filesDir/llm_models/qwen3_vl_2b/
├── config.json          # 模型配置（必须）
├── llm.mnn             # 模型结构（必须）
├── llm.mnn.weight      # 模型权重（必须）
├── tokenizer.json      # 分词器（必须）
└── tokenizer_config.json
```

### 2. Java 层加载检查清单（`MnnLlmClient.load()`）

- `config.json` 存在且非空
- `llm.mnn` / `llm.mnn.weight` 至少其一存在
- **Git LFS 指针检测**：文件 <1000 字节且首行含 `git-lfs` → 判为损坏，删除重下

### 3. Native 层加载

```cpp
MNN::Transformer::Llm *llm = MNN::Transformer::Llm::createLLM(configPath);
if (llm == nullptr) return 0;
bool loaded = llm->load();   // 失败须 destroy(llm)
```

---

## 常见陷阱

| 陷阱 | 症状 | 修复 |
|------|------|------|
| **调用纯文本 `generate()`** | VLM 无视觉输入，打标内容与图无关 | 走 `generateWithImage*` / `imageInference*` |
| **手动调用 `generate_init()`** | 状态混乱，输出异常或空 | 删除手动调用，`response()` 内部已自动调用 |
| **`end_with = nullptr`** | 可能触发异常或空响应 | 传 `"\n"` 或 `"<eop>"` |
| **maxNewTokens 过大** | 2B 模型返回空 | 打标用 128-256 |
| **手动加 chat template** | 双重标记，输出异常 | `use_template=true` 自动处理，外部用纯文本 |
| **模型目录名不匹配** | `Model not found` | 注册表 `cacheDirName` 必须与下载目录名一致 |
| **Git LFS 指针文件** | 模型加载失败 | 检查文件头 `git-lfs`，删除重新下载 |
| **未加线程锁** | 并发调用崩溃 | JNI `std::mutex` + 引擎级单线程/Mutex |
| **外部预先 resize 图像** | 打标质量劣化 | 交给 `LocalLlmEngine` 的 ≤1024 降采样策略 |
| **OpenCL 连续超时仍强推 GPU** | 打标卡死 | 尊重 `OpenClGuardian` 降级决策与黑名单 |

---

## 诊断流程

### Step 1: 确认模型文件完整性

```bash
adb shell run-as com.mamba.picme ls -la files/llm_models/qwen3_vl_2b/
# config.json 应 > 1KB，llm.mnn[.weight] 应 > 100MB
```

### Step 2: 检查 config.json 关键配置

```bash
adb shell run-as com.mamba.picme cat files/llm_models/qwen3_vl_2b/config.json
```

关键字段：`max_new_tokens`、`use_template`、`jinja.context.enable_thinking`、`backend_type`（cpu / opencl）。

### Step 3: 检查 Native 层日志

```bash
adb logcat -s PoLang:LlmJNI:D *:S
```

关注：`After prefill, oss size=?, status=?`、`After generate(N), gen_seq_len=?, stopped=?`；OpenCL 降级看 `OpenClGuardian` 相关日志。

### Step 4: 验证推理调用链

确认调用方走的是 `imageInference*`（见上「现役调用链」），而非残留的纯文本方法。

---

## Qwen 模型特殊处理

### Thinking 模式（Qwen3 系）

`config.json` 中 `jinja.context.enable_thinking: true` 时输出含 `<think>...</think>`，后处理须过滤 think 标签（VLM 打标 prompt 通常直接关闭 thinking）。

### Prompt 格式

system prompt（打标指令）+ user prompt（问题/约束）经 `imageInference(bitmap, systemPrompt, userPrompt, maxTokens)` 传入；**不要**手动添加 `<|im_start|>`、`<|im_end|>` 等标记，`use_template=true` 时 MNN-LLM 内部自动应用。

---

## 审查清单

- [ ] 调用链走 `imageInference*` / `generateWithImage*`（非残留纯文本方法）
- [ ] 模型文件完整且不是 Git LFS 指针
- [ ] 模型目录名与注册表 `cacheDirName` 一致（`qwen3_vl_2b`）
- [ ] JNI 桥接中 `response()`/`generate()` 有 `std::mutex` 保护
- [ ] 未手动调用 `generate_init()`
- [ ] `end_with` 参数不为 `nullptr`
- [ ] `maxNewTokens` 适合打标任务（128-256）
- [ ] 外部 prompt 未手动添加 chat template 标记
- [ ] OpenCL 降级路径可用（Guardian 黑名单生效）

---

## 参考文档

- [MNN GitHub](https://github.com/alibaba/MNN)
- [MNN-LLM 官方 Android Demo](https://github.com/alibaba/MNN/tree/master/apps/Android/MnnLlmChat)
- `engines/agent-native/src/main/cpp/llm_jni_bridge.cpp` — 项目 VLM JNI 桥接实现
- `engines/beauty-engine/libs/mnn/include/MNN/llm/llm.hpp` — MNN-LLM 头文件
- `docs/03-TECHNICAL-SPECS/MNN_LLM_OPERATIONS.md` — 端侧 VLM 打标引擎运维
- `docs/03-TECHNICAL-SPECS/TAG_GENERATION.md` — TAG 3-Pass 流水线

---

## 相关文件

- [mnn-integration](/mnn-integration) — CV 检测 + 通用 MNN 接入
- [onnx-model-integration](/onnx-model-integration) — ONNX 模型接入

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0.0 | 2026-05-26 | 初始版本，整合 MNN-LLM 文本推理经验 |
| 2.0.0 | 2026-09-25 | 对齐端侧文本 LLM 移除：重构为 VLM（Qwen3-VL-2B）打标视角；调用链/模型注册表/JNI 桥路径（agent-native）全面换新；标注纯文本方法为残留死代码 |

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
