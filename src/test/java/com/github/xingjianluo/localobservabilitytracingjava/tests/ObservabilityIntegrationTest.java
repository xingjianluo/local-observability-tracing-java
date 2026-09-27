package com.github.xingjianluo.localobservabilitytracingjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.xingjianluo.localobservabilitytracingjava.metrics.RequestMetrics;
import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceContext;
import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceEvent;
import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceRecorder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObservabilityIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityIntegrationTest.class);

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private RequestMetrics metrics;
    @Autowired
    private TraceRecorder recorder;

    @BeforeEach
    void reset() {
        metrics.reset();
        recorder.clear();
    }

    @Test
    void upstreamTraceIdIsReusedAndEchoed() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TraceContext.HEADER, "upstream-abc-123");
        ResponseEntity<Map> resp = rest.exchange("/demo/ok?input=hi", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        String responseHeader = resp.getHeaders().getFirst(TraceContext.HEADER);
        Object bodyTraceId = resp.getBody().get("traceId");
        log.info("input: header X-Trace-Id=upstream-abc-123 | response header={} body.traceId={} "
                + "| expect both equal upstream id", responseHeader, bodyTraceId);
        assertEquals("upstream-abc-123", responseHeader);
        assertEquals("upstream-abc-123", bodyTraceId);
    }

    @Test
    void traceIdIsGeneratedWhenAbsent() {
        ResponseEntity<Map> resp = rest.getForEntity("/demo/ok?input=hi", Map.class);
        String responseHeader = resp.getHeaders().getFirst(TraceContext.HEADER);
        Object bodyTraceId = resp.getBody().get("traceId");
        log.info("input: no trace header | response header={} body.traceId={} "
                + "| expect non-blank and equal", responseHeader, bodyTraceId);
        assertNotNull(responseHeader);
        assertFalse(responseHeader.isBlank());
        assertEquals(responseHeader, bodyTraceId);
    }

    @Test
    void asyncTaskKeepsSameTraceId() {
        ResponseEntity<Map> resp = rest.getForEntity("/demo/async?input=job1", Map.class);
        String traceId = resp.getHeaders().getFirst(TraceContext.HEADER);
        List<TraceEvent> events = recorder.findByTraceId(traceId);
        List<String> stages = events.stream().map(TraceEvent::stage).toList();
        log.info("input: GET /demo/async | traceId={} stages={} "
                + "| expect async-task stage under same trace id", traceId, stages);
        assertTrue(stages.contains("async-task"),
                "async stage must be recorded under the request trace id");
        assertTrue(events.stream().allMatch(e -> traceId.equals(e.traceId())),
                "no event may carry a different trace id");
    }

    @Test
    void retryAttemptsKeepSameTraceId() {
        ResponseEntity<Map> resp = rest.getForEntity(
                "/demo/retry?input=t&succeedOnAttempt=2", Map.class);
        String traceId = resp.getHeaders().getFirst(TraceContext.HEADER);
        List<TraceEvent> attempts = recorder.findByTraceId(traceId).stream()
                .filter(e -> "retry-attempt".equals(e.stage()))
                .filter(e -> e.durationMillis() != null)
                .toList();
        log.info("input: retry succeedOnAttempt=2 | traceId={} retryEvents={} "
                + "| expect 2 attempts, all with same trace id", traceId, attempts.size());
        assertEquals(2, attempts.size());
        assertTrue(attempts.stream().allMatch(e -> traceId.equals(e.traceId())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void failureCategoriesAreDistinguishable() {
        ResponseEntity<Map> reject = rest.getForEntity("/demo/reject", Map.class);
        ResponseEntity<Map> downstream = rest.getForEntity("/demo/downstream?fail=true", Map.class);
        ResponseEntity<Map> error = rest.getForEntity("/demo/error", Map.class);
        ResponseEntity<String> timeout = rest.getForEntity("/demo/timeout", String.class);

        log.info("input: one call per failing endpoint | reject={} downstream={} error={} "
                + "timeout={} | expect distinct categories",
                reject.getBody().get("failureCategory"),
                downstream.getBody().get("failureCategory"),
                error.getBody().get("failureCategory"), timeout.getStatusCode());

        assertEquals("BUSINESS_REJECTED", reject.getBody().get("failureCategory"));
        assertEquals("DOWNSTREAM_FAILURE", downstream.getBody().get("failureCategory"));
        assertEquals("SYSTEM_ERROR", error.getBody().get("failureCategory"));

        Map<String, Object> snapshot = metrics.snapshot();
        Map<String, Object> rejectM = (Map<String, Object>) snapshot.get("GET /demo/reject");
        Map<String, Object> downstreamM =
                (Map<String, Object>) snapshot.get("GET /demo/downstream");
        Map<String, Object> errorM = (Map<String, Object>) snapshot.get("GET /demo/error");
        Map<String, Object> timeoutM = (Map<String, Object>) snapshot.get("GET /demo/timeout");
        log.info("metrics snapshot per failing route | reject={} downstream={} error={} "
                + "timeout={} | expect exactly one failure each with matching category",
                rejectM, downstreamM, errorM, timeoutM);

        assertCategory(rejectM, "BUSINESS_REJECTED");
        assertCategory(downstreamM, "DOWNSTREAM_FAILURE");
        assertCategory(errorM, "SYSTEM_ERROR");
        assertCategory(timeoutM, "TIMEOUT");
    }

    @SuppressWarnings("unchecked")
    private static void assertCategory(Map<String, Object> routeMetrics, String category) {
        assertNotNull(routeMetrics, "route metrics must exist");
        assertEquals(1L, routeMetrics.get("failure"), "same failure counted exactly once");
        assertEquals(1L, routeMetrics.get("total"));
        Map<String, Object> byCategory =
                (Map<String, Object>) routeMetrics.get("failuresByCategory");
        assertEquals(1L, byCategory.get(category));
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentRequestsAreCountedExactly() throws InterruptedException {
        int total = 50;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < total; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    go.await();
                    rest.getForEntity("/demo/ok?input=c" + idx, Map.class);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        Map<String, Object> route =
                (Map<String, Object>) metrics.snapshot().get("GET /demo/ok");
        log.info("input: {} concurrent GET /demo/ok | total={} success={} failure={} "
                + "| expect total=success={} failure=0",
                total, route.get("total"), route.get("success"), route.get("failure"), total);
        assertEquals((long) total, route.get("total"));
        assertEquals((long) total, route.get("success"));
        assertEquals(0L, route.get("failure"));
    }

    @Test
    void traceEventsAreQueryableByTraceId() {
        ResponseEntity<Map> resp = rest.getForEntity("/demo/ok?input=trace-me", Map.class);
        String traceId = resp.getHeaders().getFirst(TraceContext.HEADER);
        List<TraceEvent> events = recorder.findByTraceId(traceId);
        List<String> stages = events.stream().map(TraceEvent::stage).toList();
        String finalOutcome = events.stream()
                .filter(e -> "request-completed".equals(e.stage()))
                .map(TraceEvent::outcome).findFirst().orElse("NONE");
        log.info("input: GET /demo/ok | traceId={} stages={} finalOutcome={} "
                + "| expect received/completed stages and SUCCESS outcome",
                traceId, stages, finalOutcome);
        assertTrue(stages.contains("request-received"));
        assertTrue(stages.contains("request-completed"));
        assertEquals("SUCCESS", finalOutcome);
    }
}
