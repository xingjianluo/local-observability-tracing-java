package com.github.xingjianluo.localobservabilitytracingjava.web;

import com.github.xingjianluo.localobservabilitytracingjava.exception.BusinessRejectionException;
import com.github.xingjianluo.localobservabilitytracingjava.exception.DownstreamException;
import com.github.xingjianluo.localobservabilitytracingjava.exception.RequestTimeoutException;
import com.github.xingjianluo.localobservabilitytracingjava.observability.FailureType;
import com.github.xingjianluo.localobservabilitytracingjava.trace.TraceContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把各类异常映射为<b>不同的 HTTP 状态码</b>，调用方可直接据此区分失败原因；
 * 响应体与响应头都带 traceId，方便拿标识回来排查。
 *
 * <p>注意：这里只负责"对外长什么样"，异常仍向上抛给 TraceFilter，
 * 由过滤器做唯一的链路结束与指标统计，避免一次失败被统计两遍。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 供过滤器在不走 advice 的场景（如过滤器链异常）复用的状态码映射。 */
    public static HttpStatus statusFor(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 32) {
            if (current instanceof RequestTimeoutException) {
                return HttpStatus.GATEWAY_TIMEOUT;
            }
            if (current instanceof DownstreamException) {
                return HttpStatus.BAD_GATEWAY;
            }
            if (current instanceof BusinessRejectionException) {
                return HttpStatus.UNPROCESSABLE_ENTITY;
            }
            current = current.getCause();
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    public static FailureType typeFor(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 32) {
            if (current instanceof RequestTimeoutException) {
                return FailureType.TIMEOUT;
            }
            if (current instanceof DownstreamException) {
                return FailureType.DOWNSTREAM_FAILURE;
            }
            if (current instanceof BusinessRejectionException) {
                return FailureType.BUSINESS_REJECTION;
            }
            current = current.getCause();
        }
        return FailureType.SYSTEM_ERROR;
    }

    @ExceptionHandler(BusinessRejectionException.class)
    public Map<String, Object> handleBusiness(BusinessRejectionException ex,
                                              HttpServletRequest request,
                                              HttpServletResponse response) {
        request.setAttribute(TraceFilter.ATTRIBUTE_HANDLED_ERROR, ex);
        response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
        return body(FailureType.BUSINESS_REJECTION, ex.getMessage(), TraceContextHolder.currentTraceId());
    }

    @ExceptionHandler(DownstreamException.class)
    public Map<String, Object> handleDownstream(DownstreamException ex,
                                                HttpServletRequest request,
                                                HttpServletResponse response) {
        request.setAttribute(TraceFilter.ATTRIBUTE_HANDLED_ERROR, ex);
        response.setStatus(HttpStatus.BAD_GATEWAY.value());
        return body(FailureType.DOWNSTREAM_FAILURE, ex.getMessage(), TraceContextHolder.currentTraceId());
    }

    @ExceptionHandler(RequestTimeoutException.class)
    public Map<String, Object> handleTimeout(RequestTimeoutException ex,
                                             HttpServletRequest request,
                                             HttpServletResponse response) {
        request.setAttribute(TraceFilter.ATTRIBUTE_HANDLED_ERROR, ex);
        response.setStatus(HttpStatus.GATEWAY_TIMEOUT.value());
        return body(FailureType.TIMEOUT, ex.getMessage(), TraceContextHolder.currentTraceId());
    }

    @ExceptionHandler(Exception.class)
    public Map<String, Object> handleSystem(Exception ex,
                                            HttpServletRequest request,
                                            HttpServletResponse response) {
        request.setAttribute(TraceFilter.ATTRIBUTE_HANDLED_ERROR, ex);
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        return body(FailureType.SYSTEM_ERROR, ex.getMessage(), TraceContextHolder.currentTraceId());
    }

    static Map<String, Object> body(FailureType type, String message, String traceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", traceId);
        body.put("failureType", type.name());
        body.put("message", message);
        return body;
    }
}
