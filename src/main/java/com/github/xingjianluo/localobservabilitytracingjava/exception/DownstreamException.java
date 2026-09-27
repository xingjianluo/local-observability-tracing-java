package com.github.xingjianluo.localobservabilitytracingjava.exception;

/** 下游依赖调用失败。 */
public class DownstreamException extends RuntimeException {

    public DownstreamException(String message) {
        super(message);
    }

    public DownstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
