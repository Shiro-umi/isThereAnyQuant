/*
 * 分享页 K 线渲染脚本。
 *
 * 工作流程：
 *   1. 扫描页面所有 <figure.qk-chart>
 *   2. 按 data-share-token + data-block-key 调匿名 K 线接口
 *   3. 用 Canvas 画出蜡烛 + MA20 主图 + 成交量副图
 *
 * 设计原则：
 *   - 零额外依赖，纯原生 Canvas
 *   - 静态展示，不做缩放 / 十字线（分享场景定位是「看报告」）
 *   - 颜色完全使用 share.css 暴露的 CSS 变量，跟随主题
 */

const CHART_COLORS = readChartColors();

function readChartColors() {
    const styles = getComputedStyle(document.documentElement);
    return {
        bullish: styles.getPropertyValue("--color-bullish").trim() || "#E57373",
        bearish: styles.getPropertyValue("--color-bearish").trim() || "#81C784",
        grid: styles.getPropertyValue("--md-sys-color-outline-variant").trim() || "#53433F",
        text: styles.getPropertyValue("--md-sys-color-on-surface-variant").trim() || "#D8C2BC",
        ma: styles.getPropertyValue("--md-sys-color-tertiary").trim() || "#D8C58D",
        volumeBase: styles.getPropertyValue("--md-sys-color-surface-container-high").trim() || "#322825",
    };
}

document.addEventListener("DOMContentLoaded", () => {
    const figs = document.querySelectorAll("figure.qk-chart");
    figs.forEach((fig) => loadAndRender(fig));
});

async function loadAndRender(fig) {
    const token = fig.dataset.shareToken;
    const key = fig.dataset.blockKey;
    const tsCode = fig.dataset.tsCode;
    const period = fig.dataset.period;
    if (!token || !key || !tsCode || !period) {
        markError(fig, "图表参数缺失");
        return;
    }

    const url =
        `/api/v1/public/share/${encodeURIComponent(token)}/candles` +
        `?key=${encodeURIComponent(key)}` +
        `&tsCode=${encodeURIComponent(tsCode)}` +
        `&period=${encodeURIComponent(period)}`;

    try {
        const resp = await fetch(url, { credentials: "omit" });
        if (!resp.ok) {
            markError(fig, `加载失败 (${resp.status})`);
            return;
        }
        const wrapper = await resp.json();
        if (!wrapper || wrapper.success !== true || !wrapper.data) {
            markError(fig, wrapper?.message || "数据为空");
            return;
        }
        const candles = wrapper.data.candles || [];
        if (!candles.length) {
            markError(fig, "暂无数据");
            return;
        }
        renderChart(fig, candles);
        fig.classList.add("is-ready");
    } catch (e) {
        console.error(e);
        markError(fig, "网络错误");
    }
}

function markError(fig, message) {
    fig.dataset.error = message;
    fig.classList.add("is-error");
}

