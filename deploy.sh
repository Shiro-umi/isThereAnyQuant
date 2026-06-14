#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# 默认模式
MODE_RAW="${1:-release}"
CONFIG_FILE="$SCRIPT_DIR/config.yaml"
STRATEGY_SERVICE_ONLY=false
STRATEGY_SERVICE_ACTION=""

read_deployment_port() {
    local mode="$1"
    awk -v mode="$mode" '
        function trim(value) {
            gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
            gsub(/^"|"$/, "", value)
            return value
        }
        /^[[:space:]]*#/ || /^[[:space:]]*$/ { next }
        /^[^[:space:]][^:]*:/ {
            split($0, top, ":")
            section=trim(top[1])
            next
        }
        section == "deployment" && $0 ~ "^[[:space:]]{4}" mode ":" {
            in_mode=1
            next
        }
        in_mode && $0 ~ /^[[:space:]]{4}[^[:space:]][^:]*:/ {
            in_mode=0
        }
        in_mode && $0 ~ /^[[:space:]]{6}port:/ {
            sub(/^[^:]*:/, "", $0)
            print trim($0)
            exit
        }
    ' "$CONFIG_FILE"
}

read_strategy_service_port() {
    awk '
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
        in_strategy && $0 ~ /^[[:space:]]{2}strategyServicePort:/ {
            sub(/^[^:]*:/, "", $0)
            print trim($0)
            exit
        }
    ' "$CONFIG_FILE"
}

if [ "$MODE_RAW" = "strategy-service" ] || [ "$MODE_RAW" = "strategy" ]; then
    STRATEGY_SERVICE_ONLY=true
    STRATEGY_SERVICE_ACTION="${2:-deploy}"
    MODE_RAW="${3:-release}"
fi

case "$MODE_RAW" in
    debug|dev)
        MODE="debug"
        export QUANT_MODE=debug
        GRADLE_TASK=":ktor-server:packageDebug"
        ;;
    debug-wan|wan)
        MODE="debug-wan"
        export QUANT_MODE=debug-wan
        GRADLE_TASK=":ktor-server:packageDebugWan"
        ;;
    release|prod|production)
        MODE="release"
        export QUANT_MODE=release
        GRADLE_TASK=":ktor-server:packageRelease"
        ;;
    *)
        echo "Usage: $0 {debug|debug-wan|release}"
        echo "       $0 strategy-service {deploy|restart|rollback|health|logs|status|stop|start} [debug|debug-wan|release]"
        echo "  debug     - Deploy local debug mode"
        echo "  debug-wan - Deploy WAN-accessible debug mode"
        echo "  release   - Deploy release mode (default)"
        echo "  strategy-service deploy   - Build and restart only strategy-service"
        echo "  strategy-service rollback - Roll back only strategy-service from previous backup"
        echo "  strategy-service stop     - Stop strategy-service (does not affect ktor-server)"
        echo "  strategy-service start    - Start strategy-service from existing deploy artifacts"
        echo "  strategy-service status   - Show strategy-service runtime status"
        exit 1
        ;;
esac

TARGET_PORT="$(read_deployment_port "$MODE")"
if [ -z "$TARGET_PORT" ]; then
    echo "Unable to resolve deployment.modes.$MODE.port from $CONFIG_FILE" >&2
    exit 1
fi
# debug/debug-wan 共用同一个部署目录（端口都是 9871），release 独立
case "$MODE" in
    debug|debug-wan) MODE_FAMILY="debug" ;;
    release)         MODE_FAMILY="release" ;;
esac

STRATEGY_SERVICE_BASE_PORT="$(read_strategy_service_port)"
if [ -z "$STRATEGY_SERVICE_BASE_PORT" ]; then
    STRATEGY_SERVICE_BASE_PORT=9971
fi
case "$MODE_FAMILY" in
    release) STRATEGY_SERVICE_PORT="$STRATEGY_SERVICE_BASE_PORT" ;;
    debug)   STRATEGY_SERVICE_PORT=$((STRATEGY_SERVICE_BASE_PORT + 1)) ;;
esac
export STRATEGY_SOCKET_PORT="$STRATEGY_SERVICE_PORT"

# 盈利预测推理子进程端口族（与 start-strategy-service.sh 的派生规则一致）
case "$MODE_FAMILY" in
    release) PROFIT_PREDICTION_INFER_PORT=9875 ;;
    *)       PROFIT_PREDICTION_INFER_PORT=9874 ;;
