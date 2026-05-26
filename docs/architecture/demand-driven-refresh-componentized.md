# K线需求驱动刷新与组件化架构规划

## 1. 核心概念

本文档使用以下五个概念描述整个数据链路。理解这些概念是理解后续所有设计的前提。

| 概念 | 定义 | 类比 |
|------|------|------|
| **Provider** | 某个 `(股票, 周期)` 的常驻内存数据源 | 一本始终打开、持续更新的账簿 |
| **Snapshot** | Provider 内部的数据容器，同时承载**需求表达**职责 | 账簿的当前页，页眉写着"我需要从哪些渠道补充数据" |
| **Demand（需求）** | Snapshot 向刷新服务声明的数据缺口 | 采购清单：要什么、从哪要、多久要一次 |
| **Refresh Component（刷新组件）** | 对应单个外部接口的独立刷新器，按接口自身限频工作 | 专属采购员，只跑一个供应商，有自己的节奏 |
| **Refresh Service（刷新服务）** | 所有刷新组件的托管层，负责根据 Demand 启停组件、聚合请求、分发结果 | 采购部门经理，按采购清单调度采购员 |

> **关键设计**：Snapshot 不仅是数据的"存储"，更是数据的"需求方"。刷新服务不是全局定时轮询，而是响应 Snapshot 发出的 Demand。

---

## 2. 背景与待解问题

### 2.1 现状痛点

当前架构存在三个层面未收敛：

1. **生命周期层面**：Provider 有时按需创建、有时常驻，前端取消订阅后可能被释放，与"常驻数据源"目标矛盾。
2. **刷新层面**：小周期刷新职责分散在订阅服务、Provider runtime 和全局 tick 中，重复刷新与遗漏刷新并存。
3. **消费层面**：前端和 CLI 遇到 Provider 未就绪时的处理路径不同，同一只股票、同一周期体验不一致。

### 2.2 本次新增目标

在原有常驻化基础上，引入**需求驱动的组件化刷新架构**：

- Snapshot 显式声明数据需求（Demand），而不是被动等待外部写入。
- 每个外部接口绑定一个独立的刷新组件，各自按接口限频运行，互不干扰。
- 刷新组件根据 Demand 的有无自动启停：有 Demand 则启动，无 Demand 则停止。
- 刷新结果写回 Snapshot，Provider 的订阅者自动收到更新。

---

## 3. 架构总览

### 3.1 整体结构

```
+-------------------------------------------------------------------------+
|                              Provider (常驻)                              |
|  +-----------------------------------------------------------------+    |
|  |                         Snapshot                                |    |
|  |  +-------------+  +-------------+  +-------------------------+  |    |
|  |  |  历史窗口 H  |  |  实时窗口 R  |  |     Demand Board        |  |    |
|  |  |  (已落地)   |  |  (已落地)   |  |  需求记录板              |  |    |
|  |  +-------------+  +-------------+  | - source: 接口标识       |  |    |
|  |         |                |         | - params: 请求参数       |  |    |
|  |         +--------|---------+       | - refreshInterval: 频率   |  |    |
|  |                  |                 | - status: PENDING/ACTIVE |  |    |
|  |              merged                +-------------------------+  |    |
|  |                 |                                              |    |
|  |         订阅者收到 UPDATE                                       |    |
|  +-----------------------------------------------------------------+    |
+-------------------------------------------------------------------------+
                                    ^
                     Refresh Service | 写入刷新结果
                                    |
+-----------------------------------|-------------------------------------+
|                      Refresh Service (刷新服务层)                         |
|  +-------------------------------|----------------------------------+  |
|  |  +-------------+    +----------|----------+    +-------------+  |  |
|  |  | Component A |    |     Demand Router    |    | Component B |  |  |
|  |  | 接口A刷新器  |<---|   (需求路由与聚合)    |--->| 接口B刷新器  |  |  |
|  |  | 限频: 1s    |    |                     |    | 限频: 5s    |  |  |
|  |  +------|------+    +---------------------+    +------|------+  |  |
|  |         |                                              |         |  |
|  |         +--------------|-------------------------------+         |  |
|  |                        |                                         |  |
|  |                 外部接口调用                                     |  |
|  +------------------------------------------------------------------+  |
|                                                                         |
|  启停规则: 当某个 Component 没有任何关联 Demand 时，该 Component 停止工作   |
|            当首个 Demand 指向某 Component 时，该 Component 启动工作       |
+-------------------------------------------------------------------------+
```

