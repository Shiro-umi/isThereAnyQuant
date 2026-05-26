# Agent Markdown 自定义组件渲染技术方案

## 1. 背景

当前 Agent 分析结果的链路是：

1. Agent 生成 Markdown 文本。
2. 服务端在会话完成时将 `state.output` 原样写入 `agent_analysis_result.content_md`。
3. 前端分析详情页读取 `contentMd`。
4. Compose 端通过 `MarkdownText` 使用 `multiplatform-markdown-renderer-m3` 渲染。

这个链路适合渲染标准 Markdown，但不适合表达可交互业务组件，例如：

- 可缩放、可滑动、可 hover 的 K 线图。
- 风格化指标卡片。
- 风险矩阵、持仓变化流、交易计划面板。

本方案目标是在不破坏现有 Markdown 存储和展示能力的前提下，允许 Agent 在 Markdown 中插入受控的自定义组件声明，前端渲染时将声明转换为真实的 Compose 交互组件。

## 2. 目标与非目标

### 2.1 目标

- `content_md` 仍保存 Agent 输出的 Markdown 原文。
- 自定义组件以 Markdown 合法语法存在，保证旧客户端可降级显示。
- K 线图不把大体量行情数据落入 Markdown，只落查询声明。
- 渲染层复用现有 K 线数据源和 `CandleChartPanel`。
- 支持后续扩展指标卡、风险卡、交易动作按钮等组件。
- 对 Agent 输出做白名单校验，避免任意组件注入。

### 2.2 非目标

- 不从零实现完整 Markdown parser。
- 不用 Markdown 承载 K 线快照数据。
- 不在 Markdown 渲染层绕过现有 K 线 Provider / Repository 体系直接调用外部行情接口。
- 不要求聊天流式气泡立即支持重型交互组件。

## 3. 总体设计

### 3.1 核心思路

Markdown 中只放“组件声明”，渲染时再解析声明并加载业务数据。

````markdown
### 关键走势

```quant-kline
{
  "tsCode": "000001.SZ",
  "period": "DAY",
  "startDate": "2025-01-01",
  "endDate": "2025-04-24",
  "height": 360,
  "indicators": ["MA20", "EMA20", "VOLUME"]
}
```
````

渲染结果：

```text
Markdown AST
  -> 普通标题/段落/列表：继续交给现有 MarkdownText 渲染
  -> quant-kline code fence：解析 JSON，渲染 EmbeddedKLineChartBlock
```

### 3.2 为什么选择 fenced code block