esac

kill_inference_port_leftover() {
    # 推理子进程只靠 strategy-service JVM 的 shutdown hook 清理；kill -9 兜底或 JVM 异常退出
    # 会留下孤儿进程占住端口。模型切换后身份守卫会拒绝旧模型孤儿、同端口重启必然失败，
    # 导致盘后链路硬失败，因此停服后必须按端口兜底查杀。
    local leftover_pids
    leftover_pids=$(lsof -ti:"$PROFIT_PREDICTION_INFER_PORT" 2>/dev/null || true)
    if [ -n "$leftover_pids" ]; then
        echo "🛑 Killing leftover inference process on port $PROFIT_PREDICTION_INFER_PORT (PID: $leftover_pids)..."
        kill $leftover_pids 2>/dev/null || true
        sleep 2
        if lsof -ti:"$PROFIT_PREDICTION_INFER_PORT" > /dev/null 2>&1; then
            kill -9 $leftover_pids 2>/dev/null || true
            sleep 1
        fi
    fi
}

echo "🚀 Deploying $(echo "$MODE" | tr '[:lower:]' '[:upper:]') mode (port $TARGET_PORT, strategy-service $STRATEGY_SERVICE_PORT)..."

DEPLOY_DIR="$SCRIPT_DIR/ktor-server/build/deploy.${MODE_FAMILY}"
DIST_DIR="$SCRIPT_DIR/ktor-server/build/distributions.${MODE_FAMILY}"
PID_FILE="$DEPLOY_DIR/.quant-server.pid"
STRATEGY_PID_FILE="$DEPLOY_DIR/.strategy-service.pid"
PREVIOUS_DEPLOY_DIR="$SCRIPT_DIR/ktor-server/build/deploy.${MODE_FAMILY}.previous"
STAGING_DEPLOY_DIR="$SCRIPT_DIR/ktor-server/build/deploy.${MODE_FAMILY}.staging"
STAGING_DIST_DIR="$SCRIPT_DIR/ktor-server/build/distributions.${MODE_FAMILY}.staging"
DEPLOY_BIN="$DEPLOY_DIR/bin"
STRATEGY_START_SCRIPT="$DEPLOY_BIN/start-strategy-service.sh"
STRATEGY_DEPLOY_DIR="$DEPLOY_DIR/strategy-service"
STRATEGY_ROLLBACK_DIR="$DEPLOY_DIR/strategy-service.rollback"

ensure_strategy_deploy_layout() {
    mkdir -p "$DEPLOY_BIN" "$DEPLOY_DIR/logs" "$DEPLOY_DIR/data" "$DEPLOY_DIR/config"
    # 启动脚本每次部署都刷新（stop 走 PID 文件，刷新无副作用）；config 仅缺失时初始化
    cp "$SCRIPT_DIR/ktor-server/src/main/scripts/start-strategy-service.sh" "$STRATEGY_START_SCRIPT"
    chmod 755 "$STRATEGY_START_SCRIPT"
    if [ ! -f "$DEPLOY_DIR/config/config.yaml" ]; then
        cp "$CONFIG_FILE" "$DEPLOY_DIR/config/config.yaml"
    fi
}

stop_strategy_service_if_running() {
    if [ -x "$STRATEGY_START_SCRIPT" ]; then
        "$STRATEGY_START_SCRIPT" stop || true
        kill_inference_port_leftover
        return
    fi
    if [ -f "$STRATEGY_PID_FILE" ]; then
        local old_strategy_pid
        old_strategy_pid=$(cat "$STRATEGY_PID_FILE" 2>/dev/null || true)
        if [ -n "$old_strategy_pid" ] && ps -p "$old_strategy_pid" > /dev/null 2>&1; then
            kill "$old_strategy_pid" 2>/dev/null || true
        fi
        rm -f "$STRATEGY_PID_FILE"
    fi
    kill_inference_port_leftover
}

