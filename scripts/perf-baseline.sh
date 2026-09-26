#!/bin/bash
#
# Perf Baseline - PoLang 性能基线提取与对比工具
# 用途: 从 logcat 提取性能指标，与基线对比，自动告警性能回归
# 调用: ./scripts/perf-baseline.sh [options]
#
# Options:
#   --capture         执行一次拍照并提取性能数据
#   --baseline        将当前数据设为新的性能基线
#   --compare         与基线对比（默认）
#   --output <dir>    输出目录
#
# 示例:
#   ./scripts/perf-baseline.sh --capture              # 执行拍照并提取性能
#   ./scripts/perf-baseline.sh --baseline             # 设为基线
#   ./scripts/perf-baseline.sh --compare              # 与基线对比
#

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

MODE="compare"
OUTPUT_DIR="$PROJECT_ROOT/scripts/auto_test_output/perf_$(date +%Y%m%d_%H%M%S)"

while [[ $# -gt 0 ]]; do
    case $1 in
        --capture) MODE="capture"; shift ;;
        --baseline) MODE="baseline"; shift ;;
        --compare) MODE="compare"; shift ;;
        --output) OUTPUT_DIR="$2"; shift 2 ;;
        *) echo "未知参数: $1"; exit 1 ;;
    esac
done

mkdir -p "$OUTPUT_DIR"
mkdir -p "$PROJECT_ROOT/scripts/auto_test_output/baseline"

BASELINE_FILE="$PROJECT_ROOT/scripts/auto_test_output/baseline/perf_baseline.json"

# 性能指标定义
# 格式: 指标名|日志标签|提取正则|单位|基线值|告警阈值(>基线*阈值)
declare -a METRICS=(
    "gpu_photo_process|PoLang:PhotoProcessor|process DONE: elapsed=([0-9]+)|ms|200|1.5"
    "preview_fps|PoLang:Camera|fps=([0-9.]+)|fps|30|0.8"
    "frame_delay|PoLang:BeautyRenderer|delay=([0-9]+)|ms|50|2.0"
    "processing_time|PoLang:BeautyRenderer|processingMs=([0-9]+)|ms|33|2.0"
    "face_detect_time|PoLang:FaceDetector|detect elapsed=([0-9]+)|ms|100|2.0"
    "startup_time|PoLang:App|cold start elapsed=([0-9]+)|ms|500|1.5"
)

# 从 logcat 提取性能数据
extract_perf_data() {
    local logcat_file="$1"
    local output_json="$2"
    
    echo "{"
    echo "  \"timestamp\": \"$(date -Iseconds)\","
    echo "  \"source\": \"$logcat_file\","
    echo "  \"metrics\": {"
    
    local first=true
    for metric in "${METRICS[@]}"; do
        IFS='|' read -r name tag pattern unit baseline threshold <<< "$metric"
        
        # 从日志提取数值
        local value=$(grep -E "$pattern" "$logcat_file" 2>/dev/null | tail -1 | grep -oE "[0-9]+\.?[0-9]*" | head -1 || echo "")
        
        if [ -n "$value" ]; then
            if [ "$first" = true ]; then
                first=false
            else
                echo ","
            fi
            echo -n "    \"$name\": { \"value\": $value, \"unit\": \"$unit\", \"baseline\": $baseline, \"threshold\": $threshold }"
        fi
    done
    
    echo ""
    echo "  }"
    echo "}"
}

# 对比性能数据
compare_with_baseline() {
    local current_file="$1"
    local baseline_file="$2"
    
    if [ ! -f "$baseline_file" ]; then
        echo "⚠️ 基线文件不存在，请先执行 --baseline 建立基线"
        return 1
    fi
    
    echo ""
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${CYAN}  📊 PoLang 性能基线对比报告${NC}"
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo ""
    
    local pass_count=0
    local fail_count=0
    local warn_count=0
    
    # 使用 Python 进行 JSON 对比
    python3 << EOF
import json
import sys

with open("$baseline_file", 'r') as f:
    baseline = json.load(f)

with open("$current_file", 'r') as f:
    current = json.load(f)

baseline_metrics = baseline.get("metrics", {})
current_metrics = current.get("metrics", {})

pass_count = 0
fail_count = 0
warn_count = 0

for name, current_data in current_metrics.items():
    baseline_data = baseline_metrics.get(name)
    if not baseline_data:
        print(f"  ⚠️  {name}: 无基线数据")
        warn_count += 1
        continue
    
    current_val = current_data["value"]
    baseline_val = baseline_data["value"]
    unit = current_data["unit"]
    threshold = baseline_data["threshold"]
    
    # 计算变化比例
    if baseline_val > 0:
        ratio = current_val / baseline_val
    else:
        ratio = 1.0
    
    # 判断方向：某些指标越低越好（耗时），某些越高越好（FPS）
    is_higher_better = (unit == "fps")
    
    if is_higher_better:
        status = "✅ PASS" if ratio >= threshold else ("⚠️ WARN" if ratio >= 0.9 else "❌ FAIL")
        change = f"{ratio:.2f}x"
    else:
        status = "✅ PASS" if ratio <= threshold else ("⚠️ WARN" if ratio <= threshold * 1.2 else "❌ FAIL")
        change = f"{ratio:.2f}x"
    
    if "PASS" in status:
        pass_count += 1
    elif "WARN" in status:
        warn_count += 1
    else:
        fail_count += 1
    
    color = "\033[0;32m" if "PASS" in status else ("\033[1;33m" if "WARN" in status else "\033[0;31m")
    reset = "\033[0m"
    print(f"  {color}{status}{reset} {name}: {current_val}{unit} (基线: {baseline_val}{unit}, 变化: {change})")

# 统计基线中有但当前没有的数据
for name in baseline_metrics:
    if name not in current_metrics:
        print(f"  ⚠️  {name}: 当前数据中缺失")
        warn_count += 1

print("")
print(f"  通过: {pass_count} | 警告: {warn_count} | 失败: {fail_count}")

if fail_count > 0:
    sys.exit(1)
EOF
    
    return $?
}

