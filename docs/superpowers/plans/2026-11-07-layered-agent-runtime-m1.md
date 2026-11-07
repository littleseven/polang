# 分层 Agent Runtime M1（端云同构：消灭契约/策略/执行器三份双写）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** server 经 composite build 依赖 `:shared` jvm 变体，`DeviceToolService`/`CloudAgentStrategy`/`AgentExecutorFactory` 三处双写改为真引用 shared SSOT，端侧行为零变化。

**Architecture:** 设计见配套 spec `docs/superpowers/specs/2026-11-07-layered-agent-runtime-design.md`（七层模型；M1 = L1 契约 / L2 策略 / L3 执行器同源）。server 是独立 Gradle 构建，经 `includeBuild("..")` 复合构建消费 `:shared` jvm 变体（`com.mamba.picme:shared:1.0.0`）。

**Tech Stack:** Kotlin 2.3.10 / Koog 1.3.0（两端锁死）/ KMP（android+jvm+ios 三 target）/ Ktor 3.3.3。

**工作区：** `.worktrees/layered-agent-runtime`，分支 `feat/layered-agent-runtime`（已建）。以下所有路径相对该工作区根。

**Global Constraints:**
- shared `commonMain` 禁 `import android.*`/`java.*`（纯度守卫 `checkCommonMainPurity` 会拦）。
- 端侧行为零变化：`poLangSingleRunStrategy()` 默认路径保留原内建节点实现；`RemoteModelFactory` thinking 规则改并集后端侧全部存量路径逐一同口径。
- server 侧 `AgentExecutorFactory(httpClientFactory)` 构造签名与 `BuiltExecutor` 三字段外形不变（AgentRunnerTest/AgentRoutesTest/AgentRoutesBridgeTest 经 MockEngine 真跑工厂，行为由存量套件钉住）。
- Koog 版本两端 1.3.0 手工对齐，不引入跨构建版本目录。
- 构建命令：shared 一律 `JITPACK=true` 前缀；server `./gradlew -p server build`。

---

### Task 1: composite build 接线（shared 坐标 + server 依赖）

**Files:**
- Modify: `shared/build.gradle.kts`（文件末尾追加 group/version）
- Modify: `server/settings.gradle.kts`
- Modify: `server/build.gradle.kts`（dependencies 块）
- Test: `server/src/test/kotlin/com/mamba/picme/server/agent/SharedModuleSmokeTest.kt`（新建）

- [ ] **Step 1: 写冒烟测试（红：编译期 unresolved reference）**

```kotlin
package com.mamba.picme.server.agent

import com.mamba.picme.agent.core.inference.remote.tool.CloudAgentToolContract
import org.junit.Assert.assertEquals
import org.junit.Test

/** 分层 runtime M1：composite build 接线冒烟——server 编译期可见 :shared jvm 变体。 */
class SharedModuleSmokeTest {

    @Test
    fun `shared contract constants are on the classpath`() {
        assertEquals("search_media", CloudAgentToolContract.TOOL_SEARCH_MEDIA)
        assertEquals("error:", CloudAgentToolContract.ERROR_PREFIX)
        assertEquals(32_000, CloudAgentToolContract.MAX_RESULT_CHARS)
    }
}
```

- [ ] **Step 2: shared 设 composite 替换坐标**

`shared/build.gradle.kts` 末尾追加：

```kotlin
// 分层 Agent runtime M1（2026-11-07）：server 经 composite build（includeBuild("..")）
// 以模块坐标替换方式消费本模块 jvm 变体——group/name 即替换键，缺失则替换不生效。
// project(":shared") 消费方（androidApp/engines）不受 group/version 影响。
group = "com.mamba.picme"
version = "1.0.0"
```

- [ ] **Step 3: server 挂复合构建 + 声明依赖**

`server/settings.gradle.kts` 末尾追加：

```kotlin
// 分层 Agent runtime M1（2026-11-07）：复合构建挂 monorepo 根构建，
// 以 "com.mamba.picme:shared:1.0.0" 坐标替换消费 :shared jvm 变体
//（契约/策略/执行器规则端云同源）。server 未被根构建 include，无环。
// ⚠️ 构建 server 自此需要本机 Android SDK（:shared 配置含 android target）；
// 部署构建在开发机执行（deploy.sh 开发机构建→rsync），VPS 只跑 jar。
includeBuild("..")
```

`server/build.gradle.kts` 的 `dependencies` 块内（Koog 依赖之后）追加：

```kotlin
    // :shared jvm 变体（composite build 替换；分层 Agent runtime M1：契约/策略/执行器同源）。
    // Kotlin("jvm") 消费方经 Gradle Module Metadata 自动选中 jvm 变体。
    implementation("com.mamba.picme:shared:1.0.0")
```

- [ ] **Step 4: 构建验证（绿）**

Run: `./gradlew -p server build`
Expected: BUILD SUCCESSFUL，`SharedModuleSmokeTest` 通过。若 Koog metadata 404，重跑加 `JITPACK=true`（根构建 settings 读此环境变量切仓库序）。

- [ ] **Step 5: Commit**

```bash
git add shared/build.gradle.kts server/settings.gradle.kts server/build.gradle.kts server/src/test/kotlin/com/mamba/picme/server/agent/SharedModuleSmokeTest.kt
git commit -m "build(server): composite build 消费 :shared jvm 变体（分层 runtime M1 接线）"
```

