#!/usr/bin/env bash
# 同步 docs/ -> docs-site/docs/（docsify 文档站产物），排除不上线的敏感/内部文档。
# 无构建：docsify 为浏览器运行时渲染，rsync 文件即可。
# deploy-docs-site.sh 与本地预览均调用本脚本。
set -euo pipefail
cd "$(dirname "$0")/.."

SRC="docs"
DST="docs-site/docs"

# 公开面瘦身（2026-09-27）：内部工作文档/过时快照只在仓库，不上线
rsync -a --delete --delete-excluded \
  --exclude 'superpowers/' \
  --exclude '08-UI-SPECS/' \
  --exclude 'reviews/' \
  --exclude '01-PRODUCT/IOS_DOC_INDEX.md' \
  --exclude '01-PRODUCT/IOS_TASK_STATUS.md' \
  --exclude '01-PRODUCT/IOS_PRODUCT_REFERENCE.md' \
  --exclude '03-TECHNICAL-SPECS/OVERSEAS_SERVER_DEPLOYMENT.md' \
  --exclude '03-TECHNICAL-SPECS/AI_IMAGE_EDITING_CAPABILITY_GAP.md' \
  --exclude '03-TECHNICAL-SPECS/MNN_LANDMARK_DIAGNOSIS.md' \
  --exclude '03-TECHNICAL-SPECS/ONDEVICE_IMAGE_UNDERSTANDING_MODELS.md' \
  --exclude '03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md' \
  --exclude '03-TECHNICAL-SPECS/SMART_OPTIMIZE_VLM_DESIGN.md' \
  --exclude '05-DEVELOPMENT/LOCAL_ENVIRONMENT.md' \
  --exclude '05-DEVELOPMENT/RELEASE_PACKAGE_BACKUP_RESTORE.md' \
  --exclude '06-QA/' \
  --exclude 'privacy-policy/' \
  "$SRC/" "$DST/"

echo "sync-docs: ${SRC} -> ${DST} (docsify site)"
echo "  online docs: $(find "${DST}" -name '*.md' | wc -l | tr -d ' ')"
echo "  excluded: superpowers/(在途) + 08-UI-SPECS/(双端契约) + reviews/(内部审计) + iOS 工作文档×3 + 本机环境 + 06-QA/(历史报告) + 4 篇过时技术快照 + server deploy/backup + privacy-policy/ (landing page has it)"
