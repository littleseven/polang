# ArDot 全画布英文默认 + 语言变量集切换 — 设计文档

- 日期：2026-08-23
- 状态：已实施（2026-08-23，Task 0-9 全量落地；验收见 refs 快照 + lang/ 台账）
- 云端文件：`polang-ui-spec`（ardot.tencent.com/file/715061534788814，唯一编辑区）
- 快照管线：`scripts/export-ardot-snapshot.py` → `docs/08-UI-SPECS/screens/refs/ardot/`

## 1. 背景与目标

画布 40 个设计帧中 31 个以中文成稿（历史还原期取材 `values-zh-rCN`），与 App EN 默认语言
（`values/strings.xml`）及真机常年英文的实况脱节；EN 文案更长引发的折行/裁字问题只能到真机
才暴露（近期两笔线上修复：编辑页底部 tab EN 折行、图片预览底栏 EN 裁字）。

目标：**所有设计图以英文版为默认；中文版为辅**——通过变量集 mode 切换实现单套帧双语，
EN 为 canonical 正稿，中文一键预览/导出，零物理重复帧。

## 2. 范围

### 2.1 In scope

| 页 | 帧数 | 帧（id） |
|----|------|----------|
| Gallery | 9 | empty(103:3) info(105:1) grid(105:45) search(107:112) search_no_result(107:156) scanning(107:189) sort_menu(107:264) selection(107:353) settings(118:1) |
| Settings | 10 | main_list(108:94) local_models(108:363) remote_models(111:1) sandbox(111:50) developer(111:129) dialog_language(111:276) dialog_stage(111:294) dialog_theme(111:446) add_remote_provider(166:3) provider_config(167:2) |
| Chat | 4 | empty(111:321) conversation(111:383) sidebar(111:492) guest-nudge-sheet(171:171) |
| Editor | 6 | current_crop(118:105) current_adjust(118:165) concept_a_hypic(118:243) concept_a_adjust(118:372) concept_a_crop(118:480) concept_a_beauty(118:583) |
| 已有 EN 帧补绑 | 5 | people/grid(171:3) people/detail(171:87) gallery/tag_control_v2(171:273) gallery/tag_stage_sheet(172:113) chat/empty-v2-guest(171:187) |

补绑目的：zh mode 下这些帧同样可切中文——「所有设计图」的完整含义。

### 2.2 Out of scope

- **Camera 7 帧**：产品冻结线（2026-08-16 决策）+ structure.json 实测无文本节点，零操作。
- **Play Store Assets 页**（152:1）：本就 en-US/zh-CN/zh-TW 三语资产帧，不动。
- **icon/spec_sheet**（132:4）：文档页，标签本就 EN，不动。

### 2.3 基线数据（2026-08-23 structure.json 统计）

29 个中文帧：322 文本节点 / 223 唯一字符串 / 182 条含中文 → 预估变量 ~190。

## 3. 机制设计

### 3.1 变量集

- 名称：**UI Language**（与 `PoLang Tokens` 完全独立）
- modes：`English`（默认）+ `中文`；建集时捕获 set id 与两个 mode id，工具脚本参数化
- 变量类型 STRING，scope = TEXT_CONTENT
- 命名：语义 + 页面前缀（`gallery.title`、`settings.themeMode`、`chat.emptyGreeting`）
- **粒度：唯一字符串一变量多处绑**（`取消`/`保存`/`今天` 等共享文案复用）

### 3.2 绑定策略

- **绑**：一切界面文案（标题/按钮/标签/提示/菜单项/聊天气泡文案）
- **不绑（保持 literal）**：纯数据文本——不含语言单位的日期、张数 `(6)`、`IMG_2026…`
  文件名、`sk-•••`、`tok` 计数、型号名（`deepseek-v4-flash`、`RetinaFace 500M-MN`）
  等与语言无关的字符串；含语言单位的数据文本（日期、量词）按 §3.4 绑变量
- 绑定操作走 `batch_edit`；绑定能力地图中 TEXT_CONTENT 无实证 → Step 0 探针为硬门
- 例外（2026-08-23 Task 7 裁定）：stat tile 数值/人名等 zh==en 语言不变文本，可为 tile 级统一而绑（无 strings 锚，note 标 no-anchor）——区分标准：计数括号类纯数据（`(6)`）skip；stat tile 数值/人名/品牌自名等 zh==en 但属界面内容表达的，可绑（no-anchor noop 标记）

### 3.3 EN 文案来源（SSOT 对齐）

- 凡 App 已有界面：逐条对齐 `androidApp/src/main/res/values/strings.xml` 实际 EN 值，
  杜绝自造文案漂移；zh mode 变量值对齐 `values-zh-rCN/strings.xml`
