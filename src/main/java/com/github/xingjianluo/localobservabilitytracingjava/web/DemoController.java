package com.github.xingjianluo.localobservabilitytracingjava.web;

import com.github.xingjianluo.localobservabilitytracingjava.async.AsyncTaskService;
import com.github.xingjianluo.localobservabilitytracingjava.config.ObservabilityProperties;
import com.github.xingjianluo.localobservabilitytracingjava.downstream.DownstreamClient;
import com.github.xingjianluo.localobservabilitytracingjava.exception.BusinessRejectionException;
import com.github.xingjianluo.localobservabilitytracingjava.exception.DownstreamException;
import com.github.xingjianluo.localobservabilitytracingjava.exception.RequestTimeoutException;
import com.github.xingjianluo.localobservabilitytracingjava.observability.TraceRecorder;
import com.github.xingjianluo.localobservabilitytracingjava.retry.RetryingExecutor;
import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 演示各类链路的入口，全部可本地 curl 验证。
 */
@RestController
@RequestMapping("/demo")
public class DemoController {

    private final DownstreamClient downstream;
    private final RetryingExecutor retryingExecutor;
    private final AsyncTaskService asyncTaskService;
    private final TraceRecorder recorder;
    private final ObservabilityProperties properties;

    public DemoController(DownstreamClient downstream,
                          RetryingExecutor retryingExecutor,
                          AsyncTaskService asyncTaskService,
                          TraceRecorder recorder,
                          ObservabilityProperties properties) {
        this.downstream = downstream;
        this.retryingExecutor = retryingExecutor;
        this.asyncTaskService = asyncTaskService;
        this.recorder = recorder;
        this.properties = properties;
    }

    private Map<String, Object> base(String outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", TraceContextHolder.currentTraceId());
        body.put("thread", Thread.currentThread().getName());
        body.put("outcome", outcome);
        return body;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() throws Exception {
        return recorder.recordStage("ping-work", () -> {
            Map<String, Object> body = base("OK");
            body.put("message", "pong");
            return body;
        });
    }

    @GetMapping("/slow")
    public Map<String, Object> slow(@RequestParam(defaultValue = "600") long millis) throws Exception {
        return recorder.recordStage("slow-work", () -> {
            Thread.sleep(millis);
            Map<String, Object> body = base("OK_BUT_SLOW");
            body.put("sleptMillis", millis);
            return body;
        });
    }

    @GetMapping("/downstream-fail")
    public Map<String, Object> downstreamFail() throws Exception {
        return recorder.recordStage("call-downstream",
                (java.util.concurrent.Callable<Map<String, Object>>) () -> {
                    downstream.call(true, 50);
                    return base("UNREACHABLE");
                });
    }

    @GetMapping("/timeout")
    public Map<String, Object> timeout(@RequestParam(defaultValue = "1200") long millis) throws Exception {
        return recorder.recordStage("timeout-work",
                (java.util.concurrent.Callable<Map<String, Object>>) () -> {
                    long start = System.currentTimeMillis();
                    Thread.sleep(millis);
                    long elapsed = System.currentTimeMillis() - start;
                    if (elapsed >= properties.getTimeoutMillis()) {
                        throw new RequestTimeoutException(
                                "processing exceeded timeout threshold: " + elapsed + "ms");
                    }
                    return base("OK");
                });
    }

    @GetMapping("/business-reject")
    public Map<String, Object> businessReject() throws Exception {
        return recorder.recordStage("business-check",
                (java.util.concurrent.Callable<Map<String, Object>>) () -> {
                    throw new BusinessRejectionException("rejected by business rule");
                });
    }

    @GetMapping("/system-error")
    public Map<String, Object> systemError() throws Exception {
        return recorder.recordStage("failing-work",
                (java.util.concurrent.Callable<Map<String, Object>>) () -> {
                    throw new IllegalStateException("unexpected system failure");
                });
    }

    @GetMapping("/async")
    public Map<String, Object> async(@RequestParam(defaultValue = "100") long millis) throws Exception {
        String httpTraceId = TraceContextHolder.currentTraceId();
        // traceId 由线程池装饰器带到工作线程，异步任务回带它的 traceId 供比对。
        CompletableFuture<String> future = asyncTaskService.runAsync(millis);
        String asyncTraceId = future.get();
        Map<String, Object> body = base("ASYNC_OK");
        body.put("httpTraceId", httpTraceId);
        body.put("asyncTraceId", asyncTraceId);
        body.put("sameTraceId", httpTraceId.equals(asyncTraceId));
        return body;
    }

    @GetMapping("/async-fail")
    public Map<String, Object> asyncFail() throws Exception {
        String httpTraceId = TraceContextHolder.currentTraceId();
        CompletableFuture<String> future = asyncTaskService.runAsyncFailing();
        boolean asyncFailed;
        String asyncTraceId;
        try {
            asyncTraceId = future.get();
            asyncFailed = false;
        } catch (java.util.concurrent.ExecutionException e) {
            // 异步任务自身失败被它自己的 start/end 独立记录；HTTP 主请求仍正常结束。
            asyncTraceId = httpTraceId;
            asyncFailed = true;
        }
        Map<String, Object> body = base("HTTP_OK_ASYNC_FAILED");
        body.put("httpTraceId", httpTraceId);
        body.put("asyncTraceId", asyncTraceId);
        body.put("sameTraceId", httpTraceId.equals(asyncTraceId));
        body.put("asyncFailed", asyncFailed);
        return body;
    }

    @GetMapping("/retry")
    public Map<String, Object> retry(@RequestParam(defaultValue = "3") int attempts) throws Exception {
        String httpTraceId = TraceContextHolder.currentTraceId();
        int[] trial = {0};
        String result = retryingExecutor.execute(() -> {
            trial[0]++;
            return recorder.recordStage("flaky-business",
                    (java.util.concurrent.Callable<String>) () -> {
                        if (trial[0] < attempts) {
                            throw new DownstreamException("flaky failure on trial " + trial[0]);
                        }
                        return "success-on-trial-" + trial[0];
                    });
        }, attempts, 10);
        Map<String, Object> body = base("RETRY_OK");
        body.put("httpTraceId", httpTraceId);
        body.put("attempts", trial[0]);
        body.put("result", result);
        return body;
    }
}
