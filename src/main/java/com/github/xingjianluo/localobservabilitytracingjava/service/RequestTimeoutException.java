package com.github.xingjianluo.localobservabilitytracingjava.service;

/** An operation exceeded its time budget. */
public class RequestTimeoutException extends RuntimeException {
    public RequestTimeoutException(String message) {
        super(message);
    }
}