- 设计虚构内容（聊天消息正文、示例数据如「北京市朝阳区」）：按语义翻译，两语等义即可
- 画布 EN 字体取齐现有 EN 帧（tag_control/people 族）所用字体；中文字体维持 Sarasa
  Gothic SC 系——若字体是节点属性而非变量，EN 正稿统一改节点字体

### 3.4 语言敏感数据格式

- 日期：EN mode 用 `Aug 16, 2026 10:23` 风格，zh 用 `2026年8月16日 10:23` → 此类节点
  绑变量（两语各自格式）
- 量词文案（`12 张` → `12 photos`）：整串绑变量，不做拼接

### 3.5 与 token 体系的隔离（已核实）

`scripts/sync-ardot-variables.py` 按 `SET_NAME="PoLang Tokens"` 过滤（sync-ardot-variables.py:42），
UI Language 集不进 push/pull/check 门禁，双向同步零污染。

## 4. 工具链扩展

1. **`scripts/ardot-preview-mode.sh` 增加 `--lang en|zh`**：向帧写 UI Language 的
   variableModes override；与 theme override 并存（variableModes 为数组，同帧可同时挂
   两个 set 的 override——并存性列入 Step 0 验证项）。`auto` 语义=移除 lang override。
   > **Step 0 实测修正（2026-08-23）**：override 无法用 `null`/`[]` 移除（静默 no-op），且数组
   > 写入是 merge 语义；`auto` 须实现为「写回 English 默认 mode」。见 docs/08-UI-SPECS/screens/lang/probe-record.md §5。
   > ⚠️ 页根 override 不受引擎支持（Task 8 实证：PAGE 节点无 variableModes 属性）——语言/主题切换均须逐帧；脚本已加页根防呆。
2. **zh 版快照 pass**：「临时 override → export → 还原」，产物入 refs（命名如
   `gallery-grid-zh.png`），同浅色预览法先例。
3. **structure.json diff 语义（Step 0 实测，2026-08-23）**：已绑定文本节点的 `characters`
   导出为**引用字符串**（如 `"$182:135"`），与 fills（`"$2:149"`）、fontSize（`"$2:114"`）
   同构；未绑定节点仍导出字面量。快照 diff 中绑定操作表现为 `相册 → $182:135` 的
   characters 变更——**这是绑定而非数据丢失，审查勿误判**。详见
   `docs/08-UI-SPECS/screens/lang/probe-record.md` §6。

## 5. 实施顺序（风险驱动）

```
Step 0 探针（go/no-go 硬门）
  建集 + 3 个变量，绑 gallery/grid(105:45) 3 个文本节点，验证：
  ① TEXT_CONTENT 绑定 batch_edit 语法可行
  ② mode 切换后两语渲染正确（截图比对）
  ③ theme + lang 双 override 并存
  ④ structure.json 导出形态（characters 字段）
  ⑤ 桌面端直改绑定文本的行为（是否解绑/改写变量）——记录工效约定
  ❌ 任一关键项失败 → 回落备选：画布纯英文（EN 就地改写，不建变量集，中文仅在
     strings.xml + 已有 _zh 帧 + Play Store 页）
Step 1 Gallery 9 帧（pilot，含已 EN 的 tag_control 2 帧补绑）
Step 2 Settings 10 帧
Step 3 Chat 4 帧 + empty-v2-guest 补绑
Step 4 Editor 6 帧
Step 5 people 2 帧补绑 + 命名收敛 + 删 _zh 物理帧
每页流程：strings.xml 对齐表 → 批量建变量 → 批量绑定（batch_edit 逐 op 回 id 编排）
  → capture_layout problemsOnly + 截图验证（EN 更长 → 折行/裁字提前暴露）
  → zh mode 抽查 ≥1 帧
终局：sync-ardot-variables.py --check 零漂移 → export 快照（stat <5KB 空白帧检查）
  → zh 预览 pass 产物 → git 提交
```

并发纪律（画布多会话共享）：每步开工前 `fetch_editor_state` + 关键帧 children 计数重扫
基线；发现内容突变先停手报告；提交前 `git log` 防骑他人 HEAD。

## 6. 验收标准

1. ✅ 默认 English mode：全部设计帧 EN 渲染，capture_layout 零新增 problemsOnly 问题（39 帧零残留，Task 6 布局回归清零）
2. ✅ zh override：每页抽查 ≥1 帧正确回中文（含已 EN 帧补绑的页）（五页实证：Gallery/Settings/Chat/Editor/People）
3. ✅ `scripts/sync-ardot-variables.py --check` 零漂移（exit 0，PoLang Tokens 不受影响）
4. ✅ 快照入库：manifest + EN PNG + structure.json 更新，zh 抽查帧 PNG 入 refs（Task 9 完成）
5. ✅ EN 文案与 strings.xml 对齐率：界面文案 100%（设计虚构内容除外）——对齐率承诺达成，含 INSTANCE 级绑定对齐

