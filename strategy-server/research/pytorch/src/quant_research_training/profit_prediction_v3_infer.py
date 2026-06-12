"""
V3 正式版选股模型推理服务（诚实标签 SummaryMLP, V2 20 维特征）。

与 V2 推理服务的三处修正:
  1. 训练/推理窗口对齐: 接受 seq_len + context 根日线, 特征在全窗计算后取末 seq_len 行。
     训练侧特征在全历史上计算后切片, 最长滚动窗为 60 日; context >= 60 时两侧数值完全一致。
  2. 特征与训练精确对齐: turnoverReal 缺失记 0(e1 置 0), 不回退 volume*close。
  3. 完整参数契约: 接受 --mode / --threshold-name(Kotlin 启动协议), 未知参数不再导致启动崩溃。

模型包: manifest.json(feature_dim/d_model/layers/seq_len 经 normalization) + normalization.json + model.pt。
请求/响应与 V2 服务完全兼容: POST /predict {tradeDate, universe, stocks[{tsCode, rows}]}。

集成模型包: manifest.json 含 "ensemble_members": [兄弟目录名,...] 时, 本服务加载全部成员
(各自的 manifest/normalization/model.pt, 特征契约必须一致), 特征只计算一次, 各成员独立打分后
在请求内做百分位秩平均(请求 = 当日全候选截面, 与研究侧日内百分位集成语义一致)。
集成包目录自身不含 model.pt; /health 返回集成包身份。
"""

import argparse
import json
from http.server import HTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from typing import Any

import numpy as np
import torch
import torch.nn as nn

FEATURE_CONTEXT_DAYS = 60  # 最长滚动统计窗(vr 60日 z-score); 与训练全历史计算对齐所需的最小上下文


def _rolling_mean(x: np.ndarray, window: int) -> np.ndarray:
    out = np.zeros_like(x, dtype=np.float64)
    cs = np.cumsum(np.insert(x.astype(np.float64), 0, 0.0))
    for i in range(len(x)):
        lo = max(0, i - window + 1)
        out[i] = (cs[i + 1] - cs[lo]) / (i - lo + 1)
    return out


def _rolling_std(x: np.ndarray, window: int) -> np.ndarray:
    out = np.zeros_like(x, dtype=np.float64)
    for i in range(len(x)):
        lo = max(0, i - window + 1)
        out[i] = float(np.nanstd(x[lo: i + 1]))
    return out


def _safe_div(a: np.ndarray, b: np.ndarray, default: float = 0.0) -> np.ndarray:
    out = np.full_like(a, default, dtype=np.float64)
    ok = np.isfinite(a) & np.isfinite(b) & (np.abs(b) > 1e-12)
    out[ok] = a[ok] / b[ok]
    return out


