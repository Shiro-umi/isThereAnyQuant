# 原始情绪指标计算算法梳理

本文档只做一件事：从当前代码实现出发，逐步还原“市场情绪指标”在项目里的真实计算算法。

目标不是解释一个理想化公式，而是完整描述当前系统实际在跑的逻辑，包括：

- 日频情绪快照如何生成
- 样本股如何选择
- 输入行情如何对齐
- 每一个中间指标如何计算
- 最终仓位 `sentimentExposure` 如何得到
- 盘中实时增量如何基于 T-1 盘后结果更新
- 哪些字段被持久化，哪些字段在读回时被降级或重构
- 哪些边界条件会触发默认值、保护机制和回退逻辑

涉及的核心实现文件：

- [MarketSentimentCalculator.kt](/Users/zhouzheng/Code/quant/strategy-server/core/src/main/kotlin/org/shiroumi/strategy/core/daily/MarketSentimentCalculator.kt)
- [PostMarketPreparationJob.kt](/Users/zhouzheng/Code/quant/strategy-server/service/src/main/kotlin/org/shiroumi/strategy/service/postmarket/PostMarketPreparationJob.kt)
- [IntradaySentimentCalculator.kt](/Users/zhouzheng/Code/quant/strategy-server/core/src/main/kotlin/org/shiroumi/strategy/core/intraday/IntradaySentimentCalculator.kt)
- [DailyMarketSentimentRepository.kt](/Users/zhouzheng/Code/quant/database/src/main/kotlin/org/shiroumi/database/strategy/daily/repository/DailyMarketSentimentRepository.kt)
- [DefaultStrategyPreprocessor.kt](/Users/zhouzheng/Code/quant/database/src/main/kotlin/org/shiroumi/database/strategy/daily/preprocessing/DefaultStrategyPreprocessor.kt)
- [PreparedBar.kt](/Users/zhouzheng/Code/quant/shared/src/commonMain/kotlin/org/shiroumi/quant_kmp/strategy/daily/model/PreparedBar.kt)
- [StockFactorCalculator.kt](/Users/zhouzheng/Code/quant/strategy-server/core/src/main/kotlin/org/shiroumi/strategy/core/daily/StockFactorCalculator.kt)

---

## 1. 总体结论

当前“市场情绪”不是直接由指数、成交额或上涨家数简单拼出来的。

它的真实定义是：

1. 先为全市场股票构造标准化日线窗口。
2. 再从中抽样 500 只股票作为情绪样本股。
3. 对每个交易日，先计算样本股中 `EMA10 > EMA30` 的股票占比，得到 `bullRatio`。
4. 再从 `bullRatio` 这一条时间序列上，进一步计算：
   - `fftScore`
   - `residualScore`
   - `marketVol`
   - `volZ`
   - `accelZ`
5. 将上述五个维度按固定权重融合，得到基础情绪暴露值。
6. 最后再叠加绝对水位保护、波动率上限保护、可选的过热衰减，得到最终的 `sentimentExposure`。

也就是说：

- `bullRatio` 是基础观察量
- `sentimentExposure` 是最终策略真正使用的情绪仓位系数
- 日频情绪是一个“从样本股状态序列推导出来的综合仓位信号”

---

## 2. 日频情绪的完整计算链路

日频情绪是在每日盘后策略预处理时生成的，入口是：

- [PostMarketPreparationJob.kt](/Users/zhouzheng/Code/quant/strategy-server/service/src/main/kotlin/org/shiroumi/strategy/service/postmarket/PostMarketPreparationJob.kt)

真实执行顺序如下。

### 2.1 获取股票池

`PostMarketPreparationJob.run(...)` 首先通过 `MainBoardUniverseProvider.getActiveSymbols()` 获取主板股票池。

这一步得到的是“当天参与日频策略预处理的股票全集”。

注意：

- 情绪指标不是直接对所有股票强制计算。
- 后续只有历史足够的股票才会进入有效样本集合。

### 2.2 构造标准化历史窗口

然后 `DefaultStrategyPreprocessor.prepareStockWindows(...)` 会为每只股票准备 `PreparedStockWindow`。

这一层的职责是：

1. 从仓库读取该股票在 `[startDate, endDate]` 范围内的历史 `Candle`
2. 结合 `firstAdj` 构造 `hfqFactor`
3. 按指定 `signalBasis` / `executionBasis` 把原始 K 线转换成统一的 `PreparedBar`

