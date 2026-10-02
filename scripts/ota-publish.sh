#!/usr/bin/env bash
# ota-publish.sh — OTA 一键发包：构建 APK → 提取版本信息 → 上传自建 OTA 渠道（腾讯 COS 经 picme-server /admin/apk/upload）
#
# 用法:
#   ./scripts/ota-publish.sh                          # debug 轨（默认）：assembleDebug → 上传 debug 渠道
#   ./scripts/ota-publish.sh --type release           # release 轨：build.sh release（需 POLANG_RELEASE_* 签名环境变量）
#   ./scripts/ota-publish.sh --notes "修复打标崩溃"    # 更新说明（App 更新弹窗展示）
#   ./scripts/ota-publish.sh --no-build               # 跳过构建，直接上传已有产物
#
# 环境变量:
#   POLANG_ADMIN_TOKEN   服务端 ADMIN_TOKEN（必填）
#   POLANG_OTA_BASE_URL  服务端地址（默认 https://api.polang.net）
#
# 渠道说明: debug 轨用本机 debug.keystore 签名（同机覆盖安装签名一致，遛狗主轨）;
#           release 轨用 picme-release.jks（Play 对齐验证）。两轨 COS key 独立互不影响。
set -euo pipefail

TYPE="debug"
NOTES=""
BUILD=1
BASE_URL="${POLANG_OTA_BASE_URL:-https://api.polang.net}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --type)
            TYPE="$2"; shift 2 ;;
        --notes)
            NOTES="$2"; shift 2 ;;
        --no-build)
            BUILD=0; shift ;;
        -h|--help)
            sed -n '2,17p' "$0"; exit 0 ;;
        *)
            echo "未知参数: $1" >&2; exit 1 ;;
    esac
done

if [[ "$TYPE" != "debug" && "$TYPE" != "release" ]]; then
    echo "--type 仅支持 debug | release" >&2; exit 1
fi

if [[ -z "${POLANG_ADMIN_TOKEN:-}" ]]; then
    echo "缺少 POLANG_ADMIN_TOKEN 环境变量（对应服务端 ADMIN_TOKEN）" >&2; exit 1
fi

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

APK_PATH="$REPO_ROOT/androidApp/build/outputs/apk/$TYPE/polang-$TYPE.apk"

# ── 1. 构建 ─────────────────────────────────────────────
if [[ "$BUILD" == "1" ]]; then
    echo "==> 构建 $TYPE 包"
    if [[ "$TYPE" == "debug" ]]; then
        ./gradlew :androidApp:assembleDebug --console=plain -q
    else
        ./scripts/build.sh release
    fi
fi

if [[ ! -f "$APK_PATH" ]]; then
    echo "APK 不存在: $APK_PATH（先去掉 --no-build 或检查构建）" >&2; exit 1
fi

# ── 2. 提取 versionCode / versionName ───────────────────
APKANALYZER="${ANDROID_HOME:-$HOME/Library/Android/sdk}/cmdline-tools/latest/bin/apkanalyzer"
if [[ ! -x "$APKANALYZER" ]]; then
    echo "apkanalyzer 不可用: $APKANALYZER" >&2; exit 1
fi
VERSION_CODE="$("$APKANALYZER" manifest version-code "$APK_PATH")"
VERSION_NAME="$("$APKANALYZER" manifest version-name "$APK_PATH")"
APK_SIZE="$(stat -f%z "$APK_PATH")"
echo "==> $TYPE | v$VERSION_NAME ($VERSION_CODE) | $((APK_SIZE / 1024 / 1024)) MB"

# ── 3. 上传 ─────────────────────────────────────────────
echo "==> 上传到 ${BASE_URL}（${TYPE} 渠道）"
RESP="$(curl -fsS -X POST "$BASE_URL/admin/apk/upload" \
    -H "X-Admin-Token: $POLANG_ADMIN_TOKEN" \
    -F "channel=$TYPE" \
    -F "version=$VERSION_NAME" \
    -F "versionCode=$VERSION_CODE" \
    -F "changelog=$NOTES" \
    -F "apkfile=@$APK_PATH;type=application/vnd.android.package-archive")"
echo "==> 服务端响应: $RESP"

if [[ "$RESP" == *'"ok":true'* ]]; then
    echo "==> 发布成功：测试机下次冷启动将收到更新提示（仅非 Play 渠道安装的包）"
else
    echo "==> 发布失败，见上方响应" >&2; exit 1
fi
