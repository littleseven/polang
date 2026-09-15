# 2026-09-16 ios-follow batch-20260915 双端同步审查（gap analysis）

> 批次：`ios-follow-batch-20260915`（模式 B，基线 main 9032fbd6a，范围 efa6e4646..9032fbd6a 四域：organize v2 / chat / design-tokens v2.2.3 / gallery）
> 分支：`feat/ios-follow-batch-20260915`（worktree `.worktrees/ios-follow-batch-20260915`）
> 提交链：Stage2 契约 `0cd50f1b0` → Stage3 实现 `b914f62e3` → 审查修复 `7bced5ac5`
> 审查交叉：K3 写的平台/领域层 → GLM review（agent-78）；GLM 写的 UI 层 → K3 review（agent-79）。🔴 修复后由主会话（K3）复核编译绿。

## 审查发现与处置

### 🔴 阻塞（5 条，全部清零）
| # | 问题 | 处置 |
|---|------|------|
| 1 | PhMediaBridge.deleteMediaAwaitingOutcome guard 早退不回 completion → 上滑删除 UI 永久卡飞出态 | 已修（两 guard 补 completion(false)） |
| 2 | OrganizeRepository `Dictionary(uniqueKeysWithValues:)` 重复 uri trap 崩溃 | 已修（uniquingKeysWith last-wins） |
| 3 | SwipeReview 飞出 220ms 窗口内 undo 竞态 → 决策落错卡片（可删错照片） | 已修（decide 携 expectedUri 校验 + canUndo 加 !isFlying） |
| 4 | PoLangUITests 两条用例按旧 4 页序断言，确定性失败 | 已修（新页序 Camera0/Gallery1/Organize2/Chat3/Person4；人物页补 person_root a11y id） |
| 5 | xcstrings `Found %lld results for「%@」` zh-Hans 值字面量「找到」（占位符全丢，父提交遗留） | 已修（找到 %lld 张「%@」的照片） |

### 🟡 建议（修复 9 条 + 登记 6 条）
已修：OCR 计数 utf16 口径（+分解重音边界测试）、桶内/meta 排序稳定化（enumerated tie-break）、TagDatabase+Organize 全 8 处 SQL prepare 守卫、提示胶囊安全区、翻页手势态复位、删除位前移索引修正、USER_IMAGE_TEXT 气泡底色对齐 Android 绿底（spec §5 已同步修正）、底栏 a11y Label + MainTabView 失实注释、字节格式化三处收口为一（消 I18N 硬编码单位）、chatBubble token 值校准（design-tokens.json paddingH/V 18/14、textSize/lineHeight 16/24，codegen 双端同步，Android 不消费这四值故零行为变化）。

登记（随报告技术债）：refine_template 未接线（chat.yaml §15）；§16b 三宿主覆盖缺口；DUPLICATES hub 卡降档（organize.yaml §8 ios_duplicates_card_downgrade）；cleaned Undo/回收站预览卡裁剪（ios_cleaned_undo_cut）；录屏不可判定（ios_screen_recording_undetectable）；xcstrings 三处继承自 Android SSOT 的 es/fr 措辞不对等（改需双端同步）。

### ✅ 审查确认无问题维度
- 领域管线口径逐条与 Android 一致（互斥裁定顺序/等值边界/keeper 扣减/coverage/保守偏置/桶序/TTL/nil≠0 哨兵），测试真实锁定（BlurAnalyzer 手算值、边界恰等用例）
- chat 原位 upsert 真原位（保位置/id/timestamp）；上滑删除手势 §16b 逐条一致
- MainTab 页索引位移 13 处引用点全适配；shared 场景映射翻译层（sharedScenePage）兜底
- xcstrings 结构：62 键×5 语全 translated、占位符零失配、plurals 合 CLDR

## 验收结果

| 项 | 结果 |
|----|------|
| Android 基线编译 | ✅ assembleDebug（修复后复验见 state.json） |
| shared jvmTest | ✅ exit 0 |
| iOS 编译 | ✅ BUILD SUCCEEDED（generic/platform=iOS，CODE_SIGNING_ALLOWED=NO） |
| iOS 单测（模拟器） | ⚠️ 降级：MNN.framework 无 simulator slice 链接失败；真机 unavailable。T1 领域层 30+ 断言经出树冒烟验证通过；PoLangTests 7 套件 839 行待真机跑 |
| 截图/SSIM 深浅双跑 | ⚠️ 降级：Android 设备离线 + iPhone unavailable；复用 tmp/ui-reference 旧截图（v2.2.3 前色值，仅结构参考） |
| gen-design-tokens --check | ✅ 7 文件绿 |

## ⚠️ 待真机终验 / 待用户裁决
1. **主导航差异（最重要）**：Android 相机不在 pager（5 页：相册/整理/聊天/人物/回忆），iOS 保留 Camera 页 0（5 页：Camera/相册/整理+扫描/聊天/人物），回忆页 iOS 不存在（memoryPage token 已双端生成）。是否做完整导航统一（相机转全屏路由 + 回忆页新建）待裁决。
2. iOS 上滑删除/整理删除会弹系统确认框（Apple 强制，无静默通路）——与 Android「删除不再询问」体验的差距为平台本质差异，已台账登记。
3. 真机任务流：整理全链路（扫描→hub→详情→删除→cleaned）、swipe 手势手感、20 张批提交系统确认框、chat 气泡观感、深浅双模式色值。
4. iOS 底栏无「相册」项（既有结构，相机页手势进相册）；Android 底栏五项含相册——差异待裁决。

## 📋 技术债清单
- organize 领域管线未下沉 shared commonMain（iOS Swift 移植版；BlurAnalyzer/DuplicateGrouper 等纯函数下沉候选）
- T8 dedup_hash 扫描器（DUPLICATES 类目启用前提；MD5/pHash 回填 + hub Config 展开卡）
- organize 信号存储 iOS 落 TagDatabase 扩列（本批已建）；全库扫描性能（5 万量级）与回填错峰待真机实测
- chat：FIND_SIMILAR 追加分支随以图搜图引擎落地；拒绝回退守卫 iOS 无该路径（注释已记）；refine_template
- settings_menu_entry（设置页「相册整理」一级入口）未接线
- 图标资产缺口：mat_cleaning_services 系（过渡用 mat_o_delete_sweep）
- MainTabView.sharedScenePage()：shared 侧加 ORGANIZE Scene 契约时的唯一适配点
- PersonViewModel 等旧页对 shared 4 页场景契约的依赖（翻译层已兜底）
