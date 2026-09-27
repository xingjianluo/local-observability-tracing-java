package com.github.xingjianluo.localobservabilitytracingjava.web;

import com.github.xingjianluo.localobservabilitytracingjava.metrics.FailureCategory;
import com.github.xingjianluo.localobservabilitytracingjava.metrics.RequestMetrics;
import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceContext;
import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceEvent;
import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceRecorder;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the correlation id for each incoming request: reuses the upstream
 * {@code X-Trace-Id} header when present, otherwise generates one. The id
 * is echoed on the response so callers can reproduce issues.
 *
 * <p>Times the request and records metrics exactly once per request. For
 * async requests the recording is deferred to an {@link AsyncListener} so
 * timeouts and async failures are classified correctly; a request
 * attribute guards against double counting across timeout/complete/error
 * callbacks.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** Request attribute used by the exception handler to pass the category. */
    public static final String FAILURE_CATEGORY_ATTR = "observe.failureCategory";
    /** Request attribute carrying the trace id for error/async dispatch threads. */
    public static final String TRACE_ID_ATTR = "observe.traceId";
    private static final String METRICS_RECORDED_ATTR = "observe.metricsRecorded";

    private final RequestMetrics metrics;
    private final TraceRecorder recorder;

    public TraceIdFilter(RequestMetrics metrics, TraceRecorder recorder) {
        this.metrics = metrics;
        this.recorder = recorder;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // Async re-dispatch must not re-bind or re-count the request.
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String incoming = request.getHeader(TraceContext.HEADER);
        if (incoming != null && !incoming.isBlank()) {
            TraceContext.set(incoming);
        } else {
            TraceContext.getOrCreate();
        }
        String traceId = TraceContext.get();
        request.setAttribute(TRACE_ID_ATTR, traceId);
        response.setHeader(TraceContext.HEADER, traceId);

        long startNanos = System.nanoTime();
        recorder.record(new TraceEvent(traceId, "request-received",
                System.currentTimeMillis(), null, "START",
                request.getMethod() + " " + request.getRequestURI()));
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (request.isAsyncStarted()) {
                // Defer to async callbacks; the outcome is not known yet.
                request.getAsyncContext().addListener(
                        new MetricsAsyncListener(request, response, traceId, startNanos));
            } else {
                complete(request, response, traceId, startNanos, null);
            }
            TraceContext.clear();
        }
    }

    private void complete(HttpServletRequest request, HttpServletResponse response,
            String traceId, long startNanos, FailureCategory forcedCategory) {
        long durationMillis = (System.nanoTime() - startNanos) / 1_000_000;
        FailureCategory category = recordMetricsOnce(request, durationMillis, forcedCategory);
        String outcome = category != null ? "FAILURE:" + category.name()
                : (response.getStatus() >= 500 ? "FAILURE:SYSTEM_ERROR" : "SUCCESS");
        recorder.record(new TraceEvent(traceId, "request-completed",
                System.currentTimeMillis(), durationMillis, outcome,
                "status=" + response.getStatus()));
    }

    /** Records metrics at most once per request; returns the category or null. */
    private FailureCategory recordMetricsOnce(HttpServletRequest request,
            long durationMillis, FailureCategory forcedCategory) {
        if (request.getAttribute(METRICS_RECORDED_ATTR) != null) {
            return (FailureCategory) request.getAttribute(FAILURE_CATEGORY_ATTR);
        }
        request.setAttribute(METRICS_RECORDED_ATTR, Boolean.TRUE);
        FailureCategory category = forcedCategory != null ? forcedCategory
                : (FailureCategory) request.getAttribute(FAILURE_CATEGORY_ATTR);
        if (category == null
                && request.getAttribute("jakarta.servlet.error.exception") != null) {
            category = FailureCategory.SYSTEM_ERROR;
        }
        String route = request.getMethod() + " " + request.getRequestURI();
        if (category != null) {
            metrics.recordFailure(route, durationMillis, category);
        } else {
            metrics.recordSuccess(route, durationMillis);
        }
        return category;
    }

    private final class MetricsAsyncListener implements AsyncListener {
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final String traceId;
        private final long startNanos;

        MetricsAsyncListener(HttpServletRequest request, HttpServletResponse response,
                String traceId, long startNanos) {
            this.request = request;
            this.response = response;
            this.traceId = traceId;
            this.startNanos = startNanos;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            complete(request, response, traceId, startNanos, null);
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            complete(request, response, traceId, startNanos, FailureCategory.TIMEOUT);
        }

        @Override
        public void onError(AsyncEvent event) {
            complete(request, response, traceId, startNanos, FailureCategory.SYSTEM_ERROR);
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            // A new async cycle keeps the same listener registration.
        }
    }
}