---

### Task 2: L1 契约——工具描述收口 shared，DeviceToolService 真引用

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/tool/CloudAgentToolContract.kt`
- Modify: `server/src/main/kotlin/com/mamba/picme/server/agent/DeviceToolService.kt`
- Test: `server/src/test/kotlin/com/mamba/picme/server/agent/DeviceToolDescriptionTest.kt`（追加用例）

- [ ] **Step 1: shared 契约新增描述常量区（逐字自 server 迁入）**

`CloudAgentToolContract.kt` object 内末尾追加：

```kotlin
    // ── 云端 LLM 可见描述 SSOT（2026-11-07 分层 runtime M1，自 server DeviceToolService
    // 逐字收口）── server @Tool/@LLMDescription 注解直接引用下列 const（编译期常量，
    // 跨模块可作注解实参）。投影承诺面与 CloudAgentToolCatalog.projectionSchema 同源；
    // 改描述只改本区，禁止任何一侧持有字面量副本。

    const val DESC_GET_GALLERY_SUMMARY =
        "获取用户手机相册摘要（照片/视频总数、人物聚类数、已打标数等）。工具在用户设备上执行，结果以文本摘要回传（投影：仅计数与文本元数据，不含文件路径与媒体清单）。"

    const val DESC_SEARCH_MEDIA =
        "在用户手机相册搜索照片/视频，结果以文本摘要回传（投影：仅数量+命中说明+尾部 opaque 数据句柄「handle: scan/<id>」（供 OpenUI 卡数据绑定（Query local_handle），禁止臆造 handleId），不含文件路径与媒体清单）。query 自然语言搜索词（留空=全相册所有媒体，统计全库时直接留空即可）；person 人物分组名或称谓（无则空串）；fromMs/toMs 毫秒时间串（query 含时间词时必须换算填入，空串=不限）；hasFace 传 'true'=只要含人脸照片（空串=不限）；mediaType 媒体类型：'photo'=只搜照片（默认）、'video'=只搜视频（用户提到视频/录像/vlog 时必传）、'all'=照片+视频。【display 判定——浏览/看照片（「看看」「给我看看」「最近拍了什么」「找些…的照片」等）一律 'false' 取数 + OpenUI PhotoGrid 卡展示（照片网格 + 统计 + 分组头 = 更好的浏览体验）；仅纯计数/取数（统计、为出卡取数）也传 'false'。'true'（横滑图集卡）仅限快速单次查询（如精确找某一张、快速核对存在性），不是默认展示方式。】"

    const val DESC_REFINE_MEDIA_SEARCH =
        "在上一轮 search_media 结果内细化过滤（如'只要夜景'），结果以文本摘要回传（投影：仅数量+尾部 opaque 数据句柄「handle: scan/<id>」（供 OpenUI 卡数据绑定（Query local_handle），禁止臆造 handleId），不含文件路径与媒体清单）。constraint 细化条件；fromMs/toMs 毫秒串（空串=不限）。上一轮无搜索基数时设备会返回错误，此时改用 search_media。"

    const val DESC_PARAM_QUERY = "自然语言搜索词"
    const val DESC_PARAM_PERSON = "人物分组名或称谓，无则空串"
    const val DESC_PARAM_FROM_MS = "时间起点毫秒串，空串=不限"
    const val DESC_PARAM_TO_MS = "时间终点毫秒串，空串=不限"
    const val DESC_PARAM_HAS_FACE = "'true'=只要含人脸；空串=不限"
    const val DESC_PARAM_DISPLAY = "浏览照片='false'+OpenUI PhotoGrid 卡；仅快速单查='true'。默认 'false'"
    const val DESC_PARAM_MEDIA_TYPE = "'photo'=只搜照片（默认）；'video'=只搜视频；'all'=照片+视频"
    const val DESC_PARAM_CONSTRAINT = "细化条件"
```

同时把类 KDoc 中「server `DeviceToolService`（Ktor，`@Tool(customName=…)` 注解需编译期常量且 server 不依赖 :shared，持字面量副本——改动本文件任一常量必须同步核对 server 侧）」改写为「server `DeviceToolService`（分层 runtime M1 起经 composite build 真引用本对象 const，含工具名与 DESC_* 描述）」；Swift 副本注记保留不动。

- [ ] **Step 2: server 描述钉住测试（先红后绿意义上的等价断言）**

`DeviceToolDescriptionTest.kt` 追加（import 增 `com.mamba.picme.agent.core.inference.remote.tool.CloudAgentToolContract`）：

```kotlin
    @Test
    fun `descriptions are the shared contract constants verbatim`() {
        assertEquals(
            CloudAgentToolContract.DESC_GET_GALLERY_SUMMARY,
            descriptors.getValue("get_gallery_summary").descriptor.description,
        )
        assertEquals(
            CloudAgentToolContract.DESC_SEARCH_MEDIA,
            descriptors.getValue("search_media").descriptor.description,
        )
        assertEquals(
            CloudAgentToolContract.DESC_REFINE_MEDIA_SEARCH,
            descriptors.getValue("refine_media_search").descriptor.description,
        )
    }
