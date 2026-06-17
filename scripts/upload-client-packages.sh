#!/bin/bash
# 客户端安装包上传夸克网盘固定目录 /quant-client
# 可单独重跑；上传新包不改变固定分享链接。
set -euo pipefail

# 切到项目根（本脚本位于 scripts/ 下）
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

# 1) 读 KUAKE_COOKIE：env 已有则用；否则 source 根目录 .env.model 兜底
if [ -z "${KUAKE_COOKIE:-}" ]; then
    [ -f ".env.model" ] && source ".env.model"
fi
if [ -z "${KUAKE_COOKIE:-}" ]; then
    echo "❌ KUAKE_COOKIE 未配置（设置环境变量，或写入 private/.env.model）。"
    exit 1
fi
export KUAKE_COOKIE

# 2) 校验 kuake 二进制
if ! command -v kuake >/dev/null 2>&1; then
    echo "❌ 未安装 kuake。安装：github.com/zhangjingwei/kuake_cli/releases（或 Go 源码 build）。"
    exit 1
fi

# 3) 收敛产物到 build/client-packages/
"$PROJECT_ROOT/scripts/collect-client-packages.sh"

# 4) 时间戳 + 固定目录
TS="$(date +%Y%m%d-%H%M%S)"
QUARK_DIR="/quant-client"

# 固定目录已存在则忽略（不碰需 pipe 的 delete）
kuake create "quant-client" "/" 2>/dev/null || true

# 5) 上传（带时间戳文件名，远端路径全引号）
kuake upload "build/client-packages/Quant.apk" "${QUARK_DIR}/Quant-${TS}.apk"
echo "✅ APK 上传 → ${QUARK_DIR}/Quant-${TS}.apk"

if [ -f "build/client-packages/Quant.ipa" ]; then
    kuake upload "build/client-packages/Quant.ipa" "${QUARK_DIR}/Quant-${TS}.ipa"
    echo "✅ ipa 上传 → ${QUARK_DIR}/Quant-${TS}.ipa"
else
    echo "ℹ️  ipa 缺失（非 macOS 或未出 ipa），仅传 APK。"
fi

echo "📎 固定分享页：https://pan.quark.cn/s/667221bcabd6"
