# AI 修图能力缺口与端侧模型选型

> **文档类型**：技术方案 / 能力评估（Analysis & Proposal）
> **针对能力**：AI 修图整体（会话式修图 + AI 一键优化 + 抠图/证件照 + 画质度量）
> **最后更新**：2026-08-08
> **维护者**：项目开发者
> **状态**：⚠️ 分析阶段，尚未做产品决策；本文件为「当前能力盘点 + 缺口 + 候选模型」SSOT，落地需另起实现计划。

---

## 1. 核心结论

经全链路排查，PoLang 当前的「AI 修图」本质上**不是 AI 在改图，而是 LLM 在遥控一个参数化的 GPU shader 引擎**：

- **像素来源只有两处**：① GLSL 数学运算（双边模糊 / 色调曲线 / LUT / 网格变形）；② 一个 2021 水平的抠图模型（MODNet，无精修）。
- **整个"生成式 / 修复式像素能力"缺失**：无超分、无 AI 降噪、无人脸修复、无 inpainting（连桩都没有），"智能消除"经 `[unsupported:erase]` 协议显式拒绝（LLM 在 explanation 返回该标记，端侧映射为不支持文案）。
- 视觉模型仅 Qwen3-VL-2B + Florence-2（理解 / 打标）+ MobileCLIP（语义）——**全是"看懂图"，没有"画图"**。

这是**能力品类的缺失**，不是工程质量问题：架构干净、隐私守得死（媒体处理 100% 端侧）、抽卡闭环 + 退化守卫设计有想法。

缺口是**两层**，必须一起补：

| 层 | 现状 | 含义 |
|---|---|---|
| **能力层（供给侧）** | 无任何生成 / 修复像素的模型 | 没有模型能把"烂照片变好"或"消除路人" |
| **度量层（需求侧）** | 修图循环唯一判官是 NIMA；eDifFIQA 仅用于封面选择，未入循环 | 没有诚实的 IQA 判断"这次编辑到底有没有变好"，光堆模型仍会"越优化越差" |

---

## 2. 全链路能力地图（存在 / 缺失）

### 2.1 会话式修图链路（`edit_image` → 像素）

调用链：`ChatToolService.editImage`（LLM @Tool）→ `ImageEditCapability` → `ChatEditRecipeBuilder`（解析 EditParams delta）→ `ChatEditProcessor`（编排）→ `RecipeApplier.applyGpuEffects` → `PhotoProcessor.process`（GPU 离屏渲染）。

| 能力 | 状态 | 机制 |
|---|---|---|
| 磨皮 / 美白 / 瘦脸 / 调色 / 滤镜 / 风格 | ✅ 全参数化 | GPU beauty/filter shader；多轮 delta 累积 |
| **智能消除** | ❌ 死路 | LLM 经 `[unsupported:erase]` 协议显式拒绝（`ChatToolService.edit_image` explanation 标记 → `chat_edit_unsupported_erase` 文案）；无 inpainting / LaMa / SD 后端 |
| **会话换背景** | ❌ 不可达 | `MattingEngine` 只接手动编辑器，且仅抠图 + 纯色填充，非生成 |
| **局部编辑** | ❌ 不支持 | 全是全局或全脸（landmark 网格变形），"只磨左脸"做不到 |

### 2.2 增强 / 修复模型

| 能力 | 状态 |
|---|---|
| 超分（Real-ESRGAN 等） | ❌ 完全没有 |
| AI 降噪 / 暗部 / 去模糊 | ❌ 没有；`LOW_LIGHT` 场景只是参数化提亮 |
| 人脸修复（CodeFormer / GFPGAN） | ❌ 没有 |
| 生成式 inpainting / outpainting / diffusion | ❌ 连桩都没有 |
| 参数化美颜引擎（GLSL） | ✅ 存在，但内含零个学习式修复网络 |

### 2.3 抠图 / 证件照（`domain/matting/`）

| 项 | 现状 |
|---|---|
| 模型 | MODNet(1024², ONNX) + U2NetP + MediaPipe selfie，per-pixel max 融合（证件照），100% 端侧 |
| 质量上限 | ⚠️ "MODNet 原始 alpha、无精修" ≈ 2021 水平 |
| alpha matting 精修 | ❌ 无 trimap / guided filter / closed-form |
| 发丝 / 细节 | ❌ 亚像素发丝被双线性放大平均掉（最大短板） |
| 非人像主体 | ❌ U2NetP 二值化（0.5 阈值）→ 硬边缘锯齿 |