### 3.2 数据闭环

```
Provider 订阅者发起请求
    |
    v
Snapshot 检查数据缺口 ---> 在 Demand Board 登记需求
    |                         |
    |                         v
    |              Refresh Service 发现新 Demand
    |                         |
    |                         v
    |              对应 Refresh Component 启动（如无活跃实例）
    |                         |
    |                         v
    |              Component 按自身限频调用外部接口
    |                         |
    |                         v
    |              接口返回数据写回 Snapshot
    |                         |
    v                         v
Snapshot merged 更新 <--- 实时窗口 / 历史窗口补充
    |
    v
Provider 推送 UPDATE 给所有订阅者
```

---

## 4. 组件详细职责

### 4.1 Provider（常驻内存数据源）

**唯一职责**：维护某个 `(tsCode, period)` 的数据权威。

- 跟随服务端生命周期启动并 warmup，此后常驻内存。
- 内部持有 Snapshot，Snapshot 对外暴露 `merged` 视图。
- 提供订阅接口，订阅者只消费 `merged`，不感知内部结构。
- **不负责**：任何刷新逻辑、任何外部接口调用、任何订阅关系管理。

**常驻范围**：

| 维度 | 范围 |
|------|------|
| 股票 | `stock_info.listStatus = "L"` 的全市场上市股票 |
| 周期 | `DAY / WEEK / MONTH / MIN_5 / MIN_15 / MIN_30 / MIN_60` |

### 4.2 Snapshot（数据容器 + 需求表达）

**职责一分为二**：

**数据侧**：
- 维护历史窗口 `H`、实时窗口 `R`、对外视图 `merged`。
- 维护版本号、更新时间、ready 状态、数据源健康状态。
- 接收外部写入（来自 Refresh Service 的刷新结果），更新内部窗口。

**需求侧（新增）**：
- 维护 **Demand Board**：当前需要从哪些外部接口补充数据。
- 每个 Demand 包含：
  - `sourceId`：接口唯一标识（如 `"rt_minute"`、`"rt_daily"`）
  - `params`：调用该接口需要的参数（如股票代码、周期、请求窗口大小）
  - `refreshInterval`：建议刷新间隔（由接口特性决定，Snapshot 可提出，Refresh Service 可调整）
  - `status`：`PENDING`（已登记但未就绪）/ `ACTIVE`（正在刷新）/ `PAUSED`（主动暂停）/ `FULFILLED`（已满足，可移除）
- Snapshot **只声明需求**，不决定如何满足、何时调用接口。

**Demand 的产生与消亡**：

- **产生**：Snapshot 初始化时发现数据缺口（如 warmup 后实时窗口为空），或运行时检测到数据过期。
- **消亡**：Demand 对应的数据缺口已被填补且持续有效，或 Snapshot 被释放（实际上 Provider 常驻，Demand 长期存在但可能进入 `PAUSED`）。
- **更新**：数据缺口参数变化时（如需要扩大窗口），Demand 参数随之更新。

### 4.3 Demand（需求记录）

Demand 是 Snapshot 与 Refresh Service 之间的契约。

```
Demand {
  id: 唯一标识
  sourceId: 接口标识
  params: Map<String, Any>   // 接口调用参数
  requestedInterval: Long    // Snapshot 期望的刷新间隔（毫秒）
  priority: Int              // 优先级，高优先级优先调度
  status: PENDING | ACTIVE | PAUSED | FULFILLED
  createdAt: Long
  lastRefreshAt: Long
  nextRefreshAt: Long
}
```

