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
# 链路（版本前置同步 + 增量优先 + 二进制门禁 + 失败兜底，见 deployment-architecture.md §6.5）：
#   版本前置同步（syncIosVersion 把 APP_BUILD 钉到当前 versionCode，打破 archive 内写-读竞态）
#   -> 增量 link（Gradle 自身增量判定，不清缓存、不 --rerun-tasks）
#   -> xcodebuild archive（Release / 关闭签名 / 增量）
#   -> 从 .xcarchive 提取 .app -> 打包 Payload/ 为未签名 ipa
#   -> 二进制实证门禁：内嵌 host 必为生产 bigsmart.space（无内网 IP）+ CFBundleVersion 对版本
#   -> 门禁通过即完成；门禁不通过则 fallback 全量清缓存重链重出一次，二次仍不过硬退出报警
#
# 为什么默认增量安全（实测结论，2026-06-17）：Gradle 对 linkReleaseFrameworkIosArm64 的增量
# 判定正确——源码零变化时 link UP-TO-DATE（秒级），commonMain/:shared 真变化时 compile+link
# 正确重跑。旧脚本每次无条件 rm -rf + --rerun-tasks 全量重链（约 13 分钟）是在白白摧毁一个本来
# 正确的缓存，它规避的「K/N 增量陈旧 framework」从未有二进制实证；唯一被二进制证实的真事故是
# QUANT_MODE=debug 污染（内嵌内网 IP），已由下方 export QUANT_MODE=release 修复。出包后的二进制
# 实证门禁是正确性的最终保障：任何「带旧代码/内网地址/旧版本上设备」都会在门禁处被拦下并触发兜底。
#
set -euo pipefail

# QUANT_MODE 陷阱（deployment-architecture.md §6.5）：xcodebuild archive 的 Build Phase 会调
# :compose-app:embedAndSignAppleFrameworkForXcode（任务名不含 release），无 mode 时
# generateAppEnvironment 落默认 debug 分支，把内嵌 API host 写成内网 LAN IP（auto-lan 检测）、
# versionCode 也不对，release ipa 连不上生产 bigsmart.space。
#
# 治本（2026-06-17）：pbxproj 的 Build Phase 已按 $CONFIGURATION 源头钉 -Pquant.mode（Release 类
# 配置永久 release），archive 走该 Build Phase 时 mode 已在源头锁定，不再依赖本行。本行作为冗余
# 防线保留：手动调试、绕过 pbxproj 的路径仍受其保护。mode 优先级 -Pquant.mode > QUANT_MODE >
# 任务名默认值，两者都指向 release，结果一致。另有 generateAppEnvironment 编译期出包断言兜底：
# 出包任务若无显式 mode 且 host 内网会硬失败，杜绝内网地址烧进客户端产物。
export QUANT_MODE=release

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
VERSION_PROPS="$PROJECT_ROOT/compose-app/version.properties"

# ---------------------------------------------------------------------------
# 版本前置同步（消除 fallback 全量重链的根因，实测结论 2026-06-17）：
# version.properties 的 versionCode 在 deploy.sh release 的主 gradle 调用（packageRelease）
# 配置阶段自增（如 50 -> 51），但写进 iosApp/Configuration/Config.xcconfig 的 APP_BUILD 只挂在
# embedAndSignAppleFrameworkForXcode 的 dependsOn 上——那在本脚本后续 archive 内部才触发。
# 同一次 archive 里 Xcode 在 build 早期就解析了 xcconfig（此时仍是旧 APP_BUILD），syncIosVersion
# 作为 framework build 依赖随后才改文件，于是首次 archive 的 Info.plist CFBundleVersion 注入旧版本，
# 二进制门禁判 CFBundleVersion != versionCode 不通过 -> 触发约 13 分钟全量重链；第二次 archive 时
# xcconfig 已更新才过。纯粹是版本注入晚了一个 archive 周期，与 K/N 缓存陈旧无关。
# 治本：archive 前独立执行一次 syncIosVersion，把 APP_BUILD 钉到当前 versionCode，打破同次 archive
# 内的写-读竞态，首次 archive 即拿到正确版本，门禁一次过，不再进 fallback。该任务名不在 release
# 自增集合内（仅 assembleRelease/bundleRelease/installRelease/packageRelease 自增），单独跑不会再次
# 自增；已对齐时 UP-TO-DATE 无副作用，幂等安全。
# ---------------------------------------------------------------------------
sync_ios_version() {
    echo "🔢 Syncing iOS version (APP_BUILD <- version.properties versionCode)..."
    ./gradlew :compose-app:syncIosVersion
}

