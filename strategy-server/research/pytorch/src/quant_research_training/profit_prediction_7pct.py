from __future__ import annotations

import argparse
import csv
import heapq
import json
import math
import random
import time
from collections import defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any
from urllib.parse import urlencode
from urllib.request import urlopen
from http.server import HTTPServer, BaseHTTPRequestHandler

import numpy as np
import torch
from torch import nn
from torch.utils.data import DataLoader, TensorDataset


FEATURE_NAMES = [
    "E1_turnover_bias",
    "E2_amount_bias",
    "E3_overnight_gap",
    "E4_distance_momentum_purity",
    "R1_crowding_residual",
    "R2_return_entropy",
    "R3_upper_shadow_pressure",
    "R4_acceleration_decay",
    "P1_overnight_momentum",
    "P2_intraday_momentum",
    "P3_overnight_momentum_5d",
    "P4_intraday_momentum_5d",
    "V1_effort_result_divergence",
    "V2_effort_result_delta_5d",
    "V3_volume_range_break",
    "V4_close_quality_volume_stress",
]


@dataclass(frozen=True)
class ModelManifest:
    topic: str
    model_type: str
    format_version: int
    source: str
    run_id: str
    dataset_path: str
    feature_schema_path: str
    normalization_path: str
    weights_path: str
    thresholds_path: str
    metrics_path: str
    device: str
    seed: int
    start: str
    end: str
    seq_len: int
    feature_dim: int
    d_model: int
    layers: int
    label: str


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
        summary = torch.cat(
            [
                x.mean(dim=1),
                x[:, -1, :],
                x[:, -5:, :].mean(dim=1),
                x.std(dim=1),
                x[:, -1, :] - x[:, 0, :],
            ],
            dim=1,
        )
        return self.head(self.trunk(summary)).squeeze(-1)


def load_model_package(model_dir: Path, device_name: str) -> tuple[SummaryMLP, dict[str, Any], dict[str, Any], dict[str, Any], torch.device]:
    manifest = json.loads((model_dir / "manifest.json").read_text(encoding="utf-8"))
    normalization = json.loads((model_dir / "normalization.json").read_text(encoding="utf-8"))
    thresholds = json.loads((model_dir / "thresholds.json").read_text(encoding="utf-8"))
    device = choose_device(device_name)
    model = SummaryMLP(
        feature_dim=int(manifest["feature_dim"]),
        d_model=int(manifest["d_model"]),
        layers=int(manifest["layers"]),
        dropout=0.0,
    ).to(device)
    weights_path = Path(manifest["weights_path"])
    if not weights_path.is_absolute():
        weights_path = model_dir / weights_path.name
    state = torch.load(weights_path, map_location=device)
    model.load_state_dict(state["model_state_dict"] if isinstance(state, dict) and "model_state_dict" in state else state)
    model.eval()
    return model, manifest, normalization, thresholds, device


def normalize_inference_features(x: np.ndarray, normalization: dict[str, Any]) -> np.ndarray:
    mean = np.array(normalization["mean"], dtype=np.float32)
    std = np.array(normalization["std"], dtype=np.float32)
    clip = normalization.get("clip", [-8.0, 8.0])
    out = (x.astype(np.float32) - mean) / (std + 1e-6)
    return np.clip(out, float(clip[0]), float(clip[1])).astype(np.float32)


def run_infer_json(args: argparse.Namespace) -> None:
    payload = json.loads(Path(args.input_json).read_text(encoding="utf-8") if args.input_json else __import__("sys").stdin.read())
    model_dir = Path(args.model_dir)
    model, manifest, normalization, thresholds, device = load_model_package(model_dir, args.device)
    seq_len = int(manifest["seq_len"])
    threshold = float(thresholds.get(args.threshold_name, thresholds["recall_0_2"])["threshold"])

    rows_by_code: dict[str, list[dict[str, Any]]] = {}
    for item in payload.get("stocks", []):
        ts_code = item["tsCode"]
        rows = sorted(item.get("rows", []), key=lambda r: r["tradeDate"])
        if len(rows) < seq_len:
            continue
        rows_by_code[ts_code] = rows[-seq_len:]

    seqs: list[np.ndarray] = []
    codes: list[str] = []
    skipped: list[dict[str, str]] = []
    for ts_code in sorted(payload.get("universe", rows_by_code.keys())):
        rows = rows_by_code.get(ts_code)
        if not rows:
            skipped.append({"tsCode": ts_code, "reason": "missing_window"})
            continue
        if rows[-1]["tradeDate"] != payload.get("tradeDate"):
            skipped.append({"tsCode": ts_code, "reason": "latest_row_not_trade_date"})
            continue
        if any(min(float(r["openQfq"]), float(r["highQfq"]), float(r["lowQfq"]), float(r["closeQfq"])) <= 0.0 for r in rows):
            skipped.append({"tsCode": ts_code, "reason": "non_positive_price"})
            continue
        if len(rows) != seq_len:
            skipped.append({"tsCode": ts_code, "reason": "insufficient_window"})
            continue
        seqs.append(compute_features(rows))
        codes.append(ts_code)

    predictions: list[dict[str, Any]] = []
    if seqs:
        x = normalize_inference_features(np.stack(seqs), normalization)
        with torch.no_grad():
            logits = model(torch.tensor(x, dtype=torch.float32, device=device))
            scores = torch.sigmoid(logits).detach().cpu().numpy()
        for ts_code, score in zip(codes, scores.tolist()):
            predictions.append({
                "tsCode": ts_code,
                "score": float(score),
                "selectedByThreshold": bool(score >= threshold),
            })

    predictions.sort(key=lambda r: (-r["score"], r["tsCode"]))
    top_n = max(0, int(args.top_n))
    selected_codes = {r["tsCode"] for r in predictions[:top_n]}
    for row in predictions:
        row["selectedByTopN"] = row["tsCode"] in selected_codes

    result = {
        "modelId": manifest["run_id"],
        "topic": manifest["topic"],
        "tradeDate": payload.get("tradeDate"),
        "thresholdName": args.threshold_name,
        "threshold": threshold,
        "topN": top_n,
        "predictions": predictions,
        "skipped": skipped,
        "coverage": {
            "requested": len(payload.get("universe", [])),
            "scored": len(predictions),
            "skipped": len(skipped),
        },
    }
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))