| 方案 | 示例 | 结论 |
|------|------|------|
| HTML 标签 | `<quant-kline ... />` | Markdown parser 与 Compose 渲染支持不稳定，安全边界差 |
| 图片语法伪协议 | `![kline](quant://kline?... )` | 能显示占位，但参数复杂后可读性差 |
| 自定义短码 | `{{kline ...}}` | 需要自己预处理 Markdown，容易影响正常文本 |
| fenced code block | <code>```quant-kline</code> | 推荐。标准 Markdown 合法，Agent 易输出，旧客户端可降级为代码块 |

## 4. 自定义块协议

### 4.1 块格式

所有自定义组件统一采用 fenced code block：

````markdown
```quant-{component}
{
  "...": "..."
}
```
````

规则：

- language 必须以 `quant-` 开头。
- block body 必须是 JSON。
- JSON 只允许声明参数，不允许脚本、表达式或远程代码。
- 未识别的 `quant-*` 块降级为普通代码块。
- JSON 解析失败时降级为错误占位，不影响整篇报告。

### 4.2 K 线图协议

```json
{
  "tsCode": "000001.SZ",
  "period": "DAY",
  "startDate": "2025-01-01",
  "endDate": "2025-04-24",
  "height": 360,
  "indicators": ["MA20", "EMA20", "VOLUME"],
  "markers": [
    {
      "date": "2025-03-12",
      "type": "BUY",
      "label": "突破"
    }
  ]
}
```

字段说明：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `tsCode` | string | 是 | 带交易所后缀的股票代码，如 `000001.SZ`、`600519.SH` |
| `period` | enum | 是 | `DAY`、`WEEK`、`MONTH`、`MIN_60`、`MIN_30`、`MIN_15`、`MIN_5` |
| `startDate` | string | 是 | 查询起始日期，格式 `yyyy-MM-dd` |
| `endDate` | string | 是 | 查询结束日期，格式 `yyyy-MM-dd` |
| `height` | number | 否 | 图表高度，前端限制最小/最大值 |
| `indicators` | string[] | 否 | 指标白名单，例如 `MA20`、`EMA20`、`VOLUME`、`RSI`、`MACD`、`BOLL` |
| `markers` | object[] | 否 | 买卖点、风险点等标记，数量受限 |
| `maxCandles` | number | 否 | 可见 K 线数量上限；小周期买卖点图必须不超过 60 |
| `focusDate` | string | 否 | 局部窗口聚焦时间；分钟线建议写完整时间 |
| `tradePlan` | object | 否 | 入场价、止损价、目标价与盈亏比区间 |

### 4.3 指标卡协议

图片中的价格/波动率卡片可以作为另一个组件类型：

````markdown
```quant-metric-card
{
  "title": "LAST TRADED PRICE",
  "primary": "$462.41",
  "delta": "+2.4%",
  "secondaryTitle": "VOLATILITY INDEX",
  "secondary": "Stable-Medium",
  "bars": [12, 16, 24, 32, 36, 24, 40, 48]
}
```
````

这类块只适合展示报告内的摘要数据，可以直接落入 Markdown，因为数据量小且属于报告结论。

## 5. 数据边界

### 5.1 Markdown 落库内容

`content_md` 保存：

- 标准 Markdown 文本。
- 自定义组件声明 JSON。
- 报告生成当时的业务参数，例如 `tsCode`、`period`、`endDate`、指标选择、标记点。

`content_md` 不保存：

- 完整 K 线数组。
- 外部接口响应原文。
- 可执行脚本。
- 客户端状态，例如 hover index、缩放比例。

### 5.2 时间语义

Agent 输出的报告是历史产物，因此自定义 K 线块必须写明确日期。

推荐规则：

- `endDate` 优先使用报告关联的 `tradeDate`。
- 如果报告需要表达盘后实时状态，必须显式写入对应交易日，而不是让前端默认取今天。
- 前端渲染时不自动把缺失的 `endDate` 补成当前日期；缺失则显示参数错误。

这样可以避免历史报告在未来打开时变成新的行情视角。

### 5.3 K 线数据来源

前端自定义块渲染不直接访问外部行情接口。

数据流：

```text
Agent Markdown quant-kline 声明
  -> 前端解析 KLineBlockSpec
  -> CandleRepository / WebSocket 订阅能力
  -> 后端 Provider / Snapshot / 数据库
  -> CandleChartData
  -> CandleChartPanel
```

DAY 与小周期仍遵守现有 K 线架构边界：

- DAY 是特殊轨道，不能简单混入分钟周期刷新逻辑。
- Snapshot 是独立可读缓存中心，不直接承担前端订阅源角色。
- Provider / Repository 负责订阅、mapper 与 merge projection。
- 前端渲染块只消费投影结果，不重新拼接历史窗口和实时窗口。

## 6. 前端渲染架构

### 6.1 新增组件层级

建议新增：

```text
AgentReportMarkdownText
  -> StyledMarkdownRenderer
      -> MarkdownText 基础能力
      -> QuantMarkdownBlockRenderer
          -> EmbeddedKLineChartBlock
          -> EmbeddedMetricCardBlock
          -> UnknownQuantBlockFallback
```

职责：

| 组件 | 职责 |
|------|------|
| `AgentReportMarkdownText` | 报告详情页入口，替代普通 `MarkdownText` |
| `QuantMarkdownBlockParser` | 从 code fence 中识别 `quant-*` 并解析 JSON |
| `QuantMarkdownBlockRenderer` | 根据 block type 分发到业务组件 |
| `EmbeddedKLineChartBlock` | 拉取 K 线数据并渲染 `CandleChartPanel` |
| `UnknownQuantBlockFallback` | 识别失败、参数错误、数据加载失败时的降级 UI |

### 6.2 与现有 Markdown 库集成

当前 `MarkdownText` 已使用：

- `markdownTypography`
- `markdownColor`
- `markdownPadding`
- `markdownComponents`
- 自定义 `table` 组件

新组件可以继续使用同一个库，只覆盖 `codeFence`：

```kotlin
val components = markdownComponents(
    codeFence = { model ->
        val block = QuantMarkdownBlockParser.parseCodeFence(model.content, model.node)
        if (block != null) {
            QuantMarkdownBlockRenderer(block)
        } else {
            MarkdownCodeFence(model.content, model.node, style = typography.code)
        }
    },
    table = existingTableRenderer
)
```

### 6.3 渲染状态

`EmbeddedKLineChartBlock` 至少包含四种状态：

| 状态 | UI |
|------|----|
| 参数错误 | 小型错误块，展示错误原因 |
| 加载中 | 固定高度骨架屏，避免页面跳动 |
| 加载成功 | `CandleChartPanel` |
| 加载失败 | 错误块 + 重试按钮 |

图表容器高度必须稳定：

- `height` 默认 360dp。
- 最小 240dp。
- 最大 560dp。
- 加载、失败、成功三种状态使用同一外层高度，避免 Markdown 内容重排。

## 7. 服务端与落库策略

### 7.1 最小实现

服务端不需要改表结构。

现有字段已经足够：

- `content_md`：保存 Markdown 原文和自定义块声明。
- `metadata_json`：暂不必使用。
- `trade_date`：作为报告日期语义来源。

### 7.2 推荐增强

后续可以在持久化前做一次轻量解析：

```text
state.output
  -> scan quant-* fenced blocks
  -> validate schema
  -> extract metadata
  -> save content_md
  -> save metadata_json
