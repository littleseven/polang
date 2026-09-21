# IM 远程控制 Capability 模块技术实现规范 (Remote Control)

> **⚠️ 状态声明（2026-08-03）**：本文档描述的 Capability 化设计未按原方案落地（`RemoteControlCapability` 代码存在但未注册到 `CapabilityRegistry`）；IM 远程控制线本身已于 2026-07-27 重新激活（RemoteChannel 多通道：飞书 + Telegram，详见 `docs/03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md`）。本文档已压缩为状态说明，仅保留仍有效的本地约定。

> **边界声明（Boundary Statement）**
> - 本文档仅承载本模块的实现细节（架构、代码约束、检查清单）。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/01-PRODUCT/FEATURES.md#5-im-远程控制融合入口` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 跨模块技术架构以 `docs/03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md` 为准。
> - 禁止将模块级实现细节回填到顶层 `AGENTS.md`；跨模块或专项技术内容应下沉到对应模块文档或 `docs/*_TECH_SPEC.md`。

**模块定位**：通过飞书等 IM 平台 + LLM 实现 App 远程控制的核心 Capability 与基础设施组件。

**主要维护者**：项目开发者

**阅读对象**：RD、AI Agent

---

## 1. 核心产品逻辑 (Core Product Logic)

- **[IM_FLOW] IM 消息驱动**：所有远程操作由 IM 消息触发，通过飞书 WebSocket 实时推送至设备
- **[LOCAL] 命令执行在设备端**：飞书仅做消息中转，不执行任何业务逻辑
- **[PRIVACY] 人脸数据不离开设备**：涉及人像的检测/编辑强制设备端执行，不上传
- **[FEEDBACK] 操作结果回传**：所有命令执行后必须向 IM 回传结果（成功/失败/进度）
- **[CONFIRM] 敏感操作确认**：删除/批量操作/人像编辑/解绑需用户确认
- **[BIND] 扫码绑定**：首次绑定必须设备端扫码确认，防止未授权访问

## 2. 注册语义与 SSOT

- **SSOT**：Capability 注册表、命令映射、新增指南与生命周期规范以 `docs/04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md` 为准，本文不再维护能力清单。
- **注册入口**：应用级 Capability 全部在 `PoLangApplication.initializeCapabilities()` 启动期注册；`NavigationCapability`/`SystemCapability` 由 `MainActivity` 创建后经 `DisposableEffect` 注册、onDispose 注销；页面级（如 `CameraCapability`）随页面 `DisposableEffect` register/unregister。
- **注册语义（2026-08-07 起）**：`CapabilityRegistry.register()` 同名同实例跳过、同名**不同实例替换**；`unregister()` 实例感知——仅当注册表当前持有的就是该实例时才移除，防止旧实例 onDispose 竞态摘除新实例。
- **`RemoteControlCapability`**：未注册到 `CapabilityRegistry`，`execute()` 恒返回 `METHOD_NOT_FOUND`；管理命令（绑定/解绑/自动确认）由 `RemoteCommandDispatcher` 直接调用其公开 API。

## 3. 仍有效的本地约定

- **agent-core 边界**：禁止在 `AgentCommands.kt` 添加远程控制专用 `Remote*` 类型；禁止在 agent-core 引入飞书 SDK 依赖；飞书 OAPI SDK 只允许出现在 `app` 模块 domain 层。
- **单例访问**：通过 `RemoteControlCapability.getInstance()` 获取实例，禁止直接构造；状态字段使用 `@Volatile` 修饰。
- **日志规范**：绑定状态变更等关键操作记录 `PoLang:RemoteControl` TAG。
- **结果回传**：命令执行结果（含错误）必须通过 IM 回复，不能静默失败。
- **隐私红线**：涉及人脸的照片处理必须在设备端完成，不得经飞书平台中转。
- **LLM 调用**：IM 远程的 LLM 调用共享 `UnifiedRemoteClient`，但使用独立的 System Prompt。
- **通道与分派实现**（`FeishuChannelHandler` / `RemoteCommandDispatcher` / 相册搜索快速通道）：以 `IM_REMOTE_CONTROL_TECH_SPEC.md` 为准。

## 4. 确认流程矩阵

| 操作类型 | 确认要求 | 实现 |
|----------|----------|------|
| 浏览/搜索 | 不要求 | 直接执行 |
| 图片编辑（非人像） | 不要求 | 直接执行 |
| 图片编辑（人像） | 确认 | 飞书交互卡片确认 |
| 批量操作 | 确认 | 飞书交互卡片确认 |
| 删除操作 | 确认 | 飞书交互卡片确认 |
| 设备解绑 | 双重确认 | App 内确认 + 飞书确认 |
| 系统设置修改 | 确认 | 飞书交互卡片确认 |

## 5. 常见陷阱检查清单 (Checklist)

- [ ] 飞书 SDK 的 `ws.Client` 是否初始化成功？（检查 App ID/Secret 配置）
- [ ] 飞书 SDK 的 packaging exclude 是否配置？（META-INF 冲突）
- [ ] 敏感操作是否加了确认流程？（参照 §4 确认矩阵）
- [ ] 人脸相关操作是否强制设备端执行？（隐私红线）
- [ ] 命令执行结果是否总是回传？（即使失败）
- [ ] 新增 Capability 是否按 CAPABILITY_REGISTRY.md 附录 A/B 注册并声明生命周期？

## 6. 与产品文档对照 (Product Alignment)

**必须满足的产品指标**：
- ✅ IM 消息驱动 → 飞书 WebSocket 实时推送，无需云端中转
- ✅ 命令执行在设备端 → 飞书仅做消息通道
- ✅ 人脸数据不离开设备 → 人像编辑强制走本地推理
- ✅ 操作结果回传 → 所有命令执行后通过飞书 OAPI 回复
- ✅ 敏感操作确认 → 飞书交互卡片 + App 内双重确认
- ✅ 扫码绑定 → 设备端扫码绑定，防止未授权访问

---

> **维护者**：项目开发者 · **最后更新**：2026-08-03
> **状态**：已冻结（服务端替代方案优先）· 仅作历史设计参考；注册语义以 `CAPABILITY_REGISTRY.md` 为 SSOT
