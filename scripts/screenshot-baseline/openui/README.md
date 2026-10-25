# OpenUI 卡片渲染截图基线

- 建立时间：2026-10-25（卡内设计语言第一刀，规范 `docs/08-UI-SPECS/OPENUI_CARD_DESIGN_LANGUAGE.md` 落地后首轮真机验收）
- 设备：Xiaomi M2002J9E（vangogh，1080x2400），`adb -s 92a0cfb8`（与 chat/ 基线的
  Redmi 24129PN74C 1200x2670 是**两台不同设备**，`--ignore` 区域不可互用）
- 内容（同一张「相册年报卡」的两个滚动位）：
  - `openui_stat_tile.png` — 卡头（OpenUI 角标 + Icon + HEADLINE）+ stat tile 三宫格（DISPLAY 32sp 大数字 + CAPTION 弱色标签）+ 柱状图（柱道 + 圆角柱端）
  - `openui_charts_donut.png` — 柱状图全量 + donut 环形饼图（中心总值，环外缘内缩半描边不越界）+ 图例 + Callout

## 复现与回归流程

卡 program 持久化在 `chat_messages.partsJson`，**重开聊天即从同一 program 重渲染**——
同设备同数据下截图可复现（数据经 Query 重取，媒体库不变则数值一致）。

1. 真机装被测构建，启动 App → 聊天页 → 上滑定位「相册年报卡」（2026-10-25 生成，
   卡头标题「我的相册 · 2026」；若会话被清，重发 prompt：「给我一份相册年报卡：顶部
   三个统计大数字，按月照片分布柱状图，人像/风景/其他占比饼图，配图标点缀，杂志风排版」
   ——LLM 生成物非确定，只能保证构图模式一致，数值随媒体库变化，此时应重建基线而非比对）
2. 同滚动位截图：`adb -s 92a0cfb8 exec-out screencap -p > current.png`
3. 比对：`/usr/bin/python3 scripts/screenshot-diff.py --baseline scripts/screenshot-baseline/openui/<name>.png --current current.png --ignore 0,0,1080,204`（忽略状态栏+顶栏，时间/电量噪声；PIL+numpy 在系统 python3，PATH 上的 harbor venv 没有）
4. 渲染器皮肤（tokens/图表几何/字阶）任何改动后必须重跑比对；相似度低于阈值先人工
   看图判断是预期演进（更新基线）还是回归（修代码），基线更新需在提交信息里说明。

> 注意：截图会进 MediaStore 被相册搜索命中（测试 artifact，正常）；`--ignore` 区域
> 按 1080x2400 分辨率标定，换设备需重标。
