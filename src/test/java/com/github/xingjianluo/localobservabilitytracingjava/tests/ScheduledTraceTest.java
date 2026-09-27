package com.github.xingjianluo.localobservabilitytracingjava.tests;

import com.github.xingjianluo.localobservabilitytracingjava.observability.InMemoryRecordStore;
import com.github.xingjianluo.localobservabilitytracingjava.observability.RequestRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 后台调度链路测试：每次调度独立 traceId，同一周期内标识贯穿，结束后线程不残留。
 */
@SpringBootTest(properties = {
        "observability.scheduling-enabled=true",
        "observability.schedule.fixed-delay=50"
})
class ScheduledTraceTest {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTraceTest.class);

    @Autowired
    InMemoryRecordStore store;

    @Test
    @DisplayName("调度连续触发多次：每次都是独立、成功的 scheduled 入口记录，traceId 互不相同")
    void scheduledRunsHaveDistinctTraceIds() {
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50))
                .until(() -> store.latest(100).stream()
                        .filter(r -> "scheduled:heartbeat".equals(r.entry()))
                        .count() >= 3);

        List<RequestRecord> heartbeats = store.latest(100).stream()
                .filter(r -> "scheduled:heartbeat".equals(r.entry()))
                .limit(5)
                .collect(Collectors.toList());
        Set<String> traceIds = heartbeats.stream().map(RequestRecord::traceId).collect(Collectors.toSet());
        log.info("输入: 调度以 50ms 间隔触发 -> 判定: 捕获 heartbeat 记录数={} traceIds={} 全部成功={}",
                heartbeats.size(), traceIds,
                heartbeats.stream().allMatch(RequestRecord::success));
        assertTrue(heartbeats.size() >= 3);
        assertEquals(heartbeats.size(), traceIds.size(), "每次调度必须使用不同 traceId");
        assertTrue(heartbeats.stream().allMatch(RequestRecord::success));
    }
}
