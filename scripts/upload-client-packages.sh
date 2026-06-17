#!/bin/bash
# 客户端安装包上传夸克网盘固定目录 /BigSmart（固定分享页 667221bcabd6 实际绑定的文件夹）。
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

# 4) 上传目标目录 = 固定分享页 667221bcabd6 实际绑定的文件夹。
#    实测核账：分享 667221bcabd6（share_id 8d170e29…）的 first_fid 40ae7945… 指向 /BigSmart，
#    而非历史脚本默认的 /quant-client（fid d234bd32…）。两者是互不相干的文件夹：包传进
#    /quant-client 永远不会出现在分享页，用户扫码只能下到 /BigSmart 里的旧包。夸克【文件夹分享】
#    是动态的（分享渲染该文件夹实时内容），把包传进分享真正绑定的 /BigSmart，新包即实时出现在
#    667221bcabd6，URL 不变（URL 已编译期注入前端二维码，是硬约束，不能换）。
QUARK_DIR="/BigSmart"

# 5) 远端固定文件名（覆盖式分发，分享页恒只展示这一份最新包）。
REMOTE_APK="${QUARK_DIR}/Quant.apk"
REMOTE_IPA="${QUARK_DIR}/Quant.ipa"

# 6) 清掉 /BigSmart 里所有【带版本号/时间戳】的历史遗留包（Quant-*.apk / Quant-*.ipa），
#    只保留固定名 Quant.apk / Quant.ipa。整段为尽力而为，任何失败都不阻断主链路上传。
#    历史包来自旧分发方案（时间戳命名 / versionCode 自增的 Quant-v0.0.1-NN）。它们与固定名不同名，
#    覆盖式上传碰不到，会与新固定名包在分享页并列让用户下错。正则 ^Quant-.*\.(apk|ipa)$ 要求
#    `Quant-` 前缀，固定名 Quant.apk/Quant.ipa 因无该前缀被天然排除，不会误删当前分发包。
#
#    两个已实测钉死的远端非确定性，本段都对症处理：
#      ① kuake list "${QUARK_DIR}" 有约 1/8 瞬时失败率（偶发 success:false 拿不到列表）。
#         → 对每次 list 加重试；只有 JSON success:true 才算拿到有效结果。
#      ② kuake delete 返回「删除成功」后是【异步最终一致】：文件延迟抖动消失（立即 ~ 2-3 秒不等），
#         发完 delete 立刻 list 仍可能看到该文件——这是「发完 delete 就走」会偶发漏清的真因。
#         → delete 之后【轮询 list 确认目标真从列表消失】，而非发完就走。
#    （`--stream` 模式在本环境间歇拉不到列表，不可用于清理判定，故统一用非 stream list。）
#
# set -euo pipefail 安全要点：把可能失败的 `kuake list | python3` 整体放进 if 条件位（条件中命令
# 失败不触发 set -e），用 python 退出码作 list 成败判据，避免 || true 吞掉失败信号。python 在
# success:true 时先打印哨兵行 OK 再逐行打印 legacy path（path 必含 /BigSmart/ 前缀，与裸 OK 不同形，
# tail -n +2 剥哨兵无歧义）；任何失败一律 sys.exit(1)，由 pipefail 抓为管道失败。

# list+解析原语：单次 list 并解析出当前全部 legacy path。
#   成功（list 拿到 success:true 的有效列表）：把 legacy path 逐行写到 stdout（可能 0 行），返回 0。
#   失败（kuake 非零退出 / JSON success 非 true / 非合法 JSON）：返回非 0，stdout 不可信。
# 发现阶段与删后确认阶段复用此同一原语——正则只有一处、口径统一。
list_legacy() {
    local out
    if out="$(
        kuake list "${QUARK_DIR}" 2>/dev/null | QUARK_DIR="${QUARK_DIR}" python3 -c '
import json, os, re, sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(1)
if d.get("success") is not True:
    sys.exit(1)
base = os.environ["QUARK_DIR"]
print("OK")
for f in d.get("data", {}).get("list", []):
    name = f.get("file_name", "")
    if re.match(r"^Quant-.*\.(apk|ipa)$", name):
        print(f.get("path") or base + "/" + name)
'
    )"; then
        # python 返回 0：list 成功。剥掉首行哨兵 OK，剩余即为 legacy path 列表（可能为空）。
        printf '%s\n' "$out" | tail -n +2
        return 0
    fi
    return 1
}

