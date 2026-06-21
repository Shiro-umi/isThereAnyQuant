#!/bin/bash

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(dirname "$SCRIPT_DIR")"
PROJECT_ROOT="$(cd "$DEPLOY_DIR/../../.." && pwd)"
APP_NAME="strategy-service"
SERVICE_BIN="${DEPLOY_DIR}/strategy-service/bin/strategy-service"
PID_FILE="${DEPLOY_DIR}/.${APP_NAME}.pid"
LOG_DIR="${DEPLOY_DIR}/logs"
DATA_DIR="${DEPLOY_DIR}/data"
CONFIG_DIR="${DEPLOY_DIR}/config"
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
    export STRATEGY_SOCKET_BIND_HOST="${STRATEGY_SOCKET_BIND_HOST:-127.0.0.1}"
fi

# 盈利预测推理子进程端口按部署族隔离：release=9875、其余(debug/debug-wan)=9874。
# 两族共用同一端口时，先启动族的推理进程会被后启动族复用；推理进程在 service 被杀后可能
# 残留为孤儿继续占端口（健康检查已校验模型身份，错配会快速失败而非静默用错模型）。
if [ -z "${PROFIT_PREDICTION_INFER_PORT:-}" ]; then
    case "$QUANT_MODE" in
        release|prod|production) export PROFIT_PREDICTION_INFER_PORT=9875 ;;
        *)                       export PROFIT_PREDICTION_INFER_PORT=9874 ;;
    esac
fi

JVM_OPTS="${JVM_OPTS:-"
    -server
    -Xms256m
    -Xmx2g
    -XX:+UseG1GC
    -XX:MaxGCPauseMillis=200
    -Djava.awt.headless=true
    -Dfile.encoding=UTF-8
"}"

APP_OPTS="${APP_OPTS:-"
    -Dconfig.file=${CONFIG_DIR}/config.yaml
    -Dlogback.configurationFile=${CONFIG_DIR}/logback.xml
    -Dserver.data.dir=${DATA_DIR}
    -Dquant.project.root=${PROJECT_ROOT}
    -Dquant.profitPrediction.servicePort=${PROFIT_PREDICTION_INFER_PORT}
    -Dquant.strategy.holding.breakdownRerank=true
    -Dquant.strategy.entryBackfill.enabled=true
    -Dquant.strategy.entryBackfill.modelKey=deepseek-v4-flash
"}"

mkdir -p "$LOG_DIR"
mkdir -p "$DATA_DIR"
cd "$DEPLOY_DIR"

if [ ! -x "$SERVICE_BIN" ]; then
    echo "Error: strategy-service binary not found: $SERVICE_BIN"
    echo "Please run './gradlew :ktor-server:prepareDeploy' first"
    exit 1
fi

timestamp() {
    date +"%Y-%m-%d %H:%M:%S"
}

read_strategy_config_value() {
    local key="$1"
    awk -v key="$key" '
        function trim(value) {
            gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
            gsub(/^"|"$/, "", value)
            return value
        }
        /^[[:space:]]*#/ || /^[[:space:]]*$/ { next }
        /^strategy:/ {
            in_strategy=1
            next
        }
        in_strategy && /^[^[:space:]][^:]*:/ {
            in_strategy=0
        }
        in_strategy && $0 ~ "^[[:space:]]{2}" key ":" {
            sub(/^[^:]*:/, "", $0)
            print trim($0)
            exit
        }
    ' "${CONFIG_DIR}/config.yaml"
}

check_running() {
    if [ -f "$PID_FILE" ]; then
        local pid
        pid=$(cat "$PID_FILE")
        if ps -p "$pid" > /dev/null 2>&1; then
            return 0
        fi
    fi
    return 1
}

start() {
    if check_running; then
        echo "$(timestamp) ${APP_NAME} is already running (PID: $(cat "$PID_FILE"))"
        exit 1
    fi

    echo "$(timestamp) Starting ${APP_NAME}..."
    echo "   Mode: $QUANT_MODE"
    echo "   Bin:  $SERVICE_BIN"
    echo "   Log:  $LOG_DIR/${APP_NAME}.stdout.log"

    nohup env JAVA_OPTS="$JVM_OPTS $APP_OPTS" "$SERVICE_BIN" \
        > "$LOG_DIR/${APP_NAME}.stdout.log" 2>&1 < /dev/null &
    local pid=$!
    echo "$pid" > "$PID_FILE"

    sleep 2

    if ps -p "$pid" > /dev/null 2>&1; then
        echo "$(timestamp) ${APP_NAME} started successfully (PID: $pid)"
    else
        echo "$(timestamp) Failed to start ${APP_NAME}"
        rm -f "$PID_FILE"
        exit 1
    fi
}