**关键设计**：

- `sourceId` 决定由哪个 Refresh Component 处理。
- 多个 Snapshot 可能对同一个 `sourceId` 提出 Demand，Refresh Service 负责聚合。
- Demand 是**声明式**的：Snapshot 说"我需要这个"，而不是"你去帮我调接口"。

### 4.4 Refresh Component（接口级刷新器）

**一个外部接口对应一个 Refresh Component**。

例如：
- `MinuteRealtimeComponent` -> 对接实时分钟接口
- `DailyRealtimeComponent` -> 对接实时日线接口
- `HistoryComponent` -> 对接历史 K 线接口

**职责**：
- 维护自身的工作状态（运行中 / 暂停）。
- 按接口自身的限频执行调用（如分钟接口每秒一次，日线接口每 5 秒一次）。
- 接收 Refresh Service 下发的聚合参数列表，批量或分片调用外部接口。
- 将返回结果按 `(tsCode, period)` 写回对应 Snapshot。
- **只处理分配给它的 Demand**，不感知其他 Component。

**独立限频**：

| Component | 典型限频 | 说明 |
|-----------|----------|------|
| 实时分钟 | 1 秒 | 盘中高频 |
| 实时日线 | 5 秒 | 日级快照 |
| 历史补全 | 按需 / 低频 | 仅在 warmup 或缺口修复时触发 |

### 4.5 Refresh Service（刷新服务托管层）

**职责**：
- **Demand 收集**：监听所有 Snapshot 的 Demand Board 变化。
- **Demand 路由**：按 `sourceId` 将 Demand 分发给对应 Refresh Component。
- **启停调度**：
  - 当某个 Component 首次收到 Demand 时，启动该 Component。
  - 当某个 Component 的所有 Demand 都被移除或进入 `PAUSED` 时，停止该 Component。
- **请求聚合**：将同一 Component 下的多个 Demand 按参数相似度聚合，减少外部接口调用次数。
- **结果分发**：将 Component 返回的批量数据按 `(tsCode, period)` 路由到对应 Snapshot。

**不负责**：
- 直接调用外部接口（交给 Component）。
- 决定 Provider 生命周期（Provider 常驻）。
- 管理 WebSocket 订阅关系。

### 4.6 CandleProjectionService（消费侧裁剪）

**职责**：
- 读取 Provider 的 `merged` 快照。
- 按请求参数（`limit`、`startDate`、`endDate`）裁剪。
- 输出统一数据模型给 WebSocket 订阅者和 CLI。
- 附带快照元信息：版本、更新时间、是否包含实时数据、数据源状态。

**窗口默认**：
- 前端图表默认 500 根
- CLI 默认 100 根
- CLI 可通过参数覆盖


---

## 5. 数据流详解

### 5.1 启动期：Warmup 与 Demand 初始化

```
Server 启动
  -> 创建全市场全周期 Provider
  -> 每个 Provider 创建 Snapshot
  -> Snapshot 初始化后检查数据缺口
       |- 历史窗口为空 -> 登记 Demand(sourceId="history")
       |- 实时窗口为空（MIN_*） -> 登记 Demand(sourceId="rt_minute")
       |- 实时窗口为空（DAY） -> 登记 Demand(sourceId="rt_daily")
  -> Refresh Service 收集所有 Demand
  -> 启动有 Demand 的 Component
  -> Component 执行首次调用，结果写回 Snapshot
  -> Snapshot 标记 ready，订阅者收到 SYNC
```

**边界情况**：
- 某个 Provider warmup 失败：该 Provider 的 Snapshot 保持 `ready = false`，Demand 继续存在，Component 继续尝试刷新。失败原因记录到 Snapshot 状态，不影响其他 Provider。
- 外部接口首次调用失败：Component 按退避策略重试，Snapshot 保持 `PENDING`，不向订阅者推送不完整数据。

### 5.2 运行期：订阅驱动的刷新

