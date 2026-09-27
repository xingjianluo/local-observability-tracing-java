package com.github.xingjianluo.localobservabilitytracingjava.service;

import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceContext;
import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceEvent;
import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceRecorder;
import java.util.concurrent.CompletableFuture;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Business logic used by the demo endpoints. Every key step records a
 * trace event so a request can be reconstructed by trace id.
 */
@Service
public class DemoService {

    private final TraceRecorder recorder;

    public DemoService(TraceRecorder recorder) {
        this.recorder = recorder;
    }

    /** Synchronous processing step. */
    public String process(String input) {
        long start = System.nanoTime();
        recorder.record("service-process", "START", "input=" + input);
        String result = "processed:" + input;
        recorder.record(new TraceEvent(TraceContext.get(), "service-process",
                System.currentTimeMillis(), elapsedMillis(start), "SUCCESS", result));
        return result;
    }

    /** Simulated downstream call that fails when asked to. */
    public String callDownstream(boolean fail) {
        long start = System.nanoTime();
        recorder.record("downstream-call", "START", "fail=" + fail);
        if (fail) {
            recorder.record(new TraceEvent(TraceContext.get(), "downstream-call",
                    System.currentTimeMillis(), elapsedMillis(start), "FAILURE",
                    "downstream unavailable"));
            throw new DownstreamFailureException("downstream dependency failed");
        }
        recorder.record(new TraceEvent(TraceContext.get(), "downstream-call",
                System.currentTimeMillis(), elapsedMillis(start), "SUCCESS", "ok"));
        return "downstream-ok";
    }

    /** Validates input, rejecting blank values as a business error. */
    public void validate(String input) {
        if (input == null || input.isBlank()) {
            recorder.record("validate", "FAILURE", "blank input rejected");
            throw new BusinessRejectedException("input must not be blank");
        }
        recorder.record("validate", "SUCCESS", "input accepted");
    }

    /** Async task; the executor decorator keeps the same trace id. */
    @Async
    public CompletableFuture<String> processAsync(String input) {
        long start = System.nanoTime();
        recorder.record("async-task", "START", "input=" + input);
        String result = "async:" + input;
        recorder.record(new TraceEvent(TraceContext.get(), "async-task",
                System.currentTimeMillis(), elapsedMillis(start), "SUCCESS", result));
        return CompletableFuture.completedFuture(result);
    }

    /**
     * Retry loop: every attempt keeps the same trace id; the operation
     * succeeds on the given attempt number.
     */
    public String withRetry(String input, int succeedOnAttempt) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long start = System.nanoTime();
            recorder.record("retry-attempt", "START", "attempt=" + attempt);
            if (attempt >= succeedOnAttempt) {
                recorder.record(new TraceEvent(TraceContext.get(), "retry-attempt",
                        System.currentTimeMillis(), elapsedMillis(start), "SUCCESS",
                        "attempt=" + attempt));
                return "retried:" + input + ":attempt" + attempt;
            }
            recorder.record(new TraceEvent(TraceContext.get(), "retry-attempt",
                    System.currentTimeMillis(), elapsedMillis(start), "FAILURE",
                    "attempt=" + attempt + " transient error"));
        }
        throw new IllegalStateException("unreachable");
    }

    /** Background scheduled job; generates its own trace id per run. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void scheduledHousekeeping() {
        try {
            TraceContext.getOrCreate();
            recorder.record("scheduled-housekeeping", "SUCCESS", "run");
        } finally {
            TraceContext.clear();
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
