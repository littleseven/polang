# iOS Follow Gap Analysis — person-relation-fix（2026-09-29）

- **Android 基线**: `fix/person-relation-save-silent-drop` 012ecb2f6（rebase 于 main b99c1e2fd，含 chat-parts-m5 合并）
- **iOS 实现**: a01f96ea7 + 审查修复提交
- **审查方式**: reviewer agent 对抗审查（8 审查面；交叉模型写审在本会话物理不可用——Agent 工具无 model 参数，与 chat-parts-m5 同款披露）
- **spec**: person.yaml §7 保存流契约定稿（ebea299f3）

## 审查发现与处置

| 级别 | 发现 | 处置 |
|------|------|------|
| 🔴1 | 引导 toast 被 `emitHiddenHintIfNeeded` 覆盖——默认筛选+hidden>0（被拒场景几乎必然）时引导闪现即被「已隐藏 N 个单人组」顶掉，等于「被拒无反馈」在 iOS 换形态复活 | ✅ 已修：`emitHiddenHintIfNeeded` 加 `guard toast == nil`（Android snackbar 队列 vs iOS toast 单槽的容器差异补偿） |
| 🟡1 | onBack 与 onSaveResult 双 refresh 无代际守卫，旧快照后发覆盖新快照（低概率「改名被回退」假象） | ✅ 已修：reload 加单调 generation，过期代际发布丢弃 |
| 🟡2 | PoLangTests target 被既有陈旧 chat 测试打红（基线 b99c1e2fd 即红，非本批引入）——ChatMessageTypeTests 测的 Swift Codable type 推断已被 M5 parts 模型删除（覆盖已迁 Kotlin ChatHistoryStoreCodec iosTest）；ChatSmokeTest 用 M5 前 onText/onToolCall 双回调 API（B2 已改 onStreamEvent 单流+三参 onComplete） | ✅ 处置：删 ChatMessageTypeTests.swift（被测对象不存在）；ChatSmokeTest.swift → `.disabled` 保留待 M5 域改写（需真机后端）。测试 target 复绿恢复全部测试可跑 |
| 🔵1 | `repo.setSelf` 死代码 + applyPersonEdit 直调 db 绕过同类包装 | ✅ 已修：复用 `rename`/`setSelf` 包装 |
| 🔵2 | iOS subjectNotFound 覆盖面比 Android 宽（relation=nil 且人物已删时 iOS 也出 toast） | 登记：spec §7 已注明 iOS 有意补齐（超集通知） |
| 🔵3 | customLabel 未 trim 即落库（纯空白+已选谓词 → 空白胶囊） | 登记：**双端同病**（Android 同样不 trim），另开任务统一 |
| 🔵4 | pbxproj 混入 xcodegen/pod 再生噪声（~4/76 行与本批相关） | 不处理：project.yml 目录式源 SSOT 无漂移风险 |
| 顺带 | Android PersonViewModel.kt 落库版格式毛刺（`class PersonViewModel(    private val`） | ✅ 已修：纯空白修复（ktlint 面） |

## 验收判据

| 判据 | 结果 |
|------|------|
| Android assembleDebug（rebase 后定版） | ✅ 绿（APK 00:23 产物） |
| Android PersonRepositoryTest（含新增 4 条） | ✅ 绿（BUILD SUCCESSFUL, EXIT=0） |
| :shared:jvmTest（合并 M5 后 main） | ✅ 绿（6m39s；本分支零 shared 改动） |
| iOS Debug device build（app） | ✅ BUILD SUCCEEDED（首轮 @escaping 修复后） |
| iOS build-for-testing（含 6 条新测试编译） | ✅ TEST BUILD SUCCEEDED（0 error） |
| iOS 测试执行 / 真机安装截图 / 双端 SSIM | ⚠️ 待真机（iPhone unavailable；模拟器被 MNN device-only framework 阻断——既有环境约束，同 chat-parts-m5 Stage 4 降级） |

## 技术债登记

1. **ChatSmokeTest 恢复**：M5 onStreamEvent 三参形态改写 + 真机后端凭据（M5 chat 域跟进）
2. **customLabel 双端统一 trim**（🔵3）
3. **iOS 测试执行欠账**：PersonApplyEditTests 6 条编译通过但未执行（设备/模拟器均不可用）；与 PoLangTests 全量真机回归一并补
4. 主 checkout 遗留未提交物（docs plan/spec 改动 + BeautySettingsMoshiReproTest.kt + muse 调研文档）系其他会话在途，未动
