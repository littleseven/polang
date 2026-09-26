# 端侧人脸修复（CodeFormer）调研方案

> **文档类型**：技术调研 / 设计方案（Pre-implementation Design）
> **针对能力**：AI 修图 桶 1 —— 画质修复（先做"人脸修复"）
> **最后更新**：2026-08-08
> **状态**：⚠️ 调研阶段。**正式实施前置于 Phase 4（`shared/` KMP 模块落地）**；期间可做 Android 本地 Phase-0 spike 验证质量（见 §8）。
> **上游缺口文档**：[`AI_IMAGE_EDITING_CAPABILITY_GAP.md`](../../03-TECHNICAL-SPECS/AI_IMAGE_EDITING_CAPABILITY_GAP.md)

---

## 1. 目标与范围

- **目标**：端侧（100% 本地，守 ADR-008 隐私红线）把老照片 / 糊脸 / 低分辨率人脸**修复清晰**，这是相册修图"把烂照片救回来"的核心价值，也是当前完全缺失的能力。
- **范围（本方案）**：**仅人脸修复**（CodeFormer），输入整图 → 检出所有人脸 → 修复 → 贴回。**不含全图超分**（Real-ESRGAN，作为后续第二步，组成完整"老照片修复"）。
- **非目标**：不做生成式换脸 / 重绘（触 `AI_OPTIMIZATION.md:27` 红线）；不做云端推理（媒体不出端）。

---

## 2. 选型：CodeFormer

| 项 | CodeFormer | GFPGAN |
|---|---|---|
| 来源 | NeurIPS 2022（sczhou/CodeFormer） | 2021（TencentARC） |
| 机制 | Codebook Lookup Transformer | 预训练人脸 GAN 先验 |
| 保真权重 | **有 `-w`（0–1）**：小→更修复、大→更忠于原貌，建议起手 **0.5** | 无显式权重（靠版本权衡） |
| 重退化鲁棒性 | 强（专为盲修复设计） | 中 |
| 体积/速度 | 偏重 | 相对轻 |

**结论**：主选 **CodeFormer**——`-w` 权重是实打实的产品优势（用户/场景可调"修复强度 vs 忠实度"）。若目标机型实测过重，**GFPGAN 作为轻量退路**。

标准推理管线（官方）：
**人脸检测 → 对齐 crop 到 512×512 → CodeFormer 修复 → paste-back 贴回原图**。ONNX 导出有现成社区版（如 `yuvraj108c/Codeformer-Tensorrt` 提供 ONNX 下载）。

