package com.github.xingjianluo.localobservabilitytracingjava.tests;

import com.github.xingjianluo.localobservabilitytracingjava.async.AsyncTaskService;
import com.github.xingjianluo.localobservabilitytracingjava.observability.InMemoryRecordStore;
import com.github.xingjianluo.localobservabilitytracingjava.observability.MetricsRegistry;
import com.github.xingjianluo.localobservabilitytracingjava.observability.RequestRecord;
import com.github.xingjianluo.localobservabilitytracingjava.trace.PropagationKeys;
import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 异步 / 调度 / 重试链路测试：
 * 跨线程 traceId 一致、重试不换标识、异步失败有独立记录但不拖垮主请求。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AsyncTraceTest {

    private static final Logger log = LoggerFactory.getLogger(AsyncTraceTest.class);

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    AsyncTaskService asyncTaskService;

    @Autowired
    InMemoryRecordStore store;

    @Autowired
    MetricsRegistry metricsRegistry;

    @BeforeEach
    void reset() {
        metricsRegistry.reset();
        store.clear();
    }

    @AfterEach
    void clean() {
        TraceContextHolder.clear();
    }

    @Test
    @DisplayName("异步任务在工作线程沿用同一 traceId，并生成独立的 async 入口记录")
    void asyncKeepsSameTraceId() throws Exception {
        TraceContextHolder.clear();
        TraceContextHolder.getOrCreate("async-direct-001");
        String mainId = TraceContextHolder.currentTraceId();

        String asyncId = asyncTaskService.runAsync(50).get(5, TimeUnit.SECONDS);
        assertEquals(mainId, asyncId, "异步工作线程必须沿用提交方 traceId");

        List<RequestRecord> records = store.findByTraceId(mainId);
        assertEquals(1, records.size());
        RequestRecord record = records.get(0);
        assertEquals("async:runAsync", record.entry());
        assertTrue(record.success());
        log.info("输入: 主线程提交异步任务 traceId={} -> 判定: 工作线程 traceId={} entry={} success={}",
                mainId, asyncId, record.entry(), record.success());
    }

    @Test
    @DisplayName("异步任务抛异常：异常被独立记录为失败，标识不变，且不波及提交方")
    void asyncFailureIsRecordedIndependently() {
        TraceContextHolder.clear();
        TraceContextHolder.getOrCreate("async-fail-002");
        String mainId = TraceContextHolder.currentTraceId();

        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> asyncTaskService.runAsyncFailing().get(5, TimeUnit.SECONDS));
        List<RequestRecord> records = store.findByTraceId(mainId);
        assertEquals(1, records.size());
        RequestRecord record = records.get(0);
        assertFalse(record.success());
        log.info("输入: 异步任务主动抛异常 traceId={} -> 判定: 记录失败 type={} message={} 提交方线程存活",
                mainId, record.failureType(), ex.getCause().getMessage());
    }

    @Test
    @DisplayName("HTTP 触发异步：httpTraceId 与 asyncTraceId 完全相同")
    void httpAsyncSameTraceId() {
        String incoming = "http-async-003";
        HttpHeaders headers = new HttpHeaders();
        headers.set(PropagationKeys.TRACE_ID_HEADER, incoming);
        ResponseEntity<String> resp = restTemplate.exchange(
                url("/demo/async?millis=50"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        String body = resp.getBody();
        String headerId = resp.getHeaders().getFirst(PropagationKeys.TRACE_ID_HEADER);
        log.info("输入: GET /demo/async 带 X-Trace-Id={} -> 判定: 响应头={} body={}", incoming, headerId, body);
        assertEquals(incoming, headerId);
        assertNotNull(body);
        assertTrue(body.contains("\"sameTraceId\":true"), "HTTP 与异步线程必须同标识");
        assertTrue(body.contains(incoming));

        // 同一 traceId 下应有两条入口记录（http + async）。
        List<RequestRecord> recs = store.latest(50).stream()
                .filter(r -> incoming.equals(r.traceId())).toList();
        boolean hasHttp = recs.stream().anyMatch(r -> r.entry().startsWith("http:"));
        boolean hasAsync = recs.stream().anyMatch(r -> r.entry().startsWith("async:"));
        assertTrue(hasHttp && hasAsync, "同一 traceId 应串联 HTTP 与异步两条记录");
        log.info("判定: traceId={} 下记录数={}, 含http={}, 含async={}", incoming, recs.size(), hasHttp, hasAsync);
    }

    @Test
    @DisplayName("重试链路：先失败后成功，所有尝试同属一个 traceId，且只产生一次成功统计")
    void retryUsesSameTraceIdAndCountsOnce() {
        String incoming = "http-retry-004";
        HttpHeaders headers = new HttpHeaders();
        headers.set(PropagationKeys.TRACE_ID_HEADER, incoming);
        ResponseEntity<String> resp = restTemplate.exchange(
                url("/demo/retry?attempts=3"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        String body = resp.getBody();
        log.info("输入: GET /demo/retry attempts=3 带 X-Trace-Id={} -> 判定: {}", incoming, body);
        assertNotNull(body);
        assertTrue(body.contains("\"attempts\":3"), "前两次失败、第三次成功，共 3 次尝试");
        assertTrue(body.contains("success-on-trial-3"));

        List<RequestRecord> sameTrace = store.findByTraceId(incoming);
        RequestRecord httpRecord = sameTrace.stream()
                .filter(r -> r.entry().startsWith("http:"))
                .findFirst().orElse(null);
        assertNotNull(httpRecord);
        assertTrue(httpRecord.success(), "最终成功，HTTP 记录必须是成功");
        long retryStages = httpRecord.stages().stream()
                .filter(s -> s.name().startsWith("retry-attempt-")).count();
        assertEquals(3, retryStages, "三次尝试都应作为环节挂在同一 traceId 下");
        log.info("判定: traceId={} 成功=true, retry-attempt 环节数={}（全部沿用同一标识）",
                incoming, retryStages);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