```

- [ ] **Step 3: DeviceToolService 改真引用**

三处 `@Tool(customName = "…")` 改 `@Tool(customName = CloudAgentToolContract.TOOL_GET_GALLERY_SUMMARY)` 等；全部 `@LLMDescription("…")` 字面量改对应 `CloudAgentToolContract.DESC_*`；`executeOnDevice("get_gallery_summary", …)` 等工具名实参同步改常量；`"error: device_offline"`/`"error: device_tool_timeout"` 改 `CloudAgentToolContract.ERROR_PREFIX + " device_offline"` / `+ " device_tool_timeout"`（两处 complete 调用与两处 return，共 4 个点）；文件 import 增 `com.mamba.picme.agent.core.inference.remote.tool.CloudAgentToolContract`。类 KDoc 中「工具名/`error:` 前缀/32_000 字符截断的 SSOT = shared commonMain `CloudAgentToolContract`（M3 收口；server 不依赖 :shared，本类持字面量副本——改契约常量须双向同步核对）。@LLMDescription 的投影承诺面与 shared CloudAgentToolCatalog.projectionSchema 逐字对齐（混合架构 M1），改动须双向同步核对。」整段改写为「工具名/描述/`error:` 前缀全部真引用 shared `CloudAgentToolContract`（分层 runtime M1，composite build）；投影承诺面与 shared `CloudAgentToolCatalog.projectionSchema` 同源。」

- [ ] **Step 4: 测试验证**

Run: `./gradlew -p server test --tests "com.mamba.picme.server.agent.DeviceToolDescriptionTest" --tests "com.mamba.picme.server.agent.DeviceToolServiceTest"`
Expected: 全绿（描述逐字等价，存量断言不受影响）。

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/tool/CloudAgentToolContract.kt server/src/main/kotlin/com/mamba/picme/server/agent/DeviceToolService.kt server/src/test/kotlin/com/mamba/picme/server/agent/DeviceToolDescriptionTest.kt
git commit -m "refactor(agent): 云端设备工具名与描述收口 shared CloudAgentToolContract（M1 L1）"
```

---

### Task 3: L3 执行器——RemoteModelFactory 参数化 + server 变适配器

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/remote/config/RemoteModelConfig.kt`（`RemoteModelConfig` 增字段）
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/remote/config/RemoteModelFactory.kt`
- Test: `shared/src/commonTest/kotlin/com/mamba/picme/agent/core/remote/config/RemoteModelFactoryTest.kt`（追加用例）
- Modify: `server/src/main/kotlin/com/mamba/picme/server/agent/AgentExecutorFactory.kt`（重写 create）

- [ ] **Step 1: shared 测试先行（红）**

`RemoteModelFactoryTest.kt` 追加：

```kotlin
    @Test
    fun `host marker rule injects thinking disabled for deepseek family regardless of providerId`() {
        for (baseUrl in listOf(
            "https://tokenhub.tencentmaas.com",
            "https://api.moonshot.cn",
            "https://api.deepseek.com",
        )) {
            val bundle = RemoteModelFactory.createKoogExecutor(
                RemoteModelConfig(
                    modelId = "test-model",
                    providerId = "server-cloud-agent", // 非空：不走旧配置空串兜底
                    protocol = RemoteProtocol.OPENAI,
                    apiKey = "test-key",
                    baseUrl = baseUrl,
                )
            )
            assertNotNull("thinking injection missing for $baseUrl", bundle.baseParams.additionalProperties)
        }
    }

    @Test
    fun `host marker rule does not inject for openai official host`() {
        val bundle = RemoteModelFactory.createKoogExecutor(
            RemoteModelConfig(
                modelId = "gpt-5",
                providerId = "server-cloud-agent",
                protocol = RemoteProtocol.OPENAI,
                apiKey = "test-key",
                baseUrl = "https://api.openai.com",
            )
        )
        assertNull(bundle.baseParams.additionalProperties)
    }

    @Test
    fun `custom chatCompletionsPath builds executor bundle`() {
        val bundle = RemoteModelFactory.createKoogExecutor(
            RemoteModelConfig(
                modelId = "test-model",
                providerId = "server-cloud-agent",
                protocol = RemoteProtocol.OPENAI,
                apiKey = "test-key",
                baseUrl = "https://gateway.ai.cloudflare.com",
                chatCompletionsPath = "v1/acc/picme/compat/chat/completions",
            )
        )
        assertEquals("test-model", bundle.model.id)
        assertEquals(LLMProvider.OpenAI, bundle.model.provider)
    }
```

- [ ] **Step 2: 跑测试确认前两个红**

Run: `JITPACK=true ./gradlew :shared:jvmTest --tests "com.mamba.picme.agent.core.remote.config.RemoteModelFactoryTest"`
Expected: `host marker rule injects …` 与 `custom chatCompletionsPath …` FAIL（前者 example.com 外 host 不命中旧 providerId 规则；后者 `chatCompletionsPath` 参数不存在编译失败——编译失败等同红）。

- [ ] **Step 3: RemoteModelConfig 增字段**

`RemoteModelConfig` 主构造 `deviceId` 之后追加：

```kotlin
    /**
     * OpenAI 兼容端点的 chat completions 路径（相对 baseUrl；分层 runtime M1）。
     * 空串 = Koog 客户端默认 `v1/chat/completions`（端侧存量路径行为不变）；
     * server 云端 Agent 经 `AgentTargetResolver.splitEndpoint` 两段式拆分后显式传入
     *（Cloudflare AIG 等带前缀路径的上游必须显式，默认路径会拼出 404）。
     */
    val chatCompletionsPath: String = "",
```

