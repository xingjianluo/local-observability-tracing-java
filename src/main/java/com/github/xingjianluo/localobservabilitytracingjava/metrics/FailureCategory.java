package com.github.xingjianluo.localobservabilitytracingjava.metrics;

/**
 * Classification of request failures.
 */
public enum FailureCategory {
    TIMEOUT,
    DOWNSTREAM_FAILURE,
    BUSINESS_REJECTED,
    SYSTEM_ERROR
}