Sources: [sczhou/CodeFormer](https://github.com/sczhou/codeformer) · [NeurIPS 2022 paper](https://proceedings.neurips.cc/paper_files/paper/2022/file/c573258c38d0a3919d8c1364053c45df-Paper-Conference.pdf) · [CodeFormer-TensorRT/ONNX](https://github.com/yuvraj108c/Codeformer-Tensorrt)

---

## 3. 端侧推理接入（复用 EdiffiqaScorer 范式）

完全镜像现有 `EdiffiqaScorer`（`androidApp/.../domain/aesthetic/EdiffiqaScorer.kt`）：

- ONNX Runtime session + **NNAPI（失败兜底 CPU）**，`OrtSession` 跨调用复用。
- 模型下载走 `ModelPathConfig` + `LlmModelDownloadManager` + ModelScope（与 NIMA/eDifFIQA/MODNet 同一套）。
- 预处理 NCHW `(x-127.5)/127.5`，**输入/输出 512×512**。
- 唯一差异：**输出是 512² bitmap**（修复后人脸），而非标量。`score()` 形状照搬，把"打分"换成"还原"。

---

## 4. 复用盘点（已排查）

| 组件 | 可复用？ | 说明 |
|---|---|---|
| 多脸检测 `FaceDetectorManager.detectFacesWithLandmarks` | ✅ 直接 | 返回每人 `roi: RectF` + `landmarks5`；纯库 API，无 Compose/lifecycle 耦合（`engines/beauty-engine` impl + `beauty-api` 契约） |
| `FaceAligner` Umeyama 数学 `similarityMatrix()` | ✅ 需改 | **硬编码 112**（`SIZE`、`DST` 模板）；需扩到 512，且 **DST 要换成 CodeFormer/FFHQ 的 5 点模板**（≠ ArcFace-112 模板） |
| ONNX 会话范式 `EdiffiqaScorer` | ✅ 直接 | 就是 CodeFormer 推理封装的模板 |
| `MaskPostProcessor.feather()` | ◻️ 部分 | 可作 paste-back 蒙版的羽化原语（纯 FloatArray，可单测） |
| `PhotoProcessor` GPU 管线 | ❌ | 整图美颜/调色，无区域合成；**非 paste-back 的工具** |

---

## 5. 必须新建：Paste-back（最大工作量 / 最大风险）

**现状**：全代码库**无任何 paste-back / 逆向仿射 / seam blend / seamlessClone**；OpenCV 不是依赖。这是本方案最主要的新建部分。

需构建的 paste-back 流程：

1. **逆向仿射**：对每张脸保留前向 `similarityMatrix`（现为"算完即弃"，需改为逐脸保留），`Matrix.invert()` 把 512² 修复 crop 的坐标映射回原图坐标（`MnnLandmarkDetector` 内部已用 `Matrix.invert()` 做反投影，有先例）。
2. **人脸形蒙版**：用已有的 **106 点 landmark**（`MnnLandmarkDetector`）勾出紧贴脸型的轮廓（非简单椭圆），定义贴回区域。
3. **羽化**：复用 `MaskPostProcessor.feather()` 柔化蒙版边缘，消除接缝。
4. **alpha 合成**：把修复 crop 按蒙版叠回原图（`BackgroundComposer`/`CutoutComposer` 的 alpha 合成原语可借）。

**关键决策点**：paste-back 走 **纯 Kotlin/Canvas（CPU）** 还是用 **GPU shader**？CPU 实现简单、可单测、与 matting 原语同构，建议**先 CPU 跑通**；大图性能不达标再考虑 GPU pass。

---

## 6. 接入点（产品决策，待定）

| 方案 | 说明 | 取舍 |
|---|---|---|
| 编辑器"AI 修复"按钮（与"一键优化"并列） | 与现有抽卡闭环同入口，体验一致 | 推荐——复用编辑器 + 非破坏性 `EditRecipe` |
| 独立"老照片修复"入口（相册/单独页） | 强感知、引流 | 新建 UI 成本 |
| 会话 `edit_image` 扩展（"帮我把这张脸修清晰"） | 走 Agent 链路 | 与桶 1 定位一致，但会话路径目前连抠图都不可达，改造面大 |

**默认推荐**：编辑器按钮 + 非破坏性（修复结果作为 `EditRecipe` 一步，可撤销）。最终接入点为产品决策，正式开工前确认。

---

## 7. 双端归属（正式开工的前置）

KMP 改造（转换 plan 已随交付清理，git 历史可查）：

- **Phase 4 新建 `shared/`**，**`:runtime-core` 在 Phase 4 消亡**（逻辑迁 `shared/`，Android 特有沉 `shared/androidMain`）。
- 推理两端异构：Android ONNX Runtime / iOS MNN-Metal 或 CoreML。

→ **正式集成应落在 `shared/`**：`commonMain` 放修复契约（接口/编排/paste-back 纯逻辑），`androidMain`/`iosMain` 各放实际推理。**现在写进 `androidApp` 或 `runtime-core` 会被 Phase 4 搬迁**——这正是"先出方案、等双端架构定再开工"的原因。
→ 检测/对齐已在 `engines/`（C++ 跨端）+ `FaceAligner`（宜从 `androidApp` 迁 `shared/`）。

---

## 8. 建议节奏：先 Phase-0 本地 spike 验证质量（不等架构）

为不空等 Phase 4、又尊重"正式开工待架构"，建议：

- **Phase-0（Android 本地 throwaway）**：在 `androidApp` 临时接入 CodeFormer ONNX + 纯 CPU paste-back，**只验证一件事——真实照片上修复 + 贴回的画质与接缝是否可接受**。placement 明确为一次性、不进 `shared/`。
- **Phase-1（正式，Phase 4 后）**：按 §7 落 `shared/`，接抽卡度量闭环，产品化接入点。

> Phase-0 的唯一价值是**用最小代价把"paste-back 接缝 / 身份漂移 / 机型速度"这三个最大不确定性验证掉**，避免正式开工后才发现要返工。

---

## 9. 度量选优集成（闭环 Capability + Measurement）

修复后用度量层判"是否真变好"，镜像现有抽卡退化守卫（`OptimizeScorer`/`Guardrails`）：

- **eDifFIQA 人脸质量分应上升**（修复后更清晰 → 识别可用性更高）——这是天然闭环信号：修复→eDifFIQA 打分→未提升则不应用（退化守卫）。**顺便把 eDifFIQA 拉进修图度量循环**（补 [`AI_IMAGE_EDITING_CAPABILITY_GAP.md`](../../03-TECHNICAL-SPECS/AI_IMAGE_EDITING_CAPABILITY_GAP.md) §3 缺口）。
- 叠加 NIMA 美学轴 + 一道 full-reference 漂移上界（先 Laplacian 锐度差），防"修过头"。

---

## 10. 性能与红线

- **`[PERF]` 交互反馈 < 100ms**：修复必须**异步离线**（后台任务 + 进度 UI），不可阻塞主交互。
- **`[PRIVACY]`**：端侧推理，媒体不出端，守 ADR-008。唯一网络触点 = 下载模型文件（权重）。
- **模型体积**：CodeFormer 偏大，**需 INT8 量化评估**，精确体积待实测。
- **paste-back 性能**：纯 CPU 在大图上可能偏慢，Phase-0 实测决定是否上 GPU。

---

## 11. 风险与决策点清单

| # | 风险/决策 | 状态 |
|---|---|---|
| R1 | paste-back 接缝质量（最大不确定性） | Phase-0 验证 |
| R2 | 低 `-w` 下身份漂移（"换了个脸"） | 默认 0.5 起手，实测调 |
| R3 | 模型体积/速度在目标机型不可接受 | 实测；退路 GFPGAN |
| D1 | 接入点（编辑器按钮 / 独立入口 / 会话） | 产品决策，默认编辑器按钮 |
| D2 | paste-back CPU vs GPU | 默认 CPU 先跑通 |
| D3 | 512² 对齐模板（CodeFormer/FFHQ 5 点） | 实施时按 FFHQ 标准 |

---

## 12. 相关文档 / 代码

- 缺口上游：[`AI_IMAGE_EDITING_CAPABILITY_GAP.md`](../../03-TECHNICAL-SPECS/AI_IMAGE_EDITING_CAPABILITY_GAP.md)
- 双端架构前置：KMP 转换 plan（Phase 4 `shared/`，已随交付清理，git 历史可查）
- 抽卡度量：[`AI_OPTIMIZATION.md`](../../03-TECHNICAL-SPECS/AI_OPTIMIZATION.md) §11.5；`optimize/gacha/OptimizeScorer.kt`、`Guardrails.kt`
- 复用代码：`domain/aesthetic/EdiffiqaScorer.kt`（ONNX 范式）、`FaceAligner.kt`（Umeyama，需改 512）、`domain/matting/MaskPostProcessor.kt:feather`、`engines/beauty-engine/.../facedetect/`（检测）

---

## 13. 修订历史

| 日期 | 变更 |
|---|---|
| 2026-08-08 | 初版：选型 CodeFormer、端侧接入、复用盘点、paste-back 新建方案、双端归属、Phase-0 spike 建议、度量闭环 |