# —— 发现阶段：带重试拿到当前全部 legacy 包 ——
# list 偶发失败（约 1/8）→ 最多重试 5 次；只有某次成功才采纳结果（哪怕 0 个 legacy 也不再重试）。
LEGACY_PATHS=""
list_ok=0
for attempt in 1 2 3 4 5; do
    if LEGACY_PATHS="$(list_legacy)"; then
        list_ok=1
        break
    fi
    if [ "$attempt" -lt 5 ]; then sleep 1; fi
done

if [ "$list_ok" -ne 1 ]; then
    echo "[upload-client-packages] 警告：kuake list 连续多次失败，本次未清理 legacy 历史包，请关注分享页是否残留旧版本包。" >&2
fi

# —— 删除 + 确认阶段 ——
# list 成功且匹配到 legacy 包时才删；list 失败时 LEGACY_PATHS 为空，天然跳过（尽力而为，不阻断上传）。
if [ -n "$LEGACY_PATHS" ]; then
    # 先逐个发 delete（delete 失败也告警，不纯 || true 静默吞，避免在 delete 侧重蹈静默漏清）。
    #
    # ★ 关键陷阱（实测根因）：本循环用 here-string `done <<< "$LEGACY_PATHS"` 把列表喂给 read 的
    #   stdin。kuake 支持 pipe mode，会主动【探读 stdin】——在循环体里直接 `kuake delete` 会抢读
    #   here-string 的剩余内容，导致 delete 实际拿到污染输入、变成空操作（返回空、文件没真删，
    #   且把 read 的后续行也吃掉）。实测现象：delete「看似成功」但文件始终在，多包时还表现为
    #   「总残留一个」。修复：循环体内所有 kuake 调用都用 `</dev/null` 切断 stdin，杜绝抢读。
    while IFS= read -r legacy_path; do
        [ -z "$legacy_path" ] && continue
        if ! kuake delete "$legacy_path" </dev/null >/dev/null 2>&1; then
            echo "[upload-client-packages] 警告：删除 legacy 包失败：${legacy_path}（请关注分享页是否残留）。" >&2
        fi
    done <<< "$LEGACY_PATHS"

    # delete 后轮询 list 确认全部 legacy 真从列表消失（delete 偶有最终一致延迟）。
    #   判据稳：只有「list 成功(list_legacy 返回 0) 且返回集合为空」才算确认清理完成。
    #   list 本轮失败（约 1/8）→ list_legacy 返回非 0、if 取假、不误判为已删，sleep 后继续轮询。
    #   仍有残留 → delete 尚未生效，sleep 后继续等。
    # 上限 6 次≈6 秒：覆盖实测删除生效抖动并留余量；超时即降级告警、不阻断、下次 deploy 自愈。
    confirmed=0
    remaining=""
    for poll in 1 2 3 4 5 6; do
        if remaining="$(list_legacy)"; then
            if [ -z "$remaining" ]; then
                confirmed=1
                break
            fi
            # 集合非空：删除尚未生效，继续等。
        fi
        # list 失败 或 仍有残留：本轮不作数，间隔后重试（最后一轮不必再 sleep）。
        if [ "$poll" -lt 6 ]; then sleep 1; fi
    done

    # 超时兜底：轮询耗尽仍未确认清空 → 告警可观测，不 exit、不阻断上传（下次 deploy 重清自愈）。
    if [ "$confirmed" -ne 1 ]; then
        echo "[upload-client-packages] 警告：轮询确认超时，legacy 历史包可能仍残留于分享页（delete 异步未及时生效或 list 持续失败）。" >&2
        if [ -n "$remaining" ]; then
            echo "[upload-client-packages] 仍可见的残留包：" >&2
            printf '%s\n' "$remaining" | sed 's/^/[upload-client-packages]   - /' >&2
        fi
        echo "[upload-client-packages] 不阻断上传；下次 deploy 会重新清理收敛。" >&2
    fi
fi