```
前端建立 WebSocket 订阅 (tsCode=A, period=MIN_5)
  -> CandleSubscriptionService 登记订阅关系
  -> 订阅者开始监听 Provider(A, MIN_5).snapshotFlow
  -> Provider(A, MIN_5) 已 ready，立即发送 SYNC
  -> Provider(A, MIN_5) 的 Snapshot 已登记 Demand(sourceId="rt_minute")
  -> Refresh Service 确保 MinuteRealtimeComponent 运行中
  -> Component 每秒聚合所有含 rt_minute Demand 的 Snapshot，批量请求
  -> 返回结果写回各 Snapshot
  -> Snapshot 版本变化，Provider 向所有订阅者发送 UPDATE
```

**关键**：前端订阅本身不触发新的 Demand（Demand 在 Provider 创建时已登记），但订阅的存在意味着 Component 不能停止。

### 5.3 运行期：CLI 一次性读取

```
CLI get-intraday-candles --code A --period MIN_5
  -> HTTP internal route
  -> 等待 Provider(A, MIN_5) ready，默认 15 秒
  -> ready 后 Projection 按 limit 裁剪
  -> 返回 Markdown
```

**CLI 不登记持续订阅**，因此 CLI 调用不影响 Component 的启停决策。Component 的启停只取决于 Snapshot Demand Board 中是否存在 ACTIVE Demand。

### 5.4 停止期：Demand 消失与 Component 暂停

```
场景：某 Snapshot 的缺口已填满，进入稳定状态
  -> Snapshot 将 Demand 标记为 FULFILLED 或 PAUSED
  -> Refresh Service 检测到该 Component 下无 ACTIVE Demand
  -> 等待一个优雅周期（如 5 秒），确认无新 Demand
  -> 暂停 Component，停止外部接口调用
  -> 后续若新 Demand 出现，Component 重新启动
```

**边界情况**：
- 同一 Component 下多个 Snapshot，只要还有一个 ACTIVE Demand，Component 就继续运行。
- Component 暂停期间收到新 Demand，应立即启动，不应丢弃 Demand。

---

## 6. 自动启停机制

### 6.1 启停决策图

```
Refresh Service 监听 Demand Board 变化
    |
    |- 新 Demand 出现，sourceId = X
    |     |- Component X 运行中 -> 将 Demand 加入 X 的任务队列
    |     |- Component X 未运行 -> 启动 Component X，加入任务队列
    |
    |- Demand 被移除或标记 PAUSED/FULFILLED，sourceId = X
          |- Component X 仍有其他 ACTIVE Demand -> 继续运行
          |- Component X 无 ACTIVE Demand
                |- 启动优雅关闭计时器（如 5s）
                |- 计时器到期前无新 Demand -> 停止 Component X
                |- 计时器到期前出现新 Demand -> 取消关闭，继续运行
```

### 6.2 启停的粒度

启停以 **Refresh Component** 为单位，而不是以 Provider 或 Snapshot 为单位。

原因：
- 一个 Component 可能服务多个 Snapshot（如全市场分钟刷新由一个 Component 处理）。
- 单个 Snapshot 可能有多个 Demand（如同时需要历史和实时数据）。
- 以 Component 为单位，避免频繁启停带来的连接开销。

### 6.3 避免抖动

**问题**：Demand 在 ACTIVE 和 PAUSED 之间快速切换，导致 Component 频繁启停。

**策略**：
- **FULFILLED Demand 保留**：即使数据缺口暂时填满，Demand 仍保留在 Board 上，状态为 `FULFILLED`。
- **状态变更有延迟**：从 `ACTIVE` 到可移除需要满足"数据持续有效超过阈值"（如 30 秒）。
- **Component 关闭有宽限期**：无 ACTIVE Demand 后，等待 5 秒再关闭，避免瞬态波动。

---

## 7. 周期分层策略

不同周期的数据特性不同，刷新策略也不同。

### 7.1 分层总览

