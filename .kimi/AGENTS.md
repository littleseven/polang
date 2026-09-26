# PoLang — kimi-cli 项目配置

> **定位**：本文件为 kimi-cli 专用项目级配置，与项目根目录 `AGENTS.md` 共同构成规范体系。  
> **优先级**：根目录 `AGENTS.md` > 本文件。模块级 `AGENTS.md` 在其管辖范围内优先。

## 项目速览

- **名称**: PoLang
- **类型**: Android + iOS 跨平台应用（Kotlin/Jetpack Compose + Swift/SwiftUI，KMP 共享 core；iOS 应用骨架设计 spec 已随交付清理，git 历史可查）
- **包名**: com.mamba.picme
- **架构**: Clean Architecture + MVVM
- **关键约束**: 100% 本地 AI 处理、交互反馈 < 100ms、五语言 I18N（EN/CN/TW/ES/FR）

## 启动上下文（Startup Context）

每次新会话启动时，**必须先读取** `../docs/05-DEVELOPMENT/LOCAL_ENVIRONMENT.md`，了解本机环境上下文：

- MNN 源码目录：`~/code/MNN`
- Hugging Face / ModelScope CLI 与缓存路径
- `~/code` 下已下载的端侧模型清单
- 项目内嵌 MNN 头文件目录（当前无预编译库）

## kimi-cli 工作规范

### 🚀 Token 优化与交互效率
- **引用替代全文**：严禁在对话中粘贴超过 50 行的代码片段。必须使用 `[file](file:///path)` 引用，仅展示关键行。
- **记忆优先检索**：处理渲染、坐标或架构问题时，必须先调用 `search_memory` 检索 `expert_experience`，避免重复阅读技术文档。
- **增量式修改**：使用 `StrReplaceFile` 时，`original_text` 必须足够唯一且简短，严禁替换整个文件。

### 文件操作偏好
- **并行读取**：对无依赖关系的多个文件，必须一次性并行调用 `ReadFile`。
- **精准定位**：未知路径时，优先使用 `Grep` 配合正则表达式定位，减少 `Glob` 的无效遍历。

### 构建与验证
- Android 代码修改后必须执行 `./gradlew :androidApp:assembleDebug` 验证编译
- iOS 代码修改后用 `xcodebuild -scheme PoLang -destination 'generic/platform=iOS' build` 验证（模拟器安装/截屏见 `/ios-build-debug`、`/ios-dev-loop`）
- 构建失败时基于日志自主修复，单任务最多自动修复 2 次
- Android 使用 `adb logcat -s "PoLang:*"` 查看运行时日志；iOS 真机用 DebugOverlay 状态画屏

### 多语言同步（I18N）
- 新增或修改用户可见字符串时，必须同步更新以下五个语言集：
  - Android `androidApp/src/main/res/values/strings.xml`（英文/默认）
  - Android `androidApp/src/main/res/values-zh-rCN/strings.xml`（简体中文）
  - Android `androidApp/src/main/res/values-zh-rTW/strings.xml`（繁体中文）
  - Android `androidApp/src/main/res/values-es/strings.xml`（西班牙语）
  - Android `androidApp/src/main/res/values-fr/strings.xml`（法语）
  - iOS `iosApp/PoLang/Localizable.xcstrings`（String Catalog，en / zh-Hans / zh-Hant / es / fr 五语）
- 双端同义键语义对齐（S5 双端体验一致），详见 `/i18n-validator` 与 `/ios-i18n-validator`

### 日志规范
- 统一标签格式：`PoLang:[ModuleName]`
- 示例：`private const val TAG = "PoLang:Camera"`

## 设计稿 ↔ 代码工作流（Ardot MCP）

