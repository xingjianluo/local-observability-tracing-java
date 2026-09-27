# 本地可观测性链路（Local Observability & Tracing）

不依赖任何外部服务，在本地就能跑通"一次请求从进入到返回"的完整追踪、统计与排查链路。

## 运行

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8080"
# 测试
mvn test
```

## 关联标识（Trace Id）怎么传播

- 每个请求由 `TraceIdFilter`（最高优先级过滤器）绑定一个关联标识，响应头 `X-Trace-Id` 与响应体 `traceId` 字段都会带回，方便复现问题。
- **上游已带标识就沿用**：请求头带 `X-Trace-Id` 时直接使用；没有才生成（UUID）。
- **重入不新增**：`TraceContext.getOrCreate()` 在同一线程内幂等，重复执行不会凭空多出新标识。
- **跨线程不丢失、不串号**：
  - 同步处理：标识保存在 `ThreadLocal`（`TraceContext`），请求结束在 `finally` 中清理。
  - 异步任务：`AsyncConfig` 的 `TaskDecorator` 在任务提交时捕获标识、在工作线程执行前绑定、执行后恢复/清理，线程池复用不会串号。
  - 后台调度：`@Scheduled` 任务每次运行用 `getOrCreate()` 自取一个标识，结束后清理。
  - 重试：同一请求内的多次重试共享同一标识（见 `/demo/retry`）。
- 错误/异步派发线程可能没有 `ThreadLocal` 绑定，过滤器同时把标识写入请求属性 `observe.traceId` 兜底。

## 失败怎么分类

`FailureCategory` 四类，由 `GlobalExceptionHandler` 把异常映射到分类并写入请求属性，过滤器据此统计：

| 分类 | 含义 | 触发示例 | HTTP 状态 |
|---|---|---|---|
| `TIMEOUT` | 超时 | `/demo/timeout`（100ms 预算，工作需 1s） | 503 |
| `DOWNSTREAM_FAILURE` | 下游依赖失败 | `/demo/downstream?fail=true` | 502 |
| `BUSINESS_REJECTED` | 业务拒绝 | `/demo/reject`（空输入） | 422 |
| `SYSTEM_ERROR` | 系统异常 | `/demo/error` | 500 |

**同一次故障只算一次**：过滤器用请求属性 `observe.metricsRecorded` 做一次性标记，异步派发、错误重派发、超时/完成回调并发触发都不会重复计数；并发下用 `LongAdder` 保证不丢计数。

## 指标与本地数据怎么查看

- `GET /observe/metrics`：按路由的调用量（total/success/failure）、平均耗时、耗时直方图（<=10/50/100/500/1000/3000ms 分桶）、按分类的失败数。
- `GET /observe/traces/{traceId}`：按关联标识查询一次请求经过的关键环节（`request-received` → 各业务阶段 → `request-completed`），含耗时与最终判定（`SUCCESS` / `FAILURE:<分类>`）。
- `GET /observe/traces`：导出全部已记录事件（本地落点，容量上限 10000 条，超出淘汰最旧）。
- 追踪写入失败只记日志、绝不抛出，不会拖垮或中断正常请求（`TraceRecorder.record` 内捕获）。

## 演示端点

| 端点 | 说明 |
|---|---|
| `/demo/ok?input=x` | 正常同步链路 |
| `/demo/slow?millis=300` | 慢请求（直方图分桶可见） |
| `/demo/async?input=x` | 异步任务（跨线程标识一致） |
| `/demo/retry?succeedOnAttempt=2` | 重试（多次尝试同一标识） |
| `/demo/reject` `/demo/downstream` `/demo/timeout` `/demo/error` | 四类失败 |

## 怎么复现验证

```bash
# 1. 正常请求，拿到关联标识
curl -s -D - localhost:8080/demo/ok?input=hi | grep -i x-trace-id

# 2. 上游带标识会被沿用
curl -s -H 'X-Trace-Id: my-id-1' localhost:8080/demo/ok | jq .traceId   # => my-id-1

# 3. 制造各类失败
curl -s localhost:8080/demo/reject; curl -s localhost:8080/demo/downstream
curl -s localhost:8080/demo/timeout; curl -s localhost:8080/demo/error

# 4. 看统计（失败分类、耗时分布）
curl -s localhost:8080/observe/metrics | jq

# 5. 按标识串起一次请求的关键环节与最终判定
curl -s localhost:8080/observe/traces/my-id-1 | jq

# 6. 自动化验证（正常/并发/异步/异常链路）
mvn test
```

测试覆盖：标识在各入口一致（上游沿用/缺省生成/异步/重试）、并发 50 请求计数不多不少、四类失败可区分且同一故障只计一次、按标识可查询事件链；单测日志会打印输入与判定依据。
