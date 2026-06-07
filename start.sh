#!/bin/bash
# ============================================================
# HFT Trading System - 启动脚本 (Mac/Linux)
# ============================================================
# 用法:
#   ./start.sh [选项]
# 选项:
#   -m, --memory SIZE   设置内存大小 (默认: 2g)
#   -p, --profile NAME  Spring Profile (默认: prod)
#   -d, --debug         启用远程调试 (端口 5005)
#   -h, --help          显示帮助信息
# ============================================================

set -e

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 默认配置
MEMORY_SIZE="2g"
SPRING_PROFILE="prod"
DEBUG_MODE=false
DEBUG_PORT=5005

# 解析命令行参数
while [[ $# -gt 0 ]]; do
    case $1 in
        -m|--memory)
            MEMORY_SIZE="$2"
            shift 2
            ;;
        -p|--profile)
            SPRING_PROFILE="$2"
            shift 2
            ;;
        -d|--debug)
            DEBUG_MODE=true
            shift
            ;;
        -h|--help)
            echo "用法: $0 [选项]"
            echo "选项:"
            echo "  -m, --memory SIZE   设置内存大小 (默认: 2g)"
            echo "  -p, --profile NAME  Spring Profile (默认: prod)"
            echo "  -d, --debug         启用远程调试 (端口 5005)"
            echo "  -h, --help          显示帮助信息"
            exit 0
            ;;
        *)
            echo -e "${RED}未知参数: $1${NC}"
            exit 1
            ;;
    esac
done

# 检测操作系统
OS="$(uname -s)"
case "$OS" in
    Darwin*)    OS_NAME="Mac" ;;
    Linux*)     OS_NAME="Linux" ;;
    *)          OS_NAME="Unknown" ;;
esac
echo -e "${GREEN}检测到操作系统: $OS_NAME${NC}"

# 检查 Java 版本
if ! command -v java &> /dev/null; then
    echo -e "${RED}错误: 未找到 Java，请先安装 Java 17 或更高版本${NC}"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 17 ]; then
    echo -e "${RED}错误: Java 版本过低 ($JAVA_VERSION)，需要 Java 17 或更高版本${NC}"
    exit 1
fi
echo -e "${GREEN}Java 版本: $(java -version 2>&1 | head -n 1)${NC}"

# JAR 文件路径
JAR_FILE="target/hft-trading-1.0.0-SNAPSHOT.jar"
PID_FILE="hft-trading.pid"
LOG_DIR="logs"

# 检查是否已在运行
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE")
    if ps -p "$OLD_PID" > /dev/null 2>&1; then
        echo -e "${YELLOW}应用已在运行中 (PID: $OLD_PID)${NC}"
        echo "如需重启，请先运行: ./stop.sh"
        exit 1
    else
        echo -e "${YELLOW}发现残留 PID 文件，清理中...${NC}"
        rm -f "$PID_FILE"
    fi
fi

# 检查 JAR 文件是否存在
if [ ! -f "$JAR_FILE" ]; then
    echo -e "${YELLOW}JAR 文件不存在，正在构建...${NC}"
    if command -v mvn &> /dev/null; then
        mvn clean package -DskipTests
        if [ $? -ne 0 ]; then
            echo -e "${RED}构建失败${NC}"
            exit 1
        fi
    else
        echo -e "${RED}错误: 未找到 Maven，无法自动构建${NC}"
        echo "请手动运行: mvn clean package -DskipTests"
        exit 1
    fi
fi

# 创建日志目录
mkdir -p "$LOG_DIR"

# JVM 参数 - Chronicle Queue 需要
JVM_OPTS="--add-exports java.base/jdk.internal.ref=ALL-UNNAMED \
          --add-exports java.base/sun.nio.ch=ALL-UNNAMED \
          --add-exports java.base/java.lang.reflect=ALL-UNNAMED \
          --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
          --add-opens java.base/java.lang=ALL-UNNAMED \
          --add-opens java.base/java.util=ALL-UNNAMED \
          --add-opens java.base/java.nio=ALL-UNNAMED"

# 内存配置
MEMORY_OPTS="-Xms${MEMORY_SIZE} -Xmx${MEMORY_SIZE}"

# GC 配置
GC_OPTS="-XX:+UseZGC -XX:+ZGenerational"

# 调试选项
if [ "$DEBUG_MODE" = true ]; then
    DEBUG_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:${DEBUG_PORT}"
    echo -e "${YELLOW}远程调试已启用，端口: ${DEBUG_PORT}${NC}"
fi

# 应用参数
APP_OPTS="--spring.profiles.active=${SPRING_PROFILE}"

echo ""
echo "=========================================="
echo "启动配置:"
echo "  内存: ${MEMORY_SIZE}"
echo "  Profile: ${SPRING_PROFILE}"
echo "  JAR: ${JAR_FILE}"
echo "=========================================="
echo ""

# 启动应用
echo -e "${GREEN}正在启动 HFT Trading System...${NC}"
java $JVM_OPTS $MEMORY_OPTS $GC_OPTS $DEBUG_OPTS -jar "$JAR_FILE" $APP_OPTS > "$LOG_DIR/app.log" 2>&1 &

# 记录 PID
PID=$!
echo $PID > "$PID_FILE"

# 等待启动
sleep 2

# 检查是否启动成功
if ps -p $PID > /dev/null 2>&1; then
    echo -e "${GREEN}应用启动成功!${NC}"
    echo "  PID: $PID"
    echo "  日志: $LOG_DIR/app.log"
    echo "  访问: http://localhost:8080/"
else
    echo -e "${RED}应用启动失败，请检查日志: $LOG_DIR/app.log${NC}"
    rm -f "$PID_FILE"
    exit 1
fi