# 7) 原子替换上传：先传到临时名 .new，成功后才 rename 成正式名。
#    背景：kuake upload 对远端【已存在同名文件】是秒传去重——返回「文件已存在，跳过上传」且不更新
#    内容（已实测：同名二次上传 size 不变）。所以固定名要真正"覆盖更新"必须先 delete 同名再传。
#    但「先删正式名再传」若 upload 中途失败，会留下『旧包已删、新包没传』的空窗，分享页彻底无包。
#    改为：传到临时名 .new（临时名也先删以绕过秒传）→ upload 成功 → 删旧正式名 → rename .new 为
#    正式名。rename 是亚秒级元数据操作、不受秒传影响，把空窗从「整个 upload 时长」收窄到「delete
#    final 与 rename 之间的亚秒级缝隙」。
#    边界诚实说明：kuake rename 到【已存在目标名会失败】（实测 status 400「存在同名文件」），故无法
#    省掉 delete-final、也无法靠 rename 覆盖；因此 delete-final 之后、rename 之前若 rename 失败，
#    远端会只剩 .new、正式名缺失。为此对 rename 失败做兜底：重试一次，仍失败则【明确告警引导手动
#    修复】（远端只剩 .new，提示手动 rename 或重跑），而非 set -e 静默退出留半成品。正常路径
#    （rename 前 final 已删、不会撞同名）下 rename 几乎不失败；下次 deploy 重跑也会重新 upload+rename
#    自愈。
upload_atomic() {
    # $1 本地文件  $2 远端正式路径  $3 正式文件名（rename 的 newName，纯文件名不带路径）
    # 所有 kuake 调用统一 `</dev/null` 切断 stdin：kuake 支持 pipe mode 会探读 stdin，纵深防御
    # 避免本函数将来若被挪进 read 循环/here-string 上下文时抢读 stdin（见清理段同款陷阱说明）。
    local local_file="$1" remote_final="$2" final_name="$3"
    local remote_tmp="${remote_final}.new"

    # upload 整体重试：~50MB 大包遇分片网络超时，kuake 内部重试 3 次仍会以非零退出
    # （UPLOAD_PART_ERROR / operation timed out，实测约每 5~7 次跑撞 1 次）。set -euo pipefail 下
    # 裸 upload 一旦非零会立刻 set -e 退出整脚本 → 后续 rename 不做、ipa 也不传，那次发版只传半套。
    # 对偶发网络抖动加整体重试自愈：把 upload 放进 if 条件位（条件位命令失败不触发 set -e），
    # 失败 sleep 2 后重试；3 次（首次+2 重试）全部耗尽才算真失败、return 1。不无限重试，持续网络
    # 问题就该如实报失败让人工介入。
    local attempt
    local uploaded=false
    for attempt in 1 2 3; do
        # 每次重传前都先删 .new：kuake upload 对远端同名是秒传去重（已存在则跳过不更新内容）。
        # 上一次 upload 中途超时很可能在远端落下【不完整的 .new 分片】，若不先删，本次重传会命中
        # 秒传去重、判定「已存在跳过」，把损坏的 .new 当成功 → rename 成正式名 → 分享页拿到坏包。
        # 先 delete 确保每次都是干净重传而非秒传跳过坏文件。
        kuake delete "$remote_tmp" </dev/null >/dev/null 2>&1 || true
        if kuake upload "$local_file" "$remote_tmp" </dev/null; then
            uploaded=true
            break
        fi
        if [ "$attempt" -lt 3 ]; then
            echo "⚠️  upload 第 ${attempt}/3 次失败（${remote_tmp}），2s 后重试…" >&2
            sleep 2
        fi
    done
    if [ "$uploaded" != true ]; then
        echo "❌ upload 重试 3 次仍失败：${local_file} → ${remote_tmp}（持续网络问题，需人工介入）。" >&2
        return 1
    fi

    kuake delete "$remote_final" </dev/null >/dev/null 2>&1 || true
    # rename 失败兜底：再试一次；仍失败则不静默退出，明确告警当前远端只剩 .new、引导手动修复。
    if ! kuake rename "$remote_tmp" "$final_name" </dev/null >/dev/null 2>&1; then
        if ! kuake rename "$remote_tmp" "$final_name" </dev/null >/dev/null 2>&1; then
            echo "❌ rename 失败：远端 ${remote_tmp} 已上传但未切换为 ${remote_final}。" >&2
            echo "   分享页暂缺该包，请手动执行：kuake rename \"${remote_tmp}\" \"${final_name}\"，或重跑本脚本。" >&2
            return 1
        fi
    fi
}

# 8) 上传 APK（始终出包）。
upload_atomic "build/client-packages/Quant.apk" "$REMOTE_APK" "Quant.apk"
echo "✅ APK 上传 → ${REMOTE_APK}"

# 9) 上传 ipa（缺失仅告警，非 macOS 无 ipa 合法）。
if [ -f "build/client-packages/Quant.ipa" ]; then
    upload_atomic "build/client-packages/Quant.ipa" "$REMOTE_IPA" "Quant.ipa"
    echo "✅ ipa 上传 → ${REMOTE_IPA}"
else
    echo "ℹ️  ipa 缺失（非 macOS 或未出 ipa），仅传 APK。"
fi

echo "📎 固定分享页：https://pan.quark.cn/s/667221bcabd6"