# ---------------------------------------------------------------------------
# 增量 link：让 Gradle 自身增量判定接管。源码无变化时 link UP-TO-DATE（秒级），
# 有变化时正确重编重链。这一步是 archive 阶段 embedAndSign 编译的预热，archive 自身也会
# 触发 embedAndSign，因此即使省略本步也能出包；保留是为了把链接耗时与 archive 解耦、便于观测。
# ---------------------------------------------------------------------------
incremental_link() {
    echo "🔗 Linking release framework (iosArm64, incremental)..."
    ./gradlew :compose-app:linkReleaseFrameworkIosArm64
}

# ---------------------------------------------------------------------------
# 全量重链（仅二进制门禁失败时的兜底）：清 K/N 编译产物 + --rerun-tasks 强制全量重编重链，
# 杜绝任何增量残留。代价约 13 分钟，因此只在门禁判定本次产物有问题时才触发，而非每次固定成本。
# ---------------------------------------------------------------------------
full_relink() {
    echo "🧹 Cleaning Kotlin/Native iOS caches (fallback full relink)..."
    rm -rf compose-app/build/bin/iosArm64 compose-app/build/bin/iosSimulatorArm64 compose-app/build/xcode-frameworks
    rm -rf compose-app/build/classes/kotlin/iosArm64 compose-app/build/classes/kotlin/iosSimulatorArm64
    echo "🔗 Linking release framework (iosArm64) from scratch..."
    ./gradlew :compose-app:linkReleaseFrameworkIosArm64 --rerun-tasks
}

# ---------------------------------------------------------------------------
# archive + 提取 .app + 打包未签名 ipa。每次重出前清掉旧 OUTPUT_DIR 与归档，保证产物干净。
# ---------------------------------------------------------------------------
do_archive() {
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

    local app_path
    app_path="$(/usr/bin/find "$ARCHIVE_PATH/Products/Applications" -maxdepth 1 -name "*.app" | head -n 1)"
    if [ -z "$app_path" ]; then
        echo "未在归档中找到 .app：$ARCHIVE_PATH/Products/Applications" >&2
        exit 1
    fi

    echo "🗜️  Packaging unsigned ipa..."
    local payload_dir="$OUTPUT_DIR/Payload"
    rm -rf "$payload_dir"
    mkdir -p "$payload_dir"
    cp -R "$app_path" "$payload_dir/"
    # ipa 即 zip：根目录必须只含 Payload/，且用相对路径压缩，避免带入绝对路径目录层级。
    (cd "$OUTPUT_DIR" && /usr/bin/zip -q -r -y "$IPA_PATH" Payload)
    rm -rf "$payload_dir"
    echo "   App: $(basename "$app_path")"
}

