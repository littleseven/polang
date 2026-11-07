# 架构健康审计快照（2026-11-07）

> **类型**：结论性系统快照（docs/reviews 登记制，不随代码维护）
> **审计方式**：本地全量门禁（assembleDebug + detekt + test）+ check_doc_sync.py + 三路只读深扫（androidApp / shared+engines / server+iosApp+构建）+ CI 失败日志归因
> **基线**：main @ 1.0.83（后随 40eafd36e 合并）；修复落 `fix/arch-health-p0` 分支

## 一、总评

骨架健康、纪律优秀，但**分层架构**与**CI 门禁**两道防线已实质失效。

健康面：本地门禁全绿；测试规模 androidApp 234 + shared 99 + server 75（远超文档声称）；commonMain 纯度零泄漏且有 Gradle 任务强制兜底；version catalog 零孤儿；生产 UI 零中文硬编码；TODO/FIXME ≈ 0。

## 二、P0 问题

1. **CI 门禁连红 4 次、三 job 全挂**（11-01~11-06），全部归因：
   - Doc Consistency：`docs/02-ARCHITECTURE/c4/` 的 `exported/` 为派生物目录不入 VCS（文档自述），check_doc_sync.py 图片存在性检查对 CI 必误报 → 已修（checker 加 `DERIVED_ARTIFACT_DIRS` 白名单）。
   - iOS Build：全新 checkout 上 Xcode 构建图校验要求 `SharedKit.xcframework` 已存在，Xcode 脚本阶段来不及产出（本地靠存量产物掩盖）→ 已修（CI 显式预构建 XCFramework + setup-java）。
   - Build & Verify：`processDebugNavigationResources` NPE（`"path" is null`）= Gradle #36284 / KT-81973，真实错误是被掩盖的**依赖解析失败**——CI 未设 `JITPACK=true`，走阿里云镜像间歇 404 → 已修（workflow 级 env + JDK 21 对齐 daemon toolchain）。
2. **androidApp Clean Architecture 分层名存实亡**：domain/ 住 35 个 data 实现（106 处 import，如 `domain/person/PersonRepository.kt` 直接 import DAO/实体）；features→data 98 处 import / 45 文件；domain 133 处 `android.*` import（含 25 处裸 `android.util.Log`）。"domain 纯 Kotlin" 声明证伪。

## 三、P1 技术债（未本轮修复，按序排期）

| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| 1 | Chat 域 God class | `ChatViewModel.kt` 4697 行 + 26 字段依赖容器；`ChatScreen.kt` 3191 行；4 个 >2000 行文件全在 features | 冻结膨胀，按 streaming/browser/cloud 接缝拆 delegate |
| 2 | beauty-engine 边界失守 | 9 文件 13 处 import `render/`、`internal/`（features 层 4 处最重） | 相机线冻结期收口到 beauty-api 门面，组合根接线豁免写入契约 |
| 3 | 文档数字漂移 | shared/AGENTS.md commonMain 136→152、commonTest 56→83；server/AGENTS.md 测试数 40→75、路由树漏 2 文件；CLAUDE.md "~50 test files"→~330 | 本轮已改对；长期建议活文档删易腐计数或自动生成 |
| 4 | detekt baseline 1194 条豁免 | `androidApp/detekt-baseline.xml` | 冻结新增、按规则类型分批消化 |
| 5 | 死代码 | 3 个未注册 Capability + `RemotePromptBuilder` 287 行死模板 + `Screen.Chat`/`Screen.Gallery` 死路由 | 本轮已删（含 RemoteControlCapability 假接线 KDoc 一并清除） |

## 四、P2 流程项

- server↔App 版本对齐纯人肉（0.9.20 vs 1.0.83 无工程化契约，「双交付铁律」历史上翻过车）
- OpenUI 新域单日 +6.8k 行，`OpenUiLibrary.kt` 1601 行成 shared 最大文件——拆分成本现在最低
- iOS 桥债 10 个回调桥 + 1 FlowWatcher（已知冻结至 iOS 1.0，需登记收口触发条件）
- `domain/backup` 是唯一无测试的核心 domain 包（备份恢复最怕回归）
- infra/ 三 Node 服务无 CI；GRDB.swift 未锁版本；iOS 仅挂 debug XCFramework
- MNN.framework 仅 device-arm64 slice（本轮为 CI 可复现已入库）：模拟器目标无法链接，iOS CI Test 步骤（PoLangTests 48 文件）暂撤，恢复条件 = MNN XCFramework 化（补 simulator slice，需 MNN 源码树构建）
- 工作区卫生：15 worktree（3 个 Qoder 残留）、23 本地分支 17% 陈旧

## 五、行动项状态（本快照登记时点）

- [x] CI 三处修复（checker 白名单 / iOS 预构建 / JITPACK+JDK21）
- [x] 死代码清理（5 文件删除 + 10 处文档同步）
- [x] 文档数字修正（shared/server/CLAUDE.md）
- [ ] 分层止血（domain 内 data 实现迁回试点：PersonRepository）— 下个迭代
- [ ] ChatViewModel 冻结 + 拆 delegate — 下个迭代
- [ ] detekt baseline 只减不增门禁 — 持续
