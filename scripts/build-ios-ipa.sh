#!/bin/bash
#
# iOS 未签名 ipa 出包脚本。
#
# 产物是未签名的 Quant.ipa，交由用户自行签名后 sideload 安装（AltStore / Sideloadly /
# 自签证书等），不上 App Store。整个出包过程关闭 Xcode 签名阶段（CODE_SIGNING_ALLOWED=NO），
# 不依赖任何开发者证书或 provisioning profile。
#
# 与 deploy.sh 的关系：iOS 出包依赖 macOS + Xcode 工具链，无法在 Linux/CI/Docker 执行，
# 因此独立成脚本，由 deploy.sh release 在 macOS 上守卫调用。直接手动执行本脚本同样可出包。
#
# 链路：
#   清 K/N 缓存 -> linkReleaseFrameworkIosArm64 全量重链（杜绝增量陈旧 framework，
#   见 deployment-architecture.md §6.5 的 K/N 增量缓存陷阱）
#   -> xcodebuild archive（Release / 关闭签名）
#   -> 从 .xcarchive 提取 .app -> 打包 Payload/ 为未签名 ipa
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

if [ "$(uname -s)" != "Darwin" ]; then
    echo "iOS ipa 出包仅支持 macOS（依赖 Xcode 工具链）。当前系统：$(uname -s)" >&2
    exit 1
fi
if ! command -v xcodebuild > /dev/null 2>&1; then
    echo "未找到 xcodebuild，请先安装 Xcode 命令行工具。" >&2
    exit 1
fi

XCODE_PROJECT="$PROJECT_ROOT/iosApp/iosApp.xcodeproj"
SCHEME="iosApp"
OUTPUT_DIR="$PROJECT_ROOT/build/ios-ipa"
ARCHIVE_PATH="$OUTPUT_DIR/Quant.xcarchive"
IPA_PATH="$OUTPUT_DIR/Quant.ipa"

echo "🧹 Cleaning Kotlin/Native iOS caches to avoid stale framework..."
rm -rf compose-app/build/bin/iosArm64 compose-app/build/bin/iosSimulatorArm64 compose-app/build/xcode-frameworks
rm -rf compose-app/build/classes/kotlin/iosArm64 compose-app/build/classes/kotlin/iosSimulatorArm64

echo "🔗 Linking release framework (iosArm64) from scratch..."
./gradlew :compose-app:linkReleaseFrameworkIosArm64 --rerun-tasks

rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"

echo "📦 Archiving iOS app (Release, unsigned)..."
xcodebuild archive \
    -project "$XCODE_PROJECT" \
    -scheme "$SCHEME" \
    -configuration Release \
    -archivePath "$ARCHIVE_PATH" \
    -destination "generic/platform=iOS" \
    CODE_SIGNING_ALLOWED=NO \
    CODE_SIGNING_REQUIRED=NO \
    CODE_SIGN_IDENTITY="" \
    DEVELOPMENT_TEAM=""

APP_PATH="$(/usr/bin/find "$ARCHIVE_PATH/Products/Applications" -maxdepth 1 -name "*.app" | head -n 1)"
if [ -z "$APP_PATH" ]; then
    echo "未在归档中找到 .app：$ARCHIVE_PATH/Products/Applications" >&2
    exit 1
fi

echo "🗜️  Packaging unsigned ipa..."
PAYLOAD_DIR="$OUTPUT_DIR/Payload"
rm -rf "$PAYLOAD_DIR"
mkdir -p "$PAYLOAD_DIR"
cp -R "$APP_PATH" "$PAYLOAD_DIR/"
# ipa 即 zip：根目录必须只含 Payload/，且用相对路径压缩，避免带入绝对路径目录层级。
(cd "$OUTPUT_DIR" && /usr/bin/zip -q -r -y "$IPA_PATH" Payload)
rm -rf "$PAYLOAD_DIR"

echo ""
echo "✅ Unsigned iOS ipa built."
echo "   App:  $(basename "$APP_PATH")"
echo "   ipa:  $IPA_PATH"
echo "   用户需自行签名后 sideload 安装（不签名无法直接安装到非越狱设备）。"
