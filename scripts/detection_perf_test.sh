#!/bin/bash
#
# Detection Performance Test - 人脸检测引擎 GPU 性能对比
# 用途：对比 ROI 和 Landmark 两个阶段在不同推理引擎下的 GPU 性能
# 调用：./scripts/detection_perf_test.sh
#

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

export OUTPUT_DIR="$PROJECT_ROOT/scripts/auto_test_output/detection_perf_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$OUTPUT_DIR"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${CYAN}  🎯 人脸检测引擎 GPU 性能对比测试${NC}"
echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""

# 检查设备
if ! adb devices | grep -q "device$"; then
    echo -e "${RED}❌ 未检测到设备${NC}"
    exit 1
fi

# 测试用例列表（简化版，直接列出所有组合）
TEST_CASES=(
    "DET10G|INSIGHTFACE_2D106|ROI_DET10G_Landmark_INSIGHTFACE"
    "MNN|INSIGHTFACE_2D106|ROI_MNN_Landmark_INSIGHTFACE"
    "MEDIAPIPE|INSIGHTFACE_2D106|ROI_MEDIAPIPE_Landmark_INSIGHTFACE"
    "DET10G|MNN|ROI_DET10G_Landmark_MNN"
    "DET10G|MEDIAPIPE|ROI_DET10G_Landmark_MEDIAPIPE"
    "DET10G|INSIGHTFACE_2D106|Full_DET10G_INSIGHTFACE"
    "MNN|MNN|Full_MNN_MNN"
    "MEDIAPIPE|MEDIAPIPE|Full_MEDIAPIPE_MEDIAPIPE"
)

# 测试函数
test_detection_performance() {
    local roi_detector="$1"
    local landmark_detector="$2"
    local test_name="$3"
    
    echo ""
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BLUE}🧪 测试：$test_name${NC}"
    echo -e "${BLUE}ROI: $roi_detector | Landmark: $landmark_detector${NC}"
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    
    # 清除日志
    adb logcat -c > /dev/null 2>&1 || true
    
    # 确保应用运行
    if ! adb shell pidof com.mamba.picme > /dev/null 2>&1; then
        adb shell am start -n com.mamba.picme/.MainActivity > /dev/null 2>&1
        sleep 3
    fi
    
    # 等待相机页面加载
    sleep 2
    
    # 触发多次检测（模拟连续拍照）
    local iterations=5
    local total_time=0
    
    for i in $(seq 1 $iterations); do
        echo -e "  ${YELLOW}迭代 $i/$iterations...${NC}"
        
        # 记录开始时间
        local start_time=$(date +%s%N)
        
        # 触发拍照（ui-driver 点击快门；替代已退役的 AgentTestBroadcastReceiver 广播，ADR-011）
        # 前提：debug 包 + 无障碍服务已启用 + 相机页在前台（快门 contentDescription 本地化，尝试 EN/zh）
        python3 "$(dirname "$0")/ui_driver.py" click --content-description "Shutter" > /dev/null 2>&1 || \
        python3 "$(dirname "$0")/ui_driver.py" click --content-description "快门" > /dev/null 2>&1 || \
            echo -e "  ${YELLOW}⚠️ ui-driver 快门点击失败（确认相机页前台 / 无障碍服务已启用 / debug 构建）${NC}"
        
        # 等待检测完成
        sleep 2
        
        # 记录结束时间
        local end_time=$(date +%s%N)
        
        # 计算耗时（毫秒）
        local elapsed=$(( (end_time - start_time) / 1000000 ))
        total_time=$((total_time + elapsed))
        
        # 提取检测耗时日志
        local detect_log=$(adb logcat -d -s PoLang:* 2>/dev/null | grep -E "detect elapsed|Face detected" | tail -5 || echo "")
        
        if [ -n "$detect_log" ]; then
            echo "$detect_log" | while read line; do
                echo "    $line"
            done
        fi
    done
    
    # 收集完整日志
    local log_file="$OUTPUT_DIR/${test_name}_logcat.txt"
    adb logcat -d -s PoLang:* > "$log_file" 2>&1
    
    # 从日志中提取详细性能数据
    local roi_time=$(grep -oE "ROI.*elapsed=([0-9]+)" "$log_file" 2>/dev/null | tail -1 | grep -oE "[0-9]+" || echo "0")
    local landmark_time=$(grep -oE "Landmark.*elapsed=([0-9]+)" "$log_file" 2>/dev/null | tail -1 | grep -oE "[0-9]+" || echo "0")
    local total_detect_time=$(grep -oE "Total detection time: ([0-9]+)" "$log_file" 2>/dev/null | tail -1 | grep -oE "[0-9]+" || echo "0")
    
    # 平均每次检测时间
    local avg_time=$((total_time / iterations))
    
    echo ""
    echo -e "  ${GREEN}测试结果:${NC}"
    echo -e "    平均总耗时：${avg_time}ms (${iterations}次平均)"
    [ "$roi_time" != "0" ] && echo -e "    ROI 检测耗时：${roi_time}ms"
    [ "$landmark_time" != "0" ] && echo -e "    Landmark 检测耗时：${landmark_time}ms"
    [ "$total_detect_time" != "0" ] && echo -e "    总检测耗时：${total_detect_time}ms"
    
    # 保存到 JSON
    local result_json="$OUTPUT_DIR/${test_name}_result.json"
    cat > "$result_json" << EOF
{
  "test_name": "$test_name",
  "roi_detector": "$roi_detector",
  "landmark_detector": "$landmark_detector",
  "iterations": $iterations,
  "average_total_time_ms": $avg_time,
  "roi_time_ms": $roi_time,
  "landmark_time_ms": $landmark_time,
  "total_detect_time_ms": $total_detect_time,
  "timestamp": "$(date -Iseconds)"
}
EOF
    
    echo "  ✅ 结果已保存：$result_json"
}

