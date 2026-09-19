# Play 商店图 zh-CN/zh-TW 行 v2.2.3 刷新 — 设计

- 日期：2026-09-18
- 状态：**已实施**（zh-CN/zh-TW 两行 + 2 FG 全部发布，API 回读 18/18 哈希一致；plan=docs/superpowers/plans/2026-09-18-playstore-zh-v2.2.3-refresh.md）
- 前置：en-US 行已于本日完成 v2.2.3 两轮刷新并发布（commit a6fba05d9 + 第二轮补烘，API 回读 8/8 哈希一致）

## 背景与目标

Play Store Assets 页（152:1）三行 × 8 帧 + 3 张 feature-graphic 中，en-US 已是 v2.2.3（teal→能量绿、中性黑→近黑绿，纯换肤零文案变化）；zh-CN / zh-TW 两行外框渐变与 chrome 已同构，但 **Screen 内容图仍为 09-07 旧渲染**。本次将 zh 两行 8 帧 + 2 张 FG 的 Screen 刷新为 v2.2.3 源帧渲染，并走全流程发布。

## 槽位 → 源帧 → 目标映射（执行 SSOT）

| 槽位 | 源帧 | zh-CN Screen | zh-TW Screen |
|---|---|---|---|
| 01-gallery | gallery/grid-store01 (300:29, Gallery 页) | 302:46 | 302:110* |
| 02-search | search/store01 (300:539) | 302:54 | 302:118* |
| 03-chat | chat/store01-conversation (301:13, Chat 页 y1735) | 302:62* | 302:126* |
| 04-people | people/detail-store01 (300:723, People 页 x1320) | 302:70* | 302:134* |
| 05-person-groups | people/grid-store01 (300:215) | 302:78* | 302:142* |
| 06-chat-welcome | chat/store01-welcome (300:651) | 302:86* | 302:150* |
| 07-insight | chat/store01-insight (301:96) | 302:94* | 302:158* |
| 08-privacy | settings/main_list (108:94, Settings 页) | 302:102* | 302:166* |
| feature-graphic | 同 01 源 | 161:19 | 161:21（en=161:20 已刷新） |

\* 按「帧根 → Screen = root+7」规律推定（302:39→302:46 已实证），执行前 batch_read 全量核实，以实际 id 为准。

源帧结构（已核实）：均含 status_bar(33px, 真机裁片 e3e99d49…) + system_nav_bar(51px, 黑底裁片 aefb7bbb…) chrome，铺满 393×852；Play 帧外框渐变 #45E8AD→#00BF7A、标题 #05301F。

## 方案：逐语言整批（B）

### Pass 1 — zh-CN
1. 前置校验：8 源帧 `fetch_variables({nodeId})` 的 availableVariableModes 含 UI Language(182:133)；逐源帧 patterns TEXT 盘点（structure.json 压缩会漏盘）；检查 PerfRow 调试行残留（有则导出期 visible:false）。
2. 8 源帧 `U(源帧, {variableModes:[{variableSetId:"182:133",modeId:"182:134"}]})` 切中文。
3. `export_nodes` scale2 → `upload_images` 灌 zh-CN 行 8 Screen + FG Screen(161:19)。
4. **还原：显式写回 English(182:132)**（null/[] 静默 no-op）。

### Pass 2 — zh-TW
1. 语言集无繁体模式：**覆写前先 live 盘点每源帧全量 TEXT（patterns 查询，记录 id + characters + 绑定态 $ref）作为 Pass C 还原基线**——本地 structure.json 可能滞后于今日画布改动，不作还原依据。逐 TEXT 覆写繁体字面量（U 纯值即真解绑，对 characters 不存在 no-op 陷阱）。词表对齐 values-zh-rTW：相簿/搜尋/設定/遠端/本機/張/**社會（非「社交」）**/智能助手小浪（非「智慧助理」）/相簿掃描/相簿整理；chat 消息体等非 UI 词按语境逐句译。
2. 若覆写值与既有变量值相同会被自动重绑 $ref——简繁同形句无碍。
3. export scale2 → 灌 zh-TW 行 8 Screen + FG Screen(161:21)。
4. **Pass C 还原**：按覆写前 live 盘点回填（$ref 节点恢复 `characters:"$<varId>"`，EN 字面量节点回原值）+ variableModes 回 English。

### 装填与发布
- Play 帧 export scale1 = 1080×1920 PNG → 填 `androidApp/src/main/play/listings/{zh-CN,zh-TW}/graphics/phone-screenshots/` 与 `feature-graphic/` + `google-play-listing/{zh-CN,zh-TW}/` + `docs-site/assets/shots/{zh-CN,zh-TW}/` 镜像。
- 发布：ABC 代理探活（`curl --socks5-hostname … 51081`）→ `/tmp/socks-bridge.py` 落文件起桥(51999) → `export https_proxy` → `python3 scripts/play-upload-resumable.py --listing androidApp/src/main/play/listings --prune-types phoneScreenshots,featureGraphic --langs zh-CN,zh-TW`（SSL UNEXPECTED_EOF 逐语言重试×3）→ API 回读哈希比对 8+1/8+1。

## 验收（逐帧硬 gate）

1. 简繁判定：逐帧 OCR → 词汇 grep `values-zh-rCN` / `values-zh-rTW`（相册 vs 相簿），禁目测；并核对文案 ↔ captions SSOT（google-play-listing/<locale>/screenshot-captions.json）。
2. v2.2.3 色彩：成品与 en 行对应槽位像素采样比对（能量绿系）；zh-TW 铺底与 en 同源差异应仅语言文字。
3. Pass 2 后源帧 EN 零残留（TEXT 全量 $ref / EN 字面量）+ variableModes 回默认；zh-CN Pass 后同样校验。
4. 导出冷缓存空白 → 重试（已知套路）；截图竞态空白 → 重试。

## 边界与风险

- **不碰 git**：仓库 mid-merge（chat.yaml UU），本次零 commit；成品与 spec 的提交等 merge 收口后另起。
- zh-HK（仅文本）不动；营销文案/captions 零修改。
- 源帧若某帧 unavailable 于 182:133 → 记录并按「文本字面量覆写」降级处理（同 zh-TW 法，简体词表 values-zh-rCN）。
- 视觉模型深色帧误报 → 判定一律走节点直读 + 像素采样 + OCR，不信纯视觉结论。
