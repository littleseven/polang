package com.mamba.picme.features.chat

import android.content.Context
import com.mamba.picme.core.agenttools.AppToolExecutor
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatSessionDao
import com.mamba.picme.data.local.dao.PersonDao
import com.mamba.picme.data.remote.picme.ClaudeChatClient
import com.mamba.picme.data.remote.picme.PoLangAuthClient
import com.mamba.picme.data.repository.MediaFeedbackRepository
import com.mamba.picme.domain.repository.ChatImageStore
import com.mamba.picme.domain.repository.AndroidMediaRepository
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.search.MediaSearchEngine
import com.mamba.picme.domain.tag.ControlledVocab
import com.mamba.picme.domain.tag.TagGenerationScheduler
import com.mamba.picme.domain.usecase.ChatEditProcessor
import com.mamba.picme.domain.usecase.GetGallerySummaryUseCase
import com.mamba.picme.domain.usecase.QueryGalleryMediaUseCase
import com.mamba.picme.domain.usecase.SaveChatEditResultUseCase
import com.mamba.picme.domain.usecase.StartTagScanUseCase

@Suppress("LongParameterList") // 待重构：依赖容器，考虑分组或 builder
class ChatViewModelDependencies(
    val context: Context,
    val chatMessageDao: ChatMessageDao,
    val chatSessionDao: ChatSessionDao,
    val userSettingsRepository: UserSettingsRepository,
    val mediaSearchEngine: MediaSearchEngine,
    val mediaFeedbackRepository: MediaFeedbackRepository,
    val mediaRepository: AndroidMediaRepository,
    val picMeAuthClient: PoLangAuthClient,
    val claudeChatClient: ClaudeChatClient = ClaudeChatClient(),
    val getGallerySummaryUseCase: GetGallerySummaryUseCase,
    val queryGalleryMediaUseCase: QueryGalleryMediaUseCase,
    val startTagScanUseCase: StartTagScanUseCase,
    val personDao: PersonDao,
    val controlledVocab: ControlledVocab,
    val chatEditStateHolder: ChatEditStateHolder,
    val chatEditProcessor: ChatEditProcessor,
    val chatImageRenderer: ChatImageRenderer? = null,
    val chatImageStore: ChatImageStore,
    val saveChatEditResultUseCase: SaveChatEditResultUseCase,
    /** AI 优化抽卡编排器；null = 未接线（单测默认），AiOptimize 走旧单发路径。 */
    val optimizeGachaController: ChatOptimizeGachaController? = null,
    /**
     * 打标调度器：单图图像理解经 [TagGenerationScheduler.currentTaggerModelKey] /
     * [TagGenerationScheduler.describeImage] 与打标同源选模型（跟随设置页打标模型）。
     * null = 未接线（单测默认），图像理解回退默认 MNN 模型。
     */
    val tagGenerationScheduler: TagGenerationScheduler? = null
) {
    /**
     * app_tool_request 采集执行器（spec §3.1）。null = 未接线（单测默认不注入则功能关闭）。
     * var 而非构造参数：执行器工厂 [buildAppToolExecutor] 依赖本容器的其他字段，
     * 生产由 AppContainer 构造本容器后回填（在创建 ChatViewModel 之前完成）。
     */
    var appToolExecutor: AppToolExecutor? = null

    /** claude-tunnel sid 持久化（Task 8）；生产由 AppContainer 回填 PrefsClaudeSidStore。 */
    var claudeSidStore: ClaudeSidStore? = null
}
