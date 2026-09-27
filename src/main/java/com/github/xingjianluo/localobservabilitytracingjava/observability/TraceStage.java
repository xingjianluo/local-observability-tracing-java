package com.github.xingjianluo.localobservabilitytracingjava.observability;

import java.time.Instant;

/**
 * 一次请求链路上的一个关键环节记录，用于按 traceId 串联排查。
 */
public record TraceStage(
        String name,
        long startNanos,
        long durationNanos,
        TraceStageStatus status,
        String detail,
        String thread,
        Instant timestamp
) {
}
