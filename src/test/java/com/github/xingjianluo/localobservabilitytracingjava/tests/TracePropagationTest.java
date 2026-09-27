package com.github.xingjianluo.localobservabilitytracingjava.tests;

import com.github.xingjianluo.localobservabilitytracingjava.trace.PropagationKeys;
import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceContext;
import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 关联标识传播测试。
 * 判定依据：上游有标识沿用、无标识生成、重入不换号、跨线程传递、响应头可拿到。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TracePropagationTest {

    private static final Logger log = LoggerFactory.getLogger(TracePropagationTest.class);

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @AfterEach
    void cleanThread() {
        TraceContextHolder.clear();
    }

    @Test
    @DisplayName("上游已带标识则沿用，缺失才生成")
    void reusesIncomingOrGenerates() {
        TraceContextHolder.clear();
        TraceContext first = TraceContextHolder.getOrCreate(null);
        assertNotNull(first.traceId());
        log.info("输入: incoming=null -> 判定: 生成新标识 traceId={}", first.traceId());

        TraceContext same = TraceContextHolder.getOrCreate(null);
        assertSame(first, same, "重入必须沿用已有上下文，不得新造标识");
        log.info("输入: 同线程重入 -> 判定: 沿用 traceId={}", same.traceId());

        TraceContextHolder.clear();
        TraceContext upstream = TraceContextHolder.getOrCreate("upstream-id-123");
        assertEquals("upstream-id-123", upstream.traceId(), "上游标识必须原样沿用");
        log.info("输入: incoming=upstream-id-123 -> 判定: 沿用 traceId={}", upstream.traceId());

        TraceContext again = TraceContextHolder.getOrCreate("should-be-ignored");
        assertEquals("upstream-id-123", again.traceId(), "已有标识时，后续入参不得替换");
        log.info("输入: 重入且传入不同标识 -> 判定: 仍沿用原 traceId={}", again.traceId());
    }

    @Test
    @DisplayName("wrap 把标识带到工作线程，工作线程结束后不残留、主线程不受影响")
    void propagatesAcrossThreads() throws Exception {
        TraceContextHolder.clear();
        TraceContext ctx = TraceContextHolder.getOrCreate("thread-trace-abc");
        AtomicReference<String> workerId = new AtomicReference<>();
        AtomicReference<String> afterTaskId = new AtomicReference<>();

        Thread t = new Thread(TraceContextHolder.wrap(() -> {
            workerId.set(TraceContextHolder.currentTraceId());
            log.info("输入: 工作线程执行中 -> 判定: 读到 traceId={}", workerId.get());
        }));
        t.start();
        t.join();
        // 工作线程执行完毕后在另一个裸任务上观察，应当无标识（不串号）。
        Thread t2 = new Thread(() -> afterTaskId.set(TraceContextHolder.currentTraceId()));
        t2.start();
        t2.join();

        assertEquals("thread-trace-abc", workerId.get(), "跨线程必须拿到提交方 traceId");
        assertNull(afterTaskId.get(), "线程复用/结束后不得残留上一个任务的标识");
        assertEquals("thread-trace-abc", TraceContextHolder.currentTraceId(), "主线程标识不受影响");
        log.info("判定: workerTraceId={}, 复用线程残留={}, mainTraceId={}",
                workerId.get(), afterTaskId.get(), TraceContextHolder.currentTraceId());
    }

    @Test
    @DisplayName("HTTP 无标识时生成并通过响应头返回")
    void httpGeneratesAndReturnsHeader() {
        ResponseEntity<String> resp = restTemplate.getForEntity(url("/demo/ping"), String.class);
        String traceId = resp.getHeaders().getFirst(PropagationKeys.TRACE_ID_HEADER);
        assertNotNull(traceId);
        assertTrue(resp.getBody() != null && resp.getBody().contains(traceId),
                "响应体也应回带 traceId");
        log.info("输入: GET /demo/ping 无头 -> 判定: 2xx 且响应头 traceId={} body={}",
                traceId, resp.getBody());
    }

    @Test
    @DisplayName("HTTP 带上游标识时全链路沿用并原样回传")
    void httpReusesIncomingHeader() {
        String incoming = "customer-correlation-001";
        HttpHeaders headers = new HttpHeaders();
        headers.set(PropagationKeys.TRACE_ID_HEADER, incoming);
        ResponseEntity<String> resp = restTemplate.exchange(
                url("/demo/ping"), HttpMethod.GET, new HttpEntity<>(headers), String.class);
        String traceId = resp.getHeaders().getFirst(PropagationKeys.TRACE_ID_HEADER);
        assertEquals(incoming, traceId, "响应头必须回传上游标识");
        assertTrue(resp.getBody() != null && resp.getBody().contains(incoming),
                "响应体中的 traceId 必须与上游一致");
        log.info("输入: X-Trace-Id={} -> 判定: 响应头/体均为同一标识 traceId={}", incoming, traceId);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
