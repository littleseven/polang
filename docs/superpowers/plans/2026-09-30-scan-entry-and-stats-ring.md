# 扫描页入口便捷化 + 图库统计圆环口径澄清 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 底 bar 点「整理」不再强制重置到去重 tab（记住上次 tab，扫描中智能落扫描 tab）；图库统计圆环口径统一为「AI 打标完成率」并加中心微标签。

**Architecture:** 两处独立小改：(1) `MainPagerHost.onBarSwitchPage` 删除无条件 tab 重置、加 `TagGenerationService.isScanning` 智能落点；(2) `StatsCard` hero 圆环由语义向量占比改为与阶段行「内容标签」同源的 pass 完成率，复用新提取的 `TagPassProgress.percentRounded()`（同一舍入），五语文案同步。spec：`docs/superpowers/specs/2026-09-30-scan-entry-and-stats-ring-design.md`。

**Tech Stack:** Kotlin + Jetpack Compose（Material3），Gradle 单测（JUnit4），五语 res（en/zh-rCN/zh-rTW/es/fr）。

**注意：** 工作区有他人/其他会话的在途改动（`.qoder/`、`docs/superpowers/plans/2026-08-13-*` 等）。**每次 commit 只 `git add` 任务里列出的明确路径**，禁止 `git add -A` / `git add .`。

---

### Task 1: `TagPassProgress.percentRounded()` —— 统一百分比舍入（TDD）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/components/TagPassProgress.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/features/gallery/components/TagPassProgressTest.kt`

- [ ] **Step 1: 写失败测试**

在 `TagPassProgressTest.kt` 类尾部（最后一个 `@Test` 方法之后、类闭括号 `}` 之前）追加：

```kotlin
    @Test
    fun `percentRounded rounds half up and matches stage text`() {
        // 1000 张待处理 904 → 9.6% → 四舍五入 10（整数截断会给 9，同页圆环与阶段行将漂移）
        assertEquals(10, tagPassProgress(total = 1000, remaining = 904).percentRounded())
        assertEquals(80, tagPassProgress(total = 100, remaining = 20).percentRounded())
    }

    @Test
    fun `percentRounded of empty is zero and of complete is hundred`() {
        assertEquals(0, tagPassProgress(total = 0, remaining = 0).percentRounded())
        assertEquals(100, tagPassProgress(total = 100, remaining = 0).percentRounded())
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.features.gallery.components.TagPassProgressTest"`
Expected: 编译失败 `unresolved reference: percentRounded`

- [ ] **Step 3: 最小实现**

`TagPassProgress.kt`：文件头补 import（现有 import 区，紧随 `package` 行之后按序添加）：

```kotlin
import kotlin.math.roundToInt
```

文件尾部（`tagPassProgress` 函数闭括号之后）追加：

```kotlin
/**
 * 阶段完成率整数百分比（0..100，四舍五入）。阶段行 trailing 与图库统计圆环
 * 共用本函数（2026-09-30），杜绝同页两个口径/两种舍入的漂移。
 */
fun TagPassProgress.percentRounded(): Int = (fraction * 100).roundToInt()
```

（`TagPassProgress`/`tagPassProgress` 同文件同包，extension 无需额外 import。）

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.features.gallery.components.TagPassProgressTest"`
Expected: PASS（原有 5 个 + 新增 2 个全绿）

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/gallery/components/TagPassProgress.kt androidApp/src/test/java/com/mamba/picme/features/gallery/components/TagPassProgressTest.kt
git commit -m "feat(gallery): TagPassProgress.percentRounded——阶段行与统计圆环统一百分比舍入

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 2: 五语 strings —— caption 改口径 + 新增圆环微标签

**Files:**
- Modify: `androidApp/src/main/res/values/strings.xml`（EN，line 1439 附近）
- Modify: `androidApp/src/main/res/values-zh-rCN/strings.xml`（line 1430 附近）
- Modify: `androidApp/src/main/res/values-zh-rTW/strings.xml`（line 1430 附近）
- Modify: `androidApp/src/main/res/values-es/strings.xml`（line 1394 附近）
- Modify: `androidApp/src/main/res/values-fr/strings.xml`（line 1394 附近）

