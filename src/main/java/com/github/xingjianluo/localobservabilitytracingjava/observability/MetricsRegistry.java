package com.github.xingjianluo.localobservabilitytracingjava.observability;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 本地进程内指标注册中心。
 *
 * <p>统计口径：
 * <ul>
 *   <li>total：所有已完成请求（成功 + 失败）；</li>
 *   <li>success / failure：互斥，按 {@link RequestRecord#tryMarkCounted()} 保证只累计一次；</li>
 *   <li>failureByType：四类失败分别计数，同一次故障只会落到其中一类一次；</li>
 *   <li>slowCount：慢请求数（慢但成功也算）；</li>
 *   <li>耗时分布：{@link LatencyHistogram} 毫秒桶。</li>
 * </ul>
 * 全部使用 {@link LongAdder}，并发下不丢计数、不重复计数。
 */
@Component
public class MetricsRegistry {

    private final LongAdder total = new LongAdder();
    private final LongAdder success = new LongAdder();
    private final LongAdder failure = new LongAdder();
    private final LongAdder slowCount = new LongAdder();
    private final Map<FailureType, LongAdder> failureByType = new EnumMap<>(FailureType.class);
    private final LatencyHistogram histogram = new LatencyHistogram();

    public MetricsRegistry() {
        for (FailureType type : FailureType.values()) {
            failureByType.put(type, new LongAdder());
        }
    }

    /**
     * 记录一次已完成请求。CAS 成功才计数，保证同一次请求并发触发结束时只算一次。
     */
    public void recordCompleted(RequestRecord record) {
        if (record == null || !record.tryMarkCounted()) {
            return;
        }
        total.increment();
        histogram.record(record.durationNanos());
        if (record.slow()) {
            slowCount.increment();
        }
        if (record.success()) {
            success.increment();
        } else {
            failure.increment();
            FailureType type = record.failureType() == null ? FailureType.SYSTEM_ERROR : record.failureType();
            failureByType.get(type).increment();
        }
    }

    public MetricsSnapshot snapshot() {
        Map<FailureType, Long> counts = new LinkedHashMap<>();
        for (FailureType type : FailureType.values()) {
            counts.put(type, failureByType.get(type).sum());
        }
        return new MetricsSnapshot(
                total.sum(),
                success.sum(),
                failure.sum(),
                counts,
                slowCount.sum(),
                LatencyHistogram.BUCKET_BOUNDS_MS.clone(),
                histogram.cumulativeCounts()
        );
    }

    /** 测试用重置。 */
    public void reset() {
        total.reset();
        success.reset();
        failure.reset();
        slowCount.reset();
        failureByType.values().forEach(LongAdder::reset);
        histogram.reset();
    }
}