## 7. 附带清理（已批准）

- **命名收敛**：EN = canonical 无后缀——`tag_control_v2_en` → `tag_control_v2`、
  `tag_stage_sheet_en` → `tag_stage_sheet`、`viewer-bottombar-en-preview` → 去 `-en`
- **删 `chat/empty-v2-guest-zh`(172:117) 物理帧**：语言 mode 落地后被变量切换取代
  （同 2026-08-19 浅色物理帧删除先例），其文案进变量集后再删

## 8. 风险与备选

| 风险 | 缓解 |
|------|------|
| TEXT_CONTENT 绑定未实证（绑定地图缺口） | Step 0 探针硬门；失败回落纯英文方案。**已实证可行（2026-08-23 GO）**：`U(node,{characters:"$varId"})`，见 probe-record.md §2 |
| 双 override 并存未验证 | 探针项 ③。**已实证并存可用（2026-08-23）**；但 override 移除须写回默认 mode，见 probe-record.md §5 |
| ~400 次绑定操作量大 | 逐页分批、batch_edit 逐 op 回 id、子代理可并行页级任务 |
| 绑定后画布直改文案工效变化 | 探针项 ⑤；若直改即破坏绑定，约定「文案改动走变量面板」并写入 spec。**该项延后**：需桌面端人工协助，Step 0 未验证（2026-08-23 控制器安排），工效约定待补 |
| export 渲染缓存冷致空白帧 | 已知问题，终局 stat <5KB 检查 + MCP capture 恢复套路 |
| 相机帧 refs 中 PNG 空白（camera-idle.png 3.2KB） | 既有问题非本任务引入，另行处理 |

## 9. 覆盖扩展批次（2026-09-13，用户报「切中文经常无效」根因修复）

**根因**：2026-08-27 全量落地后新增/演进的帧未补绑。引擎语义实证：帧内未绑定某集任何变量时，
对该集写 `variableModes` 会被引擎直接跳过（`potentialIssues: "Variable set ... not available"`，
MCP 仍回 success）——失效帧连 override 都不落盘。对照实验证明机制本身正常（gallery/grid 双切换全生效）。

**本批补绑**（64 绑定 / 57 新变量 / 4 复用，ledger 360→420 含 3 个补录游离 search 槽位）：

- **Organize 5 帧**：hub / category_grid / cleaned / swipe review / swipe done 全字面量文本 → 绑定；
  文案落后于 app 演进处按 strings.xml canonical 对齐（`Quick tidy up`→`Swipe to tidy`[8b2372ddc]、
  `Eyes-closed portraits`→`Low-quality portraits`、`Large videos`→`Large files`、
  `Screenshots`→`Screenshots & recordings`）
- **Memories 3 帧**：feed/detail 原为中文烘焙（违反 EN 默认约定）→ 绑定后 EN 正稿化
  （`回忆`→`Memories`、`精选`→`Best`）；store01-feed 为 Play 商店源帧，文案冻结用独立 store.* 槽位
  （`Moments`/`Trips` 不并 canonical 变量——Play 线上 en-US 资产已定稿）
- **散点**：gallery/selection 中文残留 `已选 3 项`→`3 Selected`（key=selected_items）、
  settings `Gallery Scan`/`Gallery Cleanup`、developer `Normal`、gallery/info 专辑样例文案、
  search/store01 `18 photos`
- **甄别豁免**：纯数字/单位/符号（`3.4 GB`/`✓`/`›`/`(6)`/`1:1`）、语言自名（`English`/`中文`/`Español`）、
  品牌/型号自名（`OpenAI`/`deepseek-v4-flash`/`MNN 2D106`）、注释/预览帧（annotation_nav_change、
  viewer-bottombar-preview、editor/tabbar-en-preview）、PlayStore 27 帧烘焙合成帧

**验收**：zh 模式 OCR 抽查 hub/feed/done 全中文零英文残留（单位除外）；EN 默认渲染 canonical 无裁断；
测试 override 已显式写回 English 默认。台账：organize.csv / memories.csv 新建，gallery.csv / settings.csv 追加。

**遗留**（不在本批）：chat/cleanup_confirm + cleanup_done（Chat 页 Organize 族帧）与
chat/store01-conversation + insight（Play 源帧）仍字面量——Chat 页不在本批批准范围；
Editor 页 current_*/concept_a* 字面量 fills 的 light 半切换缺口（独立批次）；Camera 7 帧空壳第三次丢失（另行拍板）。