# 执行所有测试
echo -e "${CYAN}开始执行测试...${NC}"

for test_case in "${TEST_CASES[@]}"; do
    IFS='|' read -r roi_detector landmark_detector test_name <<< "$test_case"
    test_detection_performance "$roi_detector" "$landmark_detector" "$test_name"
done

# 生成汇总报告
echo ""
echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${CYAN}📊 生成性能对比报告...${NC}"
echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"

python3 << 'PYTHON_SCRIPT'
import json
import os
from datetime import datetime

output_dir = os.environ.get('OUTPUT_DIR', '/Users/guoshuai/AndroidStudioProjects/PoLang/scripts/auto_test_output')
results = []

# 读取所有结果文件
for filename in os.listdir(output_dir):
    if filename.endswith('_result.json'):
        with open(os.path.join(output_dir, filename), 'r') as f:
            results.append(json.load(f))

# 按平均耗时排序
results.sort(key=lambda x: x['average_total_time_ms'])

# 生成 Markdown 报告
report_file = os.path.join(output_dir, 'performance_report.md')
with open(report_file, 'w') as f:
    f.write("# 🎯 人脸检测引擎 GPU 性能对比报告\n\n")
    f.write(f"**生成时间**: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n\n")
    
    f.write("## 📈 性能排名（耗时由低到高）\n\n")
    f.write("| 排名 | 测试名称 | ROI 检测器 | Landmark 检测器 | 平均耗时 | FPS 估算 |\n")
    f.write("|------|----------|------------|-----------------|----------|---------|\n")
    
    for i, result in enumerate(results, 1):
        avg_time = result['average_total_time_ms']
        fps = round(1000 / avg_time, 2) if avg_time > 0 else 0
        f.write(f"| {i} | {result['test_name']} | {result['roi_detector']} | {result['landmark_detector']} | {avg_time}ms | {fps} |\n")
    
    f.write("\n## 🔬 详细数据\n\n")
    
    for result in results:
        f.write(f"### {result['test_name']}\n\n")
        f.write(f"- **ROI 检测器**: {result['roi_detector']}\n")
        f.write(f"- **Landmark 检测器**: {result['landmark_detector']}\n")
        f.write(f"- **平均总耗时**: {result['average_total_time_ms']}ms\n")
        f.write(f"- **ROI 耗时**: {result.get('roi_time_ms', 'N/A')}ms\n")
        f.write(f"- **Landmark 耗时**: {result.get('landmark_time_ms', 'N/A')}ms\n")
        f.write(f"- **迭代次数**: {result['iterations']}\n")
        f.write(f"- **FPS 估算**: {round(1000 / result['average_total_time_ms'], 2) if result['average_total_time_ms'] > 0 else 0}\n\n")
    
    # 最佳性能分析
    best = results[0]
    f.write("## 🏆 最佳性能\n\n")
    f.write(f"**最优组合**: {best['test_name']}\n\n")
    f.write(f"- ROI 检测器：{best['roi_detector']}\n")
    f.write(f"- Landmark 检测器：{best['landmark_detector']}\n")
    f.write(f"- 平均耗时：{best['average_total_time_ms']}ms\n")
    f.write(f"- 理论 FPS：{round(1000 / best['average_total_time_ms'], 2)}\n\n")

