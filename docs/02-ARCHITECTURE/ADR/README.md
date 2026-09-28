# ADR 索引（架构决策记录）

> 只保留仍 govern 现状的决策记录；被推翻且无追溯刚需的决策直接删除，编号永久留空不复用。

## 现役 ADR（11 篇）

| 编号 | 标题 | 一句话决策 |
|------|------|-----------|
| [ADR-001](02-ARCHITECTURE/ADR/ADR-001-beauty-engine-architecture.md) | 美颜引擎单模块分层架构 | App → `beauty-api`/`beauty-engine:api` 单向依赖；GL/EGL 全部封装在 `render/` 包内 |
| [ADR-002](02-ARCHITECTURE/ADR/ADR-002-opengl-offscreen-unified-pipeline.md) | OpenGL 离屏渲染统一管线 | 拍照复用预览 `BeautyRenderer` 多 Pass 管线（`skipCopyPass`），保证预览/拍照一致性 |
| [ADR-003](02-ARCHITECTURE/ADR/ADR-003-coordinate-system-management.md) | 坐标系管理 | 图像坐标系与人脸坐标系并存但严禁混用；跨系转换须显式；渲染/算法层必须图像坐标系 |
| [ADR-005](02-ARCHITECTURE/ADR/ADR-005-local-remote-inference-split.md) | 远程推理协议标准化 + 产品重心迁移 | 远程推理走标准 OpenAI Chat Completions（tool_calls/流式/多轮）；产品重心相机 → 相册+图片编辑 |
| [ADR-007](02-ARCHITECTURE/ADR/ADR-007-natural-language-photo-search.md) | 自然语言相册搜索 | 端侧 CV 标签 + LLM 意图标准化（SearchIntent）双层架构；实现 SSOT 见 GALLERY_SEARCH.md |
| [ADR-008](02-ARCHITECTURE/ADR/ADR-008-privacy-redline-media-only.md) | 隐私红线（禁媒体上传） | 只禁用户图片/视频文件上远程模型；文本/元数据/相册摘要可走远程；守卫测试防回归 |
| [ADR-011](02-ARCHITECTURE/ADR/ADR-011-retire-non-ui-driver-tests.md) | 退役非 ui-driver 测试 | UI 自动化只保留 `ui-driver`（Accessibility 结构化文本驱动）+ JVM 单测 |
| [ADR-012](02-ARCHITECTURE/ADR/ADR-012-unify-conversation-memory.md) | 统一会话记忆 | 每条链路有且仅有一套对话记忆；事实记忆/人物关系与对话记忆职责分离 |
| [ADR-013](02-ARCHITECTURE/ADR/ADR-013-kmp-architecture-contract.md) | KMP 架构契约 | 只共享业务逻辑绝不共享 UI（不做 CMP）；跨 Swift seam 必须扁平；commonMain 纯度构建期守卫 |
| [ADR-015](02-ARCHITECTURE/ADR/ADR-015-intent-routing-contract.md) | 意图路由契约与路由器 | LLM 管语义理解（意图闭集分类），代码管路由策略（查表执行）；门控+pattern+1.5s 超时降级，路由器永不拦截能力 |
| [ADR-016](02-ARCHITECTURE/ADR/ADR-016-chat-parts-model-mainstream-alignment.md) | Chat 消息内容模型与渲染架构（整合原 ADR-014） | 数据层对齐 Vercel parts（有序 parts 数组 + chunk 三段式 + 工具状态机 + UIMessage/ModelMessage 双层）；渲染层对齐 ChatGPT（markdown AST→原生受控组件 + 卡片独立 block + Turn 聚合 + WebView 永不渲染正文）；沙箱卡双形态 + D5 L1/L2/L3 样式分级（LLM 永不产代码只产数据）；不做全量 HTML 会话/文内锚点/JS 桥/消息树 |

## 维护规则

- 新 ADR 编号自 **017** 起递增，已删除编号不复用。
- ADR 记录**决策**（why），实现细节归 TECH_SPECS / AGENT_ARCHITECTURE.md / 模块 AGENTS.md，ADR 内只留链接。
- 决策被推翻时：若新决策有独立价值 → 新开 ADR 并在旧 ADR 头部标注 Superseded；若旧 ADR 全文失去决策效力且无追溯刚需 → 直接删除并更新本索引。
