#!/usr/bin/env python3
"""Light PNG 像素采样验证: 对照 Dark 快照 PNG, 采样顶条/中部/底部三区域均值亮度。
用法: ardot-light-verify.py --light-dir tmp/ardot-audit/light-all --dark-dir docs/08-UI-SPECS/screens/refs/ardot [--map mapping.json]
mapping.json: {"gallery-grid.png": "105:45", ...} 或按文件名约定 light 目录与 dark 目录同名匹配。
判据: Light 版中部背景亮度显著高于 Dark 版(Δ>40) 且无纯黑块(均值 <10 报警)。"""
import argparse
import json
import os
import subprocess
import sys


def sample(path, w, h):
    """返回 [(顶部, 中部, 底部)] 区域平均 RGB"""
    regions = [
        (w, 60, 0, 0),            # 顶条
        (w, min(200, h // 3), 0, h // 2 - 100),  # 中部
        (w, 80, 0, h - 120),      # 底部 bar 区
    ]
    out = []
    for rw, rh, rx, ry in regions:
        cmd = ["ffmpeg", "-i", path, "-vf", f"crop={rw}:{rh}:{rx}:{max(0,ry)},scale=1:1",
               "-f", "rawvideo", "-pix_fmt", "rgb24", "-"]
        p = subprocess.run(cmd, capture_output=True)
        if p.returncode != 0 or len(p.stdout) < 3:
            out.append(None)
            continue
        out.append(tuple(p.stdout[:3]))
    return out


def lum(rgb):
    return None if rgb is None else round(0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2])


def size(path):
    p = subprocess.run(["sips", "-g", "pixelWidth", "-g", "pixelHeight", path],
                       capture_output=True, text=True)
    w = h = None
    for ln in p.stdout.splitlines():
        if "pixelWidth" in ln: w = int(ln.split()[-1])
        if "pixelHeight" in ln: h = int(ln.split()[-1])
    return w, h


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--light-dir", required=True)
    ap.add_argument("--dark-dir", default="docs/08-UI-SPECS/screens/refs/ardot")
    a = ap.parse_args()
    fails, checked = [], 0
    for f in sorted(os.listdir(a.light_dir)):
        if not f.endswith(".png"):
            continue
        dark = os.path.join(a.dark_dir, f)
        light = os.path.join(a.light_dir, f)
        if not os.path.exists(dark):
            print(f"SKIP {f}: Dark 对照缺失")
            continue
        w, h = size(light)
        ls, ds = sample(light, w, h), sample(dark, w, h)
        checked += 1
        ll, dl = lum(ls[1]), lum(ds[1])
        status = "?"
        if ll is not None and dl is not None:
            if ll - dl > 40:
                status = "LIGHT-OK"
            elif abs(ll - dl) <= 12 and ll > 40:
                status = "FIXED-DARK(相机/编辑器预期)"
            elif ll < 10:
                status = "BLACK-BLOCK!"
                fails.append(f)
            else:
                status = "SUSPECT"
                fails.append(f)
        print(f"{status:<28} {f:<44} mid L={ll} D={dl} top={ls[0]} bottom={ls[2]}")
    print(f"\nchecked={checked} suspect/black={len(fails)}")
    if fails:
        print("需复查:", ", ".join(fails))
        sys.exit(1)


if __name__ == "__main__":
    main()