`PreparedBar` 的核心字段：

- `open/high/low/close/volume`
  这是“给信号计算用”的价格体系
- `executionOpen/executionClose`
  这是“给执行参考用”的价格体系
- `raw*`
  原始未复权价格
- `qfq*`
  前复权价格
- `hfqFactor`
  后复权因子

情绪指标在日频阶段实际使用的是：

- `PreparedBar.close`
- `PreparedBar.high`
- `PreparedBar.low`
- `PreparedBar.volume`
- `PreparedBar.date`
- `PreparedBar.signalBasis`

### 2.3 过滤历史不足的股票

`PostMarketPreparationJob` 会剔除：

- `window.sufficientHistory == false`
- `window.bars.isEmpty()`

只有满足历史长度要求的股票，才会：

- 进入 `marketBarsBySymbol`
- 参与日频因子计算
- 有资格进入后续情绪样本抽样

也就是说，情绪指标的母集不是股票池全集，而是：

**股票池中历史足够的股票集合**

### 2.4 样本股抽样

> 注：盘后服务化迁移后（PR1 PostMarket owner 落地），情绪样本规则已简化为"主板 universe 全集"，参见 `PostMarketPreparationJob.run` 中 `val sentimentSampleCodes = universeSymbols`。下面的"500 抽样 + 哈希确定性洗牌"规则是历史实现，仅作算法演变对照保留，不再代表当前实际逻辑。

历史实现（已废弃）情绪计算不是对全部有效股票运行，而是固定抽样最多 500 只：

- 常量：`MARKET_SENTIMENT_SAMPLE_SIZE = 500`

抽样函数原本位于 `DailyStrategyDataPreparationJob.sampleMarketSentimentBars(...)`（已随该文件物理删除）。

历史规则如下：

1. 如果有效股票数 `<= 500`，则全部进入样本。
2. 如果有效股票数 `> 500`：
   - 先按股票代码排序
   - 再用 `tradeDate.toString().hashCode()` 作为随机种子
   - 用这个确定性种子做 Fisher-Yates 洗牌
   - 取前 500 只作为样本

这个设计意味着：

- 同一个交易日，多次运行会抽到同一批样本股
- 不同交易日，样本股可能变化
- 样本抽样是"日级稳定、跨日可变化"的

这点很关键，因为它决定了：

- `sampleSize`
- `bullRatio`
- 后续整条情绪序列

都依赖于当天确定性的 500 股样本。

---

## 3. 日频情绪的输入数据如何对齐

日频计算器入口是：

- `MarketSentimentCalculator.calculate(barsBySymbol, requiredHistory, ...)`

这里的 `barsBySymbol` 是：

- `Map<String, List<PreparedBar>>`

其中每只股票自己的历史序列长度、交易日覆盖范围未必完全相同，所以计算器首先要做对齐。

### 3.1 对齐主轴不是交集，而是并集

实现函数：

- `alignBarsBySymbol(...)`

它不是取所有股票共同存在的日期交集，而是：

1. 取所有股票历史里出现过的 `date` 的并集
2. 排序后得到 `allDates`
3. 每只股票都对齐到这个公共日期主轴

### 3.2 缺失值补齐策略

对每只股票：

1. 先做 `byDate = bars.associateBy { it.date }`
2. 遍历 `allDates`
3. 如果当天有 bar，用当天 bar
4. 如果当天没有 bar：
   - 若已经出现过上一条有效 bar，则用上一条 bar 做前向填充 `ffill`
   - 如果连上一条都没有，则一开始把 `lastValidBar = bars.firstOrNull()`，相当于用第一条 bar 做向后补齐 `bfill`
5. 补齐时会把 bar 的 `date` 改成当前缺失日期

这意味着当前算法的真实对齐规则是：

- 日期主轴取并集
- 缺失数据先前向填充
- 序列起始缺失则用第一条数据反向填充

这个设计会影响：

- 共同日期长度
- bullRatio 序列平滑性
- FFT / Residual / Z-Score 的统计窗口

### 3.3 历史不足判断

对齐完成后，计算器取：

- `commonDates = alignedBarsBySymbol.values.firstOrNull()?.map { it.date }`

然后检查：

- `commonDates.size < requiredHistory`

如果不足，则直接返回一个失败型 `MarketSentimentSnapshot`：