> **能力澄清（2026-08-08）**：抠图能力**已存在且可复用**（`MattingEngine.removeBackground()` → alpha，后端 MODNet/U2NetP/MediaPipe/FUSION），证件照即建于此。"换底"当前**仅支持纯色**（`BackgroundComposer.composeOnColor(bgColor)`，蓝/红/白）；换成**任意背景图**只需扩一个 `composeOnImage(bgBitmap)`（逐像素 alpha 合成，约 10 行，无需换模型）；**AI 生成场景背景**则无（涉生成式 + 红线）。故 §4/§5 中 RMBG-2.0/BiRefNet 的定位是**已有能力的质量升级**（发丝/边缘），非新增能力。会话路径不可达（`ChatEditRecipeBuilder` 不填 `cutout`）。

---

## 3. 度量层缺口：NIMA 不是"质量"，是"美观"

抽卡评分链路（`OptimizeScorer.scoreCandidate`）= **护栏淘汰 → `scorer.score()`（= NIMA）→ 选优 + 退化守卫**。护栏仅两个标量：高光裁剪增量 > 5%、平均亮度漂移 > 15%（`Guardrails.kt`）。

三个常被混为一谈的"质量"维度，需严格区分：

| 维度 | 量的是什么 | 你现有的工具 | 漏什么 |
|---|---|---|---|
| **美观（aesthetic）** | 人觉得好不好看 | NIMA（整图，1–10） | 技术退化（过磨皮、halo） |
| **识别可用性（recognition utility）** | 这张脸能不能被识别系统认对 | eDifFIQA（112² 对齐人脸，0–1）—— **仅用于封面选择，未入修图循环** | 美学；非人脸图无分 |
| **技术画质 / 失真（distortion）** | 锐度、噪点、压缩伪影、halo | ❌ 谁都没打（需 NIQE / BRISQUE 类） | —— |

关键问题：

1. **NIMA 偏好高对比高饱和**（代码注释 `Guardrails.kt:8` 自述），会**反向奖励**过磨皮 / 塑料脸 / 过锐化——恰是修图最易出的毛病。
2. **eDifFIQA 没接进 `OptimizeScorer`**（`AppContainer.kt:400` 注入的是 `NimaScorer`），人物照为主、美颜直接改脸，修图判官对人脸零信号。
3. **没有 full-reference 保真信号**：原图 bitmap 在手上却只抽 2 个标量，无法检测细节丢失 / 纹理糊化 / halo。

→ 详见 §6 优先级，度量层最小修复 = eDifFIQA 入循环 + 一道漂移上界。

---

## 4. 候选端侧模型选型（映射到上述缺口）

> **红线遵守**：下列模型均**端侧推理**（ONNX Runtime + NNAPI，或 MNN），媒体文件不出端，符合 ADR-008。下表"修复/增强"属**保真类**（恢复细节、消除杂物），**非** `AI_OPTIMIZATION.md:27` 所禁的"AI 换脸 / 重绘"生成式身份修改。

| 缺口 | 候选模型 | 作用 | ONNX / 移动端可用性 | 移动端注意 |
|---|---|---|---|---|
| **超分 / 放大** | **Real-ESRGAN**（x4plus / anime 变体） | 2–4× 超分、恢复细节 | 社区 ONNX 导出常见，有 NCNN/MNN 移植先例 | 大模型 CPU 重，需 INT8；anime 变体更小更快 |
| **人脸修复** | **CodeFormer**（推荐）/ GFPGAN | 老照片 / 糊脸修复 | 社区 ONNX 可导出 | CodeFormer 有 fidelity 权重可调、相对更轻；可与 Real-ESRGAN 组成"老照片修复"标准管线（背景超分 + 人脸修复） |
| **消除 / 抹除** | **LaMa**（big-lama, 512²） | 涂抹 / 点选区域消除（路人、杂物、文字） | `Carve/LaMa-ONNX`（HF）现成 512² | 配 MobileSAM 做"点一下→mask→消除"；非完整生成式 fill，但覆盖大部分消除需求 |
| **交互式分割** | **MobileSAM** | 点 / 框 → mask（消除前置选区） | ONNX（HF `vietanhdev/...`）+ Qualcomm AI Hub 文档 | 比原 SAM 小 66×、快 5–38×；可替 U2NetP 做"点选主体" |
| **抠图换底（质量升级，非新能力）** | **RMBG-2.0 / BiRefNet** | 真 alpha matte，替 MODNet 提升发丝/边缘 | ONNX 导出 + 量化部署有社区指南 | 抠图能力**已存在**（MODNet）；此为 backbone 升级，**移动端最重**，需 INT8 + NPU；"任意背景图换底"则是 `BackgroundComposer` 小扩展（无需换模型） |
| 降噪 / 暗部 | NAFNet / PRIDNet 等 | 学习式降噪 | 有 ONNX | **移动端最薄的一类**，模型偏重；暂维持参数化提亮 |

