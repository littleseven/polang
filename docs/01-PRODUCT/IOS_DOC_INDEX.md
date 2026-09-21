# iOS 文档索引（前门）

> **定位**：iOS 端文档的单一入口。2026-08-10 整合后，冗余/历史文档已**删除**（git 历史可恢复），只保留活文档 SSOT。
> **现状**：iOS 冷启动期，**尚无功能完善的稳定版本**。进度以 **origin/main** 为准；未合并分支标「in-flight」。
> **权威进展**：[`IOS_TASK_STATUS.md`](IOS_TASK_STATUS.md) · **整合留痕**：[`../reviews/2026-08-10-ios-doc-consolidation-audit.md`](../reviews/2026-08-10-ios-doc-consolidation-audit.md)。

---

## §1 活文档（现行事实来源，须保持最新）

| 文档 | 职责 |
|---|---|
| [`IOS_TASK_STATUS.md`](IOS_TASK_STATUS.md) | **缺口看板**：当前真实缺口 + 下一步任务（每项带代码/commit 证据） |
| [`IOS_PRODUCT_REFERENCE.md`](IOS_PRODUCT_REFERENCE.md) | **产品实现参考**：逐模块现状 + 双端能力对照，以 iOS 代码为准 |
| [`../superpowers/plans/2026-08-07-polang-kmp-ios-transformation.md`](../superpowers/plans/2026-08-07-polang-kmp-ios-transformation.md) | **Phase 路线图**：Phase 1-7 + 决策 + 风险登记 + 变更记录 |
| [`../superpowers/plans/2026-08-10-ios-implementation-tasks.md`](../superpowers/plans/2026-08-10-ios-implementation-tasks.md) | **缺口主排序**：G1-G7 → T0-T11 Wave |
| [`../reviews/2026-08-10-ios-android-consistency-gap.md`](../reviews/2026-08-10-ios-android-consistency-gap.md) | **5 屏 code 级差异审计**（最新；相机项已完成） |
| [`../../docs/08-UI-SPECS/PARITY_MASTER_PLAN.md`](../../docs/08-UI-SPECS/PARITY_MASTER_PLAN.md) | **Parity 顶层架构**（五层防线） |
| [`../03-TECHNICAL-SPECS/IOS_ANDROID_UI_PARITY.md`](../03-TECHNICAL-SPECS/IOS_ANDROID_UI_PARITY.md) | **Parity 方法论** |
| [`../../docs/08-UI-SPECS/README.md`](../../docs/08-UI-SPECS/README.md) | **Vibe Coding 流程** |
| [`../../docs/08-UI-SPECS/screens/*.yaml`](../../docs/08-UI-SPECS/screens/) | **逐屏契约**：camera / gallery-grid / chat / settings / model-download-center |
| [`../superpowers/specs/2026-08-10-ios-follow-command-design.md`](../superpowers/specs/2026-08-10-ios-follow-command-design.md) | **`/ios-follow` 命令设计**（待审批） |

---

## §2 已删减（2026-08-10，git 历史可恢复）

> 下列文档的事实已沉淀进上方活文档或代码，原文档删除以消除冗余。需要细节时查 git 历史（`git log -- <path>` 或 `git show <rev>:<path>`）。

- **4 排雷 Spike**（mnn / spm-quickjs / kmp-koog / beauty-metal）→ 结论在路线图 Phase 2
- **Phase 5 骨架**：app-skeleton design + plan、camera-s5-consistency、phase5-task20-21-verification → Phase 5 已交付，代码为现行事实
- **已实现/已完成**：adhoc-distribution-page-design、server-ios-adaptation-audit、product-reference-design、gallery-face-landmark design + plan、chat-phase6.2 plan、ios-ui-parity-spec → 事实在产品参考/看板/代码
- **已过期审计**：08-08 camera / gallery UI gap-analysis → 被 `2026-08-10-ios-android-consistency-gap.md` 取代
- **已吸收**：camera-gallery-gap plan（相册 G1-G4 → 看板 §6.6）、spec-test-gaps（gap 项 → 看板 §6.5）
- **过程产物**：2 份 Phase 5 kickoff 派发（camera-glm / gallery-k3）

---

## §3 真实状态快照（2026-09-20 校准，详见看板与产品参考）

- **代码规模**：iosApp 185 文件 / 41808 行 Swift + 5 Metal shader；shared iosMain 28 文件 / 2507 行；测试 58 文件 / 8494 行。
- **主界面**：5 页 Pager（相册/整理+扫描/Chat/人物/回忆），2026-09-16 已对齐 Android 主导航。
- **模块**：相册/整理/Chat/人物/回忆/证件照/扫描/搜索已落地 ✅；设置（备份恢复占位）、编辑器（去背景未接线）🔄；端侧 VLM 打标 = stub ❌。
- **相机**：✅ 已对齐，2026-08-16 起冻结（代码保留不加新功能）。
- **聚类**：MNN3.5 Apple bug 已经 ONNX Runtime embedder（`ORTFaceEmbedder.swift`）规避并合入 main，真机终验观察中。
- **i18n**：`Localizable.xcstrings` 767 键 × 五语（en / zh-Hans / zh-Hant / es / fr）。
