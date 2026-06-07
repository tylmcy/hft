#!/bin/bash
# ============================================================
# HFT Trading System - 状态检查脚本 (Mac/Linux)
# ============================================================
# 用法:
#   ./status.sh
# ============================================================

set -e

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

PID_FILE="hft-trading.pid"
LOG_DIR="logs"

echo -e "${BLUE}=========================================="
echo "HFT Trading System - 状态检查"
echo -e "==========================================${NC}"
echo ""

# 检查 PID 文件
if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")

    if ps -p "$PID" > /dev/null 2>&1; then
        echo -e "状态:     ${GREEN}运行中${NC}"
        echo "PID:      $PID"

        # 获取进程信息
        echo ""
        echo -e "${BLUE}进程信息:${NC}"
        ps -p "$PID" -o pid,ppid,%cpu,%mem,vsz,rss,etime,command 2>/dev/null | tail -n +1

        # 检查端口
        echo ""
        echo -e "${BLUE}端口监听:${NC}"
        if command -v lsof &> /dev/null; then
            lsof -i :8080 -P -n 2>/dev/null | grep -v "COMMAND" || echo "未找到 8080 端口监听"
        elif command -v netstat &> /dev/null; then
            netstat -tlnp 2>/dev/null | grep ":8080" || echo "未找到 8080 端口监听"
        elif command -v ss &> /dev/null; then
            ss -tlnp 2>/dev/null | grep ":8080" || echo "未找到 8080 端口监听"
        fi

        # 检查 HTTP 健康端点
        echo ""
        echo -e "${BLUE}健康检查:${NC}"
        if command -v curl &> /dev/null; then
            HEALTH=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/health 2>/dev/null || echo "000")
            if [ "$HEALTH" = "200" ]; then
                echo -e "HTTP 端点: ${GREEN}正常${NC}"
            else
                echo -e "HTTP 端点: ${YELLOW}无响应 (HTTP $HEALTH)${NC}"
            fi
        fi

    else
        echo -e "状态:     ${RED}已停止${NC} (PID 文件存在但进程已终止)"
        echo "PID:      $PID (无效)"
        echo ""
        echo -e "${YELLOW}提示: 运行 'rm $PID_FILE' 清理残留文件${NC}"
    fi
else
    echo -e "状态:     ${RED}未运行${NC}"
    echo "PID:      无"

    # 检查是否有孤儿进程
    echo ""
    echo -e "${BLUE}检查孤儿进程:${NC}"
    ORPHANS=$(pgrep -f "hft-trading.*\.jar" 2>/dev/null || true)
    if [ -n "$ORPHANS" ]; then
        echo -e "${YELLOW}发现孤儿进程: $ORPHANS${NC}"
        echo "运行 './stop.sh' 来停止它们"
    else
        echo "未发现孤儿进程"
    fi
fi

# 日志文件
echo ""
echo -e "${BLUE}日志文件:${NC}"
if [ -d "$LOG_DIR" ]; then
    if [ -f "$LOG_DIR/app.log" ]; then
        LOG_SIZE=$(ls -lh "$LOG_DIR/app.log" | awk '{print $5}')
        echo "  app.log: $LOG_SIZE"

        # 显示最近的错误日志
        if [ -s "$LOG_DIR/app.log" ]; then
            echo ""
            echo -e "${BLUE}最近日志 (最后 5 行):${NC}"
            tail -5 "$LOG_DIR/app.log"
        fi
    else
        echo "  app.log: 不存在"
    fi
else
    echo "  日志目录不存在"
fi

echo ""
echo -e "${BLUE}==========================================${NC}"
