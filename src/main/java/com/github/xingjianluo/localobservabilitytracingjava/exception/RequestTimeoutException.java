package com.github.xingjianluo.localobservabilitytracingjava.exception;

/** 处理超过本地超时阈值。 */
public class RequestTimeoutException extends RuntimeException {

    public RequestTimeoutException(String message) {
        super(message);
    }

    public RequestTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
