package com.github.xingjianluo.localobservabilitytracingjava.trace;

import org.slf4j.MDC;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * 基于 ThreadLocal 栈的关联上下文持有者。
 *
 * <p>统一语义：
 * <ul>
 *   <li>同步/异步/调度/重试入口都通过 {@link #getOrCreate(String)} 获取上下文；</li>
 *   <li>当前线程已有上下文时直接沿用（重入、同线程内的重试都不会新造标识）；</li>
 *   <li>跨线程通过 {@link #wrap(Runnable)} 显式把提交方的上下文复制到执行方线程；</li>
 *   <li>线程复用时必须 {@link #pop()} / {@link #clear()}，防止串号。</li>
 * </ul>
 */
public final class TraceContextHolder {

    private static final ThreadLocal<Deque<TraceContext>> STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    private TraceContextHolder() {
    }

    /** 生成一个新的关联标识（32 位十六进制，无连字符）。 */
    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 当前线程已有上下文则直接沿用（重入不换号、不新造）；
     * 否则优先使用上游传入的 incomingTraceId，没有才生成，并入栈、写入 MDC。
     */
    public static TraceContext getOrCreate(String incomingTraceId) {
        TraceContext existing = STACK.get().peek();
        if (existing != null) {
            return existing;
        }
        String id = (incomingTraceId != null && !incomingTraceId.isBlank())
                ? incomingTraceId.trim()
                : generateTraceId();
        TraceContext context = new TraceContext(id);
        push(context);
        return context;
    }

    /** 入栈一个上下文并把 traceId 写入 MDC。 */
    public static void push(TraceContext context) {
        STACK.get().push(context);
        MDC.put(PropagationKeys.MDC_TRACE_ID, context.traceId());
    }

    public static TraceContext current() {
        return STACK.get().peek();
    }

    public static String currentTraceId() {
        TraceContext context = current();
        return context == null ? null : context.traceId();
    }

    /**
     * 在提交任务的线程捕获上下文（允许为 null），在执行线程恢复；
     * 执行结束恢复执行线程原有栈顶，保证线程池线程复用不串号。
     */
    public static Runnable wrap(Runnable task) {
        TraceContext captured = current();
        return () -> {
            TraceContext previousOnWorker = current();
            boolean pushed = false;
            if (captured != null && (previousOnWorker == null
                    || !captured.traceId().equals(previousOnWorker.traceId()))) {
                push(captured);
                pushed = true;
            }
            try {
                task.run();
            } finally {
                if (pushed) {
                    pop();
                }
            }
        };
    }

    /** 弹出栈顶；若栈空则彻底清理，避免线程池线程残留标识。 */
    public static void pop() {
        Deque<TraceContext> stack = STACK.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        TraceContext next = stack.peek();
        if (next != null) {
            MDC.put(PropagationKeys.MDC_TRACE_ID, next.traceId());
        } else {
            MDC.remove(PropagationKeys.MDC_TRACE_ID);
        }
    }

    public static void clear() {
        STACK.remove();
        MDC.remove(PropagationKeys.MDC_TRACE_ID);
    }
}
