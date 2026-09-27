# PoLang 系统架构说明（当前状态）

> 受众：开发 / 架构评审。范围：polang Monorepo 当前状态（2026-09-19）。
> 生成路径：`explore` → `system-modeler`（场景）+ `c4model`（Structurizr DSL 载体）。
> 阅读顺序：本说明 → 下方三视图信息图 → `polang-system.structurizr.dsl` → `polang-system.evidence.md` 查证。

## 三视图（信息图风格，2026-09-27 重绘）

> 渲染源为本目录 `system-context.html` / `containers.html` / `android-components.html`（正典模板 `../polang-architecture-infographic.html` 换内容不换骨架），Chrome headless 2x 导出至 `exported/*.png`。

### L1 系统上下文

![系统上下文](exported/system-context.png?v=20260927-4)

### L2 容器视图

![容器视图](exported/containers.png?v=20260927-4)

### L3 Android 组件视图

![Android 组件视图](exported/android-components.png?v=20260927-4)

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
- 渲染图维护：改对应 `*.html`（换内容不换骨架）→ Chrome headless 两遍法导出 `exported/*.png`（exported/ 为派生物目录，不入 VCS）：
  1. 量高：往 `<head>` 注入 `document.title=Math.ceil(document.documentElement.scrollHeight)` 的 load 监听，`--headless=new --window-size=1300,600 --dump-dom --virtual-time-budget=3000` 读 `<title>` 取内容高 H。**量高与截图必须同窗口宽度**（2026-09-27 事故：量高漏带 window-size 走 800px 默认窄视口，芯片流式布局重排后高度≈1.8 倍，截图按 1300 宽内容只填 55%，画布拖近半空白）；
  2. 截图：`--headless=new --hide-scrollbars --force-device-scale-factor=2 --window-size=1300,H --screenshot=<name>.png <name>.html`；
  3. **sips 重编码**：`sips -s format png`（或 `--resampleWidth 1500` 顺带瘦身）过一遍——Chrome 截图原始字节存在「Chrome 自身渲染为全白」问题（苹果系解码器正常，极具迷惑性）；
  4. **机器验收**：像素行扫填充率 ≥95%（逐行最暗像素 <190 的最后行位置），外加 Chrome 实渲染截图体积 >15KB。尺寸/字节/预览工具零证明力（同日三起事故：空白废图、串图、半空画布均逃过文件级检查）。
- DSL 与 evidence 是唯一事实来源；渲染图均为派生物。架构变化先改 DSL，并同步更新 evidence.md 的 sourceRefs 与本说明
- 旧 Structurizr/Mermaid 线框导出（svg/mmd，2026-09-19）已于 2026-09-27 汰换，git/本地历史可查
