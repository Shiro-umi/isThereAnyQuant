#!/bin/bash
# 安装 @zed-industries/claude-agent-acp 脚本
# 用法: ./install-zed-acp.sh

set -e

echo "=== Installing @zed-industries/claude-agent-acp ==="
echo ""

# 检查是否已安装
if command -v claude-agent-acp &> /dev/null; then
    echo "✓ claude-agent-acp is already installed"
    claude-agent-acp --version 2>/dev/null || echo "Version check skipped"
    exit 0
fi

# 方法1: 通过 Homebrew 安装 (推荐 macOS/Linux)
if command -v brew &> /dev/null; then
    echo "→ Installing via Homebrew..."
    brew install claude-agent-acp
    if [ $? -eq 0 ]; then
        echo "✓ Installation successful via Homebrew"
        exit 0
    fi
fi

# 方法2: 通过 npm 全局安装
echo "→ Installing via npm..."
if command -v npm &> /dev/null; then
    npm install -g @zed-industries/claude-agent-acp
    if [ $? -eq 0 ]; then
        echo "✓ Installation successful via npm"
        exit 0
    fi
else
    echo "✗ npm not found. Please install Node.js first: https://nodejs.org/"
fi

# 方法3: 下载独立可执行文件 (备用)
echo ""
echo "→ Trying to download standalone binary..."

OS=$(uname -s | tr '[:upper:]' '[:lower:]')
ARCH=$(uname -m)

# 映射架构名称
case "$ARCH" in
    x86_64) ARCH="x64" ;;
    arm64|aarch64) ARCH="arm64" ;;
esac

# 目前官方只提供通过 npm/homebrew 安装
# 独立可执行文件需要从 GitHub Releases 手动下载
echo ""
echo "Please install manually using one of the following methods:"
echo ""
echo "  1. Homebrew (macOS/Linux):"
echo "     brew install claude-agent-acp"
echo ""
echo "  2. npm (requires Node.js):"
echo "     npm install -g @zed-industries/claude-agent-acp"
echo ""
echo "  3. Download from GitHub Releases:"
echo "     https://github.com/zed-industries/claude-agent-acp/releases"
echo ""

exit 1
