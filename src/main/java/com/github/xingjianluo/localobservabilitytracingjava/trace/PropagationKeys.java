package com.github.xingjianluo.localobservabilitytracingjava.trace;

/** 关联标识在 HTTP 头、MDC 等位置使用的键名常量。 */
public final class PropagationKeys {

    /** 上游传入 / 下游回传关联标识所用的 HTTP 请求/响应头。 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** 日志 MDC 中关联标识的键。 */
    public static final String MDC_TRACE_ID = "traceId";

    private PropagationKeys() {
    }
}
