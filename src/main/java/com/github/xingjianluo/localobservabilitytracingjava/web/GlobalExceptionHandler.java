package com.github.xingjianluo.localobservabilitytracingjava.web;

import com.github.xingjianluo.localobservabilitytracingjava.metrics.FailureCategory;
import com.github.xingjianluo.localobservabilitytracingjava.service.BusinessRejectedException;
import com.github.xingjianluo.localobservabilitytracingjava.service.DownstreamFailureException;
import com.github.xingjianluo.localobservabilitytracingjava.service.RequestTimeoutException;
import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps exceptions to stable HTTP responses and tags the request with the
 * failure category so the metrics filter can classify it exactly once.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessRejectedException.class)
    public ResponseEntity<Map<String, Object>> businessRejected(
            BusinessRejectedException ex, HttpServletRequest request) {
        return build(request, HttpStatus.UNPROCESSABLE_ENTITY,
                FailureCategory.BUSINESS_REJECTED, ex.getMessage());
    }

    @ExceptionHandler(DownstreamFailureException.class)
    public ResponseEntity<Map<String, Object>> downstreamFailure(
            DownstreamFailureException ex, HttpServletRequest request) {
        return build(request, HttpStatus.BAD_GATEWAY,
                FailureCategory.DOWNSTREAM_FAILURE, ex.getMessage());
    }

    @ExceptionHandler(RequestTimeoutException.class)
    public ResponseEntity<Map<String, Object>> timeout(
            RequestTimeoutException ex, HttpServletRequest request) {
        return build(request, HttpStatus.SERVICE_UNAVAILABLE,
                FailureCategory.TIMEOUT, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> systemError(
            Exception ex, HttpServletRequest request) {
        return build(request, HttpStatus.INTERNAL_SERVER_ERROR,
                FailureCategory.SYSTEM_ERROR, ex.getMessage());
    }

    private ResponseEntity<Map<String, Object>> build(HttpServletRequest request,
            HttpStatus status, FailureCategory category, String message) {
        request.setAttribute(TraceIdFilter.FAILURE_CATEGORY_ATTR, category);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", resolveTraceId(request));
        body.put("status", status.value());
        body.put("failureCategory", category.name());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }

    /** Error dispatches may run on threads without the context bound. */
    private static String resolveTraceId(HttpServletRequest request) {
        Object attr = request.getAttribute(TraceIdFilter.TRACE_ID_ATTR);
        if (attr instanceof String id && !id.isBlank()) {
            return id;
        }
        return TraceContext.get();
    }
}