- [ ] **Step 4: RemoteModelFactory 参数化**

`createKoogExecutor` 签名改：

```kotlin
    public fun createKoogExecutor(
        config: RemoteModelConfig,
        extraHeaders: Map<String, String> = emptyMap(),
        /**
         * 自定义 Koog HTTP 工厂（分层 runtime M1：server 传 CF_AIG 鉴权改写包装或裸
         * Ktor 工厂）。null = 默认路径 `createKoogHttpClientFactory(extraHeaders)`。
         */
        httpClientFactory: KoogHttpClient.Factory? = null,
    ): KoogExecutorBundle {
```

函数体 `val factory = createKoogHttpClientFactory(extraHeaders)` 改：

```kotlin
        val factory = httpClientFactory ?: createKoogHttpClientFactory(extraHeaders)
```

文件 import 增 `ai.koog.http.client.KoogHttpClient`。

OpenAI 分支 `OpenAILLMClient(` 调用改：

```kotlin
                val settings = if (config.chatCompletionsPath.isBlank()) {
                    OpenAIClientSettings(baseUrl = config.baseUrl)
                } else {
                    OpenAIClientSettings(
                        baseUrl = config.baseUrl,
                        chatCompletionsPath = config.chatCompletionsPath,
                    )
                }
                OpenAILLMClient(effectiveApiKey, settings, factory)
```

thinking 规则改并集（KDoc 同步说明）：

```kotlin
    private fun shouldInjectThinkingDisabled(config: RemoteModelConfig): Boolean {
        if (config.protocol != RemoteProtocol.OPENAI) return false
        return config.providerId.isBlank() ||
            config.providerId in THINKING_DISABLED_PROVIDERS ||
            THINKING_DISABLED_HOST_MARKERS.any { config.baseUrl.contains(it, ignoreCase = true) }
    }

    private val THINKING_DISABLED_PROVIDERS = setOf(
        "tencent-tokenhub",
        "kimi-official",
        "deepseek-official",
    )

    /**
     * DeepSeek 系上游的 baseUrl 标记（分层 runtime M1 自 server AgentExecutorFactory 收口）。
     * 云端 Agent 的 providerId 恒非空（"server-cloud-agent"），注入判定由本表接管；
     * 端侧存量路径（空 providerId 或白名单 providerId）不受影响。
     */
    private val THINKING_DISABLED_HOST_MARKERS = listOf("tokenhub", "moonshot", "deepseek")
```

- [ ] **Step 5: shared 测试验证（绿）+ 全量回归**

Run: `JITPACK=true ./gradlew :shared:jvmTest`
Expected: 全绿（含存量 5 用例——端侧路径口径不变）。

- [ ] **Step 6: server AgentExecutorFactory 重写为适配器**

整个 class 体替换为（`BuiltExecutor` 与构造签名不变；删 `clampTemperature`/`shouldInjectThinkingDisabled`/`THINKING_DISABLED_HOSTS`/`MAX_TOKENS` 及 companion；`CfAigKoogHttpClientFactory` 保留不动）：

```kotlin
class AgentExecutorFactory(
    private val httpClientFactory: KoogHttpClient.Factory = KtorKoogHttpClient.Factory(),
) {

    data class BuiltExecutor(
        val executor: PromptExecutor,
        val model: LLModel,
        val params: LLMParams,
    )

    /**
     * 映射运行目标 → shared `RemoteModelConfig`，委托 `RemoteModelFactory.createKoogExecutor`
     *（分层 runtime M1：capabilities 三件套/temperature 钳制/thinking 注入规则全部端云同源，
     * 本类只剩 CF_AIG 鉴权包装这一云端渠道关切）。
     *
     * providerId 恒非空：shared 对空 providerId 保留「旧配置注入 thinking=disabled」的
     * 历史行为，云端判定改由 baseUrl host 标记规则精确接管。
     */
    fun create(target: AgentLlmTarget): BuiltExecutor {
        val factory = when (target.authStyle) {
            AuthStyle.BEARER -> httpClientFactory
            AuthStyle.CF_AIG -> CfAigKoogHttpClientFactory(httpClientFactory)
        }
        val bundle = RemoteModelFactory.createKoogExecutor(
            RemoteModelConfig(
                modelId = target.model,
                providerId = PROVIDER_ID,
                protocol = RemoteProtocol.OPENAI,
                apiKey = target.apiKey,
                baseUrl = target.baseUrl,
                chatCompletionsPath = target.chatCompletionsPath,
            ),
            httpClientFactory = factory,
        )
        return BuiltExecutor(bundle.executor, bundle.model, bundle.baseParams)
    }

    private companion object {
        const val PROVIDER_ID = "server-cloud-agent"
    }
}
```

import 改为：`ai.koog.http.client.KoogHttpClient` / `ai.koog.http.client.ktor.KtorKoogHttpClient` / `ai.koog.prompt.executor.model.PromptExecutor` / `ai.koog.prompt.llm.LLModel` / `ai.koog.prompt.params.LLMParams` / `com.mamba.picme.agent.core.remote.config.RemoteModelConfig` / `com.mamba.picme.agent.core.remote.config.RemoteModelFactory` / `com.mamba.picme.agent.core.remote.config.RemoteProtocol` / `com.mamba.picme.server.llm.AuthStyle` / `kotlinx.serialization.json.Json`（CfAig 类用）。类 KDoc 重写（删「服务端对映」表述，写明 M1 委托关系与 CF_AIG 保留原因）。

