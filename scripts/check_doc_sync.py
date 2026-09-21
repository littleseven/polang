#!/usr/bin/env python3
"""
Document Sync Guardian - 文档一致性自动检查器
检查 PoLang 三层文档体系中的不一致问题：
1. 对已删除文件的引用
2. "进行中" vs "已落地" 状态标记不一致
3. 无效的内部链接
4. spec/plan/design/adr 文档散逸门禁（集中管理，见 docs/00-INDEX.md 文档地图）
"""

import os
import re
import subprocess
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).parent.parent

# 扫描时排除的镜像/生成目录（相对项目根）
EXCLUDED_DIRS = (
    ".git/",
    ".worktrees/",
    ".claude/worktrees/",
    "docs-site/docs/",   # sync-docs.sh 生成物
    "build/",
    "temp/gpupixel/",
    "tmp/",
    "iosApp/Pods/",
    "iosApp/build/",
    ".lingma/skills/",
    ".kimi/skills/",
    ".openclaw/skills/",
)


def is_excluded(rel_path: Path) -> bool:
    s = str(rel_path) + "/"
    return any(s.startswith(d) or f"/{d}" in s for d in EXCLUDED_DIRS)

# 已删除但仍可能被引用的文档
DELETED_FILES = {
    "Analysis_Report.md",
    "docs/GPU_PHOTO_IMPLEMENTATION_GUIDE.md",
    "docs/GPU_PHOTO_MAJOR_CHANGES.md",
    "docs/audit_report_20260503.md",
    "docs/MEDIAPIPE_468_COMPLETE_REFERENCE.md",
}

# 状态标记正则
STATUS_PATTERNS = [
    (r"\(2026-0\d 进行中\)", "进行中"),
    (r"\[ROADMAP\].*进行中", "进行中"),
]

# 需要同步状态标记的文件
SYNC_FILES = [
    "PRODUCT.md",
    "docs/01-PRODUCT/FEATURES.md",
    "docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md",
    "engines/beauty-engine/AGENTS.md",
    "README.md",
]


def check_deleted_file_references() -> list:
    """检查对已删除文件的引用"""
    issues = []
    md_files = list(PROJECT_ROOT.rglob("*.md"))

    for md_file in md_files:
        # 跳过 .git 和 temp/gpupixel
        rel_path = md_file.relative_to(PROJECT_ROOT)
        if is_excluded(rel_path):
            continue

        content = md_file.read_text(encoding="utf-8")
        for deleted in DELETED_FILES:
            if deleted in content:
                issues.append(
                    f"  [无效引用] {rel_path}: 引用了已删除的 '{deleted}'"
                )

    return issues


def check_status_inconsistency() -> list:
    """检查 '进行中' 状态标记是否存在于已落地的功能描述中"""
    issues = []
    keywords = ["拍照 GPU 化", "GPU 离屏渲染拍照", "PhotoProcessorImpl"]

    for filename in SYNC_FILES:
        filepath = PROJECT_ROOT / filename
        if not filepath.exists():
            continue

        content = filepath.read_text(encoding="utf-8")
        lines = content.split("\n")

        for i, line in enumerate(lines, 1):
            # 如果行包含关键词且包含"进行中"
            has_keyword = any(kw in line for kw in keywords)
            has_in_progress = "进行中" in line
            if has_keyword and has_in_progress:
                issues.append(
                    f"  [状态不一致] {filename}:{i}: '{line.strip()[:80]}...'"
                )

    return issues


def check_broken_links() -> list:
    """检查内部 Markdown 链接是否指向存在的文件"""
    issues = []
    md_files = [
        f for f in PROJECT_ROOT.rglob("*.md")
        if not is_excluded(f.relative_to(PROJECT_ROOT))
    ]

    link_pattern = re.compile(r"\[([^\]]+)\]\(([^)]+)\)")

    for md_file in md_files:
        rel_path = md_file.relative_to(PROJECT_ROOT)
        content = md_file.read_text(encoding="utf-8")
        base_dir = md_file.parent

        for match in link_pattern.finditer(content):
            link_target = match.group(2)
            # 只检查相对路径的 .md 链接
            if link_target.startswith("http") or link_target.startswith("#"):
                continue
            if not link_target.endswith(".md"):
                continue

            # skills/TEMPLATE.md 的占位链接不检查
            if rel_path == Path("skills/TEMPLATE.md"):
                continue

            target_path = base_dir / link_target
            # 回退：skills/ 与 .claude/commands/ 内链接 / leading-slash 根相对链接按项目根解析
            if not target_path.exists():
                target_path = PROJECT_ROOT / link_target.lstrip("/")
            if not target_path.exists():
                issues.append(
                    f"  [断裂链接] {rel_path}: '{link_target}' 不存在"
                )

    return issues