| 周期类型 | 代表周期 | 历史来源 | 实时来源 | 刷新策略 |
|----------|----------|----------|----------|----------|
| **日线轨道** | DAY | 历史日线接口 | 独立日线实时接口 | 独立 Component，不与小周期混合 |
| **小周期轨道** | MIN_5/15/30/60 | 历史分钟接口 | 实时分钟接口（聚合） | 统一 Component 按周期分频 |
| **聚合轨道** | WEEK/MONTH | 历史聚合接口 | 无（不参与盘中刷新） | 仅 warmup 时刷新，运行时无 Demand |

### 7.2 日线轨道隔离

`DAY` 是特殊轨道：
- 使用独立的 `DailyRealtimeComponent`，限频和接口与小周期不同。
- `DAY` 的 Demand 不触发 `MinuteRealtimeComponent` 启动。
- `rt_min_daily` 归入日线轨道，不作为小周期通用接口。

### 7.3 小周期轨道聚合

`MIN_*` 共享 `MinuteRealtimeComponent`：
- Component 按周期维度聚合 Demand（所有 MIN_5 一起请求，所有 MIN_15 一起请求）。
- 每秒执行一次批量请求，返回结果按 `(tsCode, period)` 分发。
- 无活跃小周期 Demand 时，`MinuteRealtimeComponent` 停止。

### 7.4 聚合轨道静默

`WEEK/MONTH`：
- warmup 时登记 `HistoryComponent` Demand，完成后标记 `FULFILLED`。
- 运行期间无实时 Demand，不触发任何实时 Component。
- 如需盘中更新，由日线数据派生（不直接调用外部接口）。

---

## 8. 边界情况处理

### 8.1 外部接口限频冲突

**场景**：Component 的聚合请求量超过外部接口限频。

**策略**：
- Component 内部维护令牌桶或滑动窗口限流器。
- 超限请求排队，按优先级和先到先服务顺序执行。
- 排队超时的 Demand 保持 `ACTIVE` 但标记 `throttled`，下次调度优先处理。
- Snapshot 收到 throttled 信号后，不标记数据过期，避免无限重试。

### 8.2 Snapshot 写入并发

**场景**：多个 Component 同时向同一个 Snapshot 写入（如历史 Component 和实时 Component）。

**策略**：
- Snapshot 内部对窗口写入加锁（或采用 CAS 乐观锁）。
- 不同窗口（`H` 和 `R`）可独立写入，无竞争。
- `merged` 的生成是只读操作，不阻塞写入。
- 写入后原子性更新版本号，确保订阅者看到一致快照。

### 8.3 Component 故障恢复

**场景**：Component 调用外部接口持续失败。

**策略**：
- Component 内部维护连续失败计数。
- 连续失败超过阈值（如 5 次），Component 进入 `DEGRADED` 状态：
  - 降低调用频率（如从 1s 降到 5s）。
  - 向关联 Snapshot 写入 `sourceStatus = DEGRADED`。
  - 订阅者收到带有降级标记的 UPDATE，可提示用户。
- 连续成功超过阈值后，恢复正常频率和状态。
- Component 故障不影响其他 Component。

### 8.4 Demand 参数冲突

**场景**：同一个 Snapshot 对同一 `sourceId` 登记了两个参数不同的 Demand。

**策略**：
- Refresh Service 在收集 Demand 时进行去重和合并。
- 参数冲突时，取并集（如窗口大小取最大值，参数取超集）。
- 合并后的 Demand 关联到原 Snapshot，结果写回后满足所有原始需求。
- 合并逻辑记录到日志，便于排查。

### 8.5 无订阅但 Snapshot 有 Demand

**场景**：Provider warmup 完成后，没有任何前端订阅，但 Snapshot 仍有 ACTIVE Demand。

**策略**：
- **这是正常状态**。Provider 常驻，Snapshot 的 Demand 表示"我需要保持数据新鲜"，不依赖是否有订阅者。
- 若系统资源紧张，可引入**资源压力感知**：当内存/CPU 压力高时，将无订阅者的 Snapshot 的 Demand 降级为 `PAUSED`，释放 Component 压力。
- 资源压力缓解后，或新订阅者出现时，Demand 恢复 `ACTIVE`。