print(f"✅ 报告已生成：{report_file}")
PYTHON_SCRIPT

export OUTPUT_DIR
python3 << 'PYTHON_SCRIPT'
import json
import os
from datetime import datetime

output_dir = os.environ.get('OUTPUT_DIR', '/Users/guoshuai/AndroidStudioProjects/PoLang/scripts/auto_test_output')
results = []

# 读取所有结果文件
for filename in os.listdir(output_dir):
    if filename.endswith('_result.json'):
        with open(os.path.join(output_dir, filename), 'r') as f:
            results.append(json.load(f))

# 按平均耗时排序
results.sort(key=lambda x: x['average_total_time_ms'])

# 生成 Markdown 报告
report_file = os.path.join(output_dir, 'performance_report.md')
with open(report_file, 'w') as f:
    f.write("# 🎯 人脸检测引擎 GPU 性能对比报告\n\n")
    f.write(f"**生成时间**: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n\n")
    
    f.write("## 📈 性能排名（耗时由低到高）\n\n")
    f.write("| 排名 | 测试名称 | ROI 检测器 | Landmark 检测器 | 平均耗时 | FPS 估算 |\n")
    f.write("|------|----------|------------|-----------------|----------|---------|\n")
    
    for i, result in enumerate(results, 1):
        avg_time = result['average_total_time_ms']
        fps = round(1000 / avg_time, 2) if avg_time > 0 else 0
        f.write(f"| {i} | {result['test_name']} | {result['roi_detector']} | {result['landmark_detector']} | {avg_time}ms | {fps} |\n")
    
    f.write("\n## 🔬 详细数据\n\n")
    
    for result in results:
        f.write(f"### {result['test_name']}\n\n")
        f.write(f"- **ROI 检测器**: {result['roi_detector']}\n")
        f.write(f"- **Landmark 检测器**: {result['landmark_detector']}\n")
        f.write(f"- **平均总耗时**: {result['average_total_time_ms']}ms\n")
        f.write(f"- **ROI 耗时**: {result.get('roi_time_ms', 'N/A')}ms\n")
        f.write(f"- **Landmark 耗时**: {result.get('landmark_time_ms', 'N/A')}ms\n")
        f.write(f"- **迭代次数**: {result['iterations']}\n")
        f.write(f"- **FPS 估算**: {round(1000 / result['average_total_time_ms'], 2) if result['average_total_time_ms'] > 0 else 0}\n\n")
    
    # 最佳性能分析
    best = results[0]
    f.write("## 🏆 最佳性能\n\n")
    f.write(f"**最优组合**: {best['test_name']}\n\n")
    f.write(f"- ROI 检测器：{best['roi_detector']}\n")
    f.write(f"- Landmark 检测器：{best['landmark_detector']}\n")
    f.write(f"- 平均耗时：{best['average_total_time_ms']}ms\n")
    f.write(f"- 理论 FPS：{round(1000 / best['average_total_time_ms'], 2)}\n\n")

print(f"✅ 报告已生成：{report_file}")
PYTHON_SCRIPT

echo ""
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${GREEN}✅ 测试完成！${NC}"
echo -e "${GREEN}报告位置：$OUTPUT_DIR/performance_report.md${NC}"
echo -e "${GREEN}原始数据：$OUTPUT_DIR/*.json${NC}"
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
