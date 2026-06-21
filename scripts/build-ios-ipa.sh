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
# 链路（版本前置同步 + Xcode 单点构建 framework + 二进制门禁 + 失败兜底，见 deployment-architecture.md §6.5）：
#   版本前置同步（syncIosVersion 把 APP_BUILD 钉到当前 versionCode，打破 archive 内写-读竞态）
#   -> xcodebuild archive（Release / 关闭签名；Build Phase 调 embedAndSign 构建/嵌入 framework）
#   -> 从 .xcarchive 提取 .app -> 打包 Payload/ 为未签名 ipa
#   -> 二进制实证门禁：内嵌 host 必为生产 bigsmart.space（无内网 IP）+ CFBundleVersion 对版本
#   -> 门禁通过即完成；门禁不通过则 fallback 清 K/N 缓存后重出一次，二次仍不过硬退出报警
#
# 为什么默认增量安全（实测结论，2026-06-17）：Gradle 对 linkReleaseFrameworkIosArm64 的增量
# 判定正确——源码零变化时 link UP-TO-DATE（秒级），commonMain/:shared 真变化时 compile+link
# 正确重跑。旧脚本每次无条件 rm -rf + --rerun-tasks 全量重链（约 13 分钟）是在白白摧毁一个本来
# 正确的缓存，它规避的「K/N 增量陈旧 framework」从未有二进制实证；唯一被二进制证实的真事故是
# QUANT_MODE=debug 污染（内嵌内网 IP），已由下方 export QUANT_MODE=release 修复。出包后的二进制
# 实证门禁是正确性的最终保障：任何「带旧代码/内网地址/旧版本上设备」都会在门禁处被拦下并触发兜底。
#
# 为什么不再在 archive 前单独跑 linkReleaseFrameworkIosArm64：iOS archive 的 Build Phase 本来就
# 调 embedAndSignAppleFrameworkForXcode，并由它选择/构建/嵌入正确 SDK 的 framework。脚本预先 link
# 会把同一职责拆成两个入口；一旦路径、mode 或输出目录判定不一致，容易出现先 link 一次、archive
# 内再 link 一次的慢路径。这里让 Xcode Build Phase 成为 framework 构建的唯一 owner。
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
# 任务名默认值。另有 generateAppEnvironment 编译期出包断言兜底：出包任务若无显式 mode 且 host
# 内网会硬失败，杜绝内网地址烧进客户端产物。
#
# 出包模式：$1 = release（默认，向后兼容 deploy.sh 无参/有参调用）| debug。
#   release：-configuration Release（内嵌生产 host bigsmart.space），产 build/ios-ipa/Quant.ipa，
#            走生产门禁（必生产 host + 禁内网）。这是夸克分发包。
#   debug：  -configuration Debug（内嵌内网/DDNS host），产 build/ios-ipa/debug/Quant.ipa，
#            走 debug 门禁（禁生产 host），只本地 sideload，绝不上传夸克。
# mode 必须与 xcodebuild -configuration 同源：二进制里烧什么 host 由 -configuration 经 pbxproj
# Build Phase（case $CONFIGURATION）决定，门禁判据也必须从同一 mode 派生，不能脱钩成两个变量。
MODE="${1:-release}"
case "$MODE" in
    release) XC_CONFIG="Release" ;;
    debug)   XC_CONFIG="Debug" ;;
    *)
        echo "用法：$0 [release|debug]（默认 release）" >&2
        exit 1
        ;;
esac
# QUANT_MODE 是冗余防线（优先级 -Pquant.mode > QUANT_MODE）：真正决定内嵌 mode 的是 pbxproj
# Build Phase 按 $CONFIGURATION 注入的 -Pquant.mode；此处与 -configuration 对齐做纵深防御。
export QUANT_MODE="$MODE"

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
# 输出路径按 mode 物理隔离，杜绝 debug ipa 覆盖 release ipa：release 维持老路径
# build/ios-ipa/Quant.ipa（collect-client-packages.sh release 链路硬编码取此路径，不动它），
# debug 进 build/ios-ipa/debug/Quant.ipa。这样 collect/upload（只认 release 老路径）物理上够不到
# debug 包——「debug 零污染夸克」是结构性隔离，不靠流程纪律。
if [ "$MODE" = "release" ]; then
    OUTPUT_DIR="$PROJECT_ROOT/build/ios-ipa"
