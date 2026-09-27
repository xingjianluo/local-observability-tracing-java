package com.github.xingjianluo.localobservabilitytracingjava.observability;

/** 链路上单个环节（span）的最终状态。 */
public enum TraceStageStatus {
    RUNNING,
    SUCCESS,
    FAILURE
}
