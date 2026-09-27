package com.github.xingjianluo.localobservabilitytracingjava.observability;

import com.github.xingjianluo.localobservabilitytracingjava.config.ObservabilityProperties;
import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceContext;
import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@link TraceRecorder} 默认实现。
 *
 * <p>关键保证：
 * <ul>
 *   <li>记录栈基于 ThreadLocal，标识来自 {@link TraceContextHolder}，
 *       异步线程通过 wrap 携带同一 traceId，调度/重试入口用 getOrCreate 沿用，不新造；</li>
 *   <li>{@link #end(Throwable)} 是唯一结束点：分类、判慢、写内存存储、累计指标，
 *       重复/并发结束靠 {@link RequestRecord#tryMarkCounted()} 幂等；</li>
 *   <li>存储/指标写入出现异常只告警，绝不向调用方抛出，避免观测代码拖垮正常请求。</li>
 * </ul>
 */
@Component
public class DefaultTraceRecorder implements TraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(DefaultTraceRecorder.class);

    private static final ThreadLocal<java.util.Deque<RecordFrame>> FRAMES =
            ThreadLocal.withInitial(java.util.ArrayDeque::new);

    private final MetricsRegistry metricsRegistry;
    private final InMemoryRecordStore store;
    private final FailureClassifier classifier;
    private final ObservabilityProperties properties;

    public DefaultTraceRecorder(MetricsRegistry metricsRegistry,
                                InMemoryRecordStore store,
                                FailureClassifier classifier,
                                ObservabilityProperties properties) {
        this.metricsRegistry = metricsRegistry;
        this.store = store;
        this.classifier = classifier;
        this.properties = properties;
    }

    /** 正在执行的请求记录 + 起始计时。 */
    private record RecordFrame(RequestRecord record, long startNanos) {
    }

    @Override
    public RequestRecord start(String entry) {
        TraceContext context = TraceContextHolder.getOrCreate(null);
        RequestRecord record = new RequestRecord(context.traceId(), entry);
        FRAMES.get().push(new RecordFrame(record, System.nanoTime()));
        log.info("trace start entry={} traceId={}", entry, record.traceId());
        return record;
    }

    @Override
    public StageHandle stageStart(String name, String detail) {
        return new StageHandle(name, detail, System.nanoTime(),
                Thread.currentThread().getName(), Instant.now());
    }

    @Override
    public void stageEnd(StageHandle handle, boolean success, String detail) {
        RequestRecord record = currentOrNull();
        if (handle == null || record == null) {
            return;
        }
        long duration = System.nanoTime() - handle.startNanos();
        TraceStage stage = new TraceStage(
                handle.name(),
                handle.startNanos(),
                duration,
                success ? TraceStageStatus.SUCCESS : TraceStageStatus.FAILURE,
                detail != null ? detail : handle.detail(),
                handle.thread(),
                handle.timestamp()
        );
        try {
            record.addStage(stage);
        } catch (Exception e) {
            log.warn("record stage failed, ignored: traceId={} stage={}",
                    record.traceId(), handle.name(), e);
        }
    }

    @Override
    public <T> T recordStage(String name, Callable<T> action) throws Exception {
        StageHandle handle = stageStart(name, null);
        try {
            T result = action.call();
            stageEnd(handle, true, null);
            return result;
        } catch (Exception e) {
            stageEnd(handle, false, e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    @Override
    public void recordStage(String name, CheckedRunnable action) throws Exception {
        recordStage(name, () -> {
            action.run();
            return null;
        });
    }

    @Override
    public RequestRecord end(Throwable error) {
        RecordFrame frame = FRAMES.get().peek();
        if (frame == null) {
            log.warn("trace end called without active record, ignored");
            return null;
        }
        RequestRecord record = frame.record();
        long durationNanos = System.nanoTime() - frame.startNanos();
        boolean success = error == null;
        FailureType type = classifier.classify(error);
        boolean slow = durationNanos >= properties.getSlowRequestMillis() * 1_000_000L;
        record.markEnd(success, type, error == null ? null : error.getMessage(),
                0, slow, durationNanos, Instant.now());

        // 观测写入与业务隔离：任何异常都不向上抛。
        try {
            store.save(record);
        } catch (Exception e) {
            log.warn("save trace record failed, ignored: traceId={}", record.traceId(), e);
        }
        try {
            metricsRegistry.recordCompleted(record);
        } catch (Exception e) {
            log.warn("record metrics failed, ignored: traceId={}", record.traceId(), e);
        }

        FRAMES.get().pop();
        if (FRAMES.get().isEmpty()) {
            FRAMES.remove();
        }
        if (success) {
            log.info("trace end success traceId={} durationMs={} slow={}",
                    record.traceId(), durationNanos / 1_000_000L, slow);
        } else {
            log.warn("trace end failure traceId={} type={} durationMs={} message={}",
                    record.traceId(), type, durationNanos / 1_000_000L, record.failureMessage());
        }
        return record;
    }

    @Override
    public RequestRecord current() {
        RecordFrame frame = FRAMES.get().peek();
        if (frame == null) {
            throw new IllegalStateException("no active trace record on current thread");
        }
        return frame.record();
    }

    @Override
    public RequestRecord currentOrNull() {
        RecordFrame frame = FRAMES.get().peek();
        return frame == null ? null : frame.record();
    }

    @Override
    public List<RequestRecord> recent(int limit) {
        return store.latest(limit);
    }
}
