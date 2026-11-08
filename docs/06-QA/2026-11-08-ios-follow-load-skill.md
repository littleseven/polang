# ios-follow load-skill 过程验收（2026-11-08）

> Android commit 8534c4572（端侧 load_skill 执行器，server 0.9.42）的 iOS 对等接线。
> 完整报告：`tmp/ios-follow/load-skill/report.md`（主 checkout）。

## 自动验收结果

| 项 | 结果 |
|----|------|
| `:shared:jvmTest`（含 ChatToolManifestConsistencyTest 反射逐项比对） | ✅ 绿 |
| `:shared:iosX64Test`（commonTest descriptor + iosTest IosChatPromptTest/IosAgentCompositionTest/IosSkillTransportTest） | ✅ 绿 |
| SharedKit XCFramework（iosMain 编译） | ✅ |
| xcodebuild device（CODE_SIGNING_ALLOWED=NO 编译降级） | ✅ BUILD SUCCEEDED |

## 交叉审查（reviewer，无 🔴）

- 🟡-1 技能链 vs 2 次工具调用收敛上限冲突（**两端共有**，Android `ChatPromptRules` 同病）→ 待用户裁决：建议端云 SSOT `AgentSkillContract.buildPromptSection` 补技能执行豁免句（会同时改 server prompt，未擅改）
- 🟡-2 接线路径零测试 → 已修（IosAgentCompositionTest 双桥接线 + IosSkillTransportTest 4 用例）
- 🔵-2/3/5 空白 body 判定 / IosChatPrompt KDoc / CLAUDE.md capabilities 清单 → 已修
- 🔵-1（取消不中止请求，语义等价）/🔵-4（预存 FQN 债）/🔵-6（pbxproj 再生噪声）→ 维持现状记录在案

## 环境问题（非本批次）

- 本机 Xcode 无可用 Apple ID（`No Accounts`，主 checkout 同复现）→ 真机签名装机待账号恢复
- server `/v1/skills` 未入访客放行清单（`Application.kt` isLlmPath）→ 未登录两端降级（待裁决，修法一行）
