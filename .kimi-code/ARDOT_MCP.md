# Ardot MCP × Kimi Code 工作流速查

> 本文件记录 polang 项目中通过 Ardot 本地 MCP 完成“AI 生成 UI → 设计稿 → 代码”的常用 Prompt 和工具组合。
> 配置位置：`.kimi-code/mcp.json`

---

## 前置检查

每次使用前确认：

1. Ardot 桌面客户端已启动并登录
2. 已打开至少一个 `.ardot` 设计文件
3. Kimi Code 中运行 `/mcp` 能看到 `mcp__ardot__*` 工具

---

## 常用 Prompt 模板

### 1. 从零生成一个页面设计稿

在 Ardot 对话框中输入：

```
为 poLang 相册 App 生成一个移动端首页，包含：
- 顶部搜索栏和头像入口
- 照片网格（2 列），带日期分组
- 底部 4 个 Tab：相册、回忆、人物、设置
- 风格：简洁现代，深色模式优先
```

### 2. 在 Kimi Code 中读取当前设计稿并生成 Compose

```
当前 Ardot 设计稿是什么页面？请读取编辑器状态、截图、图层结构和 Design Token，然后为 poLang Android 生成对应的 Jetpack Compose 代码。
要求：
- 使用 Material3 组件
- 颜色、间距、字号与 Design Token 对齐
- 图片占位使用 Coil AsyncImage
- 字符串提取到 strings.xml 五语文件
```

### 3. 生成 iOS SwiftUI 代码

```
基于当前 Ardot 设计稿，为 poLang iOS 生成 SwiftUI 代码。
要求：
- 使用 SwiftUI 原生布局
- 颜色从 Assets 或 Color extension 引用
- 字体使用 .system 规范尺寸
- 文本同步到 Localizable.xcstrings 五语
```

### 4. 设计审查

```
检查当前 Ardot 页面是否存在布局问题（重叠、溢出、对齐异常），并给出修复建议。
```

### 5. 批量修改设计稿

```
在当前 Ardot 文件中，把所有主按钮的圆角改为 12dp，主色改为 #6750A4，并截图验证。
```

---

## 工具调用组合

| 任务 | 推荐工具链 |
|------|-----------|
| 了解当前上下文 | `mcp__ardot__fetch_editor_state` |
| 获取文件元数据 | `mcp__ardot__fetch_file_info` |
| 读取图层/属性 | `mcp__ardot__batch_read` |
| 获取截图 | `mcp__ardot__capture_screenshot` |
| 检查布局问题 | `mcp__ardot__capture_layout` |
| 读取 Design Token | `mcp__ardot__fetch_variables` |
| 创建/修改节点 | `mcp__ardot__batch_edit` |
| 查找空白区域 | `mcp__ardot__locate_available_space` |
| 新增页面 | `mcp__ardot__create_new_page` |
| 导出资源 | `mcp__ardot__scan_exportable_resources` → `mcp__ardot__export_nodes` |
| 设计健康审计 | `scripts/ardot-health-check.py`(batch_read JSONL + fetch_variables + components.json) |

---

## 设计稿 → Compose 的标准推理链

当用户要求“把当前设计稿转成 Compose 代码”时，Kimi 应按以下顺序调用：

1. `mcp__ardot__fetch_editor_state` — 确认当前页面
2. `mcp__ardot__fetch_file_info` — 确认文件和权限
3. `mcp__ardot__capture_screenshot` — 拿到视觉参考
4. `mcp__ardot__batch_read` — 拿到图层结构与属性
5. `mcp__ardot__fetch_variables` — 拿到颜色、字号、间距 Token
6. 生成 Compose 代码并写入项目文件
7. （可选）`mcp__ardot__export_nodes` — 导出图片资源到 `androidApp/src/main/res/drawable*`

---

## 成本与限制

- **免费**：所有 MCP 读取、编辑、截图、导出操作
- **消耗 Credits**：Ardot 内置 AI 生成（文生 UI、图生 UI）
- **单次限制**：`batch_edit` 每次建议不超过 25 个操作；`capture_screenshot` 单次建议不超过 10 个

---

## 故障排查

| 现象 | 排查 |
|------|------|
| `/mcp` 看不到 Ardot 工具 | 确认 Ardot 客户端已打开设计文件；检查 `.kimi-code/mcp.json` 端口是否为 `50501` |
| 工具调用超时 | 增加 `toolTimeoutMs` 到 120000 以上 |
| 截图为空 | 确认节点在画布可见范围内且有实际内容 |
| batch_edit 报错 | 确保每个 Insert/Copy/Replace 操作都有绑定名；不要对刚复制节点的子节点使用 Update |

---

## Token 同步工作流（2026-08-15 起，codegen 模式）

Ardot 降级为「token 活体预览层」，SSOT 是 `shared/src/commonMain/resources/design-tokens.json`。完整规范（token 分组 / 双端生成物 / 消费规则）见 `docs/03-TECHNICAL-SPECS/DESIGN_TOKENS_SPEC.md`，本节只记操作细节。

```
改 design-tokens.json
→ python3 scripts/gen-design-tokens.py        # 重新生成双端镜像 + build/design-tokens/ardot-variables.json
→ python3 scripts/sync-ardot-variables.py      # 经本地 MCP (50501) 推入 Ardot「PoLang Tokens」变量集
→ Ardot 画布实时预览；agent 用 capture_screenshot 截图留档
```

- 变量集：`PoLang Tokens`，Dark/Light 双模式（Dark 为 Mode 1，勿调换顺序）
- 语义色命名前缀 `scheme/*`（沿用 2026-08-14 Figma 先导期既有约定，勿改）
- 校验反向漂移：`fetch_variables` 对比 `ardot-variables.json`，以 JSON 为准回写
- 为什么不直接让 agent 调 MCP `apply_variables` 工具：337 个变量需内联 JSON，手抄易错；
  `sync-ardot-variables.py` 直连本地 MCP HTTP 端点，payload 文件原样上送，零转录误差

## 设计资产治理（2026-09-19 起）

组件目录 SSOT = `docs/08-UI-SPECS/screens/refs/ardot/components.json`;新建页面/改帧/token sync/快照入库前必跑
`scripts/ardot-health-check.py` 归零。完整工作流见 `skills/ardot-design-ops/SKILL.md`。