# ---------------------------------------------------------------------------
# 二进制实证门禁（正确性最终保障）。Compose iOS framework isStatic=true，静态链进主程序
# Payload/Quant.app/Quant；内嵌 host 以 UTF-16LE 存（strings 抓不到），用 Python 解码搜。
#   1) 内嵌 host 必含生产 bigsmart.space，且不得出现内网/LAN host（172./192.168./127.0.0.1/:9871）
#      —— 命中内网即 QUANT_MODE=debug 污染。
#   2) CFBundleVersion 必须等于 version.properties 当前 versionCode —— 不等即用了旧版本产物。
# 返回 0 通过，非 0 不通过。不依赖 framework mtime（§6.5：embedAndSign 在 DerivedData 独立编译，
# mtime 与脚本前置产物无关，不能作为陈旧判据）。
# ---------------------------------------------------------------------------
verify_ipa() {
    if [ ! -f "$IPA_PATH" ]; then
        echo "⚠️  二进制门禁：ipa 不存在：$IPA_PATH" >&2
        return 1
    fi

    local expected_code
    expected_code="$(grep -E '^versionCode=' "$VERSION_PROPS" | head -1 | cut -d= -f2 | tr -d '[:space:]')"

    local tmp
    tmp="$(mktemp -d "${TMPDIR:-/tmp}/ipa-verify.XXXXXX")"
    # shellcheck disable=SC2064
    trap "rm -rf '$tmp'" RETURN

    if ! /usr/bin/unzip -q "$IPA_PATH" -d "$tmp" 2> /dev/null; then
        echo "⚠️  二进制门禁：解包 ipa 失败" >&2
        return 1
    fi

    local bin plist
    bin="$(/usr/bin/find "$tmp/Payload" -maxdepth 2 -name "Quant" -type f | head -1)"
    plist="$(/usr/bin/find "$tmp/Payload" -maxdepth 2 -name "Info.plist" | head -1)"
    if [ -z "$bin" ] || [ -z "$plist" ]; then
        echo "⚠️  二进制门禁：未在 ipa 中找到主二进制或 Info.plist" >&2
        return 1
    fi

    /usr/bin/python3 - "$bin" "$plist" "$expected_code" <<'PY'
import sys, re, subprocess

bin_path, plist_path, expected_code = sys.argv[1], sys.argv[2], sys.argv[3]

# host 实证：UTF-16LE 解码主二进制后搜 URL
data = open(bin_path, "rb").read().decode("utf-16-le", errors="ignore")
urls = set(re.findall(r"[a-z]+://[a-z0-9._:-]+", data))
prod = sorted(u for u in urls if "bigsmart.space" in u)
intranet = sorted(u for u in urls if re.search(r"172\.|192\.168\.|127\.0\.0\.1|:9871", u))

ok = True
if not prod:
    print("  ❌ host：未找到生产 host bigsmart.space")
    ok = False
else:
    print("  ✅ host：" + ", ".join(prod))
if intranet:
    print("  ❌ host：检出内网/LAN host（QUANT_MODE=debug 污染）：" + ", ".join(intranet))
    ok = False

# 版本实证：CFBundleVersion == version.properties versionCode
try:
    cfver = subprocess.check_output(
        ["/usr/bin/plutil", "-extract", "CFBundleVersion", "raw", plist_path],
        text=True,
    ).strip()
except Exception as exc:  # noqa: BLE001
    print(f"  ❌ 版本：读取 CFBundleVersion 失败：{exc}")
    sys.exit(1)

if expected_code and cfver != expected_code:
    print(f"  ❌ 版本：CFBundleVersion={cfver} 与 versionCode={expected_code} 不一致")
    ok = False
else:
    print(f"  ✅ 版本：CFBundleVersion={cfver}")

sys.exit(0 if ok else 1)
PY
}

# ---------------------------------------------------------------------------
# 主流程：版本前置同步 -> 增量出包 -> 门禁；不过则全量重链重出 -> 再门禁；二次仍不过硬退出。
# 版本前置同步消除「首次 archive 注入旧 CFBundleVersion -> 门禁挂 -> fallback 全量重链」的根因。
# ---------------------------------------------------------------------------
sync_ios_version
incremental_link
do_archive

echo ""
echo "🔍 Verifying ipa (binary evidence gate)..."
if verify_ipa; then
    echo ""
    echo "✅ Unsigned iOS ipa built and verified."
    echo "   ipa:  $IPA_PATH"
    echo "   用户需自行签名后 sideload 安装（不签名无法直接安装到非越狱设备）。"
    exit 0
fi

echo ""
echo "⚠️  二进制门禁未通过，触发 fallback 全量重链重出一次..."
full_relink
do_archive

echo ""
echo "🔍 Re-verifying ipa after full relink..."
if verify_ipa; then
    echo ""
    echo "✅ Unsigned iOS ipa built and verified (after fallback full relink)."
    echo "   ipa:  $IPA_PATH"
    echo "   用户需自行签名后 sideload 安装（不签名无法直接安装到非越狱设备）。"
    exit 0
fi

echo ""
echo "❌ 全量重链后二进制门禁仍未通过，出包失败。" >&2
echo "   请人工检查：QUANT_MODE 注入、version.properties 与 Config.xcconfig 版本同步、Xcode DerivedData。" >&2
exit 1
