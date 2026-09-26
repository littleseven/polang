# PoLang 系统架构说明（当前状态）

> 受众：开发 / 架构评审。范围：polang Monorepo 当前状态（2026-09-19）。
> 生成路径：`explore` → `system-modeler`（场景）+ `c4model`（Structurizr DSL 载体）。
> 阅读顺序：本说明 → 下方三视图信息图 → `polang-system.structurizr.dsl` → `polang-system.evidence.md` 查证。

## 三视图（信息图风格，2026-09-27 重绘）

> 渲染源为本目录 `system-context.html` / `containers.html` / `android-components.html`（正典模板 `../polang-architecture-infographic.html` 换内容不换骨架），Chrome headless 2x 导出至 `exported/*.png`。

### L1 系统上下文

![系统上下文](02-ARCHITECTURE/c4/exported/system-context.png)

### L2 容器视图

![容器视图](02-ARCHITECTURE/c4/exported/containers.png)

### L3 Android 组件视图

![Android 组件视图](02-ARCHITECTURE/c4/exported/android-components.png)

## 系统是什么

PoLang（破浪相册）是一个 Monorepo，包含三个可部署单元：

1. **Android App**（`:androidApp`，Kotlin + Compose）——主体应用。内部内嵌 KMP 共享层与原生引擎：
   - Compose 功能界面层（gallery / camera / chat / settings 等 14 个 feature）
   - **AndroidAgentComposition** 组合根：平台实现唯一直构点
   - **Agent 编排核心**（`:shared` commonMain）：AgentOrchestrator / CapabilityRegistry / PrivacyGuard / SceneManager，引擎无关
   - **Koog 远程推理层**：KoogChatAgent / KoogReActAgent，OpenAI 协议与 Anthropic Messages 协议分流（RemoteModelFactory）
   - **QuickJS JS 沙箱**：Chat 内取数 / 图卡 / capability.dispatch 写通路
   - **TAG 生成管线**：3-Pass 端侧 VLM 打标（Qwen3-VL-2B），OpenCL 超时自动降级 CPU
   - **原生引擎**：美颜（C++）、MNN 推理、agent-native（VLM JNI 桥）、sentencepiece
   - 本地数据：Room / DataStore / MediaStore（媒体 100% 端侧，[PRIVACY] 红线）
2. **iOS App**（`iosApp/`，SwiftUI）——相机（AVFoundation + Metal 4-pass 美颜）、相册、Chat 已落地；经 **SharedKit XCFramework** 复用 `:shared`（ChatAgentBridge 流式推理 + tool_calls）。
3. **PoLang Server**（`server/`，Ktor）——AI 网关（Channel 路由 / LlmProxy）、账号、管理后台、推荐、限流、AI 工程师链路（/v1/claude-chat → chisel 隧道 → 云主机 Claude Code）、问题上报（自动建 GitHub issue）；持久化 Exposed + SQLite + HikariCP。

## 外部依赖

- **OpenAI 兼容 LLM**（DeepSeek / 通义千问 / OpenAI 官方）与 **Anthropic**：远程文本推理，端侧直连或服务端网关代理两条通路并存
- **飞书 / Telegram**：IM 远程控制（FEISHU 模式，用户自配置通道）
- **腾讯云 COS**：服务端对象存储
- **GitHub**：用户问题上报落点

## 关键边界

- **隐私边界**：图片/视频不出端（媒体处理 100% 端侧）；仅文本/元数据走远程推理；IM 回传媒体属用户自配置豁免（ADR-008）
- **推理分流**：相机 AI 指令与 chat 全远程（Koog tool_calls）；端侧仅存 VLM 打标（TAG Pass3）与人脸检测等视觉推理
- **双端共享**：Agent 编排层在 `:shared` commonMain，Android 经组合根注入、iOS 经 XCFramework 消费

## 置信度与缺口

- 绝大多数节点/边为 **high**（代码 + AGENTS.md + 技术规格三源互证）
- 两处 **inferred**（图中虚线）：iOS → LLM 直连、iOS → Server——复用 shared Koog 链路可推断，但 iOS 侧通道配置未逐条核实
- 详细未知项与验证任务见 `polang-system.evidence.md` 末节

## 如何查看 / 维护

- 模型事实：用 Structurizr DSL 预览打开 `polang-system.structurizr.dsl`（或粘贴到 Structurizr Playground / Lite）
- 渲染图维护：改对应 `*.html`（换内容不换骨架）→ Chrome headless `--force-device-scale-factor=2` 截图导出 `exported/*.png`（exported/ 为派生物目录，不入 VCS）
- DSL 与 evidence 是唯一事实来源；渲染图均为派生物。架构变化先改 DSL，并同步更新 evidence.md 的 sourceRefs 与本说明
- 旧 Structurizr/Mermaid 线框导出（svg/mmd，2026-09-19）已于 2026-09-27 汰换，git/本地历史可查