- `sufficientHistory = false`
- 所有核心数值字段都为 `0.0`
- `reason = "共同交易日不足: x < requiredHistory"`

这里要注意：

因为对齐主轴是“并集 + 填充”，所以 `commonDates.size` 不等于“所有股票真实共同交易日交集长度”，而是“并集主轴长度”。

---

## 4. 日频情绪的基础观测量：bullRatio 与 marketVol

在历史足够的情况下，计算器会逐日扫描全部样本股时间序列。

### 4.1 EMA 趋势状态

对每只股票，维护一个 `SymbolSignalState`：

- `emaShort`
- `emaLong`

参数固定：

- `SENT_EMA_S = 10`
- `SENT_EMA_L = 30`

EMA 更新公式：

```text
multiplier = 2 / (period + 1)
ema = previous == null ? value : (value - previous) * multiplier + previous
```

每个交易日对每只股票：

1. 取当日 `close`
2. 更新 `emaShort`
3. 更新 `emaLong`
4. 如果二者都已存在且 `emaShort > emaLong`，则记为 bullish

### 4.2 bullRatio

对同一个交易日：

- `bullishCount = 当天 emaShort > emaLong 的样本股数量`
- `bullRatio = bullishCount / 样本股总数`

所以 `bullRatio` 的真实语义是：

**样本股中 EMA10 上穿 EMA30 的比例**

它不是上涨家数比，也不是收益率比。

### 4.3 marketVol

日频版的 `marketVol` 不是直接用 ATR。

它的计算方式是：

1. 对每只股票维护一个 `RollingVarianceWindow(VOL_WINDOW)`
   - `VOL_WINDOW = 20`
2. 从第 2 个交易日开始，计算该股票日收益率：

```text
ret = (close_t - close_t-1) / close_t-1
```

3. 把 `ret` 加入该股票自己的 20 日滚动窗口
4. 计算这个滚动窗口的标准差 `std`
5. 若 `std > 0`，计入当天全市场波动率均值

最后：

```text
marketVol = 所有有效样本股收益率标准差的平均值
```

所以日频版 `marketVol` 的语义是：

**样本股层面的 20 日收益率滚动波动率的截面平均**

它不是指数波动率，也不是 ATR 平均值。

---

## 5. 日频情绪的五个核心维度

日频最终融合五个维度：

1. `fftScore`
2. `residualScore`
3. `ratioNorm`
4. `volScore`
5. `accelScore`

下面分别说明。

### 5.1 ratioNorm

首先对 `bullRatio` 做线性归一化：

常量：

- `RATIO_LOW = 0.206`
- `RATIO_HIGH = 0.536`

公式：

```text
ratioNorm = ((bullRatio - RATIO_LOW) / (RATIO_HIGH - RATIO_LOW)).coerceIn(0, 1)
```

也就是说：

- `bullRatio <= 0.206` 时，`ratioNorm = 0`
- `bullRatio >= 0.536` 时，`ratioNorm = 1`
- 中间线性映射

### 5.2 fftScore

窗口：

- `FFT_WINDOW = 19`

输入：

- 最近 19 个 `bullRatio`

步骤：

1. 如果标准差极小 `< 1e-6`，直接返回 `0.5`
2. 对序列去均值中心化
3. 对频率 `k = 1 .. floor(n/2)` 逐项做离散傅里叶展开，计算每个频率的 `re/im`
4. 取幅值最大的前 2 个频率
5. 计算这两个频率的相位，并按幅值占比加权
6. 最终对得分施加 `sigmoid(3 * score)`

所以 `fftScore` 本质上衡量的是：

**bullRatio 序列在短窗口内的主导周期相位特征**

并且其输出范围被压缩到 `(0, 1)`。

### 5.3 residualScore

窗口：

- `RES_WINDOW = 228`

输入：

- 最近 228 个 `bullRatio`

步骤：

1. 如果标准差极小 `< 1e-6`，返回 `0.5`
2. 构造一组 ridge regression 特征列：
   - 常数项 1
   - `k = 1..3` 的 `sin(freq * t)`
   - `k = 1..3` 的 `cos(freq * t)`
3. 用 `alpha = 0.1` 做 ridge 回归
4. 计算最后一个点的残差：

```text
residual = actual_last - fitted_last
```

5. 再计算残差序列的标准差 `std`
6. 返回：

```text
residualScore = sigmoid(-4 * residual / std)
```

所以 `residualScore` 的真实语义是：

