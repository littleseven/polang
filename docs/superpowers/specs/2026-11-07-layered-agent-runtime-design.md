# 分层 Agent Runtime（One Agent, Two Runtimes）设计 spec

> **日期**：2026-11-07
> **状态**：在途活跃 spec（M1/M1.5/M2 已合 main；M3~M6 未开始）
> **分支**：M1 = `feat/layered-agent-runtime`（已合）；M1.5/M2 = `feat/layered-runtime-m2`（已合）
> **起因**：用户要求统一本地 Agent 与远程（云端）Agent 的代码——「出于成本考虑仍在端上跑 Agent，但差异仅在一个开关；从原理上同构，仅仅是 runtime 在云上或端上的区别」。用户补充关键修正：**Agent 架构是分层的，开关控制各层跑在端上还是服务端**（不是整体两态）。

---

## 1. 目标模型：分层 × 逐层放置开关

Agent 栈拆为七层。每一层有**端/云两个实现**（或天然同构一份代码），部署形态由「各层放哪」组合而成——本地 Agent = 全部层在端；云端 Agent = 推理/记忆在云、工具执行按 locality 分布、产出协议两端各自渲染。**同一份代码、同一套协议，只有 runtime 落点不同。**

| 层 | 职责 | 端侧实现现状 | 云端实现现状 | 同构路径 |
|----|------|------------|------------|---------|
| **L1 契约层** | 工具名/参数/错误前缀/截断/投影承诺 | shared `CloudAgentToolContract`（SSOT） | server `DeviceToolService` **字面量副本** | **M1**：server 真引用 shared 常量 |
| **L2 策略层** | ReAct 图结构（节点/边/纠正回环） | shared `poLangSingleRunStrategy` | server `cloudAgentStrategy` **移植副本**（+流式+ProgramGuard） | **M1**：shared 参数化策略工厂，两端各传配置 |
| **L3 执行层** | LLM 执行器组装（capabilities/钳制/thinking/路径） | shared `RemoteModelFactory` | server `AgentExecutorFactory` **规则副本**（+CF_AIG/chatCompletionsPath） | **M1**：shared 工厂参数化，server 变适配器 |
| **L4 工具层** | 工具集 + locality 声明 + 执行桥 | `ChatToolService` / `CloudAgentToolCatalog`（LOCAL/CLOUD 已声明） | `DeviceToolService`（端工具云桥）+ `ServerTools` | **M2**：locality 泛化——同一目录声明工具跑在端/云，开关即「哪个 runtime 持有 executor」 |
| **L5 记忆层** | 会话记忆组装/压缩/投影 | shared `KoogMessageMemory`（DataStore store） | server 事件溯源 `agent_event` + `AgentProjection` | **M3**：`MemoryStore` 接口两侧实现，组装/压缩算法已在 shared 共用 |
| **L6 流式协议层** | 事件→parts 的流式下行 | `ChatStreamEvent`→`TurnStreamEvent`（端内 Flow） | SSE 事件帧（`AgentEvent`） | **M4**：事件模型合一，端内 Flow 与 SSE 只是同一事件的两种传输 |
| **L7 产出协议层** | 卡片/GenUI 产出形态 | OpenUI 卡 + HTML 卡 + parts | OpenUI 回合原生（turn-native only） | 已拍板：**不合线为「一份形态」**，产出协议下沉为「产出 Profile」配置（见 §3 决策 1） |

三个注入点（差异全部收敛于此）：

1. **LlmChannel**：端 `RemoteModelFactory`（网关/BYOK 直连）↔ 云 `AgentLlmTarget`（渠道托管密钥/BYOK 透传）。M1 后共用同一工厂。
2. **ToolLocality**：`CloudAgentToolCatalog` 已有 LOCAL/CLOUD 声明；M2 泛化为「同一工具目录，executor 按 runtime 解析」。
3. **MemoryStore**：端 DataStore 实现 ↔ 云 `agent_event` 事件溯源实现。M3 抽象。

**开关的物理形态**：不是 if/else 撒满代码，而是 **TurnRunner 的选择**——`LocalTurnRunner`（全端层）vs `CloudTurnRunner`（云推理+记忆、端工具经工具桥）。各层内部不出现「云端特判」，差异只在组合根注入哪个实现。

## 2. 里程碑

