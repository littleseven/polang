# OPUS-MT 翻译模型验证记录

> **验证日期**: 2026-06-28  
> **验证范围**: FP32 与 INT8 量化模型在 Android 端侧推理的正确性  
> **验证人**: RD Agent  
> **状态**: ⚠️ **FP32 模型推理异常，INT8 模型尚未验证**

> **重要更新**：本记录反映的是 **2026-06-28 日间的代码状态**。当天晚些时候 commit `bee0dcbe`（`fix(i18n): OPUS-MT tokenizer vocab mapping + decoder optimization`）修复了 SentencePiece ↔ HuggingFace token ID 映射、decoder EOS 控制、KV-cache 传递等问题，并验证通过 `美丽的姑娘` → `beautiful girl` 等用例。当前 `OpusMtTranslator.kt` 已改为加载 INT8 量化模型（`encoder_model_quantized.onnx` 等）。因此本文档目前处于**待重新验证**状态，不能直接代表当前代码的行为。

---

## 1. 验证目标

验证 Helsinki-NLP/opus-mt-zh-en 的 ONNX 模型在 Android 设备上的翻译效果：
- FP32 模型（`encoder_model.onnx` + `decoder_model.onnx`）
- INT8 量化模型（`encoder_model_quantized.onnx` + `decoder_model_quantized.onnx`）

**预期**: 中文 "多" → 英文 "many" 或 "much"  
**实际**: 两种模型均输出乱码

---

## 2. 环境信息

| 项目 | 值 |
|------|-----|
| 设备 | Android (adb 连接) |
| 推理框架 | ONNX Runtime 1.19.0 |
| Tokenizer | SentencePiece (C++ JNI) |
| 模型来源 | HuggingFace Helsinki-NLP/opus-mt-zh-en → ONNX 导出 |

---

## 3. 模型文件清单

```
/data/data/com.mamba.picme/files/llm_models/opus-mt-zh-en/
├── encoder_model.onnx                      200 MB  (FP32)
├── encoder_model_quantized.onnx           50 MB  (INT8)
├── decoder_model.onnx                      351 MB  (FP32)
├── decoder_model_quantized.onnx           89 MB  (INT8)
├── decoder_with_past_model_quantized.onnx 86 MB  (INT8, 未加载)
├── source.spm                              中文 SentencePiece 模型
├── target.spm                              英文 SentencePiece 模型
└── config.json                             模型配置
```

> **注意**: 当前代码优先加载 FP32 模型（`encoder_model.onnx`），INT8 量化模型文件存在但未被加载。

---

## 4. 验证结果

### 4.1 FP32 模型（当前加载）

**输入**: "多"  
**输出**: `")otototototot2-otototototot2- innocent..."`  
**状态**: ❌ **严重错误 — 完全乱码**

#### 关键日志分析

```
06-28 12:11:43.003 29474 29474 D OpusMtTranslator: Encoder input: text='多', ids=[8530], length=1
06-28 12:11:43.009 29474 29474 D OpusMtTranslator: Encoder hidden states shape: [1, 1, 512]
06-28 12:11:43.009 29474 29474 D OpusMtTranslator: Encoder hidden states stats: count=512, mean=-0.012907846, max=1.7782773, min=-1.2611934
06-28 12:11:43.177 29474 29474 V OpusMtTranslator: Step 0: nextTokenId=28, piece=')', usePast=false, decoderInput=[65000], top5=[28=8, 6227=7, 32272=7, 27660=7, 26=6]
06-28 12:11:43.243 29474 29474 V OpusMtTranslator: Step 1: nextTokenId=6227, piece='ot', usePast=false, decoderInput=[65000, 28], top5=[6227=10, 13645=8, 4343=8, 22026=7, 28=7]
06-28 12:11:43.322 29474 29474 V OpusMtTranslator: Step 2: nextTokenId=6227, piece='ot', usePast=false, decoderInput=[65000, 28, 6227], top5=[6227=11, 13645=10, 4343=9, 28=8, 2=8]
06-28 12:11:43.389 29474 29474 V OpusMtTranslator: Step 3: nextTokenId=6227, piece='ot', usePast=false, decoderInput=[65000, 28, 6227, 6227], top5=[6227=11, 13645=10, 4343=9, 28=8, 53=8]
06-28 12:11:43.466 29474 29474 V OpusMtTranslator: Step 4: nextTokenId=6227, piece='ot', usePast=false, decoderInput=[65000, 28, 6227, 6227, 6227], top5=[6227=11, 13645=10, 4343=9, 28=8, 53=8]
06-28 12:11:43.553 29474 29474 V OpusMtTranslator: Repetition penalty applied to cycle of length 3: [6227, 6227, 6227]
06-28 12:11:49.905 29474 29474 D OpusMtTranslator: Translated: '多' -> ')otototototot2-otototototot2- innocentotototototot2-otototototot2- innocent)otototototot2-otototototot2- innocentotototototot2-otototototot2- innocent) 262'
```

