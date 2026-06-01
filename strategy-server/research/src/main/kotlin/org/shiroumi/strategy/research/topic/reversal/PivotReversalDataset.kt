package org.shiroumi.strategy.research.topic.reversal

import kotlinx.datetime.LocalDate
import org.shiroumi.database.sentiment.SentimentFactorDailyRecord
import org.shiroumi.database.sentiment.SentimentFactorDailyRepository
import org.shiroumi.strategy.research.pipeline.ResearchContext
import org.shiroumi.strategy.research.topic.factor.study.VolumePriceFactors
import org.shiroumi.strategy.research.topic.trend.tuner.NextDaySentimentScorer
import org.shiroumi.strategy.research.topic.trend.tuner.SentimentFactorRecord
import kotlin.math.ln

/**
 * 反转研究的样本数据集 —— 「装配一次、反复打分」的单一数据底座。
 *
 * 把昂贵的取数（全市场日线 + trend score + VP 推导）与样本对齐**只做一次**，产出不可变的
 * [PivotReversalFeatures.Sample] 序列（已 rolling-z、严格 t−1 → t）。Study 和 tuner harness
 * 都消费同一个数据集：Study 跑一次诊断，tuner 在内存里**只换 [PivotReversalScorer.Params] 重打分**，
 * 不重复触库——这是参数搜索可行的性能前提。
 *
 * 时序切分（不打乱、保留因果）：train 60% / val 20% / test 20%，与 §7.4「绝不回训练集」一致。
 */
