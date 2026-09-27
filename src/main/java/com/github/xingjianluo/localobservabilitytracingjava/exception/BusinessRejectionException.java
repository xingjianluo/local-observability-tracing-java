package com.github.xingjianluo.localobservabilitytracingjava.exception;

/** 业务规则拒绝（非系统故障，需要单独统计）。 */
public class BusinessRejectionException extends RuntimeException {

    public BusinessRejectionException(String message) {
        super(message);
    }
}
