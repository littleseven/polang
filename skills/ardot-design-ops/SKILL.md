---
name: ardot-design-ops
description: Ardot 设计稿资产治理——新建页面/帧、token 样式变更、健康审计三场景编排,保障 Light/Dark 全域可切与组件化不腐败。Use when creating/modifying Ardot frames or components, syncing design tokens to canvas, exporting snapshots, or auditing design health (ardot, 设计稿, Light/Dark, 组件化).
version: 1.5.0
created: 2026-09-19
updated: 2026-09-25
maintainer: [RD] 全栈工程师
tags: [ardot, design-system, light-dark, ui-consistency]
---

# Ardot Design Ops

> **定位**:ardot 画布一切增删改的统一入口——组件目录先行、零 literal、事件驱动健康检查。
> **触发时机**:新建/修改 ardot 帧或组件、token 变更同步画布、快照入库、设计健康审计。

---

## 触发条件

- 用户提到 ardot / 设计稿 / 画布 / Light 模式 / Dark 模式 / 组件化
- 要在画布新建页面、帧、组件,或批量改样式
- `design-tokens.json` 变更后需同步画布并验证
- 快照入库(`export-ardot-snapshot.py`)前后

## 核心原则

1. **Dark 唯一正稿**:Light 只经帧级 variableModes 钉定预览,永不建物理副本;`PoLang Tokens` modeMap `2:0`=Dark/`79:1`=Light 勿调换。
2. **零 literal**:一切颜色绑双模 token;literal 是 Light 崩色唯一根源。
3. **先查目录、能拼不画**:新页面先读 `docs/08-UI-SPECS/screens/refs/ardot/components.json`,≥2 次复用即组件化。
4. **事件驱动必跑 health-check**:新建/改帧后、token sync 后、快照入库前,退出码必须归零。
5. **单会话串行**:严禁并发会话/并行子代理操作同一 ardot 文件(相机页曾被并发恢复)。

## 前置检查(每次必做)

- [ ] Ardot 桌面客户端已启动并打开 polang-ui-spec(fileId 715061534788814)
- [ ] 本地 MCP 可达:`POST http://127.0.0.1:50501/api/v1/mcp`
- [ ] 确认当前无其他会话在操作同一文件

## S1 新建页面/帧

1. 读 `components.json` 选可复用组件,拼装优先;页面帧内只允许组件实例 + 页面特有布局
2. batch_edit 建帧(≤25 ops/批;非当前页带 fileUrl,`:`→`%3A`),颜色一律绑 `PoLang Tokens` 变量
3. 自检:对该页 batch_read(readDepth=-1) + fetch_variables,跑健康检查:

> 落盘方式:MCP 大结果自动写 `$TMPDIR/.ardot/reads/*.jsonl`,响应文本给路径;已入库封装 `scripts/ardot-scan.py`(`--pages`/`--out-dir`/`--vars-out` 可配,含缓存路径正则回退),底层模式同 `scripts/sync-ardot-variables.py` 的 `mcp_connect`。

```bash
python3 scripts/ardot-health-check.py --jsonl tmp/ardot-health/read.jsonl \
  --vars tmp/ardot-health/vars.jsonl \
  --components docs/08-UI-SPECS/screens/refs/ardot/components.json \
  --out-dir tmp/ardot-health
```

4. Light 验证(逐新帧):

```bash
scripts/ardot-preview-mode.sh <frameId> light --shot tmp/ardot-health/<name>-light.png
python3 scripts/ardot-light-verify.py --light-dir tmp/ardot-health \
  --dark-dir docs/08-UI-SPECS/screens/refs/ardot
scripts/ardot-preview-mode.sh <frameId> dark
```

5. health-check 退出码归零 + Light 判据通过后,快照入库(见 S3 第 3 步)

## S2 样式/token 变更

```bash
# 改 design-tokens.json 后:
python3 scripts/gen-design-tokens.py
python3 scripts/sync-ardot-variables.py --push
```

→ 抽 3 个代表帧(顶栏型/面板型/列表型)按 S1 第 4 步做双模导出验证 → health-check 归零 → 快照收口。
反向(画布调色回流):`sync-ardot-variables.py --pull` → gen → `--push` 零漂移收敛。

## S3 健康审计 / 快照入库

