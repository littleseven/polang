# 分层 Agent runtime M1.5 + M2 实施计划

> **Spec**：`docs/superpowers/specs/2026-11-07-layered-agent-runtime-design.md`
> **前置**：M1 已合 main（composite build + L1 契约描述 / L2 策略 / L3 执行器三份双写消灭）
> **目标**：
> - **M1.5**：OpenUI 白名单/prompt 资产在 server↔shared 之间的字面量副本收口（M1 收尾时发现的自然下一步，路已铺好）；
> - **M2**：工具 locality 泛化——`CloudAgentToolCatalog` 目录驱动的工具声明源：参数/描述/缺省一处声明，server 工具桥与双端 executor 全部从目录读取，同一目录声明工具跑在端/云。
>
> **工作区**：`.worktrees/layered-runtime-m2`，分支 `feat/layered-runtime-m2`（基于 main `40eafd36e`）。

## Global Constraints

- 每 Task 一 commit；TDD（先写/改测试见红，再实现见绿）。
- 行为等价优先：prompt 文本、正则 pattern、descriptor 参数集在收口前后**逐字节/逐项等价**（既有钉住测试原样绿或改为断言派生等价）。
- 只收**常量白名单/声明**，不动校验逻辑本体（`RenderOpenUiValidator`/`OpenUiLayoutNormalizer` 等 server-only 半信任正则级逻辑保持 server 侧）。
- KDoc 同步清扫：所有「server 持字面量副本，改动须双向同步核对」表述改为真引用表述。
- 验收门槛（全绿才合并）：`JITPACK=true ./gradlew :shared:jvmTest` + `:shared:assemble`、`./gradlew -p server build`（含全部测试）、`./gradlew :androidApp:compileDebugKotlin` + 受影响单测。

## 已核实事实（勘察结论，勿重复核实）

- shared `OpenUiLibrary.components: Map<String, OpenUiComponent>`（103 项，listOf 保序）→ `componentNames = components.keys` 派生即可；无现成 `componentNames` val。
- shared `OpenUiLibrary.iconNames: List<String>`（59 枚）与 `customActionNames: Set<String>` 已存在。
- shared `OpenUiContract` 只有 `FRAGMENT_PHOTOS/FRAGMENT_VIDEOS`，**无** `FRAGMENT_HISTOGRAM`（server `LOCAL_HANDLE_FORM` 闭集含 histogram）——须新增常量。
- shared `OpenUiContract.Actions`（SET/RESET/RUN/TO_ASSISTANT/OPEN_URL/OPEN_PHOTO/WRAPPER）+ `ENGINE_ACTIONS` 已存在；`Query`/`Mutation` 无 shared 常量。
- server `OpenUiPromptAssets.DSL_RULES` 已无 annotation 消费方（GenUI 发卡工具 2026-11-06 退役），仅 `OpenUiTurnNative.SYSTEM_PROMPT` 运行时拼接 + 测试 `contains` 断言 → `ICON_NAMES_TEXT` 与 `DSL_RULES` 可由 `const val` 降为派生 `val`。
- Koog 是 shared **commonMain** 依赖 → 目录驱动的 Koog `ToolDescriptor` 生成代码可直接放 commonMain。
- Koog 1.3.0 签名（javap 核实）：`Tool(TypeToken, TypeToken, ToolDescriptor, Map<String,String>)` 显式 descriptor 构造可用；`execute(TArgs, Continuation)` 抽象可覆写（带 metadata 的重载是 final）；`ToolDescriptor(name, description, requiredParameters: List<ToolParameterDescriptor>, optionalParameters, cacheControl)`；`ToolParameterDescriptor(name, description, type: ToolParameterType)`；`ai.koog.serialization.typeToken<T>()` reified 助手存在。
- server 工具注册点：`AgentRoutes.kt:266-273` 每轮 new `DeviceToolService` + `ServerToolService`，经 `asToolsByClass()` 反射展开。

## Task 1（M1.5）：shared 补派生面

- `OpenUiLibrary`：新增 `val componentNames: Set<String> = components.keys`（显式 SSOT，KDoc 注明消费方 = server 校验器/prompt 生成）。
- `OpenUiContract`：新增 `const val FRAGMENT_HISTOGRAM = "histogram"`；新增语言级调用名常量 `CALL_QUERY = "Query"` / `CALL_MUTATION = "Mutation"`（与 `Actions.WRAPPER` 同族）。
- 清扫两处 KDoc 的「字面量副本双向同步」表述（Actions、FRAGMENT_*）。
- 测试（shared commonTest，先红）：componentNames == components.keys 且 == 103；FRAGMENT_HISTOGRAM 值钉住。

## Task 2（M1.5）：server `RenderOpenUiValidator` 真引用化

- `ALLOWED_COMPONENTS = OpenUiLibrary.componentNames`（删 103 项字面量清单，KDoc 治理史精简为指针）。
- `ICON_NAMES = OpenUiLibrary.iconNames.toSet()`。
- `ALLOWED_CALLS = ALLOWED_COMPONENTS + listOf(CALL_QUERY, CALL_MUTATION, Actions.WRAPPER)`。
- `QUOTED_ACTION` 词表由常量拼装，**保持现有顺序逐字节等价**（`OpenUrl|Set|Reset|ToAssistant|Run|Navigate|Action`）：
  `listOf(OPEN_URL, SET, RESET, TO_ASSISTANT, RUN) + OpenUiLibrary.customActions + Actions.WRAPPER`（customActions 现仅 Navigate 一枚；拼装在 KDoc 注明顺序钉住语义）。
