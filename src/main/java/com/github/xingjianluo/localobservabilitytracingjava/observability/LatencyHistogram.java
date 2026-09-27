package com.github.xingjianluo.localobservabilitytracingjava.observability;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 耗时分布直方图。
 *
 * <p>采用固定毫秒上界桶 + +Inf 桶，内部为每个桶维护一个 {@link java.util.concurrent.atomic.AtomicLong}，
 * 高并发记录时先落到所有满足上界的桶，读取时即为 Prometheus 风格的累计（le）计数。
 */
public final class LatencyHistogram {

    /** 桶上界（毫秒），严格递增；最后一个桶是 +Inf。 */
    public static final long[] BUCKET_BOUNDS_MS = {
            1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000, Long.MAX_VALUE
    };

    private final AtomicLongArray bucketCounts = new AtomicLongArray(BUCKET_BOUNDS_MS.length);

    public LatencyHistogram() {
    }

    /** 记录一次耗时。 */
    public void record(long durationNanos) {
        long durationMs = durationNanos / 1_000_000L;
        for (int i = 0; i < BUCKET_BOUNDS_MS.length; i++) {
            if (durationMs <= BUCKET_BOUNDS_MS[i]) {
                bucketCounts.incrementAndGet(i);
            }
        }
    }

    /**
     * 返回每个桶上界及以下的累计样本数（与 {@link #BUCKET_BOUNDS_MS} 等长，最后一个即总数）。
     */
    public long[] cumulativeCounts() {
        long[] result = new long[BUCKET_BOUNDS_MS.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = bucketCounts.get(i);
        }
        return result;
    }

    public long totalCount() {
        return bucketCounts.get(BUCKET_BOUNDS_MS.length - 1);
    }

    public void reset() {
        for (int i = 0; i < BUCKET_BOUNDS_MS.length; i++) {
            bucketCounts.set(i, 0L);
        }
    }
}
