# ADR-015: 意图路由契约与意图路由器——理解归 LLM、策略归代码

**状态**: 已实施（M1 止血 + M2 路由器主干，2026-09-26）
**日期**: 2026-09-25（spec 定稿）/ 2026-09-26（M1/M2 实施）
**决策**: 用户（spec 逐节评审认可）
**依赖**: ADR-005（远程推理协议）、ADR-007（自然语言搜索）、ADR-008（隐私红线）、ADR-013（KMP 契约）
**实现 SSOT**: `docs/superpowers/specs/2026-09-25-intent-routing-contract-design.md`；架构叙述 `AGENT_ARCHITECTURE.md` §2.4.4

---

## 1. 背景

「儿子的照片」事故复盘（2026-09-25）：用户说「看下我儿子的照片」，chat 链路未出横滑卡片。根因不是单点 bug，而是五层结构性缺陷（spec §2 事故链 D1-D5）：

- **D1 规则矛盾**：system prompt 互斥规则让模型在「search_media 直搜」与「run_gallery_script 精确」之间自由心证；
- **D2 意图→UI 契约缺失**：模型不知道 search_media 的结果会自动渲染横滑卡片，以为必须脚本拿 ids；
- **D3 工具面不对齐**：search_media 无人物/时间结构化参数，精确搜索只能绕脚本；chat 工具面挂着必败的 view_media（无 delegate）；
- **D4 会话状态隐式耦合**：refine 依赖模型记住上一轮，基数缺失时静默全局重搜；
- **D5 flash 档指令遵循不稳**：34k 字符 system prompt 下小模型规则遵循率不足。

## 2. 决策

**LLM 管语义理解（输出意图），代码管路由策略（查表执行）**：

1. **意图契约表**（`ChatIntentContract`）：10 意图闭集 × UI 产出物契约 × 工具白/黑名单的声明式纯数据，allowed∩forbidden=∅ 由测试机器校验——规则矛盾在设计上不可能再发生。
2. **意图路由器**（`IntentRouter`）：本地信号门控（寒暄直通零延迟）→ pattern 捷径（最热句式短路，负面语素防劫持）→ 专用 LLM 闭集分类（~1k token prompt、temperature=0、JSON 强校验、1.5s 硬超时 + schema 重试 1 次 + 低置信降级）。一切失败同路回落完整 agent loop——**路由器永不拦截能力**。
3. **确定性策略层**（`ChatRoutingPolicy`）：意图→命令查表执行。M2 仅 VIEW_PHOTOS/REFINE_RESULTS 直执（经 `ChatToolService.dispatchCommandWithTrace` 与 tool_calls 同一 dispatch 链路）；其余意图与 secondary 双产出物回落全量 agent（M3 分支化再逐个接管）。
4. **路由可观测**：每回合判定落 `routing_audit_log`（仅路由维度指标、不含用户 query 原文，守 ADR-008），路由器 LLM 调用落 `llm_call_log`（source=`chat-intent-router`）——「护栏前路由正确率」可度量，为 M3 裁剪提供数据。

## 3. 为什么不选替代方案

- **不修 prompt 了事**：D1-D4 是结构问题，措辞修补无法消除「模型在互斥规则里自由选路」的自由度；
- **不让路由器直接输出工具调用**：退化为把选路问题搬家，flash 档同样不稳；意图闭集（10 类）比工具面（35+）小一个量级，分类错误率与 prompt 长度都显著更低；
- **不做本地小模型分类**：端侧文本 LLM 已移除（2026-08-02），不为其回归；门控+pattern 已覆盖零延迟需求。

## 4. 影响面

- 路由器挂在 `RemoteChatEngine.streamChat`，覆盖 **Android chat 页与 iOS ChatAgentBridge 两个入口**；飞书 `processRemoteImInput` 走 `KoogReActAgent` 独立链路、**不经 streamChat，行为不变**（M2 验收观测口径以此为准）；
- 直执路径跳过全量 agent loop：最热意图（看照片/细化）时延 ≈ 路由 LLM 一跳（≤1.5s 超时兜底）+ 端侧搜索，不再走多轮 tool_calls；直执不产 LLM 总结，用户气泡由平台层按 `DirectRouteReply` 本地化渲染（I18N 红线），该轮 user+observation 补写 Koog 会话记忆防多轮指代断裂；
- iOS 侧自动生效（shared commonMain），工具清单 `ChatToolManifest` 已同步（view_media 移除、search_media 参数对齐）；搜索基数存在性经 `IosChatGalleryCapability.hasSearchBase` 透传进路由状态。