def compute_features_v2(rows: list[dict[str, Any]]) -> np.ndarray:
    """20 维特征, 与训练侧 lhb_profit_v2_enhanced_train.compute_features_v2 逐项一致。"""
    open_ = np.array([r["openQfq"] for r in rows], dtype=np.float64)
    high = np.array([r["highQfq"] for r in rows], dtype=np.float64)
    low = np.array([r["lowQfq"] for r in rows], dtype=np.float64)
    close = np.array([r["closeQfq"] for r in rows], dtype=np.float64)
    volume = np.array([r["volumeQfq"] for r in rows], dtype=np.float64)
    turnover = np.array([float(r.get("turnoverReal") or 0.0) for r in rows], dtype=np.float64)
    amount = volume * close

    prev_close = np.roll(close, 1)
    prev_close[0] = np.nan
    logret = np.log(_safe_div(close, prev_close, default=np.nan))
    logret[~np.isfinite(logret)] = 0.0

    ma20_turnover = _rolling_mean(np.clip(turnover, 0, None), 20)
    ma20_amount = _rolling_mean(np.clip(amount, 0, None), 20)
    ma20_volume = _rolling_mean(np.clip(volume, 0, None), 20)
    day_range = high - low

    e1 = np.where((turnover > 0) & (ma20_turnover > 0), np.log(np.clip(turnover / ma20_turnover, 1e-6, None)), 0.0)
    e2 = np.where((amount > 0) & (ma20_amount > 0), np.log(np.clip(amount / ma20_amount, 1e-6, None)), 0.0)
    e3 = _safe_div(open_ - prev_close, prev_close)
    e4 = np.zeros(len(rows), dtype=np.float64)
    for i in range(len(rows)):
        seg = logret[max(0, i - 4): i + 1]
        e4[i] = seg.sum() / (np.abs(seg).sum() + 1e-12)

    r1 = np.zeros(len(rows), dtype=np.float64)
    r2 = np.zeros(len(rows), dtype=np.float64)
    r3 = np.where(day_range > 1e-9, (high - close) / day_range, 0.0)
    r4 = -(logret - 2.0 * np.roll(logret, 1) + np.roll(logret, 2))
    r4[:2] = 0.0

    p1 = e3.copy()
    p2 = _safe_div(close - open_, open_)
    p3 = np.zeros(len(rows), dtype=np.float64)
    p4 = np.zeros(len(rows), dtype=np.float64)
    for i in range(len(rows)):
        lo = max(0, i - 4)
        p3[i] = p1[lo: i + 1].sum()
        p4[i] = p2[lo: i + 1].sum()

    vr = np.where(ma20_volume > 0, volume / ma20_volume, 1.0)
    erd = np.where(vr > 1e-9, np.log(np.clip(high / np.clip(low, 1e-9, None), 1.0, None)) / vr, 0.0)
    v2 = erd - np.roll(erd, 5)
    v2[:5] = 0.0
    vr_mean = _rolling_mean(vr, 60)
    vr_std = _rolling_std(vr, 60)
    zvr = np.divide(vr - vr_mean, vr_std, out=np.zeros_like(vr), where=vr_std > 1e-9)
    amp = _safe_div(high - low, open_)
    amp_base = _rolling_mean(amp, 20)
    amp_rel = np.divide(amp, amp_base, out=np.ones_like(amp), where=amp_base > 1e-9)
    amp_shrink = 1.0 - amp_rel
    v3 = zvr * np.clip(amp_shrink, -2, 2)
    close_quality = np.where(day_range > 1e-9, (close - low) / day_range, 0.5)
    v4 = (1.0 - close_quality) * zvr

    gap = np.zeros(len(rows), dtype=np.float64)
    gap[1:] = (open_[1:] - prev_close[1:]) / np.clip(prev_close[1:], 1e-9, None)
    gap[~np.isfinite(gap)] = 0.0
    g1 = np.zeros(len(rows), dtype=np.float64)
    for i in range(len(rows)):
        lo = max(0, i - 4)
        g1[i] = gap[lo: i + 1].mean()

    vol5 = np.zeros(len(rows), dtype=np.float64)
    price5 = np.zeros(len(rows), dtype=np.float64)
    for i in range(len(rows)):
        lo = max(0, i - 4)
        vol5[i] = volume[i] / np.clip(volume[lo: i + 1].mean(), 1e-9, None)
        if i >= 4:
            price5[i] = close[i] / np.clip(close[i - 4], 1e-9, None) - 1.0
    g2 = (vol5 - 1.0) - price5 * 5

    open_pos = np.where(day_range > 1e-9, (open_ - low) / day_range, 0.5)
    g3 = open_pos * 2 - 1

    limit_pct = np.full(len(rows), 0.10, dtype=np.float64)
    for i in range(len(rows)):
        code = rows[i].get("tsCode", "")
        if isinstance(code, str) and code.startswith(("688", "300", "301")):
            limit_pct[i] = 0.20
    limit_price = prev_close * (1.0 + limit_pct)
    g4 = np.where(limit_price > 1e-9, close / limit_price, 0.5)
    g4 = np.clip(g4, 0.5, 1.0)

    features = np.stack([
        e1, e2, e3, e4, r1, r2, r3, r4,
        p1, p2, p3, p4, erd, v2, v3, v4,
        g1, g2, g3, g4,
    ], axis=1)
    features[~np.isfinite(features)] = 0.0
    return np.clip(features, -10.0, 10.0).astype(np.float32)


