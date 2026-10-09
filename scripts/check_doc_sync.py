#!/usr/bin/env python3
"""
Document Sync Guardian - 文档一致性自动检查器
检查 PoLang 三层文档体系中的不一致问题：
1. 对已删除文件的引用
2. "进行中" vs "已落地" 状态标记不一致
3. 无效的内部链接
4. spec/plan/design/adr 文档散逸门禁（集中管理，见 docs/00-INDEX.md 文档地图）
5. 活文档 → 过程文档 单向引用门禁（AGENTS.md §4.3 三分类：活文档/快照/过程文档）
6. docs/reviews 快照登记（结论性快照准入制，AGENTS.md §4.3）
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
    ".qoder/worktrees/",   # Qoder 工具 worktree 镜像（同 .claude/worktrees）
    "docs-site/docs/",   # sync-docs.sh 生成物
    "build/",
    "temp/gpupixel/",
    "tmp/",
    "iosApp/Pods/",
    "iosApp/build/",
    "node_modules/",      # npm vendor 树（browser-bridge 等，同 iosApp/Pods 性质）
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


def _site_excludes():
    """解析 scripts/sync-docs.sh 的 rsync --exclude 清单（发布集合判定单一事实源）"""
    sh = PROJECT_ROOT / "scripts" / "sync-docs.sh"
    return [m.group(1) for m in re.finditer(
        r"--exclude '([^']+)'", sh.read_text(encoding="utf-8"))]


def _is_published(rel_docs_posix: str) -> bool:
    """docs/ 内文件是否随官网文档站发布（未被 sync-docs.sh exclude）"""
    for ex in _site_excludes():
        ex = ex.rstrip("/")
        if rel_docs_posix == ex or rel_docs_posix.startswith(ex + "/"):
            return False
    return True


def check_broken_links() -> list:
    """检查内部 Markdown 链接是否指向存在的文件

    双语义解析（2026-09-27）：① 文件相对（GitHub/skills 习惯）；② 项目根相对；
    ③ docs 站点根相对（docsify relativePath:false 语义——官网发布文档的标准写法）。
    任一命中即有效。另对「发布集合内嵌套文档」做 docsify 断链回归拦截：
    同目录裸文件名链接只在文件相对语义下命中 = 官网点开必 404。
    """
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
        in_docs = str(rel_path).startswith("docs/")
        published = in_docs and _is_published(str(rel_path)[len("docs/"):])

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

            file_rel_ok = (base_dir / link_target).exists()
            root_rel_ok = (PROJECT_ROOT / link_target.lstrip("/")).exists()
            docs_rel_ok = (PROJECT_ROOT / "docs" /
                           link_target.lstrip("./").lstrip("/")).exists()
            if not (file_rel_ok or root_rel_ok or docs_rel_ok):
                issues.append(
                    f"  [断裂链接] {rel_path}: '{link_target}' 不存在"
                )
            elif (published and not str(rel_path) == "docs/_sidebar.md"
                  and file_rel_ok and not docs_rel_ok):
                # 发布集合内：docsify 按 /docs/ 根解析，文件相对命中的裸链在线必断
                fixed = (base_dir / link_target).resolve().relative_to(
                    (PROJECT_ROOT / "docs").resolve()).as_posix()
                issues.append(
                    f"  [docsify 断链] {rel_path}: '{link_target}' 在官网按 /docs/ 根"
                    f"解析会 404，应改写为 '{fixed}'"
                )

        # markdown 图片：docsify renderer.image 无条件按「文件所在目录」解析
        # （q(contentBase, getParentPath(currentRoute), href)）——与链接语义相反；
        # 根相对图片在嵌套页必 404。原始 HTML <img> 走浏览器 /docs/ 基址，不在此列。
        img_pattern = re.compile(r"!\[[^\]]*\]\(([^)\s]+)(?:\s+[^)]*)?\)")
        for match in img_pattern.finditer(content):
            img_target = match.group(1)
            if img_target.startswith(("http", "data:", "#", "mailto:")):
                continue
            img_path = img_target.split("#")[0].split("?")[0]
            if not img_path:
                continue
            img_file_rel_ok = (base_dir / img_path).exists()
            img_root_rel = img_path.strip("./").lstrip("/")
            img_docs_rel_ok = (PROJECT_ROOT / "docs" / img_root_rel).exists()
            if not img_file_rel_ok and not img_docs_rel_ok:
                issues.append(
                    f"  [断裂图片] {rel_path}: '{img_target}' 不存在"
                )
            elif published and not img_file_rel_ok and img_docs_rel_ok:
                # rel_path 含 docs/ 前缀，深度按 docs 内层数计
                depth = len(rel_path.parent.parts) - (1 if in_docs else 0)
                fixed = "../" * depth + img_root_rel if depth else img_root_rel
                issues.append(
                    f"  [docsify 图片断链] {rel_path}: '{img_target}' 官网按文件所在"
                    f"目录解析会 404（docsify 图片=文件相对，与链接相反），应改写为"
                    f" '{fixed}'"
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
    "docs/reviews/",               # 结论性快照（登记准入，AGENTS.md §4.3）
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


# ---------------------------------------------------------------------------
# 检查 5：活文档 → 过程文档 单向引用门禁（2026-09-28 机制，AGENTS.md §4.3）
# 三分类：活文档（过程命名空间之外的一切 .md）/ 快照（docs/reviews 结论性
# 系统快照，登记准入）/ 过程文档（docs/superpowers + docs/06-QA）。
# 活文档可引用活文档与在册快照；引用过程产物路径即 FAIL。
# ---------------------------------------------------------------------------
PROCESS_DIRS = ("docs/superpowers/", "docs/06-QA/")

REVIEWS_WHITELIST = {
    "2026-08-10-ios-android-consistency-gap.md":
        "PARITY 现行差异审计快照（PARITY_MASTER_PLAN/skills 引用）",
    "2026-08-10-kmp-best-practices-architecture-review.md":
        "KMP 路线评估快照（含行动项，AGENTS.md §7 引用）",
}

PROCESS_REF_PATTERN = re.compile(
    r"(?:docs/)?(?:\.\./)*(?:docs/)?"
    r"(superpowers|reviews|06-QA)/([\w\-./]+\.(?:md|html|yaml))"
)


def _superpowers_whitelist() -> set:
    """docs/superpowers/README.md §6 的在途/活跃 spec 白名单（活文档可引用集）"""
    names = {"README.md"}  # 白名单登记处与机制说明本身
    readme = PROJECT_ROOT / "docs" / "superpowers" / "README.md"
    if readme.exists():
        text = readme.read_text(encoding="utf-8")
        m = re.search(r"## 6\.[^\n]*\n(.*?)(?=\n## )", text, re.S)
        if m:
            names |= set(re.findall(r"`([\w\-./]+\.md)`", m.group(1)))
    return names


def _tracked_files():
    """git 追踪文件集合（非 git 环境返回 None，调用方回退全量扫描）"""
    try:
        out = subprocess.run(
            ["git", "ls-files"], cwd=PROJECT_ROOT, capture_output=True,
            text=True, check=True,
        ).stdout
        return set(out.splitlines())
    except Exception:
        return None


def check_one_way_references() -> list:
    """活文档禁止指向过程目录（白名单例外；AGENTS.md §4.3 引用单向铁律）"""
    issues = []
    sp_whitelist = _superpowers_whitelist()
    tracked = _tracked_files()
    md_files = [
        f for f in PROJECT_ROOT.rglob("*.md")
        if not is_excluded(f.relative_to(PROJECT_ROOT))
    ]
    for md_file in md_files:
        rel = md_file.relative_to(PROJECT_ROOT).as_posix()
        if any(rel.startswith(d) for d in PROCESS_DIRS):
            continue  # 过程命名空间内部互引不受限（docs/reviews 快照照常扫描）
        if tracked is not None and rel not in tracked:
            continue  # 未追踪草稿不拦（并行会话在途稿；提交边界由 CI 把关）
        content = md_file.read_text(encoding="utf-8")
        for m in PROCESS_REF_PATTERN.finditer(content):
            ns, name = m.group(1), Path(m.group(2)).name
            if ns == "superpowers" and name in sp_whitelist:
                continue
            if ns == "reviews" and name in REVIEWS_WHITELIST:
                continue
            issues.append(
                f"  [过程引用] {rel}: 指向过程文档 '{m.group(0)}'"
                f"（AGENTS.md §4.3 单向引用：结论收编活文档，不留过程路径）"
            )
    return issues


def check_reviews_whitelist() -> list:
    """git 追踪的 docs/reviews 快照必须显式登记白名单（未追踪的在途稿不拦）"""
    issues = []
    try:
        tracked = subprocess.run(
            ["git", "ls-files", "docs/reviews"], cwd=PROJECT_ROOT,
            capture_output=True, text=True, check=True,
        ).stdout.splitlines()
    except Exception:
        return issues  # 非 git 环境跳过本检查
    for path in tracked:
        name = Path(path).name
        if not (PROJECT_ROOT / path).exists():
            continue  # 已从工作区删除、待暂存的历史项不重复报
        if name not in REVIEWS_WHITELIST:
            issues.append(
                f"  [快照未登记] {path}: docs/reviews 为结论性快照目录"
                f"（登记准入制）——结论收编活文档或被新快照取代后应删除；"
                f"确需保留请在 check_doc_sync.py REVIEWS_WHITELIST 登记理由"
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

    print("\n🔍 检查 5: 活文档→过程文档 单向引用门禁...")
    issues = check_one_way_references()
    if issues:
        all_issues.extend(issues)
        print(f"   ⚠️  发现 {len(issues)} 个问题")
        for issue in issues:
            print(issue)
    else:
        print("   ✅ 活文档无过程目录引用")

    print("\n🔍 检查 6: docs/reviews 快照登记...")
    issues = check_reviews_whitelist()
    if issues:
        all_issues.extend(issues)
        print(f"   ⚠️  发现 {len(issues)} 个问题")
        for issue in issues:
            print(issue)
    else:
        print("   ✅ reviews 目录在册")

    print("\n" + "=" * 50)
    if all_issues:
        print(f"❌ 共发现 {len(all_issues)} 个文档一致性问题")
        sys.exit(1)
    else:
        print("🎉 文档一致性检查全部通过！")
        sys.exit(0)


if __name__ == "__main__":
    main()