| 里程碑 | 内容 | 状态 |
|--------|------|------|
| **M1** | server 经 composite build 依赖 `:shared` jvm 变体；消灭 L1/L2/L3 三份双写（契约字面量/策略移植/执行器规则） | ✅ 已合 main（`40eafd36e`） |
| **M1.5** | OpenUI 白名单/prompt 资产（组件/Icon 名/动作词表/fragment 闭集/ICON_NAMES_TEXT）server↔shared 字面量副本收口为真引用 | ✅ 已交付（plan `2026-11-07-layered-agent-runtime-m15-m2.md`） |
| **M2** | 工具 locality 泛化：目录驱动 executor 解析，端云工具同一声明源（`CloudAgentToolCatalog` 五声明 + descriptor 生成；server 工具桥 @Tool 反射退役改 `CatalogBridgeTool`，双端 executor 参数目录化；**裁定**：server_time 纯服务端本地工具不入目录，目录保持「端云边界工具」单一语义） | ✅ 已交付（同 M1.5 plan） |
| M3 | MemoryStore 抽象：记忆组装/压缩（已在 shared）两侧复用，store 实现各端自理 | 未开始 |
| M4 | 流式协议合一：`ChatStreamEvent`/`TurnStreamEvent` 与 SSE 事件帧同源 | 未开始 |
| M5 | ViewModel/TurnRunner 合一：端侧开关选择 Local/Cloud TurnRunner | 未开始 |
| M6 | iOS 经 SKIE 消费同一 AgentCore | 未开始 |

## 3. 已拍板决策（与用户对齐，勿重开）

1. **产出协议（L7）不合为一份形态**：端侧本地 Agent 的产出形态（parts 卡）与云端 turn-native OpenUI 是两种产品形态，收敛为「产出 Profile」配置而非统一实现。M1 不触碰。
2. **开关硬两态、不漂移**：TurnRunner 只有 Local/Cloud 两档，不做运行时按请求动态分流（避免三态漂移）。
3. **会话归属创建它的 runtime**：云端会话（`agent_session` 事件溯源）与端侧会话（Room）有意独立（ADR-018），状态层不统一。
4. **Koog 版本两端锁死 1.3.0**：server 直接依赖与 shared `libs.versions.toml` 手工对齐（双构建不共享版本目录，注释互链）。

## 4. M1 详细范围

### 4.1 构建接线（前提）

- `shared/build.gradle.kts`：设 `group = "com.mamba.picme"`、`version = "1.0.0"`（composite build 模块替换坐标；project 依赖消费方 androidApp/engines 不受影响）。
- `server/settings.gradle.kts`：`includeBuild("..")` 挂 monorepo 根构建（server 未被根 include，无环）。
- `server/build.gradle.kts`：`implementation("com.mamba.picme:shared:1.0.0")`——kotlin("jvm") 消费方经 Gradle Module Metadata 自动选中 jvm 变体。
- **连带约束（记入 server/AGENTS.md）**：构建 server 自此需要本机 Android SDK（:shared 配置含 android target）；部署构建本就在开发机执行（`deploy.sh` 开发机构建→rsync），VPS 只跑 jar，不受影响。

### 4.2 L1 契约：DeviceToolService 真引用 shared 常量

- shared `CloudAgentToolContract` 新增「云端 LLM 可见描述 SSOT」区：3 个工具级 `@LLMDescription` 全文 + 10 个参数级描述，自 server `DeviceToolService` **逐字**迁入为 `const val`。
- server `DeviceToolService`：`@Tool(customName = …)` 与 `@LLMDescription(…)` 全部改引用 shared const（Kotlin const 跨模块可作注解实参）；`"error:"` 字面量改 `CloudAgentToolContract.ERROR_PREFIX` 组合。删除 KDoc 中「字面量副本/双向同步」表述。
- shared `CloudAgentToolContract`/`CloudAgentToolCatalog` KDoc 同步改写（三方消费 → server 真引用）。

### 4.3 L2 策略：参数化策略工厂

- shared `KoogReActStrategy.kt`：
  - 新增 `FinalOutputCorrector` 接口（`invalidOutputError(text): String?` / `markCorrected()` / `correctionPrompt(error): String`，每轮新实例、有状态计数）。
  - 新增 `PoLangReActStrategyConfig`（`name` / `streaming` / `finalOutputCorrector` / `onStreamingFallback`）。
  - `poLangSingleRunStrategy(config)` 提升 public。**默认路径（非流式+无纠正）保持现行内建节点逐字节一致**（端侧 chat 零行为变化）；非默认路径 = server `cloudAgentStrategy` 现行图结构（流式节点 + 可选纠正节点/边，纠正边声明序先于 finish 边）。
