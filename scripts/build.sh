#!/bin/bash
#
# PoLang APK/AAB 打包脚本
# 支持 debug / release / release-no-proguard / aab / aab-plain 五种模式
#
# 用法:
#   ./scripts/build.sh debug          # 打 debug 包
#   ./scripts/build.sh release        # 打 release APK（使用 androidApp/keystore/picme-release.jks）
#   ./scripts/build.sh release-plain  # 打 release APK 但不混淆（用于调试 release 问题）
#   ./scripts/build.sh aab            # 打 release AAB（Google Play 上架格式；Play 渠道，自动剥离 REQUEST_INSTALL_PACKAGES）
#   ./scripts/build.sh aab-plain      # 打 release AAB 但不混淆（用于调试 release 问题）
#
# Release 签名配置从环境变量读取（优先 POLANG_*，兼容旧 PICME_*）：
#   export POLANG_RELEASE_STORE_PASSWORD=your_password
#   export POLANG_RELEASE_KEY_ALIAS=your_alias
#   export POLANG_RELEASE_KEY_PASSWORD=your_password

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
APK_OUTPUT_DIR="$PROJECT_ROOT/androidApp/build/outputs/apk"
AAB_OUTPUT_DIR="$PROJECT_ROOT/androidApp/build/outputs/bundle"
KEYSTORE_PATH="$PROJECT_ROOT/androidApp/keystore/picme-release.jks"

# 兼容旧环境变量命名（PICME_*）
export POLANG_RELEASE_STORE_PASSWORD="${POLANG_RELEASE_STORE_PASSWORD:-$PICME_RELEASE_STORE_PASSWORD}"
export POLANG_RELEASE_KEY_ALIAS="${POLANG_RELEASE_KEY_ALIAS:-$PICME_RELEASE_KEY_ALIAS}"
export POLANG_RELEASE_KEY_PASSWORD="${POLANG_RELEASE_KEY_PASSWORD:-$PICME_RELEASE_KEY_PASSWORD}"
export POLANG_RELEASE_STORE_FILE="${POLANG_RELEASE_STORE_FILE:-$PICME_RELEASE_STORE_FILE}"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查环境
check_env() {
    if ! command -v ./gradlew &> /dev/null; then
        log_error "gradlew 未找到，请在项目根目录运行"
        exit 1
    fi
}

# 查找最新生成的 APK/AAB
find_artifact() {
    local dir="$1"
    local suffix="$2"
    if [ -d "$dir" ]; then
        find "$dir" -name "*.${suffix}" -type f -print0 | xargs -0 ls -t 2>/dev/null | head -1
    fi
}

# 打印产物信息
print_artifact_info() {
    local artifact="$1"
    if [ -f "$artifact" ]; then
        local size
        size=$(du -h "$artifact" | cut -f1)
        log_info "产物路径: $artifact"
        log_info "产物大小: $size"
        # 尝试提取签名信息（仅 APK）
        if [[ "$artifact" == *.apk ]] && command -v apksigner &> /dev/null; then
            apksigner verify -v "$artifact" 2>/dev/null | head -5 || true
        fi
    fi
}

# 构建 Debug 包
build_debug() {
    log_info "开始构建 Debug 包..."
    ./gradlew :androidApp:assembleDebug

    local apk
    apk=$(find_artifact "$APK_OUTPUT_DIR/debug" "apk")
    if [ -n "$apk" ]; then
        print_artifact_info "$apk"
        log_success "Debug 包构建完成"
    else
        log_error "Debug 包未找到"
        exit 1
    fi
}

# 构建 Release 包
build_release() {
    local plain_mode="${1:-false}"

    # 检查 keystore 存在
    if [ ! -f "$KEYSTORE_PATH" ]; then
        log_error "Release keystore 未找到: $KEYSTORE_PATH"
        exit 1
    fi

    # 检查环境变量
    if [ -z "$POLANG_RELEASE_STORE_PASSWORD" ]; then
        log_warn "环境变量 POLANG_RELEASE_STORE_PASSWORD 未设置"
        read -rsp "请输入 keystore 密码: " POLANG_RELEASE_STORE_PASSWORD
        echo
        export POLANG_RELEASE_STORE_PASSWORD
    fi

    if [ -z "$POLANG_RELEASE_KEY_ALIAS" ]; then
        log_warn "环境变量 POLANG_RELEASE_KEY_ALIAS 未设置"
        read -rp "请输入 key alias: " POLANG_RELEASE_KEY_ALIAS
        export POLANG_RELEASE_KEY_ALIAS
    fi

    if [ -z "$POLANG_RELEASE_KEY_PASSWORD" ]; then
        log_warn "环境变量 POLANG_RELEASE_KEY_PASSWORD 未设置"
        read -rsp "请输入 key 密码: " POLANG_RELEASE_KEY_PASSWORD
        echo
        export POLANG_RELEASE_KEY_PASSWORD
    fi

    # 设置 keystore 路径环境变量
    export POLANG_RELEASE_STORE_FILE="$KEYSTORE_PATH"

    # 传递构建类型标记给 Gradle
    local plain_flag=""
    if [ "$plain_mode" = "true" ]; then
        plain_flag="-Ppolang.release.plain=true"
        log_info "开始构建 Release 包（不混淆）..."
    else
        log_info "开始构建 Release 包..."
    fi

    ./gradlew :androidApp:assembleRelease \
        -Pandroid.injected.signing.store.file="$KEYSTORE_PATH" \
        -Pandroid.injected.signing.store.password="$POLANG_RELEASE_STORE_PASSWORD" \
        -Pandroid.injected.signing.key.alias="$POLANG_RELEASE_KEY_ALIAS" \
        -Pandroid.injected.signing.key.password="$POLANG_RELEASE_KEY_PASSWORD" \
        $plain_flag \
        --no-configuration-cache

    local apk
    apk=$(find_artifact "$APK_OUTPUT_DIR/release" "apk")
    if [ -n "$apk" ]; then
        print_artifact_info "$apk"
        if [ "$plain_mode" = "true" ]; then
            log_success "Release 包（不混淆）构建完成"
        else
            log_success "Release 包构建完成"
        fi
    else
        log_error "Release 包未找到"
        exit 1
    fi
}