deploy_strategy_service_only() {
    ensure_strategy_deploy_layout
    echo "🚀 Building strategy-service only ($MODE)..."
    ./gradlew -Pquant.mode="$MODE" :strategy-server:service:installDist

    echo "🛑 Stopping strategy-service only..."
    stop_strategy_service_if_running

    if [ -d "$STRATEGY_DEPLOY_DIR" ]; then
        rm -rf "$STRATEGY_ROLLBACK_DIR"
        cp -R "$STRATEGY_DEPLOY_DIR" "$STRATEGY_ROLLBACK_DIR"
    fi

    rm -rf "$STRATEGY_DEPLOY_DIR"
    mkdir -p "$STRATEGY_DEPLOY_DIR"
    cp -R "$SCRIPT_DIR/strategy-server/service/build/install/strategy-service/." "$STRATEGY_DEPLOY_DIR/"

    echo "🚀 Starting strategy-service only..."
    "$STRATEGY_START_SCRIPT" start
    "$STRATEGY_START_SCRIPT" health
    echo "✅ strategy-service deploy complete."
}

rollback_strategy_service_only() {
    ensure_strategy_deploy_layout
    if [ ! -d "$STRATEGY_ROLLBACK_DIR" ]; then
        echo "No strategy-service rollback backup found: $STRATEGY_ROLLBACK_DIR" >&2
        exit 1
    fi
    echo "🛑 Stopping strategy-service only..."
    stop_strategy_service_if_running
    rm -rf "$STRATEGY_DEPLOY_DIR"
    cp -R "$STRATEGY_ROLLBACK_DIR" "$STRATEGY_DEPLOY_DIR"
    echo "🚀 Starting rolled back strategy-service..."
    "$STRATEGY_START_SCRIPT" start
    "$STRATEGY_START_SCRIPT" health
    echo "✅ strategy-service rollback complete."
}

if [ "$STRATEGY_SERVICE_ONLY" = true ]; then
    case "$STRATEGY_SERVICE_ACTION" in
        deploy|restart)
            deploy_strategy_service_only
            ;;
        rollback)
            rollback_strategy_service_only
            ;;
        health|logs|status|stop|start)
            ensure_strategy_deploy_layout
            "$STRATEGY_START_SCRIPT" "$STRATEGY_SERVICE_ACTION"
            ;;
        *)
            echo "Usage: $0 strategy-service {deploy|restart|rollback|health|logs|status|stop|start} [debug|debug-wan|release]"
            exit 1
            ;;
    esac
    exit 0
fi

# 先构建到 staging 目录，成功后再停止旧实例，避免编译失败导致线上服务提前下线。
echo "📦 Building staged deployment package..."
./gradlew \
    -Pquant.mode="$MODE" \
    -Pquant.deploy.dir="$STAGING_DEPLOY_DIR" \
    -Pquant.dist.dir="$STAGING_DIST_DIR" \
    :ktor-server:cleanDeploy \
    "$GRADLE_TASK"

STAGING_START_SCRIPT="$STAGING_DEPLOY_DIR/bin/start-${MODE}.sh"
STAGING_STRATEGY_START_SCRIPT="$STAGING_DEPLOY_DIR/bin/start-strategy-service.sh"
if [ ! -x "$STAGING_START_SCRIPT" ]; then
    echo "Staged server start script not found or not executable: $STAGING_START_SCRIPT" >&2
    exit 1
fi
if [ ! -x "$STAGING_STRATEGY_START_SCRIPT" ]; then
    echo "Staged strategy-service start script not found or not executable: $STAGING_STRATEGY_START_SCRIPT" >&2
    exit 1
fi

# staging 构建成功后再停止旧实例
if [ -f "$STRATEGY_PID_FILE" ]; then
    OLD_STRATEGY_PID=$(cat "$STRATEGY_PID_FILE" 2>/dev/null || true)
    if [ -n "$OLD_STRATEGY_PID" ] && ps -p "$OLD_STRATEGY_PID" > /dev/null 2>&1; then
        echo "🛑 Stopping existing strategy-service (PID: $OLD_STRATEGY_PID)..."
        kill "$OLD_STRATEGY_PID" 2>/dev/null || true
        for i in {1..15}; do
            if ! ps -p "$OLD_STRATEGY_PID" > /dev/null 2>&1; then
                break
            fi
            sleep 1
        done
        if ps -p "$OLD_STRATEGY_PID" > /dev/null 2>&1; then
            kill -9 "$OLD_STRATEGY_PID" 2>/dev/null || true
        fi
    fi
    rm -f "$STRATEGY_PID_FILE"
fi

if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE" 2>/dev/null || true)
    if [ -n "$OLD_PID" ] && ps -p "$OLD_PID" > /dev/null 2>&1; then
        echo "🛑 Stopping existing instance (PID: $OLD_PID)..."
        kill "$OLD_PID" 2>/dev/null || true
        for i in {1..15}; do
            if ! ps -p "$OLD_PID" > /dev/null 2>&1; then
                break
            fi
            sleep 1
        done
        if ps -p "$OLD_PID" > /dev/null 2>&1; then
            kill -9 "$OLD_PID" 2>/dev/null || true
        fi
    fi
    rm -f "$PID_FILE"