class PivotReversalDataset(
    val samples: List<PivotReversalFeatures.Sample>,
) {
    val labels: IntArray = IntArray(samples.size) { samples[it].label }
    val positives: Int = labels.count { it == 1 }

    private val trainEnd = (samples.size * 0.6).toInt()
    private val valEnd = (samples.size * 0.8).toInt()

    val train: List<PivotReversalFeatures.Sample> get() = samples.subList(0, trainEnd)
    val validation: List<PivotReversalFeatures.Sample> get() = samples.subList(trainEnd, valEnd)
    val test: List<PivotReversalFeatures.Sample> get() = samples.subList(valEnd, samples.size)

    companion object {

        /**
         * 上游情绪因子层 key（trend 研究 [NextDaySentimentScorer] 消费的因子）中**不依赖涨停/连板数据**的子集。
         *
         * 关键约束（用户洞察 2026-05-30）：涨停/连板事实表只有 2020 年后才有，依赖它的因子（A5/A6 涨停跌停家数、
         * C 树整族 连板高度/数量/破板率）会把研究**锁死在 2020+ 的单一市场结构**，导致连续切分的 train/val/test
         * 落入不同 regime、test 段崩。**剔除连板族**后，剩余因子可回溯到 2000 年，跨越多轮完整牛熊，
         * 让三集切分覆盖多种市场结构、样本与正类大幅增加，缓解非平稳与小样本。
         *
         * 剔除：A5, A6（涨停/跌停家数）、C1, C2, C2p, C3, C4, C6, C7（连板高度/数量/质量/破板，全依赖 limit 表）。
         * 保留：A 树资金/涨幅/链条（不含 A5/A6）、B 树量能形态、D 树、E 树温度 —— 均可由 OHLC/换手回溯到 2000。
         */
        private val SENTIMENT_FACTORS: List<String> = listOf(
            "A1", "A2", "A3", "A4", "A7", "A8", "A9a", "A9b", "A10", "A11", "A11a", "A12",
            "B1", "B3", "B3p", "B4", "B5", "B6", "B7",
            "D1", "D2", "D3", "D4", "D5", "D6", "D7",
            "E1", "E2",
        )

        /**
         * 从 DB 取数装配（Study / Harness 共用入口）。direction 决定顶反/底反方向（regime 域 + 有效标签）。
         *
         * 装配（触库 + 全市场聚合）走 [PivotReversalDatasetCache]：同一「区间+方向+open5m+标签口径」首次装配后落盘，
         * 后续 run 命中缓存直接复用，跳过 DB 读取与聚合（`--cache false` 强制重建）。数据底座不变、只调模型的
         * 迭代由此从分钟级降到秒级。
         */
        fun load(ctx: ResearchContext, direction: PivotReversalFeatures.Direction): PivotReversalDataset =
            PivotReversalDatasetCache.loadOrBuild(ctx, direction) {
                val records = SentimentFactorDailyRepository.findBetween(ctx.startDate, ctx.endDate)
                    .sortedBy { it.tradeDate }
                val market = PivotMarketSeries.load(ctx.startDate, ctx.endDate)
                val atrK = ctx.param("atr-k", "1.5").toDouble()
                val futureWin = ctx.param("future-win", "7").toInt()
                val crashThreshold = ctx.param("crash-threshold", "0.01").toDouble()
                // 当日开盘 5min 量价（正交新信息，t 日 09:35 已知）：仅 --open5m true 时加载并注入，基线路径不受影响。
                val open5m = if (ctx.param("open5m", "false") == "true")
                    PivotOpen5mSeries.load(ctx.startDate, ctx.endDate).associateBy { it.tradeDate }
                else emptyMap()
                // open5m 注入口径：full=8 因子全注入；breadth=只注 3 个横截面广度（去掉与隔夜状态共线的 5 个市值加权市场级量）。
                val open5mMode = ctx.param("open5m-mode", "full")
                val osc = ctx.param("osc", "false") == "true"
                val oscMode = ctx.param("osc-mode", "base")
                assemble(records, market, direction, atrK, futureWin, crashThreshold, open5m, open5mMode, osc, oscMode)
            }

        /** 纯内存装配（测试可直接喂数据，不碰 DB）。 */
        fun assemble(
            records: List<SentimentFactorDailyRecord>,
            market: List<PivotMarketSeries.DaySnapshot>,
            direction: PivotReversalFeatures.Direction = PivotReversalFeatures.Direction.TOP,
            atrK: Double = 1.5,
            futureWin: Int = 7,
            crashThreshold: Double = 0.01,
            open5m: Map<LocalDate, PivotOpen5mSeries.DaySnapshot> = emptyMap(),
            open5mMode: String = "full",
            oscEnabled: Boolean = false,
            oscMode: String = "base",
        ): PivotReversalDataset {
            val marketByDate = market.associateBy { it.tradeDate }
            val aligned = records.filter { it.tradeDate in marketByDate }
            val dates: List<LocalDate> = aligned.map { it.tradeDate }
            if (dates.isEmpty()) return PivotReversalDataset(emptyList())

            // trend score P^up（与 records 全序列对齐；只读消费生产参数）
            val trendScore = NextDaySentimentScorer().scoreSeries(toSentiment(records))
            val trendByDate = records.indices.associate { records[it].tradeDate to trendScore.getOrNull(it) }
                .mapValues { (_, v) -> v?.takeIf { it.isFinite() } }

            val ret = aligned.map { it.vpmRet }
            val turn = aligned.map { it.vpmTurn }
            val vp = VolumePriceFactors.buildAll(ret, turn)
            val vDot = firstDiff(volumeAnomaly(turn, base = 20))

            // 因子层附加特征（不只用聚合 score）。除因子原值（level）外，追加**高阶微分**：
            // 一阶差分 d1（速度）、二阶差分 d2（加速度）——反转 = 惯性被打破，本质是高阶导数现象（文档 §II），
            // 之前公式只用了 R_intra 一个序列的一阶差分，远未系统化。由体检 B 自动评估哪些微分项有 OOS 判别力。
            val extraSeries: Map<String, List<Double?>> = buildMap {
                val baseFactors = SENTIMENT_FACTORS.associateWith { key -> aligned.map { it.factors[key] } } +
                    mapOf(
                        // trendScore 是最强单因子（强反向），其速度/加速度可能携带「亢奋见顶速率」信息
                        "trend" to dates.map { trendByDate[it] },
                        // 市场路径因子也纳入微分探索
                        "rIntra" to dates.map { marketByDate[it]?.rIntra },
                        "pClose" to dates.map { marketByDate[it]?.pClose },
                        // 横截面分布矩（聚合单值之外的「市场分歧」维度，正交新信息）——各自带 level/d1/d2。
                        "xsStd" to dates.map { marketByDate[it]?.xsStd },
                        "xsSkew" to dates.map { marketByDate[it]?.xsSkew },
                        "xsKurt" to dates.map { marketByDate[it]?.xsKurt },
                        "xsDownRatio" to dates.map { marketByDate[it]?.xsDownRatio },
                        "xsWeightDiv" to dates.map { marketByDate[it]?.xsWeightDiv },
                        // 个股级日内疲态横截面广度（市值加权抹掉的正交信号；各带 level/d1/d2）。
                        "xsUpperFade" to dates.map { marketByDate[it]?.xsUpperFadeRatio },
                        "xsWeakClose" to dates.map { marketByDate[it]?.xsWeakCloseRatio },
                        "xsIntraDrop" to dates.map { marketByDate[it]?.xsIntraDropRatio },
                    )
                for ((key, series) in baseFactors) {
                    put(key, series)                       // level
                    put("${key}_d1", diffN(series, 1))     // 速度
                    put("${key}_d2", diffN(series, 2))     // 加速度
                }
                // 交互项 + 非线性：大阴线由「亢奋（trend 主导，IC−0.49）」遇上「某种脆弱/分歧信号」共同引爆。
                // trend × {E1 温度, B3 形态, B6 量能, D1} 捕捉联合条件；trend² 捕捉极端亢奋的非线性加速回落。
                val trendSeries = dates.map { trendByDate[it] }
                for (k in listOf("E1", "B3", "B6", "D1", "B7")) {
                    put("trendX$k", product(trendSeries, aligned.map { it.factors[k] }))
                }
                put("trendSq", product(trendSeries, trendSeries))
                // trend 长窗形态：亢奋不是单日水平，而是「累积了多久/多陡」。过热往往是连续多日高亢后才崩。
                put("trend_ma5", rollMean(trendSeries, 5))                  // 近 5 日平均亢奋
                put("trend_ma10", rollMean(trendSeries, 10))                // 近 10 日平均亢奋
                put("trend_hot5", rollCount(trendSeries, 5) { it > 0.6 })   // 近 5 日「高亢奋(>0.6)」天数
                put("trend_slope5", rollSlope(trendSeries, 5))              // 近 5 日亢奋斜率（还在加速变热？）
                // 见顶降温信号：高亢奋(level)且刚开始回落(trend_d1<0)——「过热见顶、热度初退」常是崩盘前兆，
                // 比单纯高亢奋更接近导火索。trendPeakFade = level × max(0, −Δtrend)。
                val trendD1 = diffN(trendSeries, 1)
                put("trendPeakFade", trendSeries.indices.map { t ->
                    val lv = trendSeries[t]; val d = trendD1.getOrNull(t)
                    if (lv == null || d == null) null else lv * maxOf(0.0, -d)
                })
                // 滚动分位：当前亢奋在过去 N 日的百分位——「亢奋达到历史极端」比绝对水平更能定位过热顶点。
                put("trend_pct60", rollPercentile(trendSeries, 60))
                put("trend_pct120", rollPercentile(trendSeries, 120))
                // 多尺度中期亢奋累积（更长窗）+ 亢奋不稳定性（高位剧烈波动 = 多空分歧加剧，易引爆）。
                put("trend_ma20", rollMean(trendSeries, 20))
                put("trend_ma60", rollMean(trendSeries, 60))
                put("trend_std10", rollStd(trendSeries, 10))
                // 极端分位 × 见顶降温的复合（达历史极端高位 且 开始回落 = 最强崩盘前兆）
                val pct120 = rollPercentile(trendSeries, 120)
                put("trendExtremeFade", trendSeries.indices.map { t ->
                    val q = pct120.getOrNull(t); val d = trendD1.getOrNull(t)
                    if (q == null || d == null) null else q * maxOf(0.0, -d)
                })
                // ── 情绪预测「成果」的元特征（stacking 思想：P^up 是已训练情绪模型对次日方向的预测，
                //    浓缩 38 因子非线性，信息密度高于原始因子）。大阴线 = 情绪模型「误判」的意外日，
                //    其前兆是「情绪预测与量价现实的背离」与「情绪过度自信」。──
                val rIntraS = dates.map { marketByDate[it]?.rIntra }   // 当日日内表现（t−1 对齐后即昨日已知）
                val vpBeta = vp["VPbeta"]!!
                // ① 情绪×量价背离：情绪仍亢奋(高 P^up)却量价弹性走弱(β 低，单位量推不动价)——基础已塌。
                put("emoDivergence", trendSeries.indices.map { t ->
                    val e = trendSeries[t]; val b = vpBeta.getOrNull(t)
                    if (e == null || b == null) null else e * (-b)         // 高情绪 × 负弹性 = 背离强
                })
                // ② 情绪过度自信陷阱：P^up 越超过中性越自信看涨；大阴线常在「自信看涨 × 当日已显疲态」时引爆。
                put("emoOverconfidence", trendSeries.indices.map { t ->
                    val e = trendSeries[t]; val ri = rIntraS.getOrNull(t)
                    if (e == null || ri == null) null else maxOf(0.0, e - 0.55) * (-ri)  // 高自信 × 日内已收弱
                })
                // ③ 情绪预测的不确定性：|P^up−0.5| 小 = 情绪模型对次日没把握，方向脆弱、易突变下行。
                val emoUnc = trendSeries.map { it?.let { v -> 0.5 - kotlin.math.abs(v - 0.5) } }
                put("emoUncertainty", emoUnc)
                // ── 低亢奋域内的高阶组合（域内体检 B：唯一仍有判别力的是 trend 族，量价/形态在市场级日频本就弱）。
                //    在 trend 族这唯一有效维度上做高阶交互，榨域内增量。──
                val peakFade = trendSeries.indices.map { t ->
                    val lv = trendSeries[t]; val d = trendD1.getOrNull(t)
                    if (lv == null || d == null) null else lv * maxOf(0.0, -d)
                }
                // 不确定 × 见顶降温：情绪没把握 且 热度正退 —— 域内最危险的「方向真空 + 退潮」叠加。
                put("emoUncX_peakFade", product(emoUnc, peakFade))
                // 历史极端分位 × 不确定：达历史高位却方向不明 —— 高位失锚。
                put("pct120X_emoUnc", product(pct120, emoUnc))
                // trend 三阶微分（加加速度）：恶化速率的变化，捕捉「退潮在加速还是减速」。
                put("trend_d3", diffN(trendSeries, 3))
                // 不确定性的变化率：情绪模型从「有把握」滑向「没把握」的速度（信心崩解速率）。
                put("emoUnc_d1", diffN(emoUnc, 1))
                // 注：横截面分布因子（xsStd/xsSkew/xsKurt/xsDownRatio/xsWeightDiv）已在 baseFactors 统一纳入
                // （自带 level/d1/d2）；此处不重复定义，避免因子膨胀稀释 topK / NelderMead 高维退化。
                // 横截面分歧 × 情绪：横截面是正交新信息（实测 P^up<0.3 域 AUC +0.012）；与情绪最强维度结合捕捉「双重恶化」。
                val xsDown = dates.map { marketByDate[it]?.xsDownRatio }
                val xsSkewS = dates.map { marketByDate[it]?.xsWeightDiv }   // 市值加权−等权背离（指数虚高）
                // 下跌广度 × 情绪没把握：多数股已跌 且 情绪模型无方向 = 广度恶化叠加方向真空。
                put("xsDownX_emoUnc", product(xsDown, emoUnc))
                // 指数虚高背离 × 退潮：权重股撑指数 且 热度正退 = 最危险的「假强真弱」结构。
                put("xsWeightDivX_peakFade", product(xsSkewS, peakFade))
                // 个股级日内疲态广度 × 情绪没把握：大面积个股已日内冲高回落/弱收 且 情绪模型无方向 =
                // 「微观结构已塌（广度证据）+ 宏观方向真空」的双重确认，市值加权聚合看不到这个广度。
                val xsUpperFadeS = dates.map { marketByDate[it]?.xsUpperFadeRatio }
                val xsIntraDropS = dates.map { marketByDate[it]?.xsIntraDropRatio }
                put("xsUpperFadeX_emoUnc", product(xsUpperFadeS, emoUnc))
                put("xsIntraDropX_peakFade", product(xsIntraDropS, peakFade))

                // ── 技术振荡器 RSI/%B/Z-Score/ADX（参考均值回归报告，--osc true 注入；现有因子无等价物）。──
                // 报告核心触发器：RSI(2)/布林%B/Z-Score 短期反转、ADX<25 震荡市门控。从市场级等权收益累乘价格指数算。
                if (oscEnabled) {
                    val oscs = PivotOscillators.compute(dates.map { marketByDate[it]?.retClose })
                    val oscByDate = dates.indices.associate { dates[it] to oscs[it] }
                    val oscFactors = mapOf(
                        "oscRsi2" to dates.map { oscByDate[it]?.rsi2 },
                        "oscRsi9" to dates.map { oscByDate[it]?.rsi9 },
                        "oscPctB" to dates.map { oscByDate[it]?.pctB },
                        "oscZ20" to dates.map { oscByDate[it]?.zScore },
                        "oscAdx" to dates.map { oscByDate[it]?.adx },
                        // Hurst：市场长程记忆状态（<0.5均值回归态/>0.5趋势态），比ADX更本质的状态判别器。
                        "oscHurst" to dates.map { oscByDate[it]?.hurst },
                    )
                    for ((k, s) in oscFactors) {
                        put(k, s); put("${k}_d1", diffN(s, 1)); put("${k}_d2", diffN(s, 2))
                    }
                    // ADX 门控交互（报告：均值回归只在低ADX震荡市有效）：trend亢奋 × (25−ADX) 正部 = 震荡市里的亢奋。
                    val adxLow = dates.map { oscByDate[it]?.adx?.let { a -> maxOf(0.0, 25.0 - a) } }
                    put("oscTrendXadxLow", product(trendSeries, adxLow))
                    // Hurst 趋势态门控（报告反洞察：大阴线高发在趋势市）：trend亢奋 × (H−0.5)正部 = 趋势态里的亢奋。
                    val hurstTrend = dates.map { oscByDate[it]?.hurst?.let { h -> maxOf(0.0, h - 0.5) } }
                    put("oscTrendXhurst", product(trendSeries, hurstTrend))

                    // lag 档（用户洞察 2026-06-01）：系统性极端情绪有惯性、延续1-2天才兑现（非个股当天结束）。
                    // 把「趋势态里的极端超买」信号沿时间前向传播（过去2天内的极端最大）+ 纯滞后1/2天对齐，
                    // 捞回「极端在前、大阴线滞后兑现」的正样本——目标提 recall。极端值保留连续度（不截断，吸取 ext 教训）。
                    if (oscMode == "lag") {
                        val rsi2 = dates.map { oscByDate[it]?.rsi2 }
                        val z20 = dates.map { oscByDate[it]?.zScore }
                        val pctB = dates.map { oscByDate[it]?.pctB }
                        // 趋势态里的极端超买（连续值，非截断）：RSI2/Z/%B × 趋势态(H>0.5)，作为「极端事件强度」基序列。
                        val rsi2Trend = product(rsi2, hurstTrend)
                        val z20Trend = product(z20, hurstTrend)
                        val pctBTrend = product(pctB, hurstTrend)
                        // 极端记忆前向传播：过去2天内「趋势态极端」的最大值带到今天（情绪延续1-2天）。
                        put("oscRsi2TrendMax2", rollMax(rsi2Trend, 2))
                        put("oscZTrendMax2", rollMax(z20Trend, 2))
                        put("oscRsi2TrendMax3", rollMax(rsi2Trend, 3))
                        // 纯滞后对齐：极端信号延后1天、2天兑现。
                        put("oscRsi2TrendLag1", lagN(rsi2Trend, 1))
                        put("oscRsi2TrendLag2", lagN(rsi2Trend, 2))
                        put("oscZTrendLag1", lagN(z20Trend, 1))
                        put("oscZTrendLag2", lagN(z20Trend, 2))
                        put("oscPctBTrendLag1", lagN(pctBTrend, 1))
                    }

                    // cross 档（2026-06-01 当前发现「亢奋×趋势态=大阴线温床」的强化）：把「极端触发」与「趋势态」
                    // 做乘性耦合——趋势市里的超买/过度拉伸（非震荡市的）才是真正危险，比单纯软门控更可能正交。
                    if (oscMode == "cross") {
                        val rsi2 = dates.map { oscByDate[it]?.rsi2 }
                        val z20 = dates.map { oscByDate[it]?.zScore }
                        val pctB = dates.map { oscByDate[it]?.pctB }
                        // 趋势市里的超买：RSI2 × 趋势态（H>0.5）。震荡市超买会均值回归，趋势市超买是末端拉伸。
                        put("oscRsi2Xhurst", product(rsi2, hurstTrend))
                        // 趋势市里的价格过度拉伸：Z-Score × 趋势态。
                        put("oscZXhurst", product(z20, hurstTrend))
                        // 趋势市里的通道顶：%B × 趋势态。
                        put("oscPctBXhurst", product(pctB, hurstTrend))
                        // 超买 × 趋势态 × 亢奋（三重耦合）：极端 + 趋势末端 + 情绪亢奋 = 最危险组合。
                        put("oscRsi2XhurstXtrend", product(product(rsi2, hurstTrend), trendSeries))
                    }

                    // ext 档（深挖报告洞察 2026-06-01）：报告强调用「极端值」而非连续值，且 ADX 是「生存门控」、
                    // 多周期 RSI「双重超卖」共振更强。把这些显式化：
                    if (oscMode == "ext") {
                        val rsi2 = dates.map { oscByDate[it]?.rsi2 }
                        val rsi9 = dates.map { oscByDate[it]?.rsi9 }
                        val pctB = dates.map { oscByDate[it]?.pctB }
                        val z20 = dates.map { oscByDate[it]?.zScore }
                        val adx = dates.map { oscByDate[it]?.adx }
                        // 极端区「距阈值的距离」（报告口径：超买 RSI>90/%B>1/Z>2 才是反转触发；越深越强）。
                        // 大阴线=顶部反转，故取「超买侧」极端度：max(0, 值−阈值)。
                        put("oscRsi2_hot", rsi2.map { it?.let { v -> maxOf(0.0, v - 90.0) } })
                        put("oscRsi9_hot", rsi9.map { it?.let { v -> maxOf(0.0, v - 80.0) } })
                        put("oscPctB_hot", pctB.map { it?.let { v -> maxOf(0.0, v - 1.0) } })
                        put("oscZ20_hot", z20.map { it?.let { v -> maxOf(0.0, v - 2.0) } })
                        // 多周期 RSI 双重超买共振（报告：双重 Stoch-RSI 胜率高于单一）：两个 RSI 同处高位才点亮。
                        put("oscRsiResonance", product(
                            rsi2.map { it?.let { v -> maxOf(0.0, v - 70.0) } },
                            rsi9.map { it?.let { v -> maxOf(0.0, v - 60.0) } },
                        ))
                        // 高位 × 趋势市（报告反洞察：振荡器超买在强趋势市≠反转，需 ADX 区分）：超买度 × ADX。
                        put("oscRsi2hotXadx", product(rsi2.map { it?.let { v -> maxOf(0.0, v - 70.0) } }, adx))
                        // 极端超买 × 低ADX震荡市：报告最强组合——震荡市里的极端超买=均值回归反转高概率。
                        put("oscZhotXadxLow", product(z20.map { it?.let { v -> maxOf(0.0, v - 1.0) } }, adxLow))
                    }
                }

                // ── 当日开盘 5min 量价（正交新信息，t 日 09:35 已知；仅 open5m 非空时注入）。──
                // 因果：这是「当日盘中预警」维度——开盘集合竞价+前5min浓缩隔夜消息/外盘/资金首次定价，
                // 对「t 日是否收盘大阴线」是此前所有 t−1 特征都不含的正交依据。各带 level/d1/d2。
                if (open5m.isNotEmpty()) {
                    // 市值加权市场级开盘量价：和隔夜状态（已被 t−1 收盘形态/trendScore 编码）高度共线，正交信息少。
                    val weightedFactors = mapOf(
                        "o5Gap" to dates.map { open5m[it]?.openGap },
                        "o5Ret" to dates.map { open5m[it]?.o5Ret },
                        "o5Amp" to dates.map { open5m[it]?.o5Amp },
                        "o5UpperWick" to dates.map { open5m[it]?.o5UpperWick },
                        "o5GapFade" to dates.map { open5m[it]?.o5GapFade },
                    )
                    // 横截面广度（多少比例个股开盘即低开/走弱/收弱）：市值加权市场级看不到的微观结构，最可能正交。
                    val breadthFactors = mapOf(
                        "o5XsGapDown" to dates.map { open5m[it]?.xsGapDownRatio },
                        "o5XsDrop" to dates.map { open5m[it]?.xsO5DropRatio },
                        "o5XsWeak" to dates.map { open5m[it]?.xsO5WeakRatio },
                    )
                    // breadth 模式只注广度（消融：验证退化是否由共线的加权市场级量引入）；full 模式两者都注。
                    val injected = if (open5mMode == "breadth") breadthFactors else weightedFactors + breadthFactors
                    for ((k, s) in injected) {
                        put(k, s); put("${k}_d1", diffN(s, 1)); put("${k}_d2", diffN(s, 2))
                    }
                    // 开盘转弱广度 × 情绪没把握：开盘大面积走弱 且 情绪模型无方向 = 当日杀跌最强即时前兆。
                    put("o5XsDropX_emoUnc", product(dates.map { open5m[it]?.xsO5DropRatio }, emoUnc))
                    if (open5mMode != "breadth") {
                        // 高开走弱 × 历史高位失锚：高开却 5min 内回落 且 处历史亢奋极端 = 假强开盘。
                        put("o5GapFadeX_pct120", product(dates.map { open5m[it]?.o5GapFade }, pct120))
                    }
                    // seq 档（用户洞察 2026-06-01）：单日开盘5min信息≈噪声(AUC≈0.5)，但「多天开盘5min序列」也许藏低频趋势——
                    // 比如连续多日开盘走弱的累积、开盘走弱在加速。对语义最清晰的几个开盘量加多天时序聚合形态：
                    // ma3/ma5(持续走弱)、slope5(走弱加速)、ewma(连续走弱记忆)、cnt5(过去5日开盘弱天数)。
                    if (open5mMode == "seq") {
                        val o5Ret = dates.map { open5m[it]?.o5Ret }
                        val o5GapFade = dates.map { open5m[it]?.o5GapFade }
                        val xsDrop = dates.map { open5m[it]?.xsO5DropRatio }
                        val xsWeak = dates.map { open5m[it]?.xsO5WeakRatio }
                        for ((name, s) in listOf("o5Ret" to o5Ret, "o5GapFade" to o5GapFade, "o5XsDrop" to xsDrop, "o5XsWeak" to xsWeak)) {
                            put("${name}_ma3", rollMean(s, 3))
                            put("${name}_ma5", rollMean(s, 5))
                            put("${name}_slope5", rollSlope(s, 5))
                            put("${name}_ewma", ewma(s, 0.7))
                        }
                        // 过去5日「开盘走弱广度偏高(>0.5)」的天数：连续多日开盘弱的计数式记忆。
                        put("o5XsDrop_cnt5", rollCount(xsDrop, 5) { it > 0.5 })
                        put("o5XsWeak_cnt5", rollCount(xsWeak, 5) { it > 0.5 })
                    }
                }

                // ── 递推域状态 s_t（状态空间内生「高危记忆」）。──
                // 数学动机（2026-05-30，用户洞察「任何状态 t 可由历史窗口递推」）：之前「情绪极度转弱」只作硬门控
                // (samples.filter trendScore<τ) —— 二值、丢样本、不可微、且把连续域信息压成 0/1。这里把它内生为
                // **连续递推状态**：危险度 r_t = 1 − P^up_t（情绪转弱程度 ∈[0,1]），EWMA 累积成有惯性的高危记忆
                //   s_t = λ·s_{t−1} + (1−λ)·r_t
                // λ 越大记忆越长（高危一旦形成衰减慢）。s_t 大 ⟺ 「近期持续处于情绪转弱域」= 大阴线高发时段的连续刻画。
                // 取多条衰减尺度（短/中/长记忆）让模型自学最优时间常数；再走现有 _d1/_d2 管线得状态速度/加速度。
                val danger = trendSeries.map { it?.let { v -> (1.0 - v).coerceIn(0.0, 1.0) } }
                put("regimeState_fast", ewma(danger, lambda = 0.5))    // 短记忆（~2 日半衰）
                put("regimeState_mid", ewma(danger, lambda = 0.8))     // 中记忆（~3 日半衰）
                put("regimeState_slow", ewma(danger, lambda = 0.94))   // 长记忆（~11 日半衰，跨周高危积累）
                // 状态 × 当前亢奋：高危记忆 与 当前 P^up 的交互（持续转弱后当下仍未修复 = 域确认）。
                put("regimeStateX_trend", product(ewma(danger, 0.8), trendSeries))
                // 状态 × 见顶降温：高危记忆叠加热度正退 = 「累积脆弱 + 即时导火索」的状态-事件耦合。
                put("regimeStateX_peakFade", product(ewma(danger, 0.8), peakFade))
            }

            val samples = PivotReversalFeatures.assemble(
                dates = dates,
                rIntra = dates.map { marketByDate[it]?.rIntra },
                pClose = dates.map { marketByDate[it]?.pClose },
                sUpper = dates.map { marketByDate[it]?.sUpper },
                dOc = dates.map { marketByDate[it]?.dOc },
                // 标签口径统一用市场级（市值加权）相对昨收的 收/高/低 收益 + 日振幅。
                retClose = dates.map { marketByDate[it]?.retClose },
                retHigh = dates.map { marketByDate[it]?.retHigh },
                retLow = dates.map { marketByDate[it]?.retLow },
                atrPct = dates.map { marketByDate[it]?.atrPct },
                vp1 = vp["VP1"]!!, vp2c = vp["VP2c"]!!, vp2v = vp["VP2v"]!!, vpBeta = vp["VPbeta"]!!,
                vDot = vDot,
                trendScore = dates.map { trendByDate[it] },
                extraSeries = extraSeries,
                direction = direction,
                atrK = atrK,
                futureWin = futureWin,
                crashThreshold = crashThreshold,
            )
            return PivotReversalDataset(samples)
        }

        private fun toSentiment(records: List<SentimentFactorDailyRecord>): List<SentimentFactorRecord> =
            records.map {
                SentimentFactorRecord(
                    tradeDate = it.tradeDate.toString(),
                    factors = it.factors,
                    y1Raw = it.y1Raw, y2Raw = it.y2Raw, y3Raw = it.y3Raw,
                    yComposite = it.yComposite,
                )
            }

        /** 量异动 v_t = ln(τ_t / 截至昨日 base 日均换手)，与 [VolumePriceFactors] 同口径（门控原料）。 */
        private fun volumeAnomaly(turn: List<Double?>, base: Int): List<Double?> =
            turn.indices.map { t ->
                val cur = turn[t] ?: return@map null
                if (cur <= 0.0 || t < base) return@map null
                val win = ((t - base) until t).mapNotNull { turn[it] }
                val mean = if (win.isEmpty()) 0.0 else win.average()
                if (mean <= 0.0) null else ln(cur / mean)
            }

        /**
         * 指数加权移动平均（递推状态）：s_t = λ·s_{t−1} + (1−λ)·x_t，起点用首个有效值。
         * 严格因果（s_t 只依赖 s_{t−1} 与 x_t，无未来泄漏）；缺值日延续上一状态、不产出（留 null）。
         */
        private fun ewma(x: List<Double?>, lambda: Double): List<Double?> {
            val out = ArrayList<Double?>(x.size)
            var s: Double? = null
            for (v in x) {
                if (v == null || !v.isFinite()) { out += s; continue }
                s = if (s == null) v else lambda * s + (1.0 - lambda) * v
                out += s
            }
            return out
        }

        private fun firstDiff(s: List<Double?>): List<Double?> =
            s.indices.map { t ->
                if (t < 1) null else {
                    val a = s[t]; val b = s[t - 1]
                    if (a == null || b == null) null else a - b
                }
            }

        /** 滚动均值（窗 [t-win+1..t]，仅前序，不足窗 null）。 */
        private fun rollMean(s: List<Double?>, win: Int): List<Double?> =
            s.indices.map { t ->
                if (t < win - 1) return@map null
                var sum = 0.0; var c = 0
                for (i in (t - win + 1)..t) s[i]?.let { sum += it; c++ }
                if (c < win) null else sum / c
            }

        /**
         * 滚动窗内最大值（前向传播「极端记忆」）：把过去 win 天内出现过的最高值带到今天。
         * 用户洞察（2026-06-01）：系统性极端情绪有惯性、延续 1-2 天才兑现，不像个股当天结束。
         * 故 t-2 的极端超买经此在 t-1/t 仍「被记得」——捞回那些「极端在前、大阴线滞后兑现」的正样本（提 recall）。
         * 严格前序：t 只看 [t-win+1..t]，无未来泄漏。
         */
        private fun rollMax(s: List<Double?>, win: Int): List<Double?> =
            s.indices.map { t ->
                if (t < win - 1) return@map null
                var mx = Double.NEGATIVE_INFINITY; var c = 0
                for (i in (t - win + 1)..t) s[i]?.let { if (it > mx) mx = it; c++ }
                if (c == 0) null else mx
            }

        /** 纯滞后 n 日：把序列整体右移 n（t 取 s[t-n]）。捕捉「极端信号延后兑现」的错位对齐。 */
        private fun lagN(s: List<Double?>, n: Int): List<Double?> =
            s.indices.map { t -> if (t < n) null else s[t - n] }

        /** 滚动分位：当前值在窗 [t-win+1..t] 内的百分位 ∈[0,1]（仅前序，无未来泄漏）。 */
        private fun rollPercentile(s: List<Double?>, win: Int): List<Double?> =
            s.indices.map { t ->
                if (t < win - 1) return@map null
                val cur = s[t] ?: return@map null
                var le = 0; var c = 0
                for (i in (t - win + 1)..t) s[i]?.let { c++; if (it <= cur) le++ }
                if (c < win) null else le.toDouble() / c
            }

        /** 滚动标准差（窗内，仅前序）。 */
        private fun rollStd(s: List<Double?>, win: Int): List<Double?> =
            s.indices.map { t ->
                if (t < win - 1) return@map null
                val vals = ArrayList<Double>(win)
                for (i in (t - win + 1)..t) s[i]?.let { vals += it }
                if (vals.size < win) return@map null
                val m = vals.average()
                kotlin.math.sqrt(vals.sumOf { (it - m) * (it - m) } / vals.size)
            }

        /** 滚动满足条件计数（窗内 predicate 为真的天数）。 */
        private fun rollCount(s: List<Double?>, win: Int, pred: (Double) -> Boolean): List<Double?> =
            s.indices.map { t ->
                if (t < win - 1) return@map null
                var n = 0; var c = 0
                for (i in (t - win + 1)..t) s[i]?.let { c++; if (pred(it)) n++ }
                if (c < win) null else n.toDouble()
            }

        /** 滚动最小二乘斜率（窗内对时间索引回归）。 */
        private fun rollSlope(s: List<Double?>, win: Int): List<Double?> =
            s.indices.map { t ->
                if (t < win - 1) return@map null
                val ys = ArrayList<Double>(win)
                for (i in (t - win + 1)..t) s[i]?.let { ys += it }
                if (ys.size < win) return@map null
                val mx = (ys.size - 1) / 2.0; val my = ys.average()
                var num = 0.0; var den = 0.0
                for (k in ys.indices) { num += (k - mx) * (ys[k] - my); den += (k - mx) * (k - mx) }
                if (den == 0.0) null else num / den
            }

        /** 逐日乘积（交互项原料）：任一缺值则该日 null。rolling-z 在 Features 段统一处理。 */
        private fun product(a: List<Double?>, b: List<Double?>): List<Double?> =
            a.indices.map { t ->
                val x = a[t]; val y = b.getOrNull(t)
                if (x == null || y == null) null else x * y
            }

        /** n 阶差分（递归应用一阶差分）：d1=速度、d2=加速度。仅用前序，无未来泄漏。 */
        private fun diffN(s: List<Double?>, n: Int): List<Double?> {
            var cur = s
            repeat(n) { cur = firstDiff(cur) }
            return cur
        }
    }
}