else
    OUTPUT_DIR="$PROJECT_ROOT/build/ios-ipa/$MODE"
fi
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
# 清 K/N 缓存（仅二进制门禁失败时的兜底准备）：清掉 iOS 编译产物后，让下一次
# xcodebuild archive 内的 embedAndSign 重新生成 framework。这样 fallback 仍是全量重链，
# 但 framework 构建只有一个 owner，不在脚本里先 link 一次、archive 里再检查一次。
# ---------------------------------------------------------------------------
prepare_full_relink() {
    echo "🧹 Cleaning Kotlin/Native iOS caches (fallback full relink on next archive)..."
    rm -rf compose-app/build/bin/iosArm64 compose-app/build/bin/iosSimulatorArm64 compose-app/build/xcode-frameworks
    rm -rf compose-app/build/classes/kotlin/iosArm64 compose-app/build/classes/kotlin/iosSimulatorArm64
}

# ---------------------------------------------------------------------------
# archive + 提取 .app + 打包未签名 ipa。每次重出前清掉旧 OUTPUT_DIR 与归档，保证产物干净。
# ---------------------------------------------------------------------------
do_archive() {
    rm -rf "$OUTPUT_DIR"
    mkdir -p "$OUTPUT_DIR"

    echo "📦 Archiving iOS app ($XC_CONFIG, unsigned, mode=$MODE)..."
    # 签名键全部命令行覆盖（优先级高于 pbxproj/xcconfig）：Release 配置块本身已写死关签名，
    # 但 Debug 配置块是 CODE_SIGN_STYLE=Automatic + DEVELOPMENT_TEAM=7ATCX9Q785 且无关签名键，
    # 完全靠这些 override 兜——含 CODE_SIGN_STYLE=Manual 压掉 Automatic，否则 Debug archive 会
    # 去找证书/profile 失败。
    # ONLY_ACTIVE_ARCH=NO 是 Debug 路径必加项（Release 配置默认 NO，无需此键）：pbxproj project
    # 级 Debug 开了 ONLY_ACTIVE_ARCH=YES，配 generic/platform=iOS 会缺 arm64 真机切片 → 未签名包
    # sideload 装真机报「不兼容此设备」。强制 NO 补回全架构，对 Release 无副作用，统一传。
    xcodebuild archive \
        -project "$XCODE_PROJECT" \
        -scheme "$SCHEME" \
        -configuration "$XC_CONFIG" \
        -archivePath "$ARCHIVE_PATH" \
        -destination "generic/platform=iOS" \
        ONLY_ACTIVE_ARCH=NO \
        CODE_SIGNING_ALLOWED=NO \
        CODE_SIGNING_REQUIRED=NO \
        CODE_SIGN_IDENTITY="" \
        CODE_SIGN_STYLE=Manual \
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
# 门禁按 mode 双向校验（mode 与 archive 的 -configuration 同源，不脱钩）：
#   release：内嵌 host 必含生产 bigsmart.space，且不得出现内网/LAN host —— 命中内网即 mode 污染。
#   debug：  内嵌 host 不得出现生产 bigsmart.space（debug 应连内网或 DDNS，混入生产 host 即 mode
#            污染）。不强制内网 host 必须出现：debug 连内网 auto-lan IP、debug-wan 连公网 DDNS
#            bigsmart.ddns.net，两者形态不同，统一只用「禁生产 host」这一反向不变量覆盖。
#   通用：   CFBundleVersion 必须等于 version.properties 当前 versionCode —— 不等即用了旧版本产物。
# 内网正则与 shared/build.gradle.kts isIntranetHost 同源（含 10. 段），杜绝双标准。
# default 必须是 release（fail-safe）：漏传 mode 时按最严生产标准校验，宁可错拦合法 debug 包
# （debug 只本地 sideload、不上夸克，误拦是噪音），绝不放行带内网地址的 release 包。
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

    /usr/bin/python3 - "$bin" "$plist" "$expected_code" "$MODE" <<'PY'
import sys, re, subprocess

bin_path, plist_path, expected_code, mode = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]