行为差说明（钉在 commit message）：shared 工厂 `apiKey.ifEmpty { "gateway-auth" }` 兜底对 server 不可达（target.apiKey 恒非空）；`OpenAIClientSettings` 显式传 `chatCompletionsPath`（target 默认 `v1/chat/completions`，与 Koog 客户端默认值相同，BEARER 裸目标 URL 不变）。

- [ ] **Step 7: server 全量测试验证**

Run: `./gradlew -p server build`
Expected: 全绿（AgentRunnerTest/AgentRoutesTest/AgentRoutesBridgeTest 经 MockEngine 真跑工厂，钉住 URL 拼接/headers/流式行为等价）。

- [ ] **Step 8: Commit**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/agent/core/remote/config/ shared/src/commonTest/kotlin/com/mamba/picme/agent/core/remote/config/ server/src/main/kotlin/com/mamba/picme/server/agent/AgentExecutorFactory.kt
git commit -m "refactor(agent): 云端执行器收口 shared RemoteModelFactory（M1 L3：capabilities/钳制/thinking 同源）"
```

---

### Task 4: L2 策略——参数化策略工厂 + server 变配置调用

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/koog/KoogReActStrategy.kt`（重写）
- Test: `shared/src/commonTest/kotlin/com/mamba/picme/agent/core/inference/remote/koog/PoLangReActStrategyTest.kt`（新建）
- Modify: `server/src/main/kotlin/com/mamba/picme/server/agent/ProgramGuard.kt`（增 `asCorrector`）
- Modify: `server/src/main/kotlin/com/mamba/picme/server/agent/CloudAgentStrategy.kt`（重写策略函数，保留 `cloudAgentPrompt`）

- [ ] **Step 1: shared 测试先行（红）**

新建 `PoLangReActStrategyTest.kt`：

```kotlin
package com.mamba.picme.agent.core.inference.remote.koog

import kotlin.test.Test
import kotlin.test.assertEquals

/** 分层 runtime M1：端云共用策略工厂——默认路径与云端配置两态。 */
class PoLangReActStrategyTest {

    private fun stubCorrector() = object : FinalOutputCorrector {
        override fun correctionGateError(assistantText: String): String? = "err"
        override fun rawValidationError(assistantText: String): String? = "err"
        override fun markCorrected() {}
        override fun correctionPrompt(error: String): String = "fix: $error"
    }

    @Test
    fun `default config keeps legacy strategy name`() {
        assertEquals("polang_single_run", poLangSingleRunStrategy().name)
    }

    @Test
    fun `named one-shot config builds`() {
        assertEquals("custom", poLangSingleRunStrategy(PoLangReActStrategyConfig(name = "custom")).name)
    }

    @Test
    fun `streaming config with corrector builds`() {
        val strategy = poLangSingleRunStrategy(
            PoLangReActStrategyConfig(
                name = "polang_cloud_single_run",
                streaming = true,
                finalOutputCorrector = stubCorrector(),
            )
        )
        assertEquals("polang_cloud_single_run", strategy.name)
    }

    @Test
    fun `one-shot config with corrector builds`() {
        val strategy = poLangSingleRunStrategy(
            PoLangReActStrategyConfig(name = "guarded", finalOutputCorrector = stubCorrector())
        )
        assertEquals("guarded", strategy.name)
    }
}
```

- [ ] **Step 2: 跑测试确认红**

Run: `JITPACK=true ./gradlew :shared:jvmTest --tests "com.mamba.picme.agent.core.inference.remote.koog.PoLangReActStrategyTest"`
Expected: 编译失败（`PoLangReActStrategyConfig`/`FinalOutputCorrector` 不存在）。

- [ ] **Step 3: KoogReActStrategy.kt 重写**

文件全量替换为：

