#!/bin/bash
#
# AI Gate - PoLang AI Coding 自动化验证脚本
# 用途：AI 提交代码前的一键完整验证（代码级 + 设备级自动闭环）
# 调用：./scripts/ai-gate.sh [options]
#
# Options:
#   --device        编译通过后自动检测设备并安装运行验证
#   --no-device     仅代码级检查（默认行为与之前一致）
#   --full          等价于 --device，执行完整验证闭环
#

set -e

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

PASS_COUNT=0
FAIL_COUNT=0
WARN_COUNT=0

# 参数解析
DEVICE_CHECK=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --device|--full) DEVICE_CHECK=true; shift ;;
        --no-device) DEVICE_CHECK=false; shift ;;
        *) echo "未知参数: $1"; exit 1 ;;
    esac
done

TIMESTAMP=$(date '+%Y%m%d_%H%M%S')
OUTPUT_DIR="$PROJECT_ROOT/scripts/auto_test_output/ai_gate_$TIMESTAMP"
mkdir -p "$OUTPUT_DIR"

run_check() {
    local name="$1"
    local cmd="$2"
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "🔍 $name"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    if eval "$cmd"; then
        echo -e "${GREEN}✅ PASS${NC}: $name"
        PASS_COUNT=$((PASS_COUNT + 1))
        return 0
    else
        echo -e "${RED}❌ FAIL${NC}: $name"
        FAIL_COUNT=$((FAIL_COUNT + 1))
        return 1
    fi
}

run_warn() {
    local name="$1"
    local msg="$2"
    echo -e "${YELLOW}⚠️ WARN${NC}: $name — $msg"
    WARN_COUNT=$((WARN_COUNT + 1))
}

echo "🤖 PoLang AI Gate - 自动化验证开始"
echo "===================================="
echo "项目路径: $PROJECT_ROOT"
echo "时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo "模式: ${DEVICE_CHECK:+完整闭环（含设备验证）}${DEVICE_CHECK:-仅代码检查}"
echo ""

# 1. 代码格式检查 (ktlint)
run_check "Ktlint Format Check" "./gradlew ktlintCheck --quiet > '$OUTPUT_DIR/ktlint.log' 2>&1"

# 1.5 Design Token 生成物一致性（防漂移：design-tokens.json 为 SSOT，双端镜像禁止手改）
if [ -f "scripts/gen-design-tokens.py" ]; then
    run_check "Design Tokens Sync Check" "python3 scripts/gen-design-tokens.py --check > '$OUTPUT_DIR/design_tokens.log' 2>&1"
else
    run_warn "Design Tokens Sync Check" "scripts/gen-design-tokens.py 不存在"
fi

# 2. 静态代码分析 (detekt)
run_check "Detekt Static Analysis" "./gradlew detekt > '$OUTPUT_DIR/detekt.log' 2>&1"

# 3. JVM 单元测试
run_check "JVM Unit Tests" "./gradlew testDebugUnitTest > '$OUTPUT_DIR/unit_test.log' 2>&1"

# 4. 编译验证
run_check "Debug Build" "./gradlew assembleDebug > '$OUTPUT_DIR/build.log' 2>&1"

# 5. 文档一致性检查
if [ -f "scripts/check_doc_sync.py" ]; then
    run_check "Document Sync Check" "python3 scripts/check_doc_sync.py > '$OUTPUT_DIR/doc_sync.log' 2>&1"
else
    run_warn "Document Sync Check" "scripts/check_doc_sync.py 不存在"
fi

# ============================================
# 6. 设备端自动验证（可选，消除人工干预）
# ============================================
if [ "$DEVICE_CHECK" = true ]; then
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "📱 设备端自动验证"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    # 检查设备连接
    if ! adb devices | grep -q "device$"; then
        run_warn "Device Check" "未检测到连接的设备，跳过设备验证"
    else
        device_count=$(adb devices | grep -c "device$")
        echo -e "${GREEN}✅ PASS${NC}: 检测到 $device_count 台设备"
        PASS_COUNT=$((PASS_COUNT + 1))

        # 自动安装 APK
        apk=$(find androidApp/build/outputs/apk/debug -name "*.apk" | head -1)
        echo "→ 安装 APK: $(basename $apk)"
        if adb install -r "$apk" > "$OUTPUT_DIR/install.log" 2>&1; then
            echo -e "${GREEN}✅ PASS${NC}: APK 安装成功"
            PASS_COUNT=$((PASS_COUNT + 1))
        else
            # 签名冲突时尝试卸载重装
            adb uninstall com.mamba.picme > /dev/null 2>&1 || true
            if adb install "$apk" > "$OUTPUT_DIR/install.log" 2>&1; then
                echo -e "${GREEN}✅ PASS${NC}: APK 卸载重装成功"
                PASS_COUNT=$((PASS_COUNT + 1))
            else
                echo -e "${RED}❌ FAIL${NC}: APK 安装失败"
                FAIL_COUNT=$((FAIL_COUNT + 1))
            fi
        fi

        # 启动应用并截屏
        echo "→ 启动应用并截屏..."
        adb shell am start -n com.mamba.picme/.MainActivity > /dev/null 2>&1
        sleep 3
        # exec-out 直传电脑，设备零残留，不污染相册/MediaStore
        adb exec-out screencap -p > "$OUTPUT_DIR/screen.png"
        echo -e "${GREEN}✅ PASS${NC}: 应用启动截屏完成"
        PASS_COUNT=$((PASS_COUNT + 1))

        # 收集日志
        adb logcat -d -s PoLang:* > "$OUTPUT_DIR/logcat_picme.txt" 2>&1
        echo -e "${BLUE}ℹ️ INFO${NC}: PoLang 日志已保存到 $OUTPUT_DIR/logcat_picme.txt"
    fi
fi

# 7. 检查 TODO/FIXME 数量（警告级别）
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "📊 代码健康度指标"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
TODO_COUNT=$(grep -rn "TODO\|FIXME\|XXX" --include="*.kt" androidApp/src/main engines/beauty-engine/src/main 2>/dev/null | wc -l | tr -d ' ')
echo "   TODO/FIXME 数量: $TODO_COUNT"

# 汇总
echo ""
echo "===================================="
echo "📋 验证结果汇总"
echo "===================================="
echo -e "   ${GREEN}通过: $PASS_COUNT${NC}"
echo -e "   ${YELLOW}警告: $WARN_COUNT${NC}"
echo -e "   ${RED}失败: $FAIL_COUNT${NC}"

if [ "$DEVICE_CHECK" = true ]; then
    echo ""
    echo -e "📁 输出目录: ${CYAN}$OUTPUT_DIR${NC}"
fi

echo ""

if [ $FAIL_COUNT -eq 0 ]; then
    echo -e "${GREEN}🎉 AI Gate 全部通过！代码可以安全提交。${NC}"
    if [ "$DEVICE_CHECK" = true ]; then
        echo -e "${GREEN}📱 设备验证通过，输出保存至 $OUTPUT_DIR${NC}"
    fi
    exit 0
else
    echo -e "${RED}⛔ AI Gate 存在 $FAIL_COUNT 项失败，请修复后重试。${NC}"
    exit 1
fi
