# local-observability-tracing-java

一个**不依赖任何外部服务**、本地即可跑通的可观测性链路样例（Spring Boot 3.4 / Java 21）。
一次请求从进入到返回的全过程都能被：

- **追踪（trace）**：全链路唯一一个关联标识 `traceId`，贯穿同步、异步、后台调度、重试；
- **统计（metrics）**：本地实时查看调用量、成功/失败、失败分类、慢请求数和耗时分布；
- **排查（troubleshoot）**：按 `traceId` 串起关键环节（span）、各环节耗时与最终判定，并可导出本地 JSON。

## 1. 快速开始

```bash
mvn spring-boot:run          # 随机端口见日志，也可改 application.properties 的 server.port
mvn test                     # 运行全部 16 个测试用例
```

## 2. 关联标识（traceId）如何传播

- **载体与命名**
  - HTTP 请求头 / 响应头：`X-Trace-Id`；
  - 日志 MDC：`traceId`（日志格式见 `logback-spring.xml`，每条日志都带标识）。
- **生成与沿用规则**（`trace/TraceContextHolder`）
  1. 入口（HTTP 过滤器、调度任务、异步任务、重试）统一调用 `getOrCreate(incomingTraceId)`；
  2. 上游带了 `X-Trace-Id` 就**原样沿用**，没带才生成 32 位十六进制 ID；
  3. 当前线程已有上下文时**重入不换号**——重试、嵌套调用都沿用同一个标识，绝不凭空多一个；
  4. 对外响应头 `X-Trace-Id` 一定回带，响应体里也有 `traceId`，方便拿回来复现。
- **跨线程传播**
  - `@Async` 线程池（`async/TraceAwareExecutorConfig`）用 `TraceContextHolder.wrap` 装饰任务：
    在提交线程捕获上下文、在工作线程恢复，任务结束恢复现场，线程池复用**不串号、不残留**；
  - 后台调度（`scheduled/ScheduledJobs`）每个调度周期生成/沿用自己的标识，周期结束清理线程；
  - 重试（`retry/RetryingExecutor`）在调用方线程内执行，天然同一 `traceId`，
    每次尝试记录为独立环节 `retry-attempt-1..n`。
- **一个 traceId 多条入口记录**：HTTP 请求与其触发的异步任务共用同一标识，
  存储中按 `traceId` 分组，查询时一起返回，方便把"主请求 + 异步执行"串起来看。

## 3. 失败怎么分类

失败类型互斥，每请求最多命中一类，沿异常因果链按固定优先级判定
（`observability/FailureClassifier`）：

| 类型 | 触发条件 | HTTP 状态码 |
| --- | --- | --- |
| `TIMEOUT` | `RequestTimeoutException`（处理超过超时阈值） | 504 |
| `DOWNSTREAM_FAILURE` | `DownstreamException`（下游依赖失败） | 502 |
| `BUSINESS_REJECTION` | `BusinessRejectionException`（业务规则拒绝） | 422 |
| `SYSTEM_ERROR` | 其他未归类异常 | 500 |
| `NONE` | 成功 | 2xx |

慢请求（默认 ≥ 500ms）是独立标记 `slow=true`：**慢但成功不算失败**，只计入慢请求数。

**同一次故障只算一次**：所有入口都汇聚到唯一结束点 `DefaultTraceRecorder#end`；
`RequestRecord.tryMarkCounted()` 用 CAS 保证并发结束、重试等场景下指标只累计一次。
指标计数器全部使用 `LongAdder`，高并发下不丢计数、不重复计数。

## 4. 指标与本地数据怎么查看

### 4.1 演示入口（`/demo`）

```bash
curl -s localhost:8080/demo/ping                      # 正常
curl -s localhost:8080/demo/slow?millis=600           # 慢但成功
curl -s localhost:8080/demo/downstream-fail           # 下游失败 -> 502
curl -s localhost:8080/demo/timeout?millis=1200       # 超时     -> 504
curl -s localhost:8080/demo/business-reject           # 业务拒绝 -> 422
curl -s localhost:8080/demo/system-error              # 系统异常 -> 500
curl -s localhost:8080/demo/async                     # 异步（同 traceId）
curl -s localhost:8080/demo/async-fail                # 异步失败、主请求成功
curl -s localhost:8080/demo/retry?attempts=3          # 先败后成的重试
# 上游自带标识：
curl -s -H 'X-Trace-Id: my-corr-id' localhost:8080/demo/ping -D -
```

### 4.2 可观测端点（`/observability`）