**当前 bullRatio 相对于周期性拟合轨迹的偏离方向与幅度**

### 5.4 volScore

先计算：

- `volZ = zScoreSeries(marketVolSeries, Z_WINDOW=252, minPeriods=60).last()`

也就是说：

- 用最近最多 252 个 `marketVol`
- 但至少要有 60 个样本，否则当期 `volZ = 0`

然后再做：

```text
volScore = sigmoid(-volZ)
```

这意味着：

- 波动越高，`volZ` 越大，`volScore` 越低
- 波动越低，`volScore` 越高

### 5.5 accelScore

先对 `bullRatioSeries` 求差分：

```text
diff[0] = 0
diff[t] = bullRatio[t] - bullRatio[t-1]
```

再对差分序列做 EMA：

- `ACCEL_EMA_SPAN = 10`

得到 `accelSeries`，然后同样做：

- `accelZ = zScoreSeries(accelSeries, Z_WINDOW=252, minPeriods=60).last()`

最后：

```text
accelScore = sigmoid(2 * accelZ)
```

所以 `accelScore` 反映的是：

**bullRatio 变化速度的标准化强弱**

---

## 6. 五维融合与动态权重

日频融合权重固定为：

- `W_FFT = 0.066`
- `W_RES = 0.241`
- `W_RATIO = 0.250`
- `W_VOL = 0.232`
- `W_ACCEL = 0.211`

但 `W_RATIO` 和 `W_VOL` 不是始终固定，存在动态调节。

### 6.1 动态权重调整

阈值：

- `VOL_DYN_THRESH = 2.0`
- `DEFAULT_VOL_DYN_BOOST = 0.40`

计算：

```text
excess = max(0, volZ - VOL_DYN_THRESH)
wVolBoost = min(volDynBoost * excess, W_RATIO * 0.8)
wVol = W_VOL + wVolBoost
wRatio = max(W_RATIO - wVolBoost, W_RATIO * 0.2)
```

解释：

- 当 `volZ <= 2.0` 时，不触发动态偏移
- 当波动率异常升高时：
  - 提高 `vol` 维度权重
  - 降低 `ratio` 维度权重
- 但 `ratio` 不会低于原始 `W_RATIO` 的 20%

### 6.2 权重归一化

不论权重是否被动态调整，最终都会归一化：

```text
nX = wX / (wFft + wRes + wRatio + wVol + wAccel)
```

### 6.3 三种融合分支

日频并不是始终使用完整五维。

真实存在 3 个分支。

#### 分支 A：`fft` 和 `residual` 都有效

条件：

- `i + 1 >= 19`
- `i + 1 >= 228`

公式：

```text
combined =
  nFft   * fftScore +
  nRes   * residualScore +
  nRatio * ratioNorm +
  nVol   * volScore +
  nAccel * accelScore
```

#### 分支 B：只有 `fft` 有效，`residual` 还无效

条件：

- `i + 1 >= 19`
- `i + 1 < 228`

做法：

- 直接把 `residual` 权重设为 `0`
- 其余四维重新归一化

这是代码里的 `only_fft` 分支。

#### 分支 C：二者都无效

条件：

- `i + 1 < 19`

做法：

- 直接退化为：

```text
combined = ratioNorm
```

也就是说，在情绪历史很短时，整个情绪算法退化成：

**EMA 多头比例的线性归一化**

---

## 7. 日频保护机制

五维融合之后，不会直接成为最终仓位，还要再经过保护机制。

### 7.1 绝对水位保护

阈值：

- `ABSOLUTE_FLOOR = 0.256`

如果：

```text
bullRatio < 0.256
```

那么当前 `combined` 会直接乘以 `0`。

所以这个保护是硬门槛：

- `bullRatio` 低于阈值时，最终基础暴露清零

### 7.2 过热保护

默认参数：

- `DEFAULT_SENT_OVERHEAT_THRESH = 0.0`
- `DEFAULT_SENT_OVERHEAT_DAYS = 10`
- `DEFAULT_SENT_OVERHEAT_DECAY = 1.0`

只有当：

- `sentOverheatThresh > 0`
- `sentOverheatDays > 0`
- `sentOverheatDecay < 1`

时，才会启用。

当前默认值下：

- **过热保护实际上默认关闭**

如果打开，它会：

1. 对最近 `days` 天中 `combined > threshold` 的比例计数
2. 计算 `ratio = count / windowLength`
3. 把当前值乘上：