# 构建 Release AAB（Google Play 上架格式）
build_aab() {
    local plain_mode="${1:-false}"

    # 检查 keystore 存在
    if [ ! -f "$KEYSTORE_PATH" ]; then
        log_error "Release keystore 未找到: $KEYSTORE_PATH"
        exit 1
    fi

    # 检查环境变量
    if [ -z "$POLANG_RELEASE_STORE_PASSWORD" ]; then
        log_warn "环境变量 POLANG_RELEASE_STORE_PASSWORD 未设置"
        read -rsp "请输入 keystore 密码: " POLANG_RELEASE_STORE_PASSWORD
        echo
        export POLANG_RELEASE_STORE_PASSWORD
    fi

    if [ -z "$POLANG_RELEASE_KEY_ALIAS" ]; then
        log_warn "环境变量 POLANG_RELEASE_KEY_ALIAS 未设置"
        read -rp "请输入 key alias: " POLANG_RELEASE_KEY_ALIAS
        export POLANG_RELEASE_KEY_ALIAS
    fi

    if [ -z "$POLANG_RELEASE_KEY_PASSWORD" ]; then
        log_warn "环境变量 POLANG_RELEASE_KEY_PASSWORD 未设置"
        read -rsp "请输入 key 密码: " POLANG_RELEASE_KEY_PASSWORD
        echo
        export POLANG_RELEASE_KEY_PASSWORD
    fi

    # 设置 keystore 路径环境变量
    export POLANG_RELEASE_STORE_FILE="$KEYSTORE_PATH"

    # 传递构建类型标记给 Gradle
    local plain_flag=""
    if [ "$plain_mode" = "true" ]; then
        plain_flag="-Ppolang.release.plain=true"
        log_info "开始构建 Release AAB（不混淆）..."
    else
        log_info "开始构建 Release AAB（Google Play 上架格式）..."
    fi

    # AAB = Play 渠道产物：剥离 REQUEST_INSTALL_PACKAGES（Play 政策禁止未声明权限，
    # 且 Play 渠道 OTA 自更新本就禁用）；APK 直装渠道保留该权限。
    ./gradlew :androidApp:bundleRelease \
        -Pandroid.injected.signing.store.file="$KEYSTORE_PATH" \
        -Pandroid.injected.signing.store.password="$POLANG_RELEASE_STORE_PASSWORD" \
        -Pandroid.injected.signing.key.alias="$POLANG_RELEASE_KEY_ALIAS" \
        -Pandroid.injected.signing.key.password="$POLANG_RELEASE_KEY_PASSWORD" \
        -Ppolang.play.channel=true \
        $plain_flag \
        --no-configuration-cache

    local aab
    aab=$(find_artifact "$AAB_OUTPUT_DIR/release" "aab")
    if [ -n "$aab" ]; then
        print_artifact_info "$aab"
        if [ "$plain_mode" = "true" ]; then
            log_success "Release AAB（不混淆）构建完成"
        else
            log_success "Release AAB 构建完成"
        fi
    else
        log_error "Release AAB 未找到"
        exit 1
    fi
}

# 主入口
main() {
    cd "$PROJECT_ROOT"
    check_env

    local mode="${1:-debug}"

    case "$mode" in
        debug)
            build_debug
            ;;
        release)
            build_release false
            ;;
        release-plain|release-plain|plain)
            build_release true
            ;;
        aab)
            build_aab false
            ;;
        aab-plain)
            build_aab true
            ;;
        *)
            echo "用法: $0 {debug|release|release-plain|aab|aab-plain}"
            echo ""
            echo "  debug          - 构建 debug APK"
            echo "  release        - 构建 release APK（使用 androidApp/keystore/picme-release.jks）"
            echo "  release-plain  - 构建 release APK 但不启用混淆/R8"
            echo "  aab            - 构建 release AAB（Google Play 上架格式）"
            echo "  aab-plain      - 构建 release AAB 但不启用混淆/R8"
            echo ""
            echo "Release 签名需要环境变量："
            echo "  POLANG_RELEASE_STORE_PASSWORD  - keystore 密码（兼容 PICME_RELEASE_STORE_PASSWORD）"
            echo "  POLANG_RELEASE_KEY_ALIAS       - key alias（兼容 PICME_RELEASE_KEY_ALIAS）"
            echo "  POLANG_RELEASE_KEY_PASSWORD    - key 密码（兼容 PICME_RELEASE_KEY_PASSWORD）"
            exit 1
            ;;
    esac
}

main "$@"