# host 实证：UTF-16LE 解码主二进制后搜 URL
data = open(bin_path, "rb").read().decode("utf-16-le", errors="ignore")
urls = set(re.findall(r"[a-z]+://[a-z0-9._:-]+", data))
prod = sorted(u for u in urls if "bigsmart.space" in u)
# 内网/LAN 判据与 shared/build.gradle.kts isIntranetHost 同源（10./192.168./172.16-31./127.0.0.1）。
intranet = sorted(
    u for u in urls
    if re.search(r"//(?:10\.|192\.168\.|172\.(?:1[6-9]|2[0-9]|3[01])\.|127\.0\.0\.1)", u)
    or ":9871" in u
)

ok = True
if mode == "release":
    # release：必含生产 host，且绝不能出现内网/LAN host。
    if not prod:
        print("  ❌ host：未找到生产 host bigsmart.space")
        ok = False
    else:
        print("  ✅ host：" + ", ".join(prod))
    if intranet:
        print("  ❌ host：检出内网/LAN host（QUANT_MODE 污染）：" + ", ".join(intranet))
        ok = False
else:
    # debug / debug-wan：绝不能出现生产 host（混入即 mode 污染）。debug 连内网、debug-wan 连
    # 公网 DDNS，形态不同，统一只用「禁生产 host」反向不变量；命中内网仅作信息提示，不判失败。
    if prod:
        print("  ❌ host：debug 包检出生产 host bigsmart.space（mode 污染，应为内网/DDNS host）：" + ", ".join(prod))
        ok = False
    elif intranet:
        print("  ✅ host：内网/LAN host（debug 预期）：" + ", ".join(intranet))
    else:
        print("  ✅ host：未检出生产 host（debug 预期；公网 DDNS 形态如 bigsmart.ddns.net 合法）")

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
# 主流程：版本前置同步 -> archive 出包 -> 门禁；不过则清缓存重出 -> 再门禁；二次仍不过硬退出。
# 版本前置同步消除「首次 archive 注入旧 CFBundleVersion -> 门禁挂 -> fallback 全量重链」的根因。
# ---------------------------------------------------------------------------
sync_ios_version
do_archive

echo ""
echo "🔍 Verifying ipa (binary evidence gate)..."
if verify_ipa; then
    echo ""
    echo "✅ Unsigned iOS ipa built and verified (mode=$MODE)."
    echo "   ipa:  $IPA_PATH"
    echo "   用户需自行签名后 sideload 安装（不签名无法直接安装到非越狱设备）。"
    exit 0
fi

echo ""
echo "⚠️  二进制门禁未通过，触发 fallback 全量重链重出一次..."
prepare_full_relink
do_archive

echo ""
echo "🔍 Re-verifying ipa after full relink..."
if verify_ipa; then
    echo ""
    echo "✅ Unsigned iOS ipa built and verified (mode=$MODE, after fallback full relink)."
    echo "   ipa:  $IPA_PATH"
    echo "   用户需自行签名后 sideload 安装（不签名无法直接安装到非越狱设备）。"
    exit 0
fi

echo ""
echo "❌ 全量重链后二进制门禁仍未通过，出包失败。" >&2
echo "   请人工检查：QUANT_MODE 注入、version.properties 与 Config.xcconfig 版本同步、Xcode DerivedData。" >&2
exit 1
