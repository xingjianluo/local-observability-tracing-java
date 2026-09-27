package com.github.xingjianluo.localobservabilitytracingjava.web;

import com.github.xingjianluo.localobservabilitytracingjava.observability.FailureType;
import com.github.xingjianluo.localobservabilitytracingjava.observability.RequestRecord;
import com.github.xingjianluo.localobservabilitytracingjava.observability.TraceRecorder;
import com.github.xingjianluo.localobservabilitytracingjava.trace.PropagationKeys;
import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceContext;
import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP 请求链路总入口过滤器。
 *
 * <p>职责（唯一结束点原则）：
 * <ol>
 *   <li>取上游 {@code X-Trace-Id} 头：有则沿用，无则生成；同线程重入不新造；</li>
 *   <li>traceId 写入 MDC（日志带标识）和响应头（调用方可拿到）；</li>
 *   <li>开始/结束链路记录；异常无论被 {@code @RestControllerAdvice} 处理还是直接抛出，
 *       都在这里通过请求属性汇合后统一 end，保证一次请求失败只统计一次；</li>
 *   <li>未被 advice 处理的异常在这里兜底成 500 JSON，不让异常穿透到容器。</li>
 * </ol>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {

    /** advice 处理完异常后把异常回传到该请求属性，供过滤器做统一结束。 */
    public static final String ATTRIBUTE_HANDLED_ERROR = TraceFilter.class.getName() + ".handledError";

    private static final Logger log = LoggerFactory.getLogger(TraceFilter.class);

    private final TraceRecorder recorder;

    public TraceFilter(TraceRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String incoming = request.getHeader(PropagationKeys.TRACE_ID_HEADER);
        // 重入安全：Tomcat 工作线程已被上一请求 finally 清理，此处必然是干净栈；
        // 即便框架内部有嵌套过滤，getOrCreate 也会沿用已有上下文，不产生新 ID。
        TraceContext context = TraceContextHolder.getOrCreate(incoming);
        String traceId = context.traceId();
        response.setHeader(PropagationKeys.TRACE_ID_HEADER, traceId);

        boolean reused = !traceId.equals(incoming);
        if (reused && incoming != null && !incoming.isBlank()) {
            log.debug("incoming trace id accepted: {}", traceId);
        }

        String entry = "http:" + request.getMethod() + " " + request.getRequestURI();
        RequestRecord record = recorder.start(entry);
        Throwable failure = null;
        try {
            filterChain.doFilter(request, response);
            Object handled = request.getAttribute(ATTRIBUTE_HANDLED_ERROR);
            if (handled instanceof Throwable t) {
                failure = t;
            }
        } catch (Throwable ex) {
            failure = unwrap(ex);
            if (!response.isCommitted()) {
                writeFallbackError(response, failure);
            }
        } finally {
            // 统一、且只有这一处结束统计；end 内部 CAS 防重复。
            RequestRecord ended = recorder.end(failure);
            if (ended != null && !ended.success()) {
                FailureType type = ended.failureType();
                log.info("request classified traceId={} failureType={} durationMs={}",
                        traceId, type, ended.durationNanos() / 1_000_000L);
            }
            TraceContextHolder.clear();
        }
    }

    private static Throwable unwrap(Throwable ex) {
        if (ex instanceof ServletException && ex.getCause() != null) {
            return ex.getCause();
        }
        return ex;
    }

    /** 未经 GlobalExceptionHandler 的异常（如过滤器链内部错误）的兜底响应。 */
    private void writeFallbackError(HttpServletResponse response, Throwable error) throws IOException {
        HttpStatus status = GlobalExceptionHandler.statusFor(error);
        FailureType type = GlobalExceptionHandler.typeFor(error);
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", TraceContextHolder.currentTraceId());
        body.put("failureType", type.name());
        body.put("message", String.valueOf(error.getMessage()));
        response.getWriter().write(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body));
    }
}