```text
decay ^ ratio
```

### 7.3 波动率上限保护

默认参数：

- `DEFAULT_VOL_CAP_THRESH = 2.0`
- `DEFAULT_VOL_CAP_MAX = 0.6`

做法：

```text
excess = max(0, volZ - volCapThresh)
cap = volCapMax + (1 - volCapMax) / (1 + 2 * excess)
combined = min(combined, cap)
```

这意味着：

- 当 `volZ <= 2.0`，cap = 1.0
- 当 `volZ > 2.0`，cap 会从 1.0 向 0.6 方向压缩
- 波动越大，可允许的最大仓位越低

### 7.4 不做整体 shift(1)

代码里有明确注释：

> Kotlin 快照计算就是基于 T 日数据生成 T 日状态（用于指导 T+1）

所以：

- 日频情绪快照 `tradeDate = T`
- 使用的是 T 日收盘后可见的全部数据
- 这个结果用于指导 T+1

这里**不会**把整个序列再整体向后错一位。

---

## 8. 日频最终输出字段

最终 `MarketSentimentSnapshot` 字段含义如下。

### 8.1 直接计算得到的字段

- `tradeDate`
- `signalBasis`
- `sampleSize`
- `bullRatio`
- `fftScore`
- `residualScore`
- `marketVol`
- `volZ`
- `accelZ`
- `sentimentExposure`
- `ratioNorm`
- `volScore`
- `accelScore`
- `absoluteFloor`
- `volCap`
- `sufficientHistory`
- `requiredHistory`
- `reason`

### 8.2 其中几个字段的真实定义

- `sentimentExposure`
  最终情绪仓位，已经过全部融合和保护
- `absoluteFloor`
  不是阈值本身，而是一个状态位：
  - `bullRatio >= 0.256` 时为 `1.0`
  - 否则为 `0.0`
- `volCap`
  不是是否触发，而是当前波动率约束下的上限值

---

## 9. 日频持久化与读回的真实语义

日频情绪快照由：

- [DailyMarketSentimentRepository.kt](/Users/zhouzheng/Code/quant/database/src/main/kotlin/org/shiroumi/database/strategy/daily/repository/DailyMarketSentimentRepository.kt)

持久化到：

- [DailyMarketSentimentTable.kt](/Users/zhouzheng/Code/quant/database/src/main/kotlin/org/shiroumi/database/strategy/daily/table/DailyMarketSentimentTable.kt)

### 9.1 真正落库的字段

表里实际保存了：

- `tradeDate`
- `signalBasis`
- `sampleSize`
- `bullRatio`
- `fftScore`
- `residualScore`
- `marketVol`
- `volZ`
- `accelZ`
- `sentimentExposure`
- `sufficientHistory`
- `requiredHistory`
- `reason`

### 9.2 没有落库的字段

注意，以下字段**没有落库**：

- `ratioNorm`
- `volScore`
- `accelScore`
- `absoluteFloor`
- `volCap`

### 9.3 读回时的重构并不完全等价

`DailyMarketSentimentRepository.toSnapshot(...)` 在从数据库读回历史情绪时，会这样填充：

- `ratioNorm = 0.5`
- `volScore = 1.0`
- `accelScore = 0.5`
- `absoluteFloor = if (bullRatio >= 0.256) 1.0 else 0.0`
- `volCap = 1.0`
- `reason = null`

这说明一个非常重要的事实：

**从数据库读回的 `MarketSentimentSnapshot` 不是盘后原始快照的完全重建版本。**

也就是说：

- 数据库存的是“核心情绪结果”
- 不是“完整中间态快照”

这会直接影响后续新架构设计：

- 如果 strategy-service 发布的 `INTRADAY` snapshot 中的 H 轨依赖数据库读取，那么它拿到的是"部分字段被降级后的快照"
- 若以后要求"逐字段完全一致"，则需要重新考虑持久化字段范围

---

## 10. 盘中实时情绪增量算法

盘中情绪不是重新跑一次日频全量算法，而是调用：

- [IntradaySentimentCalculator.kt](/Users/zhouzheng/Code/quant/database/src/main/kotlin/org/shiroumi/database/strategy/intraday/IntradaySentimentCalculator.kt)

它的设计目标写得很明确：

1. 500 只样本股循环是必要开销
2. 其他部分尽量做增量
3. 复用 T-1 盘后情绪作为基准

