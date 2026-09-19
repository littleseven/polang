# Ardot Design Ops 工作流与 Skill 设计文档

- 日期: 2026-09-19
- 状态: 已确认（用户拍板）
- 范围: Ardot 云端设计稿（PoLang 破浪相册）+ 仓库脚本/快照/Skill 体系

## 1. 背景与决策链

### 现状（2026-09-19，承接 2026-09-19-ardot-lightdark-reorg-design.md 收官态）

- 文件 11 页 / 113 顶层节点；Dark 唯一正稿 + 帧级 variableModes 钉定做 Light 预览（不建物理副本）。
- token 链路已 codegen 化：`design-tokens.json` SSOT → `gen-design-tokens.py` → `sync-ardot-variables.py` 双向同步「PoLang Tokens」变量集（Dark/Light 双 mode）。
- 已有脚本：`ardot-theme-audit.py`（literal 扫描）、`ardot-light-verify.py`（Light PNG 像素判据）、`ardot-preview-mode.sh`（帧级 mode 钉定）、`export-ardot-snapshot.py`（快照入库）。
- 已有组件 ×5：floating_tab / status_bar / system_nav_bar / provider_row / model_row（Components 页）。

### 用户拍板决策

1. **核心痛点**: Light/Dark 覆盖易漏（新帧/新组件漏钉 mode、漏绑 literal 时 Light 直接崩色）+ 新页面重复造轮子（组件目录缺失，风格漂移）。
2. **组件化策略**: 规则驱动「同一结构出现 ≥2 次即组件化」+ 首批收编共享件（约 10–15 个），后续增量收编；不做全量原子化。
3. **门禁形态**: 事件驱动必跑（新建/改帧后、token sync 后、快照入库前），**不进** `ai-gate.sh` 提交硬门禁（避免依赖 Ardot 客户端在线卡死提交）。
4. **方案选型**: 单 skill 编排 + 一个健康检查脚本 + 组件目录文件（方案 A；弃用拆三 skill 的方案 B 与纯文档约束的方案 C）。

## 2. 总体架构（三层 + 编排）

```
SSOT 层   design-tokens.json(已有) → gen-design-tokens.py / sync-ardot-variables.py(已有)
目录层   docs/08-UI-SPECS/screens/refs/ardot/components.json(新)
          — 组件目录 SSOT: id/用途/双模预览/绑定状态/使用规则
检测层   scripts/ardot-health-check.py(新)
          — 一站式健康报告(literal/组件化违规/布尔漏适配/目录漂移), 退出码=健康与否
编排层   skills/ardot-design-ops/SKILL.md(新, 同步镜像到 ~/.qoder-cn/skills/)
          — 三场景工作流 + 坑清单 + 验收标准; agent 一切 ardot 画布操作的统一入口
```

主题策略不变：Dark 唯一正稿；组件全部绑双模 token 随宿帧 variableModes 换色；系统条类（status_bar/system_nav_bar/floating_tab）沿用布尔双图层 Light 自适应。

## 3. 组件目录与入库标准

### components.json 条目结构

```json
{
  "name": "component/top_bar",
  "nodeId": "xxx:yyy",
  "page": "Components",
  "purpose": "页面顶栏(返回/标题/动作位)",
  "darkPng": "components/top_bar-dark.png",
  "lightPng": "components/top_bar-light.png",
  "tokenBound": true,
  "variants": [],
  "rules": "新页面顶栏一律用实例, 禁止手画"
}
```

### 入库标准（新组件必须全过）

1. 零 literal 色（`ardot-theme-audit.py` 判定，fills/strokes/effects 全覆盖）
2. 经 `ardot-preview-mode.sh` 钉 light 导出 PNG，`ardot-light-verify.py` 像素判据通过
3. 登记入 `components.json` 并导出双模预览图入 `refs/ardot/components/`

### 复用规则

- 同一结构出现 ≥2 次即组件化（health-check 指纹检测兜底）
- 新页面「先查目录、能拼不画」；页面帧内只允许组件实例 + 页面特有布局
- 组件更新只改主组件；发现游离副本（同构非实例）一律替换为实例

### 首批收编候选（实施时以重复子树指纹实测为准）

top_bar · bottom_sheet 外壳 · primary_button · card · chip · badge · dialog · empty_state · search_bar · slider，加已有 5 件（floating_tab/status_bar/system_nav_bar/provider_row/model_row）。

## 4. ardot-health-check.py 检测项

一次全量 `batch_read`（readDepth=-1）后输出报告（JSON + 摘要）与非零退出码：

| # | 检测项 | 说明 |
|---|--------|------|
| 1 | literal 泄漏 | 复用 theme-audit 逻辑；Light 崩色根源，目标恒为零 |
| 2 | 组件化违规 | 重复子树指纹匹配：≥2 棵同构子树未用组件实例 → 收编候选；游离副本 → 替换清单 |
| 3 | 布尔图层漏适配 | 含系统条/导航条语义的帧缺 light 布尔图层 |
| 4 | 目录漂移 | components.json 登记的 nodeId 在画布已不存在/被解绑 |

事件驱动必跑点（skill 强制）：新建/改帧后、token sync 后、快照入库前。

## 5. 三个场景工作流（SKILL.md 主体）

### S1 新建页面/帧

前置检查（Ardot 客户端在线 / **全程单会话串行，严禁并发操作同一文件**）→ 查 components.json 拼装 → 零 literal 自检 → preview-mode 钉 light 导出 → light-verify 像素判据 → 还原 dark → health-check 全量过 → 快照入库（`git rm` 陈旧 PNG）→ manifest/帧清单更新。

### S2 样式/token 变更

design-tokens.json → gen-design-tokens.py → sync-ardot-variables.py --push → 抽代表帧双模导出验证 → health-check → 快照收口。

### S3 健康审计

health-check 全量 → 按报告修 → 修复后重跑归零 → 快照收口。

## 6. 风险与坑（记忆库既有教训，SKILL.md 须内嵌）

- 跨页 M 移动孤儿化 → 已验证 M 挂载套路；batch_edit ≤25 ops 分批
- batch_edit 超时（>110s）可能已实际应用 → 先 batch_read 核实再重试，勿盲目重发
- 非当前页节点操作必须带 `fileUrl`（`:` 编码为 `%3A`）
- AI 生成残留 `frozen_render_snapshot` 全屏覆盖层 → 生成物异常先查它
- 实例数字尺寸需先 U(null) 解绑；U(svg) 静默空转
- 快照导出不清理旧帧 PNG → 提交前 ls 核对 + git rm
- export 冷缓存空白 → 预热重试
- Light 判定必须像素采样，视觉模型对 Dark 帧会误报浅色
- **严禁并发会话/并行子代理操作同一 ardot 文件**（相机页曾被并发会话恢复）

## 7. 交付物清单

| 产物 | 路径 | 形态 |
|------|------|------|
| Skill | `skills/ardot-design-ops/SKILL.md`（+ 用户级镜像） | 新增 |
| 健康检查脚本 | `scripts/ardot-health-check.py` | 新增（复用 theme-audit 内核） |
| 组件目录 | `docs/08-UI-SPECS/screens/refs/ardot/components.json` + `components/*.png` | 新增 |
| 首批组件收编 | Ardot 画布 Components 页 + 目录登记 | 画布操作 |
| 文档同步 | `docs/08-UI-SPECS/README.md`、`.kimi-code/ARDOT_MCP.md` 索引更新 | 编辑 |
