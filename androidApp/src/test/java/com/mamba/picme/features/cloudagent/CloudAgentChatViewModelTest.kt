package com.mamba.picme.features.cloudagent

import com.mamba.picme.core.agenttools.DeviceToolExecutor
import com.mamba.picme.data.remote.picme.CloudAgentApi
import com.mamba.picme.data.remote.picme.CloudFrame
import com.mamba.picme.domain.repository.UserSettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudAgentChatViewModelTest {

    private val tokenFlow = MutableStateFlow("pl-token")
    private val configsFlow = MutableStateFlow("")
    private val selectedFlow = MutableStateFlow("")
    private val consentFlow = MutableStateFlow(false)
    private val repository: UserSettingsRepository = mockk {
        every { serverAuthTokenFlow } answers { tokenFlow }
        every { aiAgentRemoteModelConfigsFlow } answers { configsFlow }
        every { aiAgentSelectedRemoteModelFlow } answers { selectedFlow }
        every { cloudAgentByokConsentFlow } answers { consentFlow }
    }

    /** 假客户端：记录调用，frames 脚本化。 */
    private class FakeApi : CloudAgentApi {
        var createdChannel: String? = null
        var createdModel: String? = null
        var createdBaseUrl: String? = null
        var sentByokKey: String? = null
        val toolResults = mutableListOf<Triple<String, Boolean, String>>()
        var framesScript: suspend (CloudAgentChatViewModelTest.FakeApi, (CloudFrame) -> Unit) -> Unit =
            { _, onFrame -> onFrame(CloudFrame.Done("running")) }

        override suspend fun createSession(token: String, channel: String, model: String, byokBaseUrl: String): Result<JSONObject> {
            createdChannel = channel; createdModel = model; createdBaseUrl = byokBaseUrl
            return Result.success(JSONObject().put("session_id", "s_1"))
        }
        override suspend fun getEvents(token: String, sessionId: String, after: Long, limit: Int) = Result.success(emptyList<JSONObject>())
        override suspend fun finish(token: String, sessionId: String) = Result.success(Unit)
        override suspend fun postMessage(token: String, sessionId: String, content: String, byokApiKey: String?, onFrame: (CloudFrame) -> Unit): Result<Unit> {
            sentByokKey = byokApiKey
            framesScript(this, onFrame)
            return Result.success(Unit)
        }
        override suspend fun postToolResult(token: String, sessionId: String, requestId: String, ok: Boolean, result: String): Result<Unit> {
            toolResults.add(Triple(requestId, ok, result))
            return Result.success(Unit)
        }
    }

    @Before
    fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private fun newVm(api: FakeApi): CloudAgentChatViewModel =
        CloudAgentChatViewModel(api, DeviceToolExecutor(gallerySummary = { "相册 42 张" }), repository)

    @Test
    fun `prepare without user config enters gateway chat`() = runTest {
        val api = FakeApi()
        val vm = newVm(api)
        vm.prepare()
        val s = vm.state.value
        assertTrue(s is CloudAgentChatViewModel.UiState.Chat)
        assertEquals("gateway", (s as CloudAgentChatViewModel.UiState.Chat).channel)
        assertEquals("gateway", api.createdChannel)
    }

    @Test
    fun `prepare byok without consent stops at NeedByokConsent and sends nothing`() = runTest {
        configsFlow.value = """[{"modelId":"kimi-k2.6","providerId":"kimi-official","protocol":"OPENAI","apiKey":"sk-xyz","baseUrl":"https://api.moonshot.cn/v1/","gatewayToken":""}]"""
        selectedFlow.value = "kimi-official:kimi-k2.6"
        val api = FakeApi()
        val vm = newVm(api)
        vm.prepare()
        assertTrue(vm.state.value is CloudAgentChatViewModel.UiState.NeedByokConsent)
        assertEquals(null, api.createdChannel) // 未授权不建会话
    }

    @Test
    fun `prepare byok with consent enters byok chat`() = runTest {
        configsFlow.value = """[{"modelId":"kimi-k2.6","providerId":"kimi-official","protocol":"OPENAI","apiKey":"sk-xyz","baseUrl":"https://api.moonshot.cn/v1/","gatewayToken":""}]"""
        selectedFlow.value = "kimi-official:kimi-k2.6"
        consentFlow.value = true
        val api = FakeApi()
        val vm = newVm(api)
        vm.prepare()
        val s = vm.state.value as CloudAgentChatViewModel.UiState.Chat
        assertEquals("byok", s.channel)
        assertEquals("byok", api.createdChannel)
        assertEquals("kimi-k2.6", api.createdModel)
        assertEquals("https://api.moonshot.cn/v1/", api.createdBaseUrl)
    }

    @Test
    fun `NeedByokConsent useGatewayInstead enters gateway chat`() = runTest {
        configsFlow.value = """[{"modelId":"kimi-k2.6","providerId":"kimi-official","protocol":"OPENAI","apiKey":"sk-xyz","baseUrl":"https://api.moonshot.cn/v1/","gatewayToken":""}]"""
        selectedFlow.value = "kimi-official:kimi-k2.6"
        val api = FakeApi()
        val vm = newVm(api)
        vm.prepare()
        vm.useGatewayInstead()
        val s = vm.state.value as CloudAgentChatViewModel.UiState.Chat
        assertEquals("gateway", s.channel)
    }

    @Test
    fun `send consumes frames and executes tool request then posts result`() = runTest {
        val api = FakeApi()
        api.framesScript = { _, onFrame ->
            onFrame(CloudFrame.ToolRequest("tr_1", "get_gallery_summary", JSONObject(), 120_000L))
            onFrame(
                CloudFrame.Event(
                    JSONObject("""{"id":3,"type":"message","source":"agent","role":"assistant","payload":{"type":"message","content":"你有 42 张照片"}}"""),
                ),
            )
            onFrame(CloudFrame.Done("running"))
        }
        val vm = newVm(api)
        vm.prepare()
        vm.send("相册多少张")
        val s = vm.state.value as CloudAgentChatViewModel.UiState.Chat
        assertEquals(false, s.inFlight)
        // user + tool 活动行 + assistant
        assertEquals(3, s.messages.size)
        assertEquals(CloudAgentChatViewModel.Role.USER, s.messages[0].role)
        assertEquals(CloudAgentChatViewModel.Role.TOOL, s.messages[1].role)
        assertEquals("你有 42 张照片", s.messages[2].text)
        assertEquals(listOf(Triple("tr_1", true, "相册 42 张")), api.toolResults)
    }

    @Test
    fun `error frame appends error message`() = runTest {
        val api = FakeApi()
        api.framesScript = { _, onFrame -> onFrame(CloudFrame.Error("quota exhausted")) }
        val vm = newVm(api)
        vm.prepare()
        vm.send("hi")
        val s = vm.state.value as CloudAgentChatViewModel.UiState.Chat
        assertEquals(CloudAgentChatViewModel.Role.ERROR, s.messages.last().role)
        assertEquals("quota exhausted", s.messages.last().text)
    }

    @Test
    fun `not logged in fails prepare`() = runTest {
        tokenFlow.value = ""
        val vm = newVm(FakeApi())
        vm.prepare()
        assertTrue(vm.state.value is CloudAgentChatViewModel.UiState.Failed)
    }
}
