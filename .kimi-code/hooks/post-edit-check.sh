#!/usr/bin/env bash
# Hook A: post Edit|Write lightweight check. Observational; always exit 0.
set -uo pipefail
DEBUG="${KIMI_POLANG_HOOK_DEBUG:-0}"
LIB="$(cd "$(dirname "$0")" && pwd)/lib"
ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"

INPUT="$(cat)"
PATH_="$(HOOK_JSON="$INPUT" python3 -c '
import os, json
try:
    d = json.loads(os.environ.get("HOOK_JSON", "") or "{}")
except Exception:
    d = {}
print((d.get("tool_input") or {}).get("file_path") or (d.get("tool_input") or {}).get("path") or "")
')"

[ -n "$PATH_" ] || exit 0
[ "$DEBUG" = "1" ] && echo "[post-edit] path=$PATH_" >&2

case "$(basename "$PATH_")" in
  *.kt|*.java)
    "$LIB/i18n-hardcode.sh" "$PATH_" || true
    "$LIB/parity-hardcode.sh" "$PATH_" || true
    ;;
  *.md)
    case "$PATH_" in
      */docs/*|*/PRODUCT.md|*/FEATURES.md)
        [ "$DEBUG" = "1" ] && echo "[post-edit] doc-sync for $PATH_" >&2
        ( cd "$ROOT" && python3 scripts/check_doc_sync.py 2>&1 | tail -8 ) || true
        ;;
    esac
    ;;
esac
exit 0