- [ ] **Step 1: 替换 `stats_hero_caption`（5 个文件各 1 处，旧行内容见各 old 串）**

`values/strings.xml`:

```xml
    <string name="stats_hero_caption">photos · %1$d%% AI-tagged</string>
```

`values-zh-rCN/strings.xml`:

```xml
    <string name="stats_hero_caption">张照片 · AI 打标已覆盖 %1$d%%</string>
```

`values-zh-rTW/strings.xml`:

```xml
    <string name="stats_hero_caption">張照片 · AI 打標已覆蓋 %1$d%%</string>
```

`values-es/strings.xml`:

```xml
    <string name="stats_hero_caption">fotos · %1$d%% etiquetadas por IA</string>
```

`values-fr/strings.xml`:

```xml
    <string name="stats_hero_caption">photos · %1$d%% annotées par IA</string>
```

（替换各自文件中同名旧行：EN `semantically indexed` / zh-CN `语义索引已覆盖` / zh-TW `語義索引已覆蓋` / es `indexadas semánticamente` / fr `indexées sémantiquement`。）

- [ ] **Step 2: 在 `stats_hero_caption` 行之后紧邻插入 `tag_stats_ring_label`（5 个文件）**

`values/strings.xml`:

```xml
    <string name="tag_stats_ring_label">AI-tagged</string>
```

`values-zh-rCN/strings.xml`:

```xml
    <string name="tag_stats_ring_label">AI 打标</string>
```

`values-zh-rTW/strings.xml`:

```xml
    <string name="tag_stats_ring_label">AI 打標</string>
```

`values-es/strings.xml`:

```xml
    <string name="tag_stats_ring_label">Etiquetado IA</string>
```

`values-fr/strings.xml`:

```xml
    <string name="tag_stats_ring_label">Annotées IA</string>
```

- [ ] **Step 3: 校验五语键齐**

Run: `grep -c 'name="tag_stats_ring_label"' androidApp/src/main/res/values*/strings.xml && grep -c 'name="stats_hero_caption"' androidApp/src/main/res/values*/strings.xml`
Expected: 每个目录各 `1`，共 5 个文件 × 2 键

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/res/values/strings.xml androidApp/src/main/res/values-zh-rCN/strings.xml androidApp/src/main/res/values-zh-rTW/strings.xml androidApp/src/main/res/values-es/strings.xml androidApp/src/main/res/values-fr/strings.xml
git commit -m "i18n(gallery): stats_hero_caption 改 AI 打标口径 + 新增圆环微标签 tag_stats_ring_label（五语）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 3: StatsCard 圆环统一口径 + 中心微标签

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/gallery/components/TagGenerationControlScreen.kt`
  - StatsCard 调用点（约 line 295-303）
  - `withSemantic` 状态变量（约 line 109）与其赋值（约 line 138）
  - `StatsCard` composable（约 line 1390-1504）
  - `stagePercentText`（约 line 1328-1329）
  - `StatsProgressRing`（约 line 1506-1542）

- [ ] **Step 1: 调用点换参数**

约 line 295-303，`StatsCard(...)` 调用中把 `withSemantic = withSemantic,` 一行替换为：

```kotlin
                remainingPass3 = remainingPass3,
```

（该行上下文：位于 `withLabels = withLabels,` 与 `personCount = personCount,` 之间。）

- [ ] **Step 2: 删除失去消费者的 `withSemantic` 局部状态**

约 line 109 删除：

```kotlin
    var withSemantic by remember { mutableIntStateOf(0) }
```

约 line 138（`refreshStats()` 内）删除：

```kotlin
                withSemantic = stats.withSemantic
```

（本文件 `withSemantic` 仅这两处写、一处读，读已随 Step 1 移除；`stats.withSemantic` 字段本身保留，另有 `GetGallerySummaryUseCase` 独立消费。）

- [ ] **Step 3: `stagePercentText` 复用 `percentRounded`**

约 line 1328-1329 替换为：

```kotlin
private fun stagePercentText(progress: TagPassProgress): String =
    if (progress.isEmpty) "—" else "${progress.percentRounded()}%"
```

（`percentRounded` 同包，无需 import。）

- [ ] **Step 4: `StatsCard` 换口径**

签名（约 line 1392-1400）中把参数 `withSemantic: Int,` 替换为：

```kotlin
    remainingPass3: Int,
