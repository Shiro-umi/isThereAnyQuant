# 数据层重构实施追踪

本文档是本次 `server.data.*` 重构的实施挂点。它不是设计文档替代品，而是把每个模块的：

- plan
- todo list
- 检查点
- 对标替代旧代码
- 模块完成后的 review

固定记录下来，避免实现一路推进后回头很难核对“到底替掉了什么、还剩什么没清”。

## 模块 1：Data API Layer

### Plan

- 收口 K 线外部数据读取入口，统一到 `server.data.api`
- 把 `db_daily / rt_k / stk_mins / rt_min_daily / weekly / monthly` 变成接口级任务执行器
- 让 snapshot 层只通过任务模型请求数据，不直接依赖旧 service / loader 分散调用

### Todo List

- [x] 定义 `ApiTask`、`TaskParams`、`ApiChannel`、`TokenBucket`
- [x] 实现 `CandleApiLayer` 与各接口 executor
- [x] 统一分钟周期口径：只保留 `MIN_5 / MIN_15 / MIN_30 / MIN_60`
- [ ] 清理旧 Candle 主链对外部接口的直连残留
- [ ] 增补更多接口级测试

### 检查点

- 新增任何 K 线外部读取都必须经过 `server.data.api`
- `rt_k` 仍保持单接口独立限频
- 不允许无界堆积等待任务

### 对标替代旧代码

- `org.shiroumi.server.service.MinuteCandleService`
- `org.shiroumi.server.dataprovider.adapter.CandleDataAdapters` 中 Candle 抓取职责
- `AuthoritativeRealtimeDailyCandleLoader` 的直接主链入口职责

### Review

- 目前新 API 层已经落下骨架，并接到了新 snapshot 层
- 旧 adapter 仍然作为底层能力被复用，但主链入口已经不再直接暴露给订阅层和 CLI
- 下一轮要继续清除旧 Candle 主链的旁路调用

## 模块 2：Data Snapshot Center

### Plan

- 建立独立于 provider 的 K 线快照中心
- 把 DAY、小周期、周/月的缓存和写回语义拆开
- 用 `SyncLooper` 驱动 DAY 的实时推进与 provider 的读取节奏

### Todo List

- [x] 定义 `PeriodCache`
- [x] 定义 `DaySnapshotManager`
- [x] 定义 `CandleSnapshotManager`
- [x] 定义 `SyncLooper`
- [ ] 完整补齐盘后刷新与更多 miss 场景测试

### 检查点

- snapshot 能独立存在，不是 provider 的字段搬家
- DAY 与小周期的刷新链互不混淆
- 小周期 miss 后由 snapshot 提交任务并在回写后出新版本

### 对标替代旧代码

- `org.shiroumi.server.runtime.market.MarketDailySnapshotService`
- `org.shiroumi.server.dataprovider.provider.CandleProvider` 中 H/R/merged 状态

### Review

- 新 snapshot 中心已经具备独立读写和按 key 发布版本的能力
- 目前仍复用旧底层抓取器，后续可以继续把历史同步与盘后刷新边界收紧

## 模块 3：Data Provider Layer

### Plan

- 只保留按 topic 的订阅管理、版本去重和 payload 推送
- 删除 Candle 的 activation / factory 参与主链

### Todo List

- [x] 实现单实例 `CandleDataProvider`
- [x] 订阅关系改为内部按 `(tsCode, period)` 组织
- [x] 去掉 provider 侧创建/warmup 语义
- [ ] 继续删除旧 Candle activation 残余状态

### 检查点

- provider 不再拥有缓存
- provider 不再直接调用外部接口
- provider 失败语义来自 snapshot 未就绪，而不是实例未激活

### 对标替代旧代码

- `CandleProviderFactory`
- `ProviderActivationService` 中 Candle 激活部分
- `CandleProvider`

### Review

- 新 provider 已经变成单 topic 实例 + key 维度订阅路由
- 旧 Candle activation 仍在非主链代码里残留，下一步继续削掉

## 模块 4：Data Subscription / Projection / CLI

### Plan

- WebSocket 与 CLI 统一走新 facade
- 保持 `CANDLE_DATA` 对外协议不变

### Todo List

- [x] 新建 `CandleDataFacade`
- [x] 新建 `server.data.subscription.CandleSubscriptionService`
- [x] CLI 改走新 facade
- [x] WebSocket Candle 订阅改走新 subscription service
- [ ] 补更多 WS / CLI 一致性测试

### 检查点

- CLI 与 WS 不再走两套日线权威源
- 不再使用 Candle activation queue
- projection 只负责裁剪和 DTO 映射

### 对标替代旧代码

- `subscription/candle/CandleSubscriptionService`
- `subscription/candle/CandleProjectionService`
- `InternalCliRoute` 中 `projectSnapshot`

### Review

- 新 facade 已成为 Candle 主链的唯一读口
- 旧 CandleProjectionService 还存在于仓库里，但主链已经切开

## 模块 5：Bootstrap / 上下游兼容 / 删除旧链

### Plan

- 把新数据层接到启动链
- 把策略持仓跟踪等 DAY 消费方切到新 facade
- 删除旧 Candle 主链无效入口

### Todo List

- [x] 新建 `server.data.bootstrap.DataLayerBootstrap`
- [x] `DataProviderBootstrap` 接入新数据层 Candle 主链
- [x] `StrategyPositionTrackingSnapshotService` 改用新 DAY facade
- [ ] 继续替换股票列表、因子/情绪读取 Candle 的旧入口
- [ ] 删除旧 Candle factory / activation / registry 接线
- [ ] 补集成测试

### 检查点

- Candle 主链只能追到 `server.data.*`
- 没有第二权威源继续服务 WS/CLI/策略跟踪
- 删除要足够彻底，避免误用

### 对标替代旧代码

- `DataProviderBootstrap` 中 Candle 装配
- `MarketDailySnapshotService` 的 Candle 主链职责
- `StrategyPositionTrackingSnapshotService` 对旧 DAY 快照的依赖

### Review

- 新主链已经进入 bootstrap、CLI、WS、策略跟踪
- 旧 Candle 代码仍有残留，当前状态是“主链已切、旧实现未完全删除”
- 下一轮清理重点是老的 Candle activation 与 provider 工厂遗留
