package com.github.xingjianluo.localobservabilitytracingjava.tests;

import com.github.xingjianluo.localobservabilitytracingjava.exception.BusinessRejectionException;
import com.github.xingjianluo.localobservabilitytracingjava.exception.DownstreamException;
import com.github.xingjianluo.localobservabilitytracingjava.exception.RequestTimeoutException;
import com.github.xingjianluo.localobservabilitytracingjava.observability.FailureType;
import com.github.xingjianluo.localobservabilitytracingjava.observability.InMemoryRecordStore;
import com.github.xingjianluo.localobservabilitytracingjava.observability.MetricsRegistry;
import com.github.xingjianluo.localobservabilitytracingjava.observability.MetricsSnapshot;
import com.github.xingjianluo.localobservabilitytracingjava.observability.RequestRecord;
import com.github.xingjianluo.localobservabilitytracingjava.observability.TraceRecorder;
import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发统计口径测试：
 * 1) 多线程大量结束请求，计数不多不少；2) 同一记录并发结束只计一次；3) 四类失败互斥。
 */
@SpringBootTest
class MetricsConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(MetricsConcurrencyTest.class);

    @Autowired
    MetricsRegistry metricsRegistry;

    @Autowired
    InMemoryRecordStore store;

    @Autowired
    TraceRecorder recorder;

    @AfterEach
    void tearDown() {
        metricsRegistry.reset();
        store.clear();
        TraceContextHolder.clear();
    }

    @Test
    @DisplayName("200 个并发请求全部成功：total/success 精确等于 200，无失败")
    void concurrentSuccessCountsExactly() throws Exception {
        metricsRegistry.reset();
        store.clear();
        int threads = 20;
        int perThread = 10;
        int expected = threads * perThread;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            int base = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        String id = "conc-ok-" + base + "-" + i;
                        TraceContextHolder.clear();
                        TraceContextHolder.getOrCreate(id);
                        recorder.start("test:concurrent-ok");
                        recorder.end(null);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        MetricsSnapshot snapshot = metricsRegistry.snapshot();
        log.info("输入: {} 个线程 x {} 次成功结束 -> 判定: total={} success={} failure={} stored={}",
                threads, perThread, snapshot.total(), snapshot.success(), snapshot.failure(), store.size());
        assertEquals(expected, snapshot.total(), "调用量必须不多不少");
        assertEquals(expected, snapshot.success());
        assertEquals(0, snapshot.failure());
        assertEquals(expected, store.size(), "每条记录都应落盘一次");
    }

    @Test
    @DisplayName("四类失败分别并发产生，分类计数互斥且总和等于总失败数")
    void failureTypesAreDistinct() throws Exception {
        metricsRegistry.reset();
        store.clear();
        Throwable[] errors = {
                new RequestTimeoutException("timeout"),
                new DownstreamException("down"),
                new BusinessRejectionException("biz"),
                new IllegalStateException("sys")
        };
        int perType = 25;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch done = new CountDownLatch(errors.length * perType);
        for (int t = 0; t < errors.length; t++) {
            final Throwable error = errors[t];
            final int typeIndex = t;
            for (int i = 0; i < perType; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        TraceContextHolder.clear();
                        TraceContextHolder.getOrCreate("conc-fail-" + typeIndex + "-" + idx);
                        recorder.start("test:concurrent-fail");
                        recorder.end(error);
                    } finally {
                        done.countDown();
                    }
                });
            }
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        MetricsSnapshot snapshot = metricsRegistry.snapshot();
        long sumByType = snapshot.failureByType().values().stream().mapToLong(Long::longValue).sum();
        log.info("输入: 四类失败各 {} 次 -> 判定: timeout={} downstream={} business={} system={}, 合计={}",
                perType,
                snapshot.failureByType().get(FailureType.TIMEOUT),
                snapshot.failureByType().get(FailureType.DOWNSTREAM_FAILURE),
                snapshot.failureByType().get(FailureType.BUSINESS_REJECTION),
                snapshot.failureByType().get(FailureType.SYSTEM_ERROR),
                sumByType);
        assertEquals(perType, snapshot.failureByType().get(FailureType.TIMEOUT));
        assertEquals(perType, snapshot.failureByType().get(FailureType.DOWNSTREAM_FAILURE));
        assertEquals(perType, snapshot.failureByType().get(FailureType.BUSINESS_REJECTION));
        assertEquals(perType, snapshot.failureByType().get(FailureType.SYSTEM_ERROR));
        assertEquals(0, snapshot.failureByType().get(FailureType.NONE),
                "NONE 不是失败类型，计数必须为 0");
        assertEquals(4L * perType, snapshot.failure());
        assertEquals(4L * perType, sumByType, "一次故障只能落到一个分类");
    }

    @Test
    @DisplayName("同一记录被 32 线程并发结束：CAS 保证指标只累计一次")
    void sameFailureCountedOnlyOnce() throws Exception {
        metricsRegistry.reset();
        store.clear();
        RequestRecord record = new RequestRecord("double-count-trace", "test:double-count");
        record.markEnd(false, FailureType.DOWNSTREAM_FAILURE, "boom", 502,
                false, 1_000_000L, Instant.now());

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    metricsRegistry.recordCompleted(record);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        MetricsSnapshot snapshot = metricsRegistry.snapshot();
        log.info("输入: 同一失败记录被 {} 线程并发 recordCompleted -> 判定: total={} failure={} downstream={}",
                threads, snapshot.total(), snapshot.failure(),
                snapshot.failureByType().get(FailureType.DOWNSTREAM_FAILURE));
        assertEquals(1, snapshot.total(), "同一次故障只能算一次调用");
        assertEquals(1, snapshot.failure());
        assertEquals(1, snapshot.failureByType().get(FailureType.DOWNSTREAM_FAILURE));
        assertTrue(record.counted());
    }
}
