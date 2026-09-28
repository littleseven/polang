# ADR-011: 退役非 ui-driver 测试框架

**状态**: 已实施（波次0）
**日期**: 2026-07-28
**决策**: 用户
**依赖**: review §0.3-D4

---

## 1. 背景

历史上存在多套 Agent/UI 自动化测试方法（`agent-test` JSON 驱动框架、`qa-acceptance` 验收流程、`regression-test.sh` 端到端回归）。实际除 `ui-driver`（Accessibility 结构化文本驱动）外，其余已不再规模化运行，沦为维护负担与误导性文档。

## 2. 决策

**除 `ui-driver` 外，其他测试方法不再维护，代码及文档一律清理。**

## 3. 已执行清理（2026-07-28）

| 类别 | 清理项 | 保留 |
|---|---|---|
| 测试框架代码 | `app/.../testing/agent/**`（15 文件 / 4138 行） | — |
| `MainActivity` | 移除 `TestEntryPoint` 的 5 处引用 | — |
| `AndroidManifest` | 移除 `AgentTestActivity` + `AgentTestBroadcastReceiver` 注册 | — |
| commands | `.claude/commands/agent-test.md`、`qa-acceptance.md` | `ui-driver.md` |
| scripts | `scripts/agent-tester`、`regression-test.sh`、`scripts/tests/`（JSON 用例） | — |
| docs | 06-QA 下 QA 执行清单（已随本决策删除） | `PERFORMANCE_BASELINE_REPORT.md` |
| 索引 | `.claude/CLAUDE.md`（25→22）、`CLAUDE.md`（Useful Scripts）、`AI_TOOLS.md` | — |

**明确保留（不属「测试方法」）**：`scripts/test_*.py` / `test_*.sh`（deepseek/mnn/florence 等模型评测脚本）——属模型实验，用户确认暂不清理。归档源 `.qoder/skills/`（项目已声明不再维护）不动；`CHANGELOG.md` 历史记录不动。

## 4. 后果

- ✅ 减约 4138 行死框架代码 + 配套脚本/用例/文档，索引与事实一致。
- ✅ 测试注意力集中在 `ui-driver` 与 JVM 单测。
- ⚠️ 依赖 `AgentTestBroadcastReceiver` 的旧脚本（`.qoder/skills/dev-loop` 等归档文档里的示例）不再可用——这些本就是不再维护的归档源。

## 5. 状态

| 项 | 状态 |
|---|---|
| 代码/脚本/文档清理 | ✅ 2026-07-28 |
| `:androidApp:compileDebugKotlin` 验证 | ✅ BUILD SUCCESSFUL |

## 6. 相关

- review §0.3-D4
- `.claude/commands/ui-driver.md`（保留的唯一测试 command）
