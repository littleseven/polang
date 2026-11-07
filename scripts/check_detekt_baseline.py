#!/usr/bin/env python3
"""detekt baseline 只减不增门禁（架构健康审计 P1：冻结新增、按规则类型分批消化）。

用法:
    python3 scripts/check_detekt_baseline.py <base-ref>

比较工作区 androidApp/detekt-baseline.xml 与 <base-ref>（如 origin/main）中的版本：
- 新增豁免条目（base 中不存在）→ FAIL：新代码不得靠 baseline 豁免绕过 detekt，
  要么修复问题，要么在代码上用 @Suppress 并说明理由（可见、可审）。
- 删除条目（消化存量）→ 鼓励，打印消化数量。

退出码: 0 = 通过（无新增）；1 = 有新增豁免；2 = 用法/环境错误。
"""

import re
import subprocess
import sys

BASELINE_PATH = "androidApp/detekt-baseline.xml"
ID_PATTERN = re.compile(r"<ID>(.*?)</ID>")


def load_ids_from_worktree() -> set[str]:
    try:
        with open(BASELINE_PATH, encoding="utf-8") as f:
            return set(ID_PATTERN.findall(f.read()))
    except FileNotFoundError:
        # baseline 被整体删除 = 全部消化，零豁免，天然通过
        return set()


def load_ids_from_ref(ref: str) -> set[str]:
    result = subprocess.run(
        ["git", "show", f"{ref}:{BASELINE_PATH}"],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        print(f"❌ 无法读取 {ref}:{BASELINE_PATH}\n{result.stderr.strip()}", file=sys.stderr)
        sys.exit(2)
    return set(ID_PATTERN.findall(result.stdout))


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    base_ref = sys.argv[1]

    current = load_ids_from_worktree()
    base = load_ids_from_ref(base_ref)

    added = sorted(current - base)
    removed = base - current

    print(f"detekt baseline 对比（base = {base_ref}）:")
    print(f"  基线条目: {len(base)} → 当前条目: {len(current)}")

    if removed:
        print(f"  ✅ 消化存量 {len(removed)} 条（只减不增，继续保持）")

    if added:
        print(f"  ❌ 新增豁免 {len(added)} 条（冻结新增，门禁拒绝）:")
        for entry in added[:20]:
            print(f"     + {entry}")
        if len(added) > 20:
            print(f"     ... 及其余 {len(added) - 20} 条")
        print(
            "\n修复方向: 新代码不得靠 baseline 豁免绕过 detekt——"
            "要么修复问题，要么在代码上用 @Suppress 并注明理由。"
        )
        return 1

    print("🎉 无新增豁免，门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