```kotlin
package com.mamba.picme.agent.core.inference.remote.koog

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.agent.session.AIAgentLLMWriteSession
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResults
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.toMessageResponse
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.toList

/**
 * 终结输出纠正钩子（分层 runtime M1；每轮新实例，实现内部自管纠正计数）。
 * server `ProgramGuard`（OpenUI 回合原生）经 `asCorrector()` 适配接入；端侧 chat 传 null。
 */
interface FinalOutputCorrector {
    /** 边门控：纠正次数未达上限且输出非法 → 错误清单；null = 放行落流。 */
    fun correctionGateError(assistantText: String): String?

    /** 纠正节点内取原始校验错误（不做计数门控——节点只经门控边进入，先 markCorrected 后取）。 */
    fun rawValidationError(assistantText: String): String?

    /** 纠正轮次标记（实现内部计数防死循环）。 */
    fun markCorrected()

    /** 回灌给 LLM 的纠正 prompt（携带逐条错误清单）。 */
    fun correctionPrompt(error: String): String
}

/**
 * ReAct 策略配置（分层 runtime M1：端云共用同一策略工厂，差异仅配置）。
 *
 * @param name 策略名（Koog 图标识；端侧默认 "polang_single_run"，云端 "polang_cloud_single_run"）
 * @param streaming true = LLM 节点走流式（逐帧触发 EventHandler 流式钩子，openui 预览旁路的
 *   数据源），失败回退一次性请求；false = 一次性 `requestLLM()`
 * @param finalOutputCorrector 非 null 时挂 correctInvalidProgram 纠正节点
 *   （先匹配者胜，纠正边声明在 finish 边之前）
 * @param onStreamingFallback 流式失败回退一次性请求时的告警出口（server=slf4j；null=静默）
 */
data class PoLangReActStrategyConfig(
    val name: String = "polang_single_run",
    val streaming: Boolean = false,
    val finalOutputCorrector: FinalOutputCorrector? = null,
    val onStreamingFallback: ((Throwable) -> Unit)? = null,
)

/**
 * PoLang 单跑 ReAct 策略（分层 runtime M1：端云共用工厂）。
 *
 * 与 Koog 1.3.0 内建 singleRunStrategy 的差异：`nodeSendToolResult` 出边把 `onToolCalls`
 * 声明在 `onTextMessage` **之前**——内建版先匹配文本，DeepSeek 惯用的「叙述文本 +
 * tool_calls 同帧」响应会被静默丢弃工具调用（真机 2026-08-07 实测复现；1.3.0 内建版
 * 未修，本策略继续必要，勿因升级误删）。
 *
 * 时效（2026-09-25 核实）：Koog 1.3.0 内建策略 nodeSendToolResult 出边
 * onTextMessage→nodeFinish 仍声明在 onToolCalls 之前（AIAgentSimpleStrategies.kt）。
 *
 * 默认配置（非流式 + 无纠正）= 端侧 chat 现行路径，保留内建节点实现逐字节一致；
 * 非默认配置 = 云端路径（server `cloudAgentStrategy` 原实现收口）：流式节点 + 可选
 * correctInvalidProgram 纠正节点（OpenUI 回合原生 ProgramGuard，2026-11-06 起云端
 * 无条件启用）。
 *
 * ⚠️ Koog 1.3.0 `onTextMessage { block }` 的 block 是**分片过滤器**（transform 语义：
 * 过滤后 join 成 String，空串也算命中），不是门控条件——条件必须写在后续级联的
 * `onCondition` 里，否则同一节点两条文本边中先声明者无条件捕获全部文本消息（事故记录）。
 */
fun poLangSingleRunStrategy(
    config: PoLangReActStrategyConfig = PoLangReActStrategyConfig(),
): AIAgentGraphStrategy<String, String> {
    if (!config.streaming && config.finalOutputCorrector == null) {
        return builtinOneShotStrategy(config.name)
    }
    return configurableStrategy(config)
}

/** 端侧现行路径：内建节点 + 修复后的边序（与 M1 前实现逐字节一致）。 */
private fun builtinOneShotStrategy(name: String): AIAgentGraphStrategy<String, String> =
    strategy(name) {
        val nodeCallLLM by nodeLLMRequest()
        val nodeExecuteTool by nodeExecuteTools()
        val nodeSendToolResult by nodeLLMSendToolResults()

        edge(nodeStart forwardTo nodeCallLLM)
        edge(nodeCallLLM forwardTo nodeExecuteTool onToolCalls { true })
        edge(nodeCallLLM forwardTo nodeFinish onTextMessage { true })
        edge(nodeExecuteTool forwardTo nodeSendToolResult)
        // 🔧 修复点：与内建版相反，先匹配 tool_calls 再兜底文本（见上方 KDoc）。
        edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCalls { true })
        edge(nodeSendToolResult forwardTo nodeFinish onTextMessage { true })
    }

/**
 * 云端路径（server `cloudAgentStrategy` 原实现，分层 runtime M1 收口）：自定义节点支持
 * 流式请求 + 可选终结纠正节点。纠正边声明序先于 finish 边（先匹配者胜）。
 */
private fun configurableStrategy(config: PoLangReActStrategyConfig): AIAgentGraphStrategy<String, String> =
    strategy(config.name) {
        val corrector = config.finalOutputCorrector
        val nodeCallLLM by node<String, Message.Assistant>("callLLM") { message ->
            llm.writeSession {
                appendPrompt {
                    user(message)
                }
                requestLLMMaybeStreaming(config)
            }
        }
        val nodeExecuteTool by nodeExecuteTools()
        val nodeSendToolResult by node<ReceivedToolResults, Message.Assistant>("sendToolResult") { input ->
            llm.writeSession {
                appendPrompt {
                    user {
                        input.toolResults.forEach { toolResult -> toolResult(toolResult.toMessagePart()) }
                    }
                }
                requestLLMMaybeStreaming(config)
            }
        }
        val nodeCorrect = corrector?.let { c ->
            node<String, Message.Assistant>("correctInvalidProgram") { text ->
                c.markCorrected()
                val error = c.rawValidationError(text) ?: "未知校验错误（请检查输出契约）"
                llm.writeSession {
                    appendPrompt {
                        user(c.correctionPrompt(error))
                    }
                    requestLLMMaybeStreaming(config)
                }
            }
        }

        edge(nodeStart forwardTo nodeCallLLM)
        edge(nodeCallLLM forwardTo nodeExecuteTool onToolCalls { true })
        if (nodeCorrect != null && corrector != null) {
            edge(
                nodeCallLLM forwardTo nodeCorrect
                    onTextMessage { true }
                    onCondition { text -> corrector.correctionGateError(text) != null },
            )
        }
        edge(nodeCallLLM forwardTo nodeFinish onTextMessage { true })
        edge(nodeExecuteTool forwardTo nodeSendToolResult)
        // 与内建版相反：先匹配 tool_calls 再兜底文本
        edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCalls { true })
        if (nodeCorrect != null && corrector != null) {
            edge(
                nodeSendToolResult forwardTo nodeCorrect
                    onTextMessage { true }
                    onCondition { text -> corrector.correctionGateError(text) != null },
            )
        }
        edge(nodeSendToolResult forwardTo nodeFinish onTextMessage { true })
        if (nodeCorrect != null) {
            edge(nodeCorrect forwardTo nodeExecuteTool onToolCalls { true })
            // 纠正后再输出仍非法：放行落流（终结兜底变换包装）
            edge(nodeCorrect forwardTo nodeFinish onTextMessage { true })
        }
    }

/**
 * 流式请求并组装完整响应。逐帧收集触发 EventHandler 流式钩子；收齐后经
 * `toMessageResponse()` 组装 Message.Assistant 并写回 prompt，与一次性 requestLLM
 * 语义等价。流式失败回退一次性请求（重复计费可接受，轮次可用性优先）。
 */
private suspend fun AIAgentLLMWriteSession.requestLLMMaybeStreaming(
    config: PoLangReActStrategyConfig,
): Message.Assistant {
    if (!config.streaming) return requestLLM()
    return try {
        requestLLMStreaming().toList().toMessageResponse().also { response ->
            appendPrompt { message(response) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        config.onStreamingFallback?.invoke(e)
        requestLLM()
    }
}
```

