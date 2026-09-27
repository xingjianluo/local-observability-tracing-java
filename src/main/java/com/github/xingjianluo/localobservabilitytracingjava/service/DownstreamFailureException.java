package com.github.xingjianluo.localobservabilitytracingjava.service;

/** A downstream dependency call failed. */
public class DownstreamFailureException extends RuntimeException {
    public DownstreamFailureException(String message) {
        super(message);
    }
    public DownstreamFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