function renderChart(fig, candles) {
    const canvas = fig.querySelector(".qk-canvas");
    if (!canvas) return;
    const dpr = window.devicePixelRatio || 1;
    const cssWidth = canvas.clientWidth;
    const cssHeight = canvas.clientHeight;
    canvas.width = Math.round(cssWidth * dpr);
    canvas.height = Math.round(cssHeight * dpr);
    const ctx = canvas.getContext("2d");
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, cssWidth, cssHeight);

    // 主图占 70%，成交量副图占 30%
    const padding = { top: 16, right: 12, bottom: 24, left: 48 };
    const innerWidth = cssWidth - padding.left - padding.right;
    const innerHeight = cssHeight - padding.top - padding.bottom;
    const mainHeight = innerHeight * 0.7;
    const volumeHeight = innerHeight * 0.25;
    const gap = innerHeight * 0.05;

    const ma20 = computeMA(candles, 20);

    // 价格范围（考虑 MA20）
    let priceMin = Number.POSITIVE_INFINITY;
    let priceMax = Number.NEGATIVE_INFINITY;
    for (const c of candles) {
        if (c.low < priceMin) priceMin = c.low;
        if (c.high > priceMax) priceMax = c.high;
    }
    for (const v of ma20) {
        if (v != null) {
            if (v < priceMin) priceMin = v;
            if (v > priceMax) priceMax = v;
        }
    }
    const pricePad = (priceMax - priceMin) * 0.08 || priceMax * 0.02 || 1;
    priceMin -= pricePad;
    priceMax += pricePad;

    let volMax = 0;
    for (const c of candles) {
        if (c.volume > volMax) volMax = c.volume;
    }
    volMax = volMax || 1;

    const candleWidth = Math.max(2, (innerWidth / candles.length) * 0.72);
    const stride = innerWidth / candles.length;

    const mainTop = padding.top;
    const volTop = padding.top + mainHeight + gap;

    drawGrid(ctx, padding.left, mainTop, innerWidth, mainHeight, 4);
    drawPriceAxis(ctx, padding.left, mainTop, mainHeight, priceMin, priceMax);

    // 蜡烛
    for (let i = 0; i < candles.length; i++) {
        const c = candles[i];
        const x = padding.left + i * stride + stride / 2;
        const open = priceToY(c.open, mainTop, mainHeight, priceMin, priceMax);
        const close = priceToY(c.close, mainTop, mainHeight, priceMin, priceMax);
        const high = priceToY(c.high, mainTop, mainHeight, priceMin, priceMax);
        const low = priceToY(c.low, mainTop, mainHeight, priceMin, priceMax);
        const isBull = c.close >= c.open;
        const color = isBull ? CHART_COLORS.bullish : CHART_COLORS.bearish;
        ctx.strokeStyle = color;
        ctx.fillStyle = color;
        ctx.lineWidth = 1;
        // 影线
        ctx.beginPath();
        ctx.moveTo(x, high);
        ctx.lineTo(x, low);
        ctx.stroke();
        // 实体
        const bodyTop = Math.min(open, close);
        const bodyHeight = Math.max(1, Math.abs(close - open));
        ctx.fillRect(x - candleWidth / 2, bodyTop, candleWidth, bodyHeight);
    }

    // MA20 线
    ctx.strokeStyle = CHART_COLORS.ma;
    ctx.lineWidth = 1.4;
    ctx.beginPath();
    let started = false;
    for (let i = 0; i < ma20.length; i++) {
        const v = ma20[i];
        if (v == null) continue;
        const x = padding.left + i * stride + stride / 2;
        const y = priceToY(v, mainTop, mainHeight, priceMin, priceMax);
        if (!started) {
            ctx.moveTo(x, y);
            started = true;
        } else {
            ctx.lineTo(x, y);
        }
    }
    ctx.stroke();

    // 成交量副图
    drawGrid(ctx, padding.left, volTop, innerWidth, volumeHeight, 2);
    for (let i = 0; i < candles.length; i++) {
        const c = candles[i];
        const x = padding.left + i * stride + stride / 2;
        const h = (c.volume / volMax) * volumeHeight;
        const y = volTop + volumeHeight - h;
        const isBull = c.close >= c.open;
        ctx.fillStyle = isBull
            ? hexWithAlpha(CHART_COLORS.bullish, 0.7)
            : hexWithAlpha(CHART_COLORS.bearish, 0.7);
        ctx.fillRect(x - candleWidth / 2, y, candleWidth, h);
    }

    // 横轴日期（首/中/末 3 个）
    ctx.fillStyle = CHART_COLORS.text;
    ctx.font = "11px var(--font-sans), sans-serif";
    ctx.textBaseline = "top";
    const dateY = cssHeight - padding.bottom + 6;
    const formatDate = (s) => (s || "").slice(0, 10);
    if (candles.length > 0) {
        ctx.textAlign = "left";
        ctx.fillText(formatDate(candles[0].date), padding.left, dateY);
        ctx.textAlign = "center";
        if (candles.length > 2) {
            ctx.fillText(
                formatDate(candles[Math.floor(candles.length / 2)].date),
                padding.left + innerWidth / 2,
                dateY,
            );
        }
        ctx.textAlign = "right";
        ctx.fillText(formatDate(candles[candles.length - 1].date), padding.left + innerWidth, dateY);
    }
}

function computeMA(candles, n) {
    const out = new Array(candles.length).fill(null);
    let sum = 0;
    for (let i = 0; i < candles.length; i++) {
        sum += candles[i].close;
        if (i >= n) sum -= candles[i - n].close;
        if (i >= n - 1) out[i] = sum / n;
    }
    return out;
}

function priceToY(price, top, height, min, max) {
    return top + (1 - (price - min) / (max - min)) * height;
}

function drawGrid(ctx, x, y, w, h, rows) {
    ctx.strokeStyle = hexWithAlpha(CHART_COLORS.grid, 0.5);
    ctx.lineWidth = 1;
    for (let i = 0; i <= rows; i++) {
        const yy = y + (h / rows) * i;
        ctx.beginPath();
        ctx.moveTo(x, yy);
        ctx.lineTo(x + w, yy);
        ctx.stroke();
    }
}

function drawPriceAxis(ctx, x, top, height, min, max) {
    ctx.fillStyle = CHART_COLORS.text;
    ctx.font = "11px var(--font-sans), sans-serif";
    ctx.textBaseline = "middle";
    ctx.textAlign = "right";
    const steps = 4;
    for (let i = 0; i <= steps; i++) {
        const price = max - ((max - min) * i) / steps;
        const y = top + (height * i) / steps;
        ctx.fillText(price.toFixed(2), x - 6, y);
    }
}

function hexWithAlpha(hex, alpha) {
    // 支持 #RRGGBB；返回 rgba(...)
    const h = (hex || "").trim();
    if (!h.startsWith("#") || h.length !== 7) return h;
    const r = parseInt(h.slice(1, 3), 16);
    const g = parseInt(h.slice(3, 5), 16);
    const b = parseInt(h.slice(5, 7), 16);
    return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}