- [ ] **Step 4: shared 测试验证（绿）**

Run: `JITPACK=true ./gradlew :shared:jvmTest`
Expected: 全绿（新增 4 用例 + 存量）。

- [ ] **Step 5: ProgramGuard 增适配 + CloudAgentStrategy 重写**

`ProgramGuard.kt` class 内追加（import 增 `com.mamba.picme.agent.core.inference.remote.koog.FinalOutputCorrector`）：

```kotlin
    /** 分层 runtime M1：适配 shared 策略工厂的终结纠正钩子（每轮新实例，与本类同生命周期）。 */
    fun asCorrector(): FinalOutputCorrector = object : FinalOutputCorrector {
        override fun correctionGateError(assistantText: String): String? = invalidProgramError(assistantText)
        override fun rawValidationError(assistantText: String): String? =
            RenderOpenUiValidator.validateProgramToError(assistantText)
        override fun markCorrected() = this@ProgramGuard.markCorrected()
        override fun correctionPrompt(error: String): String = ProgramGuard.correctionPrompt(error)
    }
```

`CloudAgentStrategy.kt`：`cloudAgentStrategy` 与 `requestLLMStreamingOrOnce` 及不再用的 import 删除，替换为：

```kotlin
/**
 * 云端单跑 ReAct 策略：shared `poLangSingleRunStrategy` 的云端配置（分层 runtime M1
 * 收口——图结构/边序/流式节点/纠正节点全部同源，本函数只剩配置组装）。
 *
 * 云端配置 = 流式节点（openui 预览旁路数据源）+ ProgramGuard 终结纠正
 * （2026-11-06 GenUI 唯一化后无条件启用；上限用尽放行落流，由 AgentRunner 终结落流的
 * [OpenUiTurnNative.ensureValidProgram] 兜底包装）。
 */
internal fun cloudAgentStrategy(
    programGuard: ProgramGuard,
): AIAgentGraphStrategy<String, String> =
    poLangSingleRunStrategy(
        PoLangReActStrategyConfig(
            name = "polang_cloud_single_run",
            streaming = true,
            finalOutputCorrector = programGuard.asCorrector(),
            onStreamingFallback = { e ->
                strategyLogger.warn("LLM 流式请求失败，回退一次性请求", e)
            },
        ),
    )
```

保留 import：`AIAgentGraphStrategy` / `com.mamba.picme.agent.core.inference.remote.koog.PoLangReActStrategyConfig` / `com.mamba.picme.agent.core.inference.remote.koog.poLangSingleRunStrategy` / `org.slf4j.LoggerFactory` / `strategyLogger` 定义；`cloudAgentPrompt` 函数与其 import（`Prompt`/`prompt`/`Message`→不需要/`LLMParams`）原样保留。

- [ ] **Step 6: server 全量测试验证**

Run: `./gradlew -p server build`
Expected: 全绿（AgentRunnerTest 中 ProgramGuard 纠正链路用例钉住行为等价）。