1. 全量 batch_read(逐页带 fileUrl, readDepth=-1) + fetch_variables → 跑 health-check

> 健康检查须带 `--ignore-file docs/08-UI-SPECS/screens/refs/ardot/health-ignore.txt`;豁免清单是「待收编/待决策」台账,增量收编时逐行移除,退出码归零才算过。
2. 按 health.json 修:literal→就近绑 token(色距≤24)或新增语义变量;重复组→收编组件;布尔漏→补 sysbar-light 图层;漂移→修 catalog 或修画布
3. 修复后重跑归零,快照收口:

```bash
python3 scripts/export-ardot-snapshot.py
ls docs/08-UI-SPECS/screens/refs/ardot/   # 核对陈旧 PNG → git rm
```

## 新组件入库标准(三过)

1. `ardot-health-check.py` 对该组件子树 literal=0
2. 钉 light 导出 PNG,`ardot-light-verify.py` 判据通过
3. 登记 `components.json` + 双模预览 PNG 入 `refs/ardot/components/`

## 常见陷阱

| 陷阱 | 症状 | 修复 |
|------|------|------|
| 跨页 M 移动孤儿化 | 移动后节点消失 | 用已验证 M 挂载套路;batch_edit ≤25 ops 分批 |
| batch_edit 超时(>110s) | 无响应但可能已应用 | 先 batch_read 核实再重试,勿盲目重发 |
| 非当前页节点 not found | batch_read/edit 报错 | 带 fileUrl,`:` 编码 `%3A` |
| AI 生成残留覆盖层 | 帧显示异常 | 查 `frozen_render_snapshot` 全屏图片层 |
| 实例尺寸改不动 | U 数字静默空转 | 先 U(null) 解绑;U(svg) 对尺寸无效 |
| 快照残留旧帧 PNG | git status 有幽灵文件 | 提交前 ls 核对 + git rm |
| export 冷缓存空白 | 导出 PNG 全白 | 预热重试 |
| Light 误判 | 视觉模型把 Dark 帧报成浅色 | 只用 ardot-light-verify.py 像素采样判据 |
| 并发会话互相覆盖 | 改动被恢复 | 全程单会话串行 |
| I 绑定字符串自带尾分号 | 覆写路径加 ";" 后绑定失配 | 覆写时原样使用,勿追加分号 |
| potentialIssues 静默跳过 | batch_edit 部分 op 未生效无报错 | 响应逐条核对 potentialIssues,中断即逐 op 回读 |
| 实例不可写 variableModes | 对实例钉 Light 报错 | 钉宿主帧或主组件,勿钉实例 |
| 绝对定位宿主帧 I 忽略 x/y | 实例插入后位置错乱(flex 父) | I 显式带 layoutPositioning:ABSOLUTE |
| 隐形损坏子实例 Copy 丢弃 | mainComponent=None 的子实例复制后消失 | Copy 前 batch_read 查 mainComponent,损坏者先修 |
| 复扫漏页致 dup 假消失 | health-check JSONL 未含全 11 页 | 11 页全量重读,缺一页结论无效 |
| 变量删除/重命名无 API | 已迁移变量旧名残留画布,--check 永久 NEW | 登记 sync-ignore.txt 豁免;勿用 apply_variables replace=true(会误删 sysbar-* 等机制变量) |
| 画布手建色值精度渣 | UI 取色存 0.0590003 式 float,--check 假值漂移(hex 相同) | pull 回流后再 push 一次归一;像素影响 ≤1 LSB,视觉零变化 |
| 双模式色勿入 color/ 组 | pull 按单值域取 Dark 弃 Light,Light 值丢失 | 主题相关色入 colorScheme(scheme/* 双 mode),或迁移:JSON 加 colorScheme 键→push→重绑→旧名豁免 |
| 模式钉定的三层效力(2026-09-21 实测定案) | 帧钉 Light 后实例仍渲染 Dark | ①帧级钉定:管直接节点,**不管实例**(实例内变量按文件模式解析);②**组件级钉定:驱动该组件全部实例**(input_bar 钉 Light→实例亮度 238)——烘焙商店素材正解:窗口期钉组件 [scheme Light + 语言],导完还原 Dark;③文件模式:管一切非钉定内容。另:variableModes 写入成功后回读恒为 null/None(不序列化),验证只能靠渲染像素,勿用回读判断"被回退" |
| 客户端占用期间写入被静默回退(已大幅修正) | batch_edit/upload_images 返回 success 但回读为旧值/None | **多数"回退"是回读假象**:隐藏层的 fills、variableModes、实例子节点 visible 回读均不序列化/不可靠,fill/钉定看似丢失实际健在(2026-09-21 多轮渲染实证);真伪一律以渲染像素判;确需回读验证时先 U visible=true 再读。真回退存在但罕见(并发会话/undo 链),表现为渲染也不变,此时停手让位 |
| 隐藏层上传图片姿势 | 对 visible=false 的层 upload_images 后回读 fills=None | 先 `U(layer,{visible:true})` → upload → 回读确认 → 需要 hidden 默认时再 U visible=false(fills 实际保留,回读看不见而已);渲染为准 |
| visible 绑布尔语法 | boundVariables 对象/`$:Set:Name` 均被静默忽略 | U 用 `{visible: "$<varId>"}`(如 `$386:38`);回读 boundVariables 确认 |
| 绑 false 值布尔不生效 | 绑当前模式解析为 false 的变量(如 Dark 下绑 sysbar-light)后回读无绑定 | 引擎疑似 bug,无绕过;bar_light 侧绑定需在 Light 默认模式下做或等引擎修复 |
| export-ardot-snapshot 漏页 | 快照只导 10/11 页(缺 PlayStoreAssets),海报 PNG 不更新 | 海报/guide 帧须单独 export_nodes(scale 1)按帧名落 refs |
| 海报烘焙图更新通道 | 设计海报内嵌截图是静态 IMAGE fill,不随组件更新 | download_source_media→本地 PIL 修补→upload_images 回传同节点(≤10/批) |
| D 删除 op 裸写不解析 | `D 387:6` 报 "Skipped unparseable line",静默跳过 | 用函数式 `D("387:6")`(与 U/M 同构);响应逐条核对 potentialIssues |
| capture_screenshot 参数形 | nodeId 单串报 invalid_type | 须 `nodeIds:[..]` 数组 + `screenShotDir`,产物落 `<dir>/<fileId>/screenshot-*.png` |
| 快照吸收在途画布变更 | 重跑快照后 git status 大量 refs PNG 变更,远超本任务改动 | 上次快照后画布可能已被其他会话/手工改过(虚拟键/语言模式钉定/删层);提交前对全部变更 PNG 做像素 diff 分类(编码噪声 vs 真实变更),真实变更逐帧归因后再报告,勿静默混入本任务提交 |
| input_bar SendButton 渐变钮渲染丢失 | 全画布所有 input_bar 实例右侧绿钮不渲染(正典 Chat 页同样丢) | 根因=**渐变 fill + VARIABLE_ALIAS stop 绑定引擎不绘制**(纯色绑定正常);已修:SendButton 改绑 `scheme/primary` 纯色($2:130,双模自适应);此后渐变+变量 stop 慎用,上线前先单帧截图验证 |
| sysbar 浅色铬件路线 | Light 内容帧(Play 商店图/guide)铬件仍深 | bar_dark=烘焙深色图、bar_light 已填反色浅色图(组件默认仍深);浅色帧逐实例覆写:`U("inst;386:40",{visible:true})`+`U("inst;386:39",{visible:false})`(状态栏 39/40、导航 41/42);模式感知待引擎修 visible 布尔绑定(sysbar-light 绑定再证不挂) |
| sysbar 组件预览导出 | light 预览切不出(组件层可见性是字面量,钉模式无效) | 临时翻转组件层(4×U)→export_nodes→原样翻回;翻回批的响应可能被截断,必须 batch_read 复核 39/41=None+40/42=False |
| 拆页(页面一拆多) | 拆完 health-check 翻红(豁免路径失配)/扫描漏新页 | 路由:`create_new_page` 逐页 → 跨页 `M(nodeId, 新页id)`(fileUrl 挂**源页**,≤25/批,先单帧试点+回读验树——2026-09-21 实测 47 帧零孤儿);三件同步:`health-ignore.txt` 豁免路径、`ardot-scan.py` PAGES、快照脚本自动纳新页(editor state) |
| capture_screenshot 批量后残影 | 编辑后立即批量截图出现跨帧内容渗入/元素缺失,重截 MD5 相同 | 渲染层缓存滞后,「MD5 稳定」≠真实画布;结构真伪以 batch_read + capture_layout(problemsOnly) 为准,视觉终验走 export_nodes/快照导出 |
| 实例子级浅色字面覆写 | Light 钉定帧内实例随文件模式变暗 | `U("inst;childId",{fills/strokes:[SOLID 无 a 键]})`;嵌套图标走深路径 `inst;子实例id;矢量id`(选中页签为固定薄荷绿 $386:46 免覆写;胶囊图标灰 $79:104 双模同值免覆写);实例根用裸 id;商店页字面量已豁免(固定样式不参与双模) |
| 烘焙钉定周期后实例覆写丢失 | 被钉定/还原过的帧,其 sysbar 实例 visible 覆写会随机丢失(2026-09-21 两度实证:search/chat×3 渲染回深条) | 凡对帧做过 variableModes 钉定/还原,事后必须渲染复验该帧全部实例覆写,丢则重铺;refs 采样 top/bottom 像素为终判 |
| 烘焙图自带铬件错位 | 源截图自身有双行状态栏/非标底区,浮层实例按标准比例盖不住(2026-09-21 guide 实证) | 勿在含铬件的烘焙图上叠浮层条;走烘焙通道:组件条图按源图原生倍率(如 3x)均匀缩放 PIL 合成进源图,补底用逐图采样底色保无缝,回传后删浮层 |
| 演示图层(Slide)管线 | 客户端切"演示图层"为空;batch_edit 写不进 Slide 标记(实证 no slide found) | Slide=独立节点类型,唯一 MCP 创建通道:`register_assets(HTML)→html_to_ardot(isSlide:true, pageId)`;slides 同页排布(勿一页一 slide);PPTX 导出按图层顺序合成;M 可重排 slide 顺序;素材图需 register 成绝对 URL 写进 HTML |
| 批量 export_nodes 帧映射错乱 | 一次导多帧时产物张冠李戴(2026-09-21:feature×3 全落成 zh-CN 内容) | 关键产物逐帧单导(nodeIds 只放一个);烘焙窗口钉过组件语言后,还原必须**显式带语言集**(variableModes 合并写入,scheme-only 还原会残留语言钉→正典实例文本变繁体) |
| 连事实例必须 I(type:"ref") | C 复制组件得到的是又一份可复用节点,不是实例 | `x=I(parent, {type:"ref", ref:"<组件id>", name/width/height/padding 内联覆写})`;M(nodeId,parent,index) 重排(M 不收 binding,须等 I 响应回传真实 id 再下一批);同批尾随 `D("旧手绘id")` |
| 全画布铬件/主题六类病灶与一键审计(2026-09-22) | 状态栏/虚拟键与内容 Light/Dark 不匹配、悬浮导航/输入框不跟随 | **`scripts/ardot-chrome-audit.py` 双指标审计**(铬件带 y0-33/y801-852 + 内容主题带 y38-70 + 悬浮导航带 + 输入卡带;`--fix` 自动修);照片 hero 页内容带 MID/LIGHT 为假阳性,目检豁免;实例名不统一按 mainComponent 扫 |
| 帧内容主题漂移 | 未钉主题的帧内容整体渲成 Light(文件级默认会漂)而铬件被实例钉强制 Dark=全帧错 | 应用帧显式钉 Dark:`U(fid,{variableModes:[{variableSetId:"2:2",modeId:"2:0"}]})`;Light 专用帧登记 audit LIGHT_FRAMES;**PlayStoreScreens 全页 Dark 终局**(localmodels 三帧 Light 方案废弃——bar_dark 隐藏在用户视角=「没有状态栏和虚拟键」) |
| D() 可删变量 | apply_variables 无删除能力,孤儿变量只能豁免 | `batch_edit D("<变量id>")` 按 id 删(UI Language 7 个+color/warmGrayIcon 已实证);删后 sync-ignore 行同步摘除 |
| 陈旧桌面客户端覆写 | MCP 写入验证绿→隔段时间 audit 又 BAD,坏帧=旧病集合 | 查 `ps` 里 ArDot.app 启动时间(长挂客户端持陈旧态回推),让用户重启;修后 90/180/360s 三连测稳定性 |
| 导出器管道僵死 | nohup 快照导出永挂不退出 | 管道 reader(如 `| tail`)死后 python print 阻塞;长跑导出必须 `-u` 直写文件,包装进程杀 python 会成孤儿续跑 |
| 缺铬件帧补齐 | 应用帧无 status_bar/system_nav_bar | I() 不支持实例类型(REF/INSTANCE 均拒);**C(健康实例,目标页)+M(帧,0)** 跨页拷贝可靠(Organize×9+Memories×2+People+22 处实证) |
| 图片资源过期渲染空白 | 结构 hash 完好但渲染纯色空白(只有渲染能暴露) | 回填源=Components 页 cast/* 五头像(download_source_media 一 node 一调用);**实例内部图走分号子路径 "inst;387:147"**;跨节点 hash 不复用,同图多节点逐节点独立 upload;upload filePath 须绝对路径(MCP 按自身 cwd 解析) |
| 底按钮被虚拟键遮挡 | btn/bottom_bar 下缘切进 y801-852 | 底容器(body/sheet/bottom_bar)padding.bottom 补足 51+~24 间隙(28→91/24→75 类);绿像素遮挡扫描只测绿钮且营销帧绿背景全误报——393×852 应用帧限定 |
| PSA 三语重发全流程 | 设计稿修复后商店/官网图需全量跟发 | 语言钉源帧→export 2x→upload 灌海报 Screen 节点→export 海报→Play 逐语言 prune 重传(回读 sha)→官网 shots+setup 刷→?v bump→双站选择性 rsync;guide 卡=EN/ZH 双轮烘焙后画布终态回 zh;zh-TW 02-07 海报 Screen 保留 09-18 繁体覆写成果(重烘会丢 hant),仅 01(zhTW 物理帧)/08(localmodels-tw)可安全重烘 |
| 全量快照商店帧渲染漂移(2026-09-25) | export-ardot-snapshot 后 70 张商店/海报 PNG 内容区由 #111 漂成 #D5D5D5(亮度 41→115),但源帧逐帧单导正常暗色 | 疑陈旧客户端(6h+)×导出管线钉定残留;处置=坏导出全部 git checkout 回滚勿混入任务提交,源帧单导自证正常,重启客户端后再择时全量快照 |
| preview-mode.sh --shot 搬移路径失配 | 脚本按 `<dir>/screenshot-*.png` 搬文件,实际产物落 `<dir>/<fileId>/` 子目录,报 FileNotFoundError | 钉定本身已生效(像素自证);截图自己 capture_screenshot 代劳,勿重试脚本(重试会二次钉定) |
| health-check 多页扫描漏页与合并 | 只扫单页时 catalog 全组件报 missing(假 drift);两页 jsonl 直接 cat 合并则 _meta 双行 KeyError | catalog 校验必须含 Components 页;合并时滤掉各文件 _meta 行,重写单行 _meta |
| 营销帧批量面色重绑后文字全隐形(2026-09-25) | 24 海报标题/副标题+brand_row 组件字被重绑到与背景同 token(scheme/background)→黑底黑字,且 health-check literal=0 照样绿——**对比度错误不在健康检查射程内** | 批量 rebind 后必须逐帧导出+文字带白像素判据复验(全帧行级亮度剖面);营销帧配色对:scheme/primary 底+scheme/onPrimary(#FFF 双模恒值)字;04-people/05-person-groups 帧内文案互换系历史既定(v2.2.2 起线上如此),勿当 bug"修" |
| Play REST v3 图片端点形态(2026-09-25) | images 增删查 URL 误带 /images 段或 type query → 400 Cannot bind / Invalid value | 真身=.../listings/{lang}/{imageType}(camelCase: phoneScreenshots/featureGraphic,无 /images 段);edit 收尾=.../edits/{id}**:commit**(冒号非斜杠);入库脚本 scripts/play-images-upload.py(SA JWT openssl 签+SOCKS+prune 重传+sha 回读) |

## 相关文件

- [设计 spec](docs/superpowers/specs/2026-09-19-ardot-design-ops-design.md) - 本 Skill 的设计 SSOT
- [组件目录](docs/08-UI-SPECS/screens/refs/ardot/components.json) - 组件 SSOT
- [DESIGN_TOKENS_SPEC](docs/03-TECHNICAL-SPECS/DESIGN_TOKENS_SPEC.md) - token codegen 规范
- [ARDOT_MCP](.kimi-code/ARDOT_MCP.md) - MCP 工具速查与坑
- [ui-parity-guard](skills/ui-parity-guard/SKILL.md) - 双端一致性守卫(代码侧)
- [豁免清单](docs/08-UI-SPECS/screens/refs/ardot/health-ignore.txt) - 待收编/待决策台账
- [sync 豁免清单](docs/08-UI-SPECS/screens/refs/ardot/sync-ignore.txt) - 画布独有变量(token 同步侧)

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0.0 | 2026-09-19 | 初始版本(三场景 + 入库标准 + 陷阱表) |
| 1.1.0 | 2026-09-20 | Task 4 实战坑回填(陷阱表 +6 行/落盘方式/ignore-file 门禁/豁免清单索引) |
| 1.1.1 | 2026-09-20 | 落盘封装入库:`tmp/ardot-health/baseline_scan.py` 提升为 `scripts/ardot-scan.py`(CLI 可配),S1 引用改指入库脚本 |
| 1.1.2 | 2026-09-20 | token 漂移收敛实战回填:sync-ignore 豁免机制(陷阱表 +3 行/相关文件 +1) |
| 1.2.0 | 2026-09-20 | 虚拟键统一实战回填:陷阱表 +5 行(钉 Light 不穿透实例/visible 绑布尔语法/false 布尔绑定失效/快照漏 PlayStoreAssets 页/烘焙图更新通道) |
| 1.2.1 | 2026-09-20 | input_bar 移除 VoiceButton 实战回填:陷阱表 +2 行(D 删除 op 函数式语法/capture_screenshot 参数形) |
| 1.2.2 | 2026-09-20 | memory/detail hero 修复实战回填:陷阱表 +1 行(快照吸收在途画布变更须像素分类归因) |
| 1.2.3 | 2026-09-21 | PSA store01 输入框组件化实战回填:陷阱表 +3 行(SendButton 渐变钮渲染丢失/截图残影以 batch_read+layout 为准/连事实例 I(type:ref)+M 重排+D 手法) |
| 1.2.4 | 2026-09-21 | 商店图铬件统一浅色实战回填:SendButton 行改根因+已修;新增 sysbar 浅色路线/组件预览导出两行 |
| 1.2.5 | 2026-09-21 | PSA 拆三页实战回填:陷阱表 +1 行(拆页路由+豁免/扫描/快照三同步) |
| 1.2.6 | 2026-09-21 | 并发回退实战回填:证伪「钉定不穿透实例」+新增「客户端占用写入回退」(写后必回读/停手让位) |
| 1.2.7 | 2026-09-21 | 海报烘焙实战回填:模式钉定三层效力定案(帧钉不管实例/组件钉驱动实例/variableModes 回读不序列化)+烘焙窗口组件钉定手法 |
| 1.2.8 | 2026-09-21 | Screens 页稳定浅色实战回填:实例子级浅色字面覆写路线(含嵌套图标 strokes 深路径/选中页签固定薄荷绿免覆写);「客户端回退」行修正为回读假象为主+隐藏层上传姿势 |
| 1.2.9 | 2026-09-21 | 快照收口实战回填:批量 export_nodes 映射错乱逐帧单导+烘焙窗口语言钉残留(还原须显式带语言集) |
| 1.3.0 | 2026-09-21 | guide 铬件比例实战回填:烘焙图自带错位铬件勿叠浮层,PIL 按源图原生倍率合成+逐图采样底色 |
| 1.4.0 | 2026-09-22 | 铬件/主题大修实战回填:六类病灶+audit 双指标工具+九行配方(主题钉/D()删变量/陈旧客户端/管道僵死/C 补铬件/图片过期回填/按钮遮挡/PSA 重发流程) |
| 1.5.0 | 2026-09-25 | 任务卡组件/任务中心页实战回填:快照商店帧渲染漂移处置+preview-mode.sh --shot 路径失配+health-check 多页漏扫/合并两坑(陷阱表 +3 行) |
| 1.6.0 | 2026-09-25 | 海报隐形文字事故修复实战回填:批量面色重绑对比度盲区(health-check 不设防,须行级亮度判据)+Play REST v3 图片端点形态/入库直传脚本(陷阱表 +2 行) |
