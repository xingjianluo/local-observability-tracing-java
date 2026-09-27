package com.github.xingjianluo.localobservabilitytracingjava.web;

import com.github.xingjianluo.localobservabilitytracingjava.service.DemoService;
import com.github.xingjianluo.localobservabilitytracingjava.service.RequestTimeoutException;
import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoints exercising sync, async, slow, retry and failing paths.
 */
@RestController
@RequestMapping("/demo")
public class DemoController {

    private static final long TIMEOUT_BUDGET_MILLIS = 100;

    private final DemoService service;
    private final Executor taskExecutor;

    public DemoController(DemoService service, Executor taskExecutor) {
        this.service = service;
        this.taskExecutor = taskExecutor;
    }

    @GetMapping("/ok")
    public Map<String, Object> ok(@RequestParam(defaultValue = "hello") String input) {
        service.validate(input);
        return body("result", service.process(input));
    }

    @GetMapping("/slow")
    public Map<String, Object> slow(@RequestParam(defaultValue = "300") long millis)
            throws InterruptedException {
        Thread.sleep(millis);
        return body("result", service.process("slow"));
    }

    @GetMapping("/async")
    public Map<String, Object> async(@RequestParam(defaultValue = "job") String input)
            throws Exception {
        String asyncResult = service.processAsync(input).get();
        return body("result", asyncResult);
    }

    @GetMapping("/retry")
    public Map<String, Object> retry(@RequestParam(defaultValue = "task") String input,
            @RequestParam(defaultValue = "2") int succeedOnAttempt) {
        return body("result", service.withRetry(input, succeedOnAttempt));
    }

    @GetMapping("/reject")
    public Map<String, Object> reject(@RequestParam(defaultValue = "") String input) {
        service.validate(input);
        return body("result", "never");
    }

    @GetMapping("/downstream")
    public Map<String, Object> downstream(@RequestParam(defaultValue = "true") boolean fail) {
        return body("result", service.callDownstream(fail));
    }

    /**
     * Deterministic timeout: the work outlives its time budget, so the
     * request fails with a timeout classification.
     */
    @GetMapping("/timeout")
    public Map<String, Object> timeout() {
        CompletableFuture<String> work = CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "never";
        }, taskExecutor);
        try {
            return body("result", work.get(TIMEOUT_BUDGET_MILLIS, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            throw new RequestTimeoutException(
                    "operation exceeded " + TIMEOUT_BUDGET_MILLIS + "ms budget");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("work failed", e);
        }
    }

    @GetMapping("/error")
    public Map<String, Object> error() {
        throw new IllegalStateException("unexpected system error");
    }

    private Map<String, Object> body(String key, Object value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", TraceContext.get());
        body.put(key, value);
        return body;
    }
}
