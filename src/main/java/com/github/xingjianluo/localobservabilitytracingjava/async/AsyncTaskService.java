package com.github.xingjianluo.localobservabilitytracingjava.async;

import com.github.xingjianluo.localobservabilitytracingjava.observability.RequestRecord;
import com.github.xingjianluo.localobservabilitytracingjava.observability.TraceRecorder;
import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * 异步任务入口。
 *
 * <p>工作线程由 {@link com.github.xingjianluo.localobservabilitytracingjava.async.TraceAwareExecutorConfig}
 * 装饰，进入时已携带提交线程的 traceId；异步任务内部 start/end 的是<b>独立入口记录</b>
 * （entry=async:*），但 traceId 与触发它的 HTTP 请求完全一致，方便把两条记录串起来排查。
 */
@Service
public class AsyncTaskService {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskService.class);

    private final TraceRecorder recorder;

    public AsyncTaskService(TraceRecorder recorder) {
        this.recorder = recorder;
    }

    @Async("traceAwareExecutor")
    public CompletableFuture<String> runAsync(long sleepMillis) {
        RequestRecord record = recorder.start("async:runAsync");
        String traceId = TraceContextHolder.currentTraceId();
        try {
            recorder.recordStage("async-work", () -> {
                log.info("async work running traceId={} thread={}", traceId, Thread.currentThread().getName());
                if (sleepMillis > 0) {
                    Thread.sleep(sleepMillis);
                }
            });
            recorder.end(null);
            return CompletableFuture.completedFuture(traceId);
        } catch (Exception e) {
            recorder.end(e);
            CompletableFuture<String> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    @Async("traceAwareExecutor")
    public CompletableFuture<String> runAsyncFailing() {
        recorder.start("async:runAsyncFailing");
        IllegalStateException error = new IllegalStateException("async task blew up");
        recorder.end(error);
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(error);
        return failed;
    }
}
