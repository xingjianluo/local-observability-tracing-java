package com.github.xingjianluo.localobservabilitytracingjava.observability;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 链路记录器：开启一次请求记录、记录关键环节（span）、结束并完成统计落盘。
 *
 * <p>HTTP、异步、调度、重试各入口共用同一套语义：
 * 标识来自 {@link com.github.xingjianluo.localobservabilitytracingjava.trace.TraceContextHolder}，
 * 因此异步/重试天然沿用同一个 traceId；同线程内重入不会生成新记录外的新标识。
 */
public interface TraceRecorder {

    /** 以当前线程的 traceId 开启一次入口记录（如 http:GET /demo/ping、async:job、scheduled:heartbeat）。 */
    RequestRecord start(String entry);

    /** 开始一个关键环节，返回计时句柄。 */
    StageHandle stageStart(String name, String detail);

    /** 结束关键环节并写入记录。 */
    void stageEnd(StageHandle handle, boolean success, String detail);

    /** 执行 action 并把它包成一个成功/失败环节；异常原样抛出。 */
    <T> T recordStage(String name, Callable<T> action) throws Exception;

    /** 无返回值版本。 */
    void recordStage(String name, CheckedRunnable action) throws Exception;

    /**
     * 结束当前请求：分类、判慢、计时、写存储、累计指标（单点结束，只计一次）。
     * error 为 null 表示成功。
     */
    RequestRecord end(Throwable error);

    /** 当前线程正在进行的请求记录，不存在时抛 {@link IllegalStateException}。 */
    RequestRecord current();

    /** 当前线程正在进行的请求记录，可能为 null。 */
    RequestRecord currentOrNull();

    /** 最近若干条记录。 */
    List<RequestRecord> recent(int limit);

    /** 环节计时句柄，仅在执行线程内使用。 */
    final class StageHandle {
        private final String name;
        private final String detail;
        private final long startNanos;
        private final String thread;
        private final Instant timestamp;

        public StageHandle(String name, String detail, long startNanos, String thread, Instant timestamp) {
            this.name = name;
            this.detail = detail;
            this.startNanos = startNanos;
            this.thread = thread;
            this.timestamp = timestamp;
        }

        public String name() {
            return name;
        }

        public String detail() {
            return detail;
        }

        public long startNanos() {
            return startNanos;
        }

        public String thread() {
            return thread;
        }

        public Instant timestamp() {
            return timestamp;
        }
    }

    /** 无受检异常的 Runnable。 */
    @FunctionalInterface
    interface CheckedRunnable {
        void run() throws Exception;
    }
}