```

`metadata_json` 可保存：

```json
{
  "embeddedBlocks": [
    {
      "type": "quant-kline",
      "tsCode": "000001.SZ",
      "period": "MIN_60",
      "startDate": "2026-04-01",
      "endDate": "2026-04-24"
    }
  ]
}
```

用途：

- 列表页显示“包含图表”标记。
- 后台统计 Agent 输出质量。
- 服务端提前发现非法块。
- 后续做数据预热。

注意：`metadata_json` 是派生索引，不是渲染主数据。渲染仍以 `content_md` 为准。

## 8. Agent 输出规范

需要在 Agent 系统提示或分析 Skill 中加入约束：

1. 需要插入 K 线图时，只能使用 `quant-kline` fenced block。
2. `tsCode` 必须使用带交易所后缀的代码。
3. `endDate` 必须使用本次分析对应的交易日。
4. 不允许输出完整 K 线数组。
5. 同一篇报告最多输出 3 个 K 线图。
6. 指标只允许白名单值。
7. JSON 必须是标准 JSON，不能写注释。
8. 涉及买卖点的小周期分析必须按周期分别做条件触发扫描：60分钟、30分钟、15分钟，必要时补充5分钟。扫描目标不是判断当前已经有没有信号，而是以当前价格为起点，推演价格如果突破、回踩、下探到支撑或跌破关键位，会触发什么买点/卖点。
9. 每个小周期可以形成一个或多个候选交易模式；每组候选模式都要给出触发条件、买点、卖点、止损、目标位、盈亏比和对应周期自己的 `quant-kline`。
10. 最终要综合比较所有小周期候选模式，选择盈亏比最高且结构质量可靠的主交易模式；如果盈亏比最高但结构弱，要说明取舍。
11. 小周期图必须只选取局部窗口，`maxCandles` 不超过 60，并用 `focusDate` 对准预计触发点；如果触发点尚未发生，使用最接近触发区间的当前/最近 K 线。
12. 如果报告输出条件触发买点/卖点、止损位、目标位或盈亏比，必须在对应周期的 `quant-kline` 中写入 `markers` 与 `tradePlan`。
13. 不要使用机械状态标签；用自然语言说明当前价格位置、各周期价格怎么变化才触发交易、哪组模式最优。

示例：

````markdown
```quant-kline
{
  "tsCode": "600519.SH",
  "period": "MIN_60",
  "startDate": "2026-04-01",
  "endDate": "2026-04-24",
  "maxCandles": 60,
  "focusDate": "2026-04-24 10:30:00",
  "height": 360,
  "indicators": ["MA20", "VOLUME"],
  "markers": [
    {"date": "2026-04-24 10:30:00", "type": "BUY", "label": "突破入场", "price": 12.35}
  ],
  "tradePlan": {
    "side": "BUY",
    "entryPrice": 12.35,
    "stopLossPrice": 12.05,
    "targetPrice": 13.10,
    "riskRewardRatio": 2.5
  }
}
```
````

## 9. 安全与校验

### 9.1 白名单

允许的 block type：

- `quant-kline`
- `quant-metric-card`

允许的 period：

- `DAY`
- `WEEK`
- `MONTH`
- `MIN_60`
- `MIN_30`
- `MIN_15`
- `MIN_5`

允许的 indicator：

- `MA20`
- `EMA20`
- `VOLUME`
- `RSI`
- `MACD`
- `BOLL`

### 9.2 限制

| 项 | 限制 |
|----|------|
| 单篇报告 K 线图数量 | 最多 3 个 |
| 单个 JSON 大小 | 建议不超过 8KB |
| 查询时间窗口 | DAY 最多 3 年，小周期最多 30 个交易日 |
| markers 数量 | 最多 20 个 |
| height | 240dp 到 560dp |

### 9.3 降级

降级原则：

- 自定义块失败不影响整篇 Markdown。
- 失败块必须占据稳定高度或明确的小型错误区域。
- 旧客户端看到的是 code fence 原文。
- 未识别的 `quant-*` 不执行、不隐藏，按代码块展示。

## 10. 实施步骤

### 阶段一：前端最小闭环

1. 新增 `QuantMarkdownBlockSpec` 数据模型。
2. 新增 `QuantMarkdownBlockParser`，识别 `quant-kline` code fence。
3. 新增 `AgentReportMarkdownText`，覆盖 `codeFence` 渲染。
4. 新增 `EmbeddedKLineChartBlock`，通过现有 `CandleRepository` 拉取 `CandleChartData`。
5. 在分析详情页使用 `AgentReportMarkdownText`。
6. 保持聊天气泡继续使用原 `MarkdownText`。

验收：

- 普通 Markdown 显示不退化。
- 合法 `quant-kline` 能渲染可交互 K 线图。
- 非法 JSON 显示错误块。
- 未识别 `quant-*` 降级代码块。

### 阶段二：Agent 输出约束

1. 更新分析类 Prompt / Skill，加入 `quant-kline` 输出规范。
2. 限制同篇报告图表数量。
3. 要求固定 `endDate`。
4. 增加示例，避免 Agent 输出伪 JSON。

验收：

- Agent 能稳定输出合法 fenced block。
- 报告落库后再次打开，图表仍按原报告日期渲染。

### 阶段三：服务端增强

1. 持久化前扫描 `quant-*` block。
2. 合法块提取到 `metadata_json`。
3. 非法块记录日志，但不阻断保存。
4. 可选：根据 metadata 做数据预热。

验收：

- 列表页可识别报告是否包含图表。
- 后台日志能定位非法 Agent 输出。
- 渲染主链路仍只依赖 `content_md`。

### 阶段四：扩展组件

可按业务优先级扩展：

- `quant-metric-card`：价格、涨跌幅、波动率、柱状迷你图。
- `quant-risk-grid`：风险因素矩阵。
- `quant-trade-plan`：买入、止损、止盈计划。
- `quant-position-flow`：持仓变化流。

扩展规则：

- 每个组件必须先定义 JSON schema。
- 每个组件必须有失败降级。
- 每个组件必须有数量和尺寸限制。

## 11. 推荐文件落点

前端：

```text
compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/ui/markdown/
  AgentReportMarkdownText.kt
  QuantMarkdownBlockParser.kt
  QuantMarkdownBlockSpec.kt
  QuantMarkdownBlockRenderer.kt

