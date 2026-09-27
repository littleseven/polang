package com.mamba.picme.features.chat

import android.content.Context
import android.util.Log
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.model.config.AssistantPersona
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatSessionDao
import com.mamba.picme.data.remote.picme.PoLangAuthClient
import com.mamba.picme.data.repository.MediaFeedbackRepository
import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.search.MediaSearchEngine
import com.mamba.picme.domain.tag.ControlledVocab
import com.mamba.picme.domain.usecase.GetGallerySummaryUseCase
import com.mamba.picme.domain.usecase.StartTagScanUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before

/**
 * ChatViewModel 单测公共基类。
 *
 * 抽取 8 个 ChatViewModel*Test 文件的共享样板：
 * - mockkStatic(Log) + Log.* stub
 * - mockkObject(AgentOrchestrator.Companion) + getInstance
 * - Room DAO 空 stub（getMessagesBySession / getLastMessageForSession / getAllSessions / getSession）
 * - ChatViewModelDependencies 构造（[newViewModel]）
 *
 * 子类差异通过以下钩子处理：
 * - [initialToken]：覆盖 flow 初始值
 * - [setUp]：override 并先调 super.setUp()，再追加文件专属 stub
 * - [newViewModel]：override 以注入 claudeChatClient / .also 回填（如 ClaudeSid / AppTool）
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
abstract class ChatViewModelTestBase {

    // ── Common mocks ──────────────────────────────────────────────

    protected val context: Context = mockk(relaxed = true)
    protected val chatMessageDao: ChatMessageDao = mockk(relaxed = true)
    protected val chatSessionDao: ChatSessionDao = mockk(relaxed = true)
    protected val userSettingsRepository: UserSettingsRepository = mockk(relaxed = true)
    protected val orchestrator: AgentOrchestrator = mockk(relaxed = true)

    // Dep-specific mocks（多数子类使用部分）
    protected val mediaSearchEngine: MediaSearchEngine = mockk(relaxed = true)
    protected val mediaFeedbackRepository: MediaFeedbackRepository = mockk(relaxed = true)
    protected val picMeAuthClient: PoLangAuthClient = mockk(relaxed = true)
    protected val getGallerySummaryUseCase: GetGallerySummaryUseCase = mockk(relaxed = true)

    // ── Overridable initial flow values ────────────────────────────

    protected open val initialToken: String = ""

    protected val tokenFlow: MutableStateFlow<String> by lazy { MutableStateFlow(initialToken) }

    // ── Lifecycle ──────────────────────────────────────────────────

    @Before
    open fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        every { context.applicationContext } returns context

        every { userSettingsRepository.serverAuthTokenFlow } returns tokenFlow
        every { userSettingsRepository.guestChatMessageCountFlow } returns flowOf(0)
        every { userSettingsRepository.assistantPersonaFlow } returns flowOf(AssistantPersona.DEFAULT)
        every { userSettingsRepository.appLanguageFlow } returns flowOf(AppLanguage.SYSTEM)
        // relaxed mock 对枚举返回随机值：钉死 SYSTEM，使 ChatViewModel.stringContext() 直接返回
        // context mock（走 createConfigurationContext 分支会拿到未 stub 的新 mock，getString 恒为 ""）。
        every { userSettingsRepository.getAppLanguageBlocking() } returns AppLanguage.SYSTEM

        every { chatMessageDao.getMessagesBySession(any()) } returns flowOf(emptyList())
        coEvery { chatMessageDao.getLastMessageForSession(any()) } returns null
        every { chatSessionDao.getAllSessions() } returns flowOf(emptyList())
        coEvery { chatSessionDao.getSession(any()) } returns null

        mockkObject(AgentOrchestrator.Companion)
        every { AgentOrchestrator.getInstance() } returns orchestrator
    }

    @After
    open fun tearDown() {
        unmockkObject(AgentOrchestrator.Companion)
        unmockkStatic(Log::class)
        Dispatchers.resetMain()
    }

    // ── ViewModel factory ──────────────────────────────────────────

    /**
     * 构造标准 [ChatViewModelDependencies] 并创建 [ChatViewModel]。
     *
     * 子类如需注入 claudeChatClient 或回填 var 属性（claudeSidStore / appToolExecutor），
     * override 本方法即可。
     */
    protected open fun newViewModel(): ChatViewModel = newViewModelWithGacha(null)

    /**
     * 抽卡控制器注入扩展点（[ChatViewModelGachaTest]）：与 [newViewModel] 相同的构造，
     * 末尾追加 optimizeGachaController。
     */
    protected open fun newViewModelWithGacha(
        controller: ChatOptimizeGachaController?
    ): ChatViewModel = ChatViewModel(
        ChatViewModelDependencies(
            context = context,
            chatMessageDao = chatMessageDao,
            chatSessionDao = chatSessionDao,
            userSettingsRepository = userSettingsRepository,
            mediaSearchEngine = mediaSearchEngine,
            mediaFeedbackRepository = mediaFeedbackRepository,
            mediaRepository = mockk(relaxed = true),
            picMeAuthClient = picMeAuthClient,
            getGallerySummaryUseCase = getGallerySummaryUseCase,
            queryGalleryMediaUseCase = mockk(relaxed = true),
            startTagScanUseCase = StartTagScanUseCase(context),
            personDao = mockk(relaxed = true),
            controlledVocab = ControlledVocab(),
            chatEditStateHolder = ChatEditStateHolder(),
            chatEditProcessor = mockk(relaxed = true),
            chatImageStore = mockk(relaxed = true),
            saveChatEditResultUseCase = mockk(relaxed = true),
            optimizeGachaController = controller,
            // decode 段与主线程同调度器（setMain 的 UnconfinedTestDispatcher）：
            // runTest 下同步执行，advanceUntilIdle 即可等 decode 完成（M4 review 🟡2 注入点）
            messageDecodeDispatcher = Dispatchers.Main,
        )
    )

    protected companion object {
        const val VERIFY_TIMEOUT_MS = 3_000L
    }
}
