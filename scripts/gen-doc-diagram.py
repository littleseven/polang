#!/usr/bin/env python3
"""gen-doc-diagram.py — 文档插图生成器（清爽中文技术信息图风格·正典）

用法:
  python3 scripts/gen-doc-diagram.py scripts/diagrams/<name>.json            # 仅生成 HTML
  python3 scripts/gen-doc-diagram.py scripts/diagrams/<name>.json --png      # HTML + Chrome 2x PNG

spec 格式（kind 三种布局）:
  flow   — 纵向流程: steps[] 每步 {t 标题, s 副标, chips[], note}
  layers — 分层带:   bands[] 每带 {t, s, chips[], split{ad[],ios[]}, note}
  hflow  — 横向链:   nodes[] 每个 {t, s}（或字符串），左→右

输出: docs/assets/diagrams/<name>.html / .png
风格 SSOT: docs/02-ARCHITECTURE/polang-architecture-infographic.html（换内容不换骨架）
"""
import json
import os
import subprocess
import sys
import html as H

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "docs/assets/diagrams")
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

CSS = """
  :root{color-scheme:light;--grid:#E8ECF1;--card:#fff;--tint:#EEF3FB;--tint2:#F0F3F8;
    --navy:#0D2240;--blue:#2563EB;--blue-deep:#3B6FD4;--chip-bg:#DCE9FB;--green:#2FA05A;
    --green-bg:#E4F5EA;--orange:#D97E2E;--orange-bg:#FDEBD9;--rose:#D9482B;--rose-bg:#FCEAE5;
    --txt:#12263F;--sub:#5A6B80;--line:#D8E0EC}
  *{margin:0;padding:0;box-sizing:border-box}
  html{background:#E9EEF5}
  body{font-family:"Noto Sans SC","PingFang SC",sans-serif;color:var(--txt);
    padding:22px 14px 26px;background:#E9EEF5;-webkit-font-smoothing:antialiased}
  .wrap{max-width:1040px;margin:0 auto;background-color:#F7F9FC;
    background-image:linear-gradient(var(--grid) 1px,transparent 1px),
      linear-gradient(90deg,var(--grid) 1px,transparent 1px);
    background-size:26px 26px;border:1.5px solid #C9D4E4;border-radius:20px;
    padding:22px 22px 20px;box-shadow:0 10px 26px rgba(18,38,63,.10)}
  .mono{font-family:"JetBrains Mono",monospace}
  .hero{text-align:center;margin-bottom:14px}
  .hero h1{font-size:26px;font-weight:900;letter-spacing:.02em}
  .hero h1 .dot{color:var(--blue)}
  .banner{display:inline-flex;align-items:center;gap:10px;background:var(--navy);color:#fff;
    border-radius:999px;padding:6px 16px 6px 12px;margin-top:10px;box-shadow:0 5px 14px rgba(13,34,64,.22)}
  .banner .bn-t{font-size:13.5px;font-weight:700;letter-spacing:.05em}
  .pill{font-size:11px;font-weight:700;color:#fff;background:#34C759;border-radius:999px;padding:2px 10px}
  .pill.blue{background:var(--blue)} .pill.orange{background:var(--orange)}
  .step{position:relative;background:var(--card);border:1px solid var(--line);border-radius:14px;
    padding:12px 15px 12px 48px;box-shadow:0 3px 10px rgba(18,38,63,.05);text-align:left}
  .step.tint{background:var(--tint)} .step.tint2{background:var(--tint2)}
  .step .num{position:absolute;left:13px;top:12px;width:26px;height:26px;border-radius:50%;
    background:var(--blue);color:#fff;font-weight:900;font-size:13px;display:grid;place-items:center;
    box-shadow:0 2px 6px rgba(37,99,235,.35)}
  .step .sh{display:flex;align-items:baseline;gap:9px;flex-wrap:wrap;margin-bottom:7px}
  .step .st{font-size:16.5px;font-weight:900}
  .step .ss{font-size:11.5px;color:var(--blue-deep);font-weight:500}
  .chips{display:flex;flex-wrap:wrap;gap:7px}
  .chip{display:inline-flex;align-items:center;gap:5px;font-size:12.5px;font-weight:500;
    color:var(--blue-deep);background:var(--chip-bg);border:1.5px solid rgba(37,99,235,.35);
    border-radius:999px;padding:4px 11px;white-space:nowrap}
  .chip.green{background:var(--green-bg);border-color:#7BC894;color:var(--green)}
  .chip.orange{background:var(--orange-bg);border-color:#E8B27C;color:var(--orange)}
  .chip.rose{background:var(--rose-bg);border-color:rgba(217,72,43,.45);color:var(--rose);border-style:dashed}
  .chip.ghost{background:transparent;border-style:dashed;border-color:var(--line);color:var(--sub)}
  .chip.hero{background:var(--navy);color:#fff;border-color:var(--navy);font-weight:700;
    box-shadow:0 2px 8px rgba(13,34,64,.28)}
  .chip small{font-size:10.5px;opacity:.75;font-weight:400}
  .varrow{display:grid;place-items:center;padding:3px 0}
  .note{margin-top:8px;font-size:11.5px;color:var(--sub);display:flex;flex-wrap:wrap;gap:5px 12px}
  .foot{margin-top:14px;background:var(--navy);color:#fff;border-radius:12px;padding:11px 16px;
    text-align:center;box-shadow:0 6px 16px rgba(13,34,64,.25)}
  .foot b{color:#7EE2A8} .foot .ft{font-size:13px;line-height:1.65;font-weight:500}
  /* hflow */
  .hrow{display:flex;align-items:stretch;gap:0;flex-wrap:wrap;justify-content:center}
  .hnode{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:11px 14px;
    box-shadow:0 3px 10px rgba(18,38,63,.05);min-width:150px;max-width:230px;text-align:center;
    display:flex;flex-direction:column;justify-content:center;gap:4px}
  .hnode .ht{font-size:14px;font-weight:900}
  .hnode .hs{font-size:11px;color:var(--sub);line-height:1.5}
  .hnode.hero{background:var(--navy);border-color:var(--navy)} .hnode.hero .ht{color:#fff}
  .hnode.hero .hs{color:#9FC3F8}
  .hnode.tint{background:var(--tint)}
  .harrow{display:grid;place-items:center;padding:0 7px;color:var(--blue)}
  /* layers split */
  .split{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-bottom:8px}
  .half{border:1px dashed var(--line);border-radius:11px;padding:8px 10px;background:rgba(255,255,255,.6)}
  .ph{display:flex;align-items:center;gap:7px;margin-bottom:6px;flex-wrap:wrap}
  .pn{font-size:13px;font-weight:900}
  .pt{font-size:10px;color:#fff;border-radius:999px;padding:2px 8px;font-weight:700}
  .pt.ad{background:var(--green)} .pt.ios{background:var(--navy)} .pt.srv{background:var(--orange)}
"""