# Capture 模式：执行拍照并收集日志
capture_perf_data() {
    echo "📱 执行拍照并收集性能数据..."
    
    # 检查设备
    if ! adb devices | grep -q "device$"; then
        echo "❌ 未检测到设备"
        exit 1
    fi
    
    # 清除日志
    adb logcat -c > /dev/null 2>&1 || true
    
    # 确保应用运行
    if ! adb shell pidof com.mamba.picme > /dev/null 2>&1; then
        adb shell am start -n com.mamba.picme/.MainActivity > /dev/null 2>&1
        sleep 3
    fi
    
    # 触发拍照（ui-driver 点击快门；替代已退役的 AgentTestBroadcastReceiver 广播，ADR-011）
    # 前提：debug 包 + 无障碍服务已启用 + 相机页在前台（快门 contentDescription 本地化，尝试 EN/zh）
    python3 "$(dirname "$0")/ui_driver.py" click --content-description "Shutter" > /dev/null 2>&1 || \
    python3 "$(dirname "$0")/ui_driver.py" click --content-description "快门" > /dev/null 2>&1 || \
        echo "⚠️ ui-driver 快门点击失败（确认相机页前台 / 无障碍服务已启用 / debug 构建）"
    sleep 3
    
    # 收集日志
    local logcat_file="$OUTPUT_DIR/logcat_perf.txt"
    adb logcat -d -s PoLang:* > "$logcat_file" 2>&1
    
    # 提取性能数据
    local perf_json="$OUTPUT_DIR/perf_data.json"
    extract_perf_data "$logcat_file" > "$perf_json"
    
    echo "✅ 性能数据已保存: $perf_json"
    
    # 显示提取的数据
    echo ""
    echo -e "${CYAN}提取的指标:${NC}"
    python3 -c "import json; data=json.load(open('$perf_json')); [print(f'  {k}: {v[\"value\"]}{v[\"unit\"]}') for k,v in data['metrics'].items()]" 2>/dev/null || true
    
    echo "$perf_json"
}

# 主流程
case "$MODE" in
    capture)
        perf_file=$(capture_perf_data)
        echo ""
        echo "💡 提示: 如果数据正常，执行 './scripts/perf-baseline.sh --baseline' 设为基线"
        ;;
    
    baseline)
        # 查找最新的 perf 数据
        latest_perf=$(find "$PROJECT_ROOT/scripts/auto_test_output" -name "perf_data.json" -type f 2>/dev/null | sort | tail -1)
        if [ -z "$latest_perf" ]; then
            echo "❌ 未找到 perf_data.json，请先执行 --capture"
            exit 1
        fi
        
        cp "$latest_perf" "$BASELINE_FILE"
        echo "✅ 基线已建立: $BASELINE_FILE"
        echo ""
        echo -e "${CYAN}基线数据:${NC}"
        python3 -c "import json; data=json.load(open('$BASELINE_FILE')); [print(f'  {k}: {v[\"value\"]}{v[\"unit\"]}') for k,v in data['metrics'].items()]" 2>/dev/null || true
        ;;
    
    compare)
        latest_perf=$(find "$PROJECT_ROOT/scripts/auto_test_output" -name "perf_data.json" -type f 2>/dev/null | sort | tail -1)
        if [ -z "$latest_perf" ]; then
            echo "未找到 perf_data.json，先执行 capture..."
            latest_perf=$(capture_perf_data)
        fi
        
        compare_with_baseline "$latest_perf" "$BASELINE_FILE"
        ;;
esac
