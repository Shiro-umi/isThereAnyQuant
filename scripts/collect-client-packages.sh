#!/bin/bash
#
# 客户端出包收敛脚本。
#
# 把分散在各自构建目录的 Android APK 与 iOS ipa 幂等收敛到统一产物目录
# build/client-packages/ 下的固定名 Quant.apk / Quant.ipa，供 upload-client-packages.sh
# 上传夸克网盘分发（见 deployment-architecture.md §6.5 客户端出包夸克分发子节）。
#
# 与 copyApk + /api/download/apk 直下链路无关，两条分发链路彼此独立。
#
# 源产物约定：
#   APK：compose-app/build/outputs/apk/release/ 下的 *.apk（文件名随构建变体浮动，
#        如 compose-app-release.apk），由 deploy.sh release 的 Android 出包段产出。
#   ipa：build/ios-ipa/Quant.ipa，由 scripts/build-ios-ipa.sh 在 macOS 上产出。
#
# 失败语义：
#   APK 缺失 -> 硬失败（exit 非 0），提示先跑 deploy.sh release。
#   ipa 缺失 -> 仅告警不失败（非 macOS 环境无 ipa 是合法状态）。
#
set -euo pipefail

# 切到项目根（脚本位于 scripts/ 下，根 = 脚本目录的上一级）。
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${PROJECT_ROOT}"

APK_SRC_DIR="compose-app/build/outputs/apk/release"
IPA_SRC="build/ios-ipa/Quant.ipa"
OUT_DIR="build/client-packages"
OUT_APK="${OUT_DIR}/Quant.apk"
OUT_IPA="${OUT_DIR}/Quant.ipa"

mkdir -p "${OUT_DIR}"

# --- APK：取 release 目录顶层 *.apk（-maxdepth 1 排除 baselineProfiles 等子目录产物）---
# 用 -print -quit 让 find 自己在首个匹配后退出，不接 head 管道，避免 set -euo pipefail 下
# head 提前关闭管道令 find 收到 SIGPIPE（退出码 141）被 pipefail 上抛中止脚本的脆弱性。
APK_SRC=""
if [ -d "${APK_SRC_DIR}" ]; then
  APK_SRC="$(find "${APK_SRC_DIR}" -maxdepth 1 -type f -name '*.apk' -print -quit)"
fi

if [ -z "${APK_SRC}" ]; then
  echo "[collect] 错误：未在 ${APK_SRC_DIR} 找到 *.apk。" >&2
  echo "[collect] 请先执行 ./deploy.sh release 产出 Android release APK 后重试。" >&2
  exit 1
fi

# 断言顶层只有一个当前 apk：多于一个说明残留了上一变体的旧包，取任意一个可能分发陈旧产物。
# 显式硬失败提示先清构建目录，把"多 apk"从静默风险变成可诊断的失败。
APK_COUNT="$(find "${APK_SRC_DIR}" -maxdepth 1 -type f -name '*.apk' | wc -l | tr -d '[:space:]')"
if [ "${APK_COUNT}" != "1" ]; then
  echo "[collect] 错误：${APK_SRC_DIR} 顶层存在 ${APK_COUNT} 个 *.apk，无法确定当前产物。" >&2
  echo "[collect] 请先清理旧构建产物（如 ./gradlew :compose-app:clean 或删除该目录残留 apk）后重试。" >&2
  exit 1
fi

# 幂等：每次覆盖固定名，多次执行结果一致。
cp -f "${APK_SRC}" "${OUT_APK}"
echo "[collect] APK 已收敛：${APK_SRC} -> ${OUT_APK}"

# --- ipa：缺失仅告警，不阻断（非 macOS 无 ipa 合法）---
if [ -f "${IPA_SRC}" ]; then
  cp -f "${IPA_SRC}" "${OUT_IPA}"
  echo "[collect] ipa 已收敛：${IPA_SRC} -> ${OUT_IPA}"
else
  echo "[collect] 警告：未找到 ${IPA_SRC}，跳过 ipa 收敛（非 macOS 环境属正常）。" >&2
fi

echo "[collect] 客户端产物收敛完成，目录：${OUT_DIR}"