fi

# 兜底：按端口查杀残留进程
STRATEGY_REMAINING_PID=$(lsof -ti:"$STRATEGY_SERVICE_PORT" 2>/dev/null || true)
if [ -n "$STRATEGY_REMAINING_PID" ]; then
    echo "🛑 Killing remaining strategy-service process on port $STRATEGY_SERVICE_PORT (PID: $STRATEGY_REMAINING_PID)..."
    kill "$STRATEGY_REMAINING_PID" 2>/dev/null || true
    sleep 2
    if lsof -ti:"$STRATEGY_SERVICE_PORT" > /dev/null 2>&1; then
        kill -9 "$STRATEGY_REMAINING_PID" 2>/dev/null || true
        sleep 1
    fi
fi

kill_inference_port_leftover

REMAINING_PID=$(lsof -ti:"$TARGET_PORT" 2>/dev/null || true)
if [ -n "$REMAINING_PID" ]; then
    echo "🛑 Killing remaining process on port $TARGET_PORT (PID: $REMAINING_PID)..."
    kill "$REMAINING_PID" 2>/dev/null || true
    sleep 2
    if lsof -ti:"$TARGET_PORT" > /dev/null 2>&1; then
        kill -9 "$REMAINING_PID" 2>/dev/null || true
        sleep 1
    fi
fi

# 替换正式 deploy 目录。保留上一版目录，便于启动失败时人工检查或恢复。
echo "📦 Promoting staged deployment..."
rm -rf "$PREVIOUS_DEPLOY_DIR"
if [ -d "$DEPLOY_DIR" ]; then
    mv "$DEPLOY_DIR" "$PREVIOUS_DEPLOY_DIR"
fi
mv "$STAGING_DEPLOY_DIR" "$DEPLOY_DIR"

rm -rf "$DIST_DIR"
if [ -d "$STAGING_DIST_DIR" ]; then
    mv "$STAGING_DIST_DIR" "$DIST_DIR"
fi

START_SCRIPT="$DEPLOY_BIN/start-${MODE}.sh"

# 启动服务
echo "🚀 Starting strategy-service..."
if ! "$STRATEGY_START_SCRIPT" start; then
    echo "⚠️  strategy-service failed to start; continuing to start Ktor."
    echo "   Note: strategy snapshot subscriptions (INTRADAY/POSITIONS/POSITION_TRACKING) will return ERROR until strategy-service recovers."
    echo "   Check logs: $DEPLOY_DIR/logs/strategy-service.stdout.log"
fi

echo "🚀 Starting server..."
"$START_SCRIPT" start

echo ""
echo "✅ Deployment complete."
echo "   Mode:   $MODE"
echo "   Port:   $TARGET_PORT"
echo "   Strategy service port: $STRATEGY_SERVICE_PORT"
echo "   Logs:   tail -f $DEPLOY_DIR/logs/server.log"

# iOS 未签名 ipa 出包：仅 release + 仅 macOS。
# iOS 出包依赖 Xcode 工具链，无法在 Linux/CI/Docker 执行，因此独立于 packageRelease
# （后者跨平台可构建），在此守卫触发，杜绝非 macOS 环境 release 部署失败。
# 出包失败不回滚已完成的 server 部署（server 已起），仅提示重试。
if [ "$MODE_FAMILY" = "release" ]; then
    if [ "$(uname -s)" = "Darwin" ]; then
        echo ""
        echo "🍎 Building unsigned iOS ipa (release)..."
        if "$SCRIPT_DIR/scripts/build-ios-ipa.sh"; then
            echo "   iOS ipa ready: $SCRIPT_DIR/build/ios-ipa/Quant.ipa（用户自行签名 sideload）"
        else
            echo "⚠️  iOS ipa 出包失败；server 部署不受影响。"
            echo "   手动重试：$SCRIPT_DIR/scripts/build-ios-ipa.sh"
        fi
    else
        echo ""
        echo "ℹ️  跳过 iOS ipa 出包：当前非 macOS（$(uname -s)），iOS 出包需在 macOS 上执行 scripts/build-ios-ipa.sh。"
    fi
fi