def run_infer_service(args: argparse.Namespace) -> None:
    model_dir = Path(args.model_dir)
    model, manifest, normalization, thresholds, device = load_model_package(model_dir, args.device)
    seq_len = int(manifest["seq_len"])

    class InferenceHandler(BaseHTTPRequestHandler):
        def log_message(self, format, *args_log):
            pass

        def do_GET(self):
            if self.path == "/health":
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"status": "healthy"}).encode("utf-8"))
            else:
                self.send_response(404)
                self.end_headers()

        def do_POST(self):
            if self.path != "/predict":
                self.send_response(404)
                self.end_headers()
                return

            content_length = int(self.headers.get('Content-Length', 0))
            post_data = self.rfile.read(content_length)
            try:
                payload = json.loads(post_data.decode("utf-8"))
            except Exception as e:
                self.send_response(400)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"error": f"Invalid JSON: {str(e)}"}).encode("utf-8"))
                return

            req_threshold_name = payload.get("thresholdName", args.threshold_name)
            threshold = float(thresholds.get(req_threshold_name, thresholds.get(args.threshold_name, list(thresholds.values())[0]))["threshold"])

            rows_by_code: dict[str, list[dict[str, Any]]] = {}
            for item in payload.get("stocks", []):
                ts_code = item["tsCode"]
                rows = sorted(item.get("rows", []), key=lambda r: r["tradeDate"])
                if len(rows) < seq_len:
                    continue
                rows_by_code[ts_code] = rows[-seq_len:]

            seqs: list[np.ndarray] = []
            codes: list[str] = []
            skipped: list[dict[str, str]] = []
            for ts_code in sorted(payload.get("universe", rows_by_code.keys())):
                rows = rows_by_code.get(ts_code)
                if not rows:
                    skipped.append({"tsCode": ts_code, "reason": "missing_window"})
                    continue
                if rows[-1]["tradeDate"] != payload.get("tradeDate"):
                    skipped.append({"tsCode": ts_code, "reason": "latest_row_not_trade_date"})
                    continue
                if any(min(float(r["openQfq"]), float(r["highQfq"]), float(r["lowQfq"]), float(r["closeQfq"])) <= 0.0 for r in rows):
                    skipped.append({"tsCode": ts_code, "reason": "non_positive_price"})
                    continue
                if len(rows) != seq_len:
                    skipped.append({"tsCode": ts_code, "reason": "insufficient_window"})
                    continue
                seqs.append(compute_features(rows))
                codes.append(ts_code)

            predictions: list[dict[str, Any]] = []
            if seqs:
                x = normalize_inference_features(np.stack(seqs), normalization)
                with torch.no_grad():
                    logits = model(torch.tensor(x, dtype=torch.float32, device=device))
                    scores = torch.sigmoid(logits).detach().cpu().numpy()
                for ts_code, score in zip(codes, scores.tolist()):
                    predictions.append({
                        "tsCode": ts_code,
                        "score": float(score),
                        "selectedByThreshold": bool(score >= threshold),
                    })

            predictions.sort(key=lambda r: (-r["score"], r["tsCode"]))
            top_n = max(0, int(payload.get("topN", args.top_n)))
            selected_codes = {r["tsCode"] for r in predictions[:top_n]}
            for row in predictions:
                row["selectedByTopN"] = row["tsCode"] in selected_codes

            result = {
                "modelId": manifest["run_id"],
                "topic": manifest["topic"],
                "tradeDate": payload.get("tradeDate"),
                "thresholdName": req_threshold_name,
                "threshold": threshold,
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
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(result, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))

    server_address = ("", args.port)
    httpd = HTTPServer(server_address, InferenceHandler)
    print(f"[infer-service] model package loaded, starting service on port {args.port}...", flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()
        print("[infer-service] service stopped.", flush=True)


def set_seed(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)


def api_get(api_base: str, path: str, params: dict[str, Any], timeout: int = 120) -> Any:
    url = f"{api_base.rstrip('/')}{path}?{urlencode({k: v for k, v in params.items() if v is not None})}"
    with urlopen(url, timeout=timeout) as response:
        payload = json.loads(response.read().decode("utf-8"))
    if not payload.get("success"):
        raise RuntimeError(f"API failed: {payload.get('code')} {payload.get('message')} url={url}")
    return payload["data"]


def fetch_top_list(api_base: str, start: str, end: str) -> set[tuple[str, str]]:
    rows = api_get(
        api_base,
        "/api/internal/research/pivot-crash-stock/top-list",
        {"start": start, "end": end},
        timeout=180,
    )
    return {(r["tsCode"], r["tradeDate"]) for r in rows}


def fetch_daily_pages(api_base: str, start: str, end: str, limit: int):
    cursor_code: str | None = None
    cursor_date: str | None = None
    total = 0
    while True:
        data = api_get(
            api_base,
            "/api/internal/research/profit-prediction/daily-ohlcv",
            {
                "start": start,
                "end": end,
                "limit": limit,
                "afterTsCode": cursor_code,
                "afterTradeDate": cursor_date,
            },
            timeout=240,
        )
        rows = data["rows"]
        if not rows:
            break
        total += len(rows)
        yield rows
        next_cursor = data.get("next")
        if not next_cursor:
            break
        cursor_code = next_cursor["tsCode"]
        cursor_date = next_cursor["tradeDate"]
        print(f"[fetch] daily rows={total:,}, next=({cursor_code},{cursor_date})", flush=True)


def rolling_mean(x: np.ndarray, window: int) -> np.ndarray:
    out = np.zeros_like(x, dtype=np.float64)
    cs = np.cumsum(np.insert(x.astype(np.float64), 0, 0.0))
    for i in range(len(x)):
        lo = max(0, i - window + 1)
        out[i] = (cs[i + 1] - cs[lo]) / (i - lo + 1)
    return out


def rolling_std(x: np.ndarray, window: int) -> np.ndarray:
    out = np.zeros_like(x, dtype=np.float64)
    for i in range(len(x)):
        lo = max(0, i - window + 1)
        out[i] = np.nanstd(x[lo : i + 1])
    return out


def rolling_entropy(x: np.ndarray, window: int, bins: int = 8) -> np.ndarray:
    out = np.zeros(len(x), dtype=np.float64)
    for i in range(len(x)):
        lo = max(0, i - window + 1)
        seg = x[lo : i + 1]
        if len(seg) < 4:
            continue
        mn = float(np.nanmin(seg))
        mx = float(np.nanmax(seg))
        if mx - mn <= 1e-9:
            continue
        hist, _ = np.histogram(seg[np.isfinite(seg)], bins=bins, range=(mn, mx))
        total = hist.sum()
        if total <= 0:
            continue
        p = hist[hist > 0] / total
        out[i] = -np.sum(p * np.log(p)) / math.log(bins)
    return out


def rolling_residual(open_: np.ndarray, amount: np.ndarray, reg_window: int = 45, z_window: int = 60) -> np.ndarray:
    residual = np.full(len(open_), np.nan, dtype=np.float64)
    for i in range(reg_window - 1, len(open_)):
        xs = open_[i - reg_window + 1 : i + 1].astype(np.float64)
        ys = amount[i - reg_window + 1 : i + 1].astype(np.float64)
        if not np.all(np.isfinite(xs)) or not np.all(np.isfinite(ys)):
            continue
        sx = xs - xs.mean()
        denom = float((sx * sx).sum())
        if denom <= 1e-9:
            continue
        beta = float((sx * (ys - ys.mean())).sum() / denom)
        alpha = ys.mean() - beta * xs.mean()
        residual[i] = amount[i] - (alpha + beta * open_[i])
    z = np.zeros(len(open_), dtype=np.float64)
    for i in range(len(open_)):
        lo = max(0, i - z_window + 1)
        seg = residual[lo : i + 1]
        seg = seg[np.isfinite(seg)]
        if len(seg) > 5 and np.isfinite(residual[i]):
            raw = residual[i] / (seg.std() + 1e-9)
            z[i] = max(0.0, min(1.0, 0.2 * raw + 0.5))
    return z


def safe_div(a: np.ndarray, b: np.ndarray, default: float = 0.0) -> np.ndarray:
    out = np.full_like(a, default, dtype=np.float64)
    ok = np.isfinite(a) & np.isfinite(b) & (np.abs(b) > 1e-12)
    out[ok] = a[ok] / b[ok]
    return out


def compute_features(rows: list[dict[str, Any]]) -> np.ndarray:
    open_ = np.array([r["openQfq"] for r in rows], dtype=np.float64)
    high = np.array([r["highQfq"] for r in rows], dtype=np.float64)
    low = np.array([r["lowQfq"] for r in rows], dtype=np.float64)
    close = np.array([r["closeQfq"] for r in rows], dtype=np.float64)
    volume = np.array([r["volumeQfq"] for r in rows], dtype=np.float64)
    turnover = np.array([r["turnoverReal"] for r in rows], dtype=np.float64)
    amount = volume * close

    prev_close = np.roll(close, 1)
    prev_close[0] = np.nan
    logret = np.log(safe_div(close, prev_close, default=np.nan))
    logret[~np.isfinite(logret)] = 0.0

    ma20_turnover = rolling_mean(np.clip(turnover, 0, None), 20)
    ma20_amount = rolling_mean(np.clip(amount, 0, None), 20)
    ma20_volume = rolling_mean(np.clip(volume, 0, None), 20)
    day_range = high - low

    e1 = np.where((turnover > 0) & (ma20_turnover > 0), np.log(np.clip(turnover / ma20_turnover, 1e-6, None)), 0.0)
    e2 = np.where((amount > 0) & (ma20_amount > 0), np.log(np.clip(amount / ma20_amount, 1e-6, None)), 0.0)
    e3 = safe_div(open_ - prev_close, prev_close)
    e4 = np.zeros(len(rows), dtype=np.float64)
    for i in range(len(rows)):
        seg = logret[max(0, i - 4) : i + 1]
        e4[i] = seg.sum() / (np.abs(seg).sum() + 1e-12)
    r1 = rolling_residual(open_, amount)
    r2 = rolling_entropy(logret, 20)
    r3 = np.where(day_range > 1e-9, (high - close) / day_range, 0.0)
    r4 = -(logret - 2.0 * np.roll(logret, 1) + np.roll(logret, 2))
    r4[:2] = 0.0

    p1 = e3.copy()
    p2 = safe_div(close - open_, open_)
    p3 = np.zeros(len(rows), dtype=np.float64)
    p4 = np.zeros(len(rows), dtype=np.float64)
    for i in range(len(rows)):
        lo = max(0, i - 4)
        p3[i] = p1[lo : i + 1].sum()
        p4[i] = p2[lo : i + 1].sum()
    vr = np.where(ma20_volume > 0, volume / ma20_volume, 1.0)
    erd = np.where(vr > 1e-9, np.log(np.clip(high / np.clip(low, 1e-9, None), 1.0, None)) / vr, 0.0)
    v2 = erd - np.roll(erd, 5)
    v2[:5] = 0.0
    vr_mean = rolling_mean(vr, 60)
    vr_std = rolling_std(vr, 60)
    zvr = np.divide(vr - vr_mean, vr_std, out=np.zeros_like(vr), where=vr_std > 1e-9)
    amp = safe_div(high - low, open_)
    amp_base = rolling_mean(amp, 20)
    amp_rel = np.divide(amp, amp_base, out=np.ones_like(amp), where=amp_base > 1e-9)
    amp_shrink = 1.0 - amp_rel
    v3 = zvr * np.clip(amp_shrink, -2, 2)
    close_quality = np.where(day_range > 1e-9, (close - low) / day_range, 0.5)
    v4 = (1.0 - close_quality) * zvr

    features = np.stack([e1, e2, e3, e4, r1, r2, r3, r4, p1, p2, p3, p4, erd, v2, v3, v4], axis=1)
    features[~np.isfinite(features)] = 0.0
    return np.clip(features, -10.0, 10.0).astype(np.float32)


def stock_samples(rows: list[dict[str, Any]], candidate_dates: set[str], seq_len: int) -> tuple[list[np.ndarray], list[float], list[float], list[float], list[str], list[str]]:
    if len(rows) < seq_len + 3:
        return [], [], [], [], [], []
    dates = [r["tradeDate"] for r in rows]
    open_ = np.array([r["openQfq"] for r in rows], dtype=np.float64)
    high = np.array([r["highQfq"] for r in rows], dtype=np.float64)
    low = np.array([r["lowQfq"] for r in rows], dtype=np.float64)
    close = np.array([r["closeQfq"] for r in rows], dtype=np.float64)
    features = compute_features(rows)

    seqs: list[np.ndarray] = []
    labels: list[float] = []
    gains: list[float] = []
    exits: list[float] = []
    pred_dates: list[str] = []
    ts_codes: list[str] = []
    ts_code = rows[0]["tsCode"]
    for i in range(seq_len, len(rows) - 3):
        if dates[i] not in candidate_dates:
            continue
        if min(open_[i], high[i], low[i], close[i]) <= 0:
            continue
        max_gain = float(np.nanmax(high[i + 1 : i + 4]) / close[i] - 1.0)
        exit_ret = 0.07 if max_gain >= 0.07 else float(close[i + 3] / close[i] - 1.0)
        seqs.append(features[i - seq_len + 1 : i + 1])
        labels.append(1.0 if max_gain >= 0.07 else 0.0)
        gains.append(max_gain)
        exits.append(exit_ret)
        pred_dates.append(dates[i])
        ts_codes.append(ts_code)
    return seqs, labels, gains, exits, pred_dates, ts_codes


def build_dataset(api_base: str, start: str, end: str, limit: int, seq_len: int, out_path: Path) -> Path:
    print("[dataset] fetching top_list candidate pool", flush=True)
    candidates = fetch_top_list(api_base, start, end)
    by_code: dict[str, set[str]] = defaultdict(set)
    for ts_code, trade_date in candidates:
        by_code[ts_code].add(trade_date)
    print(f"[dataset] top_list candidates={len(candidates):,}, stocks={len(by_code):,}", flush=True)

    seq_chunks: list[np.ndarray] = []
    labels: list[float] = []
    gains: list[float] = []
    exits: list[float] = []
    dates: list[str] = []
    codes: list[str] = []
    current_code: str | None = None
    current_rows: list[dict[str, Any]] = []

    def flush_stock() -> None:
        nonlocal current_rows, current_code
        if not current_rows or current_code not in by_code:
            current_rows = []
            return
        seqs, ys, gs, rs, ds, cs = stock_samples(current_rows, by_code[current_code], seq_len)
        if seqs:
            seq_chunks.append(np.stack(seqs).astype(np.float32))
            labels.extend(ys)
            gains.extend(gs)
            exits.extend(rs)
            dates.extend(ds)
            codes.extend(cs)
            if len(labels) % 50_000 < len(ys):
                print(f"[dataset] assembled samples={len(labels):,}", flush=True)
        current_rows = []

    for page in fetch_daily_pages(api_base, start, end, limit):
        for row in page:
            ts_code = row["tsCode"]
            if current_code is None:
                current_code = ts_code
            if ts_code != current_code:
                flush_stock()
                current_code = ts_code
            current_rows.append(row)
    flush_stock()

    if not seq_chunks:
        raise RuntimeError("No training samples assembled. Check top_list coverage and daily OHLCV range.")
    x = np.concatenate(seq_chunks, axis=0)
    y = np.array(labels, dtype=np.float32)
    max_gain = np.array(gains, dtype=np.float32)
    exit_ret = np.array(exits, dtype=np.float32)
    pred_date = np.array([int(d.replace("-", "")) for d in dates], dtype=np.int32)
    ts_code = np.array(codes)
    pred_date_iso = np.array(dates)

    order = np.lexsort((ts_code, pred_date))
    x = x[order]
    y = y[order]
    max_gain = max_gain[order]
    exit_ret = exit_ret[order]
    pred_date = pred_date[order]
    ts_code = ts_code[order]
    pred_date_iso = pred_date_iso[order]

    out_path.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(
        out_path,
        x=x,
        y=y,
        max_gain=max_gain,
        exit_ret=exit_ret,
        pred_date=pred_date,
        pred_date_iso=pred_date_iso,
        ts_code=ts_code,
        feature_names=np.array(FEATURE_NAMES),
    )
    print(f"[dataset] saved {out_path} samples={len(y):,} base_rate={y.mean():.4f}", flush=True)
    return out_path


def load_top_list_candidates(top_list_csv: Path) -> dict[str, set[str]]:
    by_code: dict[str, set[str]] = defaultdict(set)
    with top_list_csv.open("r", encoding="utf-8", newline="") as fp:
        for row in csv.DictReader(fp):
            by_code[row["ts_code"]].add(row["trade_date"])
    return by_code


def compact_date(iso_date: str) -> int:
    return int(iso_date.replace("-", ""))


def collect_market_gated_candidates(
    daily_csv: Path,
    listed_by_code: dict[str, set[str]],
    seq_len: int,
    sample_start: str,
    sample_end: str,
    per_day_unlisted: int,
    seed: int,
) -> dict[str, set[str]]:
    """Historical sample pool: keep all LHB-listed events, sample unlisted names by trading day."""
    start_i = compact_date(sample_start)
    end_i = compact_date(sample_end)
    by_day_unlisted: dict[str, list[str]] = defaultdict(list)
    listed_total = 0
    current_code: str | None = None
    current_dates: list[str] = []
    scanned = 0

    def flush_stock_dates() -> None:
        nonlocal current_dates, current_code, listed_total
        if current_code is None or len(current_dates) < seq_len + 3:
            current_dates = []
            return
        listed_dates = listed_by_code.get(current_code, set())
        for i in range(seq_len, len(current_dates) - 3):
            trade_date = current_dates[i]
            d_i = compact_date(trade_date)
            if d_i < start_i or d_i > end_i:
                continue
            if trade_date in listed_dates:
                listed_total += 1
            else:
                by_day_unlisted[trade_date].append(current_code)
        current_dates = []

    print(
        f"[dataset] collecting market-gated pool sample_start={sample_start} sample_end={sample_end} "
        f"per_day_unlisted={per_day_unlisted}",
        flush=True,
    )
    with daily_csv.open("r", encoding="utf-8", newline="") as fp:
        for row in csv.DictReader(fp):
            scanned += 1
            ts_code = row["ts_code"]
            if current_code is None:
                current_code = ts_code
            if ts_code != current_code:
                flush_stock_dates()
                current_code = ts_code
            current_dates.append(row["trade_date"])
            if scanned % 2_000_000 == 0:
                print(f"[dataset] candidate scan daily rows={scanned:,}", flush=True)
    flush_stock_dates()

    rng = np.random.default_rng(seed)
    selected: dict[str, set[str]] = defaultdict(set)
    for code, dates in listed_by_code.items():
        for trade_date in dates:
            d_i = compact_date(trade_date)
            if start_i <= d_i <= end_i:
                selected[code].add(trade_date)
    unlisted_total = 0
    for trade_date in sorted(by_day_unlisted):
        codes = by_day_unlisted[trade_date]
        if len(codes) > per_day_unlisted:
            pick = rng.choice(len(codes), per_day_unlisted, replace=False)
            codes = [codes[int(i)] for i in pick]
        for code in codes:
            selected[code].add(trade_date)
        unlisted_total += len(codes)
    print(
        f"[dataset] market-gated listed_candidates={listed_total:,} "
        f"sampled_unlisted={unlisted_total:,} dates={len(by_day_unlisted):,} stocks={len(selected):,}",
        flush=True,
    )
    return selected


def load_daily_top_candidates(daily_csv: Path, top_n: int, score_mode: str) -> dict[str, set[str]]:
    print(f"[dataset] deriving daily {score_mode} candidate pool top_n={top_n}", flush=True)
    heaps: dict[str, list[tuple[float, str]]] = defaultdict(list)
    scanned = 0
    with daily_csv.open("r", encoding="utf-8", newline="") as fp:
        for row in csv.DictReader(fp):
            scanned += 1
            if score_mode == "top-turnover":
                score = float(row["turnover_real"])
            else:
                score = float(row["volume_qfq"]) * float(row["close_qfq"])
            if not math.isfinite(score) or score <= 0.0:
                continue
            heap = heaps[row["trade_date"]]
            item = (score, row["ts_code"])
            if len(heap) < top_n:
                heapq.heappush(heap, item)
            elif item > heap[0]:
                heapq.heapreplace(heap, item)
            if scanned % 2_000_000 == 0:
                print(f"[dataset] candidate scan daily rows={scanned:,}", flush=True)
    by_code: dict[str, set[str]] = defaultdict(set)
    total = 0
    for trade_date, heap in heaps.items():
        for _, ts_code in heap:
            by_code[ts_code].add(trade_date)
            total += 1
    print(f"[dataset] {score_mode} candidates={total:,}, dates={len(heaps):,}, stocks={len(by_code):,}", flush=True)
    return by_code


def build_dataset_from_csv(
    daily_csv: Path,
    top_list_csv: Path | None,
    seq_len: int,
    out_path: Path,
    candidate_mode: str,
    top_n: int,
    sample_start: str,
    sample_end: str,
    per_day_unlisted: int,
    seed: int,
) -> Path:
    if candidate_mode == "top-list":
        if top_list_csv is None:
            raise ValueError("--top-list-csv is required when --candidate-mode=top-list")
        print("[dataset] loading exported top_list candidate pool", flush=True)
        by_code = load_top_list_candidates(top_list_csv)
        print(f"[dataset] top_list stocks={len(by_code):,}", flush=True)
    elif candidate_mode == "market-gated":
        if top_list_csv is None:
            raise ValueError("--top-list-csv is required when --candidate-mode=market-gated")
        print("[dataset] loading exported top_list gate pool", flush=True)
        listed_by_code = load_top_list_candidates(top_list_csv)
        print(f"[dataset] top_list events={sum(len(v) for v in listed_by_code.values()):,}, stocks={len(listed_by_code):,}", flush=True)
        by_code = collect_market_gated_candidates(
            daily_csv=daily_csv,
            listed_by_code=listed_by_code,
            seq_len=seq_len,
            sample_start=sample_start,
            sample_end=sample_end,
            per_day_unlisted=per_day_unlisted,
            seed=seed,
        )
    elif candidate_mode in ("top-turnover", "top-amount"):
        by_code = load_daily_top_candidates(daily_csv, top_n, candidate_mode)
    else:
        raise ValueError(f"Unsupported candidate_mode={candidate_mode}")

    seq_chunks: list[np.ndarray] = []
    labels: list[float] = []
    gains: list[float] = []
    exits: list[float] = []
    dates: list[str] = []
    codes: list[str] = []
    current_code: str | None = None
    current_rows: list[dict[str, Any]] = []
    daily_rows = 0

    def flush_stock() -> None:
        nonlocal current_rows, current_code
        if not current_rows or current_code not in by_code:
            current_rows = []
            return
        seqs, ys, gs, rs, ds, cs = stock_samples(current_rows, by_code[current_code], seq_len)
        if seqs:
            seq_chunks.append(np.stack(seqs).astype(np.float32))
            labels.extend(ys)
            gains.extend(gs)
            exits.extend(rs)
            dates.extend(ds)
            codes.extend(cs)
            if len(labels) % 50_000 < len(ys):
                print(f"[dataset] assembled samples={len(labels):,}", flush=True)
        current_rows = []

    with daily_csv.open("r", encoding="utf-8", newline="") as fp:
        for row in csv.DictReader(fp):
            daily_rows += 1
            ts_code = row["ts_code"]
            if current_code is None:
                current_code = ts_code
            if ts_code != current_code:
                flush_stock()
                current_code = ts_code
            current_rows.append(
                {
                    "tsCode": ts_code,
                    "tradeDate": row["trade_date"],
                    "openQfq": float(row["open_qfq"]),
                    "highQfq": float(row["high_qfq"]),
                    "lowQfq": float(row["low_qfq"]),
                    "closeQfq": float(row["close_qfq"]),
                    "volumeQfq": float(row["volume_qfq"]),
                    "turnoverReal": float(row["turnover_real"]),
                }
            )
            if daily_rows % 1_000_000 == 0:
                print(f"[dataset] scanned daily rows={daily_rows:,}", flush=True)
    flush_stock()

    if not seq_chunks:
        raise RuntimeError("No training samples assembled from CSV. Check top_list and daily OHLCV date ranges.")
    x = np.concatenate(seq_chunks, axis=0)
    y = np.array(labels, dtype=np.float32)
    max_gain = np.array(gains, dtype=np.float32)
    exit_ret = np.array(exits, dtype=np.float32)
    pred_date = np.array([int(d.replace("-", "")) for d in dates], dtype=np.int32)
    ts_code = np.array(codes)
    pred_date_iso = np.array(dates)

    order = np.lexsort((ts_code, pred_date))
    out_path.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(
        out_path,
        x=x[order],
        y=y[order],
        max_gain=max_gain[order],
        exit_ret=exit_ret[order],
        pred_date=pred_date[order],
        pred_date_iso=pred_date_iso[order],
        ts_code=ts_code[order],
        feature_names=np.array(FEATURE_NAMES),
    )
    print(f"[dataset] saved {out_path} samples={len(y):,} base_rate={y.mean():.4f}", flush=True)
    return out_path


def rank_auc(y_true: np.ndarray, score: np.ndarray) -> float:
    y = y_true.astype(bool)
    n_pos = int(y.sum())
    n_neg = int((~y).sum())
    if n_pos == 0 or n_neg == 0:
        return float("nan")
    order = np.argsort(score, kind="mergesort")
    ranks = np.empty_like(order, dtype=np.float64)
    ranks[order] = np.arange(1, len(score) + 1)
    return float((ranks[y].sum() - n_pos * (n_pos + 1) / 2.0) / (n_pos * n_neg))


def precision_at_recall(y_true: np.ndarray, score: np.ndarray, recall: float) -> tuple[float, float, int]:
    order = np.argsort(-score, kind="mergesort")
    y = y_true[order]
    target = max(1, int(math.ceil(y_true.sum() * recall)))
    tp = np.cumsum(y)
    idx = int(np.searchsorted(tp, target, side="left"))
    idx = min(idx, len(y) - 1)
    selected = idx + 1
    return float(tp[idx] / selected), float(score[order[idx]]), selected


def topk_backtest(pred_date: np.ndarray, ts_code: np.ndarray, score: np.ndarray, exit_ret: np.ndarray, k: int) -> dict[str, Any]:
    rows = sorted(zip(pred_date.tolist(), ts_code.tolist(), score.tolist(), exit_ret.tolist()), key=lambda r: (r[0], -r[2], r[1]))
    by_date: dict[int, list[tuple[str, float, float]]] = defaultdict(list)
    for d, c, s, r in rows:
        by_date[d].append((c, s, r))
    sorted_dates = sorted(by_date)
    date_to_index = {d: i for i, d in enumerate(sorted_dates)}
    next_free = 0
    returns: list[float] = []
    individual: list[float] = []
    for d in sorted_dates:
        di = date_to_index[d]
        if di < next_free:
            continue
        picks = by_date[d][:k]
        if len(picks) < k:
            continue
        rs = [p[2] for p in picks]
        individual.extend(rs)
        returns.append(float(np.mean(rs)))
        next_free = di + 3
    if not returns:
        return {"topk": k, "rounds": 0}
    arr = np.array(returns, dtype=np.float64)
    ind = np.array(individual, dtype=np.float64)
    return {
        "topk": k,
        "rounds": int(len(arr)),
        "individual_trades": int(len(ind)),
        "win_rate": float((arr > 0).mean()),
        "mean_return": float(arr.mean()),
        "median_return": float(np.median(arr)),
        "min_return": float(arr.min()),
        "individual_below_minus_20pct": int((ind < -0.20).sum()),
    }


def normalize_dataset(dataset: dict[str, np.ndarray], train_mask: np.ndarray) -> tuple[np.ndarray, dict[str, Any]]:
    x = dataset["x"].astype(np.float32)
    flat = x[train_mask].reshape(-1, x.shape[-1])
    mean = flat.mean(axis=0)
    std = flat.std(axis=0)
    std = np.where(std < 1e-6, 1.0, std)
    norm = np.clip((x - mean) / std, -8.0, 8.0).astype(np.float32)
    return norm, {"feature_names": FEATURE_NAMES, "mean": mean.tolist(), "std": std.tolist(), "clip": [-8.0, 8.0]}


def choose_device(requested: str) -> torch.device:
    if requested != "auto":
        return torch.device(requested)
    if torch.backends.mps.is_available():
        return torch.device("mps")
    if torch.cuda.is_available():
        return torch.device("cuda")
    return torch.device("cpu")


def train_model(args: argparse.Namespace, dataset_path: Path, run_dir: Path) -> dict[str, Any]:
    set_seed(args.seed)
    data = dict(np.load(dataset_path, allow_pickle=True))
    pred_date = data["pred_date"]
    cut = int(np.quantile(pred_date, 0.6))
    train_mask = pred_date < cut
    val_mask = pred_date >= cut
    x_norm, normalization = normalize_dataset(data, train_mask)

    x_train = torch.from_numpy(x_norm[train_mask])
    y_train = torch.from_numpy(data["y"][train_mask].astype(np.float32))
    x_val = torch.from_numpy(x_norm[val_mask])
    y_val_np = data["y"][val_mask].astype(np.float32)
    y_val = torch.from_numpy(y_val_np)

    device = choose_device(args.device)
    model = SummaryMLP(feature_dim=x_norm.shape[-1], d_model=args.d_model, layers=args.layers, dropout=args.dropout).to(device)
    pos_rate = float(y_train.mean().item())
    pos_weight = (1.0 - pos_rate) / max(pos_rate, 1e-6)
    criterion = nn.BCEWithLogitsLoss(pos_weight=torch.tensor(pos_weight, dtype=torch.float32, device=device))
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=args.weight_decay)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, args.epochs)
    loader = DataLoader(TensorDataset(x_train, y_train), batch_size=args.batch_size, shuffle=True, drop_last=False)

    best_auc = -1.0
    best_epoch = -1
    best_state: dict[str, torch.Tensor] | None = None
    history: list[dict[str, float]] = []
    start_time = time.time()
    for epoch in range(1, args.epochs + 1):
        model.train()
        losses: list[float] = []
        for xb, yb in loader:
            xb = xb.to(device)
            yb = yb.to(device)
            optimizer.zero_grad(set_to_none=True)
            loss = criterion(model(xb), yb)
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            losses.append(float(loss.detach().cpu()))
        scheduler.step()
        model.eval()
        scores: list[np.ndarray] = []
        with torch.no_grad():
            for i in range(0, len(x_val), args.batch_size * 4):
                logits = model(x_val[i : i + args.batch_size * 4].to(device))
                scores.append(torch.sigmoid(logits).detach().cpu().numpy())
        val_score = np.concatenate(scores)
        auc = rank_auc(y_val_np, val_score)
        p80, threshold80, selected80 = precision_at_recall(y_val_np, val_score, 0.8)
        history.append({"epoch": epoch, "loss": float(np.mean(losses)), "val_auc": auc, "p_at_recall_0_8": p80})
        print(f"[train] epoch={epoch:02d} loss={np.mean(losses):.5f} val_auc={auc:.4f} p@r0.8={p80:.4f}", flush=True)
        if auc > best_auc:
            best_auc = auc
            best_epoch = epoch
            best_state = {k: v.detach().cpu() for k, v in model.state_dict().items()}
        if epoch - best_epoch >= args.patience:
            print(f"[train] early stop epoch={epoch:02d} best_epoch={best_epoch:02d}", flush=True)
            break

    if best_state is None:
        raise RuntimeError("Training did not produce a model state.")
    model.load_state_dict(best_state)
    model.eval()
    scores = []
    with torch.no_grad():
        for i in range(0, len(x_val), args.batch_size * 4):
            logits = model(x_val[i : i + args.batch_size * 4].to(device))
            scores.append(torch.sigmoid(logits).detach().cpu().numpy())
    val_score = np.concatenate(scores)
    val_auc = rank_auc(y_val_np, val_score)
    p20, threshold20, selected20 = precision_at_recall(y_val_np, val_score, 0.2)
    p80, threshold80, selected80 = precision_at_recall(y_val_np, val_score, 0.8)
    bt = [
        topk_backtest(data["pred_date"][val_mask], data["ts_code"][val_mask], val_score, data["exit_ret"][val_mask], k)
        for k in (1, 5, 10)
    ]

    weights_path = run_dir / "profit_prediction_7pct_summary_mlp.pt"
    normalization_path = run_dir / "normalization.json"
    feature_schema_path = run_dir / "feature_schema.json"
    thresholds_path = run_dir / "thresholds.json"
    metrics_path = run_dir / "metrics.json"
    manifest_path = run_dir / "manifest.json"
    run_dir.mkdir(parents=True, exist_ok=True)
    torch.save({"model_state_dict": best_state, "args": vars(args)}, weights_path)
    normalization_path.write_text(json.dumps(normalization, ensure_ascii=False, indent=2), encoding="utf-8")
    feature_schema_path.write_text(
        json.dumps(
            {
                "seq_len": int(x_norm.shape[1]),
                "feature_dim": int(x_norm.shape[2]),
                "feature_names": FEATURE_NAMES,
                "summary": ["mean20", "last", "mean5", "std20", "last_minus_first"],
                "label": "max(high[t+1:t+3]) / close[t] - 1 >= 0.07",
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    thresholds_path.write_text(
        json.dumps(
            {
                "recall_0_2": {"threshold": threshold20, "precision": p20, "selected": selected20},
                "recall_0_8": {"threshold": threshold80, "precision": p80, "selected": selected80},
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    metrics = {
        "samples": int(len(data["y"])),
        "base_rate": float(data["y"].mean()),
        "cut_pred_date": cut,
        "train_samples": int(train_mask.sum()),
        "val_samples": int(val_mask.sum()),
        "val_start": str(data["pred_date_iso"][val_mask][0]),
        "val_end": str(data["pred_date_iso"][val_mask][-1]),
        "val_base_rate": float(y_val.mean().item()),
        "val_auc": val_auc,
        "history": history,
        "backtest": bt,
        "elapsed_seconds": time.time() - start_time,
    }
    metrics_path.write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    manifest = ModelManifest(
        topic="profit-prediction-7pct",
        model_type="SummaryMLP",
        format_version=1,
        source=(
            "Exported research cache from Ktor DB boundary; market-gated sample pool "
            "keeps all top-list events and samples unlisted stocks per trading day."
        ),
        run_id=args.run_id,
        dataset_path=str(dataset_path),
        feature_schema_path=str(feature_schema_path),
        normalization_path=str(normalization_path),
        weights_path=str(weights_path),
        thresholds_path=str(thresholds_path),
        metrics_path=str(metrics_path),
        device=str(device),
        seed=args.seed,
        start=args.start,
        end=args.end,
        seq_len=int(x_norm.shape[1]),
        feature_dim=int(x_norm.shape[2]),
        d_model=args.d_model,
        layers=args.layers,
        label="future 3 trading days high max gain >= 7%",
    )
    manifest_path.write_text(json.dumps(asdict(manifest), ensure_ascii=False, indent=2), encoding="utf-8")
    np.savez_compressed(run_dir / "validation_predictions.npz", score=val_score, y=y_val_np, pred_date=data["pred_date"][val_mask], ts_code=data["ts_code"][val_mask])
    print(f"[train] saved run artifacts to {run_dir}", flush=True)
    return metrics


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Reproduce the 7% profit-prediction stock selection model.")
    parser.add_argument("--api-base", default="http://127.0.0.1:9871")
    parser.add_argument("--start", default="2009-01-01")
    parser.add_argument("--end", default="2024-05-31")
    parser.add_argument("--workspace", default="research/profit-prediction")
    parser.add_argument("--run-id", default=time.strftime("reproduce-%Y%m%d-%H%M%S"))
    parser.add_argument("--mode", choices=["all", "dataset", "train", "infer-json", "infer-service"], default="all")
    parser.add_argument("--daily-limit", type=int, default=100_000)
    parser.add_argument("--seq-len", type=int, default=20)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--device", default="auto")
    parser.add_argument("--d-model", type=int, default=192)
    parser.add_argument("--layers", type=int, default=3)
    parser.add_argument("--dropout", type=float, default=0.3)
    parser.add_argument("--epochs", type=int, default=40)
    parser.add_argument("--batch-size", type=int, default=2048)
    parser.add_argument("--patience", type=int, default=8)
    parser.add_argument("--lr", type=float, default=3e-4)
    parser.add_argument("--weight-decay", type=float, default=3e-4)
    parser.add_argument("--dataset-path", default=None)
    parser.add_argument("--daily-csv", default=None)
    parser.add_argument("--top-list-csv", default=None)
    parser.add_argument("--candidate-mode", choices=["market-gated", "top-list", "top-turnover", "top-amount"], default="market-gated")
    parser.add_argument("--top-n", type=int, default=230)
    parser.add_argument("--sample-start", default="2010-01-01")
    parser.add_argument("--sample-end", default="2024-06-01")
    parser.add_argument("--per-day-unlisted", type=int, default=220)
    parser.add_argument("--model-dir", default="research/profit-prediction/models/market-gated-bce-20260606")
    parser.add_argument("--input-json", default=None)
    parser.add_argument("--threshold-name", default="recall_0_2")
    parser.add_argument("--port", type=int, default=9875)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.mode == "infer-json":
        run_infer_json(args)
        return
    if args.mode == "infer-service":
        run_infer_service(args)
        return
    workspace = Path(args.workspace)
    run_dir = workspace / "models" / args.run_id
    dataset_path = Path(args.dataset_path) if args.dataset_path else workspace / "training" / "profit_prediction_7pct_dataset.npz"
    if args.mode in ("all", "dataset"):
        if args.daily_csv:
            build_dataset_from_csv(
                daily_csv=Path(args.daily_csv),
                top_list_csv=Path(args.top_list_csv) if args.top_list_csv else None,
                seq_len=args.seq_len,
                out_path=dataset_path,
                candidate_mode=args.candidate_mode,
                top_n=args.top_n,
                sample_start=args.sample_start,
                sample_end=args.sample_end,
                per_day_unlisted=args.per_day_unlisted,
                seed=args.seed,
            )
        else:
            build_dataset(args.api_base, args.start, args.end, args.daily_limit, args.seq_len, dataset_path)
    if args.mode in ("all", "train"):
        metrics = train_model(args, dataset_path, run_dir)
        print(json.dumps({"val_auc": metrics["val_auc"], "backtest": metrics["backtest"]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
