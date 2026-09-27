# Chat 页渲染截图基线

- 建立时间：2026-09-27（ADR-016 M3，AgentMarkdown / mikepenz 0.41.0 替换 jeziellago compose-markdown 后首轮真机冒烟）
- 设备：Redmi 24129PN74C（1200x2670）
- 内容：
  - `agent_text_streaming.png` — 流式中段：降档标题 / 粗体 / 行内代码底纹 / 列表
  - `agent_text_body.png` — 终态正文：粗体 + 行内代码 + 标题 + 列表
  - `agent_code_collapsed.png` — 代码块折叠态（>12 行「展开（共 N 行）」+ 复制）
- 对比：`python3 scripts/screenshot-diff.py --baseline scripts/screenshot-baseline/chat/<name>.png --current <new>.png`（需 PIL+numpy 环境）