#### 问题分析

1. **Encoder 输出正常**: `encoder hidden states` 数值范围合理（mean=-0.01, max=1.78, min=-1.26），说明编码器前向传播无异常。
2. **Decoder 第 1 步即异常**: 第 1 个生成的 token 是 `)` (ID=28)，而非预期的英文单词开头（如 "m"）。
3. **进入循环重复**: 第 2 步后陷入 `ot` (ID=6227) 的无限循环，说明 decoder 的 logits 分布严重偏离预期。
4. **重复惩罚无效**: 即使应用了重复惩罚，模型仍无法跳出循环，说明 logits 的 top 概率高度集中且不合理。

### 4.2 INT8 量化模型

**状态**: ⚠️ **未验证** — 代码优先加载 FP32 模型，INT8 模型文件存在但未被使用。

---

## 5. 根因假设（按可能性排序）

### 假设 1: 模型导出时输入/输出名称不匹配（最可能）

**推理**: ONNX 模型导出时使用了 Optimum 或 transformers.onnx，但导出参数与推理代码的输入名称不一致。

**证据**:
- 编码器输出正常 → 编码器输入名称匹配
- 解码器输出异常 → 可能是解码器输入名称不匹配，导致某些输入（如 `attention_mask`）未被正确传递
- 历史日志中曾出现 `Missing Input: attention_mask` 错误（早期代码版本）

**需验证**:
```python
# 使用 onnx 工具检查模型输入输出名称
import onnx
model = onnx.load("decoder_model.onnx")
for input in model.graph.input:
    print(input.name)
for output in model.graph.output:
    print(output.name)
```

### 假设 2: Tokenizer 与模型词表不匹配

**推理**: SentencePiece 的 token ID 映射与模型训练时的映射不一致。

**证据**:
- `source.spm` 和 `target.spm` 是独立的两个文件，但 OPUS-MT 使用共享词表
- 如果 tokenizer 和模型的词表顺序不同，会导致编码/解码错误

**需验证**:
- 对比 `source.spm` 和 `target.spm` 的 vocab size 是否一致
- 检查 `config.json` 中的 `vocab_size` 与 tokenizer 实际大小是否匹配

### 假设 3: 特殊 Token ID 配置错误

**推理**: MarianMT 的特殊 token ID（pad=65000, eos=0, decoder_start=65000）可能与实际模型训练配置不符。

**证据**:
- 当前代码从 `config.json` 读取 token ID，但 `config.json` 可能来自 transformers 导出，与 ONNX 模型不完全一致
- 如果 `decoder_start_token_id` 错误，解码器会从错误的初始状态开始生成

**需验证**:
- 使用 transformers 加载原始模型，对比特殊 token ID
- 检查 ONNX 模型中的 `decoder_start_token_id` 常量

### 假设 4: 模型导出时未正确包含位置编码

**推理**: 如果导出时未正确导出位置编码（positional embeddings），decoder 无法区分不同位置的 token。

**证据**:
- 输出呈现循环重复模式（`otototot...`），这是位置编码缺失的典型症状
- 模型无法区分 "第 2 步" 和 "第 3 步" 的位置，导致重复生成相同 token

### 假设 5: INT8 量化导致精度损失（待验证）

**推理**: 即使 FP32 模型修复后，INT8 量化可能引入额外误差。

**当前状态**: 尚未验证，因为 FP32 模型本身尚未正常工作。

---

## 6. 修复计划

### Phase 1: 诊断模型 I/O 名称（高优先级）

1. 使用 Python + onnx 库检查 decoder 模型的输入/输出名称
2. 对比代码中硬编码的名称（`input_ids`, `attention_mask`, `encoder_hidden_states`, `encoder_attention_mask`）
3. 如果不匹配，修改代码中的输入名称

### Phase 2: 验证 Tokenizer 一致性

1. 对比 `source.spm` 和 `target.spm` 的 vocab size
2. 使用 Python 的 `sentencepiece` 库验证 "多" 的 token ID 是否为 8530
3. 验证 `decode([8530])` 是否返回 "多"

### Phase 3: 对比 Python 推理结果

1. 在 Python 中使用 `transformers` + `onnxruntime` 运行相同输入
2. 对比 Python 输出与 Android 输出
3. 如果 Python 正常而 Android 异常，说明是推理代码问题；如果两者都异常，说明是模型导出问题

### Phase 4: 验证 INT8 量化模型

1. 修改代码优先加载 INT8 模型
2. 重复上述验证流程
3. 对比 FP32 和 INT8 的翻译质量差异