```

（保留原 `@Suppress("LongParameterList") // 待重构：抽 stats 数据类` 注解与参数个数不变。）

body 中把（约 line 1404）：

```kotlin
    val semanticPct = if (totalMedia > 0) withSemantic * 100 / totalMedia else 0
```

替换为：

```kotlin
    // 口径=内容标签 pass 完成率，与 stages 区块「内容标签」行同源（stats.remainingForPass3）
    // 同舍入（percentRounded）——2026-09-30 澄清：不再用语义向量占比
    val taggedPct = tagPassProgress(totalMedia, remainingPass3).percentRounded()
```

caption（约 line 1467）`stringResource(R.string.stats_hero_caption, semanticPct)` 改为：

```kotlin
                        stringResource(R.string.stats_hero_caption, taggedPct),
```

圆环调用（约 line 1472）`StatsProgressRing(progress = semanticPct)` 改为：

```kotlin
                    StatsProgressRing(progress = taggedPct)
```

- [ ] **Step 5: `StatsProgressRing` 中心加微标签**

KDoc（约 line 1506）替换为：

```kotlin
/** 72dp 进度圆环（设计稿 ringSvg）：surfaceVariant 底环 + 品牌实色前景弧 + 中心两行（百分比 + AI 打标微标签）。 */
```

中心 `Text`（约 line 1535-1540，`"$progress%"` 那个 Text）替换为：

```kotlin
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$progress%",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.tag_stats_ring_label),
                fontSize = 8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
```

（`Column` / `Alignment` / `stringResource` 本文件已 import，无需新增。）

- [ ] **Step 6: 编译验证**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（无 unresolved reference / unused 警告不阻断）

- [ ] **Step 7: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/gallery/components/TagGenerationControlScreen.kt
git commit -m "feat(gallery): 图库统计圆环统一 AI 打标完成率口径（与内容标签阶段行同源同舍入）并加中心微标签

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 4: 整理页底 bar 落点 —— 记住上次 tab + 扫描中智能落扫描

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/main/MainPagerHost.kt`（import 区 + 约 line 103-108）
- Modify: `androidApp/src/main/java/com/mamba/picme/features/main/MainFloatingBottomBar.kt`（KDoc 约 line 24）

- [ ] **Step 1: 加 import**

`MainPagerHost.kt` import 区，`import com.mamba.picme.navigation.Screen`（约 line 34）之后按字母序插入：

```kotlin
import com.mamba.picme.service.tag.TagGenerationService
```

- [ ] **Step 2: 改 `onBarSwitchPage`**

约 line 103-108，把：

```kotlin
    // 底 bar 页切换（2026-09-06 导航统一）：目标为整理页时预选 ORGANIZE tab
    // （扫描不再是独立 bar 项，深链仍走 organizeTabRequest）
    val onBarSwitchPage: (Int) -> Unit = { index ->
        if (index == MAIN_PAGE_DEDUP) organizeTab = OrganizeTab.ORGANIZE
        onSwitchPage(index)
    }
```

整体替换为：

```kotlin
    // 底 bar 页切换（2026-09-06 导航统一；2026-09-30 入口优化）：目标为整理页时不再
    // 强制重置 tab——organizeTab 为 rememberSaveable，自然记住上次选择；扫描进行中
    // （isScanning，暂停不算）智能落 SCAN tab——此刻点整理的高频意图是查进度。
    // 深链仍走 organizeTabRequest
    val onBarSwitchPage: (Int) -> Unit = { index ->
        if (index == MAIN_PAGE_DEDUP && TagGenerationService.isScanning.value) {
            organizeTab = OrganizeTab.SCAN
        }
        onSwitchPage(index)
    }
```

- [ ] **Step 3: 同步 `MainFloatingBottomBar` KDoc**

约 line 24，把：

```
 * - 目标为整理页（[MAIN_PAGE_DEDUP]）时由调用方包装回调预选 ORGANIZE tab（见 MainPagerHost）