compose-app/src/commonMain/kotlin/org/shiroumi/quant_kmp/ui/markdown/components/
  EmbeddedKLineChartBlock.kt
  EmbeddedMetricCardBlock.kt
```

服务端可选：

```text
ktor-server/src/main/kotlin/org/shiroumi/server/agent/markdown/
  QuantMarkdownBlockScanner.kt
  QuantMarkdownBlockValidator.kt
```

共享模型可选：

```text
shared/src/commonMain/kotlin/model/agent/markdown/
  QuantMarkdownBlockModels.kt
```

如果只在前端渲染，不需要立刻把 schema 放入 `shared`。当服务端也要解析 `metadata_json` 时，再迁入 `shared` 更合适。

## 12. 风险与取舍

| 风险 | 影响 | 处理 |
|------|------|------|
| Agent 输出格式不稳定 | 图表无法渲染 | Prompt 示例 + JSON parser 降级 + 服务端日志 |
| 报告历史语义漂移 | 旧报告展示成新行情 | 强制 `endDate`，不默认当前日期 |
| 多图表同时加载导致页面卡顿 | 详情页体验下降 | 限制数量，懒加载，可选数据预热 |
| 小周期数据与 DAY 语义混用 | 业务语义错误 | period 白名单，DAY 与分钟周期按现有链路分离 |
| 自定义块滥用 | 安全和性能风险 | block type、字段、大小、数量全部白名单 |

## 13. 结论

推荐采用“Markdown 原文 + fenced code block 自定义声明 + 前端业务组件渲染”的路线。

这条路线的优点是：

- 不破坏现有落库模型。
- 不重写 Markdown 渲染库。
- 能复用现有 K 线图和数据链路。
- 对旧客户端天然可降级。
- 后续能平滑扩展为完整的 Agent 报告组件系统。

第一阶段只需要做前端专用渲染层和 `quant-kline` 一个组件，就可以验证端到端价值。
