"""
V2 选股模型推理服务 (20维特征)。

兼容现有 profit_prediction_7pct.py 的请求格式，
使用 v2 的 20 维特征计算和模型。
"""

import argparse, json
from http.server import HTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from typing import Any
import numpy as np
import torch
import torch.nn as nn


# ===== V2 特征计算 (从 lhb_profit_v2_enhanced_train.py 提取) =====
def _rolling_mean(x: np.ndarray, window: int) -> np.ndarray:
    out = np.zeros_like(x, dtype=np.float64)
    cs = np.cumsum(np.insert(x.astype(np.float64), 0, 0.0))
    for i in range(len(x)):
        lo = max(0, i - window + 1)
        out[i] = (cs[i + 1] - cs[lo]) / (i - lo + 1)
    return out

def _safe_div(a: np.ndarray, b: np.ndarray, default: float = 0.0) -> np.ndarray:
    out = np.full_like(a, default, dtype=np.float64)
    ok = np.isfinite(a) & np.isfinite(b) & (np.abs(b) > 1e-12)
    out[ok] = a[ok] / b[ok]
    return out

def compute_features_v2(rows: list[dict[str, Any]]) -> np.ndarray:
    """计算20维特征：16原始 + 4新增(G1-G4)"""
    open_ = np.array([r["openQfq"] for r in rows], dtype=np.float64)
    high = np.array([r["highQfq"] for r in rows], dtype=np.float64)
    low = np.array([r["lowQfq"] for r in rows], dtype=np.float64)
    close = np.array([r["closeQfq"] for r in rows], dtype=np.float64)
    volume = np.array([r["volumeQfq"] for r in rows], dtype=np.float64)
    turnover = np.array([r.get("turnoverReal", [0]*len(rows)) for r in rows], dtype=np.float64)
    if turnover.sum() == 0:
        turnover = volume * close
    amount = volume * close

    prev_close = np.roll(close, 1); prev_close[0] = np.nan
    logret = np.log(_safe_div(close, prev_close, default=np.nan))
    logret[~np.isfinite(logret)] = 0.0

    ma20_turnover = _rolling_mean(np.clip(turnover, 0, None), 20)
    ma20_amount = _rolling_mean(np.clip(amount, 0, None), 20)
    ma20_volume = _rolling_mean(np.clip(volume, 0, None), 20)
    day_range = high - low

    # E1-E4
    e1 = np.where((turnover > 0) & (ma20_turnover > 0), np.log(np.clip(turnover / ma20_turnover, 1e-6, None)), 0.0)
    e2 = np.where((amount > 0) & (ma20_amount > 0), np.log(np.clip(amount / ma20_amount, 1e-6, None)), 0.0)
    e3 = _safe_div(open_ - prev_close, prev_close)
    e4 = np.zeros(len(rows), dtype=np.float64)
    for i in range(len(rows)):
        seg = logret[max(0, i - 4): i + 1]
        e4[i] = seg.sum() / (np.abs(seg).sum() + 1e-12)

    # R1-R4
    r1 = np.zeros(len(rows), dtype=np.float64)
    r2 = np.zeros(len(rows), dtype=np.float64)
    r3 = np.where(day_range > 1e-9, (high - close) / day_range, 0.0)
    r4 = -(logret - 2.0 * np.roll(logret, 1) + np.roll(logret, 2))
    r4[:2] = 0.0

    # P1-P4
    p1 = e3.copy()
    p2 = _safe_div(close - open_, open_)
    p3 = np.zeros(len(rows), dtype=np.float64); p4 = np.zeros(len(rows), dtype=np.float64)
    for i in range(len(rows)):
        lo = max(0, i - 4)
        p3[i] = p1[lo: i + 1].sum()
        p4[i] = p2[lo: i + 1].sum()

    # V1-V4
    vr = np.where(ma20_volume > 0, volume / ma20_volume, 1.0)
    erd = np.where(vr > 1e-9, np.log(np.clip(high / np.clip(low, 1e-9, None), 1.0, None)) / vr, 0.0)
    v2 = erd - np.roll(erd, 5); v2[:5] = 0.0
    vr_mean = _rolling_mean(vr, 60); vr_std = np.array([np.nanstd(vr[max(0,i-59):i+1]) for i in range(len(vr))])
    zvr = np.divide(vr - vr_mean, vr_std, out=np.zeros_like(vr), where=vr_std > 1e-9)
    amp = _safe_div(high - low, open_)
    amp_base = _rolling_mean(amp, 20)
    amp_rel = np.divide(amp, amp_base, out=np.ones_like(amp), where=amp_base > 1e-9)
    amp_shrink = 1.0 - amp_rel
    v3 = zvr * np.clip(amp_shrink, -2, 2)
    close_quality = np.where(day_range > 1e-9, (close - low) / day_range, 0.5)
    v4 = (1.0 - close_quality) * zvr

    # G1-G4
    gap = np.zeros(len(rows), dtype=np.float64)
    gap[1:] = (open_[1:] - prev_close[1:]) / np.clip(prev_close[1:], 1e-9, None)
    gap[~np.isfinite(gap)] = 0.0
    g1 = np.zeros(len(rows), dtype=np.float64)
    for i in range(len(rows)):
        lo = max(0, i - 4); g1[i] = gap[lo: i + 1].mean()

    vol5 = np.zeros(len(rows), dtype=np.float64); price5 = np.zeros(len(rows), dtype=np.float64)
    for i in range(len(rows)):
        lo = max(0, i - 4)
        vol5[i] = volume[i] / np.clip(volume[lo: i + 1].mean(), 1e-9, None)
        if i >= 4: price5[i] = close[i] / np.clip(close[i - 4], 1e-9, None) - 1.0
    g2 = (vol5 - 1.0) - price5 * 5

    open_pos = np.where(day_range > 1e-9, (open_ - low) / day_range, 0.5)
    g3 = open_pos * 2 - 1

    limit_pct = np.full(len(rows), 0.10, dtype=np.float64)
    for i in range(len(rows)):
        code = rows[i].get("tsCode", "")
        if isinstance(code, str):
            if code.startswith("688") or code.startswith("300") or code.startswith("301"):
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


