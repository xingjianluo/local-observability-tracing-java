package com.github.xingjianluo.localobservabilitytracingjava.tests;

import com.github.xingjianluo.localobservabilitytracingjava.observability.FailureType;
import com.github.xingjianluo.localobservabilitytracingjava.observability.InMemoryRecordStore;
import com.github.xingjianluo.localobservabilitytracingjava.observability.MetricsRegistry;
import com.github.xingjianluo.localobservabilitytracingjava.observability.MetricsSnapshot;
import com.github.xingjianluo.localobservabilitytracingjava.observability.RequestRecord;
import com.github.xingjianluo.localobservabilitytracingjava.observability.TraceStage;
import com.github.xingjianluo.localobservabilitytracingjava.trace.PropagationKeys;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 异常分类集成测试：超时 / 下游失败 / 业务拒绝 / 系统异常各自可区分，
 * 慢请求可识别；记录能按 traceId 串起环节与判定，并可本地导出 JSON。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorClassificationIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ErrorClassificationIntegrationTest.class);

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    InMemoryRecordStore store;

    @Autowired
    MetricsRegistry metricsRegistry;

    @BeforeEach
    void reset() {
        metricsRegistry.reset();
        store.clear();
    }

    @Test
    @DisplayName("四类失败走不同 HTTP 状态码、可按 traceId 查到唯一失败类型，且计数各一次")
    void fourFailureKindsAreSeparable() {
        // 路径，期望 HTTP 状态，期望失败分类，自定义 traceId 前缀
        Object[][] cases = {
                {"/demo/timeout?millis=1200", HttpStatus.GATEWAY_TIMEOUT, FailureType.TIMEOUT, "tc-timeout"},
                {"/demo/downstream-fail", HttpStatus.BAD_GATEWAY, FailureType.DOWNSTREAM_FAILURE, "tc-down"},
                {"/demo/business-reject", HttpStatus.UNPROCESSABLE_ENTITY, FailureType.BUSINESS_REJECTION, "tc-biz"},
                {"/demo/system-error", HttpStatus.INTERNAL_SERVER_ERROR, FailureType.SYSTEM_ERROR, "tc-sys"}
        };
        for (Object[] c : cases) {
            String path = (String) c[0];
            HttpStatus status = (HttpStatus) c[1];
            FailureType expectedType = (FailureType) c[2];
            String traceId = (String) c[3];

            HttpHeaders headers = new HttpHeaders();
            headers.set(PropagationKeys.TRACE_ID_HEADER, traceId);
            ResponseEntity<String> resp = restTemplate.exchange(
                    url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);

            assertEquals(status, resp.getStatusCode(), "路径 " + path + " 状态码不符");
            assertEquals(traceId, resp.getHeaders().getFirst(PropagationKeys.TRACE_ID_HEADER));

            List<RequestRecord> recs = store.findByTraceId(traceId);
            assertEquals(1, recs.size(), "同一 traceId 恰好一条 HTTP 记录");
            RequestRecord record = recs.get(0);
            assertFalse(record.success());
            assertEquals(expectedType, record.failureType(),
                    "路径 " + path + " 失败分类必须为 " + expectedType);
            log.info("输入: {} X-Trace-Id={} -> 判定: http={} failureType={} durationMs={} msg={}",
                    path, traceId, status.value(), record.failureType(),
                    record.durationNanos() / 1_000_000L, record.failureMessage());
        }

        MetricsSnapshot snapshot = metricsRegistry.snapshot();
        log.info("判定汇总: total={} failure={} 分类={}", snapshot.total(), snapshot.failure(),
                snapshot.failureByType());
        assertEquals(4, snapshot.total());
        assertEquals(4, snapshot.failure());
        assertEquals(1, snapshot.failureByType().get(FailureType.TIMEOUT));
        assertEquals(1, snapshot.failureByType().get(FailureType.DOWNSTREAM_FAILURE));
        assertEquals(1, snapshot.failureByType().get(FailureType.BUSINESS_REJECTION));
        assertEquals(1, snapshot.failureByType().get(FailureType.SYSTEM_ERROR));
    }

    @Test
    @DisplayName("慢但成功的请求：HTTP 200、记录 slow=true、计入慢请求但不计失败")
    void slowButSuccessIsMarked() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(PropagationKeys.TRACE_ID_HEADER, "tc-slow");
        ResponseEntity<String> resp = restTemplate.exchange(
                url("/demo/slow?millis=550"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());

        RequestRecord record = store.findByTraceId("tc-slow").get(0);
        assertTrue(record.success());
        assertTrue(record.slow(), "超过慢请求阈值必须标 slow");
        MetricsSnapshot snapshot = metricsRegistry.snapshot();
        assertEquals(1, snapshot.slowCount());
        assertEquals(1, snapshot.success());
        assertEquals(0, snapshot.failure());
        log.info("输入: /demo/slow?millis=550 -> 判定: 200 success=true slow={} slowCount={} failure={}",
                record.slow(), snapshot.slowCount(), snapshot.failure());
    }

    @Test
    @DisplayName("按 traceId 可串起关键环节与耗时；导出为本地 JSON 文件且内容包含该 traceId")
    void traceStagesAreQueryableAndExportable() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set(PropagationKeys.TRACE_ID_HEADER, "tc-export");
        ResponseEntity<String> resp = restTemplate.exchange(
                url("/demo/retry?attempts=2"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());

        List<RequestRecord> recs = store.findByTraceId("tc-export");
        assertEquals(1, recs.size());
        RequestRecord record = recs.get(0);
        List<TraceStage> stages = record.stages();
        assertTrue(stages.stream().anyMatch(s -> s.name().equals("retry-attempt-1")
                        && s.status() == com.github.xingjianluo.localobservabilitytracingjava.observability.TraceStageStatus.FAILURE),
                "第 1 次尝试应是失败环节");
        assertTrue(stages.stream().anyMatch(s -> s.name().equals("retry-attempt-2")
                        && s.status() == com.github.xingjianluo.localobservabilitytracingjava.observability.TraceStageStatus.SUCCESS),
                "第 2 次尝试应是成功环节");
        log.info("输入: /demo/retry?attempts=2 -> 判定: 环节数={} 环节={}",
                stages.size(), stages.stream().map(s -> s.name() + ":" + s.status()).toList());

        // 通过本地导出端点落盘，再读回文件验证。
        ResponseEntity<String> exportResp = restTemplate.postForEntity(
                url("/observability/traces/tc-export/export"), null, String.class);
        assertEquals(HttpStatus.OK, exportResp.getStatusCode());
        String body = exportResp.getBody();
        assertNotNull(body);
        assertTrue(body.contains("\"exported\":true"), "导出应成功: " + body);
        String path = body.replaceAll(".*\"path\":\"([^\"]+)\".*", "$1");
        Path file = Path.of(path);
        assertTrue(Files.exists(file), "导出文件必须存在: " + file);
        String content = Files.readString(file);
        assertTrue(content.contains("tc-export"));
        assertTrue(content.contains("retry-attempt-1"));
        log.info("判定: 已导出本地文件 {} ({} 字节)，内容包含 traceId 与全部环节",
                file, Files.size(file));
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
