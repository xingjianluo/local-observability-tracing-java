package com.github.xingjianluo.localobservabilitytracingjava.service;

/** Business rule rejected the request (e.g. validation, state conflict). */
public class BusinessRejectedException extends RuntimeException {
    public BusinessRejectedException(String message) {
        super(message);
    }
}