# ===== V2 模型架构 =====
class SummaryMLP(nn.Module):
    def __init__(self, feature_dim, d_model, layers, dropout):
        super().__init__()
        in_dim = feature_dim * 5
        dims = [in_dim, d_model * 2] + [d_model] * (layers - 1)
        blocks = []
        for a, b in zip(dims[:-1], dims[1:]):
            blocks.extend([nn.Linear(a, b), nn.GELU(), nn.Dropout(dropout)])
        self.trunk = nn.Sequential(*blocks)
        self.head = nn.Linear(dims[-1], 1)

    def forward(self, x):
        summary = torch.cat([
            x.mean(dim=1), x[:, -1, :], x[:, -5:, :].mean(dim=1),
            x.std(dim=1), x[:, -1, :] - x[:, 0, :],
        ], dim=1)
        return self.head(self.trunk(summary)).squeeze(-1)


# ===== 推理服务 =====
def run_v2_service(args):
    device = torch.device('cpu')
    if torch.backends.mps.is_available(): device = torch.device('mps')
    elif torch.cuda.is_available(): device = torch.device('cuda')
    print(f"[V2] Device: {device}", flush=True)

    model_dir = Path(args.model_dir)
    manifest = json.load(open(model_dir / 'manifest.json'))
    normalization = json.load(open(model_dir / 'normalization.json'))

    feature_dim = manifest.get('feature_dim', 20)
    d_model = manifest.get('d_model', 256)
    print(f"[V2] Model: D={feature_dim} d_model={d_model}", flush=True)

    model = SummaryMLP(feature_dim, d_model, 3, 0.0).to(device)
    state = torch.load(model_dir / 'model.pt', map_location=device, weights_only=True)
    model.load_state_dict(state, strict=False)
    model.eval()
    print(f"[V2] Model loaded", flush=True)

    mean = np.array(normalization["mean"], dtype=np.float32)
    std = np.array(normalization["std"], dtype=np.float32)
    seq_len = 20
    top_n = args.top_n

    class V2Handler(BaseHTTPRequestHandler):
        def log_message(self, format, *args_log): pass

        def do_GET(self):
            if self.path == "/health":
                self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers()
                self.wfile.write(json.dumps({"status": "healthy", "model": "v2-20dim"}).encode())
            else:
                self.send_error(404)

        def do_POST(self):
            if self.path != "/predict":
                self.send_error(404); return
            try:
                payload = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))).decode())
            except Exception as e:
                self.send_error(400); return

            trade_date = payload.get("tradeDate", "")
            rows_by_code = {}
            for item in payload.get("stocks", []):
                rows = sorted(item.get("rows", []), key=lambda r: r["tradeDate"])
                if len(rows) >= seq_len:
                    rows_by_code[item["tsCode"]] = rows[-seq_len:]

            seqs = []; codes = []; skipped = []
            for ts_code in sorted(payload.get("universe", rows_by_code.keys())):
                rows = rows_by_code.get(ts_code)
                if not rows: skipped.append({"tsCode": ts_code, "reason": "no_data"}); continue
                if len(rows) != seq_len: skipped.append({"tsCode": ts_code, "reason": f"len={len(rows)}"}); continue
                if rows[-1]["tradeDate"] != trade_date: skipped.append({"tsCode": ts_code, "reason": "stale"}); continue
                try:
                    feats = compute_features_v2(rows)
                    seqs.append(feats); codes.append(ts_code)
                except Exception as e:
                    skipped.append({"tsCode": ts_code, "reason": f"feat:{e}"})

            predictions = []
            if seqs:
                x = np.clip((np.stack(seqs).astype(np.float32) - mean) / (std + 1e-6), -8, 8)
                with torch.no_grad():
                    scores = torch.sigmoid(model(torch.from_numpy(x).to(device))).cpu().numpy()
                for ts_code, score in zip(codes, scores.tolist()):
                    predictions.append({"tsCode": ts_code, "score": float(score)})

            predictions.sort(key=lambda r: (-r["score"], r["tsCode"]))
            selected = {r["tsCode"] for r in predictions[:top_n]}
            for row in predictions:
                row["selectedByTopN"] = row["tsCode"] in selected

            result = {
                "modelId": "v2-20dim",
                "topic": "profit-prediction-v2",
                "tradeDate": trade_date, "topN": top_n,
                "predictions": predictions, "skipped": skipped,
                "coverage": {"requested": len(payload.get("universe", [])), "scored": len(predictions), "skipped": len(skipped)},
            }
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            self.wfile.write(json.dumps(result, ensure_ascii=False, separators=(",", ":")).encode())

    server = HTTPServer(("", args.port), V2Handler)
    print(f"[V2] Serving on :{args.port} top_n={top_n}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", default="research/profit-prediction-v2/models/v2-lambda0.0")
    parser.add_argument("--port", type=int, default=9876)
    parser.add_argument("--top-n", type=int, default=5)
    args = parser.parse_args()
    run_v2_service(args)


if __name__ == "__main__":
    main()
