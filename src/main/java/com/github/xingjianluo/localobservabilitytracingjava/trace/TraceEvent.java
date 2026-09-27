package com.github.xingjianluo.localobservabilitytracingjava.trace;

/**
 * One recorded step of a request's lifecycle.
 */
public record TraceEvent(
        String traceId,
        String stage,
        long timestampMillis,
        Long durationMillis,
        String outcome,
        String detail) {
}
