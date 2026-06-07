#!/bin/bash
# ============================================================
# HFT Trading System - 停止脚本 (Mac/Linux)
# ============================================================
# 用法:
#   ./stop.sh [选项]
# 选项:
#   -f, --force   强制杀死进程
#   -h, --help    显示帮助信息
# ============================================================

set -e

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 默认配置
FORCE_KILL=false

# 解析命令行参数
while [[ $# -gt 0 ]]; do
    case $1 in
        -f|--force)
            FORCE_KILL=true
            shift
            ;;
        -h|--help)
            echo "用法: $0 [选项]"
            echo "选项:"
            echo "  -f, --force   强制杀死进程"
            echo "  -h, --help    显示帮助信息"
            exit 0
            ;;
        *)
            echo -e "${RED}未知参数: $1${NC}"
            exit 1
            ;;
    esac
done

PID_FILE="hft-trading.pid"

echo -e "${GREEN}正在停止 HFT Trading System...${NC}"

if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")

    if ps -p "$PID" > /dev/null 2>&1; then
        echo "发现运行中的进程 (PID: $PID)"

        if [ "$FORCE_KILL" = true ]; then
            echo -e "${YELLOW}强制杀死进程...${NC}"
            kill -9 "$PID" 2>/dev/null || true
        else
            echo "发送 SIGTERM 信号..."
            kill "$PID"

            # 等待进程结束 (最多 20 秒)
            for i in {1..20}; do
                if ! ps -p "$PID" > /dev/null 2>&1; then
                    break
                fi
                sleep 1
                echo "等待进程结束... ($i/20)"
            done

            # 如果进程还在运行，强制杀死
            if ps -p "$PID" > /dev/null 2>&1; then
                echo -e "${YELLOW}进程未响应，强制杀死...${NC}"
                kill -9 "$PID" 2>/dev/null || true
            fi
        fi

        rm -f "$PID_FILE"
        echo -e "${GREEN}应用已停止${NC}"
    else
        echo -e "${YELLOW}PID 文件存在但进程已不存在，清理 PID 文件${NC}"
        rm -f "$PID_FILE"
    fi
else
    echo -e "${YELLOW}未找到 PID 文件，尝试通过进程名查找...${NC}"

    # 查找并杀死进程
    PIDS=$(pgrep -f "hft-trading.*\.jar" 2>/dev/null || true)

    if [ -n "$PIDS" ]; then
        echo "找到进程: $PIDS"

        if [ "$FORCE_KILL" = true ]; then
            echo -e "${YELLOW}强制杀死进程...${NC}"
            kill -9 $PIDS 2>/dev/null || true
        else
            kill $PIDS 2>/dev/null || true
            sleep 3

            # 检查是否还有残留进程
            REMAINING=$(pgrep -f "hft-trading.*\.jar" 2>/dev/null || true)
            if [ -n "$REMAINING" ]; then
                echo -e "${YELLOW}强制杀死残留进程...${NC}"
                kill -9 $REMAINING 2>/dev/null || true
            fi
        fi

        echo -e "${GREEN}应用已停止${NC}"
    else
        echo -e "${YELLOW}未找到运行中的进程${NC}"
    fi
fi