ARROW = '<div class="varrow"><svg width="22" height="24" viewBox="0 0 26 26"><line x1="13" y1="0" x2="13" y2="17" stroke="#2563EB" stroke-width="3"/><path d="M5 16 L13 24 L21 16 Z" fill="#2563EB"/></svg></div>'
HARROW = ('<div class="harrow"><svg width="30" height="16" viewBox="0 0 34 16">'
          '<line x1="2" y1="8" x2="26" y2="8" stroke="#2563EB" stroke-width="2.4"/>'
          '<path d="M24 3 L32 8 L24 13 Z" fill="#2563EB"/></svg></div>')
PROBE = ('<script>window.addEventListener("load",function(){setTimeout(function(){'
         'document.title="H"+document.documentElement.scrollHeight},500)})</script>')


def chip(c):
    if isinstance(c, str):
        cls, txt = "", c
    else:
        cls, txt = c.get("c", ""), c["t"]
    small = ""
    if "|" in txt:
        txt, sm = txt.split("|", 1)
        small = f"<small>{H.escape(sm.strip())}</small>"
    return f'<span class="chip {cls}">{H.escape(txt.strip())} {small}</span>'.replace("  ", " ")


def render_flow(d):
    parts = []
    for i, s in enumerate(d["steps"]):
        tint = " tint" if i % 3 == 1 else (" tint2" if i % 3 == 2 else "")
        chips = "".join(chip(c) for c in s.get("chips", []))
        ss = f'<span class="ss">{H.escape(s["s"])}</span>' if s.get("s") else ""
        note = f'<div class="note">{s["note"]}</div>' if s.get("note") else ""
        parts.append(
            f'<div class="step{tint}"><span class="num">{i+1}</span>'
            f'<div class="sh"><span class="st">{H.escape(s["t"])}</span>{ss}</div>'
            f'<div class="chips">{chips}</div>{note}</div>')
        if i < len(d["steps"]) - 1:
            parts.append(ARROW)
    return "".join(parts)