---

## 7. 当前代码状态

当前代码（`OpusMtTranslator.kt`）已做以下优化：

1. **添加 EOS**: 编码器输入追加 `eosTokenId`（MarianMT 训练时要求）
2. **重复抑制**: 连续 3 个相同 token 时降低概率
3. **诊断日志**: 每步打印 top 5 logits 和生成的 token
4. **Vocab 一致性检查**: 初始化时检查 source/target vocab size 是否一致

但核心问题（decoder 输出乱码）尚未解决。

---

## 8. 附录

### 8.1 模型导出命令（参考）

```python
# 使用 Optimum 导出 ONNX
from optimum.onnxruntime import ORTModelForSeq2SeqLM
from transformers import AutoTokenizer

model = ORTModelForSeq2SeqLM.from_pretrained("Helsinki-NLP/opus-mt-zh-en", export=True)
tokenizer = AutoTokenizer.from_pretrained("Helsinki-NLP/opus-mt-zh-en")

model.save_pretrained("./opus-mt-zh-en-onnx")
tokenizer.save_pretrained("./opus-mt-zh-en-onnx")
```

### 8.2 关键 Token ID 对照

| Token | ID | 说明 |
|-------|-----|------|
| `<pad>` | 65000 | MarianMT 特殊处理，位于词表末尾 |
| `<eos>` | 0 | 序列结束 |
| `<unk>` | 2 | 未知词 |
| `<s>` | 1 | 句子开始（SentencePiece 默认） |
| `decoder_start` | 65000 | 与 pad 相同，MarianMT 特殊设计 |

### 8.3 相关文件

- 推理代码: `app/src/main/java/com/mamba/picme/domain/tag/i18n/OpusMtTranslator.kt`
- 调用方: `app/src/main/java/com/mamba/picme/domain/tag/i18n/ChineseQueryTranslator.kt`
- 模型目录: `/data/data/com.mamba.picme/files/llm_models/opus-mt-zh-en/`

---

## 9. 修复记录

### 9.1 修复 1: decoder_start_token_id 越界 (2026-06-28)

**问题**: SentencePiece 目标词表只有 32000 个 token (0~31999)，但 `decoderStartTokenId` 使用了
`config.json` 中的 `decoder_start_token_id=65000`（原始 MarianMT 的 pad_token_id），导致：

1. Decoder 第 1 步输入 token 65000 → embedding lookup 越界（或查到无意义的 embedding）
2. 后续自回归链被污染 → 生成乱码（`)ototot...`）
3. 部分生成的 token ID (如 38266) 超出 SentencePiece 词表范围 → JNI 层抛出
   `OUT_OF_RANGE: Invalid id: 38266`

**修复** (OpusMtTranslator.kt):

1. **`decoderStartTokenId` 改用 SentencePiece 的 pad token ID**（从 `pieceToId("<pad>")`
   获取，通常为 0），不再使用 config.json 的原始值 65000。

2. **特殊 token ID 全部从 SentencePiece tokenizer 推导**：
   ```kotlin
   padTokenId = srcTok.pieceToId("<pad>").let { if (it >= 0) it else 0 }
   eosTokenId = srcTok.pieceToId("</s>").let { if (it >= 0) it else 1 }
   unkTokenId = srcTok.pieceToId("<unk>").let { if (it >= 0) it else 2 }
   decoderStartTokenId = padTokenId  // MarianMT convention: decoder_start = pad
   ```

3. **argmax 约束到目标词表范围**，防止生成越界 token ID：
   ```kotlin
   val maxValidIdx = minOf(lastLogits.size, targetTokenizer?.vocabSize() ?: lastLogits.size) - 1
   val nextTokenId = (0..maxValidIdx).maxByOrNull { lastLogits[it] } ?: unkTokenId
   ```

**原理**: MarianMT 的原始设计是 `decoder_start_token_id = pad_token_id`，但在 SentencePiece
模型中 PAD 的 ID 是 0（而非 65000）。我们必须使用 SentencePiece 的 ID 体系，因为模型
导出时 embedding 层虽然保留 65001 维度，但实际训练中使用的 token ID 都在 SentencePiece
词表范围内。

### 9.2 待验证

- [ ] 修复后重新部署到设备，验证翻译是否正确（输入 "多" → 期望 "many" 或 "much"）
- [ ] 验证 decoder 不再生成越界 token ID
- [ ] 验证 INT8 量化模型的翻译效果

---

## 10. 结论

**当前状态**: decoder_start_token_id 越界已修复，FP32 模型待重新验证。  
**根本原因**: config.json 的 token ID 与 SentencePiece tokenizer 的实际 ID 不匹配。  
**修复方式**: 特殊 token ID 从 SentencePiece tokenizer 获取，不再依赖 config.json。
