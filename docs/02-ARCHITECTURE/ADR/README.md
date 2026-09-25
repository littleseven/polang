# ADR 索引（架构决策记录）

> **整理口径（2026-08-23）**：只保留仍 govern 现状的决策记录；纯历史篇已删除，编号永久留空不复用，历史细节靠 git 追溯。
> 本地/远程推理演化史（协议分离 → 本地收缩 → 链路隔离 → 端侧文本 LLM 移除 → Koog 迁移）的**现役结论**收敛于 [ADR-005](./ADR-005-local-remote-inference-split.md) + [AGENT_ARCHITECTURE.md](../AGENT_ARCHITECTURE.md)（SSOT）。

## 现役 ADR（10 篇）

| 编号 | 标题 | 一句话决策 |
|------|------|-----------|
| [ADR-001](./ADR-001-beauty-engine-architecture.md) | 美颜引擎单模块分层架构 | App → `beauty-api`/`beauty-engine:api` 单向依赖；GL/EGL 全部封装在 `render/` 包内 |
| [ADR-002](./ADR-002-opengl-offscreen-unified-pipeline.md) | OpenGL 离屏渲染统一管线 | 拍照复用预览 `BeautyRenderer` 多 Pass 管线（`skipCopyPass`），保证预览/拍照一致性 |
| [ADR-003](./ADR-003-coordinate-system-management.md) | 坐标系管理 | 图像坐标系与人脸坐标系并存但严禁混用；跨系转换须显式；渲染/算法层必须图像坐标系 |
| [ADR-005](./ADR-005-local-remote-inference-split.md) | 远程推理协议标准化 + 产品重心迁移 | 远程推理走标准 OpenAI Chat Completions（tool_calls/流式/多轮）；产品重心相机 → 相册+图片编辑 |
| [ADR-007](./ADR-007-natural-language-photo-search.md) | 自然语言相册搜索 | 端侧 CV 标签 + LLM 意图标准化（SearchIntent）双层架构；实现 SSOT 见 GALLERY_SEARCH.md |
| [ADR-008](./ADR-008-privacy-redline-media-only.md) | 隐私红线（禁媒体上传） | 只禁用户图片/视频文件上远程模型；文本/元数据/相册摘要可走远程；守卫测试防回归 |
| [ADR-011](./ADR-011-retire-non-ui-driver-tests.md) | 退役非 ui-driver 测试 | UI 自动化只保留 `ui-driver`（Accessibility 结构化文本驱动）+ JVM 单测 |
| [ADR-012](./ADR-012-unify-conversation-memory.md) | 统一会话记忆 | 每条链路有且仅有一套对话记忆；事实记忆/人物关系与对话记忆职责分离 |
| [ADR-013](./ADR-013-kmp-architecture-contract.md) | KMP 架构契约 | 只共享业务逻辑绝不共享 UI（不做 CMP）；跨 Swift seam 必须扁平；commonMain 纯度构建期守卫 |
| [ADR-014](./ADR-014-chat-rich-rendering-hybrid.md) | Chat 富内容渲染（方案 B） | 正文原生富渲染（替换 compose-markdown）；Agent 生成 UI 走 RICH_HTML 沙箱卡（卡片内直接交互，`<a>` 外链进全屏落地页；L1/L2 风格分级）；不做全量 HTML 会话 |

## 已删除的历史篇（2026-08-23）

| 编号 | 原标题 | 删除理由 |
|------|--------|---------|
| ~~ADR-004~~ | Adreno GPU 争抢问题 | 三当事方（ncnn Vulkan、ggml/llama.cpp、端侧文本 LLM）已全部删除，争抢场景不复存在 |
| ~~ADR-006~~ | 本地/远程指令体系包级隔离 | 本地指令体系已随端侧文本 LLM 整体删除，单体系无隔离对象；远程编排 SSOT 在 AGENT_ARCHITECTURE.md |
| ~~ADR-009~~ | 本地 LLM 收缩至相机场景 | 被「本地 LLM 完全移除（2026-08-02）」超越，无残余决策效力 |
| ~~ADR-010~~ | 远程/本地链路严格隔离 | 隔离以「本地链路整体删除」方式终结，隔离对象消失 |

## 维护规则

- 新 ADR 编号自 **014** 起递增，已删除编号不复用。
- ADR 记录**决策**（why），实现细节归 TECH_SPECS / AGENT_ARCHITECTURE.md / 模块 AGENTS.md，ADR 内只留链接。
- 决策被推翻时：若新决策有独立价值 → 新开 ADR 并在旧 ADR 头部标注 Superseded；若旧 ADR 全文失去决策效力且无追溯刚需 → 直接删除并更新本索引（2026-08-23 先例）。