| 方法与路径 | 作用 |
| --- | --- |
| `GET /observability/metrics` | 调用量、成功/失败数、四类失败计数、慢请求数、耗时分布直方图（毫秒桶累计计数） |
| `GET /observability/traces?limit=20` | 最近请求记录（含全部环节、耗时、判定） |
| `GET /observability/traces/failures` | 仅失败请求 |
| `GET /observability/traces/{traceId}` | 按关联标识查询同一标识下的全部入口记录（HTTP/异步/调度） |
| `POST /observability/traces/{traceId}/export` | 导出单条链路到本地 JSON 文件，返回路径 |
| `POST /observability/export` | 导出全部记录 |

导出目录默认 `./observability-data`（先写 `.tmp` 再原子 move）。
**写入失败只告警、不抛错**，观测能力永远不会反过来拖垮正常请求。

一次记录的 JSON 结构示意：

```json
{
  "traceId": "http-retry-004",
  "entry": "http:GET /demo/retry",
  "success": true,
  "failureType": "NONE",
  "slow": false,
  "durationNanos": 59000000,
  "stages": [
    { "name": "retry-attempt-1", "status": "FAILURE", "durationNanos": 1200000, "thread": "http-nio-..." },
    { "name": "retry-attempt-2", "status": "SUCCESS", "durationNanos": 800000,  "thread": "http-nio-..." }
  ]
}
```

## 5. 怎么复现验证（对照测试）

| 关注点 | 测试类 | 验证内容 |
| --- | --- | --- |
| 标识传播 | `TracePropagationTest` | 上游沿用 / 缺失生成 / 重入不换号 / 跨线程传递 / 响应头可获取 |
| 并发统计口径 | `MetricsConcurrencyTest` | 200 并发请求计数精确；四类失败互斥；同一记录被 32 线程并发结束只计一次 |
| 异步/重试 | `AsyncTraceTest` | HTTP 与异步线程 traceId 相同；异步失败独立记录不拖垮主请求；重试同标识且只算一次 |
| 失败分类与导出 | `ErrorClassificationIntegrationTest` | 504/502/422/500 与四类原因一一对应；慢请求标记；按 traceId 串查环节；JSON 落盘可读 |
| 后台调度 | `ScheduledTraceTest` | 多次调度各自独立 traceId、均成功、线程不残留 |

单测日志中会打印「输入 → 判定依据」，例如：

```
输入: GET /demo/ping 无头 -> 判定: 2xx 且响应头 traceId=ab55... body={...}
输入: 四类失败各 25 次 -> 判定: timeout=25 downstream=25 business=25 system=25, 合计=100
```

手工复现一次完整排查：

```bash
curl -s -H 'X-Trace-Id: bug-42' localhost:8080/demo/retry?attempts=2
curl -s localhost:8080/observability/traces/bug-42         # 串起两次尝试的环节和耗时
curl -s -X POST localhost:8080/observability/traces/bug-42/export
curl -s localhost:8080/observability/metrics                # 查看全局统计
```

## 6. 配置项（`application.properties`）

| 键 | 默认值 | 含义 |
| --- | --- | --- |
| `observability.timeout-millis` | 1000 | 超时判定阈值 |
| `observability.slow-request-millis` | 500 | 慢请求阈值（不影响成败） |
| `observability.export-dir` | `./observability-data` | JSON 导出目录 |
| `observability.max-records` | 10000 | 内存记录上限，超出淘汰最旧 |
| `observability.scheduling-enabled` | false | 是否启用后台调度演示 |
| `observability.schedule.fixed-delay` | 60000 | 调度间隔（毫秒） |

## 7. 代码地图

```
trace/          TraceContext、TraceContextHolder(ThreadLocal 栈 + wrap)、传播键常量
observability/  RequestRecord、TraceStage、FailureType、FailureClassifier
                MetricsRegistry(LongAdder + 耗时直方图)、LatencyHistogram
                InMemoryRecordStore、TraceRecorder/DefaultTraceRecorder
                LocalExporter/JsonFileExporter
exception/      三类业务异常：超时 / 下游 / 业务拒绝
downstream/     本地模拟的下游依赖（睡眠 + 可控制失败）
retry/          同 traceId 下的重试执行器
async/          传播 traceId 的 @Async 线程池与异步任务
scheduled/      带标识的后台调度（默认关闭）
web/            TraceFilter(唯一结束点)、GlobalExceptionHandler、Demo/观测端点
config/         ObservabilityProperties 配置绑定
```