stop() {
    if ! check_running; then
        echo "$(timestamp) ${APP_NAME} is not running"
        rm -f "$PID_FILE"
        return 0
    fi

    local pid
    pid=$(cat "$PID_FILE")
    echo "$(timestamp) Stopping ${APP_NAME} (PID: $pid)..."
    kill "$pid" 2>/dev/null || true

    local count=0
    while ps -p "$pid" > /dev/null 2>&1 && [ "$count" -lt 30 ]; do
        sleep 1
        count=$((count + 1))
        echo "   Waiting... ($count/30)"
    done

    if ps -p "$pid" > /dev/null 2>&1; then
        echo "   Force stopping..."
        kill -9 "$pid" 2>/dev/null || true
    fi

    rm -f "$PID_FILE"
    echo "$(timestamp) ${APP_NAME} stopped"
}

restart() {
    stop
    sleep 2
    start
}

status() {
    if check_running; then
        local pid
        pid=$(cat "$PID_FILE")
        echo "${APP_NAME} is running (PID: $pid)"
        echo "   Uptime: $(ps -o etime= -p "$pid" | tr -d ' ')"
        echo "   Memory: $(ps -o rss= -p "$pid" | awk '{print int($1/1024)"MB"}')"
    else
        echo "${APP_NAME} is not running"
        rm -f "$PID_FILE"
    fi
}

health() {
    if ! check_running; then
        echo "${APP_NAME} is not running"
        rm -f "$PID_FILE"
        exit 1
    fi

    local host
    local port
    host="${STRATEGY_SOCKET_HOST:-$(read_strategy_config_value strategyServiceHost)}"
    host="${host:-127.0.0.1}"
    port="${STRATEGY_SOCKET_PORT:-$(read_strategy_config_value strategyServicePort)}"
    port="${port:-9971}"

    local request_id
    if command -v uuidgen >/dev/null 2>&1; then
        request_id="$(uuidgen)"
    elif [ -r /proc/sys/kernel/random/uuid ]; then
        request_id="$(cat /proc/sys/kernel/random/uuid)"
    else
        request_id="health-$$-$(date +%s)"
    fi

    local frame
    frame=$(printf '{"frameType":"command","requestId":"%s","contractVersion":1,"command":{"frameType":"health-check"}}' "$request_id")

    if ! exec 3<>"/dev/tcp/${host}/${port}"; then
        echo "strategy-service health FAILED: cannot connect to ${host}:${port}" >&2
        exit 1
    fi
    printf '%s\n' "$frame" >&3

    local line accepted="" message="" source_id="" contract_version=""
    set +e
    local attempts=0
    while [ "$attempts" -lt 16 ]; do
        attempts=$((attempts + 1))
        IFS= read -r -t 2 -u 3 line
        local rc=$?
        if [ $rc -ne 0 ]; then
            break
        fi
        [ -z "$line" ] && continue
        case "$line" in
            *"\"frameType\":\"ack\""*"\"requestId\":\"${request_id}\""*) ;;
            *"\"requestId\":\"${request_id}\""*"\"frameType\":\"ack\""*) ;;
            *) continue ;;
        esac

        accepted=""
        case "$line" in
            *'"accepted":true'*|*'"accepted": true'*) accepted="true" ;;
            *'"accepted":false'*|*'"accepted": false'*) accepted="false" ;;
        esac
        source_id=$(printf '%s' "$line" | sed -n 's/.*"sourceInstanceId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
        contract_version=$(printf '%s' "$line" | sed -n 's/.*"contractVersion"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p')
        message=$(printf '%s' "$line" | sed -n 's/.*"message"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
        break
    done
    set -e

    exec 3<&-
    exec 3>&-

    if [ -z "$accepted" ]; then
        echo "strategy-service health FAILED: no HealthCheck ack received" >&2
        exit 1
    fi
    if [ "$accepted" = "true" ]; then
        echo "strategy-service health OK source=${source_id} contractVersion=${contract_version} message=${message}"
        exit 0
    fi
    echo "strategy-service health FAILED source=${source_id} contractVersion=${contract_version} message=${message}" >&2
    exit 1
}

logs() {
    local log_file="${LOG_DIR}/${APP_NAME}.stdout.log"
    if [ -f "$log_file" ]; then
        tail -f "$log_file"
    else
        echo "Log file not found: $log_file"
        exit 1
    fi
}

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
    health)
        health
        ;;
    logs)
        logs
        ;;
    help|--help|-h)
        echo "Usage: $0 {start|stop|restart|status|health|logs|help}"
        ;;
    *)
        echo "Unknown command: $1"
        echo "Usage: $0 {start|stop|restart|status|health|logs|help}"
        exit 1
        ;;
esac