### 10.1 盘中增量的输入

函数签名：

```kotlin
calculateIncremental(
    tradeDate,
    baseSentiment,
    intradayFactors,
    sampleCodes,
    bullRatioHistorySeed,
    marketVolHistorySeed
)
```

各参数真实含义：

- `tradeDate`
  当前交易日
- `baseSentiment`
  作为盘中计算基准的日频情绪快照
- `intradayFactors`
  当前时刻盘中股票因子快照列表
- `sampleCodes`
  情绪样本股列表
- `bullRatioHistorySeed`
  历史 bullRatio 序列种子
- `marketVolHistorySeed`
  历史 marketVol 序列种子

### 10.2 样本股筛选

先把 `intradayFactors` 过滤为：

- `tsCode in sampleCodes`

然后按 `sampleCodes` 原顺序取出对应样本。

所以盘中情绪严格依赖：

- 样本股列表本身
- 样本股顺序

### 10.3 样本股为空的回退

如果盘中样本股因子为空，直接返回：

```text
baseSentiment.copy(
  tradeDate = 当前交易日,
  reason = "样本股因子为空"
)
```

也就是说：

- 不抛错
- 不清空
- 直接沿用基准情绪

### 10.4 盘中 bullRatio 的定义

盘中不是重新跑日线 EMA，而是直接读取盘中因子里的：

- `StockFactorSnapshot.emaBull`

然后：

```text
bullRatio = emaBull == true 的样本股数 / 实际样本股数
```

所以盘中 `bullRatio` 的上游依赖是：

- 盘中因子计算器对 `emaBull` 的实时判断

### 10.5 盘中历史种子恢复

盘中会构造两个滑动窗口：

- `bullRatioHistory`
- `marketVolHistory`

如果调用方传入 `historySeed`，则直接使用 `historySeed`。

否则会从 `DailyMarketSentimentRepository.findRecentUpToDate(...)` 读取最近历史。

#### bullRatioHistory 的容量

```text
max(RES_WINDOW, Z_WINDOW) = max(228, 252) = 252
```

#### marketVolHistory 的容量

```text
Z_WINDOW = 252
```

#### 历史恢复失败时的回退

如果数据库读取失败或为空：

```text
listOf(baseSentiment)
```

也就是说盘中增量允许在极端情况下只拿基准点作为历史起点。

### 10.6 盘中 marketVol 的定义

盘中版 `marketVol` 和日频版不同。

它不是基于日收益率滚动标准差，而是：

对每个样本股：

```text
vol = atr14 / close
```

然后对所有有效样本股取平均：

```text
marketVol = avg(atr14 / close)
```

所以盘中 `marketVol` 的上游依赖是：

- `StockFactorSnapshot.atr14`
- `StockFactorSnapshot.close`

这和日频版的“收益率标准差平均”并不相同。

这是当前代码中的真实实现差异，不能忽略。

### 10.7 盘中五维融合

盘中版继续复用与日频一致的核心结构：

- `fftScore`
- `residualScore`
- `ratioNorm`
- `volScore`
- `accelScore`

并且：

- `FFT_WINDOW = 19`
- `RES_WINDOW = 228`
- `Z_WINDOW = 252`
- `ACCEL_EMA_SPAN = 10`

都与日频保持一致。

盘中的三类融合分支也和日频一致：

1. `fft + residual` 都有效
2. 只有 `fft` 有效
3. 都无效时退化为 `ratioNorm`

### 10.8 盘中保护机制

盘中版保护机制没有完整复刻日频全部保护，只保留了两类：

1. 绝对水位保护
2. 波动率上限保护

实现函数：

- `applyGuards(...)`

其中：

- 若 `bullRatio < 0.256`，直接空仓
- 若 `volZ > 2.0`，根据 cap 公式压低最大仓位

盘中版没有出现日频的 `applyOverheatGuardInPlace(...)` 调用。

所以当前真实行为是：

- 盘中增量没有启用过热衰减

### 10.9 盘中输出字段

盘中输出仍然是 `MarketSentimentSnapshot`，但有几个来源差异必须注意：

- `signalBasis = baseSentiment.signalBasis`
- `sufficientHistory = baseSentiment.sufficientHistory`
- `requiredHistory = baseSentiment.requiredHistory`
- `absoluteFloor = if (触发绝对水位保护) 0.0 else 1.0`
- `reason = null`