**落地共性**：

- 全部可走**现有 ONNX Runtime + NNAPI** 管线（NIMA / eDifFIQA / MODNet 已验证），模型下载复用 `LlmModelDownloadManager` + ModelScope。
- 大模型（RMBG-2.0、CodeFormer、LaMa@512²）**必须 INT8 量化**，理想配 NPU；纯 CPU 耗时在百毫秒～秒级，需异步 + 进度 UI。
- ONNX 多为**社区导出**而非官方移动版，预留"导出 / 量化 / 校验"工作量。

---

## 5. 推荐优先级（质量收益 / 成本，且不破红线）

1. **【度量层先修，成本最低】** 把 eDifFIQA 接进 `OptimizeScorer` 当人脸轴（仿 `CoverSelector` 的 `W_FACE=0.6/W_AESTHETIC=0.4`），并加一道 full-reference 漂移上界（先上锐度差 = Laplacian 方差 delta，纯 CPU，与现有 `Guardrails` 同文件；预算足再 MS-SSIM/LPIPS）。**先有诚实度量，再堆模型。**
2. **【画质修复，最高性价比】** CodeFormer（人脸）+ Real-ESRGAN（背景超分），ONNX，复用现成下载基建，端侧不破红线，可嵌进抽卡闭环用（补齐后的）度量选优。直击"把烂照片救回来"。
3. **【抠图换底：先做小扩展，质量升级后置】** 抠图能力已存在（MODNet），无需新建。先做**任意背景图换底**（`BackgroundComposer` 加 `composeOnImage`，约 10 行，无模型成本）；MODNet → RMBG-2.0/BiRefNet 的 backbone 升级（发丝/边缘）**移动端最重、需 INT8+NPU、性价比最低**，最后再议。
4. **【消除，用户最想要但最贵】** 先上端侧 **LaMa + MobileSAM**（点选→消除，不生成新内容、不破隐私）；真正生成式 fill（diffusion）再议——MNN 已支持 Diffusion transformer，但重量级、调优贵，且与"不做生成式重绘"红线需明确边界。

---

## 6. 风险与红线

- **隐私**：所有候选模型端侧推理，媒体不出端，符合 ADR-008。唯一网络触点 = 从 ModelScope 下载模型**文件**（权重，非用户媒体）。
- **"修复" vs "生成式身份修改"**：超分 / 人脸修复 / 消除属保真类，不触 `AI_OPTIMIZATION.md:27` 的换脸 / 重绘禁令；但若后续引入扩散式重绘 / 换脸，须单独走产品 + 红线评审。
- **性能**：大模型 INT8 + NPU；纯 CPU 不可接受。`[PERF]` 交互反馈 < 100ms 的红线要求这些能力必须**异步离线**（如"AI 增强"后台任务 + 预览），不能阻塞主交互。
- **度量先行**：不补度量层就堆模型，退化守卫失效 → "越优化越差"。

---

## 7. 相关文档

- 端侧模型清单 SSOT：[`ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md`](ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md)、[`ONDEVICE_IMAGE_UNDERSTANDING_MODELS.md`](ONDEVICE_IMAGE_UNDERSTANDING_MODELS.md)
- AI 一键优化 / 抽卡闭环：[`AI_OPTIMIZATION.md`](AI_OPTIMIZATION.md) §11.5（NIMA 评分守卫）、`SMART_OPTIMIZE_VLM_DESIGN.md`
- 🆕 人脸修复（桶 1）调研方案：[`docs/superpowers/specs/2026-08-08-face-restoration-ondevice-design.md`](../superpowers/specs/2026-08-08-face-restoration-ondevice-design.md)
- 抠图设计稿（已随交付清理，git 历史可查）：2026-07-18 背景移除、2026-08-06 证照可调抠图
- 会话式修图：`ImageEditCapability.kt` / `ChatEditProcessor.kt` / `RecipeApplier.kt`
- 度量：`domain/aesthetic/`（`NimaScorer` / `EdiffiqaScorer` / `CoverSelector`）、`optimize/gacha/OptimizeScorer.kt` / `Guardrails.kt`

---

## 8. 修订历史

| 日期 | 变更 |
|---|---|
| 2026-08-08 | 初版：全链路能力盘点 + 两层缺口（能力 / 度量）+ 端侧候选模型选型 + 优先级 |
