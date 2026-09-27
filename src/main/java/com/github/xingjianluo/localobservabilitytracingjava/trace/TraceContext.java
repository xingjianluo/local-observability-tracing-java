package com.github.xingjianluo.localobservabilitytracingjava.trace;

import java.time.Instant;

/**
 * 一次请求链路的关联上下文，核心内容是贯穿全链路的 traceId。
 * 不可变；跨线程传递时复制同一个实例，保证标识不串号。
 */
public final class TraceContext {

    private final String traceId;
    private final Instant createdAt;

    public TraceContext(String traceId) {
        this.traceId = traceId;
        this.createdAt = Instant.now();
    }

    public String traceId() {
        return traceId;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
