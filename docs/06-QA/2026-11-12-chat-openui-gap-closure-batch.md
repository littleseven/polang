# 2026-11-12 Chat/OpenUI 缺口清偿批次 QA 记录

> **分支**：`feat/chat-openui-gap-closure`（集成分支，worktree `.worktrees/gap-integration`）
> **范围**：①OpenUI 版本协商+未知组件可见降级（feat/openui-lang-version）②OpenUI 照片并行解析+流式预览预算（fix/openui-photo-parallel-resolve）③recall_chat_history+requestJson 录制+cached_tokens（feat/chat-recall-history）④M3~M6 排期路线图（spec §6）⑤文档同步债首批清偿
> **iOS 流（feat/ios-cloud-turn-openui）**：交付时另见其提交与后续验收记录

## 1. 构建与单测（worktree 实测，全部 ✅）

| 命令 | 结果 |
|---|---|
| `JITPACK=true ./gradlew :shared:jvmTest`（三流合并后全量复跑） | BUILD SUCCESSFUL（含 OpenUiEngineTest 占位节点 3 例 / MessagePartsCodecTest langVersion roundtrip / ChatHistoryRecallTest 8 例 / LatestLlmWireTapTest 5 例） |
| `JITPACK=true ./gradlew :androidApp:compileDebugKotlin` | BUILD SUCCESSFUL |
| `JITPACK=true ./gradlew :androidApp:testDebugUnitTest`（全量） | BUILD SUCCESSFUL（1745 例基线 + OpenUiStreamingBudgetTest 5 例 + ChatHistoryRecallCapabilityTest 4 例，0 失败） |
| `./gradlew -p server test`（lang_version 流） | BUILD SUCCESSFUL |
| `python3 scripts/check_doc_sync.py` | 全过 |

## 2. 真机冒烟（设备 92a0cfb8，集成分支 debug APK，2026-11-12）

- ✅ **冷启动**：安装（`adb install -r` 保数据）→ 主界面照片网格正常加载；首帧深色为启动瞬间（8s 后内容完整），无崩溃对话框
- ✅ **流式文本回复**：发送消息 → 打字机流式 → markdown 列表/加粗/emoji 渲染正常；Turn 聚合间距视觉正常（截图 `/tmp/m4_04~06`）
- ✅ **图表卡端到端**：自然语言「draw a bar chart of my photos per month this year」→ ECharts 图卡渲染正常（轴/网格/图例）；月度数据为 0 属设备数据面（云端回合读到的今年照片为 0），非渲染缺陷。注：M4 清单原写的 `/chart` 并非注册 DEV 捷径（协议 §1.1 仅登记 `/html` `/task`），已按自然语言口径验证并在 M4 文档中修正表述
- ✅ **`/html` DEV 捷径**：双卡产出——①「本地相册图引用（media:// 契约）」卡固定高度 fullpage 预览形态（渐隐+展开按钮）+ 本地图正常嵌入；②「相册月度趋势（ECharts 预制库）」inline 撑开形态渲染正常——**双形态分流与 media:// 白名单注入均工作**
- ✅ **长列表滚动**：两轮快速上下滚动无异常、无崩溃
- ✅ **logcat**：全程 0 FATAL / 0 PoLang error（45 条 AndroidRuntime 行经甄别全部来自 uiautomator dump 自身）
- ✅ **截图基线**：`scripts/screenshot-baseline/chat/` 更新 `agent_text_streaming.png` / `agent_text_body.png` 两帧（本轮回复较短，streaming 帧接近完成态；`agent_code_collapsed.png` 保留 M3 旧帧待下次含代码块回复时更新）

### 观察项（不阻塞，登记跟踪）

- 🟡 OpenUI 卡脚标「OpenUI v1.0 · p0」与正文末行轻微叠压（历史卡重现，存量表现，非本批引入；后续 OpenUI 卡 UI 批次处理）
- 🟡 一处疑似流式末行文字叠影（`/tmp/m4_08_chart2.png`，可能是截图时序与打字机余帧叠加；低频，观察）

### 未执行项（原因如实登记）

- ⏳ Layout Inspector 重组范围实测：需交互式工具，无法无人值守执行，保持待补
- ⏳ 🔵4 首拍文案残留（≤50ms 闪烁）：静态截图无法判定，保持待补
- ⏳ 🔵6 OUTPUT_ERROR 通用文案：DEV 捷径本地直出不依赖网络，未能构造真实工具失败场景
- ⏳ `/task` 任务卡冒烟：避免在真机触发真实 AI 工程师隧道任务

## 3. 本批已知限制

- OpenUI `lang_version` 协商为**双交付生效**：server 需部署（done 帧携带）+ App 发包（比对提示）；生产 server 未重新部署前该链路静默兼容（字段缺省=无提示）
- cached_tokens 依赖供应商流式尾帧回传 usage（DeepSeek/Moonshot 恒带；OpenAI 官方流式需 stream_options，Koog 未传则采集不到——已如实登记 CHAT_PROTOCOL_TECH_SPEC §5）
- requestJson 录制仅 DEBUG 构建（release 零开销零保留，隐私口径不变）