- [ ] **Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/com/mamba/picme/agent/core/inference/remote/koog/ shared/src/commonTest/kotlin/com/mamba/picme/agent/core/inference/remote/koog/ server/src/main/kotlin/com/mamba/picme/server/agent/ProgramGuard.kt server/src/main/kotlin/com/mamba/picme/server/agent/CloudAgentStrategy.kt
git commit -m "refactor(agent): 云端策略收口 shared poLangSingleRunStrategy 配置化工厂（M1 L2）"
```

---

### Task 5: 文档同步 + 全量验收

**Files:**
- Modify: `server/AGENTS.md`（§1 agent 文件三条描述、§6 构建节 composite 说明）
- Modify: `shared/AGENTS.md`（§2 tool/koog/remote-config 三行、§3 依赖方向）
- Modify: `docs/superpowers/README.md`（§6 白名单登记 spec+plan，§7 变更记录）
- Modify: `AGENTS.md`（§7 索引加一行在途 spec 指针）

- [ ] **Step 1: server/AGENTS.md 同步**

- `AgentExecutorFactory.kt` 行改：「Koog 执行器组装（分层 runtime M1 起委托 shared `RemoteModelFactory.createKoogExecutor`，本类仅剩 CF_AIG 鉴权包装与目标映射）」
- `CloudAgentStrategy.kt` 行改：「单跑 ReAct 策略的云端配置组装（分层 runtime M1 起图结构收口 shared `poLangSingleRunStrategy` 配置化工厂；流式节点 + ProgramGuard 纠正）」
- `DeviceToolService.kt` 行改：「端侧只读三件套 Koog 适配（tool_request 帧 + 120s 挂起；工具名/描述真引用 shared `CloudAgentToolContract`，分层 runtime M1）」
- §6 构建与部署节加：「构建依赖（2026-11-07 起）：server 经 composite build（`includeBuild("..")`）消费 `:shared` jvm 变体（`com.mamba.picme:shared:1.0.0`）——**构建 server 需要本机 Android SDK**（:shared 配置含 android target）；部署构建在开发机执行、VPS 只跑 jar，不受影响。」

- [ ] **Step 2: shared/AGENTS.md 同步**

- §2 `inference/remote/` 行：`poLangSingleRunStrategy` 描述改「配置化工厂（`PoLangReActStrategyConfig`：name/streaming/finalOutputCorrector/onStreamingFallback；默认路径=端侧现行内建节点实现，云端=流式+纠正节点；server `cloudAgentStrategy` 同源消费）」；`CloudAgentToolContract` 描述改「三工具名/error: 前缀/截断/纯函数 + DESC_* 云端 LLM 可见描述 SSOT（server DeviceToolService 经 composite build 真引用）」。
- §2 `remote/config/` 行改：「`RemoteModelFactory`/`RemoteModelConfig`/`KoogHttpClientFactoryProvider`（按 `RemoteProtocol` 分流；`createKoogExecutor` 增 `httpClientFactory` 注入缝与 `chatCompletionsPath` 透传，thinking 注入 = providerId 白名单 ‖ 空 providerId 历史行为 ‖ baseUrl host 标记并集；server `AgentExecutorFactory` 同源消费）」。
- §3 依赖方向图加一行：`server（独立构建，composite includeBuild）──→ :shared（jvm 变体，com.mamba.picme:shared:1.0.0）`。

- [ ] **Step 3: 白名单登记 + 根索引**

`docs/superpowers/README.md` §6 specs 列表加：「`2026-11-07-layered-agent-runtime-design.md` — 分层 Agent runtime（One Agent, Two Runtimes：七层模型，M1 契约/策略/执行器同源在途，M2~M6 见 spec §2）」；plans 行加 `2026-11-07-layered-agent-runtime-m1.md`；§7 变更记录加一行。
根 `AGENTS.md` §7 索引加一行：「**分层 Agent runtime（M1 在途）** | `docs/superpowers/specs/2026-11-07-layered-agent-runtime-design.md`（端云同构七层模型 + 开关=TurnRunner 选择；M1=server 依赖 :shared 消灭三份双写）」。

- [ ] **Step 4: 全量验收**

Run（顺序）:
1. `JITPACK=true ./gradlew :shared:jvmTest` — 绿
2. `JITPACK=true ./gradlew :shared:assemble` — 绿（裸 @Volatile 类坑只此暴露）
3. `./gradlew -p server build` — 绿
4. `./gradlew :androidApp:compileDebugKotlinAndroid` — 绿（KMP android 插件任务名以实际为准，失败时 `:androidApp:assembleDebug` 兜底）
5. `python3 scripts/check_doc_sync.py` — 绿（若存在且 ai-gate 覆盖则跑 `./scripts/ai-gate.sh`）

- [ ] **Step 5: Commit**

```bash
git add server/AGENTS.md shared/AGENTS.md docs/superpowers/README.md AGENTS.md
git commit -m "docs: 分层 runtime M1 文档同步（server/shared AGENTS + 白名单登记）"
```

---

## Self-Review 记录

- **Spec 覆盖**：§4.1→Task 1；§4.2→Task 2；§4.3→Task 4；§4.4→Task 3；§4.6 验收→Task 5 Step 4。无非目标被误纳入。
- **类型一致性**：`FinalOutputCorrector`（correctionGateError/rawValidationError/markCorrected/correctionPrompt）在 Task 4 Step 1/3/5 三处一致；`PoLangReActStrategyConfig` 四字段一致；`BuiltExecutor(executor, model, params)` 构造序与 Task 3 Step 6 `bundle.baseParams` 映射一致（server `BuiltExecutor.params` ← shared `baseParams`）。
- **行为陷阱**：ProgramGuard 计数器与 rawValidationError 分离（先 markCorrected 后取错误，门控方法不可复用于节点内）已在接口设计拆开；`onTextMessage` 过滤器语义保留 KDoc 警示。