### 8.6 快速订阅/取消订阅

**场景**：前端快速切换股票，频繁建立和取消订阅。

**策略**：
- 订阅和取消只影响 `CandleSubscriptionService`，不影响 Provider 和 Snapshot。
- Provider 常驻，不会因取消订阅而释放。
- Snapshot 的 Demand 不会因订阅取消而移除（Demand 由数据缺口决定，不是由订阅者数量决定）。
- 因此 Component 的启停也不受前端订阅行为影响，避免前端操作冲击后端刷新节奏。

### 8.7 新股票上市

**场景**：`stock_info` 中新增上市股票，需要为其创建 Provider。

**策略**：
- Refresh Service 定期扫描 `stock_info.listStatus = "L"` 的股票列表。
- 发现新股票后，为其创建全周期 Provider 并初始化 Snapshot。
- Snapshot 自动产生 Demand，Component 正常响应。
- 退市股票的 Provider 进入 `PAUSED` 状态，Snapshot 的 Demand 移除，等待资源回收。

### 8.8 盘中断线重连

**场景**：外部接口临时不可用，随后恢复。

**策略**：
- Component 进入 `DEGRADED` 状态，降频重试。
- 恢复后，Component 恢复正常频率，Snapshot 标记数据源健康。
- 重试期间，Snapshot 保留旧数据，版本号不递增，订阅者不会收到虚假 UPDATE。
- 重试成功后，一次性写入最新数据，版本号递增，订阅者收到 UPDATE。

---

## 9. 状态机

### 9.1 Provider 状态机

```
CREATED --> WARMUP --> READY <------> DEGRADED
              |           |
              v           v
           FAILED      (常驻)
```

- `CREATED`：已创建，尚未开始 warmup。
- `WARMUP`：正在加载历史数据，Snapshot Demand Board 已初始化。
- `READY`：历史数据已加载，实时数据刷新中，可对外服务。
- `DEGRADED`：数据源异常，但持有旧快照，仍可服务（带降级标记）。
- `FAILED`：warmup 失败且无数据，不可服务。

### 9.2 Demand 状态机

```
PENDING --> ACTIVE <------> PAUSED
              |
              v
           FULFILLED --> (保留在 Board，可重新激活)
```

- `PENDING`：已登记，等待首次刷新结果。
- `ACTIVE`：正在周期性刷新。
- `PAUSED`：主动暂停（如资源压力），可恢复。
- `FULFILLED`：缺口已填满，但仍保留在 Board 上，防止 Component 抖动关闭。

### 9.3 Refresh Component 状态机

```
IDLE --> RUNNING <------> DEGRADED
           |
           v
        STOPPED (可被重新启动)
```

- `IDLE`：已创建，尚未收到 Demand。
- `RUNNING`：正在处理 ACTIVE Demand，周期性调用外部接口。
- `DEGRADED`：连续失败，降频运行。
- `STOPPED`：无 ACTIVE Demand 且宽限期已过，停止调用。

---

## 10. 验收场景

### 10.1 Provider 常驻与 Warmup

- 服务启动后，为全市场全周期创建 Provider。
- 单个 Provider warmup 失败时，其他 Provider 不受影响。
- 失败 Provider 可被观测到状态和原因，其 Demand 仍保留，Component 持续重试。

### 10.2 WebSocket 订阅

- Provider 未 ready 时，订阅保持有效，前端等待 SYNC 而非退避重试。
- Provider ready 后，前端收到 `SYNC`。
- 快照版本变化后，前端收到 `UPDATE`。
- 取消订阅后，不再收到更新，但 Provider 仍常驻，Snapshot Demand 不受影响。

### 10.3 需求驱动刷新

- Snapshot 初始化后，Demand Board 自动登记所需接口的需求。
- Refresh Service 根据 Demand 启动对应 Component。
- Component 按自身限频调用外部接口，结果写回 Snapshot。
- Snapshot 数据更新后，版本号递增，Provider 向订阅者推送 UPDATE。

