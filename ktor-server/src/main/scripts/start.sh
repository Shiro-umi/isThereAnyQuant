#!/bin/bash

# =============================================================================
# Quant Server Startup Script
# =============================================================================

set -e

# 脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 部署根目录
DEPLOY_DIR="$(dirname "$SCRIPT_DIR")"
# 项目根目录（deploy 位于 ktor-server/build/deploy，往上三级即项目根）
PROJECT_ROOT="$(cd "$DEPLOY_DIR/../../.." && pwd)"
# 应用名称
APP_NAME="quant-server"
# JAR文件路径
JAR_FILE="${DEPLOY_DIR}/lib/${APP_NAME}.jar"
# PID文件路径
PID_FILE="${DEPLOY_DIR}/.${APP_NAME}.pid"
# 日志目录
LOG_DIR="${DEPLOY_DIR}/logs"
# 数据目录
DATA_DIR="${DEPLOY_DIR}/data"
# 配置目录
CONFIG_DIR="${DEPLOY_DIR}/config"
# 部署模式：具体 host/port 来自 config.yaml 的 deployment.modes
QUANT_MODE="${QUANT_MODE:-release}"
export QUANT_MODE

if [ -z "${STRATEGY_SOCKET_PORT:-}" ]; then
    _base_port="$(awk '
        function trim(v){gsub(/^[[:space:]]+|[[:space:]]+$/,"",v);gsub(/^"|"$/,"",v);return v}
        /^strategy:/{s=1;next} s&&/^[^[:space:]]/{s=0} s&&/strategyServicePort:/{sub(/^[^:]*:/,"",$0);print trim($0);exit}
    ' "${CONFIG_DIR}/config.yaml" 2>/dev/null)"
    _base_port="${_base_port:-9971}"
    case "$QUANT_MODE" in
        release|prod|production) export STRATEGY_SOCKET_PORT="$_base_port" ;;
        *)                       export STRATEGY_SOCKET_PORT=$((_base_port + 1)) ;;
    esac
fi

# JVM参数
JVM_OPTS="${JVM_OPTS:-"
    -server
    -Xms512m
    -Xmx8g
    -XX:+UseG1GC
    -XX:MaxGCPauseMillis=200
    -XX:+ParallelRefProcEnabled
    -XX:+DisableExplicitGC
    -Djava.awt.headless=true
    -Dfile.encoding=UTF-8
"}"

# 应用参数
APP_OPTS="${APP_OPTS:-"
    -Dconfig.file=${CONFIG_DIR}/config.yaml
    -Dlogback.configurationFile=${CONFIG_DIR}/logback.xml
    -Dserver.data.dir=${DATA_DIR}
    -Dquant.project.root=${PROJECT_ROOT}
"}"

# 创建必要的目录
mkdir -p "$LOG_DIR"
mkdir -p "$DATA_DIR"
cd "$DEPLOY_DIR"

# 检查JAR文件是否存在
if [ ! -f "$JAR_FILE" ]; then
    echo "❌ Error: JAR file not found: $JAR_FILE"
    echo "Please run './gradlew :ktor-server:prepareDeploy' first"
    exit 1
fi

# 获取当前时间戳
timestamp() {
    date +"%Y-%m-%d %H:%M:%S"
}

# 检查是否已经在运行
check_running() {
    if [ -f "$PID_FILE" ]; then
        local pid=$(cat "$PID_FILE")
        if ps -p "$pid" > /dev/null 2>&1; then
            return 0
        fi
    fi
    return 1
}

# 启动应用
start() {
    if check_running; then
        echo "⚠️  $(timestamp) ${APP_NAME} is already running (PID: $(cat "$PID_FILE"))"
        exit 1
    fi

    echo "🚀 $(timestamp) Starting ${APP_NAME}..."
    echo "   Mode: $QUANT_MODE"
    echo "   JAR: $JAR_FILE"
    echo "   Log: $LOG_DIR/server.log"
    
    # logback writes application logs; stdout is kept for early startup failures.
    nohup java $JVM_OPTS $APP_OPTS -jar "$JAR_FILE" > "$LOG_DIR/stdout.log" 2>&1 < /dev/null &
    local pid=$!
    echo $pid > "$PID_FILE"
    
    # 等待应用启动
    sleep 3
    
    if ps -p "$pid" > /dev/null 2>&1; then
        echo "✅ $(timestamp) ${APP_NAME} started successfully (PID: $pid)"
        echo "   Check logs: tail -f ${LOG_DIR}/server.log"
    else
        echo "❌ $(timestamp) Failed to start ${APP_NAME}"
        rm -f "$PID_FILE"
        exit 1
    fi
}

# 停止应用
stop() {
    if ! check_running; then
        echo "⚠️  $(timestamp) ${APP_NAME} is not running"
        rm -f "$PID_FILE"
        return 0
    fi

    local pid=$(cat "$PID_FILE")
    echo "🛑 $(timestamp) Stopping ${APP_NAME} (PID: $pid)..."
    
    # 尝试优雅停止
    kill "$pid" 2>/dev/null || true
    
    # 等待进程结束
    local count=0
    while ps -p "$pid" > /dev/null 2>&1 && [ $count -lt 30 ]; do
        sleep 1
        count=$((count + 1))
        echo "   Waiting... ($count/30)"
    done
    
    # 强制停止
    if ps -p "$pid" > /dev/null 2>&1; then
        echo "   Force stopping..."
        kill -9 "$pid" 2>/dev/null || true
    fi
    
    rm -f "$PID_FILE"
    echo "✅ $(timestamp) ${APP_NAME} stopped"
}

# 重启应用
restart() {
    stop
    sleep 2
    start
}

# 查看状态
status() {
    if check_running; then
        local pid=$(cat "$PID_FILE")
        echo "✅ ${APP_NAME} is running (PID: $pid)"
        echo "   Uptime: $(ps -o etime= -p "$pid" | tr -d ' ')"
        echo "   Memory: $(ps -o rss= -p "$pid" | awk '{print int($1/1024)"MB"}')"
    else
        echo "⭕ ${APP_NAME} is not running"
        rm -f "$PID_FILE"
    fi
}

# 查看日志
logs() {
    local log_file="${LOG_DIR}/server.log"
    if [ -f "$log_file" ]; then
        tail -f "$log_file"
    else
        echo "❌ Log file not found: $log_file"
        exit 1
    fi
}

# 显示帮助
help() {
    cat << EOF
Quant Server Management Script

Usage: $0 {start|stop|restart|status|logs|help}

Commands:
    start     Start the server
    stop      Stop the server
    restart   Restart the server
    status    Check server status
    logs      View server logs (tail -f)
    help      Show this help message

Environment Variables:
    JVM_OPTS    JVM options (default: see script)
    APP_OPTS    Application options (default: see script)
    QUANT_MODE  Deployment mode: debug, debug-wan or release (default: release)

Examples:
    $0 start              # Start the server
    QUANT_MODE=debug $0 start
    QUANT_MODE=debug-wan $0 start
    $0 stop               # Stop the server
    $0 restart            # Restart the server
    $0 logs               # View logs

EOF
}

# 主逻辑
case "${1:-help}" in
    start)
        start
        ;;
    stop)
        stop
        ;;
    restart)
        restart
        ;;
    status)
        status
        ;;
    logs)
        logs
        ;;
    help|--help|-h)
        help
        ;;
    *)
        echo "❌ Unknown command: $1"
        help
        exit 1
        ;;
esac