class SummaryMLP(nn.Module):
    def __init__(self, feature_dim: int, d_model: int, layers: int, dropout: float) -> None:
        super().__init__()
        in_dim = feature_dim * 5
        dims = [in_dim, d_model * 2] + [d_model] * (layers - 1)
        blocks: list[nn.Module] = []
        for a, b in zip(dims[:-1], dims[1:]):
            blocks.extend([nn.Linear(a, b), nn.GELU(), nn.Dropout(dropout)])
        self.trunk = nn.Sequential(*blocks)
        self.head = nn.Linear(dims[-1], 1)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        summary = torch.cat([
            x.mean(dim=1), x[:, -1, :], x[:, -5:, :].mean(dim=1),
            x.std(dim=1), x[:, -1, :] - x[:, 0, :],
        ], dim=1)
        return self.head(self.trunk(summary)).squeeze(-1)


def run_service(args: argparse.Namespace) -> None:
    device = torch.device("cpu")
    if torch.backends.mps.is_available():
        device = torch.device("mps")
    elif torch.cuda.is_available():
        device = torch.device("cuda")
    print(f"[V3] Device: {device}", flush=True)

    model_dir = Path(args.model_dir)
    manifest = json.load(open(model_dir / "manifest.json"))
    model_id = f"{manifest.get('model_name', 'v3-honest')}-{manifest.get('version', '')}"

    member_names = manifest.get("ensemble_members")
    member_dirs = [model_dir.parent / name for name in member_names] if member_names else [model_dir]

    members: list[tuple[nn.Module, np.ndarray, np.ndarray]] = []
    feature_dim: int | None = None
    seq_len: int | None = None
    for member_dir in member_dirs:
        m_manifest = json.load(open(member_dir / "manifest.json"))
        m_norm = json.load(open(member_dir / "normalization.json"))
        m_feature_dim = int(m_manifest.get("feature_dim", 20))
        m_seq_len = int(m_norm.get("seq_len", 20))
        if feature_dim is None:
            feature_dim, seq_len = m_feature_dim, m_seq_len
        elif (m_feature_dim, m_seq_len) != (feature_dim, seq_len):
            raise RuntimeError(
                f"ensemble member feature contract mismatch: {member_dir.name} "
                f"D={m_feature_dim}/seq={m_seq_len} vs D={feature_dim}/seq={seq_len}"
            )
        model = SummaryMLP(m_feature_dim, int(m_manifest.get("d_model", 256)), int(m_manifest.get("layers", 3)), 0.0).to(device)
        state = torch.load(member_dir / "model.pt", map_location=device, weights_only=True)
        model.load_state_dict(state)
        model.eval()
        members.append((
            model,
            np.array(m_norm["mean"], dtype=np.float32),
            np.array(m_norm["std"], dtype=np.float32),
        ))
        print(f"[V3] Member loaded: {member_dir.name} D={m_feature_dim}", flush=True)
    print(f"[V3] Model ready: {model_id} members={len(members)} D={feature_dim}", flush=True)

    top_n = args.top_n
    threshold_name = args.threshold_name

    class V3Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *log_args):
            pass

        def do_GET(self):
            if self.path == "/health":
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"status": "healthy", "model": model_id}).encode())
            else:
                self.send_error(404)

        def do_POST(self):
            if self.path != "/predict":
                self.send_error(404)
                return
            try:
                payload = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))).decode())
            except Exception:
                self.send_error(400)
                return

            trade_date = payload.get("tradeDate", "")
            rows_by_code: dict[str, list[dict[str, Any]]] = {}
            for item in payload.get("stocks", []):
                rows = sorted(item.get("rows", []), key=lambda r: r["tradeDate"])
                if len(rows) >= seq_len:
                    # Kotlin 行级契约不含 tsCode, 但 g4 涨停价通道按代码前缀取 10cm/20cm;
                    # 训练行逐行携带 tsCode, 此处注入 stock 级代码保证 train/serve 一致
                    # (缺失时创业板 g4 会被恒按 10cm 涨停价计算, 系统性放大 ~1.09 倍)
                    for row in rows:
                        row["tsCode"] = item["tsCode"]
                    rows_by_code[item["tsCode"]] = rows[-(seq_len + FEATURE_CONTEXT_DAYS):]

            seqs: list[np.ndarray] = []
            codes: list[str] = []
            skipped: list[dict[str, str]] = []
            for ts_code in sorted(payload.get("universe", rows_by_code.keys())):
                rows = rows_by_code.get(ts_code)
                if not rows:
                    skipped.append({"tsCode": ts_code, "reason": "no_data"})
                    continue
                if rows[-1]["tradeDate"] != trade_date:
                    skipped.append({"tsCode": ts_code, "reason": "stale"})
                    continue
                try:
                    feats = compute_features_v2(rows)[-seq_len:]
                    if len(feats) != seq_len:
                        skipped.append({"tsCode": ts_code, "reason": f"len={len(feats)}"})
                        continue
                    seqs.append(feats)
                    codes.append(ts_code)
                except Exception as e:
                    skipped.append({"tsCode": ts_code, "reason": f"feat:{e}"})

            predictions = []
            if seqs:
                feats = np.stack(seqs).astype(np.float32)
                member_scores: list[np.ndarray] = []
                for model, mean, std in members:
                    x = np.clip((feats - mean) / (std + 1e-6), -8, 8)
                    with torch.no_grad():
                        member_scores.append(torch.sigmoid(model(torch.from_numpy(x).to(device))).cpu().numpy())
                if len(member_scores) == 1:
                    scores = member_scores[0]
                else:
                    # 集成: 请求内(=当日候选截面)百分位秩平均, 与研究侧 PCT 日内百分位语义一致
                    n = len(codes)
                    rank_sum = np.zeros(n, dtype=np.float64)
                    for member_score in member_scores:
                        rank = np.empty(n, dtype=np.float64)
                        rank[np.argsort(member_score, kind="stable")] = np.arange(n, dtype=np.float64)
                        rank_sum += rank / max(n - 1, 1)
                    scores = rank_sum / len(member_scores)
                for ts_code, score in zip(codes, scores.tolist()):
                    predictions.append({"tsCode": ts_code, "score": float(score)})

            predictions.sort(key=lambda r: (-r["score"], r["tsCode"]))
            selected = {r["tsCode"] for r in predictions[:top_n]}
            for row in predictions:
                row["selectedByThreshold"] = False
                row["selectedByTopN"] = row["tsCode"] in selected

            result = {
                "modelId": model_id,
                "topic": "profit-prediction-v3",
                "tradeDate": trade_date,
                "thresholdName": threshold_name,
                "threshold": 0.5,
                "topN": top_n,
                "predictions": predictions,
                "skipped": skipped,
                "coverage": {
                    "requested": len(payload.get("universe", [])),
                    "scored": len(predictions),
                    "skipped": len(skipped),
                },
            }
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            self.wfile.write(json.dumps(result, ensure_ascii=False, separators=(",", ":")).encode())

    server = HTTPServer(("", args.port), V3Handler)
    print(f"[V3] Serving on :{args.port} top_n={top_n} seq_len={seq_len} context={FEATURE_CONTEXT_DAYS}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", default="infer-service", choices=["infer-service"])
    parser.add_argument("--model-dir", default="research/profit-prediction-v2/models/v3-honest-20260611")
    parser.add_argument("--port", type=int, default=9876)
    parser.add_argument("--top-n", type=int, default=5)
    parser.add_argument("--threshold-name", default="recall_0_2")
    args = parser.parse_args()
    run_service(args)


if __name__ == "__main__":
    main()
