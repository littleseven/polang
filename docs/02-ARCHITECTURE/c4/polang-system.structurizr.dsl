workspace "PoLang 破浪相册" "当前状态系统架构（2026-09-19 快照，由代码/文档证据反向建模）" {

    !identifiers hierarchical

    model {
        user = person "相册用户" "使用 Android / iOS 客户端管理相册、拍照美颜、与 AI 对话"

        polang = softwareSystem "PoLang 破浪相册" "Monorepo：Android 应用 + iOS 应用 + Ktor 服务端 + KMP 共享层" {

            androidApp = container "Android App" "Kotlin + Jetpack Compose；:androidApp 组合根模块" "Kotlin / Jetpack Compose" {

                featureUI = component "Compose 功能界面层" "features/*：gallery / camera / chat / settings / person / search / tagviewer / translation / editor / backuprestore / idphoto 等" "Jetpack Compose"

                agentComposition = component "AndroidAgentComposition 组合根" "Application.onCreate 接线，平台实现唯一直构点，经 AgentOrchestrator.initialize(AgentDependencies) 注入" "Kotlin"

                agentCore = component "Agent 编排核心 (:shared commonMain)" "AgentOrchestrator / CapabilityRegistry / PrivacyGuard / SceneManager / IntentGuard，引擎无关" "Kotlin (KMP commonMain)"

                koogLayer = component "Koog 远程推理层 (:shared)" "KoogChatAgent / KoogReActAgent / RemoteModelFactory；OpenAI 协议 + Anthropic Messages 协议分流" "Kotlin + JetBrains Koog"

                jsSandbox = component "QuickJS JS 沙箱" "run_gallery_script 取数 / draw_chart 图卡 / capability.dispatch 写通路（CommandRisk 分级 + 用户确认）" "QuickJS + JSBridge"

                tagPipeline = component "TAG 生成管线" "TagScanOrchestrator / TagGenerationScheduler（前台 Service）/ OpenClGuardian（OpenCL 超时降级）" "Kotlin"

                beautyEngine = component "美颜引擎 (:engines:beauty-api + beauty-engine)" "大美丽美颜：C++ native 管线、帧同步美妆、容灾降级" "C++ / JNI"

                mnnCore = component "端侧推理 (:engines:mnn-core + agent-native + sentencepiece)" "MNN 推理运行时；agent-native 构建 libagent_native.so（VLM JNI 桥，Qwen3-VL-2B 打标）；sentencepiece 分词" "C++ / MNN / JNI"

                localData = component "本地数据层" "Room（含 polang_llm_log.db 结构化可观测性）/ DataStore / MediaStore；媒体处理 100% 端侧" "Room / DataStore"
            }

            iosApp = container "iOS App" "SwiftUI；相机 AVFoundation + Metal 4-pass 美颜；经 SharedKit XCFramework 消费 :shared（ChatAgentBridge 流式远程推理 + tool_calls UI 动作）" "SwiftUI + SharedKit XCFramework"

            server = container "PoLang Server" "Ktor 后端：AI 网关（Channel 路由 / LlmProxy）、账号体系、管理后台、推荐引擎、限流、AI 工程师（/v1/claude-chat）、问题上报" "Kotlin / Ktor" {

                llmGateway = component "AI 网关" "ChannelRegistry / ChannelBalanceService / LlmProxy / LlmRoute" "Ktor"
                authAccount = component "账号与认证" "邮箱注册 / Token 认证（AuthRoute）" "Ktor"
                adminConsole = component "管理后台" "Admin 视图：设置（白名单）/ 问题诊断 / 静态资源" "Ktor + static"
                aiEngineer = component "AI 工程师链路" "ClaudeChatRoute / ClaudeToolResultRoute：chisel 隧道 → 云主机 Claude Code；app_tool_request 下行到 App" "Ktor + SSE"
                recommendRate = component "推荐与限流" "RuleEngine 推荐 / RateLimiter" "Ktor"
                serverDb = component "服务端数据库" "Exposed + SQLite + HikariCP；Db / Migrations / Tables" "Exposed / SQLite" {
                    tags "Database"
                }
            }
        }

        llmOpenAI = softwareSystem "OpenAI 兼容 LLM 服务" "DeepSeek / 通义千问 / OpenAI 官方（OpenAI Completions 协议，tool_calls）" {
            tags "External"
        }
        anthropic = softwareSystem "Anthropic Claude" "Messages 协议端点（Anthropic 官方及兼容端点）" {
            tags "External"
        }
        imChannels = softwareSystem "IM 远程控制通道" "飞书 + Telegram：远程指令下发、媒体回传（用户自配置通道，ADR-008 豁免）" {
            tags "External"
        }
        cos = softwareSystem "腾讯云 COS" "对象存储" {
            tags "External"
        }
        github = softwareSystem "GitHub" "用户问题上报自动创建 issue（littleseven/polang）" {
            tags "External"
        }

        # --- System context relationships ---
        user -> polang "拍照 / 相册管理 / AI 对话"

        # --- Container relationships ---
        user -> polang.androidApp "使用"
        user -> polang.iosApp "使用"

        polang.androidApp -> llmOpenAI "远程文本推理（chat / 相机 AI 指令 tool_calls）" "HTTPS / OpenAI API"
        polang.androidApp -> anthropic "远程文本推理（Messages 协议）" "HTTPS"
        polang.androidApp -> polang.server "AI 网关代理 / 账号 / AI 工程师 / 问题上报" "HTTPS /v1/*"
        polang.androidApp -> imChannels "远程控制指令收发（FEISHU 模式）" "HTTPS / 长连接"

        polang.iosApp -> llmOpenAI "远程文本推理（chat tool_calls，经 :shared Koog 层复用）" "HTTPS / OpenAI API" {
            tags "inferred"
        }
        polang.iosApp -> polang.server "账号 / 网关（推断，待验证）" "HTTPS" {
            tags "inferred"
        }

        polang.server -> llmOpenAI "网关代理转发（Channel 路由 + 余额/限流）" "HTTPS"
        polang.server -> anthropic "AI 工程师链路（/v1/claude-chat → Claude Code on 云主机）" "chisel 隧道 / SSE"
        polang.server -> cos "对象存储读写" "COS SDK"
        polang.server -> github "问题上报自动建 issue" "GitHub API"

        # --- Android App component relationships ---
        polang.androidApp.featureUI -> polang.androidApp.agentComposition "ViewModel / UseCase 依赖注入"
        polang.androidApp.agentComposition -> polang.androidApp.agentCore "initialize(AgentDependencies) 注入平台实现"
        polang.androidApp.agentCore -> polang.androidApp.koogLayer "远程推理编排（Koog agent 循环 + ToolRegistry）"
        polang.androidApp.featureUI -> polang.androidApp.jsSandbox "Chat 内 JS 卡片 / 取数脚本"
        polang.androidApp.jsSandbox -> polang.androidApp.agentCore "capability.dispatch 写通路（用户确认）"
        polang.androidApp.agentCore -> polang.androidApp.jsSandbox "JS 引擎无关层（agent/core/js）"
        polang.androidApp.featureUI -> polang.androidApp.beautyEngine "相机预览 / 拍照美颜"
        polang.androidApp.featureUI -> polang.androidApp.tagPipeline "TAG 扫描控制（3-Pass）"
        polang.androidApp.tagPipeline -> polang.androidApp.mnnCore "VLM 打标（Qwen3-VL-2B，OpenCL/CPU 降级）"
        polang.androidApp.beautyEngine -> polang.androidApp.mnnCore "人脸关键点（MediaPipe/MNN 双源）"
        polang.androidApp.agentCore -> polang.androidApp.localData "消息记忆 / 设置 / 结构化日志"
        polang.androidApp.featureUI -> polang.androidApp.localData "相册索引 / Room / MediaStore"
        polang.androidApp.koogLayer -> polang.server "经服务端网关的远程推理（可选通道）" "HTTPS"

        # --- Server component relationships ---
        polang.server.llmGateway -> polang.server.serverDb "Channel 配置 / 用量"
        polang.server.authAccount -> polang.server.serverDb "账号 / Token"
        polang.server.aiEngineer -> polang.server.serverDb "会话 / 白名单"
        polang.server.recommendRate -> polang.server.serverDb "规则 / 限流状态"
        polang.server.adminConsole -> polang.server.serverDb "管理读写"
    }

    views {
        systemContext polang "system-context" {
            include *
            autoLayout tb
            title "PoLang 系统上下文图（L1，当前状态）"
            description "用户、PoLang 系统边界与外部依赖：LLM 服务商、IM 通道、COS、GitHub"
        }

        container polang "containers" {
            include *
            autoLayout tb
            title "PoLang 容器图（L2，当前状态）"
            description "Android App / iOS App / PoLang Server 三大可部署单元；虚线关系为推断（inferred）"
        }

        component polang.androidApp "android-components" {
            include *
            autoLayout tb
            title "Android App 组件图（L3，当前状态）"
            description ":androidApp 内部：Compose 界面层 → 组合根 → :shared Agent 编排核心 → Koog 远程推理；引擎与数据层"
        }

        styles {
            element "Person" {
                shape Person
                background #08427b
                color #ffffff
            }
            element "External" {
                background #999999
                color #ffffff
            }
            element "Database" {
                shape Cylinder
            }
            element "Container" {
                background #1168bd
                color #ffffff
            }
            element "Component" {
                background #85bbf0
                color #000000
            }
            relationship "inferred" {
                style dashed
                color #999999
            }
            element "inferred" {
                opacity 60
            }
        }
    }
}