- server `CloudAgentStrategy.kt`：`cloudAgentStrategy(programGuard)` 变一行调用（`streaming=true` + `programGuard.asCorrector()` + slf4j fallback 回调）；删本地 `requestLLMStreamingOrOnce`；`cloudAgentPrompt` 保留本地（`ProjectedMessage` 是 server 类型）。
- `ProgramGuard` 增 `asCorrector(): FinalOutputCorrector` 适配。

### 4.4 L3 执行器：工厂参数化 + server 变适配器

- shared `RemoteModelConfig`：增 `chatCompletionsPath: String = ""`（空 = Koog 客户端默认 `v1/chat/completions`，端侧存量行为不变）。
- shared `RemoteModelFactory.createKoogExecutor`：
  - 增参 `httpClientFactory: KoogHttpClient.Factory? = null`——非空优先（server 传 CF_AIG 包装或裸 Ktor 工厂；端侧不传走原 `createKoogHttpClientFactory(extraHeaders)` 路径）。
  - OpenAI 分支：`chatCompletionsPath` 非空时显式传入 `OpenAIClientSettings`。
  - thinking-disabled 规则改**并集**：`providerId 为空（旧配置历史行为保留）‖ providerId ∈ 白名单 ‖ baseUrl 含 tokenhub/moonshot/deepseek`。端侧全部存量路径行为逐一同口径（空 providerId 或白名单恒注入；openai-official/anthropic 不注入）；server 适配层传非空 providerId（`"server-cloud-agent"`），由其原 host 子串规则精确接管。
- server `AgentExecutorFactory.create`：映射 `AgentLlmTarget → RemoteModelConfig`（authStyle 只决定 httpClientFactory 包装），调 shared 工厂，`BuiltExecutor` 外形不变（AgentRunner 无感）。删 `clampTemperature`/`THINKING_DISABLED_HOSTS`/`MAX_TOKENS` 三份副本；`CfAigKoogHttpClientFactory` 保留 server 侧（CF_AIG 是云端渠道关切）。

### 4.5 非目标（M1 明确不做）

- 不改端侧 chat 策略形态（不引入流式节点/纠正节点）。
- 不动 L4-L7（工具 locality 泛化/记忆抽象/流式协议/产出 Profile）。
- 不动 `AgentRunner`/`AgentRoutes`/`AgentProjection`/`ProgramGuard` 语义。
- 不做 Koog 版本目录跨构建共享（手工对齐 + 注释互链）。
- server jar 行为零变化，**无需生产部署**（但 `deploy.sh` 链路下次部署自然携带）。

### 4.6 验收

1. `JITPACK=true ./gradlew :shared:jvmTest` 绿（含新增：thinking 注入矩阵 / chatCompletionsPath 透传 / 策略配置）。
2. `JITPACK=true ./gradlew :shared:assemble` 绿（裸 @Volatile 类坑只此暴露）。
3. `./gradlew -p server build` 绿（server 全部存量测试 + composite build 生效）。
4. `./gradlew :androidApp:compileDebugKotlinAndroid`（或等价任务）绿（shared API 变化为纯增量）。
5. 行为钉住：server 三处双写删除后，注解文本/策略图/执行器参数与原逐字一致（测试断言）。

## 5. 风险与对策

| 风险 | 对策 |
|------|------|
| composite build 使 server 构建依赖 Android SDK/全插件链 | 部署构建在开发机（有 SDK）；server/AGENTS.md 显式记录 |
| shared jvm 变体解析错配（拿到 metadata 而非 jar） | kotlin("jvm") 消费方自带 platform.type=jvm 属性；构建验证步骤 3 钉住 |
| thinking 并集规则改变某条端侧存量路径 | 逐路径比对（§4.4 表），矩阵测试钉住 |
| 策略默认路径重构漂移 | 默认分支保留原内建节点实现原样，不随手「顺手统一」 |
| Koog `KoogHttpClient.Factory` 的 common expect 与 JVM actual 参数名差异 | 按位置映射（同型 8 参），server 工厂实例传给 shared 函数仅作透传，无语义跨越 |