这里的 `absoluteFloor` 含义和日频最终输出略有反向状态感：

- 盘中实现返回的是“是否未触发空仓”的标记
- 日频里则是“bullRatio 是否高于阈值”的数值表达

数值上虽然一致，但语义上需要谨慎处理。

---

## 11. 情绪指标与盘中因子的依赖关系

盘中情绪并不是直接读行情。

它的直接输入是：

- `List<StockFactorSnapshot>`

而 `StockFactorSnapshot` 是由：

- [StockFactorCalculator.kt](/Users/zhouzheng/Code/quant/database/src/main/kotlin/org/shiroumi/database/strategy/daily/StockFactorCalculator.kt)
  或对应盘中因子计算逻辑

生成的。

对盘中情绪最关键的几个字段：

- `emaBull`
  决定 bullRatio
- `atr14`
  决定盘中 `marketVol`
- `close`
  作为 `atr14 / close` 的分母

所以从依赖图上看：

```text
行情/历史窗口
  -> 因子快照
  -> 盘中情绪
```

而不是：

```text
行情
  -> 盘中情绪
```

---

## 12. 情绪快照被哪些后续模块消费

情绪快照不是孤立指标，它直接影响：

1. `TargetPortfolioGenerator`
   - `sentimentExposure` 决定组合总仓位
   - 还会影响因子动态权重
2. `StrategyAuditGenerator`
   - 会把 `bullRatio / fftScore / residualScore / marketVol / volZ / accelZ / ratioNorm / volScore / accelScore / absoluteFloor / volCap`
     都写进审计摘要
3. 盘中组合生成器
   - 也会基于实时情绪决定目标仓位

这说明如果新架构要复刻情绪链路，不能只看最终 `sentimentExposure`。

至少以下字段都必须严肃对齐：

- `bullRatio`
- `fftScore`
- `residualScore`
- `marketVol`
- `volZ`
- `accelZ`
- `ratioNorm`
- `volScore`
- `accelScore`
- `absoluteFloor`
- `volCap`
- `sentimentExposure`

---

## 13. 从第一性原理提炼出的不变量

如果后续要把情绪指标迁到新架构，以下不变量不能破坏。

### 13.1 日频不变量

1. 情绪样本股最多 500 只，按交易日确定性随机抽样。
2. 输入股票必须先满足历史长度要求。
3. 序列对齐使用“日期并集 + ffill + 起始 bfill”。
4. `bullRatio` 的定义固定为“EMA10 > EMA30 比例”。
5. 日频 `marketVol` 固定为“20 日收益率滚动波动率的样本股平均”。
6. 融合维度固定为 `fft/residual/ratio/vol/accel` 五项。
7. 历史不足时必须走与当前代码一致的退化分支。
8. 绝对水位保护和波动率上限保护不能改。

### 13.2 盘中不变量

1. 盘中情绪直接依赖盘中因子，不直接依赖 remote。
2. `baseSentiment` 是盘中增量的基准状态。
3. 盘中 `bullRatio` 来自样本股的 `emaBull`。
4. 盘中 `marketVol` 来自样本股的 `atr14 / close` 平均值。
5. 历史种子窗口默认来自已落库的日频情绪序列。
6. 样本股为空时，必须直接返回 `baseSentiment.copy(...)`。
7. 盘中当前实现没有启用过热衰减。

### 13.3 持久化不变量

1. 当前数据库只持久化核心情绪结果，不持久化完整中间态。
2. 从数据库读回的快照不是盘后快照的完整等价重建。
3. 如果新架构要求“逐字段完全一致”，就不能简单把 DB 读回结果当作完整 H 快照。

---

## 14. 最终结论

当前项目里的“情绪指标”本质上是一个两阶段系统：

1. 盘后阶段：
   用 500 只样本股的日线历史，构造 `bullRatio` 序列，再通过频域、残差、波动率、加速度五维融合，得到日频 `sentimentExposure`。
2. 盘中阶段：
   以 T-1 日频情绪为基准，用盘中因子快照增量更新 `bullRatio / marketVol / volZ / accelZ` 等指标，实时得到当日情绪快照。

它的真实复杂度不在一个最终公式，而在三件事：

1. 输入样本集如何形成
2. 时间序列如何补齐和退化
3. 持久化与读回并不是完全信息对称

后续新架构如果要做到“零偏差复刻”，必须先完整接受这些实现现实，而不是只复制几个表面公式常量。