```

替换为：

```
 * - 目标为整理页（[MAIN_PAGE_DEDUP]）时由调用方包装回调处理 tab 落点——扫描进行中落
 *   SCAN，否则保持上次选择（见 MainPagerHost）
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/mamba/picme/features/main/MainPagerHost.kt androidApp/src/main/java/com/mamba/picme/features/main/MainFloatingBottomBar.kt
git commit -m "feat(main): 整理页底 bar 落点优化——记住上次 tab，扫描进行中智能落扫描 tab

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 5: `tag-control.yaml` 同步（iOS 重写唯一输入）

**Files:**
- Modify: `docs/08-UI-SPECS/screens/tag-control.yaml:27-33`

- [ ] **Step 1: 更新 stats_card 规格**

把现有块：

```yaml
    stats_card:
      hero_number:
        text: "<语义索引覆盖率 %>"                  # 大数字，品牌渐变前景
        foreground: "brandGradient #1EA75B→#7CEFA8"
        caption: "stats_hero_caption = %1$d%% semantically indexed"
      progress_ring:
        style: "实色 primary 弧（非渐变）；尺寸对齐 ardot 帧 tag_control_v2"
        track: surfaceVariant
```

替换为：

```yaml
    stats_card:
      hero_number:
        text: "<总照片数 %,d>"                      # 大数字=图库总数，品牌渐变前景
        foreground: "brandGradient #1EA75B→#7CEFA8"
        caption: "stats_hero_caption = %1$d%% AI-tagged（口径=内容标签 pass 完成率 tagPassProgress(totalMedia, remainingPass3)，与 stages 区块「内容标签」行同源同舍入；2026-09-30 弃语义向量占比口径）"
      progress_ring:
        style: "实色 primary 弧（非渐变）；中心两行：百分比 13sp SemiBold + 微标签 tag_stats_ring_label（8sp onSurfaceVariant，AI 打标/AI-tagged）；尺寸对齐 ardot 帧 tag_control_v2"
        track: surfaceVariant
```

- [ ] **Step 2: Commit**

```bash
git add docs/08-UI-SPECS/screens/tag-control.yaml
git commit -m "docs(ui-spec): tag-control.yaml 同步统计圆环 AI 打标口径与中心微标签

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 6: 全量验证

**Files:** 无新改动（只读验证）

- [ ] **Step 1: 全量单测**

Run: `./gradlew :androidApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，0 failed

- [ ] **Step 2: 静态检查（触及模块）**

Run: `./gradlew :androidApp:ktlintCheck :androidApp:detekt`
Expected: BUILD SUCCESSFUL（若 detekt 报本次引入的问题须修复；既有基线问题不属本任务）

- [ ] **Step 3: 打 debug 包**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 真机走查（需设备，走 `./scripts/auto-dev-loop.sh` 或手动 adb install）**

清单（对应 spec §6）：
1. 整理↔扫描手动切换 → 切去相册 → 再点底 bar「整理」→ 落**上次** tab（不再被重置）
2. 扫描进行中点「整理」→ 落**扫描** tab；暂停扫描后再点 → 落上次 tab
3. 图库统计：圆环 % == 阶段行「内容标签」trailing %（同数同舍入）；caption 为「张照片 · AI 打标已覆盖 N%」
4. 圆环中心两行：N% + 「AI 打标」微标签，72dp 内不溢出
5. 五语抽查：设置切语言（应用内语言，AppLanguage DataStore），es/fr/zh-rTW caption 与微标签显示正常

- [ ] **Step 5: 汇报**

汇报走查结果（每项 pass/fail + 截图）；后续项提醒：iOS `/ios-follow`（输入=已同步的 tag-control.yaml）、Ardot 画布 `gallery/tag_control` 帧文案同步。

---

## Self-Review 记录

- **Spec 覆盖**：spec §3 → Task 4；§4（含 4.1 五语）→ Task 2/3；§5 文档同步 → Task 5（tag-control.yaml）+ Task 4 Step 3（注释）；§6 验证 → Task 6。spec 范围外（Ardot 画布、iOS）未入任务，符合 spec「范围外」声明。
- **占位符扫描**：所有代码步骤给出完整代码/精确旧行，无 TBD/「适当处理」。
- **类型一致性**：`percentRounded()` Task 1 定义、Task 3 消费一致；`remainingPass3` 参数名 StatsCard 签名与调用点一致；`tag_stats_ring_label` Task 2 定义、Task 3/5 消费一致。
