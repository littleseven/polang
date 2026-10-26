# OpenUI 自研客户端对齐 androidx A2UI Compose 适配层设计

> **日期**：2026-10-26
> **状态**：P1 实施中（P2~P4 规划）
> **上位约束**：ADR-013（commonMain 纯度：禁 androidx.compose 入 shared）/ ADR-016 / [PRIVACY] / §3.4 工作区隔离
> **动机**：OpenUI 线渲染器全自研（spec《openui-wire-format-toggle-design》D 决策），用户定方向：
> 自研应重点参考 androidx.a2ui 官方 Compose 适配方法。本文盘点代差并给出对齐路线。

## 1. androidx 适配层机制摘要（参考基准）

五个 artifact 分层（a2ui-model 协议 / a2ui-engine 模型 / compose-runtime 桥 / compose-ui 骨架 /
material3-a2ui 皮肤），核心机制四条：

1. **数据模型即 snapshot state**：`scope.bind()` 是对数据模型 path 的粒度化订阅，path 变更
   只触发订阅者局部重组；`bindUpdater()` 生成回写函数，无绑定即 `enabled=false`（只读语义）。
2. **流式就绪门控**：`updateComponents` 逐信封到达，前向引用容忍，`isReady()` 未就绪子树挂
   LoadingIndicator。
3. **逐节点错误兜底**：单组件失败渲 ErrorFallback，兄弟节点不受影响。
4. **组件契约接口即目录**：`A2uiComponent`（name/description/properties/isReady/Content），
   schema 从代码生成；高层组件经「默认 Content 绑参 → 抽象 TypedContent 渲染」留扩展点
   （我们覆写 List.TypedContent 封顶即踩此点）。

## 2. 现状盘点（自研客户端 vs 基准）

| 维度 | androidx 基准 | 自研现状 | 代差 |
|------|--------------|----------|------|
| 状态 | snapshot state 粒度订阅 | eval 期已保留 `OpenUiValue.Binding`、渲染期 `state[name]` 解析（绑定**已是渲染期**）；但宿主每次 action 后整表拷贝快照 + 无条件换新 root 树 | 半对齐：绑定时机对，观测机制错 |
| 流式 | 逐信封 + 前向引用 + isReady | 整帧收完一次性 prepare（CHAT_CARD_CATALOG「整帧，无流式」） | 未对齐 |
| 错误 | 逐节点 ErrorFallback | 任一 handle 失败整卡 Degraded（宁缺毋滥，产品决策保留）；prop 级「丢组件不炸卡」已有 | 部分对齐 |
| 契约 | 接口即目录 | schema 数据表（OpenUiLibrary 1292 行）+ 巨型 when 分发（OpenUiNodeRenderer 1572 行），名字字符串对齐 | 未对齐 |

**关键事实**（2026-10-26 读码确认）：`OpenUiEval.kt:567` 起，BINDABLE_PROP_TYPES 上的 `$var`
求值后保留为 `OpenUiValue.Binding`，渲染器组合期读 `state[binding.name]`——**绑定解析早已在
渲染期**，缺的只是 state 容器的可观测性与 root 树的重组门控。

## 3. P1：响应式状态（本周期实施）

**约束**：ADR-013 禁 androidx.compose 入 commonMain → `SnapshotStateMap` 不能进
`OpenUiSession`，对齐必须在宿主侧（androidApp）做边界转换。

**设计**（`OpenUiCardHost.OpenUiReadyContent`）：

1. `remember(session) { mutableStateMapOf() }` 作组合期 state 容器，初始 putAll(session.state)；
   action 后**逐键增量同步**（删消失键、写变更键）而非整表替换——SnapshotStateMap 的 per-key
   读追踪使 `$cb3` 变更只重组读它的 CheckBox，其余节点不动。
2. root 树**相等门控**：`reevaluate()` 产物与当前 root 做 data class 结构化相等比较，相等则
   不换引用——绑定全保留为 Binding 的卡在纯交互 action 后树不变，零树级重组（绕开
   ComponentVal 含 Map 属非 stable 类型、Compose 跳过失效的问题）。烘焙引用场景
   （如 `Text("当前选中: " + $selected)`）树必变，照常全重组，行为不回归。
3. `applyAction`/`reevaluate` 引擎逻辑不动（程序微小全量重求值是既有 M1 简化，保留）。

**验收**：既有 openui 测试全绿；新增增量同步辅助函数单测；真机交互卡（checklist）勾选/
输入/拖滑行为不回归，TextField 输入中其它组件 action 不丢焦点。

## 4. P2~P4 规划（后续周期）

- **P2 流式**：Parser 增量喂入 + Eval「引用未就绪→挂起子树」+ isReady 门控渲染。
  触服务端/端侧协议双侧（SSE 分片帧），工作量大，独立 spec。
- **P3 逐节点兜底**：prop 级失败从「丢组件」升级为「渲 ErrorFallback 占位」（可观测性更好）；
  数据缺失整卡降级（宁缺毋滥）保留不变。
- **P4 组件契约化**：每个组件收敛为「schema+渲染同处」的类，巨型 when 退化为注册表查找；
  与 IconMap 四处同源漂移问题并案解决。

## 5. 不做的事

- 不把 compose runtime 引入 commonMain（ADR-013 红线，iOS K/N 编译安全）。
- 不动 OpenUiSession 的纯 Kotlin API 形态（iOS 未来复用前提）。
- P1 不引入增量依赖图（引擎注释明示不建，程序规模不需要）。
