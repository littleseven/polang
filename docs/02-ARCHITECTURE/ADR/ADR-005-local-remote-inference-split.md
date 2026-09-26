# ADR-005: 远程推理协议标准化与产品重心迁移

**状态**: 已实施（两大决策均 govern 现状；本地链路部分已于 2026-08-02 整体删除）  
**日期**: 2026-06-15  
**最后整理**: 2026-08-23（删除已亡本地链路实现细节与迁移清单，历史版本见 git）  
**决策**: RD

---

> ## ⛔ 状态更新（2026-08-02）：本地链路已整体删除
>
> 本 ADR 原确立「本地/远程双链路」，现已演进为**文本推理全远程**：端侧文本 LLM（Qwen3.5-2B）及整条本地链路（`LocalInferencePipeline`/`LocalCameraAgent`/`LocalCommandParser`/`LocalPromptBuilder`/`IntentCache`、`AiAgentMode.LOCAL`、qwen3_5_2b 模型下载条目与设置 UI）已完全移除（commit 2fb1f299e）。相机指令同样改走远程 tool_calls：`AgentOrchestrator.processCameraInput` → `RemoteReActAgent` + `CameraToolService`（相机场域 @Tool 工具集：capture/adjust_beauty/switch_filter/adjust_zoom/flip_camera 等）→ `CapabilityRegistry.dispatch`。
> `LocalLlmEngine` 仅保留 `imageInference`（Qwen3-VL-2B 端侧 VLM 打标，TAG Pass3），位于 `:shared` androidMain。
> 后续 2026-08 远程编排由自维护 langchain4j fork 迁移至 **Koog**（JetBrains KMP Agent 框架），`:agent-core` 模块删除（commit 1cbe9353）；`:runtime-core` 整体并入 `:shared` 后删除。
> **现役架构唯一事实来源**：[`docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md`](../AGENT_ARCHITECTURE.md)（v4.1+）。本 ADR 仅保留仍 govern 现状的两大决策：**决策 1（远程协议标准化）**与**决策 2（产品重心迁移）**。

## 1. 背景与问题陈述

重构前 `InferenceRouter` 同时承载本地与远程推理路由，统一的 `PromptBuilder`/`ToolCallingChatLanguageModel`（Prompt 注入模拟 tool_calls）/`ToolCallingOutputParser`（5 种解析策略，~570 行）为两套模型生成妥协协议：本地小模型被注入过量 tool_calls 修饰符，远程模型被限制为简单 JSON 数组格式，原生能力（tool_calls、流式、多轮、system prompt）无法释放。同时产品重心已从相机漂移：端侧小模型在相机实时场景体验受限，相册/图片编辑才是 AI 价值场景。

## 2. 决策

### 决策 1: 远程推理采用标准 OpenAI Chat Completions 协议（现役）

远程链路不另造协议，直接采用标准 OpenAI Chat Completions API：

| 维度 | 远程推理（现役） |
|------|---------|
| **协议** | 标准 OpenAI Chat Completions（system/user/assistant 消息、tool_calls、流式、多轮对话） |
| **编排** | Koog `AIAgent`（`KoogChatAgent` / `KoogReActAgent`，`:shared` commonMain）；DeepSeek 等 OpenAI 兼容供应商 |
| **约束方式** | OpenAI 原生协议 + Tool Schema |
| **输出解析** | 标准 JSON 反序列化（agent 循环内直接 `CapabilityRegistry.dispatch`） |

由此删除的耦合组件（~1500 行）：`InferenceRouter`、`ToolCallingChatLanguageModel`、`ToolCallingOutputParser`、`ToolPromptBuilder`、`ToolCallingConfig`、`ToolCallingMode`、`AdaptiveStrategySelector`。

### 决策 2: 产品重心迁移 — 从相机到相册与图片编辑（现役）

![产品重心迁移](assets/diagrams/adr005-focus-shift.png)

核心原因：
1. **AI 在相册场景的不可替代性**：自动识别、智能分类、美颜建议、OCR 提取是 AI 天然优势场景。
2. **延迟容忍度不同**：相机实时交互 <100ms 端侧小模型难以稳定满足；相册场景 1-3s 可接受，远程模型可充分发挥。
3. **编辑场景资产复用**：`beauty-engine` 美颜 Shader 管线经 `PhotoProcessorImpl` 离屏渲染直接服务图片编辑（ADR-002）。
4. **体验完整性**：拍照后的编辑/管理/分享是自然延续。

导航结构随之调整：应用首页为相册（相机作为内容供给入口之一）。产品优先级与交互细节以 `PRODUCT.md` / `docs/01-PRODUCT/FEATURES.md` 为准。

## 3. 状态

| 阶段 | 状态 | 日期 |
|------|------|------|
| Phase 1: 协议分离 + 代码清理 | ✅ 已完成 | 2026-06-18 |
| Phase 2: 图片编辑 AI 集成 | ✅ 已完成 | 2026-06 |
| Phase 3: 相机场景精简（首页相册化） | ✅ 已完成 | 2026-06 |
| 本地链路整体删除 + Koog 迁移 | ✅ 已完成 | 2026-08（见顶部状态更新） |

## 4. 相关文档

- `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` — Agent 运行时架构（**现役架构 SSOT**）
- `shared/AGENTS.md` — Agent 编排层 KMP 模块规范（原 `agent-core`/`runtime-core` 已删除）
- `docs/04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md` — 命令 → Capability 路由 SSOT
- `ADR-001`/`ADR-002` — 美颜引擎分层与离屏渲染管线（图片编辑复用基础）
- `ADR-008` — 隐私红线（远程推理的边界约束）