def render_hflow(d):
    nodes = []
    for i, n in enumerate(d["nodes"]):
        if isinstance(n, str):
            n = {"t": n}
        cls = n.get("c", "")
        hs = f'<div class="hs">{H.escape(n["s"])}</div>' if n.get("s") else ""
        nodes.append(f'<div class="hnode {cls}"><div class="ht">{H.escape(n["t"])}</div>{hs}</div>')
        if i < len(d["nodes"]) - 1:
            nodes.append(HARROW)
    return f'<div class="hrow">{"".join(nodes)}</div>'


def render_layers(d):
    parts = []
    for i, b in enumerate(d["bands"]):
        tint = " tint" if i % 2 == 0 else " tint2"
        chips = "".join(chip(c) for c in b.get("chips", []))
        ss = f'<span class="ss">{H.escape(b["s"])}</span>' if b.get("s") else ""
        split = ""
        if b.get("split"):
            halves = []
            for key, label, pt in (("ad", "Android", "ad"), ("ios", "iOS", "ios"), ("srv", "Server", "srv")):
                if b["split"].get(key):
                    cs = "".join(chip(c) for c in b["split"][key])
                    halves.append(f'<div class="half"><div class="ph"><span class="pn">{label}</span>'
                                  f'<span class="pt {pt}">{b["split"].get(key+"_t","")}</span></div>'
                                  f'<div class="chips">{cs}</div></div>')
            split = f'<div class="split">{"".join(halves)}</div>'
        note = f'<div class="note">{b["note"]}</div>' if b.get("note") else ""
        parts.append(
            f'<div class="step{tint}"><span class="num">{i+1}</span>'
            f'<div class="sh"><span class="st">{H.escape(b["t"])}</span>{ss}</div>{split}'
            f'<div class="chips">{chips}</div>{note}</div>')
        if i < len(d["bands"]) - 1 and not b.get("noarrow"):
            parts.append(ARROW)
    return "".join(parts)


RENDER = {"flow": render_flow, "hflow": render_hflow, "layers": render_layers}


def build(spec):
    body = RENDER[spec["kind"]](spec)
    pill = spec.get("pill", "PoLang")
    pill_cls = "pill" + (" " + spec.get("pill_c", "") if spec.get("pill_c") else "")
    banner = f'<span class="bn-t">{H.escape(spec.get("banner", spec["title"]))}</span>'
    foot = ""
    if spec.get("foot"):
        foot = f'<div class="foot"><div class="ft">{spec["foot"]}</div></div>'
    return f"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{H.escape(spec['title'])}</title>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Noto+Sans+SC:wght@400;500;700;900&family=JetBrains+Mono:wght@400;500&display=swap">
<style>{CSS}</style>
</head>
<body>
<div class="wrap">
  <div class="hero">
    <h1>{H.escape(spec['title'])}<span class="dot">.</span></h1>
    <div class="banner">{banner}<span class="{pill_cls}">{H.escape(pill)}</span></div>
  </div>
  {body}
  {foot}
</div>
{PROBE}
</body>
</html>
"""


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    spec_path = sys.argv[1]
    spec = json.load(open(spec_path, encoding="utf-8"))
    name = spec.get("name") or os.path.splitext(os.path.basename(spec_path))[0]
    os.makedirs(OUT_DIR, exist_ok=True)
    html_path = os.path.join(OUT_DIR, name + ".html")
    with open(html_path, "w", encoding="utf-8") as f:
        f.write(build(spec))
    print(f"HTML: docs/assets/diagrams/{name}.html")
    if "--png" not in sys.argv:
        return
    # 高度探测 → 精确窗口截图 @2x
    def chrome(args):
        # timeout 兜底：Chrome 拉取 Google Fonts 偶发卡死虚拟时钟，25s 上限后降级
        try:
            return subprocess.run([CHROME] + args, capture_output=True, text=True, timeout=25).stdout
        except subprocess.TimeoutExpired:
            return ""
    dom = chrome(["--headless=new", "--disable-gpu",
                  "--virtual-time-budget=9000", "--dump-dom", "file://" + html_path])
    import re
    m = re.search(r"<title>H(\d+)", dom)
    height = int(m.group(1)) + 20 if m else 2400
    png_path = os.path.join(OUT_DIR, name + ".png")
    chrome(["--headless=new", "--disable-gpu", "--hide-scrollbars",
            "--force-device-scale-factor=2", "--virtual-time-budget=9000",
            f"--window-size=1100,{height}", "--screenshot=" + png_path,
            "file://" + html_path])
    print(f"PNG : docs/assets/diagrams/{name}.png (window 1100x{height} @2x)")


if __name__ == "__main__":
    main()
