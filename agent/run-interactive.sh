#!/bin/bash
# Agent 模块交互式测试启动脚本
# 用法: ./run-interactive.sh
#
# 环境变量:
#   ANTHROPIC_API_KEY - Anthropic API Key (必需)
#
# 支持的 Claude ACP 后端 (按优先级):
#   1. @zed-industries/claude-agent-acp (推荐)
#      安装: ./install-zed-acp.sh 或 brew install claude-agent-acp
#   2. Claude Code CLI (claude --experimental-acp)
#      安装: npm install -g @anthropic-ai/claude-code

# 获取项目根目录
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT" || exit 1

# 检查 API Key
if [ -z "$ANTHROPIC_API_KEY" ]; then
    echo "⚠️  Warning: ANTHROPIC_API_KEY is not set"
    echo "   Please set it before running: export ANTHROPIC_API_KEY=sk-..."
    echo ""
fi

# 检查可用的 ACP 后端
echo "=== Checking ACP backends ==="
if command -v claude-agent-acp &> /dev/null; then
    echo "✓ @zed-industries/claude-agent-acp found"
    claude-agent-acp --version 2>/dev/null || true
elif command -v claude &> /dev/null; then
    echo "✓ Claude Code CLI found (will use --experimental-acp)"
else
    echo "✗ No ACP backend found!"
    echo "   Please install one of:"
    echo "   - @zed-industries/claude-agent-acp: ./install-zed-acp.sh"
    echo "   - Claude Code CLI: npm install -g @anthropic-ai/claude-code"
    exit 1
fi
echo ""

# 构建项目（如果需要）
echo "Building agent module..."
./gradlew :agent:classes --quiet 2>/dev/null

if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

# 获取 classpath
CLASSPATH=$(./gradlew :agent:printClasspath -q 2>/dev/null)

if [ -z "$CLASSPATH" ]; then
    echo "Failed to get classpath!"
    exit 1
fi

# 运行交互式测试
echo ""
echo "=== Starting Agent Interactive Test ==="
echo ""
java -cp "$CLASSPATH" org.shiroumi.agent.InteractiveTestKt
