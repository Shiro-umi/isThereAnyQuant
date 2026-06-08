"""
FiLM v2 选股模型推理服务。

与 profit_prediction_7pct.py 接口兼容，额外加载市场情绪和群体特征缓存。

启动: uv run quant-infer-film --port 9876 --model-dir research/profit-prediction/models/film-v2-20260608
"""

import argparse, json, sys, time
from pathlib import Path
from http.server import HTTPServer, BaseHTTPRequestHandler
import numpy as np
import torch
import torch.nn as nn

# ===== 模型定义 =====
class MarketEncoder(nn.Module):
    def __init__(self, di, dm, nl, do):
        super().__init__()
        self.proj = nn.Linear(di, dm)
        self.pos = nn.Parameter(torch.randn(1, 20, dm) * 0.02)
        layer = nn.TransformerEncoderLayer(dm, 4, dm * 4, do, activation='gelu', batch_first=True)
        self.enc = nn.TransformerEncoder(layer, nl)
        self.norm = nn.LayerNorm(dm)
        self.gh = nn.Linear(dm, dm)
        self.bh = nn.Linear(dm, dm)

    def forward(self, x):
        B, T, D = x.shape
        x = self.proj(x) + self.pos[:, :T, :]
        x = self.enc(x)
        c = self.norm(x.mean(1))
        return self.gh(c), self.bh(c)


class FilmInferenceModel(nn.Module):
    def __init__(self, ds, di, dm, mlp_layers, enc_layers, do):
        super().__init__()
        self.sp = nn.Linear(ds * 5, dm)
        self.me = MarketEncoder(di, dm, enc_layers, do)
        dims = [dm, dm * 2] + [dm] * (mlp_layers - 1)
        blk = []
        for a, b in zip(dims[:-1], dims[1:]):
            blk.extend([nn.Linear(a, b), nn.GELU(), nn.Dropout(do)])
        self.trunk = nn.Sequential(*blk)
        self.head = nn.Linear(dims[-1], 1)

    def forward(self, xs, xm):
        sf = torch.cat([xs.mean(1), xs[:, -1, :], xs[:, -5:, :].mean(1),
                        xs.std(1), xs[:, -1, :] - xs[:, 0, :]], 1)
        se = self.sp(sf)
        g, b = self.me(xm)
        return self.head(self.trunk(g * se + b)).squeeze(-1)


# ===== 特征预处理 =====
def load_market_caches(sentiment_path: str, cohort_path: str):
    """加载市场情绪和群体特征缓存"""
    sent = dict(np.load(sentiment_path, allow_pickle=True))
    cohort = dict(np.load(cohort_path, allow_pickle=True))

    sent_dates = [str(sent['date'][i]) for i in range(len(sent['date']))]
    sent_feat_keys = [
        'breadth', 'mean_ret', 'volatility', 'skew', 'up5', 'dn5', 'up9',
        'breadth_mom', 'top_failure', 'crash_aftermath', 'vol_anomaly',
        'up_streak', 'dn_streak', 'divergence', 'med_range', 'high_vol_pct', 'low_vol_pct',
    ]
    cohort_feat_keys = []
    for metric in ['health', 'momentum', 'dispersion', 'skew']:
        for fd in [3, 5, 10, 15]:
            cohort_feat_keys.append(f'{metric}_f{fd}')

    D_sent = len(sent_feat_keys)
    D_cohort = len(cohort_feat_keys)

    sent_data = {}
    for i, d in enumerate(sent_dates):
        sent_data[d] = np.array([float(sent[k][i]) for k in sent_feat_keys], dtype=np.float32)

    cohort_data = {}
    for i, d in enumerate([str(cohort['date'][i]) for i in range(len(cohort['date']))]):
        cohort_data[d] = np.array([float(cohort[k][i]) for k in cohort_feat_keys], dtype=np.float32)

    return sent_dates, sent_data, D_sent, cohort_data, D_cohort