- `LOCAL_HANDLE_FORM` fragment 闭集由 `FRAGMENT_HISTOGRAM/FRAGMENT_PHOTOS/FRAGMENT_VIDEOS` 插值拼装（pattern 逐字节等价）。
- 测试（先红后绿）：`RenderOpenUiValidatorTest` 既有钉住测试应原样绿（值等价）；其中断言「三处字面量副本相等」的用例改写为断言派生关系（如 `ALLOWED_COMPONENTS == OpenUiLibrary.componentNames`），不再维护副本清单。

## Task 3（M1.5）：`OpenUiPromptAssets.ICON_NAMES_TEXT` 派生

- `const val ICON_NAMES_TEXT` → `val ICON_NAMES_TEXT = OpenUiLibrary.iconNames.joinToString(", ")`（iconNames 顺序与现文本一致，既有字节钉住测试绿）。
- `DSL_RULES` 随之 `const val` → `val`（无 annotation 消费方，已核实）。
- `OpenUiTurnNative` 已引用 `ALLOWED_COMPONENTS` 生成组件清单，随 Task 2 自动切换，无额外改动（验证其测试绿）。
- 测试：`OpenUiPromptAssetsTest` / `RenderOpenUiValidatorTest:291` 相关断言原样绿。

## Task 4（M2）：shared 目录参数声明 + descriptor 生成

- `CloudToolParam(name, description, required: Boolean = true, default: String = "")`。
- `CloudToolSpec` 增 `description: String` + `params: List<CloudToolParam>`；3 个 LOCAL 条目补全：
  - 工具描述引用 `CloudAgentToolContract.DESC_GET_GALLERY_SUMMARY/DESC_SEARCH_MEDIA/DESC_REFINE_MEDIA_SEARCH`；
  - 参数引用 `DESC_PARAM_*`（search_media 7 参：query/person/fromMs/toMs/hasFace/display（default "false"）/mediaType（default "photo"）；refine_media_search 3 参：query/hasFace/constraint）——与现 `DeviceToolService` @LLMDescription 逐项等价。
- `CloudAgentToolCatalog` 增：
  - `descriptorFor(tool): ToolDescriptor`（required/optional 按 `required` 分流，`ToolParameterType.String`，描述 = spec.description）；
  - `paramNames(tool): List<String>`、`defaultFor(tool, param): String`、`localTools(): List<CloudToolSpec>`。
- 测试（commonTest 先红）：每 LOCAL 工具 descriptor 参数集 == 声明集、描述 == DESC_* 常量、default 正确、required/optional 分流正确。

## Task 5（M2）：server `DeviceToolService` 目录驱动

- 新增 `CatalogBridgeTool(spec, bridge)`：`Tool<JsonObject, String>`（显式 descriptor = `CloudAgentToolCatalog.descriptorFor(spec.name)`；`execute(args)` = 现有 executeOnDevice 桥逻辑，args JsonObject 原样入帧）。
- `DeviceToolService` 删 3 个 @Tool 方法，改为按 `localTools()` 循环生成 `CatalogBridgeTool` 列表；桥帧/requestId/SSE 帧/超时/错误码逻辑原样保留（仅承载体从 @Tool 方法换成编程式 Tool）。
- `AgentRoutes.kt` 注册点：`asToolsByClass()` 反射 → 显式逐工具注册（`ToolRegistry { tool(...) }`）。
- `DeviceToolDescriptionTest` 改写为对 catalog descriptor 断言（描述/参数逐项等价现值）。
- `server_time`（`ServerToolService`）：作为 CLOUD 条目入目录（描述迁入，目录即全量工具审计面）；`ServerToolService` 工具方法描述改引用目录。
- 测试：server `AgentRunnerTest`/`AgentRoutesTest` MockEngine 链路全绿（行为等价钉住）。

## Task 6（M2）：双端 executor 参数读取目录化

- androidApp `DeviceToolExecutor`：when 分发的魔数字符串（"query"/"display"/"mediaType" 等）改走 `CloudAgentToolCatalog.paramNames/defaultFor`；display "false"/mediaType "photo" 兼容缺省 = 目录 default，语义不变。
- shared iosMain `IosCloudAgentToolBridge`：`args["query"]` 等改目录参数名；iOS 无 display/mediaType 特性，按目录读缺省空串，handler 签名不变。
- 测试：androidApp `DeviceToolExecutorTest`/`CloudAgentGalleryToolsTest` 原样绿；新增目录缺省等价断言（如需要）。

## Task 7：文档同步 + 全量验收 + 合并

- spec 状态行 + §2 里程碑表（M1 已合 / M1.5、M2 完成态）；`docs/superpowers/README.md` §6/§7。
- `server/AGENTS.md`（工具桥目录驱动 + OpenUI 资产真引用化表述）、`shared/AGENTS.md`（catalog 参数声明面 + OpenUiLibrary 派生面）、根 `AGENTS.md` spec 索引行状态。
- `CloudAgentToolContract`/`CloudAgentToolCatalog`/`OpenUiContract`/`OpenUiLibrary` KDoc 终扫。
- 四道门槛全绿后按 finishing-a-development-branch 流程合 main、删 worktree/分支。

## Self-Review

- 范围守恒：M1.5 只收常量白名单；M2 只改「声明源」，执行语义/桥协议/错误码零变化。
- 风险点：① QUOTED_ACTION/LOCAL_HANDLE_FORM 正则由常量拼装后 pattern 字节等价（测试钉）；② DSL_RULES const→val 无 annotation 消费方（已核实）；③ Koog 显式 descriptor 路径 LLM 可见 schema 不丢参数（M1 勘察已确认 Tool 构造语义）。
- 未做（刻意）：CLOUD 工具 executor 真正按 runtime 解析（M2 范围是声明源统一，执行位切换是 M5 TurnRunner 的事）；androidApp `OpenUiProgramSummary`/`HtmlCardSanitizer` 端侧副本。
