package com.github.xingjianluo.localobservabilitytracingjava.observability;

import java.util.Map;

/**
 * 某一时刻的统计快照，供本地端点查看。
 */
public record MetricsSnapshot(
        long total,
        long success,
        long failure,
        Map<FailureType, Long> failureByType,
        long slowCount,
        long[] latencyBoundsMillis,
        long[] latencyCumulativeCounts
) {
}
