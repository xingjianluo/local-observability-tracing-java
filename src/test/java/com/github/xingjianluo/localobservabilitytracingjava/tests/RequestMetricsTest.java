package com.github.xingjianluo.localobservabilitytracingjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.xingjianluo.localobservabilitytracingjava.metrics.FailureCategory;
import com.github.xingjianluo.localobservabilitytracingjava.metrics.RequestMetrics;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RequestMetricsTest {

    private static final Logger log = LoggerFactory.getLogger(RequestMetricsTest.class);

    @Test
    @SuppressWarnings("unchecked")
    void concurrentRecordingKeepsExactCounts() throws InterruptedException {
        RequestMetrics metrics = new RequestMetrics();
        int threads = 8;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    for (int i = 0; i < perThread; i++) {
                        metrics.recordSuccess("GET /demo/ok", 5);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        Map<String, Object> route =
                (Map<String, Object>) metrics.snapshot().get("GET /demo/ok");
        long expected = (long) threads * perThread;
        log.info("input: {} threads x {} success records | total={} success={} | expect {}",
                threads, perThread, route.get("total"), route.get("success"), expected);
        assertEquals(expected, route.get("total"), "no count may be lost under concurrency");
        assertEquals(expected, route.get("success"));
        assertEquals(0L, route.get("failure"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void failureCategoriesAreRecordedSeparately() {
        RequestMetrics metrics = new RequestMetrics();
        metrics.recordFailure("GET /demo/reject", 3, FailureCategory.BUSINESS_REJECTED);
        metrics.recordFailure("GET /demo/downstream", 7, FailureCategory.DOWNSTREAM_FAILURE);
        metrics.recordFailure("GET /demo/timeout", 100, FailureCategory.TIMEOUT);
        metrics.recordFailure("GET /demo/error", 2, FailureCategory.SYSTEM_ERROR);
        metrics.recordSuccess("GET /demo/ok", 4);

        Map<String, Object> snapshot = metrics.snapshot();
        Map<String, Object> reject =
                (Map<String, Object>) snapshot.get("GET /demo/reject");
        Map<String, String> categories =
                (Map<String, String>) reject.get("failuresByCategory");
        log.info("input: one failure per category | reject route categories={} | "
                + "expect BUSINESS_REJECTED=1 others=0", categories);
        assertEquals(1L, categories.get(FailureCategory.BUSINESS_REJECTED.name()));
        assertEquals(0L, categories.get(FailureCategory.DOWNSTREAM_FAILURE.name()));
        assertEquals(1L, reject.get("failure"));
        assertEquals(1L, reject.get("total"), "one failure must be counted exactly once");
    }
}
