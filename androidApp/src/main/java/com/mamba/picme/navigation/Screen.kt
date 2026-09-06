package com.mamba.picme.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    /** 主页面容器：内部以 HorizontalPager 承载 相册/相册整理/聊天/人物 4 页 */
    data object Main : Screen("main")
    data object Chat : Screen("chat")
    data object Camera : Screen("camera")
    data object Gallery : Screen("gallery") {
        const val ROUTE_WITH_ARGS = "gallery?query={query}&personId={personId}"
        const val ARG_QUERY = "query"
        const val ARG_PERSON_ID = "personId"
        fun createRoute(query: String = "", personId: Long = 0L): String {
            val params = buildList {
                if (query.isNotBlank()) {
                    add("$ARG_QUERY=${java.net.URLEncoder.encode(query, "UTF-8")}")
                }
                if (personId > 0L) {
                    add("$ARG_PERSON_ID=$personId")
                }
            }
            return if (params.isEmpty()) route else "$route?${params.joinToString("&")}"
        }
    }
    // 注：原 tag_control 路由已于 2026-09-06 并入整理+扫描合并页（Pager 页 1 SCAN Tab）
    data object Settings : Screen("settings")
    data object SettingsCategory : Screen("settings/{category}") {
        fun createRoute(category: String): String = "settings/$category"
    }
    /** 添加远程模型 — 供应商列表页（精确路由，优先于 settings/{category} 占位匹配） */
    data object AddRemoteProvider : Screen("settings/add_remote_provider")
    /** 供应商配置页；providerId 为 [RemoteModelProvider.providerId]，自定义供应商为 "custom" */
    data object ProviderConfig : Screen("settings/provider_config/{providerId}") {
        const val CUSTOM_PROVIDER_ID = "custom"
        fun createRoute(providerId: String): String = "settings/provider_config/$providerId"
    }
    data object Debug : Screen("debug")
    data object JsBridge : Screen("jsbridge")
    data object SearchTest : Screen("search_test")
    data object LlmLog : Screen("llm_log")
    data object DataPrivacy : Screen("data_privacy")
    data object MemoryFacts : Screen("memory_facts")
    data object People : Screen("people")
    data object CommunicationChannel : Screen("communication_channel")
    data object SentencePieceTest : Screen("sentencepiece_test")
    data object TagViewer : Screen("tag_viewer")

    /** 整理中心类目详情（F1）：AI 预选网格 + 批量回收站清理；路由段为 domain OrganizeCategory 枚举名 */
    data object OrganizeCategory : Screen("organize_category/{category}") {
        fun createRoute(category: String): String = "organize_category/$category"
    }

    /** 手势快速整理（F2）：全屏滑动决策页（右滑保留 / 左滑跳过 / 上滑删除，点按=跳过） */
    data object SwipeReview : Screen("swipe_review")

    /** 回忆详情（F3）：封面大图 + 精选网格 + 分享；路由段为 Memory.id（Uri.encode，Navigation 自动解码一次） */
    data object MemoryDetail : Screen("memory_detail/{memoryId}") {
        fun createRoute(memoryId: String): String {
            val encoded = Uri.encode(memoryId)
            return "memory_detail/$encoded"
        }
    }

    data object ModelCenter : Screen("model_center/{categoryTag}") {
        fun createRoute(categoryTag: String): String {
            return if (categoryTag.isNotBlank()) {
                "model_center/$categoryTag"
            } else {
                "model_center/"
            }
        }
    }

    data object PhotoEditor : Screen("photo_editor/{sourceUri}?recipeUri={recipeUri}&autoOptimize={autoOptimize}") {
        fun createRoute(
            sourceUri: String,
            recipeUri: String? = null,
            autoOptimize: Boolean = false
        ): String {
            val encodedSource = java.net.URLEncoder.encode(sourceUri, "UTF-8")
            val params = buildList {
                recipeUri?.let {
                    add("recipeUri=${java.net.URLEncoder.encode(it, "UTF-8")}")
                }
                if (autoOptimize) {
                    add("autoOptimize=true")
                }
            }
            return if (params.isNotEmpty()) {
                "photo_editor/$encodedSource?${params.joinToString("&")}"
            } else {
                "photo_editor/$encodedSource"
            }
        }
    }

    data object IDPhoto : Screen("id_photo/{sourceUri}") {
        fun createRoute(sourceUri: String): String {
            val encoded = java.net.URLEncoder.encode(sourceUri, "UTF-8")
            return "id_photo/$encoded"
        }
    }
}