> **状态（2026-08-15）**：Figma MCP 因 OAuth 403 无法完成登录，已暂停使用；Ardot 本地 MCP 已配置并通过验证，作为当前设计稿 ↔ 代码的主链路。
> **定位（2026-08-16 明确）**：Ardot 画布是**探索/预览层，不是规格 SSOT**——UI 契约以 `docs/08-UI-SPECS/screens/*.yaml` 为准，样式以 `design-tokens.json`（codegen）为准（见 `docs/03-TECHNICAL-SPECS/DESIGN_TOKENS_SPEC.md`）。「画布作 SSOT」路线已于 2026-08-15 废止。

### 配置

- 项目级 MCP 配置：`.kimi-code/mcp.json`
- Ardot 本地 MCP 端点：`http://127.0.0.1:50501/api/v1/mcp`
- 可用工具前缀：`mcp__ardot__*`（共 18 个，含读取、编辑、截图、导出、设计变量等）

### 使用前提

1. 启动 Ardot 桌面客户端并登录
2. 打开任意 `.ardot` 设计文件（本地 MCP 服务随文件打开而启动）
3. 在 Kimi Code 中运行 `/mcp` 确认 `mcp__ardot__*` 工具已连接

### 标准工作流

```
需求/参考图 ──→ Ardot AI 生成可编辑设计稿 ──→ 在 Ardot 中精修 ──→ Kimi Code 读取/截图/导出 ──→ 生成 polang 代码
```

- **AI 生成**：在 Ardot 对话框输入自然语言，如“生成一个 poLang 相册首页，底部 4 个 tab，顶部搜索栏”
- **设计精修**：在 Ardot 中调整布局、颜色、组件、Design Token
- **代码生成**：在 Kimi Code 中通过 `mcp__ardot__fetch_editor_state`、`mcp__ardot__capture_screenshot`、`mcp__ardot__batch_read`、`mcp__ardot__fetch_variables` 等工具获取上下文，生成 Jetpack Compose / SwiftUI 代码

### 成本说明

- **Ardot MCP 操作本身不消耗 Ardot Credits**：读取、截图、导出、批量编辑设计稿均免费
- **Ardot 内置 AI 生成仍消耗 Credits**：新用户 1000 Credits 初始额度，用完后可购买积分包，或通过 MCP 接本地模型

### Figma 资产处理

- 历史 Figma 文件可导出为 `.fig`，直接导入 Ardot 继续使用
- Figma MCP 登录问题解决后，可作为备选链路恢复，但当前默认链路为 Ardot

### 参考文档

- Ardot 本地 MCP 文档：https://docs.ardot.tencent.com/ardot-mcp/desktop-mcp
- 项目内使用示例：`.kimi-code/ARDOT_MCP.md`

## 项目文档索引

| 文档 | 路径 | 内容 |
|------|------|------|
| 顶层治理 | `../AGENTS.md` | 全局红线、文档治理、架构原则 |
| 产品需求 | `../PRODUCT.md` | 目标与约束 |
| 交互规范 | `../docs/01-PRODUCT/FEATURES.md` | 交互与体验规则 |
| 技术规范 | `../AGENTS.md` | 代码风格与审查清单 |
| 模块规范 | `../androidApp/src/main/java/com/mamba/picme/*/AGENTS.md` | 各模块实现细则 |
| 本地环境 | `../docs/05-DEVELOPMENT/LOCAL_ENVIRONMENT.md` | 本机 MNN、HF/MS、模型目录等环境上下文 |

## 快捷命令

```bash
./gradlew :androidApp:assembleDebug    # 构建调试 APK
./gradlew test                   # 运行单元测试
adb logcat -s "PoLang:*"          # 查看 PoLang 日志
```

> 完整开发指南（环境配置、IDE 快捷键、性能分析、发布流程）：`DEVELOPMENT.md`

## 关联 AI 工具

- **Lingma (IDE 内辅助)**: `.lingma/skills/`
- **OpenClaw (工作区上下文)**: `.openclaw/workspace/`
- **Skills 同步**: `.openclaw/skills/` → 符号链接 → `.lingma/skills/`
