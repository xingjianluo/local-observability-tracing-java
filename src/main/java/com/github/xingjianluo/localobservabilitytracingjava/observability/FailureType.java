package com.github.xingjianluo.localobservabilitytracingjava.observability;

/**
 * 失败原因分类，互斥、每请求最多记一类，保证同一次故障只算一次。
 */
public enum FailureType {

    /** 处理超过超时阈值。 */
    TIMEOUT,

    /** 下游依赖（模拟的远程调用）失败。 */
    DOWNSTREAM_FAILURE,

    /** 业务校验/业务规则拒绝。 */
    BUSINESS_REJECTION,

    /** 未归类的系统异常。 */
    SYSTEM_ERROR,

    /** 未发生失败。 */
    NONE
}