# ---------------------------------------------------------------------------
# 检查 4：spec/plan/design/adr 文档散逸门禁
# 集中管理约定（docs/00-INDEX.md 文档地图）：这类命名的工作文档只允许存在于
# 下列批准目录；仓库根 / 模块目录出现即视为散逸。例外在 DOC_GATE_WHITELIST 登记。
# ---------------------------------------------------------------------------
DOC_NAME_PATTERN = re.compile(r"(?i)(spec|plan|design|adr)")
DOC_FILE_EXTS = (".md", ".yaml", ".yml")
APPROVED_DOC_DIRS = (
    "docs/02-ARCHITECTURE/ADR/",   # 架构决策
    "docs/03-TECHNICAL-SPECS/",    # 技术规范
    "docs/01-PRODUCT/",            # 产品规格（NFR_SPEC 等）
    "docs/08-UI-SPECS/",           # 双端 UI 契约（原根 specs/，2026-08-23 迁入）
    "docs/superpowers/",           # AI 协作在途 spec/plan（交付即清理）
    "docs/reviews/",               # 时间点快照
    ".claude/agents/",             # 工具配置（planner 等 agent 定义，非项目文档）
    ".claude/commands/",
    ".qoder/agents/",              # Qoder 工具配置（planner 等 agent 定义，非项目文档）
    ".claude/workflows/",
    "skills/",                     # skill 源（SSOT，.claude/ 为其镜像）
)
DOC_GATE_WHITELIST = {
    # "path/to/file.md": "<登记理由>",
    # sentencepiece 为 vendored 第三方源码树，doc/ 为其上游自带文档
    "engines/sentencepiece/src/main/cpp/doc/special_symbols.md":
        "vendored sentencepiece 上游文档（'special' 撞 spec 关键词）",
}
# 已迁移/禁用的历史位置：任何追踪文件出现在这些目录下直接报错
BANNED_DOC_DIRS = (
    "specs/",                      # → docs/08-UI-SPECS/（2026-08-23 迁移）
)


def check_doc_drift() -> list:
    """spec/plan/design/adr 命名的 git 追踪文档必须位于批准目录"""
    issues = []
    try:
        tracked = subprocess.run(
            ["git", "ls-files"], cwd=PROJECT_ROOT, capture_output=True,
            text=True, check=True,
        ).stdout.splitlines()
    except Exception:
        return issues  # 非 git 环境跳过本检查

    for path in tracked:
        p = Path(path)
        if p.suffix.lower() not in DOC_FILE_EXTS:
            continue
        if path in DOC_GATE_WHITELIST:
            continue
        if any(path.startswith(d) for d in BANNED_DOC_DIRS):
            issues.append(
                f"  [禁用位置] {path}: 该目录已迁移，入 docs/08-UI-SPECS/"
            )
            continue
        if not DOC_NAME_PATTERN.search(p.name):
            continue
        if not any(path.startswith(d) for d in APPROVED_DOC_DIRS):
            issues.append(
                f"  [散逸文档] {path}: *spec*/*plan*/*design*/*adr* 命名文档"
                f"须位于批准目录（docs/00-INDEX.md 文档地图；"
                f"例外登记 scripts/check_doc_sync.py DOC_GATE_WHITELIST）"
            )
    return issues


def main():
    print("🤖 Document Sync Guardian")
    print("=" * 50)

    all_issues = []

    print("\n🔍 检查 1: 对已删除文件的引用...")
    issues = check_deleted_file_references()
    if issues:
        all_issues.extend(issues)
        print(f"   ⚠️  发现 {len(issues)} 个问题")
        for issue in issues:
            print(issue)
    else:
        print("   ✅ 无无效引用")

    print("\n🔍 检查 2: 状态标记一致性...")
    issues = check_status_inconsistency()
    if issues:
        all_issues.extend(issues)
        print(f"   ⚠️  发现 {len(issues)} 个问题")
        for issue in issues:
            print(issue)
    else:
        print("   ✅ 状态标记一致")

    print("\n🔍 检查 3: 内部链接有效性...")
    issues = check_broken_links()
    if issues:
        all_issues.extend(issues)
        print(f"   ⚠️  发现 {len(issues)} 个问题")
        for issue in issues:
            print(issue)
    else:
        print("   ✅ 所有链接有效")

    print("\n🔍 检查 4: spec/plan/design/adr 散逸门禁...")
    issues = check_doc_drift()
    if issues:
        all_issues.extend(issues)
        print(f"   ⚠️  发现 {len(issues)} 个问题")
        for issue in issues:
            print(issue)
    else:
        print("   ✅ 无散逸文档")

    print("\n" + "=" * 50)
    if all_issues:
        print(f"❌ 共发现 {len(all_issues)} 个文档一致性问题")
        sys.exit(1)
    else:
        print("🎉 文档一致性检查全部通过！")
        sys.exit(0)


if __name__ == "__main__":
    main()