### 10.4 组件自动启停

- 无任何 ACTIVE Demand 指向某 Component 时，该 Component 在宽限期后停止。
- 新 Demand 出现时，对应 Component 立即启动。
- 同一 Component 服务多个 Snapshot 时，只要还有一个 ACTIVE Demand，Component 就不停止。
- Component 启停不受前端订阅/取消订阅影响。

### 10.5 小周期聚合刷新

- 多用户订阅同周期不同股票时，Component 按周期聚合股票代码批量请求。
- 无小周期 Demand 时，`MinuteRealtimeComponent` 停止，不发起远程轮询。
- 新增首个小周期 Demand 后，Component 自动启动。

### 10.6 日线隔离

- `DAY` Demand 触发 `DailyRealtimeComponent`，不触发 `MinuteRealtimeComponent`。
- 小周期刷新不会写入或影响 `DAY` Snapshot。
- 日线仍通过统一 Projection 输出。

### 10.7 CLI

- Provider ready 时，CLI 快速返回 Markdown。
- Provider 未 ready 时，CLI 最多等待 15 秒。
- 超时错误包含股票、周期、等待秒数和 Provider 状态。
- CLI 调用不登记持续订阅，不影响任何 Component 启停。

### 10.8 窗口裁剪

- 同一 Provider 下，前端请求 500 根，CLI 默认请求 100 根。
- 两者数据尾部一致，只是窗口大小不同。
- 不由消费端自行裁剪或拼接。

### 10.9 故障降级

- Component 连续失败超过阈值后进入 `DEGRADED`，降频重试。
- 降级期间 Snapshot 保留旧数据，不向订阅者推送虚假 UPDATE。
- 恢复后自动恢复正常频率，推送最新数据。
- 一个 Component 故障不影响其他 Component。

---

## 11. 风险与假设

### 11.1 风险

| 风险 | 影响 | 缓解策略 |
|------|------|----------|
| 全市场全周期 warmup 增加启动压力 | 启动慢、远程接口被打满 | 控制并发度、单 Provider 失败隔离、支持延迟 warmup |
| 小周期历史窗口内存占用高 | OOM | Provider 最大窗口配置、资源压力下 PAUSED 无订阅者 Demand |
| Component 聚合请求超限 | 外部接口被封或丢弃 | Component 内部限流、排队、throttle 信号 |
| Demand 管理复杂度 | 状态机难以维护 | 状态转换统一收口到 Refresh Service、完善日志与观测 |
| 多 Component 并发写 Snapshot | 数据竞争 | 窗口级锁或 CAS、版本号原子更新 |

### 11.2 假设

- 全市场范围按 `stock_info.listStatus = "L"` 定义。
- `DAY` 是特殊轨道，架构统一但刷新逻辑不与其他周期混合。
- `rt_min_daily` 按当前业务口径归入日级特殊能力，不作为 `MIN_*` 通用刷新接口。
- CLI 是一次性读取，不算持续订阅，不影响 Demand 和 Component 状态。
- 本文档是后续实现依据，本次不包含代码开发。

---

## 12. 与旧架构的对比

| 维度 | 旧架构 | 新架构 |
|------|--------|--------|
| 刷新触发 | 全局 tick 或订阅事件驱动 | Snapshot Demand 驱动 |
| 接口限频 | 全局统一或分散控制 | 每个 Component 独立限频 |
| 启停粒度 | 按订阅者数量启停轮询 | 按 Demand ACTIVE 状态启停 Component |
| Snapshot 角色 | 被动数据容器 | 主动需求表达方（Demand Board） |
| 前端订阅影响 | 订阅数量决定轮询启停 | 订阅只影响数据消费，不影响刷新节奏 |
| 故障隔离 | 一个接口故障可能影响全局 | Component 级隔离，互不影响 |
| 可扩展性 | 新增接口需修改全局调度 | 新增接口只需新增 Component，Refresh Service 自动路由 |