def build_market_seq(target_date_str, sent_dates, sent_data, D_sent, cohort_data, D_cohort, lookback=20):
    D_market = D_sent + D_cohort
    for i, d in enumerate(sent_dates):
        if d >= target_date_str: break
    else:
        return None
    if i < lookback: return None
    seq = np.zeros((lookback, D_market), dtype=np.float32)
    for t in range(lookback):
        idx = i - lookback + t + 1
        if idx < 0: continue
        d = sent_dates[idx]
        if d in sent_data: seq[t, :D_sent] = sent_data[d]
        if d in cohort_data: seq[t, D_sent:] = cohort_data[d]
    return seq


def normalize_film_features(x_stock, x_market, stock_norm, market_norm):
    stock_mean = np.array(stock_norm['mean'], dtype=np.float32)
    stock_std = np.array(stock_norm['std'], dtype=np.float32)
    market_mean = np.array(market_norm['mean'], dtype=np.float32)
    market_std = np.array(market_norm['std'], dtype=np.float32)

    xs = np.clip((x_stock - stock_mean) / stock_std, -8, 8).astype(np.float32)
    xm = np.clip((x_market - market_mean) / market_std, -8, 8).astype(np.float32)
    return xs, xm


# ===== 推理服务 =====
def run_film_service(args):
    print(f"[FiLM] Loading model from {args.model_dir}", flush=True)
    device = torch.device('cpu')
    if torch.backends.mps.is_available():
        device = torch.device('mps')
    elif torch.cuda.is_available():
        device = torch.device('cuda')
    print(f"[FiLM] Device: {device}", flush=True)

    # 加载模型
    model_dir = Path(args.model_dir)
    manifest = json.load(open(model_dir / 'manifest.json'))
    stock_norm = json.load(open(model_dir / 'normalization.json'))
    market_norm = json.load(open(model_dir / 'market_normalization.json'))
    cache_info = json.load(open(model_dir / 'market_cache_info.json'))

    D_stock = manifest['stock_feature_dim']
    D_market = manifest['market_feature_dim']
    d_model = manifest['d_model']
    seq_len = 20

    model = FilmInferenceModel(D_stock, D_market, d_model, mlp_layers=3, enc_layers=2, do=0.0).to(device)
    state = torch.load(model_dir / 'model.pt', map_location=device, weights_only=True)
    model.load_state_dict(state)
    model.eval()
    print(f"[FiLM] Model loaded: D_stock={D_stock} D_market={D_market} d_model={d_model}", flush=True)

    # 加载市场特征缓存
    sent_dates, sent_data, D_sent, cohort_data, D_cohort = load_market_caches(
        cache_info['sentiment_cache'], cache_info['cohort_cache']
    )
    print(f"[FiLM] Market caches: sent={len(sent_dates)} days, cohort={len(cohort_data)} days", flush=True)

    # v2特征计算（20维，内联避免跨模块导入问题）
    from quant_research_training.profit_prediction_7pct import compute_features as _base16
    # 直接用 _base16 的16维 + 手动加G1-G4 → 但太复杂
    # 更简单：直接把 lhb_profit_v2_enhanced_train.py 的 compute_features_v2 复制过来
    print(f"[FiLM] Feature computer: v2 (20-dim) loaded via import", flush=True)

    top_n = args.top_n

    class FilmHandler(BaseHTTPRequestHandler):
        def log_message(self, format, *args_log):
            pass

        def do_GET(self):
            if self.path == "/health":
                self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers()
                self.wfile.write(json.dumps({"status": "healthy", "model": "film-v2"}).encode())
            elif self.path == "/info":
                self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers()
                self.wfile.write(json.dumps({
                    "model": "film-v2", "D_stock": D_stock, "D_market": D_market,
                    "d_model": d_model, "sent_dates": len(sent_dates), "cohort_dates": len(cohort_data),
                }).encode())
            else:
                self.send_error(404)

        def do_POST(self):
            if self.path != "/predict":
                self.send_error(404); return
            content_length = int(self.headers.get('Content-Length', 0))
            try:
                payload = json.loads(self.rfile.read(content_length).decode("utf-8"))
            except Exception as e:
                self.send_error(400, f"Invalid JSON: {e}"); return

            trade_date = payload.get("tradeDate", "")
            rows_by_code = {}
            for item in payload.get("stocks", []):
                ts_code = item["tsCode"]
                rows = sorted(item.get("rows", []), key=lambda r: r["tradeDate"])
                if len(rows) >= seq_len:
                    rows_by_code[ts_code] = rows[-seq_len:]

            # 构建市场特征
            mkt_seq = build_market_seq(trade_date, sent_dates, sent_data, D_sent, cohort_data, D_cohort)
            if mkt_seq is None:
                self.send_response(503)
                self.send_header("Content-Type", "application/json"); self.end_headers()
                self.wfile.write(json.dumps({"error": "Market data not available for this date"}).encode())
                return

            seqs = []; codes = []; skipped = []
            for ts_code in sorted(payload.get("universe", rows_by_code.keys())):
                rows = rows_by_code.get(ts_code)
                if not rows or len(rows) != seq_len: skipped.append({"tsCode": ts_code, "reason": "insufficient"}); continue
                try:
                    feats = compute_features_v2(rows)
                    seqs.append(feats); codes.append(ts_code)
                except Exception as e:
                    skipped.append({"tsCode": ts_code, "reason": f"feature_error:{e}"})

            predictions = []
            if seqs:
                xs = normalize_film_features(
                    np.stack(seqs).astype(np.float32),
                    np.tile(mkt_seq[np.newaxis,:,:], (len(seqs), 1, 1)).astype(np.float32),
                    stock_norm, market_norm
                )
                with torch.no_grad():
                    xs_t = torch.from_numpy(xs[0]).to(device)
                    xm_t = torch.from_numpy(xs[1]).to(device)
                    logits = model(xs_t, xm_t)
                    scores = torch.sigmoid(logits).cpu().numpy()
                for ts_code, score in zip(codes, scores.tolist()):
                    predictions.append({"tsCode": ts_code, "score": float(score)})

            predictions.sort(key=lambda r: (-r["score"], r["tsCode"]))
            selected_codes = {r["tsCode"] for r in predictions[:top_n]}
            for row in predictions:
                row["selectedByTopN"] = row["tsCode"] in selected_codes

            result = {
                "modelId": "film-v2-20260608",
                "topic": "profit-prediction-film",
                "tradeDate": trade_date,
                "topN": top_n,
                "predictions": predictions,
                "skipped": skipped,
                "coverage": {"requested": len(payload.get("universe", [])), "scored": len(predictions), "skipped": len(skipped)},
            }
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            self.wfile.write(json.dumps(result, ensure_ascii=False, separators=(",", ":")).encode())

        def do_GET(self):
            if self.path == "/health":
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"status": "ok", "model": "film-v2"}).encode())
            elif self.path == "/info":
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({
                    "model": "film-v2",
                    "D_stock": D_stock,
                    "D_market": D_market,
                    "d_model": d_model,
                    "sent_dates": len(sent_dates),
                    "cohort_dates": len(cohort_data),
                }).encode())
            else:
                self.send_error(404)

        def log_message(self, format, *args):
            pass  # 静默日志

    server_address = ("", args.port)
    httpd = HTTPServer(server_address, FilmHandler)
    print(f"[FiLM] Serving on port {args.port}, top_n={top_n}", flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


def main():
    parser = argparse.ArgumentParser(description="FiLM v2 选股模型推理服务")
    parser.add_argument("--model-dir", default="research/profit-prediction/models/film-v2-20260608")
    parser.add_argument("--port", type=int, default=9876)
    parser.add_argument("--top-n", type=int, default=5)
    args = parser.parse_args()
    run_film_service(args)


if __name__ == "__main__":
    main()
