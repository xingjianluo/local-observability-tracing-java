package com.github.xingjianluo.localobservabilitytracingjava.observability;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次请求的完整观测记录：关联标识、入口类型、经过的关键环节、耗时与最终判定。
 *
 * <p>线程安全约定：环节在请求处理过程中追加（synchronized），
 * 结束信息只由单一结束点（TraceRecorder#end）填充一次；
 * {@link #tryMarkCounted()} 用 CAS 语义保证并发/重试下同一请求最多统计一次。
 */
public final class RequestRecord {

    private final String traceId;
    private final String entry;
    private final Instant startAt;

    private final List<TraceStage> stages = new ArrayList<>();

    private volatile Instant endAt;
    private volatile long durationNanos;
    private volatile boolean success;
    private volatile FailureType failureType;
    private volatile String failureMessage;
    private volatile int httpStatus;
    private volatile boolean slow;
    /** 0=未计数，1=已计数；CAS 防重复计数。 */
    private final java.util.concurrent.atomic.AtomicInteger counted =
            new java.util.concurrent.atomic.AtomicInteger();

    public RequestRecord(String traceId, String entry) {
        this.traceId = traceId;
        this.entry = entry;
        this.startAt = Instant.now();
        this.failureType = FailureType.NONE;
    }

    @JsonProperty("traceId")
    public String traceId() {
        return traceId;
    }

    @JsonProperty("entry")
    public String entry() {
        return entry;
    }

    @JsonProperty("startAt")
    public Instant startAt() {
        return startAt;
    }

    @JsonProperty("endAt")
    public Instant endAt() {
        return endAt;
    }

    @JsonProperty("durationNanos")
    public long durationNanos() {
        return durationNanos;
    }

    @JsonProperty("success")
    public boolean success() {
        return success;
    }

    @JsonProperty("failureType")
    public FailureType failureType() {
        return failureType;
    }

    @JsonProperty("failureMessage")
    public String failureMessage() {
        return failureMessage;
    }

    @JsonProperty("httpStatus")
    public int httpStatus() {
        return httpStatus;
    }

    @JsonProperty("slow")
    public boolean slow() {
        return slow;
    }

    @JsonProperty("counted")
    public boolean counted() {
        return counted.get() == 1;
    }

    @JsonProperty("stages")
    public synchronized List<TraceStage> stages() {
        return new ArrayList<>(stages);
    }

    /** 追加一个关键环节（span）。 */
    public synchronized void addStage(TraceStage stage) {
        stages.add(stage);
    }

    /**
     * 由唯一结束点写入最终判定。
     */
    public void markEnd(boolean success, FailureType failureType, String failureMessage,
                        int httpStatus, boolean slow, long durationNanos, Instant endAt) {
        this.success = success;
        this.failureType = failureType == null ? FailureType.NONE : failureType;
        this.failureMessage = failureMessage;
        this.httpStatus = httpStatus;
        this.slow = slow;
        this.durationNanos = durationNanos;
        this.endAt = endAt;
    }

    /**
     * CAS 占位：把"未计数"翻转为"已计数"，只有一个线程能成功。
     * 保证并发结束、重试等场景下同一次请求的指标只累计一次。
     */
    public boolean tryMarkCounted() {
        return counted.compareAndSet(0, 1);
    }
}
