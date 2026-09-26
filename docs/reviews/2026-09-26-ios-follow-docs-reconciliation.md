# iOS Follow 批次报告 — docs-only 增量对账（2026-09-26）

> **批次类型**：纯文档对账（模式 B 变体，iOS 代码零改动）。上一批实施批见 `2026-09-16-ios-follow-main-nav-memories.md`（PASS）。
> **输入**：iOS 缺口看板校准基线 2026-09-20 之后合入 main 的 Android 功能提交。
> **输出**：`IOS_TASK_STATUS.md` §1 新增 #13-16 + §3/§4 联动；`PARITY_MASTER_PLAN.md` §8 新增 A12。

## 1. 对账范围（09-20 基线后 Android 交付 → iOS parity 影响）

| Android 交付 | 关键 commit | iOS 现状（证据） | 处置 |
|---|---|---|---|
| 工程师任务卡（五态渲染/审批四动作/登记状态机/信息升级） | `aaf01902b` `23f6b4985` `53aa0103e` `cabe4208e` | iosApp 全库检索 `EngineerTask` 零命中 | 登记 #13（工程师模式是否上 iOS 待拍板） |
| 任务中心页（跨会话聚合/审批/锚定）+ 活跃标记/角标 | `5b3ba6728` `74c0d6be7` `8b5bec490` | `TaskCenter` 零命中 | 并入 #13 |
| 用户任务协议 M1（UserTask 协议/Room 表/注册表/双适配器/双 Tab/五语） | `8d07adecc`→`8b5bec490`（合并 `9f95ba646`） | `UserTask` 零命中；spec §10 [PARITY] 明确「M1 Android 定稿后走 /ios-follow、记入 parity 台账」 | 登记 #13 + A12；§11 iOS 映射（VLM stub → TAG 适配器降级形态）留实施批按平台差异台账裁定 |
| 意图路由契约与路由器 M1+M2（ADR-015） | `9aacc1fa4` | 契约在 commonMain（`shared/.../intent/` 三件 + jvmTest）；`iosMain IosChatPrompt.kt` 已随提交同步；iosApp 无消费点 | 登记 #14（仅剩 Swift chat 入口接线，XCFramework 重建零重写） |
| 设置页「上报问题」入口迁移 | `b463b23c5` | `SettingsScreen.swift` 无对应入口；服务端 `/v1/report-issue` 已就绪 | 登记 #15（小项） |
| 顶栏弃微信式居中改左对齐 + 回忆页统一 | `8d0fc581a` `c1eaeb8d5` | `Features/Gallery/Components/AppTopBar.swift:27-28` 仍 `.frame(maxWidth: .infinity)` 默认居中 | 登记 #16（小项；Ardot 稿已左对齐终态） |
| Guide 图文重映射/商店素材（`7df4b9d62`） | — | Play 商店资产，非 app parity | 不登记 |

## 2. 文档动作清单

- `docs/01-PRODUCT/IOS_TASK_STATUS.md`：头部加 2026-09-26 增量对账注记；§1 新增 #13-16（均带代码/commit 证据）；§3 小项批次扩至 #15/16、新增第 7 项「chat 任务体系 + 意图路由接线」大项；§4 注记同步。
- `docs/08-UI-SPECS/PARITY_MASTER_PLAN.md`：§8 新增 A12（P1，指向 IOS_TASK_STATUS #13-16）。
- `IOS_DOC_INDEX.md` §3 快照不动（iOS 代码未变，快照事实仍真）。

## 3. 拍板记录（2026-09-26 用户裁定：全部上）

1. **工程师模式（claude-tunnel 生态：任务卡/任务中心）上 iOS**——#13 按全范围跟随。
2. **用户任务体系上 iOS**——TAG 扫描适配器映射同步做；iOS 端侧 VLM 为 stub（§1-1），实施批先补 platform_differences 台账定降级形态（候选：扫描适配器以「不可用态」注册占位、解锁 VLM 后激活），不阻塞协议/注册表/双 Tab 骨架。
3. **#14 意图路由接线先行**——不依赖 #13，shared 契约已就绪。

> 实施排序建议：#14（小，先行）→ #15/16 小项批 → #13 大项（工程师模式 → 任务中心 → 用户任务 M1 骨架）。

## 4. 验收

- ✅ `python3 scripts/check_doc_sync.py` 4 项全过。
- ✅ 新增行均有代码/commit 证据，无臆断项；`不收录纯 Android 侧变更`约定未违反（登记项均为 parity 缺口）。
